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

package org.apache.zookeeper.server.persistence;

class TxnLogToolkitCliParser {
    private String txnLogFileName;
    private boolean recoveryMode;
    private boolean verbose;
    private boolean force;

    String getTxnLogFileName() {
        return txnLogFileName;
    }

    boolean isRecoveryMode() {
        return recoveryMode;
    }

    boolean isVerbose() {
        return verbose;
    }

    boolean isForce() {
        return force;
    }

    void parse(String[] args) throws TxnLogToolkit.TxnLogToolkitParseException {
        if (args == null) {
            throw new TxnLogToolkit.TxnLogToolkitParseException(1, "No arguments given");
        }
        txnLogFileName = null;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String par = arg.substring(2);
                if ("help".equalsIgnoreCase(par)) {
                    printHelpAndExit(0);
                } else if ("recover".equalsIgnoreCase(par)) {
                    recoveryMode = true;
                } else if ("verbose".equalsIgnoreCase(par)) {
                    verbose = true;
                } else if ("dump".equalsIgnoreCase(par)) {
                    recoveryMode = false;
                } else if ("yes".equalsIgnoreCase(par)) {
                    force = true;
                } else {
                    throw new TxnLogToolkit.TxnLogToolkitParseException(1, "Invalid argument: %s", par);
                }
            } else if (arg.startsWith("-")) {
                String par = arg.substring(1);
                if ("h".equalsIgnoreCase(par)) {
                    printHelpAndExit(0);
                } else if ("r".equalsIgnoreCase(par)) {
                    recoveryMode = true;
                } else if ("v".equalsIgnoreCase(par)) {
                    verbose = true;
                } else if ("d".equalsIgnoreCase(par)) {
                    recoveryMode = false;
                } else if ("y".equalsIgnoreCase(par)) {
                    force = true;
                } else {
                    throw new TxnLogToolkit.TxnLogToolkitParseException(1, "Invalid argument: %s", par);
                }
            } else {
                if (txnLogFileName != null) {
                    throw new TxnLogToolkit.TxnLogToolkitParseException(1, "Invalid arguments: more than one TXN log file given");
                }
                txnLogFileName = arg;
            }
        }

        if (txnLogFileName == null) {
            throw new TxnLogToolkit.TxnLogToolkitParseException(1, "Invalid arguments: TXN log file name missing");
        }
    }

    static void printHelpAndExit(int exitCode) {
        System.out.println("usage: TxnLogToolkit [-dhrvy] txn_log_file_name\n");
        System.out.println("    -d,--dump      Dump mode. Dump all entries of the log file. (this is the default)");
        System.out.println("    -h,--help      Print help message");
        System.out.println("    -r,--recover   Recovery mode. Re-calculate CRC for broken entries.");
        System.out.println("    -v,--verbose   Be verbose in recovery mode: print all entries, not just fixed ones.");
        System.out.println("    -y,--yes       Non-interactive mode: repair all CRC errors without asking");
        System.exit(exitCode);
    }
}
