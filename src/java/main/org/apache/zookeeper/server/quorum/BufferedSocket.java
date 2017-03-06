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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketImpl;

public class BufferedSocket extends Socket {

  private BufferedInputStream bufferedInputStream;
  private BufferedOutputStream bufferedOutputStream;

  public BufferedSocket(SocketImpl base) throws IOException {
    super(base);
  }

  @Override
  public BufferedInputStream getInputStream() throws IOException {
    if (bufferedInputStream == null) {
      bufferedInputStream = new BufferedInputStream(super.getInputStream());
    }

    return bufferedInputStream;
  }

  @Override
  public BufferedOutputStream getOutputStream() throws IOException {
    if (bufferedOutputStream == null) {
      bufferedOutputStream = new BufferedOutputStream(super.getOutputStream());
    }

    return bufferedOutputStream;
  }

}