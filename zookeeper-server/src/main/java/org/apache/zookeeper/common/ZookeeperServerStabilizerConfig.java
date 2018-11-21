/**
 * 
 */
package org.apache.zookeeper.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZookeeperServerStabilizerConfig {
	protected static final Logger LOG = LoggerFactory.getLogger(ZookeeperServerStabilizerConfig.class);

	public static final String GLOBAL_OUTSTANDING_LIMIT = "zookeeper.globalOutstandingLimit";

	private int globalOutstandingLimit =1000;

	
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
