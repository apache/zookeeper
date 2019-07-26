<!--
Copyright 2002-2004 The Apache Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# Programming with ZooKeeper - A basic tutorial

* [Introduction](#ch_Introduction)
* [Barriers](#sc_barriers)
* [Producer-Consumer Queues](#sc_producerConsumerQueues)
* [Complete example](#Complete+example)
    * [Queue test](#Queue+test)
    * [Barrier test](#Barrier+test)
    * [Source Listing](#sc_sourceListing)

<a name="ch_Introduction"></a>

## Introduction

In this tutorial, we show simple implementations of barriers and
producer-consumer queues using ZooKeeper. We call the respective classes Barrier and Queue.
These examples assume that you have at least one ZooKeeper server running.

Both primitives use the following common excerpt of code:

    static ZooKeeper zk = null;
    static Integer mutex;

    String root;

    SyncPrimitive(String address) {
        if(zk == null){
            try {
                System.out.println("Starting ZK:");
                zk = new ZooKeeper(address, 3000, this);
                mutex = new Integer(-1);
                System.out.println("Finished starting ZK: " + zk);
            } catch (IOException e) {
                System.out.println(e.toString());
                zk = null;
            }
        }
    }

    synchronized public void process(WatchedEvent event) {
        synchronized (mutex) {
            mutex.notify();
        }
    }



Both classes extend SyncPrimitive. In this way, we execute steps that are
common to all primitives in the constructor of SyncPrimitive. To keep the examples
simple, we create a ZooKeeper object the first time we instantiate either a barrier
object or a queue object, and we declare a static variable that is a reference
to this object. The subsequent instances of Barrier and Queue check whether a
ZooKeeper object exists. Alternatively, we could have the application creating a
ZooKeeper object and passing it to the constructor of Barrier and Queue.

We use the process() method to process notifications triggered due to watches.
In the following discussion, we present code that sets watches. A watch is internal
structure that enables ZooKeeper to notify a client of a change to a node. For example,
if a client is waiting for other clients to leave a barrier, then it can set a watch and
wait for modifications to a particular node, which can indicate that it is the end of the wait.
This point becomes clear once we go over the examples.

<a name="sc_barriers"></a>

## Barriers

A barrier is a primitive that enables a group of processes to synchronize the
beginning and the end of a computation. The general idea of this implementation
is to have a barrier node that serves the purpose of being a parent for individual
process nodes. Suppose that we call the barrier node "/b1". Each process "p" then
creates a node "/b1/p". Once enough processes have created their corresponding
nodes, joined processes can start the computation.

In this example, each process instantiates a Barrier object, and its constructor takes as parameters:

* the address of a ZooKeeper server (e.g., "zoo1.foo.com:2181")
* the path of the barrier node on ZooKeeper (e.g., "/b1")
* the size of the group of processes

The constructor of Barrier passes the address of the Zookeeper server to the
constructor of the parent class. The parent class creates a ZooKeeper instance if
one does not exist. The constructor of Barrier then creates a
barrier node on ZooKeeper, which is the parent node of all process nodes, and
we call barrierPath.

            /**
             * Barrier constructor
             *
             * @param address
             * @param barrierPath
             * @param size
             */
            Barrier(String address, String barrierPath, int size) {
                super(address);
                this.barrierPath = barrierPath;
                this.size = size;
                this.ourPath = barrierPath + "/" + UUID.randomUUID().toString();
                this.readyPath = barrierPath + "/" + READY_NODE;
    
                // Create barrier node
                if (zk != null) {
                    try {
                        Stat s = zk.exists(barrierPath, false);
                        if (s == null) {
                            zk.create(barrierPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        }
                    } catch (KeeperException e) {
                        System.out.println("Keeper exception when instantiating Barrier: " + e.toString());
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted exception");
                    }
                }
            }

On entering, all processes watch on a ready node and create an ephemeral node as a child of the barrier node.
Each process but the last enters the barrier and waits for the ready node to appear
The process that creates the xth node, the last process, will see x nodes in the list of children and create the ready node,
waking up the other processes. Note that waiting processes wake up only when it is time to exit, so waiting is efficient.

            /**
             * Join barrier
             *
             * @return
             * @throws KeeperException
             * @throws InterruptedException
             */
            boolean enter() throws Exception {
                boolean readyPathExists = zk.exists(readyPath, watcher) != null;
    
                zk.create(ourPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                return readyPathExists || internalEnter();
            }
    
            private synchronized boolean internalEnter() throws Exception {
                boolean result = true;
                List<String> list = zk.getChildren(barrierPath, false);
                do {
                    if (list.size() >= size) {
                        try {
                            zk.create(readyPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        } catch (KeeperException.NodeExistsException ignore) {
                            // ignore
                        }
                        break;
                    } else {
                        if (!hasBeenNotified.get()) {
                            wait();
                        }
                    }
                } while (false);
                
                return result;
            }


Note that enter() throws both KeeperException and InterruptedException, so it is
the responsibility of the application to catch and handle such exceptions.

On exit, you can't use a flag such as ready because you are watching for process nodes to go away. By using ephemeral nodes,
processes that fail after the barrier has been entered do not prevent correct processes from finishing.
When processes are ready to leave, they need to delete their process nodes and wait for all other processes to do the same.
Processes exit when there are no process nodes left as children of b. However, as an efficiency, you can use the lowest process node as the ready flag.
All other processes that are ready to exit watch for the lowest existing process node to go away, and the owner of the lowest process watches for any other process node (picking the highest for simplicity) to go away.
This means that only a single process wakes up on each node deletion except for the last node, which wakes up everyone when it is removed.

             /**
             * Wait until all reach barrier
             *
             * @return
             * @throws KeeperException
             * @throws InterruptedException
             */
            synchronized boolean leave() throws Exception {
                boolean ourNodeShouldExist = true;
                boolean result = true;
                String ourPathName = getNodeFromPath(ourPath);
    
                while (true) {
                    List<String> children = zk.getChildren(barrierPath, false);
                    children = filterAndSortChildren(children);
                    if (children == null || children.size() == 0) {
                        break;
                    }
    
                    int ourIndex = children.indexOf(ourPathName);
    
                    if (ourIndex < 0 && ourNodeShouldExist) {
                        break;
                    }
    
                    if (children.size() == 1) {
                        if (ourNodeShouldExist && !children.get(0).equals(ourPathName)) {
                            throw new IllegalStateException(
                                    String.format("Last path (%s) is not ours (%s)", children.get(0), ourPathName));
                        }
                        checkDeleteOurPath(ourNodeShouldExist);
                        break;
                    }
    
                    Stat stat;
                    boolean isLowestNode = (ourIndex == 0);
                    if (isLowestNode) {
                        String highestNodePath = barrierPath + "/" + children.get(children.size() - 1);
                        stat = zk.exists(highestNodePath, watcher);
                    } else {
                        String lowestNodePath = barrierPath + "/" + children.get(0);
                        stat = zk.exists(lowestNodePath, watcher);
                        checkDeleteOurPath(ourNodeShouldExist);
                        ourNodeShouldExist = false;
                    }
    
                    if (stat != null) {
                        wait();
                    }
                }
    
                try {
                    zk.delete(readyPath, -1);
                } catch (KeeperException.NoNodeException ignore) {
                    // ignore
                }
                return result;
            }


<a name="sc_producerConsumerQueues"></a>

## Producer-Consumer Queues

A producer-consumer queue is a distributed data structure that groups of processes
use to generate and consume items. Producer processes create new elements and add
them to the queue. Consumer processes remove elements from the list, and process them.
In this implementation, the elements are simple integers. The queue is represented
by a root node, and to add an element to the queue, a producer process creates a new node,
a child of the root node.

The following excerpt of code corresponds to the constructor of the object. As
with Barrier objects, it first calls the constructor of the parent class, SyncPrimitive,
that creates a ZooKeeper object if one doesn't exist. It then verifies if the root
node of the queue exists, and creates if it doesn't.

    /**
     * Constructor of producer-consumer queue
     *
     * @param address
     * @param name
     */
    Queue(String address, String name) {
        super(address);
        this.root = name;
        // Create ZK node name
        if (zk != null) {
            try {
                Stat s = zk.exists(root, false);
                if (s == null) {
                    zk.create(root, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                }
            } catch (KeeperException e) {
                System.out
                        .println("Keeper exception when instantiating queue: "
                                + e.toString());
            } catch (InterruptedException e) {
                System.out.println("Interrupted exception");
            }
        }
    }


A producer process calls "produce()" to add an element to the queue, and passes
an integer as an argument. To add an element to the queue, the method creates a
new node using "create()", and uses the SEQUENCE flag to instruct ZooKeeper to
append the value of the sequencer counter associated to the root node. In this way,
we impose a total order on the elements of the queue, thus guaranteeing that the
oldest element of the queue is the next one consumed.

    /**
     * Add element to the queue.
     *
     * @param i
     * @return
     */

    boolean produce(int i) throws KeeperException, InterruptedException{
        ByteBuffer b = ByteBuffer.allocate(4);
        byte[] value;

        // Add child with value i
        b.putInt(i);
        value = b.array();
        zk.create(root + "/element", value, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);

        return true;
    }


To consume an element, a consumer process obtains the children of the root node,
reads the node with smallest counter value, and returns the element. Note that
if there is a conflict, then one of the two contending processes won't be able to
delete the node and the delete operation will throw an exception.

A call to getChildren() returns the list of children in lexicographic order.
As lexicographic order does not necessarily follow the numerical order of the counter
values, we need to decide which element is the smallest. To decide which one has
the smallest counter value, we traverse the list, and remove the prefix "element"
from each one.

    /**
     * Remove first element from the queue.
     *
     * @return
     * @throws KeeperException
     * @throws InterruptedException
     */
    int consume() throws KeeperException, InterruptedException{
        int retvalue = -1;
        Stat stat = null;

        // Get the first element available
        while (true) {
            synchronized (mutex) {
                List<String> list = zk.getChildren(root, true);
                if (list.size() == 0) {
                    System.out.println("Going to wait");
                    mutex.wait();
                } else {
                    Integer min = new Integer(list.get(0).substring(7));
                    for(String s : list){
                        Integer tempValue = new Integer(s.substring(7));
                        //System.out.println("Temporary value: " + tempValue);
                        if(tempValue < min) min = tempValue;
                    }
                    System.out.println("Temporary value: " + root + "/element" + min);
                    byte[] b = zk.getData(root + "/element" + min,
                                false, stat);
                    zk.delete(root + "/element" + min, 0);
                    ByteBuffer buffer = ByteBuffer.wrap(b);
                    retvalue = buffer.getInt();

                    return retvalue;
                    }
                }
            }
        }
    }


<a name="Complete+example"></a>

## Complete example

In the following section you can find a complete command line application to demonstrate the above mentioned
recipes. Use the following command to run it.

    ZOOBINDIR="[path_to_distro]/bin"
    . "$ZOOBINDIR"/zkEnv.sh
    java SyncPrimitive [Test Type] [ZK server] [No of elements] [Client type]

<a name="Queue+test"></a>

### Queue test

Start a producer to create 100 elements

    java SyncPrimitive qTest localhost 100 p


Start a consumer to consume 100 elements

    java SyncPrimitive qTest localhost 100 c

<a name="Barrier+test"></a>

### Barrier test

Start a barrier with 2 participants (start as many times as many participants you'd like to enter)

    java SyncPrimitive bTest localhost 2

<a name="sc_sourceListing"></a>

### Source Listing

#### SyncPrimitive.Java
    
    import java.io.IOException;
    import java.nio.ByteBuffer;
    import java.util.Collections;
    import java.util.List;
    import java.util.Random;
    import java.util.UUID;
    import java.util.concurrent.Callable;
    import java.util.concurrent.ExecutorService;
    import java.util.concurrent.Executors;
    import java.util.concurrent.TimeUnit;
    import java.util.concurrent.atomic.AtomicBoolean;
    import java.util.stream.Collectors;
    
    import org.apache.zookeeper.CreateMode;
    import org.apache.zookeeper.KeeperException;
    import org.apache.zookeeper.WatchedEvent;
    import org.apache.zookeeper.Watcher;
    import org.apache.zookeeper.ZooKeeper;
    import org.apache.zookeeper.ZooDefs.Ids;
    import org.apache.zookeeper.data.Stat;
    
    public class SyncPrimitive implements Watcher {
    
        static ZooKeeper zk = null;
        static Integer mutex;
    
        String root;
    
        SyncPrimitive(String address) {
            if(zk == null){
                try {
                    System.out.println("Starting ZK:");
                    zk = new ZooKeeper(address, 3000, this);
                    mutex = new Integer(-1);
                    System.out.println("Finished starting ZK: " + zk);
                } catch (IOException e) {
                    System.out.println(e.toString());
                    zk = null;
                }
            }
            //else mutex = new Integer(-1);
        }
    
        synchronized public void process(WatchedEvent event) {
            synchronized (mutex) {
                //System.out.println("Process: " + event.getType());
                mutex.notify();
            }
        }
    
        /**
         * Barrier
         */
        static public class Barrier extends SyncPrimitive {
            int size;
            String barrierPath;
            private final String ourPath;
            String readyPath;
            private static final String READY_NODE = "ready";
            private final AtomicBoolean hasBeenNotified = new AtomicBoolean(false);
            private final Watcher watcher = new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    notifyFromWatcher();
                }
            };
    
            /**
             * Barrier constructor
             *
             * @param address
             * @param barrierPath
             * @param size
             */
            Barrier(String address, String barrierPath, int size) {
                super(address);
                this.barrierPath = barrierPath;
                this.size = size;
                this.ourPath = barrierPath + "/" + UUID.randomUUID().toString();
                this.readyPath = barrierPath + "/" + READY_NODE;
    
                // Create barrier node
                if (zk != null) {
                    try {
                        Stat s = zk.exists(barrierPath, false);
                        if (s == null) {
                            zk.create(barrierPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        }
                    } catch (KeeperException e) {
                        System.out.println("Keeper exception when instantiating Barrier: " + e.toString());
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted exception");
                    }
                }
            }
    
            /**
             * Join barrier
             *
             * @return
             * @throws KeeperException
             * @throws InterruptedException
             */
            boolean enter() throws Exception {
                boolean readyPathExists = zk.exists(readyPath, watcher) != null;
    
                zk.create(ourPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                return readyPathExists || internalEnter();
            }
    
            private synchronized boolean internalEnter() throws Exception {
                boolean result = true;
                List<String> list = zk.getChildren(barrierPath, false);
                do {
                    if (list.size() >= size) {
                        try {
                            zk.create(readyPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        } catch (KeeperException.NodeExistsException ignore) {
                            // ignore
                        }
                        break;
                    } else {
                        if (!hasBeenNotified.get()) {
                            wait();
                        }
                    }
                } while (false);
    
                return result;
            }
    
            /**
             * Wait until all reach barrier
             *
             * @return
             * @throws KeeperException
             * @throws InterruptedException
             */
            synchronized boolean leave() throws Exception {
                boolean ourNodeShouldExist = true;
                boolean result = true;
                String ourPathName = getNodeFromPath(ourPath);
    
                while (true) {
                    List<String> children = zk.getChildren(barrierPath, false);
                    children = filterAndSortChildren(children);
                    if (children == null || children.size() == 0) {
                        break;
                    }
    
                    int ourIndex = children.indexOf(ourPathName);
    
                    if (ourIndex < 0 && ourNodeShouldExist) {
                        break;
                    }
    
                    if (children.size() == 1) {
                        if (ourNodeShouldExist && !children.get(0).equals(ourPathName)) {
                            throw new IllegalStateException(
                                    String.format("Last path (%s) is not ours (%s)", children.get(0), ourPathName));
                        }
                        checkDeleteOurPath(ourNodeShouldExist);
                        break;
                    }
    
                    Stat stat;
                    boolean isLowestNode = (ourIndex == 0);
                    if (isLowestNode) {
                        String highestNodePath = barrierPath + "/" + children.get(children.size() - 1);
                        stat = zk.exists(highestNodePath, watcher);
                    } else {
                        String lowestNodePath = barrierPath + "/" + children.get(0);
                        stat = zk.exists(lowestNodePath, watcher);
                        checkDeleteOurPath(ourNodeShouldExist);
                        ourNodeShouldExist = false;
                    }
    
                    if (stat != null) {
                        wait();
                    }
                }
    
                try {
                    zk.delete(readyPath, -1);
                } catch (KeeperException.NoNodeException ignore) {
                    // ignore
                }
                return result;
            }
    
            private void checkDeleteOurPath(boolean shouldExist) throws Exception {
                if (shouldExist) {
                    zk.delete(ourPath, 0);
                }
            }
    
            /**
             * sort the children node
             *
             * @param children
             * @return
             */
            private static List<String> filterAndSortChildren(List<String> children) {
                List<String> filterList = children.stream().filter(name -> !name.equals(READY_NODE)).collect(Collectors.toList());
                Collections.sort(filterList);
                return filterList;
            }
    
            /**
             * Given a full path, return the node name. i.e. "/one/two/three" will return
             * "three"
             *
             * @param path
             *            the path
             * @return the node
             */
            public static String getNodeFromPath(String path) {
                int i = path.lastIndexOf("/");
                if (i < 0) {
                    return path;
                }
                if ((i + 1) >= path.length()) {
                    return "";
                }
                return path.substring(i + 1);
            }
    
            private synchronized void notifyFromWatcher() {
                hasBeenNotified.set(true);
                notifyAll();
            }
        }
    
        /**
         * Producer-Consumer queue
         */
        static public class Queue extends SyncPrimitive {
    
            /**
             * Constructor of producer-consumer queue
             *
             * @param address
             * @param name
             */
            Queue(String address, String name) {
                super(address);
                this.root = name;
                // Create ZK node name
                if (zk != null) {
                    try {
                        Stat s = zk.exists(root, false);
                        if (s == null) {
                            zk.create(root, new byte[0], Ids.OPEN_ACL_UNSAFE,
                                    CreateMode.PERSISTENT);
                        }
                    } catch (KeeperException e) {
                        System.out
                                .println("Keeper exception when instantiating queue: "
                                        + e.toString());
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted exception");
                    }
                }
            }
    
            /**
             * Add element to the queue.
             *
             * @param i
             * @return
             */
    
            boolean produce(int i) throws KeeperException, InterruptedException{
                ByteBuffer b = ByteBuffer.allocate(4);
                byte[] value;
    
                // Add child with value i
                b.putInt(i);
                value = b.array();
                zk.create(root + "/element", value, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL);
    
                return true;
            }
    
    
            /**
             * Remove first element from the queue.
             *
             * @return
             * @throws KeeperException
             * @throws InterruptedException
             */
            int consume() throws KeeperException, InterruptedException{
                int retvalue = -1;
                Stat stat = null;
    
                // Get the first element available
                while (true) {
                    synchronized (mutex) {
                        List<String> list = zk.getChildren(root, true);
                        if (list.size() == 0) {
                            System.out.println("Going to wait");
                            mutex.wait();
                        } else {
                            Integer min = new Integer(list.get(0).substring(7));
                            String minNode = list.get(0);
                            for(String s : list){
                                Integer tempValue = new Integer(s.substring(7));
                                //System.out.println("Temporary value: " + tempValue);
                                if(tempValue < min) {
                                    min = tempValue;
                                    minNode = s;
                                }
                            }
                            System.out.println("Temporary value: " + root + "/" + minNode);
                            byte[] b = zk.getData(root + "/" + minNode,
                                    false, stat);
                            zk.delete(root + "/" + minNode, 0);
                            ByteBuffer buffer = ByteBuffer.wrap(b);
                            retvalue = buffer.getInt();
    
                            return retvalue;
                        }
                    }
                }
            }
        }
    
        public static void main(String args[]) {
            if (args[0].equals("qTest"))
                queueTest(args);
            else
                barrierTest(args);
    
        }
    
        public static void queueTest(String args[]) {
            Queue q = new Queue(args[1], "/app1");
    
            System.out.println("Input: " + args[1]);
            int i;
            Integer max = new Integer(args[2]);
    
            if (args[3].equals("p")) {
                System.out.println("Producer");
                for (i = 0; i < max; i++)
                try{
                    q.produce(10 + i);
                } catch (KeeperException e){
    
                } catch (InterruptedException e){
    
                }
            } else {
                System.out.println("Consumer");
    
                for (i = 0; i < max; i++) {
                    try{
                        int r = q.consume();
                        System.out.println("Item: " + r);
                    } catch (KeeperException e){
                        i--;
                    } catch (InterruptedException e){
    
                    }
                }
            }
        }
    
        public static void barrierTest(String args[]) {
            int QTY = Integer.parseInt(args[2]);
            try {
                ExecutorService service = Executors.newFixedThreadPool(QTY);
                System.out.println("barrier size:" + QTY);
                for (int i = 0; i < QTY; ++i) {
                    final Barrier barrier = new Barrier(args[1], "/b1", QTY);
                    final int index = i;
                    Callable<Void> task = () -> {
                        Thread.sleep((long) (3 * Math.random()));
                        System.out.println("Client #" + index + " enters");
                        barrier.enter();
                        System.out.println("Client #" + index + " begins processing");
                        Thread.sleep((long) (3000 * Math.random()));
                        barrier.leave();
                        System.out.println("Client #" + index + " left");
                        return null;
                    };
                    service.submit(task);
                }
                service.shutdown();
                service.awaitTermination(3, TimeUnit.MINUTES);
                System.out.println("Left barrier");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }