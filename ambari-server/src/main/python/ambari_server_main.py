#!/usr/bin/env python

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''
import getpass

import os
import stat
import subprocess
import tempfile
import sys

from ambari_commons.exceptions import FatalException
from ambari_commons.logging_utils import get_debug_mode, get_verbose, print_warning_msg, print_info_msg, \
  set_debug_mode_from_options
from ambari_commons.os_check import OSConst
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl
from ambari_commons.os_utils import is_root
from ambari_server.dbConfiguration import ensure_dbms_is_running, ensure_jdbc_driver_is_installed
from ambari_server.serverConfiguration import configDefaults, find_jdk, get_ambari_classpath, get_ambari_properties, \
  get_conf_dir, get_is_persisted, get_is_secure, get_java_exe_path, get_original_master_key, read_ambari_user, \
  PID_NAME, RESOURCES_DIR_DEFAULT, RESOURCES_DIR_PROPERTY, SECURITY_KEY_ENV_VAR_NAME, SECURITY_MASTER_KEY_LOCATION, \
  SETUP_OR_UPGRADE_MSG, check_database_name_property, parse_properties_file
from ambari_server.serverUtils import is_server_runing, refresh_stack_hash
from ambari_server.setupHttps import get_fqdn
from ambari_server.setupSecurity import save_master_key
from ambari_server.utils import check_reverse_lookup, save_pid, locate_file, looking_for_pid, wait_for_pid, \
  save_main_pid_ex, check_exitcode


# debug settings
SERVER_START_DEBUG = False
SUSPEND_START_MODE = False

# server commands
ambari_provider_module_option = ""
ambari_provider_module = os.environ.get('AMBARI_PROVIDER_MODULE')
if ambari_provider_module is not None:
  ambari_provider_module_option = "-Dprovider.module.class=" + \
                                  ambari_provider_module + " "

jvm_args = os.getenv('AMBARI_JVM_ARGS', '-Xms512m -Xmx2048m')

SERVER_START_CMD = "{0} " \
    "-server -XX:NewRatio=3 " \
    "-XX:+UseConcMarkSweepGC " + \
    "-XX:-UseGCOverheadLimit -XX:CMSInitiatingOccupancyFraction=60 " + \
    "{1} {2} " \
    "-cp {3} "\
    "org.apache.ambari.server.controller.AmbariServer " \
    "> {4} 2>&1 || echo $? > {5} &"
SERVER_START_CMD_DEBUG = "{0} " \
    "-server -XX:NewRatio=2 " \
    "-XX:+UseConcMarkSweepGC " + \
    "{1} {2} " \
    " -Xdebug -Xrunjdwp:transport=dt_socket,address=5005," \
    "server=y,suspend={6} " \
    "-cp {3} " + \
    "org.apache.ambari.server.controller.AmbariServer " \
    "> {4} 2>&1 || echo $? > {5} &"

SERVER_START_CMD_WINDOWS = "{0} " \
    "-server -XX:NewRatio=3 " \
    "-XX:+UseConcMarkSweepGC " + \
    "-XX:-UseGCOverheadLimit -XX:CMSInitiatingOccupancyFraction=60 " \
    "{1} {2} " \
    "-cp {3} " \
    "org.apache.ambari.server.controller.AmbariServer"
SERVER_START_CMD_DEBUG_WINDOWS = "{0} " \
    "-server -XX:NewRatio=2 " \
    "-XX:+UseConcMarkSweepGC " \
    "{1} {2} " \
    "-Xdebug -Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend={4} " \
    "-cp {3}" \
    "org.apache.ambari.server.controller.AmbariServer"

SERVER_INIT_TIMEOUT = 5
SERVER_START_TIMEOUT = 10

SERVER_PING_TIMEOUT_WINDOWS = 5
SERVER_PING_ATTEMPTS_WINDOWS = 4

SERVER_SEARCH_PATTERN = "org.apache.ambari.server.controller.AmbariServer"

EXITCODE_NAME = "ambari-server.exitcode"

AMBARI_SERVER_DIE_MSG = "Ambari Server java process died with exitcode {0}. Check {1} for more information."

# linux open-file limit
ULIMIT_OPEN_FILES_KEY = 'ulimit.open.files'
ULIMIT_OPEN_FILES_DEFAULT = 10000


def get_resources_location(properties):
  res_location = properties[RESOURCES_DIR_PROPERTY]
  if res_location is None:
    res_location = RESOURCES_DIR_DEFAULT
  return res_location


@OsFamilyFuncImpl(OSConst.WINSRV_FAMILY)
def ensure_can_start_under_current_user(ambari_user):
  #Ignore the requirement to run as root. In Windows, by default the child process inherits the security context
  # and the environment from the parent process.
  return ""

@OsFamilyFuncImpl(OsFamilyImpl.DEFAULT)
def ensure_can_start_under_current_user(ambari_user):
  current_user = getpass.getuser()
  if ambari_user is None:
    err = "Unable to detect a system user for Ambari Server.\n" + SETUP_OR_UPGRADE_MSG
    raise FatalException(1, err)
  if current_user != ambari_user and not is_root():
    err = "Unable to start Ambari Server as user {0}. Please either run \"ambari-server start\" " \
          "command as root, as sudo or as user \"{1}\"".format(current_user, ambari_user)
    raise FatalException(1, err)
  return current_user


@OsFamilyFuncImpl(OSConst.WINSRV_FAMILY)
def ensure_server_security_is_configured():
  pass

@OsFamilyFuncImpl(OsFamilyImpl.DEFAULT)
def ensure_server_security_is_configured():
  if not is_root():
    print "Unable to check iptables status when starting without root privileges."
    print "Please do not forget to disable or adjust iptables if needed"


def get_ulimit_open_files(properties):
  open_files_val = properties[ULIMIT_OPEN_FILES_KEY]
  open_files = int(open_files_val) if (open_files_val and int(open_files_val) > 0) else ULIMIT_OPEN_FILES_DEFAULT
  return open_files

@OsFamilyFuncImpl(OSConst.WINSRV_FAMILY)
def generate_child_process_param_list(ambari_user, current_user, java_exe, class_path, debug_start, suspend_mode):
  conf_dir = class_path
  if class_path.find(' ') != -1:
    conf_dir = '"' + class_path + '"'
  command_base = SERVER_START_CMD_DEBUG_WINDOWS if debug_start else SERVER_START_CMD_WINDOWS
  command = command_base.format(
      java_exe,
      ambari_provider_module_option,
      jvm_args,
      conf_dir,
      suspend_mode)
  environ = os.environ.copy()
  return (command, environ)

@OsFamilyFuncImpl(OsFamilyImpl.DEFAULT)
def generate_child_process_param_list(ambari_user, current_user, java_exe, class_path, debug_start, suspend_mode):
  from ambari_commons.os_linux import ULIMIT_CMD

  properties = get_ambari_properties()

  isSecure = get_is_secure(properties)
  (isPersisted, masterKeyFile) = get_is_persisted(properties)
  environ = os.environ.copy()
  # Need to handle master key not persisted scenario
  if isSecure and not masterKeyFile:
    prompt = False
    masterKey = environ.get(SECURITY_KEY_ENV_VAR_NAME)

    if masterKey is not None and masterKey != "":
      pass
    else:
      keyLocation = environ.get(SECURITY_MASTER_KEY_LOCATION)

      if keyLocation is not None:
        try:
          # Verify master key can be read by the java process
          with open(keyLocation, 'r'):
            pass
        except IOError:
          print_warning_msg("Cannot read Master key from path specified in "
                            "environemnt.")
          prompt = True
      else:
        # Key not provided in the environment
        prompt = True

    if prompt:
      import pwd

      masterKey = get_original_master_key(properties)
      tempDir = tempfile.gettempdir()
      tempFilePath = tempDir + os.sep + "masterkey"
      save_master_key(masterKey, tempFilePath, True)
      if ambari_user != current_user:
        uid = pwd.getpwnam(ambari_user).pw_uid
        gid = pwd.getpwnam(ambari_user).pw_gid
        os.chown(tempFilePath, uid, gid)
      else:
        os.chmod(tempFilePath, stat.S_IREAD | stat.S_IWRITE)

      if tempFilePath is not None:
        environ[SECURITY_MASTER_KEY_LOCATION] = tempFilePath

  command_base = SERVER_START_CMD_DEBUG if debug_start else SERVER_START_CMD

  ulimit_cmd = "%s %s" % (ULIMIT_CMD, str(get_ulimit_open_files(properties)))
  command = command_base.format(java_exe,
          ambari_provider_module_option,
          jvm_args,
          class_path,
          configDefaults.SERVER_OUT_FILE,
          os.path.join(configDefaults.PID_DIR, EXITCODE_NAME),
          suspend_mode)

  # required to start properly server instance
  os.chdir(configDefaults.ROOT_FS_PATH)

  #For properly daemonization server should be started using shell as parent
  param_list = [locate_file('sh', '/bin'), "-c"]
  if is_root() and ambari_user != "root":
    # To inherit exported environment variables (especially AMBARI_PASSPHRASE),
    # from subprocess, we have to skip --login option of su command. That's why
    # we change dir to / (otherwise subprocess can face with 'permission denied'
    # errors while trying to list current directory
    cmd = "{ulimit_cmd} ; {su} {ambari_user} -s {sh_shell} -c '{command}'".format(ulimit_cmd=ulimit_cmd, 
                                                                                su=locate_file('su', '/bin'), ambari_user=ambari_user,
                                                                                sh_shell=locate_file('sh', '/bin'), command=command)
  else:
    cmd = "{ulimit_cmd} ; {command}".format(ulimit_cmd=ulimit_cmd, command=command)
    
  param_list.append(cmd)
  return (param_list, environ)

@OsFamilyFuncImpl(OSConst.WINSRV_FAMILY)
def wait_for_server_start(pidFile, scmStatus):
  # Wait for the HTTP port to be open
  iter_start = 0
  while iter_start < SERVER_PING_ATTEMPTS_WINDOWS and not get_fqdn(SERVER_PING_TIMEOUT_WINDOWS):
    if scmStatus is not None:
      scmStatus.reportStartPending()
    iter_start += 1

@OsFamilyFuncImpl(OsFamilyImpl.DEFAULT)
def wait_for_server_start(pidFile, scmStatus):
  #wait for server process for SERVER_START_TIMEOUT seconds
  sys.stdout.write('Waiting for server start...')
  sys.stdout.flush()

  pids = looking_for_pid(SERVER_SEARCH_PATTERN, SERVER_INIT_TIMEOUT)
  found_pids = wait_for_pid(pids, SERVER_START_TIMEOUT)

  sys.stdout.write('\n')
  sys.stdout.flush()

  if found_pids <= 0:
    exitcode = check_exitcode(os.path.join(configDefaults.PID_DIR, EXITCODE_NAME))
    raise FatalException(-1, AMBARI_SERVER_DIE_MSG.format(exitcode, configDefaults.SERVER_OUT_FILE))
  else:
    save_main_pid_ex(pids, pidFile, [locate_file('sh', '/bin'),
                                     locate_file('bash', '/bin'),
                                     locate_file('dash', '/bin')], True)


def server_process_main(options, scmStatus=None):
  # debug mode, including stop Java process at startup
  try:
    set_debug_mode_from_options(options)
  except AttributeError:
    pass

  if not check_reverse_lookup():
    print_warning_msg("The hostname was not found in the reverse DNS lookup. "
                      "This may result in incorrect behavior. "
                      "Please check the DNS setup and fix the issue.")

  check_database_name_property()
  parse_properties_file(options)

  ambari_user = read_ambari_user()
  current_user = ensure_can_start_under_current_user(ambari_user)

  print_info_msg("Ambari Server is not running...")

  jdk_path = find_jdk()
  if jdk_path is None:
    err = "No JDK found, please run the \"ambari-server setup\" " \
          "command to install a JDK automatically or install any " \
          "JDK manually to " + configDefaults.JDK_INSTALL_DIR
    raise FatalException(1, err)

  properties = get_ambari_properties()

  # Preparations
  if is_root():
    print configDefaults.MESSAGE_SERVER_RUNNING_AS_ROOT

  ensure_jdbc_driver_is_installed(options, properties)

  ensure_dbms_is_running(options, properties, scmStatus)

  if scmStatus is not None:
    scmStatus.reportStartPending()

  refresh_stack_hash(properties)

  if scmStatus is not None:
    scmStatus.reportStartPending()

  ensure_server_security_is_configured()

  if scmStatus is not None:
    scmStatus.reportStartPending()

  java_exe = get_java_exe_path()

  class_path = get_conf_dir()
  class_path = os.path.abspath(class_path) + os.pathsep + get_ambari_classpath()

  debug_mode = get_debug_mode()
  debug_start = (debug_mode & 1) or SERVER_START_DEBUG
  suspend_start = (debug_mode & 2) or SUSPEND_START_MODE
  suspend_mode = 'y' if suspend_start else 'n'

  (param_list, environ) = generate_child_process_param_list(ambari_user, current_user,
                                                 java_exe, class_path, debug_start, suspend_mode)

  if not os.path.exists(configDefaults.PID_DIR):
    os.makedirs(configDefaults.PID_DIR, 0755)

  print_info_msg("Running server: " + str(param_list))
  procJava = subprocess.Popen(param_list, env=environ)

  pidJava = procJava.pid
  if pidJava <= 0:
    procJava.terminate()
    exitcode = procJava.returncode
    exitfile = os.path.join(configDefaults.PID_DIR, EXITCODE_NAME)
    save_pid(exitcode, exitfile)

    if scmStatus is not None:
      scmStatus.reportStopPending()

    raise FatalException(-1, AMBARI_SERVER_DIE_MSG.format(exitcode, configDefaults.SERVER_OUT_FILE))
  else:
    pidfile = os.path.join(configDefaults.PID_DIR, PID_NAME)
    save_pid(pidJava, pidfile)
    print "Server PID at: "+pidfile
    print "Server out at: "+configDefaults.SERVER_OUT_FILE
    print "Server log at: "+configDefaults.SERVER_LOG_FILE

    wait_for_server_start(pidfile, scmStatus)

  if scmStatus is not None:
    scmStatus.reportStarted()

  return procJava
