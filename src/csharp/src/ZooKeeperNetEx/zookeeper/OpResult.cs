using org.apache.zookeeper.data;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace org.apache.zookeeper 
{
    /// <summary>
    /// Encodes the result of a single part of a multiple operation commit.
    /// </summary>
    public abstract class OpResult 
    {
        private readonly int type;
        private OpResult(ZooDefs.OpCode type) 
        {
            this.type = (int) type;
        }
        
        /// <summary>
        /// Encodes the return type as from <see cref="ZooDefs.OpCode"/>.  Can be used
        ///to dispatch to the correct cast needed for getting the desired
        ///additional result data
        /// </summary>
        /// <returns>an integer identifying what kind of operation this result came from.</returns>
        internal int get_Type() {
            return type;
        }

        /// <summary>
        /// A result from a create operation.  This kind of result allows the
        /// path to be retrieved since the create might have been a sequential
        /// create.
        /// </summary>
        public class CreateResult : OpResult 
        {
            private readonly string path;

            internal CreateResult(string path) : base(ZooDefs.OpCode.create) 
            {
                this.path = path;
            }

            /// <summary>
            /// Gets the path.
            /// </summary>
            public string getPath() {
                return path;
            }
        }

        /// <summary>
        /// A result from a delete operation.  No special values are available.
        /// </summary>
        public class DeleteResult : OpResult 
        {
            internal DeleteResult() : base(ZooDefs.OpCode.delete) 
            {
            }
        }

        /// <summary>
        /// A result from a setData operation.  This kind of result provides access
        /// to the Stat structure from the update.
        /// </summary>
        public class SetDataResult : OpResult {
            private readonly Stat stat;

            internal SetDataResult(Stat stat) : base(ZooDefs.OpCode.setData) {
                this.stat = stat;
            }

            /// <summary>
            /// Gets the stat.
            /// </summary>
            /// <returns></returns>
            public Stat getStat() {
                return stat;
            }
        }

        /// <summary>
        /// A result from a version check operation.  No special values are available.
        /// </summary>
        public class CheckResult : OpResult 
        {
            internal CheckResult() : base(ZooDefs.OpCode.check) 
            {
            }

        }

        /// <summary>
        /// An error result from any kind of operation.  The point of error results
        /// is that they contain an error code which helps understand what happened. </summary>
        public class ErrorResult : OpResult 
        {
            private readonly int err;

            internal ErrorResult(int err) : base(ZooDefs.OpCode.error) 
            {
                this.err = err;
            }

            /// <summary>
            /// Gets the error.
            /// </summary>
            public int getErr() {
                return err;
            }
        }
    }

}