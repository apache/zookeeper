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

package org.apache.zookeeper.server.auth.znode.groupacl;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of ClientUriDomainMappingHelper that stores the mapping inside the ZK server
 * as a hierarchy of ZNodes.
 *
 * Note that the mapping metadata itself will be stored in ZKDatabase as a ZNode tree and will also
 * be cached inside this helper object. This helper object watches the clientUri-domain ZNodes and
 * updates the internal Map accordingly.
 *
 * The following illustrates the ZNode hierarchy:
 * . (root)
 * └── _CLIENT_URI_DOMAIN_MAPPING (mapping root path)
 *     ├── bar (application domain)
 *     │   ├── bar0 (client URI)
 *     │   └── bar1 (client URI)
 *     └── foo (application domain)
 *         ├── foo1 (client URI)
 *         ├── foo2 (client URI)
 *         └── foo3 (client URI)
 *
 * Note: It is not expected that there would be too many distinct client URIs so as to overwhelm
 * heap usage.
 */
public class ZkClientUriDomainMappingHelper implements Watcher, ClientUriDomainMappingHelper {

  private static final Logger LOG = LoggerFactory.getLogger(ZkClientUriDomainMappingHelper.class);

  private static final String CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH =
      "zookeeper.znode.groupacl.clientUriDomainMappingRootPath";

  private final ZooKeeperServer zks;
  private final String rootPath;
  private final Map<String, Set<String>> clientUriToDomainNames = new HashMap<>();

  public ZkClientUriDomainMappingHelper(ZooKeeperServer zks) {
    this.zks = zks;

    this.rootPath = System.getProperty(CLIENT_URI_DOMAIN_MAPPING_ROOT_PATH);
    if (rootPath == null) {
      throw new IllegalStateException(
          "ZkClientUriDomainMappingHelper::ClientUriDomainMapping root path config is not set!");
    }

    if (zks.getZKDatabase().getNode(rootPath) == null) {
      throw new IllegalStateException(
          "ZkClientUriDomainMappingHelper::ClientUriDomainMapping root path does not exist!");
    }

    addWatches();
    parseZNodeMapping();
  }

  /**
   * Install a persistent recursive watch on the root path.
   */
  private void addWatches() {
    zks.getZKDatabase().addWatch(rootPath, this, ZooDefs.AddWatchModes.persistentRecursive);
  }

  /**
   * Read ZNodes under the root path and populates clientUriToDomainNames.
   * Note: this is not thread-safe nor atomic; however, we do not need such strong guarantee with
   * this read operation.
   *
   * Also, note that this is a purely in-memory operation, so re-parsing the entire tree should not
   * be a big overhead considering how infrequently the mapping is supposed to be changed.
   */
  private void parseZNodeMapping() {
    clientUriToDomainNames.clear();
    try {
      List<String> domainNames = zks.getZKDatabase().getChildren(rootPath, null, null);
      domainNames.forEach(domainName -> {
        try {
          List<String> clientUris =
              zks.getZKDatabase().getChildren(rootPath + "/" + domainName, null, null);
          clientUris.forEach(
              clientUri -> clientUriToDomainNames.computeIfAbsent(clientUri, k -> new HashSet<>())
                  .add(domainName));
        } catch (KeeperException.NoNodeException e) {
          LOG.warn(
              "ZkClientUriDomainMappingHelper::parseZNodeMapping(): No clientUri ZNodes found under domain: {}",
              domainName);
        }
      });
    } catch (KeeperException.NoNodeException e) {
      LOG.warn(
          "ZkClientUriDomainMappingHelper::parseZNodeMapping(): No application domain ZNodes found in root path: {}",
          rootPath);
    }
  }

  @Override
  public void process(WatchedEvent event) {
    parseZNodeMapping();
  }

  @Override
  public Set<String> getDomains(String clientUri) {
    return clientUriToDomainNames.getOrDefault(clientUri, Collections.emptySet());
  }
}
