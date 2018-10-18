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
package org.apache.zookeeper.cli;

@SuppressWarnings("serial")
public class CliException extends Exception {

    protected int exitCode;

    protected static final int DEFAULT_EXCEPTION_EXIT_CODE = 1;

    public CliException(String message) {
        this(message, DEFAULT_EXCEPTION_EXIT_CODE);
    }

    public CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public CliException(Throwable cause) {
        this(cause, DEFAULT_EXCEPTION_EXIT_CODE);
    }

    public CliException(Throwable cause, int exitCode) {
        super(cause);
        this.exitCode = exitCode;
    }

    public CliException(String message, Throwable cause) {
        this(message, cause, DEFAULT_EXCEPTION_EXIT_CODE);
    }

    public CliException(String message, Throwable cause, int exitCode) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
