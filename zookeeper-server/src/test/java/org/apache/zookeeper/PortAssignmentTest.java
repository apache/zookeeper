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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class PortAssignmentTest {

    public static Stream<Arguments> data() throws Exception {
        return Stream.of(
                Arguments.of("8", "threadid=1", 11221, 13913),
                Arguments.of("8", "threadid=2", 13914, 16606),
                Arguments.of("8", "threadid=3", 16607, 19299),
                Arguments.of("8", "threadid=4", 19300, 21992),
                Arguments.of("8", "threadid=5", 21993, 24685),
                Arguments.of("8", "threadid=6", 24686, 27378),
                Arguments.of("8", "threadid=7", 27379, 30071),
                Arguments.of("8", "threadid=8", 30072, 32764),
                Arguments.of("1", "threadid=1", 11221, 32767),
                Arguments.of("2", "threadid=1", 11221, 21993),
                Arguments.of("2", "threadid=2", 21994, 32766),
                Arguments.of(null, null, 11221, 32767),
                Arguments.of("", "", 11221, 32767));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetupPortRange(String strProcessCount, String cmdLine, int expectedMinimumPort, int expectedMaximumPort) {
        PortAssignment.PortRange portRange = PortAssignment.setupPortRange(strProcessCount, cmdLine);
        assertEquals(expectedMinimumPort, portRange.getMinimum(), buildAssertionMessage("minimum", strProcessCount, cmdLine));
        assertEquals(expectedMaximumPort, portRange.getMaximum(), buildAssertionMessage("maximum", strProcessCount, cmdLine));
    }

    private String buildAssertionMessage(String checkType, String strProcessCount, String cmdLine) {
        return String.format("strProcessCount = %s, cmdLine = %s, checking %s", strProcessCount, cmdLine, checkType);
    }

}
