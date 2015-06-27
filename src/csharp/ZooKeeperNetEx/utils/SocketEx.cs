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
 * All rights reserved.
 * 
 */
using System.Net.Sockets;

namespace org.apache.utils
{
    internal static class SocketEx
    {
        public static void read(this Socket socket, ByteBuffer byteBuffer)
        {
            int size = byteBuffer.remaining();
            byte[] data = new byte[size];
            int index = 0;
            while (index < size)
            {
                SocketError socketErrorCode;
                int read = socket.Receive(data, index, size - index, SocketFlags.None, out socketErrorCode);
                if (socketErrorCode != SocketError.WouldBlock && socketErrorCode != SocketError.Success)
                {
                    throw new SocketException((int)socketErrorCode);
                }
                if (read == 0) break;
                index += read;
            }
            byteBuffer.Stream.Write(data, 0, index);
        }

        public static void write(this Socket socket, ByteBuffer byteBuffer)
        {
            byte[] data = byteBuffer.Stream.ToArray();
            int bytesToWrite = byteBuffer.remaining();
            int bytesWritten = (int)byteBuffer.Stream.Position;
            do
            {
                SocketError socketErrorCode;
                int actuallyWritten = socket.Send(data, bytesWritten, bytesToWrite, SocketFlags.None, out socketErrorCode);
                if (socketErrorCode != SocketError.WouldBlock && socketErrorCode != SocketError.Success)
                {
                    throw new SocketException((int)socketErrorCode);
                }
                if (actuallyWritten == 0) break;
                byteBuffer.Stream.Position += actuallyWritten;
                bytesWritten += actuallyWritten;
                bytesToWrite -= actuallyWritten;

            } while (bytesToWrite > 0);
        }
    }
}
