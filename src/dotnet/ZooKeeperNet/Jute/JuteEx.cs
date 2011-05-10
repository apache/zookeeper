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
﻿namespace Org.Apache.Jute
{
    public static class JuteEx
    {
        public static int CompareTo(this byte[] b1, byte[] b2)
        {
            if (b1 == null && b2 == null) return 0;
            if (b1 == null || b2 == null) return 0;

            for (var i = 0; i < b1.Length; i++)
            {
                for (var j = 0; j < b2.Length; j++)
                {
                    if (i > j) return 1;
                    if (i < j) return -1;
                }
            }
            return 0;
        }
    }
}
