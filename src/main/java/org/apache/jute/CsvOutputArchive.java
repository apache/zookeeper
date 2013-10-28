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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.TreeMap;

/**
 *
 */
public class CsvOutputArchive implements OutputArchive {

    private PrintStream stream;
    private boolean isFirst = true;
    
    static CsvOutputArchive getArchive(OutputStream strm)
    throws UnsupportedEncodingException {
        return new CsvOutputArchive(strm);
    }
    
    private void throwExceptionOnError(String tag) throws IOException {
        if (stream.checkError()) {
            throw new IOException("Error serializing "+tag);
        }
    }
 
    private void printCommaUnlessFirst() {
        if (!isFirst) {
            stream.print(",");
        }
        isFirst = false;
    }
    
    /** Creates a new instance of CsvOutputArchive */
    public CsvOutputArchive(OutputStream out)
    throws UnsupportedEncodingException {
        stream = new PrintStream(out, true, "UTF-8");
    }
    
    public void writeByte(byte b, String tag) throws IOException {
        writeLong((long)b, tag);
    }
    
    public void writeBool(boolean b, String tag) throws IOException {
        printCommaUnlessFirst();
        String val = b ? "T" : "F";
        stream.print(val);
        throwExceptionOnError(tag);
    }
    
    public void writeInt(int i, String tag) throws IOException {
        writeLong((long)i, tag);
    }
    
    public void writeLong(long l, String tag) throws IOException {
        printCommaUnlessFirst();
        stream.print(l);
        throwExceptionOnError(tag);
    }
    
    public void writeFloat(float f, String tag) throws IOException {
        writeDouble((double)f, tag);
    }
    
    public void writeDouble(double d, String tag) throws IOException {
        printCommaUnlessFirst();
        stream.print(d);
        throwExceptionOnError(tag);
    }
    
    public void writeString(String s, String tag) throws IOException {
        printCommaUnlessFirst();
        stream.print(Utils.toCSVString(s));
        throwExceptionOnError(tag);
    }
    
    public void writeBuffer(byte buf[], String tag)
    throws IOException {
        printCommaUnlessFirst();
        stream.print(Utils.toCSVBuffer(buf));
        throwExceptionOnError(tag);
    }
    
    public void writeRecord(Record r, String tag) throws IOException {
        if (r == null) {
            return;
        }
        r.serialize(this, tag);
    }
    
    public void startRecord(Record r, String tag) throws IOException {
        if (tag != null && !"".equals(tag)) {
            printCommaUnlessFirst();
            stream.print("s{");
            isFirst = true;
        }
    }
    
    public void endRecord(Record r, String tag) throws IOException {
        if (tag == null || "".equals(tag)) {
            stream.print("\n");
            isFirst = true;
        } else {
            stream.print("}");
            isFirst = false;
        }
    }
    
    public void startVector(List v, String tag) throws IOException {
        printCommaUnlessFirst();
        stream.print("v{");
        isFirst = true;
    }
    
    public void endVector(List v, String tag) throws IOException {
        stream.print("}");
        isFirst = false;
    }
    
    public void startMap(TreeMap v, String tag) throws IOException {
        printCommaUnlessFirst();
        stream.print("m{");
        isFirst = true;
    }
    
    public void endMap(TreeMap v, String tag) throws IOException {
        stream.print("}");
        isFirst = false;
    }
}
