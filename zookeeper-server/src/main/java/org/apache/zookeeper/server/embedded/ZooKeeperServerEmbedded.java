package org.apache.zookeeper.server.embedded;

/**
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

import java.nio.file.Path;
import java.util.Properties;

/**
 * TODO.
 */
public interface ZooKeeperServerEmbedded extends AutoCloseable {
    public static class ZookKeeperServerEmbeddedBuilder {
        
        private Path baseDir;
        private Properties configuration;
        private ExitHandler exitHandler = ExitHandler.EXIT();

        public ZookKeeperServerEmbeddedBuilder baseDir(Path baseDir) {
            this.baseDir = baseDir;
            return this;
        }
        
        public ZookKeeperServerEmbeddedBuilder configuration(Properties configuration) {
            this.configuration = configuration;
            return this;
        }
        
        public ZookKeeperServerEmbeddedBuilder exitHandler(ExitHandler exitHandler) {
            this.exitHandler = exitHandler;
            return this;
        }
        
        public ZooKeeperServerEmbedded build() throws Exception {
            if (baseDir == null) {
                throw new IllegalArgumentException();
            }
            if (configuration == null) {
                throw new IllegalArgumentException();
            }
            return new ZooKeeperServerEmbeddedImpl(configuration, baseDir, exitHandler);
        }
    }
 
    public static ZookKeeperServerEmbeddedBuilder builder() {
        return new ZookKeeperServerEmbeddedBuilder();
    }
    
    void start() throws Exception;
    
    @Override
    void close();

}
