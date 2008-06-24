/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper;

@SuppressWarnings("serial")
public abstract class KeeperException extends Exception {

    /**
     * All non-specific keeper exceptions should be constructed via this factory method
     * in order to guarantee consistency in error codes and such.  If you know the error
     * code, then you should construct the special purpose exception directly.  That will
     * allow you to have the most specific possible declarations of what exceptions might
     * actually be thrown.
     * @param code  The error code.
     * @param path  The zookeeper path being operated on.
     * @return  The specialized exception, presumably to be thrown by the caller.
     */
    public static KeeperException create(int code, String path) {
        KeeperException r = create(code);
        r.path = path;
        return r;
    }

    /**
     * All non-specific keeper exceptions should be constructed via this factory method
     * in order to guarantee consistency in error codes and such.  If you know the error
     * code, then you should construct the special purpose exception directly.  That will
     * allow you to have the most specific possible declarations of what exceptions might
     * actually be thrown.
     * @param code The error code of your new exception.  This will also determine the
     * specific type of the exception that is returned.
     * @return  The specialized exception, presumably to be thrown by the caller.
     */
    public static KeeperException create(int code) {
        switch (code) {
            case Code.SystemError:
                return new SystemErrorException();
            case Code.RuntimeInconsistency:
                return new RuntimeInconsistencyException();
            case Code.DataInconsistency:
                return new DataInconsistencyException();
            case Code.ConnectionLoss:
                return new ConnectionLossException();
            case Code.MarshallingError:
                return new MarshallingErrorException();
            case Code.Unimplemented:
                return new UnimplementedException();
            case Code.OperationTimeout:
                return new OperationTimeoutException();
            case Code.BadArguments:
                return new BadArgumentsException();
            case Code.APIError:
                return new APIErrorException();
            case Code.NoNode:
                return new NoNodeException();
            case Code.NoAuth:
                return new NoAuthException();
            case Code.BadVersion:
                return new BadVersionException();
            case Code.NoChildrenForEphemerals:
                return new NoChildrenForEphemeralsException();
            case Code.NodeExists:
                return new NodeExistsException();
            case Code.InvalidACL:
                return new InvalidACLException();
            case Code.AuthFailed:
                return new AuthFailedException();
            case Code.NotEmpty:
                return new NotEmptyException();
            case Code.SessionExpired:
                return new SessionExpiredException();
            case Code.InvalidCallback:
                return new InvalidCallbackException();

            case 0:
            default:
                throw new IllegalArgumentException("Invalid exception code");
        }
    }

    public void setCode(int code) {
        this.code = code;
    }

    public interface Code {
        int Ok = 0;

        // System and server-side errors
        int SystemError = -1;

        int RuntimeInconsistency = SystemError - 1;

        int DataInconsistency = SystemError - 2;

        int ConnectionLoss = SystemError - 3;

        int MarshallingError = SystemError - 4;

        int Unimplemented = SystemError - 5;

        int OperationTimeout = SystemError - 6;

        int BadArguments = SystemError - 7;

        // API errors
        int APIError = -100; // Catch all, shouldn't be used other
        // than range start

        int NoNode = APIError - 1; // Node does not exist

        int NoAuth = APIError - 2; // Current operation not permitted

        int BadVersion = APIError - 3; // Version conflict

        int NoChildrenForEphemerals = APIError - 8;

        int NodeExists = APIError - 10;

        int NotEmpty = APIError - 11;

        int SessionExpired = APIError - 12;

        int InvalidCallback = APIError - 13;

        int InvalidACL = APIError - 14;

        int AuthFailed = APIError - 15; // client authentication failed

    }

    static String getCodeMessage(int code) {
        switch (code) {
            case 0:
                return "ok";
            case Code.SystemError:
                return "SystemError";
            case Code.RuntimeInconsistency:
                return "RuntimeInconsistency";
            case Code.DataInconsistency:
                return "DataInconsistency";
            case Code.ConnectionLoss:
                return "ConnectionLoss";
            case Code.MarshallingError:
                return "MarshallingError";
            case Code.Unimplemented:
                return "Unimplemented";
            case Code.OperationTimeout:
                return "OperationTimeout";
            case Code.BadArguments:
                return "BadArguments";
            case Code.APIError:
                return "APIError";
            case Code.NoNode:
                return "NoNode";
            case Code.NoAuth:
                return "NoAuth";
            case Code.BadVersion:
                return "BadVersion";
            case Code.NoChildrenForEphemerals:
                return "NoChildrenForEphemerals";
            case Code.NodeExists:
                return "NodeExists";
            case Code.InvalidACL:
                return "InvalidACL";
            case Code.AuthFailed:
                return "AuthFailed";
            case Code.NotEmpty:
                return "Directory not empty";
            case Code.SessionExpired:
                return "Session expired";
            case Code.InvalidCallback:
                return "Invalid callback";
            default:
                return "Unknown error " + code;
        }
    }

    private int code;

    private String path;

    public KeeperException(int code) {
        this.code = code;
    }

    KeeperException(int code, String path) {
        this.code = code;
        this.path = path;
    }

    public int getCode() {
        return code;
    }

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

    public static class APIErrorException extends KeeperException {
        public APIErrorException() {
            super(Code.APIError);
        }
    }

    public static class AuthFailedException extends KeeperException {
        public AuthFailedException() {
            super(Code.AuthFailed);
        }
    }

    public static class BadArgumentsException extends KeeperException {
        public BadArgumentsException() {
            super(Code.BadArguments);
        }
    }

    public static class BadVersionException extends KeeperException {
        public BadVersionException() {
            super(Code.BadVersion);
        }
    }

    public static class ConnectionLossException extends KeeperException {
        public ConnectionLossException() {
            super(Code.ConnectionLoss);
        }
    }

    public static class DataInconsistencyException extends KeeperException {
        public DataInconsistencyException() {
            super(Code.DataInconsistency);
        }
    }

    public static class InvalidACLException extends KeeperException {
        public InvalidACLException() {
            super(Code.InvalidACL);
        }
    }

    public static class InvalidCallbackException extends KeeperException {
        public InvalidCallbackException() {
            super(Code.InvalidCallback);
        }
    }

    public static class MarshallingErrorException extends KeeperException {
        public MarshallingErrorException() {
            super(Code.MarshallingError);
        }
    }

    public static class NoAuthException extends KeeperException {
        public NoAuthException() {
            super(Code.NoAuth);
        }
    }

    public static class NoChildrenForEphemeralsException extends KeeperException {
        public NoChildrenForEphemeralsException() {
            super(Code.NoChildrenForEphemerals);
        }
    }

    public static class NodeExistsException extends KeeperException {
        public NodeExistsException() {
            super(Code.NodeExists);
        }
    }

    public static class NoNodeException extends KeeperException {
        public NoNodeException() {
            super(Code.NoNode);
        }
    }

    public static class NotEmptyException extends KeeperException {
        public NotEmptyException() {
            super(Code.NotEmpty);
        }
    }

    public static class OperationTimeoutException extends KeeperException {
        public OperationTimeoutException() {
            super(Code.OperationTimeout);
        }
    }

    public static class RuntimeInconsistencyException extends KeeperException {
        public RuntimeInconsistencyException() {
            super(Code.RuntimeInconsistency);
        }
    }

    public static class SessionExpiredException extends KeeperException {
        public SessionExpiredException() {
            super(Code.SessionExpired);
        }
    }

    public static class SystemErrorException extends KeeperException {
        public SystemErrorException() {
            super(Code.SystemError);
        }
    }

    public static class UnimplementedException extends KeeperException {
        public UnimplementedException() {
            super(Code.Unimplemented);
        }
    }
}
