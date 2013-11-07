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

package org.apache.jute;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 *
 */
public class BinaryInputArchive implements InputArchive {
    
    private DataInput in;
    
    static public BinaryInputArchive getArchive(InputStream strm) {
        return new BinaryInputArchive(new DataInputStream(strm));
    }
    
    static private class BinaryIndex implements Index {
        private int nelems;
        BinaryIndex(int nelems) {
            this.nelems = nelems;
        }
        public boolean done() {
            return (nelems <= 0);
        }
        public void incr() {
            nelems--;
        }
    }
    /** Creates a new instance of BinaryInputArchive */
    public BinaryInputArchive(DataInput in) {
        this.in = in;
    }
    
    public byte readByte(String tag) throws IOException {
        return in.readByte();
    }
    
    public boolean readBool(String tag) throws IOException {
        return in.readBoolean();
    }
    
    public int readInt(String tag) throws IOException {
        return in.readInt();
    }
    
    public long readLong(String tag) throws IOException {
        return in.readLong();
    }
    
    public float readFloat(String tag) throws IOException {
        return in.readFloat();
    }
    
    public double readDouble(String tag) throws IOException {
        return in.readDouble();
    }
    
    public String readString(String tag) throws IOException {
    	int len = in.readInt();
    	if (len == -1) return null;
    	byte b[] = new byte[len];
    	in.readFully(b);
    	return new String(b, "UTF8");
    }
    
    static public final int maxBuffer = determineMaxBuffer();
    private static int determineMaxBuffer() {
        String maxBufferString = System.getProperty("jute.maxbuffer");
        try {
            return Integer.parseInt(maxBufferString);
        } catch(Exception e) {
            return 0xfffff;
        }
        
    }
    public byte[] readBuffer(String tag) throws IOException {
        int len = readInt(tag);
        if (len == -1) return null;
        if (len < 0 || len > maxBuffer) {
            throw new IOException("Unreasonable length = " + len);
        }
        byte[] arr = new byte[len];
        in.readFully(arr);
        return arr;
    }
    
    public void readRecord(Record r, String tag) throws IOException {
        r.deserialize(this, tag);
    }
    
    public void startRecord(String tag) throws IOException {}
    
    public void endRecord(String tag) throws IOException {}
    
    public Index startVector(String tag) throws IOException {
        int len = readInt(tag);
        if (len == -1) {
        	return null;
        }
		return new BinaryIndex(len);
    }
    
    public void endVector(String tag) throws IOException {}
    
    public Index startMap(String tag) throws IOException {
        return new BinaryIndex(readInt(tag));
    }
    
    public void endMap(String tag) throws IOException {}
    
}
