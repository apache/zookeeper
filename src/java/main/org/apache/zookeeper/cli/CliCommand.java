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

import java.io.PrintStream;
import java.util.Map;
import org.apache.zookeeper.ZooKeeper;

/**
 * base class for all CLI commands
 */
abstract public class CliCommand {
    protected ZooKeeper zk;
    protected PrintStream out;
    protected PrintStream err;
    private String cmdStr;
    private String optionStr;

    /**
     * a CLI command with command string and options.
     * Using System.out and System.err for printing
     * @param cmdStr the string used to call this command
     * @param optionStr the string used to call this command 
     */
    public CliCommand(String cmdStr, String optionStr) {
        this.out = System.out;
        this.err = System.err;
        this.cmdStr = cmdStr;
        this.optionStr = optionStr;
    }

    /**
     * Set out printStream (useable for testing)
     * @param out 
     */
    public void setOut(PrintStream out) {
        this.out = out;
    }

    /**
     * Set err printStream (useable for testing)
     * @param err 
     */
    public void setErr(PrintStream err) {
        this.err = err;
    }

    /**
     * set the zookeper instance
     * @param zk the ZooKeeper instance.
     */
    public void setZk(ZooKeeper zk) {
        this.zk = zk;
    }

    /**
     * get the string used to call this command
     * @return 
     */
    public String getCmdStr() {
        return cmdStr;
    }

    /**
     * get the option string
     * @return 
     */
    public String getOptionStr() {
        return optionStr;
    }

    /**
     * get a usage string, contains the command and the options
     * @return 
     */
    public String getUsageStr() {
        return cmdStr + " " + optionStr;
    }

    /**
     * add this command to a map. Use the command string as key.
     * @param cmdMap 
     */
    public void addToMap(Map<String, CliCommand> cmdMap) {
        cmdMap.put(cmdStr, this);
    }
    
    /**
     * parse the command arguments
     * @param cmdArgs
     * @return this CliCommand
     * @throws CliParseException
     */
    abstract public CliCommand parse(String cmdArgs[]) throws CliParseException;
    
    /**
     * 
     * @return
     * @throws CliException
     */
    abstract public boolean exec() throws CliException;
}
