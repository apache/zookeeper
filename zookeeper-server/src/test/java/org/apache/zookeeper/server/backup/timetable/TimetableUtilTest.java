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

package org.apache.zookeeper.server.backup.timetable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.TreeMap;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TimetableUtilTest {
  private static final int NUM_BACKUP_FILES = 5;
  private static final File[] TIMETABLE_BACKUP_FILES = new File[NUM_BACKUP_FILES];

  /**
   * Creates a timetable sequence: (10, 10), (20, 20), ..., (50, 50) for testing
   * @throws IOException
   */
  @BeforeClass
  public static void setupTestData() throws IOException {
    for (int i = 1; i <= NUM_BACKUP_FILES; i++) {
      // Multiple by 10 to give it space in between
      createTimetableBackupFile(i * 10, i * 10, i - 1);
    }
  }

  @AfterClass
  public static void removeTestData() {
    for (File file : TIMETABLE_BACKUP_FILES) {
      file.delete();
    }
  }

  @Test
  public void testFindLastZxidFromTimestamp() {
    String result =
        TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(25L));
    Assert.assertEquals(Long.toHexString(20), result);

    result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(35L));
    Assert.assertEquals(Long.toHexString(30), result);

    result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(50L));
    Assert.assertEquals(Long.toHexString(50), result);

    result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(10L));
    Assert.assertEquals(Long.toHexString(10), result);

    result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(41L));
    Assert.assertEquals(Long.toHexString(40), result);
  }

  @Test
  public void testFindLatestZxidFromTimestamp() {
    String result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, "latest");
    Assert.assertEquals(Long.toHexString(50), result);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTimestampOutsideWindowLow() {
    String result = TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(5));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testTimestampOutsideWindowHigh() {
    String result =
        TimetableUtil.findLastZxidFromTimestamp(TIMETABLE_BACKUP_FILES, Long.toString(55));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyFiles() {
    String result = TimetableUtil.findLastZxidFromTimestamp(null, Long.toString(25));
  }

  private static void createTimetableBackupFile(long timestamp, long zxid, int fileNum)
      throws IOException {
    Map<Long, String> timetableRecords = new TreeMap<>();
    timetableRecords.put(timestamp, Long.toHexString(zxid));

    File file = new File("timetable." + timestamp + "-" + timestamp);
    FileOutputStream f = new FileOutputStream(file);
    ObjectOutputStream s = new ObjectOutputStream(f);
    s.writeObject(timetableRecords);
    s.close();

    TIMETABLE_BACKUP_FILES[fileNum] = file;
  }
}
