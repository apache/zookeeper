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

package org.apache.zookeeper.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZookeeperServerStabilizerConfig {
	protected static final Logger LOG = LoggerFactory.getLogger(ZookeeperServerStabilizerConfig.class);

	public static final String GLOBAL_OUTSTANDING_LIMIT = "zookeeper.globalOutstandingLimit";

	private int globalOutstandingLimit = 1000;

	public ZookeeperServerStabilizerConfig() {
		setGlobalOutstandingLimit(Integer.getInteger(GLOBAL_OUTSTANDING_LIMIT, 1000));
		LOG.info("{} = {}", GLOBAL_OUTSTANDING_LIMIT, getGlobalOutstandingLimit());
	}

	public int getGlobalOutstandingLimit() {
		return globalOutstandingLimit;
	}

	public void setGlobalOutstandingLimit(int globalOutstandingLimit) {
		this.globalOutstandingLimit = globalOutstandingLimit;
	}

}
