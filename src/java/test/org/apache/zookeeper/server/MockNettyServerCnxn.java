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
package org.apache.zookeeper.server;

import org.apache.jute.Record;
import org.apache.zookeeper.proto.ReplyHeader;
import org.jboss.netty.channel.Channel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Helper class to test different scenarios in NettyServerCnxn
 */
public class MockNettyServerCnxn extends NettyServerCnxn {

  public MockNettyServerCnxn(Channel channel, ZooKeeperServer zks, NettyServerCnxnFactory factory) {
    super(channel, zks, factory);
  }

  @Override
  public void sendResponse(ReplyHeader h, Record r, String tag) throws IOException {
    String exceptionType = System.getProperty("exception.type", "IOException");
    switch(exceptionType) {
      case "IOException":
        throw new IOException("test IOException");
      case "NoException":
        super.sendResponse(h,r,tag);
        break;
      case "RunTimeException":
        try {
          Field zkServerField = NettyServerCnxn.class.getDeclaredField("zkServer");
          zkServerField.setAccessible(true);
          Field modifiersField = Field.class.getDeclaredField("modifiers");
          modifiersField.setAccessible(true);
          modifiersField.setInt(zkServerField, zkServerField.getModifiers() & ~Modifier.FINAL);
          zkServerField.set((NettyServerCnxn)this, null);
          super.sendResponse(h,r,tag);
        } catch (NoSuchFieldException e) {
          e.printStackTrace();
        } catch (IllegalAccessException e) {
          e.printStackTrace();
        }
        break;
      default:
        break;
    }
  }
}
