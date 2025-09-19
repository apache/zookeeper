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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for handling secret such as key/trust store password
 */
public final class SecretUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SecretUtils.class);

    private SecretUtils() {
    }

    public static char[] readSecret(final String pathToFile) {
        LOG.info("Reading secret from {}", pathToFile);

        try {
            final String secretValue = new String(
                    Files.readAllBytes(Paths.get(pathToFile)), StandardCharsets.UTF_8);

            if (secretValue.endsWith(System.lineSeparator())) {
                return secretValue.substring(0, secretValue.length() - System.lineSeparator().length()).toCharArray();
            }

            return secretValue.toCharArray();
        } catch (final Throwable e) {
            LOG.error("Exception occurred when reading secret from file {}", pathToFile, e);
            throw new IllegalStateException("Exception occurred when reading secret from file " + pathToFile, e);
        }
    }
}
