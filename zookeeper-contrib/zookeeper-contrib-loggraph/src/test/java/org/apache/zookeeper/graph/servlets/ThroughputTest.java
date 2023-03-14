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
package org.apache.zookeeper.graph.servlets;

import static org.mockito.Mockito.mock;
import org.apache.zookeeper.graph.FilterException;
import org.apache.zookeeper.graph.Log4JEntry;
import org.apache.zookeeper.graph.Log4JSource;
import org.apache.zookeeper.graph.LogEntry;
import org.apache.zookeeper.graph.LogIterator;
import org.apache.zookeeper.graph.TransactionEntry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class ThroughputTest {

    @Test
    public void testGetJSON() throws Exception {
        long scale = 1;
        Log4JSource source = mock(Log4JSource.class);
        Throughput tp = new Throughput(source);
        LogIterator iter = new MyIterator();
        String jsonString = tp.getJSON(iter, scale);
        String expected = "[{\"time\":3,\"count\":1},{\"time\":4,\"count\":1},{\"time\":5,\"count\":0}]";
        Assertions.assertEquals(expected, jsonString);
    }

    private class MyIterator implements LogIterator {
        int index = 0;
        List<LogEntry> list = new ArrayList<>();

        public MyIterator() throws IllegalArgumentException, FilterException {
            for(int i=1; i<3; i++){
                long timestamp = i;
                int node = i;
                String entry = Integer.toString(i);
                Log4JEntry le = new Log4JEntry(timestamp, node, entry);
                list.add(le);
            }
            for(int i=3; i<7; i++){
                long timestamp = i;
                long clientId = i;
                long Cxid = i;
                long Zxid = i;
                String op = Integer.toString(i);
                TransactionEntry te = new TransactionEntry(timestamp, clientId, Cxid, Zxid, op);
                list.add(te);
            }
        }

        synchronized public long size() throws IOException {
            return list.size();
        }

        public boolean hasNext() {
            return index<list.size()-1;
        }

        public LogEntry next() throws NoSuchElementException {
            return list.get(index++);
        }

        public void remove() throws UnsupportedOperationException {
            throw new UnsupportedOperationException("remove not supported for L4J logs");
        }

        public void close(){}
    }
}
