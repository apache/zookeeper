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

using System;
using System.Collections.Generic;
using org.apache.utils;

namespace org.apache.zookeeper
 {
    /// <summary>
    /// ZooKeeper Base Exception
    /// </summary>
    public abstract class KeeperException : Exception
﻿    {
/**
     * All multi-requests that result in an exception retain the results
     * here so that it is possible to examine the problems in the catch
     * scope.  Non-multi requests will get a null if they try to access
     * these results.
     */
         private List<OpResult> results;
﻿        /**
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

         internal static KeeperException create(int code, string path = null)
﻿        {
﻿            switch (EnumUtil<Code>.DefinedCast(code))
﻿            {
﻿                //case Code.SYSTEMERROR:
﻿                //    return new SystemErrorException();
﻿                case Code.RUNTIMEINCONSISTENCY:
﻿                    return new RuntimeInconsistencyException();
﻿                case Code.DATAINCONSISTENCY:
﻿                    return new DataInconsistencyException();
﻿                case Code.CONNECTIONLOSS:
﻿                    return new ConnectionLossException();
﻿                case Code.MARSHALLINGERROR:
﻿                    return new MarshallingErrorException();
﻿                case Code.UNIMPLEMENTED:
﻿                    return new UnimplementedException();
﻿                case Code.OPERATIONTIMEOUT:
﻿                    return new OperationTimeoutException();
﻿                case Code.BADARGUMENTS:
﻿                    return new BadArgumentsException(path);
﻿                //case Code.APIERROR:
﻿                //    return new APIErrorException();
﻿                case Code.NONODE:
﻿                    return new NoNodeException(path);
﻿                case Code.NOAUTH:
﻿                    return new NoAuthException();
﻿                case Code.BADVERSION:
﻿                    return new BadVersionException(path);
﻿                case Code.NOCHILDRENFOREPHEMERALS:
﻿                    return new NoChildrenForEphemeralsException(path);
﻿                case Code.NODEEXISTS:
﻿                    return new NodeExistsException(path);
﻿                case Code.INVALIDACL:
﻿                    return new InvalidACLException(path);
﻿                case Code.AUTHFAILED:
﻿                    return new AuthFailedException();
﻿                case Code.NOTEMPTY:
﻿                    return new NotEmptyException(path);
﻿                case Code.SESSIONEXPIRED:
﻿                    return new SessionExpiredException();
﻿                case Code.INVALIDCALLBACK:
﻿                    return new InvalidCallbackException();
﻿                case Code.SESSIONMOVED:
﻿                    return new SessionMovedException();
﻿                case Code.NOTREADONLY:
﻿                    return new NotReadOnlyException();
﻿                case Code.OK:
﻿                default:
﻿                    throw new ArgumentOutOfRangeException("code", "Invalid exception code");
﻿            }
﻿        }

﻿        
﻿        

﻿        /** Codes which represent the various KeeperException
         * types. This enum replaces the deprecated earlier static final int
         * constants. The old, deprecated, values are in "camel case" while the new
         * enum values are in all CAPS.
         */

         internal enum Code
﻿        {
﻿            /** Everything is OK */
﻿            OK = 0,

            // System and server-side errors.
            // This is never thrown by the server, it shouldn't be used other than
            // to indicate a range. Specifically error codes greater than this
            // value, but lesser than {@link #APIERROR}, are system errors.
            //
            //SYSTEMERROR = -1,

            /** A runtime inconsistency was found */
            RUNTIMEINCONSISTENCY = -2,
﻿            /** A data inconsistency was found */
﻿            DATAINCONSISTENCY = -3,
﻿            /** Connection to the server has been lost */
﻿            CONNECTIONLOSS = -4,
﻿            /** Error while marshalling or unmarshalling data */
﻿            MARSHALLINGERROR = -5,
﻿            /** Operation is unimplemented */
﻿            UNIMPLEMENTED = -6,
﻿            /** Operation timeout */
﻿            OPERATIONTIMEOUT = -7,
﻿            /** Invalid arguments */
﻿            BADARGUMENTS = -8,

            // API errors.
            // This is never thrown by the server, it shouldn't be used other than
            // to indicate a range. Specifically error codes greater than this
            // value are API errors (while values less than this indicate a
            // {@link #SYSTEMERROR}).
            //
            //APIERROR = -100,

            /** Node does not exist */
            NONODE = -101,
﻿            /** Not authenticated */
﻿            NOAUTH = -102,
﻿            /** Version conflict */
﻿            BADVERSION = -103,
﻿            /** Ephemeral nodes may not have children */
﻿            NOCHILDRENFOREPHEMERALS = -108,
﻿            /** The node already exists */
﻿            NODEEXISTS = -110,
﻿            /** The node has children */
﻿            NOTEMPTY = -111,
﻿            /** The session has been expired by the server */
﻿            SESSIONEXPIRED = -112,
﻿            /** Invalid callback specified */
﻿            INVALIDCALLBACK = -113,
﻿            /** Invalid ACL specified */
﻿            INVALIDACL = -114,
﻿            /** Client authentication failed */
﻿            AUTHFAILED = -115,
﻿            /** Session moved to another server, so operation is ignored */
﻿            SESSIONMOVED = -118,

﻿            /// <summary>
﻿            /// State-changing request is passed to read-only server </summary>
﻿            NOTREADONLY = -119
﻿        }

		private readonly Code code;

﻿        private readonly string path;
﻿        private KeeperException(Code code)
﻿        {
﻿            this.code = code;
﻿        }

﻿        private KeeperException(Code code, string path)
﻿        {
﻿            this.code = code;
﻿            this.path = path;
﻿        }

﻿        /**
     * Read the error Code for this exception
     * @return the error Code for this exception
     */

         internal Code getCode() {
        return code;
    }

    /**
     * Read the path for this exception
     * @return the path associated with this error, null if none
     */

         internal string getPath() {
        return path;
    }

         internal void setMultiResults(List<OpResult> res) {
        results = res;
    }

    /**
     * If this exception was thrown by a multi-request then the (partial) results
     * and error codes can be retrieved using this getter.
     * @return A copy of the list of results from the operations in the multi-request.
     * 
     * @since 3.4.0
     *
     */

         internal List<OpResult> getResults() {
             return results != null ? new List<OpResult>(results) : null;
         }

        /// <summary>
        /// <see cref="Code.AUTHFAILED"/>
        /// </summary>
        public class AuthFailedException : KeeperException
﻿        {
﻿            internal AuthFailedException()
﻿                : base(Code.AUTHFAILED)
﻿            {
﻿            }
﻿        }

        /// <summary>
        /// <see cref="Code.BADARGUMENTS"/>
        /// </summary>
        public class BadArgumentsException : KeeperException
﻿        {
﻿            internal BadArgumentsException(string path)
﻿                : base(Code.BADARGUMENTS, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.BADVERSION"/>
        /// </summary>
        public class BadVersionException : KeeperException
﻿        {
﻿            internal BadVersionException(string path)
﻿                : base(Code.BADVERSION, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.CONNECTIONLOSS"/>
        /// </summary>
        public class ConnectionLossException : KeeperException
﻿        {
﻿            internal ConnectionLossException()
﻿                : base(Code.CONNECTIONLOSS)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.DATAINCONSISTENCY"/>
        /// </summary>
        public class DataInconsistencyException : KeeperException
﻿        {
﻿            internal DataInconsistencyException()
﻿                : base(Code.DATAINCONSISTENCY)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.INVALIDACL"/>
        /// </summary>
        public class InvalidACLException : KeeperException
﻿        {
﻿            internal InvalidACLException()
﻿                : base(Code.INVALIDACL)
﻿            {
﻿            }

﻿            internal InvalidACLException(string path)
﻿                : base(Code.INVALIDACL, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.INVALIDCALLBACK"/>
        /// </summary>
        public class InvalidCallbackException : KeeperException
﻿        {
﻿            internal InvalidCallbackException()
﻿                : base(Code.INVALIDCALLBACK)
﻿            {
﻿            }
﻿        }

        /// <summary>
        /// <see cref="Code.MARSHALLINGERROR"/>
        /// </summary>
        public class MarshallingErrorException : KeeperException
﻿        {
﻿            internal MarshallingErrorException()
﻿                : base(Code.MARSHALLINGERROR)
﻿            {
﻿            }
﻿        }

        /// <summary>
        /// <see cref="Code.NOAUTH"/>
        /// </summary>
        public class NoAuthException : KeeperException
﻿        {
﻿            internal NoAuthException()
﻿                : base(Code.NOAUTH)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.NOCHILDRENFOREPHEMERALS"/>
        /// </summary>
        public class NoChildrenForEphemeralsException : KeeperException
﻿        {
﻿            internal NoChildrenForEphemeralsException(string path)
﻿                : base(Code.NOCHILDRENFOREPHEMERALS, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.NODEEXISTS"/>
        /// </summary>

        public class NodeExistsException : KeeperException
﻿        {
﻿            internal NodeExistsException(string path)
﻿                : base(Code.NODEEXISTS, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.NONODE"/>
        /// </summary>
        public class NoNodeException : KeeperException
﻿        {
﻿            internal NoNodeException(string path)
﻿                : base(Code.NONODE, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.NOTEMPTY"/>
        /// </summary>
        public class NotEmptyException : KeeperException
﻿        {
﻿            internal NotEmptyException(string path)
﻿                : base(Code.NOTEMPTY, path)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.OPERATIONTIMEOUT"/>
        /// </summary>
        public class OperationTimeoutException : KeeperException
﻿        {
﻿            internal OperationTimeoutException()
﻿                : base(Code.OPERATIONTIMEOUT)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.RUNTIMEINCONSISTENCY"/>
        /// </summary>
        public class RuntimeInconsistencyException : KeeperException
﻿        {
﻿            internal RuntimeInconsistencyException()
﻿                : base(Code.RUNTIMEINCONSISTENCY)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.SESSIONEXPIRED"/>
        /// </summary>
        public class SessionExpiredException : KeeperException
﻿        {
﻿            internal SessionExpiredException()
﻿                : base(Code.SESSIONEXPIRED)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.SESSIONMOVED"/>
        /// </summary>
        public class SessionMovedException : KeeperException
﻿        {
﻿            internal SessionMovedException()
﻿                : base(Code.SESSIONMOVED)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.NOTREADONLY"/>
        /// </summary>
        public class NotReadOnlyException : KeeperException
﻿        {
﻿            internal NotReadOnlyException() : base(Code.NOTREADONLY)
﻿            {
﻿            }
﻿        }
        
        /// <summary>
        /// <see cref="Code.UNIMPLEMENTED"/>
        /// </summary>
        public class UnimplementedException : KeeperException
﻿        {
﻿            internal UnimplementedException()
﻿                : base(Code.UNIMPLEMENTED)
﻿            {
﻿            }
﻿        }
﻿    }
﻿}
