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

import java.util.Scanner;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.DefaultParser;
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
          + "](optional) [" + OptionFullCommand.DRY_RUN + "](optional) ["
          + OptionFullCommand.OVERWRITE + "](optional)\n Options for spot restoration: \n["
          + OptionFullCommand.ZNODE_PATH_TO_RESTORE + "] ["
          + OptionFullCommand.ZK_SERVER_CONNECTION_STRING + "] ["
          + OptionFullCommand.RECURSIVE_SPOT_RESTORE + "](optional)";

  public static final class OptionLongForm {
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
    /* Optional. Default value false */
    public static final String OVERWRITE = "overwrite";

    //For spot restoration
    /* Required */
    public static final String ZNODE_PATH_TO_RESTORE = "znode_path_to_restore";
    /* Required */
    public static final String ZK_SERVER_CONNECTION_STRING = "zk_server_connection_string";
    /* Optional. Default value false */
    public static final String RECURSIVE_SPOT_RESTORE = "recursive_spot_restore";

    // Create a private constructor so it can't be instantiated
    private OptionLongForm() {
    }
  }

  public static final class OptionShortForm {
    public static final String RESTORE_ZXID = "z";
    public static final String RESTORE_TIMESTAMP = "t";
    public static final String BACKUP_STORE = "b";
    public static final String SNAP_DESTINATION = "s";
    public static final String LOG_DESTINATION = "l";
    public static final String TIMETABLE_STORAGE_PATH = "m";
    public static final String LOCAL_RESTORE_TEMP_DIR_PATH = "r";
    public static final String DRY_RUN = "n";
    public static final String HELP = "h";
    public static final String OVERWRITE = "f";

    //For spot restoration
    public static final String ZNODE_PATH_TO_RESTORE = "p";
    public static final String ZK_SERVER_CONNECTION_STRING = "c";
    public static final String RECURSIVE_SPOT_RESTORE = "a";

    // Create a private constructor so it can't be instantiated
    private OptionShortForm() {
    }
  }

  public static final class OptionFullCommand {
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
    public static final String OVERWRITE = "-" + OptionShortForm.OVERWRITE;

    //For spot restoration
    public static final String ZNODE_PATH_TO_RESTORE =
        "-" + OptionShortForm.ZNODE_PATH_TO_RESTORE + " " + OptionLongForm.ZNODE_PATH_TO_RESTORE;
    public static final String ZK_SERVER_CONNECTION_STRING =
        "-" + OptionShortForm.ZK_SERVER_CONNECTION_STRING + " "
            + OptionLongForm.ZK_SERVER_CONNECTION_STRING;
    public static final String RECURSIVE_SPOT_RESTORE =
        "-" + OptionShortForm.RECURSIVE_SPOT_RESTORE;

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
    options.addOption(new Option(OptionShortForm.OVERWRITE, false, OptionLongForm.OVERWRITE));

    //For spot restoration
    options.addOption(new Option(OptionShortForm.ZNODE_PATH_TO_RESTORE, true,
        OptionLongForm.ZNODE_PATH_TO_RESTORE));
    options.addOption(new Option(OptionShortForm.ZK_SERVER_CONNECTION_STRING, true,
        OptionLongForm.ZK_SERVER_CONNECTION_STRING));
    options.addOption(new Option(OptionShortForm.RECURSIVE_SPOT_RESTORE, false,
        OptionLongForm.RECURSIVE_SPOT_RESTORE));
  }

  public RestoreCommand() {
    super(RESTORE_CMD_STR, OPTION_STR);
    tool = new RestoreFromBackupTool();
  }

  @Override
  public String getUsageStr() {
    return "Usage: RestoreFromBackupTool  " + RESTORE_CMD_STR + " " + OPTION_STR
        + "\n    Options for both offline restoration and spot restoration:\n    "
        + OptionFullCommand.RESTORE_ZXID
        + ": the point to restore to, either the string 'latest' or a zxid in hex format. "
        + "Choose one between this option or " + OptionFullCommand.RESTORE_TIMESTAMP
        + ", if both are specified, this option will be prioritized. "
        + "Required for both offline restoration and spot restoration.\n    "
        + OptionFullCommand.RESTORE_TIMESTAMP
        + ": the point to restore to, a timestamp in long format. Choose one between this option or "
        + OptionFullCommand.RESTORE_ZXID
        + ". Required for both offline restoration and spot restoration.\n    "
        + OptionFullCommand.BACKUP_STORE
        + ": the connection information for the backup store\n           "
        + "For GPFS the format is: gpfs:<config_path>:<backup_path>:<namespace>\n           "
        + "Required for both offline restoration and spot restoration.\n    "
        + OptionFullCommand.SNAP_DESTINATION + ": local destination path for restored snapshots. "
        + "Required for offline restoration.\n    " + OptionFullCommand.LOG_DESTINATION
        + ": local destination path for restored txlogs. "
        + "Required for offline restoration.\n    " + OptionFullCommand.TIMETABLE_STORAGE_PATH
        + ": Needed if restore to a timestamp. Backup storage path for timetable files. "
        + "For GPFS the format is: gpfs:<config_path>:<backup_path>:<namespace>. "
        + "If not set, default to be same as backup storage path\n    "
        + OptionFullCommand.LOCAL_RESTORE_TEMP_DIR_PATH
        + ": Required for spot restoration, and optional for offline restoration. "
        + "The restore tool will use this local path to stage temporary files needed for restoration work, "
        + "this directory will be deleted after restoration is done\n    "
        + OptionFullCommand.DRY_RUN + " " + OptionLongForm.DRY_RUN
        + ": Optional, no files will be actually copied in a dry run\n    "
        + OptionFullCommand.OVERWRITE + " " + OptionLongForm.OVERWRITE
        + ": Optional, default false. If true, all existing files will be wiped out and the directories "
        + "be populated with restored files\n    " + "Options for spot restoration only:\n    "
        + OptionFullCommand.ZNODE_PATH_TO_RESTORE
        + ": The znode path to restore in the zk server\n    "
        + OptionFullCommand.ZK_SERVER_CONNECTION_STRING
        + ": The connection string used to establish a client to server connection "
        + "in order to do spot restoration on zk server. "
        + "The format of this string should be host:port, " + "for example: 127.0.0.1:3000\n    "
        + OptionFullCommand.RECURSIVE_SPOT_RESTORE
        + ": Optional, default false. If false, the spot restoration will be done on one single node only; "
        + "if true, it will be done recursively on all of its descendants as well";
  }

  @Override
  public CliCommand parse(String[] cmdArgs) throws CliParseException {
    DefaultParser parser = new DefaultParser();
    try {
      cl = parser.parse(options, cmdArgs);
    } catch (ParseException ex) {
      throw new CliParseException(getUsageStr(), ex);
    }
    if ((!cl.hasOption(OptionShortForm.RESTORE_ZXID) && !cl
        .hasOption(OptionShortForm.RESTORE_TIMESTAMP)) || !cl
        .hasOption(OptionShortForm.BACKUP_STORE)) {
      throw new CliParseException("Missing required argument(s).\n" + getUsageStr());
    }
    return this;
  }

  @Override
  public boolean exec() throws CliException {
    if (cl.hasOption(OptionShortForm.HELP)) {
      out.println(getUsageStr());
      return true;
    }
    if (cl.hasOption(OptionShortForm.OVERWRITE)) {
      Scanner scanner = new Scanner(System.in);
      boolean repeat = true;
      while (repeat) {
        out.println(
            "Are you sure you want to overwrite the destination directories? Please enter \"yes/no\".");
        String input = scanner.nextLine().toLowerCase();
        switch (input) {
          case "yes":
            repeat = false;
            break;
          case "no":
            out.println(
                "Exiting restoration. Please remove the \"-f\" (overwrite) option from the command, and try again.");
            return false;
          default:
            out.println("Could not recognize the input: " + input + ". Please try again.");
            break;
        }
      }
    }
    return tool.runWithRetries(cl);
  }
}
