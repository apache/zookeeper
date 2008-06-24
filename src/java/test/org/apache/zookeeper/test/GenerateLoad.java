package org.apache.zookeeper.test;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.CreateFlags;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.WatcherEvent;

public class GenerateLoad {
    static ServerSocket ss;
    
    static Set<SlaveThread> slaves = Collections
            .synchronizedSet(new HashSet<SlaveThread>());

    static Map<Long, Long> totalByTime = new HashMap<Long, Long>();

    static long currentInterval;

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
            System.out.println("Dropping " + count + " for " + new Date(time) + " " + currentInterval + ">" + interval);
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
                BufferedReader is = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String result;
                while ((result = is.readLine()) != null) {
                    String timePercentCount[] = result.split(" ");
                    if (timePercentCount.length != 5) {
                        System.err.println("Got " + result + " from " + s + " exitng.");
                        throw new IOException(result);
                    }
                    long time = Long.parseLong(timePercentCount[0]);
                    int percent = Integer.parseInt(timePercentCount[1]);
                    int count = Integer.parseInt(timePercentCount[2]);
                    int errs = Integer.parseInt(timePercentCount[3]);
                    if (errs > 0) {
                        System.out.println(s+" Got an error! " + errs);
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
        ReporterThread() {
            setDaemon(true);
            start();
        }

        public void run() {
            try {
                currentInterval = System.currentTimeMillis() / INTERVAL;
                // Give things time to report;
                Thread.sleep(INTERVAL*2);
                long min = 99999;
                long max = 0;
                long total = 0;
                int number = 0;
                while (true) {
                    long now = System.currentTimeMillis();
                    long lastInterval = currentInterval;
                    currentInterval += 1;
                    long count = remove(lastInterval);
                    count=count*1000/INTERVAL; // Multiply by 1000 to get reqs/sec
                    if (lastChange != 0 && (lastChange + INTERVAL*4 + 5000)< now) {
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
                        String report = lastInterval + " " + calendar.get(Calendar.HOUR_OF_DAY)
                                                           + ":" + calendar.get(Calendar.MINUTE)
                                                           + ":" + calendar.get(Calendar.SECOND)
                                + " "
                                + percentage
                                + "% "
                                + count
                                + " "
                                + min
                                + " "
                                + ((double)total / (double)number) + " " + max;
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
        GenerateLoad.percentage = percentage;
        for (SlaveThread st : slaves.toArray(new SlaveThread[0])) {
            st.send(percentage);
        }
        long delay = now - start;
        if (delay > 1000) {
            System.out.println("Delay of " + delay + " to send new percentage");
        }
        lastChange = now;
    }

    static int percentage = -1;

    static String host;

        static Socket s;
    
        static int errors;
      
        static final Object statSync = new Object();
        
        static int finished;
        
        static int reads;
        
        static int writes;
        
        static int rlatency;
        
        static int wlatency;

        static int outstanding;
        
    static class ZooKeeperThread extends Thread implements Watcher, DataCallback,
            StatCallback {
        ZooKeeperThread() {
            setDaemon(true);
            start();
            alive = true;
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

        boolean alive;

        Random r = new Random();

        String path;

        ZooKeeper zk;
        public void run() {
            try {
                byte bytes[] = new byte[1024];
                zk = new ZooKeeper(host, 60000, this);
                for(int i = 0; i < 300; i++) {
                    try {
                        Thread.sleep(100);
                        path = zk.create("/client", new byte[16], Ids.OPEN_ACL_UNSAFE,
                                CreateFlags.EPHEMERAL|CreateFlags.SEQUENCE);
                        break;
                    } catch(KeeperException e) {
                    }
                }
                if (path == null) {
                    System.err.println("Couldn't create a node in /!");
                    System.exit(44);
                }
                System.err.println("Created: " + s);
                while (alive) {
                    if (r.nextInt(100) < percentage) {
                        zk.setData(path, bytes, -1, this, System.currentTimeMillis());
                    } else {
                        zk.getData(path, false, this, System.currentTimeMillis());
                    }
                    incOutstanding();
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(3);
            } finally {
                alive = false;
            }
        }

        public void process(WatcherEvent event) {
            System.err.println(event);
            synchronized(this) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            if (event.getType() == Watcher.Event.EventNone && event.getState() == Watcher.Event.KeeperStateExpired) {
                try {
                    zk = new ZooKeeper(host, 10000, this);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void processResult(int rc, String path, Object ctx, byte[] data,
                Stat stat) {
            decOutstanding();
            synchronized(statSync) {
            if (rc != 0) {
                System.err.println("Got rc = " + rc);
                errors++;
            } else {
                finished++;
                rlatency += System.currentTimeMillis() - (Long)ctx;
                reads++;
            }
            }

        }

        public void processResult(int rc, String path, Object ctx, Stat stat) {
            decOutstanding();
            synchronized(statSync) {
            if (rc != 0) {
                System.err.println("Got rc = " + rc);
                errors++;
            } else {
                finished++;
                wlatency += System.currentTimeMillis() - (Long)ctx;
                writes++;
            }
            }
        }
    }

    static class SenderThread extends Thread {
        SenderThread() {
            setDaemon(true);
            start();
        }
        public void run() {
            try {
                OutputStream os = s.getOutputStream();
                finished = 0;
                errors = 0;
                while(true) {
                    Thread.sleep(300);
                    if (percentage == -1 || (finished == 0 && errors == 0)) {
                        continue;
                    }
                    String report = System.currentTimeMillis() + " " + percentage + " " + finished + " " + errors + " " + outstanding + "\n";
                    String subreport = reads + " " + (((double)rlatency)/reads) + " " + writes + " " + (((double)wlatency/writes));
                    synchronized(statSync) {
                    finished = 0;
                    errors = 0;
                    reads = 0;
                    writes = 0;
                    rlatency = 0;
                    wlatency = 0;
                    }
                    os.write(report.getBytes());
                    System.out.println("Reporting " + report + "+" + subreport);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            
        }
    }
    
    /**
     * @param args
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length == 1) {
            try {
                int port = Integer.parseInt(args[0]);
                ss = new ServerSocket(port);
                new AcceptorThread();
                new ReporterThread();
                BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = is.readLine()) != null) {
                    try {
                        String cmdNumber[] = line.split(" ");
                        if (cmdNumber[0].equals("percentage") && cmdNumber.length > 1) {
                            int number = Integer.parseInt(cmdNumber[1]);
                            if (number < 0 || number > 100) {
                                throw new NumberFormatException("must be between 0 and 100");
                            }
                            sendChange(number);
                        } else if (cmdNumber[0].equals("sleep") && cmdNumber.length > 1) {
                            int number = Integer.parseInt(cmdNumber[1]);
                            Thread.sleep(number*1000);
                        } else if (cmdNumber[0].equals("save") && cmdNumber.length > 1) {
                            sf = new PrintStream(cmdNumber[1]);
                        } else {
                            System.err.println("Commands must be:");
                            System.err.println("\tpercentage new_write_percentage");
                            System.err.println("\tsleep seconds_to_sleep");
                            System.err.println("\tsave file_to_save_output");
                        }
                    } catch (NumberFormatException e) {
                        System.out
                                .println("Not a valid number: " + e.getMessage());
                    }
                }
            } catch (NumberFormatException e) {
                doUsage();
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(2);
            }
        } else if (args.length == 2) {
            host = args[1];
            String hostPort[] = args[0].split(":");
            try {
                s = new Socket(hostPort[0], Integer
                        .parseInt(hostPort[1]));
                new ZooKeeperThread();
                new SenderThread();
                BufferedReader is = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String line;
                while((line = is.readLine()) != null) {
                    percentage = Integer.parseInt(line);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            doUsage();
        }

    }

    private static void doUsage() {
        System.err
                .println("USAGE: "
                        + GenerateLoad.class.getName()
                        + " controller_host:port zookeeper_host:port-> connects to a controller");
        System.err.println("USAGE: " + GenerateLoad.class.getName()
                + " controller_port -> starts a controller");
        System.exit(2);
    }
}
