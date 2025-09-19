/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Module to bundle `zookeeper-client`, `zookeeper-server` and others together.
 *
 * <p>This module exists for two purposes:
 * <ol>
 *     <li>keep compatibility with old zookeeper artifact</li>
 *     <li>build osgi bundle jar</li>
 * </ol>
 *
 * <p><b>DON'T DELETE THIS FILE.</b>
 *
 * <p>`maven-bundle-plugin` does not include classes if there is no source code. I have no idea why.
 *
 * <p>As far as I know OSGI could not tolerate overlapping package paths cross bundles. But we have
 * jute classes `FileHeader`, `LearnerInfo`, `QuorumAuthPacket` and `QuorumPacket` in `zookeeper-jute`
 * under package `o.a.zookeeper.server` which is exported by `zookeeper` but not `zookeeper-jute`.
 *
 * <p>We have to include above classes to this bundle without bring other classes from `zookeeper-jute`.
 * `Embed-Dependency` can't handle this as we can not embed `zookeeper-jute` while jar of `zookeeper-server`
 * does not contain those classes. So we have to resort to exclude/include from `Export-Package` as before.
 * But, it does not work if there is no source code, so we have this file for OSGI bundle.
 */
package org.apache.zookeeper;
