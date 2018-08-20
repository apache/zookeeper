package org.apache.zookeeper.common;

/**
 * Represents a type of key pair used for X509 certs in tests. The two options are RSA or EC (elliptic curve).
 */
public enum X509KeyType {
    RSA, EC;
}
