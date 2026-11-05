<!--
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
//-->

# Rust ZooKeeper client design

- [specification.md](specification.md) is the design to build from: a native Rust ZooKeeper client and C drop-in libraries for `libzookeeper_mt` and `libzookeeper_st`.
- [c-client-bugs.md](c-client-bugs.md) reports the bugs found in the existing C client, written so they can be patched there independently.
- [research/](research/) holds the earlier working documents. They are kept for their findings and are not the design. Where they disagree with the specification, the specification is right.
