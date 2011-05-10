/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
﻿namespace ZooKeeperNet
{
    using System;

    public abstract class KeeperException : Exception
    {

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
        public static KeeperException Create(Code code, string path)
        {
            KeeperException r = Create(code);
            r.path = path;
            return r;
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
        public static KeeperException Create(Code code)
        {
            switch (code)
            {
                case Code.SYSTEMERROR:
                    return new SystemErrorException();
                case Code.RUNTIMEINCONSISTENCY:
                    return new RuntimeInconsistencyException();
                case Code.DATAINCONSISTENCY:
                    return new DataInconsistencyException();
                case Code.CONNECTIONLOSS:
                    return new ConnectionLossException();
                case Code.MARSHALLINGERROR:
                    return new MarshallingErrorException();
                case Code.UNIMPLEMENTED:
                    return new UnimplementedException();
                case Code.OPERATIONTIMEOUT:
                    return new OperationTimeoutException();
                case Code.BADARGUMENTS:
                    return new BadArgumentsException();
                case Code.APIERROR:
                    return new APIErrorException();
                case Code.NONODE:
                    return new NoNodeException();
                case Code.NOAUTH:
                    return new NoAuthException();
                case Code.BADVERSION:
                    return new BadVersionException();
                case Code.NOCHILDRENFOREPHEMERALS:
                    return new NoChildrenForEphemeralsException();
                case Code.NODEEXISTS:
                    return new NodeExistsException();
                case Code.INVALIDACL:
                    return new InvalidACLException();
                case Code.AUTHFAILED:
                    return new AuthFailedException();
                case Code.NOTEMPTY:
                    return new NotEmptyException();
                case Code.SESSIONEXPIRED:
                    return new SessionExpiredException();
                case Code.INVALIDCALLBACK:
                    return new InvalidCallbackException();
                case Code.SESSIONMOVED:
                    return new SessionMovedException();
                case Code.OK:
                default:
                    throw new InvalidOperationException("Invalid exception code");
            }
        }

        /** Codes which represent the various KeeperException
         * types. This enum replaces the deprecated earlier static final int
         * constants. The old, deprecated, values are in "camel case" while the new
         * enum values are in all CAPS.
         */
        public enum Code
        {
            /** Everything is OK */
            OK = 0,

            /** System and server-side errors.
             * This is never thrown by the server, it shouldn't be used other than
             * to indicate a range. Specifically error codes greater than this
             * value, but lesser than {@link #APIERROR}, are system errors.
             */
            SYSTEMERROR = -1,

            /** A runtime inconsistency was found */
            RUNTIMEINCONSISTENCY = -2,
            /** A data inconsistency was found */
            DATAINCONSISTENCY = -3,
            /** Connection to the server has been lost */
            CONNECTIONLOSS = -4,
            /** Error while marshalling or unmarshalling data */
            MARSHALLINGERROR = -5,
            /** Operation is unimplemented */
            UNIMPLEMENTED = -6,
            /** Operation timeout */
            OPERATIONTIMEOUT = -7,
            /** Invalid arguments */
            BADARGUMENTS = -8,

            /** API errors.
             * This is never thrown by the server, it shouldn't be used other than
             * to indicate a range. Specifically error codes greater than this
             * value are API errors (while values less than this indicate a
             * {@link #SYSTEMERROR}).
             */
            APIERROR = -100,

            /** Node does not exist */
            NONODE = -101,
            /** Not authenticated */
            NOAUTH = -102,
            /** Version conflict */
            BADVERSION = -103,
            /** Ephemeral nodes may not have children */
            NOCHILDRENFOREPHEMERALS = -108,
            /** The node already exists */
            NODEEXISTS = -110,
            /** The node has children */
            NOTEMPTY = -111,
            /** The session has been expired by the server */
            SESSIONEXPIRED = -112,
            /** Invalid callback specified */
            INVALIDCALLBACK = -113,
            /** Invalid ACL specified */
            INVALIDACL = -114,
            /** Client authentication failed */
            AUTHFAILED = -115,
            /** Session moved to another server, so operation is ignored */
            SESSIONMOVED = -118
        }

        public static string getCodeMessage(Code code)
        {
            switch (code)
            {
                case Code.OK:
                    return "ok";
                case Code.SYSTEMERROR:
                    return "SystemError";
                case Code.RUNTIMEINCONSISTENCY:
                    return "RuntimeInconsistency";
                case Code.DATAINCONSISTENCY:
                    return "DataInconsistency";
                case Code.CONNECTIONLOSS:
                    return "ConnectionLoss";
                case Code.MARSHALLINGERROR:
                    return "MarshallingError";
                case Code.UNIMPLEMENTED:
                    return "Unimplemented";
                case Code.OPERATIONTIMEOUT:
                    return "OperationTimeout";
                case Code.BADARGUMENTS:
                    return "BadArguments";
                case Code.APIERROR:
                    return "APIError";
                case Code.NONODE:
                    return "NoNode";
                case Code.NOAUTH:
                    return "NoAuth";
                case Code.BADVERSION:
                    return "BadVersion";
                case Code.NOCHILDRENFOREPHEMERALS:
                    return "NoChildrenForEphemerals";
                case Code.NODEEXISTS:
                    return "NodeExists";
                case Code.INVALIDACL:
                    return "InvalidACL";
                case Code.AUTHFAILED:
                    return "AuthFailed";
                case Code.NOTEMPTY:
                    return "Directory not empty";
                case Code.SESSIONEXPIRED:
                    return "Session expired";
                case Code.INVALIDCALLBACK:
                    return "Invalid callback";
                case Code.SESSIONMOVED:
                    return "Session moved";
                default:
                    return "Unknown error " + code;
            }
        }

        private Code code;

        private string path;

        public KeeperException(Code code)
        {
            this.code = code;
        }

        KeeperException(Code code, string path)
        {
            this.code = code;
            this.path = path;
        }

        /**
     * Read the error Code for this exception
     * @return the error Code for this exception
     */
        public Code GetCode()
        {
            return code;
        }

        /**
         * Read the path for this exception
         * @return the path associated with this error, null if none
         */
        public string getPath()
        {
            return path;
        }

        public string getMessage()
        {
            if (path == null)
            {
                return "KeeperErrorCode = " + getCodeMessage(code);
            }
            return "KeeperErrorCode = " + getCodeMessage(code) + " for " + path;
        }

        /**
         *  @see Code#APIERROR
         */
        public class APIErrorException : KeeperException
        {
            public APIErrorException()
                : base(Code.APIERROR)
            {
            }
        }

        /**
         *  @see Code#AUTHFAILED
         */
        public class AuthFailedException : KeeperException
        {
            public AuthFailedException()
                : base(Code.AUTHFAILED)
            {
            }
        }

        /**
         *  @see Code#BADARGUMENTS
         */
        public class BadArgumentsException : KeeperException
        {
            public BadArgumentsException()
                : base(Code.BADARGUMENTS)
            {
            }
            public BadArgumentsException(string path)
                : base(Code.BADARGUMENTS, path)
            {
            }
        }

        /**
         * @see Code#BADVERSION
         */
        public class BadVersionException : KeeperException
        {
            public BadVersionException()
                : base(Code.BADVERSION)
            {
            }
            public BadVersionException(string path)
                : base(Code.BADVERSION, path)
            {
            }
        }

        /**
         * @see Code#CONNECTIONLOSS
         */
        public class ConnectionLossException : KeeperException
        {
            public ConnectionLossException()
                : base(Code.CONNECTIONLOSS)
            {
            }
        }

        /**
         * @see Code#DATAINCONSISTENCY
         */
        public class DataInconsistencyException : KeeperException
        {
            public DataInconsistencyException()
                : base(Code.DATAINCONSISTENCY)
            {
            }
        }

        /**
         * @see Code#INVALIDACL
         */
        public class InvalidACLException : KeeperException
        {
            public InvalidACLException()
                : base(Code.INVALIDACL)
            {
            }
            public InvalidACLException(string path)
                : base(Code.INVALIDACL, path)
            {
            }
        }

        /**
         * @see Code#INVALIDCALLBACK
         */
        public class InvalidCallbackException : KeeperException
        {
            public InvalidCallbackException()
                : base(Code.INVALIDCALLBACK)
            {
            }
        }

        /**
         * @see Code#MARSHALLINGERROR
         */
        public class MarshallingErrorException : KeeperException
        {
            public MarshallingErrorException()
                : base(Code.MARSHALLINGERROR)
            {
            }
        }

        /**
         * @see Code#NOAUTH
         */
        public class NoAuthException : KeeperException
        {
            public NoAuthException()
                : base(Code.NOAUTH)
            {
            }
        }

        /**
         * @see Code#NOCHILDRENFOREPHEMERALS
         */
        public class NoChildrenForEphemeralsException : KeeperException
        {
            public NoChildrenForEphemeralsException()
                : base(Code.NOCHILDRENFOREPHEMERALS)
            {
            }
            public NoChildrenForEphemeralsException(string path)
                : base(Code.NOCHILDRENFOREPHEMERALS, path)
            {
            }
        }

        /**
         * @see Code#NODEEXISTS
         */
        public class NodeExistsException : KeeperException
        {
            public NodeExistsException()
                : base(Code.NODEEXISTS)
            {
            }
            public NodeExistsException(string path)
                : base(Code.NODEEXISTS, path)
            {
            }
        }

        /**
         * @see Code#NONODE
         */
        public class NoNodeException : KeeperException
        {
            public NoNodeException()
                : base(Code.NONODE)
            {
            }
            public NoNodeException(string path)
                : base(Code.NONODE, path)
            {
            }
        }

        /**
         * @see Code#NOTEMPTY
         */
        public class NotEmptyException : KeeperException
        {
            public NotEmptyException()
                : base(Code.NOTEMPTY)
            {
            }
            public NotEmptyException(string path)
                : base(Code.NOTEMPTY, path)
            {
            }
        }

        /**
         * @see Code#OPERATIONTIMEOUT
         */
        public class OperationTimeoutException : KeeperException
        {
            public OperationTimeoutException()
                : base(Code.OPERATIONTIMEOUT)
            {
            }
        }

        /**
         * @see Code#RUNTIMEINCONSISTENCY
         */
        public class RuntimeInconsistencyException : KeeperException
        {
            public RuntimeInconsistencyException()
                : base(Code.RUNTIMEINCONSISTENCY)
            {
            }
        }

        /**
         * @see Code#SESSIONEXPIRED
         */
        public class SessionExpiredException : KeeperException
        {
            public SessionExpiredException()
                : base(Code.SESSIONEXPIRED)
            {
            }
        }

        /**
         * @see Code#SESSIONMOVED
         */
        public class SessionMovedException : KeeperException
        {
            public SessionMovedException()
                : base(Code.SESSIONMOVED)
            {
            }
        }

        /**
         * @see Code#SYSTEMERROR
         */
        public class SystemErrorException : KeeperException
        {
            public SystemErrorException()
                : base(Code.SYSTEMERROR)
            {
            }
        }

        /**
         * @see Code#UNIMPLEMENTED
         */
        public class UnimplementedException : KeeperException
        {
            public UnimplementedException()
                : base(Code.UNIMPLEMENTED)
            {
            }
        }
    }
}
