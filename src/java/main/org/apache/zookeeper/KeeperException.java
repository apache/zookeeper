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

package org.apache.zookeeper;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("serial")
public abstract class KeeperException extends Exception {

    /**
     * All non-specific keeper exceptions should be constructed via
     * this factory method in order to guarantee consistency in error
     * codes and such.  If you know the error code, then you should
     * construct the special purpose exception directly.  That will
     * allow you to have the most specific possible declarations of
     * what exceptions might actually be thrown.
     *
     * @param code The error code.
     * @param path The ZooKeeper path being operated on.
     * @return The specialized exception, presumably to be thrown by
     *  the caller.
     */
    public static KeeperException create(Code code, String path) {
        KeeperException r = create(code);
        r.path = path;
        return r;
    }

    /**
     * @deprecated deprecated in 3.1.0, use {@link #create(Code, String)}
     * instead
     */
    @Deprecated
    public static KeeperException create(int code, String path) {
        KeeperException r = create(Code.get(code));
        r.path = path;
        return r;
    }

    /**
     * @deprecated deprecated in 3.1.0, use {@link #create(Code)}
     * instead
     */
    @Deprecated
    public static KeeperException create(int code) {
        return create(Code.get(code));
    }

    /**
     * All non-specific keeper exceptions should be constructed via
     * this factory method in order to guarantee consistency in error
     * codes and such.  If you know the error code, then you should
     * construct the special purpose exception directly.  That will
     * allow you to have the most specific possible declarations of
     * what exceptions might actually be thrown.
     *
     * @param code The error code of your new exception.  This will
     * also determine the specific type of the exception that is
     * returned.
     * @return The specialized exception, presumably to be thrown by
     * the caller.
     */
    public static KeeperException create(Code code) {
        switch (code) {
            case SYSTEMERROR:
                return new SystemErrorException();
            case RUNTIMEINCONSISTENCY:
                return new RuntimeInconsistencyException();
            case DATAINCONSISTENCY:
                return new DataInconsistencyException();
            case CONNECTIONLOSS:
                return new ConnectionLossException();
            case MARSHALLINGERROR:
                return new MarshallingErrorException();
            case UNIMPLEMENTED:
                return new UnimplementedException();
            case OPERATIONTIMEOUT:
                return new OperationTimeoutException();
            case BADARGUMENTS:
                return new BadArgumentsException();
            case APIERROR:
                return new APIErrorException();
            case NONODE:
                return new NoNodeException();
            case NOAUTH:
                return new NoAuthException();
            case BADVERSION:
                return new BadVersionException();
            case NOCHILDRENFOREPHEMERALS:
                return new NoChildrenForEphemeralsException();
            case NODEEXISTS:
                return new NodeExistsException();
            case INVALIDACL:
                return new InvalidACLException();
            case AUTHFAILED:
                return new AuthFailedException();
            case NOTEMPTY:
                return new NotEmptyException();
            case SESSIONEXPIRED:
                return new SessionExpiredException();
            case INVALIDCALLBACK:
                return new InvalidCallbackException();

            case OK:
            default:
                throw new IllegalArgumentException("Invalid exception code");
        }
    }

    /**
     * Set the code for this exception
     * @param code error code
     * @deprecated deprecated in 3.1.0, exceptions should be immutable, this
     * method should not be used
     */
    @Deprecated
    public void setCode(int code) {
        this.code = Code.get(code);
    }

    public static enum Code {
        /** Everything is OK */
        OK (0),

        /** System and server-side errors.
         * This is never thrown by the server, it shouldn't be used other than
         * to indicate a range. Specifically error codes greater than this
         * value, but lesser than {@link #APIERROR}, are system errors.
         */
        SYSTEMERROR (-1),

        /** A runtime inconsistency was found */
        RUNTIMEINCONSISTENCY (-2),
        /** A data inconsistency was found */
        DATAINCONSISTENCY (-3),
        /** Connection to the server has been lost */
        CONNECTIONLOSS (-4),
        /** Error while marshalling or unmarshalling data */
        MARSHALLINGERROR (-5),
        /** Operation is unimplemented */
        UNIMPLEMENTED (-6),
        /** Operation timeout */
        OPERATIONTIMEOUT (-7),
        /** Invalid arguments */
        BADARGUMENTS (-8),

        /** API errors.
         * This is never thrown by the server, it shouldn't be used other than
         * to indicate a range. Specifically error codes greater than this
         * value are API errors (while values less than this indicate a 
         * {@link #SYSTEMERROR}).
         */
        APIERROR (-100),

        /** Node does not exist */
        NONODE (-101),
        /** Not authenticated */
        NOAUTH (-102),
        /** Version conflict */
        BADVERSION (-103),
        /** Ephemeral nodes may not have children */
        NOCHILDRENFOREPHEMERALS (-108),
        /** The node already exists */
        NODEEXISTS (-110),
        /** The node has children */
        NOTEMPTY (-111),
        /** The session has been expired by the server */
        SESSIONEXPIRED (-112),
        /** Invalid callback specified */
        INVALIDCALLBACK (-113),
        /** Invalid ACL specified */
        INVALIDACL (-114),
        /** Client authentication failed */
        AUTHFAILED (-115);

        private static final Map<Integer,Code> lookup
            = new HashMap<Integer,Code>();

        static {
            for(Code c : EnumSet.allOf(Code.class))
                lookup.put(c.code, c);
        }

        private final int code;
        Code(int code) {
            this.code = code;
        }

        /**
         * Get the int value for a particular Code.
         * @return error code as integer
         */
        public int intValue() { return code; }

        /**
         * Get the Code value for a particular integer error code
         * @param code int error code
         * @return Code value corresponding to specified int code, or null
         */
        public static Code get(int code) {
            return lookup.get(code);
        }

        /**
         * @deprecated deprecated in 3.1.0, use {@link #OK} instead
         */
        @Deprecated
        public static final int Ok = OK.code;

        /**
         * @deprecated deprecated in 3.1.0, use {@link #SYSTEMERROR} instead
         */
        @Deprecated
        public static final int SystemError = SYSTEMERROR.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #RUNTIMEINCONSISTENCY} instead
         */
        @Deprecated
        public static final int RuntimeInconsistency = RUNTIMEINCONSISTENCY.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #DATAINCONSISTENCY}
         * instead
         */
        @Deprecated
        public static final int DataInconsistency = DATAINCONSISTENCY.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #CONNECTIONLOSS}
         * instead
         */
        @Deprecated
        public static final int ConnectionLoss = CONNECTIONLOSS.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #MARSHALLINGERROR}
         * instead
         */
        @Deprecated
        public static final int MarshallingError = MARSHALLINGERROR.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #UNIMPLEMENTED} instead
         */
        @Deprecated
        public static final int Unimplemented = UNIMPLEMENTED.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #OPERATIONTIMEOUT}
         * instead
         */
        @Deprecated
        public static final int OperationTimeout = OPERATIONTIMEOUT.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #BADARGUMENTS} instead
         */
        @Deprecated
        public static final int BadArguments = BADARGUMENTS.code;

        /**
         * @deprecated deprecated in 3.1.0, use {@link #APIERROR} instead
         */
        @Deprecated
        public static final int APIError = APIERROR.code;

        /**
         * @deprecated deprecated in 3.1.0, use {@link #NONODE} instead
         */
        @Deprecated
        public static final int NoNode = NONODE.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #NOAUTH} instead
         */
        @Deprecated
        public static final int NoAuth = NOAUTH.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #BADVERSION} instead
         */
        @Deprecated
        public static final int BadVersion = BADVERSION.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #NOCHILDRENFOREPHEMERALS}
         * instead
         */
        @Deprecated
        public static final int
            NoChildrenForEphemerals = NOCHILDRENFOREPHEMERALS.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #NODEEXISTS} instead
         */
        @Deprecated
        public static final int NodeExists = NODEEXISTS.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #NOTEMPTY} instead
         */
        @Deprecated
        public static final int NotEmpty = NOTEMPTY.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #SESSIONEXPIRED} instead
         */
        @Deprecated
        public static final int SessionExpired = SESSIONEXPIRED.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #INVALIDCALLBACK} instead
         */
        @Deprecated
        public static final int InvalidCallback = INVALIDCALLBACK.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #INVALIDACL} instead
         */
        @Deprecated
        public static final int InvalidACL = INVALIDACL.code;
        /**
         * @deprecated deprecated in 3.1.0, use {@link #AUTHFAILED} instead
         */
        @Deprecated
        public static final int AuthFailed = AUTHFAILED.code;
    }

    static String getCodeMessage(Code code) {
        switch (code) {
            case OK:
                return "ok";
            case SYSTEMERROR:
                return "SystemError";
            case RUNTIMEINCONSISTENCY:
                return "RuntimeInconsistency";
            case DATAINCONSISTENCY:
                return "DataInconsistency";
            case CONNECTIONLOSS:
                return "ConnectionLoss";
            case MARSHALLINGERROR:
                return "MarshallingError";
            case UNIMPLEMENTED:
                return "Unimplemented";
            case OPERATIONTIMEOUT:
                return "OperationTimeout";
            case BADARGUMENTS:
                return "BadArguments";
            case APIERROR:
                return "APIError";
            case NONODE:
                return "NoNode";
            case NOAUTH:
                return "NoAuth";
            case BADVERSION:
                return "BadVersion";
            case NOCHILDRENFOREPHEMERALS:
                return "NoChildrenForEphemerals";
            case NODEEXISTS:
                return "NodeExists";
            case INVALIDACL:
                return "InvalidACL";
            case AUTHFAILED:
                return "AuthFailed";
            case NOTEMPTY:
                return "Directory not empty";
            case SESSIONEXPIRED:
                return "Session expired";
            case INVALIDCALLBACK:
                return "Invalid callback";
            default:
                return "Unknown error " + code;
        }
    }

    private Code code;

    private String path;

    public KeeperException(Code code) {
        this.code = code;
    }

    KeeperException(Code code, String path) {
        this.code = code;
        this.path = path;
    }

    /**
     * Read the error code for this exception
     * @return the error code for this exception
     * @deprecated deprecated in 3.1.0, use {@link #code()} instead
     */
    @Deprecated
    public int getCode() {
        return code.code;
    }

    /**
     * Read the error Code for this exception
     * @return the error Code for this exception
     */
    public Code code() {
        return code;
    }

    /**
     * Read the path for this exception
     * @return the path associated with this error, null if none
     */
    public String getPath() {
        return path;
    }

    @Override
    public String getMessage() {
        if (path == null) {
            return "KeeperErrorCode = " + getCodeMessage(code);
        }
        return "KeeperErrorCode = " + getCodeMessage(code) + " for " + path;
    }

    /**
     *  @see Code.APIERROR
     */
    public static class APIErrorException extends KeeperException {
        public APIErrorException() {
            super(Code.APIERROR);
        }
    }

    /**
     *  @see Code.AUTHFAILED
     */
    public static class AuthFailedException extends KeeperException {
        public AuthFailedException() {
            super(Code.AUTHFAILED);
        }
    }

    /**
     *  @see Code.BADARGUMENTS
     */
    public static class BadArgumentsException extends KeeperException {
        public BadArgumentsException() {
            super(Code.BADARGUMENTS);
        }
    }

    /**
     * @see Code.BADVERSION
     */
    public static class BadVersionException extends KeeperException {
        public BadVersionException() {
            super(Code.BADVERSION);
        }
    }

    /**
     * @see Code.CONNECTIONLOSS
     */
    public static class ConnectionLossException extends KeeperException {
        public ConnectionLossException() {
            super(Code.CONNECTIONLOSS);
        }
    }

    /**
     * @see Code.DATAINCONSISTENCY
     */
    public static class DataInconsistencyException extends KeeperException {
        public DataInconsistencyException() {
            super(Code.DATAINCONSISTENCY);
        }
    }

    /**
     * @see Code.INVALIDACL
     */
    public static class InvalidACLException extends KeeperException {
        public InvalidACLException() {
            super(Code.INVALIDACL);
        }
    }

    /**
     * @see Code.INVALIDCALLBACK
     */
    public static class InvalidCallbackException extends KeeperException {
        public InvalidCallbackException() {
            super(Code.INVALIDCALLBACK);
        }
    }

    /**
     * @see Code.MARSHALLINGERROR
     */
    public static class MarshallingErrorException extends KeeperException {
        public MarshallingErrorException() {
            super(Code.MARSHALLINGERROR);
        }
    }

    /**
     * @see Code.NOAUTH
     */
    public static class NoAuthException extends KeeperException {
        public NoAuthException() {
            super(Code.NOAUTH);
        }
    }

    /**
     * @see Code.NOCHILDRENFOREPHEMERALS
     */
    public static class NoChildrenForEphemeralsException extends KeeperException {
        public NoChildrenForEphemeralsException() {
            super(Code.NOCHILDRENFOREPHEMERALS);
        }
    }

    /**
     * @see Code.NODEEXISTS
     */
    public static class NodeExistsException extends KeeperException {
        public NodeExistsException() {
            super(Code.NODEEXISTS);
        }
    }

    /**
     * @see Code.NONODE
     */
    public static class NoNodeException extends KeeperException {
        public NoNodeException() {
            super(Code.NONODE);
        }
    }

    /**
     * @see Code.NOTEMPTY
     */
    public static class NotEmptyException extends KeeperException {
        public NotEmptyException() {
            super(Code.NOTEMPTY);
        }
    }

    /**
     * @see Code.OPERATIONTIMEOUT
     */
    public static class OperationTimeoutException extends KeeperException {
        public OperationTimeoutException() {
            super(Code.OPERATIONTIMEOUT);
        }
    }

    /**
     * @see Code.RUNTIMEINCONSISTENCY
     */
    public static class RuntimeInconsistencyException extends KeeperException {
        public RuntimeInconsistencyException() {
            super(Code.RUNTIMEINCONSISTENCY);
        }
    }

    /**
     * @see Code.SESSIONEXPIRED
     */
    public static class SessionExpiredException extends KeeperException {
        public SessionExpiredException() {
            super(Code.SESSIONEXPIRED);
        }
    }

    /**
     * @see Code.SYSTEMERROR
     */
    public static class SystemErrorException extends KeeperException {
        public SystemErrorException() {
            super(Code.SYSTEMERROR);
        }
    }

    /**
     * @see Code.UNIMPLEMENTED
     */
    public static class UnimplementedException extends KeeperException {
        public UnimplementedException() {
            super(Code.UNIMPLEMENTED);
        }
    }
}
