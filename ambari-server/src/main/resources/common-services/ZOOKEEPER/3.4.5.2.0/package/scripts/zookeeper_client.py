"""
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

Ambari Agent

"""

import sys
from resource_management import *
from resource_management.libraries.functions.version import compare_versions, format_hdp_stack_version
from resource_management.libraries.functions.format import format

from zookeeper import zookeeper

class ZookeeperClient(Script):

  def get_stack_to_component(self):
    return {"HDP": "zookeeper-client"}

  def install(self, env):
    self.install_packages(env)
    self.configure(env)

  def configure(self, env):
    import params
    env.set_params(params)

    zookeeper(type='client')

  def pre_rolling_restart(self, env):
    Logger.info("Executing Rolling Upgrade pre-restart")
    import params
    env.set_params(params)

    if params.version and compare_versions(format_hdp_stack_version(params.version), '2.2.0.0') >= 0:
      Execute(format("hdp-select set zookeeper-client {version}"))

  def start(self, env, rolling_restart=False):
    import params
    env.set_params(params)
    self.configure(env)
    pass

  def stop(self, env, rolling_restart=False):
    import params
    env.set_params(params)
    pass

  def status(self, env):
    raise ClientComponentHasNoStatus()

if __name__ == "__main__":
  ZookeeperClient().execute()