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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.server.backup.RestoreFromBackupTool;

/**
 * Restore command for ZkCli.
 */
public class RestoreCommand extends CliCommand {

  private RestoreFromBackupTool tool;
  private CommandLine cl;
  private static Options options = new Options();
  private static final String RESTORE_CMD_STR = "restore";
  private static final String OPTION_STR =
      "[" + OptionFullCommand.RESTORE_ZXID + "]/[" + OptionFullCommand.RESTORE_TIMESTAMP + "] ["
          + OptionFullCommand.BACKUP_STORE + "] [" + OptionFullCommand.SNAP_DESTINATION + "] ["
          + OptionFullCommand.LOG_DESTINATION + "] [" + OptionFullCommand.TIMETABLE_STORAGE_PATH
          + "](needed if restore to a timestamp) [" + OptionFullCommand.LOCAL_RESTORE_TEMP_DIR_PATH
          + "](optional) [" + OptionFullCommand.DRY_RUN + "](optional)";

  public final class OptionLongForm {
    /* Required if no restore timestamp is specified */
    public static final String RESTORE_ZXID = "restore_zxid";
    /* Required if no restore zxid is specified */
    public static final String RESTORE_TIMESTAMP = "restore_timestamp";
    /* Required */
    public static final String BACKUP_STORE = "backup_store";
    /* Required */
    public static final String SNAP_DESTINATION = "snap_destination";
    /* Required */
    public static final String LOG_DESTINATION = "log_destination";
    /* Optional. If not set, default to backup storage path */
    public static final String TIMETABLE_STORAGE_PATH = "timetable_storage_path";
    /* Optional. If not set, default to the log destination dir */
    public static final String LOCAL_RESTORE_TEMP_DIR_PATH = "local_restore_temp_dir_path";
    /* Optional. Default value false */
    public static final String DRY_RUN = "dry_run";

    // Create a private constructor so it can't be instantiated
    private OptionLongForm() {
    }
  }

  public final class OptionShortForm {
    public static final String RESTORE_ZXID = "z";
    public static final String RESTORE_TIMESTAMP = "t";
    public static final String BACKUP_STORE = "b";
    public static final String SNAP_DESTINATION = "s";
    public static final String LOG_DESTINATION = "l";
    public static final String TIMETABLE_STORAGE_PATH = "m";
    public static final String LOCAL_RESTORE_TEMP_DIR_PATH = "r";
    public static final String DRY_RUN = "n";

    // Create a private constructor so it can't be instantiated
    private OptionShortForm() {
    }
  }

  public final class OptionFullCommand {
    public static final String RESTORE_ZXID =
        "-" + OptionShortForm.RESTORE_ZXID + " " + OptionLongForm.RESTORE_ZXID;
    public static final String RESTORE_TIMESTAMP =
        "-" + OptionShortForm.RESTORE_TIMESTAMP + " " + OptionLongForm.RESTORE_TIMESTAMP;
    public static final String BACKUP_STORE =
        "-" + OptionShortForm.BACKUP_STORE + " " + OptionLongForm.BACKUP_STORE;
    public static final String SNAP_DESTINATION =
        "-" + OptionShortForm.SNAP_DESTINATION + " " + OptionLongForm.SNAP_DESTINATION;
    public static final String LOG_DESTINATION =
        "-" + OptionShortForm.LOG_DESTINATION + " " + OptionLongForm.LOG_DESTINATION;
    public static final String TIMETABLE_STORAGE_PATH =
        "-" + OptionShortForm.TIMETABLE_STORAGE_PATH + " " + OptionLongForm.TIMETABLE_STORAGE_PATH;
    public static final String LOCAL_RESTORE_TEMP_DIR_PATH =
        "-" + OptionShortForm.LOCAL_RESTORE_TEMP_DIR_PATH + " "
            + OptionLongForm.LOCAL_RESTORE_TEMP_DIR_PATH;
    public static final String DRY_RUN = "-" + OptionShortForm.DRY_RUN;

    // Create a private constructor so it can't be instantiated
    private OptionFullCommand() {
    }
  }

  static {
    options.addOption(new Option(OptionShortForm.RESTORE_ZXID, true, OptionLongForm.RESTORE_ZXID));
    options.addOption(
        new Option(OptionShortForm.RESTORE_TIMESTAMP, true, OptionLongForm.RESTORE_TIMESTAMP));
    options.addOption(new Option(OptionShortForm.BACKUP_STORE, true, OptionLongForm.BACKUP_STORE));
    options.addOption(
        new Option(OptionShortForm.SNAP_DESTINATION, true, OptionLongForm.SNAP_DESTINATION));
    options.addOption(
        new Option(OptionShortForm.LOG_DESTINATION, true, OptionLongForm.LOG_DESTINATION));
    options.addOption(new Option(OptionShortForm.TIMETABLE_STORAGE_PATH, true,
        OptionLongForm.TIMETABLE_STORAGE_PATH));
    options.addOption(new Option(OptionShortForm.LOCAL_RESTORE_TEMP_DIR_PATH, true,
        OptionLongForm.LOCAL_RESTORE_TEMP_DIR_PATH));
    options.addOption(new Option(OptionShortForm.DRY_RUN, false, OptionLongForm.DRY_RUN));
  }

  public RestoreCommand() {
    super(RESTORE_CMD_STR, OPTION_STR);
    tool = new RestoreFromBackupTool();
  }

  @Override
  public String getUsageStr() {
    return "Usage: RestoreFromBackupTool  " + RESTORE_CMD_STR + " " + OPTION_STR + "\n    "
        + OptionFullCommand.RESTORE_ZXID
        + ": the point to restore to, either the string 'latest' or a zxid in hex format. Choose one between this option or "
        + OptionFullCommand.RESTORE_TIMESTAMP
        + ", if both are specified, this option will be prioritized\n    "
        + OptionFullCommand.RESTORE_TIMESTAMP
        + ": the point to restore to, a timestamp in long format. Choose one between this option or "
        + OptionFullCommand.RESTORE_ZXID + ".\n    " + OptionFullCommand.BACKUP_STORE
        + ": the connection information for the backup store\n           For GPFS the format is: gpfs:<config_path>:<backup_path>:<namespace>\n    "
        + OptionFullCommand.SNAP_DESTINATION
        + ": local destination path for restored snapshots\n    "
        + OptionFullCommand.LOG_DESTINATION + ": local destination path for restored txlogs\n    "
        + OptionFullCommand.TIMETABLE_STORAGE_PATH
        + ": Needed if restore to a timestamp. Backup storage path for timetable files, for GPFS the format is: gpfs:<config_path>:<backup_path>:<namespace>, if not set, default to be same as backup storage path\n    "
        + OptionFullCommand.LOCAL_RESTORE_TEMP_DIR_PATH
        + ": Optional, local path for creating a temporary intermediate directory for restoration, the directory will be deleted after restoration is done\n    "
        + OptionFullCommand.DRY_RUN + " " + OptionLongForm.DRY_RUN
        + ": Optional, no files will be actually copied in a dry run";
  }

  @Override
  public CliCommand parse(String[] cmdArgs) throws CliParseException {
    Parser parser = new PosixParser();
    try {
      cl = parser.parse(options, cmdArgs);
    } catch (ParseException ex) {
      throw new CliParseException(getUsageStr(), ex);
    }
    if ((!cl.hasOption(OptionShortForm.RESTORE_ZXID) && !cl
        .hasOption(OptionShortForm.RESTORE_TIMESTAMP)) || !cl
        .hasOption(OptionShortForm.BACKUP_STORE) || !cl.hasOption(OptionShortForm.SNAP_DESTINATION)
        || !cl.hasOption(OptionShortForm.LOG_DESTINATION)) {
      throw new CliParseException("Missing required argument(s).\n" + getUsageStr());
    }
    return this;
  }

  @Override
  public boolean exec() throws CliException {
    return tool.runWithRetries(cl);
  }
}