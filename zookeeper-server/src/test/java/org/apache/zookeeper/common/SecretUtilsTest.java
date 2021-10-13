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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SecretUtilsTest {

    @ParameterizedTest
    @ValueSource (strings = {"test secret", ""})
    public void testReadSecret(final String secretTxt) throws Exception {
        final Path secretFile = createSecretFile(secretTxt);

        final char[] secret = SecretUtils.readSecret(secretFile.toString());
        assertEquals(secretTxt, String.valueOf(secret));
    }

    @Test
    public void tesReadSecret_withLineSeparator() throws Exception {
        final String secretTxt = "test secret  with line separator" + System.lineSeparator();
        final Path secretFile = createSecretFile(secretTxt);

        final char[] secret = SecretUtils.readSecret(secretFile.toString());
        assertEquals(secretTxt.substring(0, secretTxt.length() - 1), String.valueOf(secret));
    }

    @Test
    public void testReadSecret_fileNotExist() {
        final String pathToFile = "NonExistingFile";
        final IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> SecretUtils.readSecret(pathToFile));
        assertEquals("Exception occurred while reading secret from file " + pathToFile, exception.getMessage());
    }

    public static Path createSecretFile(final String secretTxt) throws IOException {
        final Path path = Files.createTempFile("test_", ".secrete");

        final BufferedWriter writer = new BufferedWriter(new FileWriter(path.toString()));
        writer.append(secretTxt);
        writer.close();

        path.toFile().deleteOnExit();
        return path;
    }
}
