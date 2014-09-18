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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Various utility functions for Hadoop record I/O runtime.
 */
public class Utils {
    
    /** Cannot create a new instance of Utils */
    private Utils() {
        super();
    }
   
    /**
     * equals function that actually compares two buffers.
     *
     * @param onearray First buffer
     * @param twoarray Second buffer
     * @return true if one and two contain exactly the same content, else false.
     */
    public static boolean bufEquals(byte onearray[], byte twoarray[] ) {
    	if (onearray == twoarray) return true;
        boolean ret = (onearray.length == twoarray.length);
        if (!ret) {
            return ret;
        }
        for (int idx = 0; idx < onearray.length; idx++) {
            if (onearray[idx] != twoarray[idx]) {
                return false;
            }
        }
        return true;
    }
    
    private static final char[] hexchars = { '0', '1', '2', '3', '4', '5',
                                            '6', '7', '8', '9', 'A', 'B',
                                            'C', 'D', 'E', 'F' };
    /**
     * 
     * @param s 
     * @return 
     */
    static String toXMLString(String s) {
        if (s == null)
            return "";

        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < s.length(); idx++) {
          char ch = s.charAt(idx);
          if (ch == '<') {
            sb.append("&lt;");
          } else if (ch == '&') {
            sb.append("&amp;");
          } else if (ch == '%') {
            sb.append("%25");
          } else if (ch < 0x20) {
            sb.append("%");
            sb.append(hexchars[ch/16]);
            sb.append(hexchars[ch%16]);
          } else {
            sb.append(ch);
          }
        }
        return sb.toString();
    }
    
    static private int h2c(char ch) {
      if (ch >= '0' && ch <= '9') {
        return ch - '0';
      } else if (ch >= 'A' && ch <= 'F') {
        return ch - 'A';
      } else if (ch >= 'a' && ch <= 'f') {
        return ch - 'a';
      }
      return 0;
    }
    
    /**
     * 
     * @param s 
     * @return 
     */
    static String fromXMLString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < s.length();) {
          char ch = s.charAt(idx++);
          if (ch == '%') {
            char ch1 = s.charAt(idx++);
            char ch2 = s.charAt(idx++);
            char res = (char)(h2c(ch1)*16 + h2c(ch2));
            sb.append(res);
          } else {
            sb.append(ch);
          }
        }
        
        return sb.toString();
    }
    
    /**
     * 
     * @param s 
     * @return 
     */
    static String toCSVString(String s) {
        if (s == null)
            return "";

        StringBuilder sb = new StringBuilder(s.length()+1);
        sb.append('\'');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch(c) {
                case '\0':
                    sb.append("%00");
                    break;
                case '\n':
                    sb.append("%0A");
                    break;
                case '\r':
                    sb.append("%0D");
                    break;
                case ',':
                    sb.append("%2C");
                    break;
                case '}':
                    sb.append("%7D");
                    break;
                case '%':
                    sb.append("%25");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
    
    /**
     * 
     * @param s 
     * @throws java.io.IOException 
     * @return 
     */
    static String fromCSVString(String s) throws IOException {
        if (s.charAt(0) != '\'') {
            throw new IOException("Error deserializing string.");
        }
        int len = s.length();
        StringBuilder sb = new StringBuilder(len-1);
        for (int i = 1; i < len; i++) {
            char c = s.charAt(i);
            if (c == '%') {
                char ch1 = s.charAt(i+1);
                char ch2 = s.charAt(i+2);
                i += 2;
                if (ch1 == '0' && ch2 == '0') { sb.append('\0'); }
                else if (ch1 == '0' && ch2 == 'A') { sb.append('\n'); }
                else if (ch1 == '0' && ch2 == 'D') { sb.append('\r'); }
                else if (ch1 == '2' && ch2 == 'C') { sb.append(','); }
                else if (ch1 == '7' && ch2 == 'D') { sb.append('}'); }
                else if (ch1 == '2' && ch2 == '5') { sb.append('%'); }
                else {throw new IOException("Error deserializing string.");}
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    /**
     * 
     * @param s 
     * @return 
     */
    static String toXMLBuffer(byte barr[]) {
        if (barr == null || barr.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(2*barr.length);
        for (int idx = 0; idx < barr.length; idx++) {
            sb.append(Integer.toHexString(barr[idx]));
        }
        return sb.toString();
    }
    
    /**
     * 
     * @param s 
     * @throws java.io.IOException 
     * @return 
     */
    static byte[] fromXMLBuffer(String s)
    throws IOException {
        ByteArrayOutputStream stream =  new ByteArrayOutputStream();
        if (s.length() == 0) { return stream.toByteArray(); }
        int blen = s.length()/2;
        byte[] barr = new byte[blen];
        for (int idx = 0; idx < blen; idx++) {
            char c1 = s.charAt(2*idx);
            char c2 = s.charAt(2*idx+1);
            barr[idx] = Byte.parseByte(""+c1+c2, 16);
        }
        stream.write(barr);
        return stream.toByteArray();
    }
    
    /**
     * 
     * @param buf 
     * @return 
     */
    static String toCSVBuffer(byte barr[]) {
        if (barr == null || barr.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(barr.length + 1);
        sb.append('#');
        for(int idx = 0; idx < barr.length; idx++) {
            sb.append(Integer.toHexString(barr[idx]));
        }
        return sb.toString();
    }
    
    /**
     * Converts a CSV-serialized representation of buffer to a new
     * ByteArrayOutputStream.
     * @param s CSV-serialized representation of buffer
     * @throws java.io.IOException 
     * @return Deserialized ByteArrayOutputStream
     */
    static byte[] fromCSVBuffer(String s)
    throws IOException {
        if (s.charAt(0) != '#') {
            throw new IOException("Error deserializing buffer.");
        }
        ByteArrayOutputStream stream =  new ByteArrayOutputStream();
        if (s.length() == 1) { return stream.toByteArray(); }
        int blen = (s.length()-1)/2;
        byte[] barr = new byte[blen];
        for (int idx = 0; idx < blen; idx++) {
            char c1 = s.charAt(2*idx+1);
            char c2 = s.charAt(2*idx+2);
            barr[idx] = Byte.parseByte(""+c1+c2, 16);
        }
        stream.write(barr);
        return stream.toByteArray();
    }
    public static int compareBytes(byte b1[], int off1, int len1, byte b2[], int off2, int len2) {
        int i;
        for(i=0; i < len1 && i < len2; i++) {
            if (b1[off1+i] != b2[off2+i]) {
                return b1[off1+i] < b2[off2+i] ? -1 : 1;
            }
        }
        if (len1 != len2) {
            return len1 < len2 ? -1 : 1;
        }
        return 0;
    }
}
