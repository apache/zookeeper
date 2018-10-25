/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.net.SocketImpl;

public class PrependableSocket extends Socket {

  private PushbackInputStream pushbackInputStream;

  public PrependableSocket(SocketImpl base) throws IOException {
    super(base);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (pushbackInputStream == null) {
      return super.getInputStream();
    }

    return pushbackInputStream;
  }

  /**
   * Prepend some bytes that have already been read back to the socket's input stream. Note that this method can be
   * called at most once with a non-0 length per socket instance.
   * @param bytes the bytes to prepend.
   * @param offset offset in the byte array to start at.
   * @param length number of bytes to prepend.
   * @throws IOException if this method was already called on the socket instance, or if super.getInputStream() throws.
   */
  public void prependToInputStream(byte[] bytes, int offset, int length) throws IOException {
    if (length == 0) {
      return; // nothing to prepend
    }
    if (pushbackInputStream != null) {
      throw new IOException("prependToInputStream() called more than once");
    }
    PushbackInputStream pushbackInputStream = new PushbackInputStream(getInputStream(), length);
    pushbackInputStream.unread(bytes, offset, length);
    this.pushbackInputStream = pushbackInputStream;
  }

}