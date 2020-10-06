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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;

public class FsTest {
    @Test
    public void testGenerateJSON() throws IOException {
        File[] files = new File[2];
        final File file1 = mock(File.class);
        when(file1.getName()).thenReturn("testDir");
        when(file1.isDirectory()).thenReturn(true);
        when(file1.getCanonicalPath()).thenReturn("/tmp/testDir");
        final File file2 = mock(File.class);
        when(file2.getName()).thenReturn("test");
        when(file2.isDirectory()).thenReturn(false);
        when(file2.getCanonicalPath()).thenReturn("/tmp/test");
        files[0]=file1;
        files[1]=file2;
        String output = Fs.generateJSON(files);
        String expectedOutput = "[{\"file\":\"testDir\",\"type\":\"D\",\"path\":\"/tmp/testDir\"}," +
                "{\"file\":\"test\",\"type\":\"F\",\"path\":\"/tmp/test\"}]";
        Assertions.assertEquals(expectedOutput, output);
    }
}
