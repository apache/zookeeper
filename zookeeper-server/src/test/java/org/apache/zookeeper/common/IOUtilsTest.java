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

package org.apache.zookeeper.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.ZKTestCase;
import org.junit.jupiter.api.Test;

public class IOUtilsTest extends ZKTestCase {

    @Test
    public void testCloseAllWithoutObjects() throws IOException {
        IOUtils.closeAll();
    }

    @Test
    public void testCloseAllInOrderIgnoringNulls() throws IOException {
        List<Integer> closed = new ArrayList<>();

        IOUtils.closeAll(null, () -> closed.add(1), null, () -> closed.add(2));

        assertEquals(List.of(1, 2), closed);
    }

    @Test
    public void testCloseAllPreservesFirstFailureAndSuppressesLaterFailures() {
        IOException first = new IOException("first");
        IOException second = new IOException("second");
        IOException third = new IOException("third");
        List<Integer> closed = new ArrayList<>();

        IOException failure = assertThrows(IOException.class, () -> IOUtils.closeAll(
            () -> {
                closed.add(1);
                throw first;
            },
            () -> {
                closed.add(2);
                throw second;
            },
            () -> {
                closed.add(3);
                throw third;
            },
            () -> closed.add(4)));

        assertSame(first, failure);
        assertArrayEquals(new Throwable[]{second, third}, failure.getSuppressed());
        assertEquals(List.of(1, 2, 3, 4), closed);
    }

    @Test
    public void testCloseAllPreservesFailureAfterSuccessfulClose() {
        IOException expected = new IOException("second");
        List<Integer> closed = new ArrayList<>();

        IOException failure = assertThrows(IOException.class, () -> IOUtils.closeAll(
            () -> closed.add(1),
            () -> {
                closed.add(2);
                throw expected;
            }));

        assertSame(expected, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(List.of(1, 2), closed);
    }

    @Test
    public void testCloseAllDoesNotSuppressAnExceptionOnItself() {
        IOException expected = new IOException("shared");
        List<Integer> closed = new ArrayList<>();

        IOException failure = assertThrows(IOException.class, () -> IOUtils.closeAll(
            () -> {
                throw expected;
            },
            () -> {
                throw expected;
            },
            () -> closed.add(3)));

        assertSame(expected, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(List.of(3), closed);
    }

}
