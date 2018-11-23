package org.apache.zookeeper.server.quorum.exception;

public class RuntimeConfigException extends RuntimeException {

    private static final long serialVersionUID = -9025894204684855418L;

    public RuntimeConfigException() {
        super();
    }

    public RuntimeConfigException(String message) {
        super(message);
    }

}
