#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import subprocess

from pathlib import Path

class Server():
    def __init__(self, binpath):
        self.binpath = binpath
    def __enter__(self):
        subprocess.run([f'{self.binpath}', 'start'], check=True)
        return self
    def __exit__(self, type, value, traceback):
        subprocess.run([f'{self.binpath}', 'stop'], check=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', help="basepath to zk server", required=True)
    parser.add_argument('--client', help="basepath to zk client", required=True)

    args = parser.parse_args()
    
    server_basepath = Path(args.server).absolute()
    server_binpath = server_basepath / "bin" / "zkServer.sh"
    assert server_binpath.exists(), f"server binpath not exist: {server_binpath}"
    client_basepath = Path(args.client).absolute()
    client_binpath = client_basepath / "bin" / "zkCli.sh"
    assert client_binpath.exists(), f"client binpath not exist: {client_binpath}"

    with Server(server_binpath):
        subprocess.run([f'{client_binpath}', 'sync', '/'], check=True)
