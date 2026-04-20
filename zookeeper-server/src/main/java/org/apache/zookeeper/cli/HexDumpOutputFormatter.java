/*
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

package org.apache.zookeeper.cli;

public class HexDumpOutputFormatter implements OutputFormatter {

    public static final HexDumpOutputFormatter INSTANCE = new HexDumpOutputFormatter();

    private static final int BYTES_PER_ROW = 16;
    private static final int ASCII_PRINTABLE_MIN = 0x20; // space
    private static final int ASCII_PRINTABLE_MAX = 0x7f; // DEL (exclusive)
    private static final String HEADER_LINE =
        "         +-------------------------------------------------+\n"
        + "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n"
        + "+--------+-------------------------------------------------+----------------+";
    private static final String FOOTER_LINE =
        "+--------+-------------------------------------------------+----------------+";

    @Override
    public String format(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER_LINE).append('\n');
        for (int offset = 0; offset < data.length; offset += BYTES_PER_ROW) {
            sb.append(String.format("|%08x|", offset));
            StringBuilder charPart = new StringBuilder();
            for (int i = 0; i < BYTES_PER_ROW; i++) {
                if (offset + i < data.length) {
                    int b = data[offset + i] & 0xFF;
                    sb.append(String.format(" %02x", b));
                    char c = (char) b;
                    charPart.append(c >= ASCII_PRINTABLE_MIN && c < ASCII_PRINTABLE_MAX ? c : '.');
                } else {
                    sb.append("   ");
                    charPart.append(' ');
                }
            }
            sb.append("  |").append(charPart).append("|\n");
        }
        sb.append(FOOTER_LINE);
        return sb.toString();
    }
}
