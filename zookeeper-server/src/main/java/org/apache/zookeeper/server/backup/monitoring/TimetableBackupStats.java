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

package org.apache.zookeeper.server.backup.monitoring;

import java.util.concurrent.TimeUnit;

/**
 * TimetableBackupStats contains fields that represent metrics for timetable backup.
 */
public class TimetableBackupStats {
  /*
    Timetable backup metric declarations
  */
  private int failedTimetableIterationCount = 0;
  private boolean timetableBackupActive = false;
  private long lastSuccessfulTimetableBackupIterationFinishTime = System.currentTimeMillis();
  private long timetableIterationDuration = 0L;
  private long lastTimetableBackupIterationStartTime = System.currentTimeMillis();

  // Timetable backup metrics

  /**
   * Counter
   * For example: if backup iteration A fails, the number is 1; if next backup iteration B succeeds,
   * the number is reset to 0.
   * If A fails, the number is 1; if then the next iteration B fails again, the number is
   * incremented to 2.
   * @return Number of consecutive timetable backup errors since last successful timetable backup
   *         iteration
   */
  public int getNumConsecutiveFailedTimetableIterations() {
    return failedTimetableIterationCount;
  }

  /**
   * Counter
   * @return Time passed (minutes) since last successful timetable backup iteration
   */
  public long getMinutesSinceLastSuccessfulTimetableIteration() {
    return TimeUnit.MILLISECONDS
        .toMinutes(System.currentTimeMillis() - lastSuccessfulTimetableBackupIterationFinishTime);
  }

  /**
   * Gauge
   * @return true if timetable backup is currently in progress
   */
  public boolean getTimetableBackupActiveStatus() {
    return timetableBackupActive;
  }

  /**
   * Gauge
   * @return How long it took to complete the last timetable backup iteration
   */
  public long getTimetableIterationDuration() {
    return timetableIterationDuration;
  }

  /**
   * Record the status and timestamp when a timetable backup iteration starts
   */
  public void setTimetableBackupIterationStart() {
    lastTimetableBackupIterationStartTime = System.currentTimeMillis();
    timetableBackupActive = true;
  }

  /**
   * Record the status and timestamp when a timetable backup iteration finishes.
   * @param errorFree If this iteration finishes without error
   */
  public void setTimetableBackupIterationDone(boolean errorFree) {
    long finishTime = System.currentTimeMillis();
    timetableBackupActive = false;
    timetableIterationDuration = finishTime - lastTimetableBackupIterationStartTime;
    if (errorFree) {
      lastSuccessfulTimetableBackupIterationFinishTime = finishTime;
      failedTimetableIterationCount = 0;
    } else {
      failedTimetableIterationCount++;
    }
  }
}
