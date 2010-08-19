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
package org.apache.hedwig.conf;

import java.net.URL;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

public abstract class AbstractConfiguration {
    protected CompositeConfiguration conf;

    protected AbstractConfiguration() {
        conf = new CompositeConfiguration();
    }

    /**
     * You can load configurations in precedence order. The first one takes
     * precedence over any loaded later.
     * 
     * @param confURL
     */
    public void loadConf(URL confURL) throws ConfigurationException {
        Configuration loadedConf = new PropertiesConfiguration(confURL);
        conf.addConfiguration(loadedConf);

    }
}
