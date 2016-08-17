package org.apache.zookeeper.common;

import javax.net.ssl.SSLContext;

import org.apache.zookeeper.common.X509Exception.SSLContextException;

public interface ZKSSLContext {
	
	SSLContext getSSLContext() throws SSLContextException;

}
