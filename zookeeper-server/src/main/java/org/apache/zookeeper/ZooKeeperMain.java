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

package org.apache.zookeeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.cli.AddAuthCommand;
import org.apache.zookeeper.cli.CliCommand;
import org.apache.zookeeper.cli.CliException;
import org.apache.zookeeper.cli.CloseCommand;
import org.apache.zookeeper.cli.CommandNotFoundException;
import org.apache.zookeeper.cli.CreateCommand;
import org.apache.zookeeper.cli.DelQuotaCommand;
import org.apache.zookeeper.cli.DeleteAllCommand;
import org.apache.zookeeper.cli.DeleteCommand;
import org.apache.zookeeper.cli.GetAclCommand;
import org.apache.zookeeper.cli.GetAllChildrenNumberCommand;
import org.apache.zookeeper.cli.GetCommand;
import org.apache.zookeeper.cli.GetConfigCommand;
import org.apache.zookeeper.cli.GetEphemeralsCommand;
import org.apache.zookeeper.cli.ListQuotaCommand;
import org.apache.zookeeper.cli.Ls2Command;
import org.apache.zookeeper.cli.LsCommand;
import org.apache.zookeeper.cli.MalformedCommandException;
import org.apache.zookeeper.cli.ReconfigCommand;
import org.apache.zookeeper.cli.RemoveWatchesCommand;
import org.apache.zookeeper.cli.SetAclCommand;
import org.apache.zookeeper.cli.SetCommand;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.cli.StatCommand;
import org.apache.zookeeper.cli.SyncCommand;
import org.apache.zookeeper.cli.VersionCommand;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ExitCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command line client to ZooKeeper.
 *
 */
@InterfaceAudience.Public
public class ZooKeeperMain {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperMain.class);
    static final Map<String, String> commandMap = new HashMap<String, String>();
    static final Map<String, CliCommand> commandMapCli = new HashMap<String, CliCommand>();

    protected MyCommandOptions cl = new MyCommandOptions();
    protected HashMap<Integer, String> history = new HashMap<Integer, String>();
    protected int commandCount = 0;
    protected boolean printWatches = true;
    protected int exitCode = ExitCode.EXECUTION_FINISHED.getValue();

    protected ZooKeeper zk;
    protected String host = "";

    public boolean getPrintWatches() {
        return printWatches;
    }

    static {
        commandMap.put("connect", "host:port");
        commandMap.put("history", "");
        commandMap.put("redo", "cmdno");
        commandMap.put("printwatches", "on|off");
        commandMap.put("quit", "");

        new CloseCommand().addToMap(commandMapCli);
        new CreateCommand().addToMap(commandMapCli);
        new DeleteCommand().addToMap(commandMapCli);
        new DeleteAllCommand().addToMap(commandMapCli);
        // Depricated: rmr
        new DeleteAllCommand("rmr").addToMap(commandMapCli);
        new SetCommand().addToMap(commandMapCli);
        new GetCommand().addToMap(commandMapCli);
        new LsCommand().addToMap(commandMapCli);
        new Ls2Command().addToMap(commandMapCli);
        new GetAclCommand().addToMap(commandMapCli);
        new SetAclCommand().addToMap(commandMapCli);
        new StatCommand().addToMap(commandMapCli);
        new SyncCommand().addToMap(commandMapCli);
        new SetQuotaCommand().addToMap(commandMapCli);
        new ListQuotaCommand().addToMap(commandMapCli);
        new DelQuotaCommand().addToMap(commandMapCli);
        new AddAuthCommand().addToMap(commandMapCli);
        new ReconfigCommand().addToMap(commandMapCli);
        new GetConfigCommand().addToMap(commandMapCli);
        new RemoveWatchesCommand().addToMap(commandMapCli);
        new GetEphemeralsCommand().addToMap(commandMapCli);
        new GetAllChildrenNumberCommand().addToMap(commandMapCli);
        new VersionCommand().addToMap(commandMapCli);

        // add all to commandMap
        for (Entry<String, CliCommand> entry : commandMapCli.entrySet()) {
            commandMap.put(entry.getKey(), entry.getValue().getOptionStr());
        }
    }

    static void usage() {
        System.err.println("ZooKeeper -server host:port cmd args");
        List<String> cmdList = new ArrayList<String>(commandMap.keySet());
        Collections.sort(cmdList);
        for (String cmd : cmdList) {
            System.err.println("\t" + cmd + " " + commandMap.get(cmd));
        }
    }

    private class MyWatcher implements Watcher {

        public void process(WatchedEvent event) {
            if (getPrintWatches()) {
                ZooKeeperMain.printMessage("WATCHER::");
                ZooKeeperMain.printMessage(event.toString());
            }
        }

    }

    /**
     * A storage class for both command line options and shell commands.
     *
     */
    static class MyCommandOptions {

        private Map<String, String> options = new HashMap<String, String>();
        private List<String> cmdArgs = null;
        private String command = null;
        public static final Pattern ARGS_PATTERN = Pattern.compile("\\s*([^\"\']\\S*|\"[^\"]*\"|'[^']*')\\s*");
        public static final Pattern QUOTED_PATTERN = Pattern.compile("^([\'\"])(.*)(\\1)$");

        public MyCommandOptions() {
            options.put("server", "localhost:2181");
            options.put("timeout", "30000");
        }

        public String getOption(String opt) {
            return options.get(opt);
        }

        public String getCommand() {
            return command;
        }

        public String getCmdArgument(int index) {
            return cmdArgs.get(index);
        }

        public int getNumArguments() {
            return cmdArgs.size();
        }

        public String[] getArgArray() {
            return cmdArgs.toArray(new String[0]);
        }

        /**
         * Parses a command line that may contain one or more flags
         * before an optional command string
         * @param args command line arguments
         * @return true if parsing succeeded, false otherwise.
         */
        public boolean parseOptions(String[] args) {
            List<String> argList = Arrays.asList(args);
            Iterator<String> it = argList.iterator();

            while (it.hasNext()) {
                String opt = it.next();
                try {
                    if (opt.equals("-server")) {
                        options.put("server", it.next());
                    } else if (opt.equals("-timeout")) {
                        options.put("timeout", it.next());
                    } else if (opt.equals("-r")) {
                        options.put("readonly", "true");
                    }
                } catch (NoSuchElementException e) {
                    System.err.println("Error: no argument found for option " + opt);
                    return false;
                }

                if (!opt.startsWith("-")) {
                    command = opt;
                    cmdArgs = new ArrayList<String>();
                    cmdArgs.add(command);
                    while (it.hasNext()) {
                        cmdArgs.add(it.next());
                    }
                    return true;
                }
            }
            return true;
        }

        /**
         * Breaks a string into command + arguments.
         * @param cmdstring string of form "cmd arg1 arg2..etc"
         * @return true if parsing succeeded.
         */
        public boolean parseCommand(String cmdstring) {
            Matcher matcher = ARGS_PATTERN.matcher(cmdstring);

            List<String> args = new LinkedList<String>();
            while (matcher.find()) {
                String value = matcher.group(1);
                if (QUOTED_PATTERN.matcher(value).matches()) {
                    // Strip off the surrounding quotes
                    value = value.substring(1, value.length() - 1);
                }
                args.add(value);
            }
            if (args.isEmpty()) {
                return false;
            }
            command = args.get(0);
            cmdArgs = args;
            return true;
        }

    }

    /**
     * Makes a list of possible completions, either for commands
     * or for zk nodes if the token to complete begins with /
     *
     */

    protected void addToHistory(int i, String cmd) {
        history.put(i, cmd);
    }

    public static List<String> getCommands() {
        List<String> cmdList = new ArrayList<String>(commandMap.keySet());
        Collections.sort(cmdList);
        return cmdList;
    }

    protected String getPrompt() {
        return "[zk: " + host + "(" + zk.getState() + ")" + " " + commandCount + "] ";
    }

    public static void printMessage(String msg) {
        System.out.println("\n" + msg);
    }

    protected void connectToZK(String newHost) throws InterruptedException, IOException {
        if (zk != null && zk.getState().isAlive()) {
            zk.close();
        }

        host = newHost;
        boolean readOnly = cl.getOption("readonly") != null;
        if (cl.getOption("secure") != null) {
            System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
            System.out.println("Secure connection is enabled");
        }
        zk = new ZooKeeperAdmin(host, Integer.parseInt(cl.getOption("timeout")), new MyWatcher(), readOnly);
    }

    public static void main(String[] args) throws CliException, IOException, InterruptedException {
        ZooKeeperMain main = new ZooKeeperMain(args);
        main.run();
    }

    public ZooKeeperMain(String[] args) throws IOException, InterruptedException {
        cl.parseOptions(args);
        System.out.println("Connecting to " + cl.getOption("server"));
        connectToZK(cl.getOption("server"));
    }

    public ZooKeeperMain(ZooKeeper zk) {
        this.zk = zk;
    }

    void run() throws CliException, IOException, InterruptedException {
        if (cl.getCommand() == null) {
            System.out.println("Welcome to ZooKeeper!");

            boolean jlinemissing = false;
            // only use jline if it's in the classpath
            try {
                Class<?> consoleC = Class.forName("jline.console.ConsoleReader");
                Class<?> completorC = Class.forName("org.apache.zookeeper.JLineZNodeCompleter");

                System.out.println("JLine support is enabled");

                Object console = consoleC.getConstructor().newInstance();

                Object completor = completorC.getConstructor(ZooKeeper.class).newInstance(zk);
                Method addCompletor = consoleC.getMethod("addCompleter", Class.forName("jline.console.completer.Completer"));
                addCompletor.invoke(console, completor);

                String line;
                Method readLine = consoleC.getMethod("readLine", String.class);
                while ((line = (String) readLine.invoke(console, getPrompt())) != null) {
                    executeLine(line);
                }
            } catch (ClassNotFoundException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalAccessException
                | InstantiationException e
            ) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            }

            if (jlinemissing) {
                System.out.println("JLine support is disabled");
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

                String line;
                while ((line = br.readLine()) != null) {
                    executeLine(line);
                }
            }
        } else {
            // Command line args non-null.  Run what was passed.
            processCmd(cl);
        }
        System.exit(exitCode);
    }

    public void executeLine(String line) throws CliException, InterruptedException, IOException {
        if (!line.equals("")) {
            cl.parseCommand(line);
            addToHistory(commandCount, line);
            processCmd(cl);
            commandCount++;
        }
    }

    protected boolean processCmd(MyCommandOptions co) throws CliException, IOException, InterruptedException {
        boolean watch = false;
        try {
            watch = processZKCmd(co);
            exitCode = ExitCode.EXECUTION_FINISHED.getValue();
        } catch (CliException ex) {
            exitCode = ex.getExitCode();
            System.err.println(ex.getMessage());
        }
        return watch;
    }

    protected boolean processZKCmd(MyCommandOptions co) throws CliException, IOException, InterruptedException {
        String[] args = co.getArgArray();
        String cmd = co.getCommand();
        if (args.length < 1) {
            usage();
            throw new MalformedCommandException("No command entered");
        }

        if (!commandMap.containsKey(cmd)) {
            usage();
            throw new CommandNotFoundException("Command not found " + cmd);
        }

        boolean watch = false;

        LOG.debug("Processing {}", cmd);

        if (cmd.equals("quit")) {
            zk.close();
            System.exit(exitCode);
        } else if (cmd.equals("redo") && args.length >= 2) {
            Integer i = Integer.decode(args[1]);
            if (commandCount <= i || i < 0) { // don't allow redoing this redo
                throw new MalformedCommandException("Command index out of range");
            }
            cl.parseCommand(history.get(i));
            if (cl.getCommand().equals("redo")) {
                throw new MalformedCommandException("No redoing redos");
            }
            history.put(commandCount, history.get(i));
            processCmd(cl);
        } else if (cmd.equals("history")) {
            for (int i = commandCount - 10; i <= commandCount; ++i) {
                if (i < 0) {
                    continue;
                }
                System.out.println(i + " - " + history.get(i));
            }
        } else if (cmd.equals("printwatches")) {
            if (args.length == 1) {
                System.out.println("printwatches is " + (printWatches ? "on" : "off"));
            } else {
                printWatches = args[1].equals("on");
            }
        } else if (cmd.equals("connect")) {
            if (args.length >= 2) {
                connectToZK(args[1]);
            } else {
                connectToZK(host);
            }
        }

        // Below commands all need a live connection
        if (zk == null || !zk.getState().isAlive()) {
            System.out.println("Not connected");
            return false;
        }

        // execute from commandMap
        CliCommand cliCmd = commandMapCli.get(cmd);
        if (cliCmd != null) {
            cliCmd.setZk(zk);
            watch = cliCmd.parse(args).exec();
        } else if (!commandMap.containsKey(cmd)) {
            usage();
        }
        return watch;
    }

}
