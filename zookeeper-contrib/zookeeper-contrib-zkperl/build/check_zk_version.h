/* Net::ZooKeeper - Perl extension for Apache ZooKeeper
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* keep in sync with Makefile.PL */
#if !defined(ZOO_MAJOR_VERSION) || ZOO_MAJOR_VERSION != 3 || \
    !defined(ZOO_MINOR_VERSION) || ZOO_MINOR_VERSION < 1 || \
    !defined(ZOO_PATCH_VERSION) || \
    (ZOO_MINOR_VERSION == 1 && ZOO_PATCH_VERSION < 1)
#error "Net::ZooKeeper requires at least ZooKeeper version 3.1.1"
#endif

