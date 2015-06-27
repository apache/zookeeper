using System;
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

        private bool Equals(OpResult other) 
        {
            if (ReferenceEquals(null, other)) return false;
            if (ReferenceEquals(this, other)) return true;
            return type == other.type;
        }

        public override bool Equals(object obj) 
        {
            if (ReferenceEquals(null, obj)) return false;
            if (ReferenceEquals(this, obj)) return true;
            if (obj.GetType() != GetType()) return false;
            return Equals((OpResult) obj);
        }

        public override int GetHashCode() 
        {
            return type;
        }

        private OpResult(ZooDefs.OpCode type) 
        {
            this.type = (int) type;
        }

        /**
     * Encodes the return type as from ZooDefs.OpCode.  Can be used
     * to dispatch to the correct cast needed for getting the desired
     * additional result data.
     * @see ZooDefs.OpCode
     * @return an integer identifying what kind of operation this result came from.
     */
        public int get_Type() {
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

            public string getPath() {
                return path;
            }

            private bool Equals(CreateResult other) 
            {
                if (ReferenceEquals(null, other)) return false;
                if (ReferenceEquals(this, other)) return true;
                return base.Equals(other) && string.Equals(path, other.path);
            }

            public override bool Equals(object obj) 
            {
                if (ReferenceEquals(null, obj)) return false;
                if (ReferenceEquals(this, obj)) return true;
                if (obj.GetType() != GetType()) return false;
                return Equals((CreateResult) obj);
            }

            public override int GetHashCode() 
            {
                unchecked 
                {
                    return (base.GetHashCode()*397) ^ (path != null ? path.GetHashCode() : 0);
                }
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

            public Stat getStat() {
                return stat;
            }

            private bool Equals(SetDataResult other) 
            {
                if (ReferenceEquals(null, other)) return false;
                if (ReferenceEquals(this, other)) return true;
                return base.Equals(other) && Equals(stat, other.stat);
            }


            public override bool Equals(object obj) {
                if (ReferenceEquals(null, obj)) return false;
                if (ReferenceEquals(this, obj)) return true;
                if (obj.GetType() != GetType()) return false;
                return Equals((SetDataResult) obj);
            }

            public override int GetHashCode() {
                unchecked {
                    return (base.GetHashCode()*397) ^ (stat != null ? stat.GetHashCode() : 0);
                }
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

            public int getErr() {
                return err;
            }

            private bool Equals(ErrorResult other) 
            {
                if (ReferenceEquals(null, other)) return false;
                if (ReferenceEquals(this, other)) return true;
                return base.Equals(other) && err == other.err;
            }
            public override bool Equals(object obj) 
            {
                if (ReferenceEquals(null, obj)) return false;
                if (ReferenceEquals(this, obj)) return true;
                if (obj.GetType() != GetType()) return false;
                return Equals((ErrorResult) obj);
            }

            public override int GetHashCode() 
            {
                unchecked 
                {
                    return (base.GetHashCode()*397) ^ err;
                }
            }
        }
    }

}