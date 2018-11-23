package org.apache.zookeeper.server.quorum.exception;

public class RuntimeNoReachableHostException extends RuntimeException {

    private static final long serialVersionUID = -8118892361652577058L;

    public RuntimeNoReachableHostException() {
        super();
    }

    public RuntimeNoReachableHostException(String message) {
        super(message);
    }

}
