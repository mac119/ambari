#!/usr/bin/env python
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

"""

from resource_management import *
from oozie import oozie

class OozieClient(Script):
  def install(self, env):
    # client checks env var to determine if it is installed
    if not os.environ.has_key("OOZIE_HOME"):
      self.install_packages(env)
    self.configure(env)

  def configure(self, env):
    import params
    env.set_params(params)
    oozie()

  def status(self, env):
    raise ClientComponentHasNoStatus()

if __name__ == "__main__":
  OozieClient().execute()
