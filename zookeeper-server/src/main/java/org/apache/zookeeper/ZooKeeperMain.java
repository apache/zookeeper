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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.cli.CliException;
import org.apache.zookeeper.cli.CommandNotFoundException;
import org.apache.zookeeper.cli.MalformedCommandException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.zookeeper.cli.AddAuthCommand;
import org.apache.zookeeper.cli.CliCommand;
import org.apache.zookeeper.cli.CloseCommand;
import org.apache.zookeeper.cli.CreateCommand;
import org.apache.zookeeper.cli.DelQuotaCommand;
import org.apache.zookeeper.cli.DeleteAllCommand;
import org.apache.zookeeper.cli.DeleteCommand;
import org.apache.zookeeper.cli.RemoveWatchesCommand;
import org.apache.zookeeper.cli.GetAclCommand;
import org.apache.zookeeper.cli.GetCommand;
import org.apache.zookeeper.cli.GetConfigCommand;
import org.apache.zookeeper.cli.ListQuotaCommand;
import org.apache.zookeeper.cli.Ls2Command;
import org.apache.zookeeper.cli.LsCommand;
import org.apache.zookeeper.cli.ReconfigCommand;
import org.apache.zookeeper.cli.SetAclCommand;
import org.apache.zookeeper.cli.SetCommand;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.cli.StatCommand;
import org.apache.zookeeper.cli.SyncCommand;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.admin.ZooKeeperAdmin;

/**
 * The command line client to ZooKeeper.
 *
 */
@InterfaceAudience.Public
public class ZooKeeperMain {
    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperMain.class);
    static final Map<String,String> commandMap = new HashMap<String,String>( );
    static final Map<String,CliCommand> commandMapCli =
            new HashMap<String,CliCommand>( );

    protected MyCommandOptions cl = new MyCommandOptions();
    protected HashMap<Integer,String> history = new HashMap<Integer,String>( );
    protected int commandCount = 0;
    protected boolean printWatches = true;
    protected int exitCode = 0;

    protected ZooKeeper zk;
    protected String host = "";

    public boolean getPrintWatches( ) {
        return printWatches;
    }

    static {
        commandMap.put("connect", "host:port");
        commandMap.put("history","");
        commandMap.put("redo","cmdno");
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
            System.err.println("\t"+cmd+ " " + commandMap.get(cmd));
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

        private Map<String,String> options = new HashMap<String,String>();
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

        public String getCommand( ) {
            return command;
        }

        public String getCmdArgument( int index ) {
            return cmdArgs.get(index);
        }

        public int getNumArguments( ) {
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
                } catch (NoSuchElementException e){
                    System.err.println("Error: no argument found for option "
                            + opt);
                    return false;
                }

                if (!opt.startsWith("-")) {
                    command = opt;
                    cmdArgs = new ArrayList<String>( );
                    cmdArgs.add( command );
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
        public boolean parseCommand( String cmdstring ) {
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
            if (args.isEmpty()){
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


    protected void addToHistory(int i,String cmd) {
        history.put(i, cmd);
    }

    public static List<String> getCommands() {
        List<String> cmdList = new ArrayList<String>(commandMap.keySet());
        Collections.sort(cmdList);
        return cmdList;
    }

    protected String getPrompt() {       
        return "[zk: " + host + "("+zk.getState()+")" + " " + commandCount + "] ";
    }

    public static void printMessage(String msg) {
        System.out.println("\n"+msg);
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

    public static void main(String args[]) throws IOException, InterruptedException {
        ZooKeeperMain main = new ZooKeeperMain(args);
        main.run();
    }

    public ZooKeeperMain(String args[]) throws IOException, InterruptedException {
        cl.parseOptions(args);
        System.out.println("Connecting to " + cl.getOption("server"));
        connectToZK(cl.getOption("server"));
    }

    public ZooKeeperMain(ZooKeeper zk) {
      this.zk = zk;
    }

    void run() throws IOException, InterruptedException {
        if (cl.getCommand() == null) {
            System.out.println("Welcome to ZooKeeper!");

            boolean jlinemissing = false;
            // only use jline if it's in the classpath
            try {
                Class<?> consoleC = Class.forName("jline.console.ConsoleReader");
                Class<?> completorC =
                    Class.forName("org.apache.zookeeper.JLineZNodeCompleter");

                System.out.println("JLine support is enabled");

                Object console =
                    consoleC.getConstructor().newInstance();

                Object completor =
                    completorC.getConstructor(ZooKeeper.class).newInstance(zk);
                Method addCompletor = consoleC.getMethod("addCompleter",
                        Class.forName("jline.console.completer.Completer"));
                addCompletor.invoke(console, completor);

                String line;
                Method readLine = consoleC.getMethod("readLine", String.class);
                while ((line = (String)readLine.invoke(console, getPrompt())) != null) {
                    executeLine(line);
                }
            } catch (ClassNotFoundException e) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            } catch (NoSuchMethodException e) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            } catch (InvocationTargetException e) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            } catch (IllegalAccessException e) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            } catch (InstantiationException e) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            }

            if (jlinemissing) {
                System.out.println("JLine support is disabled");
                BufferedReader br =
                    new BufferedReader(new InputStreamReader(System.in));

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

    public void executeLine(String line) throws InterruptedException, IOException {
      if (!line.equals("")) {
        cl.parseCommand(line);
        addToHistory(commandCount,line);
        processCmd(cl);
        commandCount++;
      }
    }

    /**
     * trim the quota tree to recover unwanted tree elements
     * in the quota's tree
     * @param zk the zookeeper client
     * @param path the path to start from and go up and see if their
     * is any unwanted parent in the path.
     * @return true if sucessful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    private static boolean trimProcQuotas(ZooKeeper zk, String path)
        throws KeeperException, IOException, InterruptedException
    {
        if (Quotas.quotaZookeeper.equals(path)) {
            return true;
        }
        List<String> children = zk.getChildren(path, false);
        if (children.size() == 0) {
            zk.delete(path, -1);
            String parent = path.substring(0, path.lastIndexOf('/'));
            return trimProcQuotas(zk, parent);
        } else {
            return true;
        }
    }

    /**
     * this method deletes quota for a node.
     * @param zk the zookeeper client
     * @param path the path to delete quota for
     * @param bytes true if number of bytes needs to
     * be unset
     * @param numNodes true if number of nodes needs
     * to be unset
     * @return true if quota deletion is successful
     * @throws KeeperException
     * @throws IOException
     * @throws InterruptedException
     */
    public static boolean delQuota(ZooKeeper zk, String path,
            boolean bytes, boolean numNodes)
        throws KeeperException, IOException, InterruptedException
    {
        String parentPath = Quotas.quotaZookeeper + path;
        String quotaPath = Quotas.quotaZookeeper + path + "/" + Quotas.limitNode;
        if (zk.exists(quotaPath, false) == null) {
            System.out.println("Quota does not exist for " + path);
            return true;
        }
        byte[] data = null;
        try {
            data = zk.getData(quotaPath, false, new Stat());
        } catch(KeeperException.NoNodeException ne) {
            System.err.println("quota does not exist for " + path);
            return true;
        }
        StatsTrack strack = new StatsTrack(new String(data));
        if (bytes && !numNodes) {
            strack.setBytes(-1L);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        } else if (!bytes && numNodes) {
            strack.setCount(-1);
            zk.setData(quotaPath, strack.toString().getBytes(), -1);
        } else if (bytes && numNodes) {
            // delete till you can find a node with more than
            // one child
            List<String> children = zk.getChildren(parentPath, false);
            /// delete the direct children first
            for (String child: children) {
                zk.delete(parentPath + "/" + child, -1);
            }
            // cut the tree till their is more than one child
            trimProcQuotas(zk, parentPath);
        }
        return true;
    }

    private static void checkIfParentQuota(ZooKeeper zk, String path)
        throws InterruptedException, KeeperException
    {
        final String[] splits = path.split("/");
        String quotaPath = Quotas.quotaZookeeper;
        for (String str: splits) {
            if (str.length() == 0) {
                // this should only be for the beginning of the path
                // i.e. "/..." - split(path)[0] is empty string before first '/'
                continue;
            }
            quotaPath += "/" + str;
            List<String> children =  null;
            try {
                children = zk.getChildren(quotaPath, false);
            } catch(KeeperException.NoNodeException ne) {
                LOG.debug("child removed during quota check", ne);
                return;
            }
            if (children.size() == 0) {
                return;
            }
            for (String child: children) {
                if (Quotas.limitNode.equals(child)) {
                    throw new IllegalArgumentException(path + " has a parent "
                            + quotaPath + " which has a quota");
                }
            }
        }
    }

    /**
     * this method creates a quota node for the path
     * @param zk the ZooKeeper client
     * @param path the path for which quota needs to be created
     * @param bytes the limit of bytes on this path
     * @param numNodes the limit of number of nodes on this path
     * @return true if its successful and false if not.
     */
    public static boolean createQuota(ZooKeeper zk, String path,
            long bytes, int numNodes)
        throws KeeperException, IOException, InterruptedException
    {
        // check if the path exists. We cannot create
        // quota for a path that already exists in zookeeper
        // for now.
        Stat initStat = zk.exists(path, false);
        if (initStat == null) {
            throw new IllegalArgumentException(path + " does not exist.");
        }
        // now check if their is already existing
        // parent or child that has quota

        String quotaPath = Quotas.quotaZookeeper;
        // check for more than 2 children --
        // if zookeeper_stats and zookeeper_qutoas
        // are not the children then this path
        // is an ancestor of some path that
        // already has quota
        String realPath = Quotas.quotaZookeeper + path;
        try {
            List<String> children = zk.getChildren(realPath, false);
            for (String child: children) {
                if (!child.startsWith("zookeeper_")) {
                    throw new IllegalArgumentException(path + " has child " +
                            child + " which has a quota");
                }
            }
        } catch(KeeperException.NoNodeException ne) {
            // this is fine
        }

        //check for any parent that has been quota
        checkIfParentQuota(zk, path);

        // this is valid node for quota
        // start creating all the parents
        if (zk.exists(quotaPath, false) == null) {
            try {
                zk.create(Quotas.procZookeeper, null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                zk.create(Quotas.quotaZookeeper, null, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            } catch(KeeperException.NodeExistsException ne) {
                // do nothing
            }
        }

        // now create the direct children
        // and the stat and quota nodes
        String[] splits = path.split("/");
        StringBuilder sb = new StringBuilder();
        sb.append(quotaPath);
        for (int i=1; i<splits.length; i++) {
            sb.append("/" + splits[i]);
            quotaPath = sb.toString();
            try {
                zk.create(quotaPath, null, Ids.OPEN_ACL_UNSAFE ,
                        CreateMode.PERSISTENT);
            } catch(KeeperException.NodeExistsException ne) {
                //do nothing
            }
        }
        String statPath = quotaPath + "/" + Quotas.statNode;
        quotaPath = quotaPath + "/" + Quotas.limitNode;
        StatsTrack strack = new StatsTrack(null);
        strack.setBytes(bytes);
        strack.setCount(numNodes);
        try {
            zk.create(quotaPath, strack.toString().getBytes(),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            StatsTrack stats = new StatsTrack(null);
            stats.setBytes(0L);
            stats.setCount(0);
            zk.create(statPath, stats.toString().getBytes(),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch(KeeperException.NodeExistsException ne) {
            byte[] data = zk.getData(quotaPath, false , new Stat());
            StatsTrack strackC = new StatsTrack(new String(data));
            if (bytes != -1L) {
                strackC.setBytes(bytes);
            }
            if (numNodes != -1) {
                strackC.setCount(numNodes);
            }
            zk.setData(quotaPath, strackC.toString().getBytes(), -1);
        }
        return true;
    }

    protected boolean processCmd(MyCommandOptions co) throws IOException, InterruptedException {
        boolean watch = false;
        try {
            watch = processZKCmd(co);
            exitCode = 0;
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
        LOG.debug("Processing " + cmd);


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
                if (i < 0) continue;
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
        if(cliCmd != null) {
            cliCmd.setZk(zk);
            watch = cliCmd.parse(args).exec();
        } else if (!commandMap.containsKey(cmd)) {
             usage();
        }
        return watch;
    }
}
