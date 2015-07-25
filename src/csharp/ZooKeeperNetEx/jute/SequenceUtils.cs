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

using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;

namespace org.apache.jute
{
    internal static class SequenceUtils
    {
        [DllImport("msvcrt.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int memcmp(byte[] b1, byte[] b2, long count);

        private static int CompareTo(byte[] b1, byte[] b2)
        {
            if (ReferenceEquals(b1, b2))
            {
                return 0;
            }

            //they can't both be null;
            if (b1 == null)
            {
                return -1;
            }

            if (b2 == null)
            {
                return 1;
            }

            int length1 = b1.Length;
            int length2 = b2.Length;
            if (length1 < length2) return -1;
            else
            {
                if (length2 < length1) return 1;
                else
                {
                    return memcmp(b1, b2, length1);
                }
            }
        }

        public static bool EqualsEx<T>(IEnumerable<T> ar1, IEnumerable<T> ar2)
        {
            if (ReferenceEquals(ar1, ar2)) return true;
            if (ar1 == null || ar2 == null) return false;
            return ar1.SequenceEqual(ar2);
        }

        public static bool EqualsEx(byte[] ar1, byte[] ar2)
        {
            return CompareTo(ar1, ar2) == 0;
        }

        public static int GetHashCodeEx<T>(IEnumerable<T> ar)
        {
            return ar == null
                ? 0
                : ar.Aggregate(17,
                    (current, value) => current + unchecked(current*31 + (value == null ? 0 : value.GetHashCode())));
        }

        public static bool isEmpty(this ICollection list) {
            return list.Count == 0;
        }
    }
}
