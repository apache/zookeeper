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

package org.apache.zookeeper.server.watch;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory used to produce the actual watch manager based on the
 * zookeeper.watchManagerName option.
 */
public class WatchManagerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(WatchManagerFactory.class);

    public static final String ZOOKEEPER_WATCH_MANAGER_NAME = "zookeeper.watchManagerName";

    public static IWatchManager createWatchManager() throws IOException {
        String watchManagerName = System.getProperty(ZOOKEEPER_WATCH_MANAGER_NAME);
        if (watchManagerName == null) {
            watchManagerName = WatchManager.class.getName();
        }
        try {
            IWatchManager watchManager = (IWatchManager) Class.forName(watchManagerName).getConstructor().newInstance();
            LOG.info("Using {} as watch manager", watchManagerName);
            return watchManager;
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate " + watchManagerName, e);
            throw ioe;
        }
    }

}
