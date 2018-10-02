package org.apache.zookeeper.common;

import javax.net.ssl.SSLContext;

/**
 * An interface for providing a custom {@link SSLContext} object to {@link X509Util} using {@link ZKConfig#SSL_CLIENT_CONTEXT}
 */
public interface ZKClientSSLContext {

    /**
     * Returns an {@link SSLContext} for use within the {@link X509Util}
     *
     * @return {@link SSLContext} for use within {@link X509Util}
     * @throws X509Exception.SSLContextException if {@link SSLContext} cannot be created
     */
    SSLContext getSSLContext() throws X509Exception.SSLContextException;

}
