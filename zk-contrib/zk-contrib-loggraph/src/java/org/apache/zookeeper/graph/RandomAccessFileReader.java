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
package org.apache.zookeeper.graph;

import java.io.File;
import java.io.Reader;
import java.io.IOException;
import java.io.EOFException;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RandomAccessFileReader extends Reader implements DataInput {
    private static final Logger LOG = LoggerFactory.getLogger(RandomAccessFileReader.class);
    private RandomAccessFile file;
    private byte[] buffer;
    private int buffersize;
    private int bufferoffset;
    private long fileoffset;
    private long fp;

    private static final int DEFAULT_BUFFER_SIZE = 512*1024; // 512k
    private int point = 0;

    public RandomAccessFileReader(File f) throws FileNotFoundException {
	file = new RandomAccessFile(f, "r");
	if (LOG.isDebugEnabled()) {
	    try {
		LOG.debug("Opened file(" + f + ") with FD (" + file.getFD() + ")");
	    } catch (IOException ioe) { 
		LOG.debug("Opened file(" + f + ") coulds get FD");
	    }
	}

	buffer = new byte[DEFAULT_BUFFER_SIZE];
	buffersize = 0;
	bufferoffset = 0;
	fileoffset = 0;
	fp = 0;
    }

    /**
       fill the buffer from the file.
       fp keeps track of the file pointer.
       fileoffset is the offset into the file to where the buffer came from.
    */
    private int fill() throws IOException {
	fileoffset = fp;
	int read = file.read(buffer, 0, buffer.length);

	if (LOG.isDebugEnabled()) {
	    String buf = new String(buffer, 0, 40, "UTF-8");
	    LOG.debug("fill(buffer=" + buf + ")");
	}

	if (read == -1) { // eof reached
	    buffersize = 0;
	} else {
	    buffersize = read;
	}
	fp += buffersize;
	bufferoffset = 0;

	return buffersize;
    }

    /**
     * Reader interface 
     */
    public boolean markSupported() { return false; }

    /**
       copy what we can from buffer. if it's not enough, fill buffer again and copy again
    */
    synchronized public int read(char[] cbuf, int off, int len) throws IOException {
	// This could be faster, but probably wont be used
	byte[] b = new byte[2];
	int bytesread = 0;
	while (len > 0) {
	    int read = read(b, 0, 2);
	    bytesread += read;
	    if (read < 2) {
		return bytesread;
	    }
	    cbuf[off] = (char)((b[0] << 8) | (b[1] & 0xff));
	    off += read;
	    len -= read;
	}

	return bytesread;
    }

    synchronized public int read(byte[] buf, int off, int len) throws IOException {
	if (LOG.isTraceEnabled()) {
	    LOG.trace("read(buf, off=" + off + ", len=" + len);
	}

	int read = 0;
	while (len > 0) {
	    if (buffersize == 0) {
		fill();
		if (buffersize == 0) {
		    break;
		}
	    }

	    int tocopy = Math.min(len, buffersize);
	    if (LOG.isTraceEnabled()) {
		LOG.trace("tocopy=" + tocopy);
	    }

	    System.arraycopy(buffer, bufferoffset, buf, off, tocopy);
	    buffersize -= tocopy;
	    bufferoffset += tocopy;

	    len -= tocopy;
	    read += tocopy;
	    off += tocopy;
	}
	if (LOG.isTraceEnabled()) {
	    LOG.trace("read=" + read);
	}

	return read;
    }

    public void close() throws IOException {
	file.close();
    }

    /**
     * Seek interface 
     */
    public long getPosition() {
	return bufferoffset + fileoffset;
    }
    
    synchronized public void seek(long pos) throws IOException {
	if (LOG.isDebugEnabled()) {
	    LOG.debug("seek(" + pos + ")");
	}
	file.seek(pos);
	fp = pos;
	buffersize = 0; // force a buffer fill on next read
    }

    /**
       works like the usual readLine but disregards \r to make things easier
    */
    synchronized public String readLine() throws IOException {
	StringBuffer s = null;
	
	// go through buffer until i find a \n, if i reach end of buffer first, put whats in buffer into string buffer,
	// repeat
	buffering:
	for (;;) {
	    if (buffersize == 0) {
		fill();
		if (buffersize == 0) {
		    break;
		}
	    }

	    for (int i = 0; i < buffersize; i++) {
		if (buffer[bufferoffset + i] == '\n') { 
		    if (i > 0) { // if \n is first char in buffer, leave the string buffer empty
			if (s == null) { s = new StringBuffer(); }
			s.append(new String(buffer, bufferoffset, i, "UTF-8"));
		    }
		    bufferoffset += i+1;
		    buffersize -= i+1; 
		    break buffering;
		}
	    }

	    // We didn't find \n, read the whole buffer into string buffer
	    if (s == null) { s = new StringBuffer(); }
	    s.append(new String(buffer, bufferoffset, buffersize, "UTF-8"));
	    buffersize = 0; 
	}

	if (s == null) {
	    return null;
	} else {
	    return s.toString();
	}	    
    }

    /**
       DataInput interface
    */
    public void readFully(byte[] b) throws IOException {
	readFully(b, 0, b.length);
    }

    public void readFully(byte[] b, int off, int len) throws IOException
    {
	while (len > 0) {
	    int read = read(b, off, len);
	    len -= read;
	    off += read;

	    if (read == 0) {
		throw new EOFException("End of file reached");
	    }	    
	}
    }

    public int skipBytes(int n) throws IOException {
	seek(getPosition() + n);
	return n;
    }

    public boolean readBoolean() throws IOException {
	return (readByte() != 0);	    
    }

    public byte readByte() throws IOException {
	byte[] b = new byte[1];
	readFully(b, 0, 1);
	return b[0];
    }

    public int readUnsignedByte() throws IOException {
	return (int)readByte();
    }

    public short readShort() throws IOException {
	byte[] b = new byte[2];
	readFully(b, 0, 2);
	return (short)((b[0] << 8) | (b[1] & 0xff));
    }
    
    public int readUnsignedShort() throws IOException {
	byte[] b = new byte[2];
	readFully(b, 0, 2);
	return (((b[0] & 0xff) << 8) | (b[1] & 0xff));
    }

    public char readChar() throws IOException {
	return (char)readShort();
    }

    public int readInt() throws IOException {
	byte[] b = new byte[4];
	readFully(b, 0, 4);
	return (((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) |  ((b[2] & 0xff) << 8) | (b[3] & 0xff));
    }

    public long readLong() throws IOException {
	byte[] b = new byte[8];
	readFully(b, 0, 8);
	
	return (((long)(b[0] & 0xff) << 56) |  ((long)(b[1] & 0xff) << 48) |
		((long)(b[2] & 0xff) << 40) |  ((long)(b[3] & 0xff) << 32) |
		((long)(b[4] & 0xff) << 24) |  ((long)(b[5] & 0xff) << 16) |
		((long)(b[6] & 0xff) <<  8) |  ((long)(b[7] & 0xff)));
    }

    public float readFloat() throws IOException {
	return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
	return Double.longBitsToDouble(readLong());
    }

    public String readUTF() throws IOException {
	int len = readUnsignedShort();
	byte[] bytes = new byte[len+2];
	bytes[0] = (byte)((len >> 8) & 0xFF);
	bytes[1] = (byte)(len & 0xFF);
	readFully(bytes, 2, len);
	DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
	return dis.readUTF();
    }

    public static void main(String[] args) throws IOException {
	RandomAccessFileReader f = new RandomAccessFileReader(new File(args[0]));
	
	long pos0 = f.getPosition();
	for (int i = 0; i < 5; i++) {
	    System.out.println(f.readLine());
	}
	System.out.println("=============");
	long pos1 = f.getPosition();
	System.out.println("pos: " + pos1);
	for (int i = 0; i < 5; i++) {
	    System.out.println(f.readLine());
	}
	System.out.println("=============");
	f.seek(pos1);
	for (int i = 0; i < 5; i++) {
	    System.out.println(f.readLine());
	}
	System.out.println("=============");
	f.seek(pos0);
	for (int i = 0; i < 5; i++) {
	    System.out.println(f.readLine());
	}
	long pos2 = f.getPosition();
	System.out.println("=============");
	System.out.println(f.readLine());
	f.seek(pos2);
	System.out.println(f.readLine());
	f.close();
    }
};
