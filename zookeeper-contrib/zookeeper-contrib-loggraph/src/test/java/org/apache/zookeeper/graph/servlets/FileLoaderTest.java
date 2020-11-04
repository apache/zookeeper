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
import static org.mockito.Mockito.when;
import org.apache.zookeeper.graph.FilterException;
import org.apache.zookeeper.graph.FilterOp;
import org.apache.zookeeper.graph.LogIterator;
import org.apache.zookeeper.graph.LogSource;
import org.apache.zookeeper.graph.MergedLogSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import java.io.IOException;
import java.util.List;

public class FileLoaderTest {

    @Test
    public void testHandleRequestOK() throws Exception {
        String[] files = {""};
        MyMergedLogSource mls = new MyMergedLogSource(files);
        final JsonServlet.JsonRequest jsonRequest = mock(JsonServlet.JsonRequest.class);
        when(jsonRequest.getString("path", "/")).thenReturn("/tmp");
        FileLoader fl = new FileLoader(mls);
        String s = fl.handleRequest(jsonRequest);
        Assertions.assertEquals("{\"status\":\"OK\"}", s);
        Assertions.assertTrue(mls.getSources().contains(new MySource("/tmp")));
        Assertions.assertFalse(mls.getSources().contains(new MySource("/tmp2")));
    }

    @Test
    public void testHandleRequestERR() throws Exception {
        String[] files = {""};
        MyMergedLogSource mls = new MyMergedLogSource(files);
        final JsonServlet.JsonRequest jsonRequest = mock(JsonServlet.JsonRequest.class);
        when(jsonRequest.getString("path", "/")).thenReturn("/tmp3");
        FileLoader fl = new FileLoader(mls);
        String s = fl.handleRequest(jsonRequest);
        Assertions.assertEquals("{\"status\":\"ERR\",\"error\":\"java.io.IOException: Message\"}", s);
        Assertions.assertFalse(mls.getSources().contains(new MySource("/tmp")));
        Assertions.assertFalse(mls.getSources().contains(new MySource("/tmp2")));
    }

    private class MyMergedLogSource extends MergedLogSource {
        public MyMergedLogSource(String[] files) throws IOException {
            super(files);
        }
        public void addSource(String f) throws IOException {
            if ("/tmp3".equals(f)) throw new IOException("Message");
            sources.add(new MySource(f));
        }
        public List<LogSource> getSources(){
            return sources;
        }
    }

    private class MySource implements LogSource{
        private String file = null;
        public MySource(String file) throws IOException {
            this.file=file;
        }

        public boolean equals(Object o){
            if(!(o instanceof MySource)) return false;
            if(((MySource)o).file == null) return this.file==null;
            return ((MySource)o).file.equals(this.file);
        }

        @Override
        public LogIterator iterator(long starttime, long endtime, FilterOp filter) throws IllegalArgumentException, FilterException {
            return null;
        }

        @Override
        public LogIterator iterator(long starttime, long endtime) throws IllegalArgumentException {
            return null;
        }

        @Override
        public LogIterator iterator() throws IllegalArgumentException {
            return null;
        }

        @Override
        public boolean overlapsRange(long starttime, long endtime) {
            return false;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public long getStartTime() {
            return 0;
        }

        @Override
        public long getEndTime() {
            return 0;
        }
    }
}