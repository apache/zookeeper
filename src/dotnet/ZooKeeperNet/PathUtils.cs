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
﻿using System;
using System.Text;

namespace ZooKeeperNet
{
    public static class PathUtils
    {
        /// <summary>
        /// Path Separator string ("/")
        /// </summary>
        public const string PathSeparator = "/";
        /// <summary>
        /// Path Separator char ('/')
        /// </summary>
        public const char PathSeparatorChar = '/';
        private const char PathDotChar = '.';


        /** validate the provided znode path string
         * @param path znode path string
         * @param isSequential if the path is being created
         * with a sequential flag
         * @throws IllegalArgumentException if the path is invalid
         */        
        public static void ValidatePath(string path, bool isSequential)
        {
            ValidatePath(isSequential ? new StringBuilder(path).Append("1").ToString() : path);
        }

        /**
         * Validate the provided znode path string
         * @param path znode path string
         * @throws IllegalArgumentException if the path is invalid
         **/
        public static void ValidatePath(string path)
        {
            if (path == null)
            {
                throw new InvalidOperationException("Path cannot be null");
            }
            if (path.Length == 0)
            {
                throw new InvalidOperationException("Path length must be > 0");
            }
            if (path[0] != PathSeparatorChar)
            {
                throw new InvalidOperationException(
                             "Path must start with / character");
            }
            if (path.Length == 1)
            { // done checking - it's the root
                return;
            }
            if (path[path.Length - 1] == PathSeparatorChar)
            {
                throw new InvalidOperationException(
                             "Path must not end with / character");
            }

            string reason = null;
            char lastc = PathSeparatorChar;
            char[] chars = path.ToCharArray();
            char c;
            for (int i = 1; i < chars.Length; lastc = chars[i], i++)
            {
                c = chars[i];

                if (c == 0)
                {
                    reason = new StringBuilder("null character not allowed @").Append(i).ToString();
                    break;
                }
                else if (c == PathSeparatorChar && lastc == PathSeparatorChar)
                {
                    reason = new StringBuilder("empty node name specified @").Append(i).ToString();
                    break;
                }
                else if (c == PathDotChar && lastc == PathDotChar)
                {
                    if (chars[i - 2] == PathSeparatorChar &&
                            ((i + 1 == chars.Length)
                                    || chars[i + 1] == PathSeparatorChar))
                    {
                        reason = new StringBuilder("relative paths not allowed @").Append(i).ToString();
                        break;
                    }
                }
                else if (c == PathDotChar)
                {
                    if (chars[i - 1] == PathSeparatorChar &&
                            ((i + 1 == chars.Length)
                                    || chars[i + 1] == PathSeparatorChar))
                    {
                        reason = new StringBuilder("relative paths not allowed @").Append(i).ToString();
                        break;
                    }
                }
                else if (c > '\u0000' && c < '\u001f'
                      || c > '\u007f' && c < '\u009F'
                      || c > '\ud800' && c < '\uf8ff'
                      || c > '\ufff0' && c < '\uffff')
                {
                    reason = new StringBuilder("invalid charater @").Append(i).ToString();
                    break;
                }
            }

            if (reason != null)
            {
                throw new InvalidOperationException(new StringBuilder("Invalid path string \"").Append(path).Append("\" caused by ").Append(reason).ToString());
            }
        }
    }
}
