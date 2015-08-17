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
using System.Threading;

namespace org.apache.utils
{
    internal class Fenced<T>
    {
        public Fenced(T value)
        {
            m_Value = value;
        }

        protected T m_Value;

        public T Value
        {
            get
            {
                MemoryBarrier();
                var value = m_Value;
                MemoryBarrier();
                return value;
            }
            set
            {
                MemoryBarrier();
                m_Value = value;
                MemoryBarrier();
            }
        }

        public override string ToString() {
            return m_Value.ToString();
        }
        private static void MemoryBarrier()
        {
#if NET4
          Thread.MemoryBarrier();
#else
            Interlocked.MemoryBarrier();
#endif
        }
    }

    internal class ThreadSafeInt : Fenced<int>
    {
        public ThreadSafeInt(int value)
            : base(value)
        {
        }

        public bool TrySetValue(int preconditionValue, int newValue)
        {
            return Interlocked.CompareExchange(ref m_Value, newValue, preconditionValue) == preconditionValue;
        }

        public void SetValue(int preconditionValue, int newValue)
        {
            int actualValue = Interlocked.CompareExchange(ref m_Value, newValue, preconditionValue);
            if (actualValue != preconditionValue)
                throw new InvalidOperationException("Expected=" + preconditionValue + "Actual=" + actualValue);
        }

        public int Increment() {
            return Interlocked.Increment(ref m_Value);
        }
    }
}
