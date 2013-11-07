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
            case SESSIONMOVED:
                return new SessionMovedException();
            	
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

    /** This interface contains the original static final int constants
     * which have now been replaced with an enumeration in Code. Do not
     * reference this class directly, if necessary (legacy code) continue
     * to access the constants through Code.
     * Note: an interface is used here due to the fact that enums cannot
     * reference constants defined within the same enum as said constants
     * are considered initialized _after_ the enum itself. By using an
     * interface as a super type this allows the deprecated constants to
     * be initialized first and referenced when constructing the enums. I
     * didn't want to have constants declared twice. This
     * interface should be private, but it's declared public to enable
     * javadoc to include in the user API spec.
     */
    @Deprecated
    public interface CodeDeprecated {
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#OK} instead
         */
        @Deprecated
        public static final int Ok = 0;

        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#SYSTEMERROR} instead
         */
        @Deprecated
        public static final int SystemError = -1;
        /**
         * @deprecated deprecated in 3.1.0, use
         * {@link Code#RUNTIMEINCONSISTENCY} instead
         */
        @Deprecated
        public static final int RuntimeInconsistency = -2;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#DATAINCONSISTENCY}
         * instead
         */
        @Deprecated
        public static final int DataInconsistency = -3;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#CONNECTIONLOSS}
         * instead
         */
        @Deprecated
        public static final int ConnectionLoss = -4;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#MARSHALLINGERROR}
         * instead
         */
        @Deprecated
        public static final int MarshallingError = -5;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#UNIMPLEMENTED}
         * instead
         */
        @Deprecated
        public static final int Unimplemented = -6;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#OPERATIONTIMEOUT}
         * instead
         */
        @Deprecated
        public static final int OperationTimeout = -7;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#BADARGUMENTS}
         * instead
         */
        @Deprecated
        public static final int BadArguments = -8;

        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#APIERROR} instead
         */
        @Deprecated
        public static final int APIError = -100;

        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#NONODE} instead
         */
        @Deprecated
        public static final int NoNode = -101;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#NOAUTH} instead
         */
        @Deprecated
        public static final int NoAuth = -102;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#BADVERSION} instead
         */
        @Deprecated
        public static final int BadVersion = -103;
        /**
         * @deprecated deprecated in 3.1.0, use
         * {@link Code#NOCHILDRENFOREPHEMERALS}
         * instead
         */
        @Deprecated
        public static final int NoChildrenForEphemerals = -108;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#NODEEXISTS} instead
         */
        @Deprecated
        public static final int NodeExists = -110;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#NOTEMPTY} instead
         */
        @Deprecated
        public static final int NotEmpty = -111;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#SESSIONEXPIRED} instead
         */
        @Deprecated
        public static final int SessionExpired = -112;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#INVALIDCALLBACK}
         * instead
         */
        @Deprecated
        public static final int InvalidCallback = -113;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#INVALIDACL} instead
         */
        @Deprecated
        public static final int InvalidACL = -114;
        /**
         * @deprecated deprecated in 3.1.0, use {@link Code#AUTHFAILED} instead
         */
        @Deprecated
        public static final int AuthFailed = -115;
        /**
         * This value will be used directly in {@link CODE#SESSIONMOVED}
         */
        // public static final int SessionMoved = -118;
    }

    /** Codes which represent the various KeeperException
     * types. This enum replaces the deprecated earlier static final int
     * constants. The old, deprecated, values are in "camel case" while the new
     * enum values are in all CAPS.
     */
    public static enum Code implements CodeDeprecated {
        /** Everything is OK */
        OK (Ok),

        /** System and server-side errors.
         * This is never thrown by the server, it shouldn't be used other than
         * to indicate a range. Specifically error codes greater than this
         * value, but lesser than {@link #APIERROR}, are system errors.
         */
        SYSTEMERROR (SystemError),

        /** A runtime inconsistency was found */
        RUNTIMEINCONSISTENCY (RuntimeInconsistency),
        /** A data inconsistency was found */
        DATAINCONSISTENCY (DataInconsistency),
        /** Connection to the server has been lost */
        CONNECTIONLOSS (ConnectionLoss),
        /** Error while marshalling or unmarshalling data */
        MARSHALLINGERROR (MarshallingError),
        /** Operation is unimplemented */
        UNIMPLEMENTED (Unimplemented),
        /** Operation timeout */
        OPERATIONTIMEOUT (OperationTimeout),
        /** Invalid arguments */
        BADARGUMENTS (BadArguments),
        
        /** API errors.
         * This is never thrown by the server, it shouldn't be used other than
         * to indicate a range. Specifically error codes greater than this
         * value are API errors (while values less than this indicate a
         * {@link #SYSTEMERROR}).
         */
        APIERROR (APIError),

        /** Node does not exist */
        NONODE (NoNode),
        /** Not authenticated */
        NOAUTH (NoAuth),
        /** Version conflict */
        BADVERSION (BadVersion),
        /** Ephemeral nodes may not have children */
        NOCHILDRENFOREPHEMERALS (NoChildrenForEphemerals),
        /** The node already exists */
        NODEEXISTS (NodeExists),
        /** The node has children */
        NOTEMPTY (NotEmpty),
        /** The session has been expired by the server */
        SESSIONEXPIRED (SessionExpired),
        /** Invalid callback specified */
        INVALIDCALLBACK (InvalidCallback),
        /** Invalid ACL specified */
        INVALIDACL (InvalidACL),
        /** Client authentication failed */
        AUTHFAILED (AuthFailed),
        /** Session moved to another server, so operation is ignored */
        SESSIONMOVED (-118);

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
            case SESSIONMOVED:
                return "Session moved";
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
     *  @see Code#APIERROR
     */
    public static class APIErrorException extends KeeperException {
        public APIErrorException() {
            super(Code.APIERROR);
        }
    }

    /**
     *  @see Code#AUTHFAILED
     */
    public static class AuthFailedException extends KeeperException {
        public AuthFailedException() {
            super(Code.AUTHFAILED);
        }
    }

    /**
     *  @see Code#BADARGUMENTS
     */
    public static class BadArgumentsException extends KeeperException {
        public BadArgumentsException() {
            super(Code.BADARGUMENTS);
        }
        public BadArgumentsException(String path) {
            super(Code.BADARGUMENTS, path);
        }
    }

    /**
     * @see Code#BADVERSION
     */
    public static class BadVersionException extends KeeperException {
        public BadVersionException() {
            super(Code.BADVERSION);
        }
        public BadVersionException(String path) {
            super(Code.BADVERSION, path);
        }
    }

    /**
     * @see Code#CONNECTIONLOSS
     */
    public static class ConnectionLossException extends KeeperException {
        public ConnectionLossException() {
            super(Code.CONNECTIONLOSS);
        }
    }

    /**
     * @see Code#DATAINCONSISTENCY
     */
    public static class DataInconsistencyException extends KeeperException {
        public DataInconsistencyException() {
            super(Code.DATAINCONSISTENCY);
        }
    }

    /**
     * @see Code#INVALIDACL
     */
    public static class InvalidACLException extends KeeperException {
        public InvalidACLException() {
            super(Code.INVALIDACL);
        }
        public InvalidACLException(String path) {
            super(Code.INVALIDACL, path);
        }
    }

    /**
     * @see Code#INVALIDCALLBACK
     */
    public static class InvalidCallbackException extends KeeperException {
        public InvalidCallbackException() {
            super(Code.INVALIDCALLBACK);
        }
    }

    /**
     * @see Code#MARSHALLINGERROR
     */
    public static class MarshallingErrorException extends KeeperException {
        public MarshallingErrorException() {
            super(Code.MARSHALLINGERROR);
        }
    }

    /**
     * @see Code#NOAUTH
     */
    public static class NoAuthException extends KeeperException {
        public NoAuthException() {
            super(Code.NOAUTH);
        }
    }

    /**
     * @see Code#NOCHILDRENFOREPHEMERALS
     */
    public static class NoChildrenForEphemeralsException extends KeeperException {
        public NoChildrenForEphemeralsException() {
            super(Code.NOCHILDRENFOREPHEMERALS);
        }
        public NoChildrenForEphemeralsException(String path) {
            super(Code.NOCHILDRENFOREPHEMERALS, path);
        }
    }

    /**
     * @see Code#NODEEXISTS
     */
    public static class NodeExistsException extends KeeperException {
        public NodeExistsException() {
            super(Code.NODEEXISTS);
        }
        public NodeExistsException(String path) {
            super(Code.NODEEXISTS, path);
        }
    }

    /**
     * @see Code#NONODE
     */
    public static class NoNodeException extends KeeperException {
        public NoNodeException() {
            super(Code.NONODE);
        }
        public NoNodeException(String path) {
            super(Code.NONODE, path);
        }
    }

    /**
     * @see Code#NOTEMPTY
     */
    public static class NotEmptyException extends KeeperException {
        public NotEmptyException() {
            super(Code.NOTEMPTY);
        }
        public NotEmptyException(String path) {
            super(Code.NOTEMPTY, path);
        }
    }

    /**
     * @see Code#OPERATIONTIMEOUT
     */
    public static class OperationTimeoutException extends KeeperException {
        public OperationTimeoutException() {
            super(Code.OPERATIONTIMEOUT);
        }
    }

    /**
     * @see Code#RUNTIMEINCONSISTENCY
     */
    public static class RuntimeInconsistencyException extends KeeperException {
        public RuntimeInconsistencyException() {
            super(Code.RUNTIMEINCONSISTENCY);
        }
    }

    /**
     * @see Code#SESSIONEXPIRED
     */
    public static class SessionExpiredException extends KeeperException {
        public SessionExpiredException() {
            super(Code.SESSIONEXPIRED);
        }
    }
    
    /**
     * @see Code#SESSIONMOVED
     */
    public static class SessionMovedException extends KeeperException {
        public SessionMovedException() {
            super(Code.SESSIONMOVED);
        }
    }

    /**
     * @see Code#SYSTEMERROR
     */
    public static class SystemErrorException extends KeeperException {
        public SystemErrorException() {
            super(Code.SYSTEMERROR);
        }
    }

    /**
     * @see Code#UNIMPLEMENTED
     */
    public static class UnimplementedException extends KeeperException {
        public UnimplementedException() {
            super(Code.UNIMPLEMENTED);
        }
    }
}
