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
package org.apache.zookeeper.server.backup.exception;

/**
 * Signals a logical error has occurred during the ZooKeeper backup/restore process.
 * Note this error does not mean an error in I/O, but means the object for the operation is not in appropriate state.
 * For example: failure to copy a file which maybe due to invalid path or source file does not exist.
 */
public class BackupException extends RuntimeException {
  public BackupException(String message) {
    super(message);
  }

  public BackupException(String message, Exception e) {
    super(message, e);
  }
}
