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

using org.apache.utils;

namespace org.apache.zookeeper
{
    public sealed class CreateMode
    {
        /**
     * The znode will not be automatically deleted upon client's disconnect.
     */
        public static readonly CreateMode PERSISTENT = new CreateMode(0, false, false);
        /**
    * The znode will not be automatically deleted upon client's disconnect,
    * and its name will be appended with a monotonically increasing number.
    */
        public static readonly CreateMode PERSISTENT_SEQUENTIAL = new CreateMode(2, false, true);
        /**
     * The znode will be deleted upon the client's disconnect.
     */
        public static readonly CreateMode EPHEMERAL = new CreateMode(1, true, false);
        /**
     * The znode will be deleted upon the client's disconnect, and its name
     * will be appended with a monotonically increasing number.
     */
        public static readonly CreateMode EPHEMERAL_SEQUENTIAL = new CreateMode(3, true, true);

        private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(CreateMode));

        private readonly bool ephemeral;
        private readonly bool sequential;
        private readonly int flag;

        private CreateMode(int flag, bool ephemeral, bool sequential)
        {
            this.flag = flag;
            this.ephemeral = ephemeral;
            this.sequential = sequential;
        }

        public bool isEphemeral()
        {
            return ephemeral;
        }

        public bool isSequential()
        {
            return sequential;
        }

        public int toFlag()
        {
            return flag;
        }

        /**
     * Map an integer value to a CreateMode value
     */
        public static CreateMode fromFlag(int flag)
        {
            switch (flag)
            {
                case 0:
                    return PERSISTENT;

                case 1:
                    return EPHEMERAL;

                case 2:
                    return PERSISTENT_SEQUENTIAL;

                case 3:
                    return EPHEMERAL_SEQUENTIAL;

                default:
                    string errMsg = "Received an invalid flag value: " + flag
                                    + " to convert to a CreateMode";
                    LOG.error(errMsg);
                    throw new KeeperException.BadArgumentsException(errMsg);
            }
        }
    }
}
