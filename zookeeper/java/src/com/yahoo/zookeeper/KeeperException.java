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
public class KeeperException extends Exception {
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
        case Code.SessionExpired:
            return "SessionExpired";
        default:
            return "Unknown error " + code;
        }
    }

    private int _code;

    private String path;

    public KeeperException(int code) {
        _code = code;
    }

    public KeeperException(int code, String path) {
        _code = code;
        this.path = path;
    }

    public int getCode() {
        return _code;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String getMessage() {
        if (path == null) {
            return "KeeperErrorCode = " + getCodeMessage(_code);
        }
        return "KeeperErrorCode = " + getCodeMessage(_code) + " for " + path;
    }
}
