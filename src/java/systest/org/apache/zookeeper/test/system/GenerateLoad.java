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

package org.apache.zookeeper.test.system;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.WatchedEvent;

public class GenerateLoad {
    protected static final Logger LOG = LoggerFactory.getLogger(GenerateLoad.class);

    static ServerSocket ss;

    static Set<SlaveThread> slaves = Collections
            .synchronizedSet(new HashSet<SlaveThread>());

    static Map<Long, Long> totalByTime = new HashMap<Long, Long>();

    volatile static long currentInterval;

    static long lastChange;
    
    static PrintStream sf;
    static PrintStream tf;
    static {
        try {
            tf = new PrintStream(new FileOutputStream("trace"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    static final int INTERVAL = 6000;

    synchronized static void add(long time, int count, Socket s) {
        long interval = time / INTERVAL;
        if (currentInterval == 0 || currentInterval > interval) {
            System.out.println("Dropping " + count + " for " + new Date(time)
                    + " " + currentInterval + ">" + interval);
            return;
        }
        // We track totals by seconds
        Long total = totalByTime.get(interval);
        if (total == null) {
            totalByTime.put(interval, (long) count);
        } else {
            totalByTime.put(interval, total.longValue() + count);
        }
        tf.println(interval + " " + count + " " + s);
    }

    synchronized static long remove(long interval) {
        Long total = totalByTime.remove(interval);
        return total == null ? -1 : total;
    }

    static class SlaveThread extends Thread {
        Socket s;

        SlaveThread(Socket s) {
            setDaemon(true);
            this.s = s;
            start();
        }

        public void run() {
            try {
                System.out.println("Connected to " + s);
                BufferedReader is = new BufferedReader(new InputStreamReader(s
                        .getInputStream()));
                String result;
                while ((result = is.readLine()) != null) {
                    String timePercentCount[] = result.split(" ");
                    if (timePercentCount.length != 5) {
                        System.err.println("Got " + result + " from " + s
                                + " exitng.");
                        throw new IOException(result);
                    }
                    long time = Long.parseLong(timePercentCount[0]);
                    // int percent = Integer.parseInt(timePercentCount[1]);
                    int count = Integer.parseInt(timePercentCount[2]);
                    int errs = Integer.parseInt(timePercentCount[3]);
                    if (errs > 0) {
                        System.out.println(s + " Got an error! " + errs);
                    }
                    add(time, count, s);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                close();
            }
        }

        void send(int percentage) {
            try {
                s.getOutputStream().write((percentage + "\n").getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void close() {
            try {
                System.err.println("Closing " + s);
                slaves.remove(this);
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static class AcceptorThread extends Thread {
        AcceptorThread() {
            setDaemon(true);
            start();
        }

        public void run() {
            try {
                while (true) {
                    Socket s = ss.accept();
                    System.err.println("Accepted connection from " + s);
                    slaves.add(new SlaveThread(s));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                for (Iterator<SlaveThread> it = slaves.iterator(); it.hasNext();) {
                    SlaveThread st = it.next();
                    it.remove();
                    st.close();
                }
            }
        }
    }

    static class ReporterThread extends Thread {
        static int percentage;

        ReporterThread() {
            setDaemon(true);
            start();
        }

        public void run() {
            try {
                currentInterval = System.currentTimeMillis() / INTERVAL;
                // Give things time to report;
                Thread.sleep(INTERVAL * 2);
                long min = 99999;
                long max = 0;
                long total = 0;
                int number = 0;
                while (true) {
                    long now = System.currentTimeMillis();
                    long lastInterval = currentInterval;
                    currentInterval += 1;
                    long count = remove(lastInterval);
                    count = count * 1000 / INTERVAL; // Multiply by 1000 to get
                                                     // reqs/sec
                    if (lastChange != 0
                            && (lastChange + INTERVAL * 3) < now) {
                        // We only want to print anything if things have had a
                        // chance to change

                        if (count < min) {
                            min = count;
                        }
                        if (count > max) {
                            max = count;
                        }
                        total += count;
                        number++;
                        Calendar calendar = Calendar.getInstance();
                        calendar.setTimeInMillis(lastInterval * INTERVAL);
                        String report = lastInterval + " "
                                + calendar.get(Calendar.HOUR_OF_DAY) + ":"
                                + calendar.get(Calendar.MINUTE) + ":"
                                + calendar.get(Calendar.SECOND) + " "
                                + percentage + "% " + count + " " + min + " "
                                + ((double) total / (double) number) + " "
                                + max;
                        System.err.println(report);
                        if (sf != null) {
                            sf.println(report);
                        }
                    } else {
                        max = total = 0;
                        min = 999999999;
                        number = 0;
                    }
                    Thread.sleep(INTERVAL);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    synchronized static void sendChange(int percentage) {
        long now = System.currentTimeMillis();
        long start = now;
        ReporterThread.percentage = percentage;
        for (SlaveThread st : slaves.toArray(new SlaveThread[0])) {
            st.send(percentage);
        }
        now = System.currentTimeMillis();
        long delay = now - start;
        if (delay > 1000) {
            System.out.println("Delay of " + delay + " to send new percentage");
        }
        lastChange = now;
    }

    static public class GeneratorInstance implements Instance {

        byte bytes[];
        
        int percentage = -1;

        int errors;

        final Object statSync = new Object();

        int finished;

        int reads;

        int writes;

        int rlatency;

        int wlatency;

        int outstanding;
        
        volatile boolean alive;

        class ZooKeeperThread extends Thread implements Watcher, DataCallback,
                StatCallback {
            String host;

            ZooKeeperThread(String host) {
                setDaemon(true);
                alive = true;
                this.host = host;
                start();
            }

            static final int outstandingLimit = 100;

            synchronized void incOutstanding() throws InterruptedException {
                outstanding++;
                while (outstanding > outstandingLimit) {
                    wait();
                }
            }

            synchronized void decOutstanding() {
                outstanding--;
                notifyAll();
            }

            Random r = new Random();

            String path;

            ZooKeeper zk;

            boolean connected;

            public void run() {
                try {
                    zk = new ZooKeeper(host, 60000, this);
                    synchronized (this) {
                        if (!connected) {
                            wait(20000);
                        }
                    }
                    for (int i = 0; i < 300; i++) {
                        try {
                            Thread.sleep(100);
                            path = zk.create("/client", new byte[16],
                                    Ids.OPEN_ACL_UNSAFE,
                                    CreateMode.EPHEMERAL_SEQUENTIAL);
                            break;
                        } catch (KeeperException e) {
                            LOG.error("keeper exception thrown", e);
                        }
                    }
                    if (path == null) {
                        System.err.println("Couldn't create a node in /!");
                        return;
                    }
                    while (alive) {
                        if (r.nextInt(100) < percentage) {
                            zk.setData(path, bytes, -1, this, System
                                    .currentTimeMillis());
                        } else {
                            zk.getData(path, false, this, System
                                    .currentTimeMillis());
                        }
                        incOutstanding();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    alive = false;
                    try {
                        zk.close();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            public void process(WatchedEvent event) {
                System.err.println(event);
                synchronized (this) {
                    if (event.getType() == EventType.None) {
                        connected = (event.getState() == KeeperState.SyncConnected);
                        notifyAll();
                    }
                }
            }

            public void processResult(int rc, String path, Object ctx, byte[] data,
                    Stat stat) {
                decOutstanding();
                synchronized (statSync) {
                    if (!alive) {
                        return;
                    }
                    if (rc != 0) {
                        System.err.println("Got rc = " + rc);
                        errors++;
                    } else {
                        finished++;
                        rlatency += System.currentTimeMillis() - (Long) ctx;
                        reads++;
                    }
                }
            }

            public void processResult(int rc, String path, Object ctx, Stat stat) {
                decOutstanding();
                synchronized (statSync) {
                    if (rc != 0) {
                        System.err.println("Got rc = " + rc);
                        errors++;
                    } else {
                        finished++;
                        wlatency += System.currentTimeMillis() - (Long) ctx;
                        writes++;
                    }
                }
            }
        }

        class SenderThread extends Thread {
            Socket s;

            SenderThread(Socket s) {
                this.s = s;
                setDaemon(true);
                start();
            }

            public void run() {
                try {
                    OutputStream os = s.getOutputStream();
                    finished = 0;
                    errors = 0;
                    while (alive) {
                        Thread.sleep(300);
                        if (percentage == -1 || (finished == 0 && errors == 0)) {
                            continue;
                        }
                        String report = System.currentTimeMillis() + " "
                                + percentage + " " + finished + " " + errors + " "
                                + outstanding + "\n";
                       /* String subreport = reads + " "
                                + (((double) rlatency) / reads) + " " + writes
                                + " " + (((double) wlatency / writes)); */
                        synchronized (statSync) {
                            finished = 0;
                            errors = 0;
                            reads = 0;
                            writes = 0;
                            rlatency = 0;
                            wlatency = 0;
                        }
                        os.write(report.getBytes());
                        //System.out.println("Reporting " + report + "+" + subreport);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }

        Socket s;
        ZooKeeperThread zkThread;
        SenderThread sendThread;
        Reporter r;

        public void configure(final String params) {
            System.err.println("Got " + params);
            new Thread() {
                public void run() {
                    try {
                        String parts[] = params.split(" ");
                        String hostPort[] = parts[1].split(":");
                        int bytesSize = 1024;
                        if (parts.length == 3) {
                            try {
                                bytesSize = Integer.parseInt(parts[2]);
                            } catch(Exception e) {
                                System.err.println("Not an integer: " + parts[2]);
                            }
                        }
                        bytes = new byte[bytesSize];
                        s = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
                        zkThread = new ZooKeeperThread(parts[0]);
                        sendThread = new SenderThread(s);
                        BufferedReader is = new BufferedReader(new InputStreamReader(s
                                .getInputStream()));
                        String line;
                        while ((line = is.readLine()) != null) {
                            percentage = Integer.parseInt(line);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }.start();

        }

        public void setReporter(Reporter r) {
            this.r = r;
        }

        public void start() {
            try {
                r.report("started");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void stop() {
            alive = false;
            zkThread.interrupt();
            sendThread.interrupt();
            try {
                zkThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            try {
                sendThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            try {
                r.report("stopped");
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private static class StatusWatcher implements Watcher {
        volatile boolean connected;

        public void process(WatchedEvent event) {
            if (event.getType() == Watcher.Event.EventType.None) {
                synchronized (this) {
                    connected = event.getState() == Watcher.Event.KeeperState.SyncConnected;
                    notifyAll();
                }
            }
        }

        public boolean isConnected() {
            return connected;
        }

        synchronized public boolean waitConnected(long timeout)
                throws InterruptedException {
            long endTime = System.currentTimeMillis() + timeout;
            while (!connected && System.currentTimeMillis() < endTime) {
                wait(endTime - System.currentTimeMillis());
            }
            return connected;
        }
    }

    private static boolean leaderOnly;
    private static boolean leaderServes;
    
    private static String []processOptions(String args[]) {
        ArrayList<String> newArgs = new ArrayList<String>();
        for(String a: args) {
            if (a.equals("--leaderOnly")) {
                leaderOnly = true;
                leaderServes = true;
            } else if (a.equals("--leaderServes")) {
                leaderServes = true;
            } else {
                newArgs.add(a);
            }
        }
        return newArgs.toArray(new String[0]);
    }
    
    /**
     * @param args
     * @throws InterruptedException
     * @throws KeeperException
     * @throws DuplicateNameException
     * @throws NoAvailableContainers
     * @throws NoAssignmentException
     */
    public static void main(String[] args) throws InterruptedException,
            KeeperException, NoAvailableContainers, DuplicateNameException,
            NoAssignmentException {

        args = processOptions(args);
        if (args.length == 5) {
            try {
                StatusWatcher statusWatcher = new StatusWatcher();
                ZooKeeper zk = new ZooKeeper(args[0], 15000, statusWatcher);
                if (!statusWatcher.waitConnected(5000)) {
                    System.err.println("Could not connect to " + args[0]);
                    return;
                }
                InstanceManager im = new InstanceManager(zk, args[1]);
                ss = new ServerSocket(0);
                int port = ss.getLocalPort();
                int serverCount = Integer.parseInt(args[2]);
                int clientCount = Integer.parseInt(args[3]);
                StringBuilder quorumHostPort = new StringBuilder();
                StringBuilder zkHostPort = new StringBuilder();
                for (int i = 0; i < serverCount; i++) {
                    String r[] = QuorumPeerInstance.createServer(im, i, leaderServes);
                    if (i > 0) {
                        quorumHostPort.append(',');
                        zkHostPort.append(',');
                    }
                    zkHostPort.append(r[0]);
                    quorumHostPort.append(r[1]);
                }
                for (int i = 0; i < serverCount; i++) {
                    QuorumPeerInstance.startInstance(im, quorumHostPort
                            .toString(), i);
                }
                if (leaderOnly) {
                    int tries = 0;
                    outer:
                        while(true) {
                            Thread.sleep(1000);
                            IOException lastException = null;
                            String parts[] = zkHostPort.toString().split(",");
                            for(int i = 0; i < parts.length; i++) {
                                try {
                                    String mode = getMode(parts[i]);
                                    if (mode.equals("leader")) {
                                        zkHostPort = new StringBuilder(parts[i]);
                                        System.out.println("Connecting exclusively to " + zkHostPort.toString());
                                        break outer;
                                    }
                                } catch(IOException e) {
                                    lastException = e;
                                }
                            }
                            if (tries++ > 3) {
                                throw lastException;
                            }
                        }
                }
                for (int i = 0; i < clientCount; i++) {
                    im.assignInstance("client" + i, GeneratorInstance.class,
                            zkHostPort.toString()
                                    + ' '
                                    + InetAddress.getLocalHost()
                                            .getCanonicalHostName() + ':'
                                    + port, 1);
                }
                new AcceptorThread();
                new ReporterThread();
                BufferedReader is = new BufferedReader(new InputStreamReader(
                        System.in));
                String line;
                while ((line = is.readLine()) != null) {
                    try {
                        String cmdNumber[] = line.split(" ");
                        if (cmdNumber[0].equals("percentage")
                                && cmdNumber.length > 1) {
                            int number = Integer.parseInt(cmdNumber[1]);
                            if (number < 0 || number > 100) {
                                throw new NumberFormatException(
                                        "must be between 0 and 100");
                            }
                            sendChange(number);
                        } else if (cmdNumber[0].equals("sleep")
                                && cmdNumber.length > 1) {
                            int number = Integer.parseInt(cmdNumber[1]);
                            Thread.sleep(number * 1000);
                        } else if (cmdNumber[0].equals("save")
                                && cmdNumber.length > 1) {
                            sf = new PrintStream(cmdNumber[1]);
                        } else {
                            System.err.println("Commands must be:");
                            System.err
                                    .println("\tpercentage new_write_percentage");
                            System.err.println("\tsleep seconds_to_sleep");
                            System.err.println("\tsave file_to_save_output");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Not a valid number: "
                                + e.getMessage());
                    }
                }
            } catch (NumberFormatException e) {
                doUsage();
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(2);
            }
        } else {
            doUsage();
        }

    }

    private static String getMode(String hostPort) throws NumberFormatException, UnknownHostException, IOException {
        String parts[] = hostPort.split(":");
        Socket s = new Socket(parts[0], Integer.parseInt(parts[1]));
        s.getOutputStream().write("stat".getBytes());
        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
        String line;
        try {
          while((line = br.readLine()) != null) {
            if (line.startsWith("Mode: ")) {
              return line.substring(6);
            }
          }
          return "unknown";
        } finally {
          s.close();
        }
    }

    private static void doUsage() {
        System.err.println("USAGE: " + GenerateLoad.class.getName()
                + " [--leaderOnly] [--leaderServes] zookeeper_host:port containerPrefix #ofServers #ofClients requestSize");
        System.exit(2);
    }
}
