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

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.TreeMap;

/**
 *
 */
public class BinaryOutputArchive implements OutputArchive {
    private ByteBuffer bb = ByteBuffer.allocate(1024);

    private DataOutput out;
    
    public static BinaryOutputArchive getArchive(OutputStream strm) {
        return new BinaryOutputArchive(new DataOutputStream(strm));
    }
    
    /** Creates a new instance of BinaryOutputArchive */
    public BinaryOutputArchive(DataOutput out) {
        this.out = out;
    }
    
    public void writeByte(byte b, String tag) throws IOException {
        out.writeByte(b);
    }
    
    public void writeBool(boolean b, String tag) throws IOException {
        out.writeBoolean(b);
    }
    
    public void writeInt(int i, String tag) throws IOException {
        out.writeInt(i);
    }
    
    public void writeLong(long l, String tag) throws IOException {
        out.writeLong(l);
    }
    
    public void writeFloat(float f, String tag) throws IOException {
        out.writeFloat(f);
    }
    
    public void writeDouble(double d, String tag) throws IOException {
        out.writeDouble(d);
    }
    
    /**
     * create our own char encoder to utf8. This is faster 
     * then string.getbytes(UTF8).
     * @param s the string to encode into utf8
     * @return utf8 byte sequence.
     */
    final private ByteBuffer stringToByteBuffer(CharSequence s) {
        bb.clear();
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            if (bb.remaining() < 3) {
                ByteBuffer n = ByteBuffer.allocate(bb.capacity() << 1);
                bb.flip();
                n.put(bb);
                bb = n;
            }
            char c = s.charAt(i);
            if (c < 0x80) {
                bb.put((byte) c);
            } else if (c < 0x800) {
                bb.put((byte) (0xc0 | (c >> 6)));
                bb.put((byte) (0x80 | (c & 0x3f)));
            } else {
                bb.put((byte) (0xe0 | (c >> 12)));
                bb.put((byte) (0x80 | ((c >> 6) & 0x3f)));
                bb.put((byte) (0x80 | (c & 0x3f)));
            }
        }
        bb.flip();
        return bb;
    }

    public void writeString(String s, String tag) throws IOException {
        if (s == null) {
            writeInt(-1, "len");
            return;
        }
        ByteBuffer bb = stringToByteBuffer(s);
        writeInt(bb.remaining(), "len");
        out.write(bb.array(), bb.position(), bb.limit());
    }

    public void writeBuffer(byte barr[], String tag)
    throws IOException {
    	if (barr == null) {
    		out.writeInt(-1);
    		return;
    	}
    	out.writeInt(barr.length);
        out.write(barr);
    }
    
    public void writeRecord(Record r, String tag) throws IOException {
        r.serialize(this, tag);
    }
    
    public void startRecord(Record r, String tag) throws IOException {}
    
    public void endRecord(Record r, String tag) throws IOException {}
    
    public void startVector(List<?> v, String tag) throws IOException {
    	if (v == null) {
    		writeInt(-1, tag);
    		return;
    	}
        writeInt(v.size(), tag);
    }
    
    public void endVector(List<?> v, String tag) throws IOException {}

    public void startMap(TreeMap<?,?> v, String tag) throws IOException {
        writeInt(v.size(), tag);
    }
    
    public void endMap(TreeMap<?,?> v, String tag) throws IOException {}
    
}
