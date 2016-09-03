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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.io.IOException;

import org.apache.jute.Record;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.server.NIOServerCnxnFactory.SelectorThread;

public class MockNIOServerCnxn extends NIOServerCnxn {

    public MockNIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
                         SelectionKey sk, NIOServerCnxnFactory factory,
                         SelectorThread selectorThread) throws IOException {
        super(zk, sock, sk, factory, selectorThread);
    }

    /**
     * Handles read/write IO on connection.
     */
    public void doIO(SelectionKey k) throws InterruptedException {
        super.doIO(k);
    }

    @Override
    protected boolean isSocketOpen() {
        // trying to reuse this class for different tests.
        // problem with Java, that I can not create a class that inherits from some other - like <T extends ServerCnxn>
        if (System.getProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN) != null) {
            return super.isSocketOpen();
        }
        return true;
    }

    @Override
    public void sendResponse(ReplyHeader h, Record r, String tag) throws IOException {
        String exceptionType = System.getProperty("exception.type", "NoException");
        switch(exceptionType) {
            case "IOException":
                throw new IOException("test IOException");
            case "NoException":
                super.sendResponse(h,r,tag);
                break;
            case "RunTimeException":
                try {
                    Field zkServerField = NIOServerCnxn.class.getDeclaredField("zkServer");
                    zkServerField.setAccessible(true);
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
                    modifiersField.setInt(zkServerField, zkServerField.getModifiers() & ~Modifier.FINAL);
                    zkServerField.set((NIOServerCnxn)this, null);
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
