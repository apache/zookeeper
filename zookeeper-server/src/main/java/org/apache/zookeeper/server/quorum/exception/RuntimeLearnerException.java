package org.apache.zookeeper.server.quorum.exception;

public class RuntimeLearnerException extends RuntimeException {

    private static final long serialVersionUID = 9164642202468819481L;

    public RuntimeLearnerException() {
        super();
    }

    public RuntimeLearnerException(Throwable cause) {
        super(cause);
    }

    public RuntimeLearnerException(String message) {
        super(message);
    }

}
