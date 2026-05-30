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

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class HexDumpOutputFormatterTest {

    @Test
    public void testFormatOneReadableExample() {
        byte[] data = ("Hello," + "\n" + "Zoo" + "\u0001" + "Keep!").getBytes(StandardCharsets.UTF_8);
        String lineSeparator = System.lineSeparator();

        String expected = String.join(
            lineSeparator,
            "         +-------------------------------------------------+",
            "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |",
            "+--------+-------------------------------------------------+----------------+",
            "|00000000| 48 65 6c 6c 6f 2c 0a 5a 6f 6f 01 4b 65 65 70 21 |Hello,.Zoo.Keep!|",
            "+--------+-------------------------------------------------+----------------+");

        assertEquals(expected, HexDumpOutputFormatter.INSTANCE.format(data));
    }
}

