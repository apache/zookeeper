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
package org.apache.hedwig.server.proxy;

import org.apache.hedwig.client.conf.ClientConfiguration;

public class ProxyConfiguration extends ClientConfiguration{

    protected static String PROXY_PORT = "proxy_port";
    protected static String MAX_MESSAGE_SIZE = "max_message_size";
    
    public int getProxyPort(){
        return conf.getInt(PROXY_PORT, 9099);
    }
    
    @Override
    public int getMaximumMessageSize() {
        return conf.getInt(MAX_MESSAGE_SIZE, 1258291); /* 1.2M */
    }
    
}
