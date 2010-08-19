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
package org.apache.hedwig.exceptions;

import java.util.Collection;

import org.apache.hedwig.protocol.PubSubProtocol.StatusCode;

@SuppressWarnings("serial")
public abstract class PubSubException extends Exception {
    protected StatusCode code;

    protected PubSubException(StatusCode code, String msg) {
        super(msg);
        this.code = code;
    }

    protected PubSubException(StatusCode code, Exception e) {
        super(e);
        this.code = code;
    }

    public static PubSubException create(StatusCode code, String msg) {
        if (code == StatusCode.CLIENT_ALREADY_SUBSCRIBED) {
            return new ClientAlreadySubscribedException(msg);
        } else if (code == StatusCode.CLIENT_NOT_SUBSCRIBED) {
            return new ClientNotSubscribedException(msg);
        } else if (code == StatusCode.MALFORMED_REQUEST) {
            return new MalformedRequestException(msg);
        } else if (code == StatusCode.NO_SUCH_TOPIC) {
            return new NoSuchTopicException(msg);
        } else if (code == StatusCode.NOT_RESPONSIBLE_FOR_TOPIC) {
            return new ServerNotResponsibleForTopicException(msg);
        } else if (code == StatusCode.SERVICE_DOWN) {
            return new ServiceDownException(msg);
        } else if (code == StatusCode.COULD_NOT_CONNECT) {
            return new CouldNotConnectException(msg);
        }
        /*
         * Insert new ones here
         */
        else if (code == StatusCode.UNCERTAIN_STATE) {
            return new UncertainStateException(msg);
        }
        // Finally the catch all exception (for unexpected error conditions)
        else {
            return new UnexpectedConditionException("Unknow status code:" + code.getNumber() + ", msg: " + msg);
        }
    }

    public StatusCode getCode() {
        return code;
    }

    public static class ClientAlreadySubscribedException extends PubSubException {
        public ClientAlreadySubscribedException(String msg) {
            super(StatusCode.CLIENT_ALREADY_SUBSCRIBED, msg);
        }
    }

    public static class ClientNotSubscribedException extends PubSubException {
        public ClientNotSubscribedException(String msg) {
            super(StatusCode.CLIENT_NOT_SUBSCRIBED, msg);
        }
    }

    public static class MalformedRequestException extends PubSubException {
        public MalformedRequestException(String msg) {
            super(StatusCode.MALFORMED_REQUEST, msg);
        }
    }

    public static class NoSuchTopicException extends PubSubException {
        public NoSuchTopicException(String msg) {
            super(StatusCode.NO_SUCH_TOPIC, msg);
        }
    }

    public static class ServerNotResponsibleForTopicException extends PubSubException {
        // Note the exception message serves as the name of the responsible host
        public ServerNotResponsibleForTopicException(String responsibleHost) {
            super(StatusCode.NOT_RESPONSIBLE_FOR_TOPIC, responsibleHost);
        }
    }

    public static class TopicBusyException extends PubSubException {
        public TopicBusyException(String msg) {
            super(StatusCode.TOPIC_BUSY, msg);
        }
    }

    public static class ServiceDownException extends PubSubException {
        public ServiceDownException(String msg) {
            super(StatusCode.SERVICE_DOWN, msg);
        }

        public ServiceDownException(Exception e) {
            super(StatusCode.SERVICE_DOWN, e);
        }
    }

    public static class CouldNotConnectException extends PubSubException {
        public CouldNotConnectException(String msg) {
            super(StatusCode.COULD_NOT_CONNECT, msg);
        }
    }

    /*
     * Insert new ones here
     */
    public static class UncertainStateException extends PubSubException {
        public UncertainStateException(String msg) {
            super(StatusCode.UNCERTAIN_STATE, msg);
        }
    }

    // The catch all exception (for unexpected error conditions)
    public static class UnexpectedConditionException extends PubSubException {
        public UnexpectedConditionException(String msg) {
            super(StatusCode.UNEXPECTED_CONDITION, msg);
        }
    }
    
    // The composite exception (for concurrent operations).
    public static class CompositeException extends PubSubException {
        private final Collection<PubSubException> exceptions;
        public CompositeException(Collection<PubSubException> exceptions) {
            super(StatusCode.COMPOSITE, "composite exception");
            this.exceptions = exceptions;
        }
        public Collection<PubSubException> getExceptions() {
            return exceptions;
        }
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(super.toString()).append('\n');
            for (PubSubException exception : exceptions)
                builder.append(exception).append('\n');
            return builder.toString();
        }
    }

    public static class ClientNotSubscribedRuntimeException extends RuntimeException {
    }

}
