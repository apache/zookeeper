import{j as s}from"./chunk-EPOLDU6W-BACfhBcx.js";let r=`## Scripts

### zkServer.sh

Manage the ZooKeeper server process.

\`\`\`bash
# start the server
./zkServer.sh start

# start the server in the foreground (useful for debugging)
./zkServer.sh start-foreground

# stop the server
./zkServer.sh stop

# restart the server
./zkServer.sh restart

# show the status, mode, and role of the server
./zkServer.sh status
JMX enabled by default
Using config: /data/software/zookeeper/conf/zoo.cfg
Mode: standalone

# print the startup parameters
./zkServer.sh print-cmd

# show the ZooKeeper server version
./zkServer.sh version
Apache ZooKeeper, version 3.6.0-SNAPSHOT 06/11/2019 05:39 GMT
\`\`\`

The \`status\` command establishes a client connection to execute diagnostic commands.
When the ZooKeeper cluster is started in TLS-only mode (by omitting \`clientPort\` from
\`zoo.cfg\`), additional SSL configuration must be provided:

\`\`\`bash
CLIENT_JVMFLAGS="-Dzookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty \\
  -Dzookeeper.ssl.trustStore.location=/tmp/clienttrust.jks \\
  -Dzookeeper.ssl.trustStore.password=password \\
  -Dzookeeper.ssl.keyStore.location=/tmp/client.jks \\
  -Dzookeeper.ssl.keyStore.password=password \\
  -Dzookeeper.client.secure=true" \\
  ./zkServer.sh status
\`\`\`

### zkCli.sh

See [ZooKeeper CLI](/admin-ops/cli).

### zkEnv.sh

Sets environment variables for the ZooKeeper server. Key variables:

* \`ZOO_LOG_DIR\` — the directory where logs are stored.

### zkCleanup.sh

Clean up old snapshots and transaction logs.

\`\`\`bash
# Usage: ./zkCleanup.sh dataLogDir [snapDir] -n count
#   dataLogDir -- path to the transaction log directory
#   snapDir    -- path to the snapshot directory (optional)
#   count      -- number of recent snaps/logs to keep (must be >= 3)

# Keep the latest 5 logs and snapshots
./zkCleanup.sh -n 5
\`\`\`

### zkTxnLogToolkit.sh

Dump and recover transaction log files with broken CRC entries.

\`\`\`bash
$ bin/zkTxnLogToolkit.sh
usage: TxnLogToolkit [-dhrv] txn_log_file_name
-d,--dump      Dump mode. Dump all entries of the log file. (this is the default)
-h,--help      Print help message
-r,--recover   Recovery mode. Re-calculate CRC for broken entries.
-v,--verbose   Be verbose in recovery mode: print all entries, not just fixed ones.
-y,--yes       Non-interactive mode: repair all CRC errors without asking
\`\`\`

The default behavior is safe — it dumps the entries of the given transaction log file
to the screen (same as \`-d,--dump\`):

\`\`\`bash
$ bin/zkTxnLogToolkit.sh log.100000001
ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
4/5/18 2:15:58 PM CEST session 0x16295bafcc40000 cxid 0x0 zxid 0x100000001 createSession 30000
CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
4/5/18 2:16:12 PM CEST session 0x26295bafcc90000 cxid 0x0 zxid 0x100000003 createSession 30000
4/5/18 2:17:34 PM CEST session 0x26295bafcc90000 cxid 0x0 zxid 0x200000001 closeSession null
4/5/18 2:17:34 PM CEST session 0x16295bd23720000 cxid 0x0 zxid 0x200000002 createSession 30000
4/5/18 2:17:34 PM CEST session 0x16295bd23720000 cxid 0x2 zxid 0x200000003 create '/andor,#626262,v{s{31,s{'world,'anyone}}},F,1
EOF reached after 6 txns.
\`\`\`

In **recovery mode** (\`-r,--recover\`), the original file is left untouched and all transactions
are copied to a new file with a \`.fixed\` suffix. CRC values are recalculated; if the calculated
value does not match the original, the new value is used. By default the tool is interactive,
asking for confirmation on each CRC error:

\`\`\`bash
$ bin/zkTxnLogToolkit.sh -r log.100000001
ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
Would you like to fix it (Yes/No/Abort) ?
\`\`\`

* **Yes** — write the recalculated CRC to the new file.
* **No** — copy the original CRC value.
* **Abort** — abort the operation. The \`.fixed\` file will not be deleted and may be in a half-complete state.

\`\`\`bash
$ bin/zkTxnLogToolkit.sh -r log.100000001
ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
Would you like to fix it (Yes/No/Abort) ? y
EOF reached after 6 txns.
Recovery file log.100000001.fixed has been written with 1 fixed CRC error(s)
\`\`\`

Use \`-v,--verbose\` to print all records (not just broken ones). Use \`-y,--yes\` to fix all
CRC errors automatically without prompting.

### zkSnapShotToolkit.sh

Dump a snapshot file to stdout, showing detailed information for each znode.

\`\`\`bash
# show usage
./zkSnapShotToolkit.sh
USAGE: SnapshotFormatter [-d|-json] snapshot_file
       -d dump the data for each znode
       -json dump znode info in json format

# show each znode's metadata without data content
./zkSnapShotToolkit.sh /data/zkdata/version-2/snapshot.fa01000186d
/zk-latencies_4/session_946
  cZxid = 0x00000f0003110b
  ctime = Wed Sep 19 21:58:22 CST 2018
  mZxid = 0x00000f0003110b
  mtime = Wed Sep 19 21:58:22 CST 2018
  pZxid = 0x00000f0003110b
  cversion = 0
  dataVersion = 0
  aclVersion = 0
  ephemeralOwner = 0x00000000000000
  dataLength = 100

# -d: include data content
./zkSnapShotToolkit.sh -d /data/zkdata/version-2/snapshot.fa01000186d
/zk-latencies2/session_26229
  cZxid = 0x00000900007ba0
  ctime = Wed Aug 15 20:13:52 CST 2018
  mZxid = 0x00000900007ba0
  mtime = Wed Aug 15 20:13:52 CST 2018
  pZxid = 0x00000900007ba0
  cversion = 0
  dataVersion = 0
  aclVersion = 0
  ephemeralOwner = 0x00000000000000
  data = eHh4eHh4eHh4eHh4eA==

# -json: output in JSON format
./zkSnapShotToolkit.sh -json /data/zkdata/version-2/snapshot.fa01000186d
[[1,0,{"progname":"SnapshotFormatter.java","progver":"0.01","timestamp":1559788148637},[{"name":"\\/","asize":0,"dsize":0,"dev":0,"ino":1001},[{"name":"zookeeper","asize":0,"dsize":0,"dev":0,"ino":1002},{"name":"config","asize":0,"dsize":0,"dev":0,"ino":1003},[{"name":"quota","asize":0,"dsize":0,"dev":0,"ino":1004},[{"name":"test","asize":0,"dsize":0,"dev":0,"ino":1005},{"name":"zookeeper_limits","asize":52,"dsize":52,"dev":0,"ino":1006},{"name":"zookeeper_stats","asize":15,"dsize":15,"dev":0,"ino":1007}]]],{"name":"test","asize":0,"dsize":0,"dev":0,"ino":1008}]]
\`\`\`

### zkSnapshotRecursiveSummaryToolkit.sh

Recursively collect and display child count and data size for a selected node.

\`\`\`bash
$ ./zkSnapshotRecursiveSummaryToolkit.sh
USAGE:
SnapshotRecursiveSummary  <snapshot_file>  <starting_node>  <max_depth>

snapshot_file:  path to the ZooKeeper snapshot
starting_node:  the path in the ZooKeeper tree where traversal begins
max_depth:      depth limit for output (0 = no limit; 1 = starting node + direct children;
                2 = one more level, etc.). Only affects display, NOT the calculation.
\`\`\`

\`\`\`bash
# display stats for the root node and 2 levels of descendants
./zkSnapshotRecursiveSummaryToolkit.sh /data/zkdata/version-2/snapshot.fa01000186d / 2

/
   children: 1250511
   data: 1952186580
-- /zookeeper
--   children: 1
--   data: 0
-- /solr
--   children: 1773
--   data: 8419162
---- /solr/configs
----   children: 1640
----   data: 8407643
---- /solr/overseer
----   children: 6
----   data: 0
---- /solr/live_nodes
----   children: 3
----   data: 0
\`\`\`

### zkSnapshotComparer.sh

Compare two snapshots with configurable thresholds and filters, outputting the delta —
which znodes were added, updated, or deleted. Useful for offline consistency checks and
data trend analysis. Only permanent nodes are reported; sessions and ephemeral nodes are ignored.

Tuning parameters:

* \`--nodes\` — threshold for number of descendant nodes added/removed.
* \`--bytes\` — threshold for bytes added/removed.

#### Locating Snapshots

Snapshots are stored in the [ZooKeeper data directory](/admin-ops/administrators-guide/data-file-management#the-data-directory)
configured in \`conf/zoo.cfg\`.

#### Supported Snapshot Formats

Uncompressed snapshots and compressed formats (\`snappy\`, \`gz\`) are all supported.
Snapshots in different formats can be compared directly without manual decompression.

#### Running the Tool

Running the tool with no arguments prints the help page:

\`\`\`
usage: java -cp <classPath> org.apache.zookeeper.server.SnapshotComparer
 -b,--bytes <BYTETHRESHOLD>   (Required) The node data delta size threshold, in bytes, for printing the node.
 -d,--debug                   Use debug output.
 -i,--interactive             Enter interactive mode.
 -l,--left <LEFT>             (Required) The left snapshot file.
 -n,--nodes <NODETHRESHOLD>   (Required) The descendant node delta size threshold, in nodes, for printing the node.
 -r,--right <RIGHT>           (Required) The right snapshot file.
\`\`\`

Example command:

\`\`\`
./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1
\`\`\`

Example output:

\`\`\`
...
Deserialized snapshot in snapshot.44 in 0.002741 seconds
Processed data tree in 0.000361 seconds
Node count: 10
Total size: 0
Max depth: 4
Count of nodes at depth 0: 1
Count of nodes at depth 1: 2
Count of nodes at depth 2: 4
Count of nodes at depth 3: 3

Node count: 22
Total size: 2903
Max depth: 5
Count of nodes at depth 0: 1
Count of nodes at depth 1: 2
Count of nodes at depth 2: 4
Count of nodes at depth 3: 7
Count of nodes at depth 4: 8

Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 0
Node  found in both trees. Delta: 2903 bytes, 12 descendants
Analysis for depth 1
Node /zk_test found in both trees. Delta: 2903 bytes, 12 descendants
Analysis for depth 2
Node /zk_test/gz found in both trees. Delta: 730 bytes, 3 descendants
Node /zk_test/snappy found in both trees. Delta: 2173 bytes, 9 descendants
Analysis for depth 3
Node /zk_test/gz/12345 found in both trees. Delta: 9 bytes, 1 descendants
Node /zk_test/gz/a found only in right tree. Descendant size: 721. Descendant count: 0
Node /zk_test/snappy/anotherTest found in both trees. Delta: 1738 bytes, 2 descendants
Node /zk_test/snappy/test_1 found only in right tree. Descendant size: 344. Descendant count: 3
Node /zk_test/snappy/test_2 found only in right tree. Descendant size: 91. Descendant count: 2
Analysis for depth 4
Node /zk_test/gz/12345/abcdef found only in right tree. Descendant size: 9. Descendant count: 0
Node /zk_test/snappy/anotherTest/abc found only in right tree. Descendant size: 1738. Descendant count: 0
Node /zk_test/snappy/test_1/a found only in right tree. Descendant size: 93. Descendant count: 0
Node /zk_test/snappy/test_1/b found only in right tree. Descendant size: 251. Descendant count: 0
Node /zk_test/snappy/test_2/xyz found only in right tree. Descendant size: 33. Descendant count: 0
Node /zk_test/snappy/test_2/y found only in right tree. Descendant size: 58. Descendant count: 0
All layers compared.
\`\`\`

#### Interactive Mode

Add \`-i\` / \`--interactive\` to enter interactive mode:

\`\`\`
./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1 -i
\`\`\`

Three navigation options are available:

* Press **Enter** to print the current depth layer.
* Type a **number** to jump to and print all nodes at that depth.
* Enter an **absolute path** (starting with \`/\`) to print the immediate subtree of that node.

Note: only nodes passing the bytes and nodes thresholds are shown.

Press Enter to move to the next depth layer:

\`\`\`
Current depth is 0
Press enter to move to print current depth layer;
...
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 0
Node  found in both trees. Delta: 2903 bytes, 12 descendants
\`\`\`

Type a number to jump forward or backward to a specific depth:

\`\`\`
Current depth is 1
...
Type a number to jump to and print all nodes at a given depth;
...
3
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 3
Node /zk_test/gz/12345 found in both trees. Delta: 9 bytes, 1 descendants
Node /zk_test/gz/a found only in right tree. Descendant size: 721. Descendant count: 0
Filtered node /zk_test/gz/anotherOne of left size 0, right size 0
Filtered right node /zk_test/gz/b of size 0
Node /zk_test/snappy/anotherTest found in both trees. Delta: 1738 bytes, 2 descendants
Node /zk_test/snappy/test_1 found only in right tree. Descendant size: 344. Descendant count: 3
Node /zk_test/snappy/test_2 found only in right tree. Descendant size: 91. Descendant count: 2

Current depth is 3
...
Type a number to jump to and print all nodes at a given depth;
...
0
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 0
Node  found in both trees. Delta: 2903 bytes, 12 descendants
\`\`\`

Out-of-range depth is handled gracefully:

\`\`\`
Current depth is 1
...
10
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Depth must be in range [0, 4]
\`\`\`

Enter an absolute path to print the immediate subtree of a node:

\`\`\`
Current depth is 3
...
Enter an ABSOLUTE path to print the immediate subtree of a node.
/zk_test
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for node /zk_test
Node /zk_test/gz found in both trees. Delta: 730 bytes, 3 descendants
Node /zk_test/snappy found in both trees. Delta: 2173 bytes, 9 descendants
\`\`\`

Invalid path and invalid input are handled:

\`\`\`
Enter an ABSOLUTE path to print the immediate subtree of a node.
/non-exist-path
Analysis for node /non-exist-path
Path /non-exist-path is neither found in left tree nor right tree.

12223999999999999999999999999999999999999
Input 12223999999999999999999999999999999999999 is not valid. Depth must be in range [0, 4]. Path must be an absolute path which starts with '/'.
\`\`\`

The tool exits interactive mode automatically when all layers are compared, or press \`^C\` to exit at any time.

## Benchmark

### YCSB

[YCSB](https://github.com/brianfrankcooper/YCSB) (Yahoo Cloud Serving Benchmark) can be used to benchmark ZooKeeper. Follow the steps below to get started.

<Steps>
  <Step>
    **Start ZooKeeper Server(s)**

    Start your ZooKeeper ensemble before running any benchmark.
  </Step>

  <Step>
    **Install Java and Maven**

    Ensure a JDK and Maven are installed on the machine running the benchmark.
  </Step>

  <Step>
    **Set Up YCSB**

    Clone and build the ZooKeeper binding:

    \`\`\`bash
    git clone http://github.com/brianfrankcooper/YCSB.git
    cd YCSB
    mvn -pl site.ycsb:zookeeper-binding -am clean package -DskipTests
    \`\`\`

    See the [YCSB README](https://github.com/brianfrankcooper/YCSB#getting-started) for more details.
  </Step>

  <Step>
    **Configure ZooKeeper Connection Parameters**

    Set the following properties in your workload file or via the shell:

    * \`zookeeper.connectString\` — connection string (e.g. \`127.0.0.1:2181/benchmark\`)
    * \`zookeeper.sessionTimeout\` — session timeout in milliseconds
    * \`zookeeper.watchFlag\` — enable ZooKeeper watches (\`true\` or \`false\`, default \`false\`). This measures the effect of watch overhead on read/write performance, not watch notification latency.

    \`\`\`bash
    ./bin/ycsb run zookeeper -s -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark \\
      -p zookeeper.watchFlag=true
    \`\`\`

    Or set properties directly on the command line (create the \`/benchmark\` namespace first using \`create /benchmark\` in the CLI):

    \`\`\`bash
    ./bin/ycsb run zookeeper -s -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark \\
      -p zookeeper.sessionTimeout=30000
    \`\`\`
  </Step>

  <Step>
    **Load Data and Run Tests**

    Load data:

    \`\`\`bash
    # -p recordcount: number of znodes to insert
    ./bin/ycsb load zookeeper -s -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark \\
      -p recordcount=10000 > outputLoad.txt
    \`\`\`

    Run the workload (\`workloadb\` is recommended as the most representative read-heavy workload):

    \`\`\`bash
    # test the effect of value size on performance
    ./bin/ycsb run zookeeper -s -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark -p fieldlength=1000

    # test with multiple fields
    ./bin/ycsb run zookeeper -s -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark -p fieldcount=20

    # HDR histogram output
    ./bin/ycsb run zookeeper -threads 1 -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark \\
      -p hdrhistogram.percentiles=10,25,50,75,90,95,99,99.9 \\
      -p histogram.buckets=500

    # multi-client test (increase maxClientCnxns in zoo.cfg as needed)
    ./bin/ycsb run zookeeper -threads 10 -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark

    # timeseries output
    ./bin/ycsb run zookeeper -threads 1 -P workloads/workloadb \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark \\
      -p measurementtype=timeseries -p timeseries.granularity=50

    # cluster test
    ./bin/ycsb run zookeeper -P workloads/workloadb \\
      -p zookeeper.connectString=192.168.10.43:2181,192.168.10.45:2181,192.168.10.27:2181/benchmark

    # test leader performance only
    ./bin/ycsb run zookeeper -P workloads/workloadb \\
      -p zookeeper.connectString=192.168.10.43:2181/benchmark

    # large znodes (default jute.maxbuffer = 1 MB; set the same value on all ZK servers)
    ./bin/ycsb run zookeeper -jvm-args="-Djute.maxbuffer=4194304" -s -P workloads/workloadc \\
      -p zookeeper.connectString=127.0.0.1:2181/benchmark

    # clean up after benchmarking: CLI: deleteall /benchmark
    \`\`\`
  </Step>
</Steps>

### zk-smoketest

[zk-smoketest](https://github.com/phunt/zk-smoketest) provides a simple smoketest client
for a ZooKeeper ensemble. Useful for verifying new, updated, or existing installations.

## Testing

### Fault Injection Framework

#### Byteman

[Byteman](https://byteman.jboss.org/) is a tool for tracing, monitoring, and testing Java
application and JDK runtime code. It injects Java code into methods without requiring
recompilation, repackaging, or redeployment — and injection can be performed at JVM startup
or while the application is running. See the [Byteman tutorial](https://developer.jboss.org/wiki/ABytemanTutorial)
for a quick introduction.

\`\`\`bash
# Attach Byteman to 3 ZooKeeper servers at runtime
# (55001/55002/55003 = Byteman ports; 714/740/758 = ZK server PIDs)
./bminstall.sh -b -Dorg.jboss.byteman.transform.all -Dorg.jboss.byteman.verbose -p 55001 714
./bminstall.sh -b -Dorg.jboss.byteman.transform.all -Dorg.jboss.byteman.verbose -p 55002 740
./bminstall.sh -b -Dorg.jboss.byteman.transform.all -Dorg.jboss.byteman.verbose -p 55003 758

# load a fault injection script
./bmsubmit.sh -p 55002 -l my_zk_fault_injection.btm
# unload a fault injection script
./bmsubmit.sh -p 55002 -u my_zk_fault_injection.btm
\`\`\`

**Example 1:** Force a leader re-election by rolling over the leader's zxid.

\`\`\`bash
cat zk_leader_zxid_roll_over.btm

RULE trace zk_leader_zxid_roll_over
CLASS org.apache.zookeeper.server.quorum.Leader
METHOD propose
IF true
DO
  traceln("*** Leader zxid has rolled over, forcing re-election ***");
  $1.zxid = 4294967295L
ENDRULE
\`\`\`

**Example 2:** Make the leader drop ping packets to a specific follower. The leader will close
the \`LearnerHandler\` for that follower, and the follower will re-enter the quorum.

\`\`\`bash
cat zk_leader_drop_ping_packet.btm

RULE trace zk_leader_drop_ping_packet
CLASS org.apache.zookeeper.server.quorum.LearnerHandler
METHOD ping
AT ENTRY
IF $0.sid == 2
DO
  traceln("*** Leader drops ping packet to sid: 2 ***");
  return;
ENDRULE
\`\`\`

**Example 3:** Make a follower drop ACK packets. This has limited effect during broadcast since
the leader only needs a majority of ACKs to commit a proposal.

\`\`\`bash
cat zk_follower_drop_ack_packet.btm

RULE trace zk.follower_drop_ack_packet
CLASS org.apache.zookeeper.server.quorum.SendAckRequestProcessor
METHOD processRequest
AT ENTRY
IF true
DO
  traceln("*** Follower drops ACK packet ***");
  return;
ENDRULE
\`\`\`

### Jepsen Test

[Jepsen](https://github.com/jepsen-io/jepsen) is a framework for distributed systems
verification with fault injection. It has been used to verify eventually-consistent databases,
linearizable coordination systems, and distributed task schedulers.

Running the [Dockerized Jepsen](https://github.com/jepsen-io/jepsen/blob/master/docker/README.md)
is the simplest way to get started.

Installation:

\`\`\`bash
git clone git@github.com:jepsen-io/jepsen.git
cd docker
# initial setup may take a while
./up.sh
# verify one control node and five DB nodes are running
docker ps
     CONTAINER ID        IMAGE               COMMAND                 CREATED             STATUS              PORTS                     NAMES
     8265f1d3f89c        docker_control      "/bin/sh -c /init.sh"   9 hours ago         Up 4 hours          0.0.0.0:32769->8080/tcp   jepsen-control
     8a646102da44        docker_n5           "/run.sh"               9 hours ago         Up 3 hours          22/tcp                    jepsen-n5
     385454d7e520        docker_n1           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n1
     a62d6a9d5f8e        docker_n2           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n2
     1485e89d0d9a        docker_n3           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n3
     27ae01e1a0c5        docker_node         "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-node
     53c444b00ebd        docker_n4           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n4
\`\`\`

Running the test:

\`\`\`bash
# enter the control container
docker exec -it jepsen-control bash
# run the ZooKeeper test
cd zookeeper && lein run test --concurrency 10
# passing output looks like:
INFO [2019-04-01 11:25:23,719] jepsen worker 8 - jepsen.util 8	:ok	:read	2
INFO [2019-04-01 11:25:23,722] jepsen worker 3 - jepsen.util 3	:invoke	:cas	[0 4]
INFO [2019-04-01 11:25:23,760] jepsen worker 3 - jepsen.util 3	:fail	:cas	[0 4]
INFO [2019-04-01 11:25:23,791] jepsen worker 1 - jepsen.util 1	:invoke	:read	nil
INFO [2019-04-01 11:25:23,794] jepsen worker 1 - jepsen.util 1	:ok	:read	2
INFO [2019-04-01 11:25:24,038] jepsen worker 0 - jepsen.util 0	:invoke	:write	4
INFO [2019-04-01 11:25:24,073] jepsen worker 0 - jepsen.util 0	:ok	:write	4
...............................................................................
Everything looks good! ヽ('ー\`)ノ
\`\`\`

Read [this blog post](https://aphyr.com/posts/291-call-me-maybe-zookeeper) to learn more
about the Jepsen analysis of ZooKeeper.
`,d={title:"Tools",description:"Reference for the scripts and tools bundled with ZooKeeper — including server management scripts, snapshot and transaction log utilities, benchmark tools, and testing frameworks."},k=[{href:"/admin-ops/cli"},{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory"},{href:"https://github.com/brianfrankcooper/YCSB"},{href:"https://github.com/brianfrankcooper/YCSB#getting-started"},{href:"https://github.com/phunt/zk-smoketest"},{href:"https://byteman.jboss.org/"},{href:"https://developer.jboss.org/wiki/ABytemanTutorial"},{href:"https://github.com/jepsen-io/jepsen"},{href:"https://github.com/jepsen-io/jepsen/blob/master/docker/README.md"},{href:"https://aphyr.com/posts/291-call-me-maybe-zookeeper"}],c={contents:[{heading:"zkserversh",content:"Manage the ZooKeeper server process."},{heading:"zkserversh",content:`The status command establishes a client connection to execute diagnostic commands.
When the ZooKeeper cluster is started in TLS-only mode (by omitting clientPort from
zoo.cfg), additional SSL configuration must be provided:`},{heading:"zkclish",content:"See ZooKeeper CLI."},{heading:"zkenvsh",content:"Sets environment variables for the ZooKeeper server. Key variables:"},{heading:"zkenvsh",content:"ZOO_LOG_DIR — the directory where logs are stored."},{heading:"zkcleanupsh",content:"Clean up old snapshots and transaction logs."},{heading:"zktxnlogtoolkitsh",content:"Dump and recover transaction log files with broken CRC entries."},{heading:"zktxnlogtoolkitsh",content:`The default behavior is safe — it dumps the entries of the given transaction log file
to the screen (same as -d,--dump):`},{heading:"zktxnlogtoolkitsh",content:`In recovery mode (-r,--recover), the original file is left untouched and all transactions
are copied to a new file with a .fixed suffix. CRC values are recalculated; if the calculated
value does not match the original, the new value is used. By default the tool is interactive,
asking for confirmation on each CRC error:`},{heading:"zktxnlogtoolkitsh",content:"Yes — write the recalculated CRC to the new file."},{heading:"zktxnlogtoolkitsh",content:"No — copy the original CRC value."},{heading:"zktxnlogtoolkitsh",content:"Abort — abort the operation. The .fixed file will not be deleted and may be in a half-complete state."},{heading:"zktxnlogtoolkitsh",content:`Use -v,--verbose to print all records (not just broken ones). Use -y,--yes to fix all
CRC errors automatically without prompting.`},{heading:"zksnapshottoolkitsh",content:"Dump a snapshot file to stdout, showing detailed information for each znode."},{heading:"zksnapshotrecursivesummarytoolkitsh",content:"Recursively collect and display child count and data size for a selected node."},{heading:"zksnapshotcomparersh",content:`Compare two snapshots with configurable thresholds and filters, outputting the delta —
which znodes were added, updated, or deleted. Useful for offline consistency checks and
data trend analysis. Only permanent nodes are reported; sessions and ephemeral nodes are ignored.`},{heading:"zksnapshotcomparersh",content:"Tuning parameters:"},{heading:"zksnapshotcomparersh",content:"--nodes — threshold for number of descendant nodes added/removed."},{heading:"zksnapshotcomparersh",content:"--bytes — threshold for bytes added/removed."},{heading:"locating-snapshots",content:`Snapshots are stored in the ZooKeeper data directory
configured in conf/zoo.cfg.`},{heading:"supported-snapshot-formats",content:`Uncompressed snapshots and compressed formats (snappy, gz) are all supported.
Snapshots in different formats can be compared directly without manual decompression.`},{heading:"running-the-tool",content:"Running the tool with no arguments prints the help page:"},{heading:"running-the-tool",content:"Example command:"},{heading:"running-the-tool",content:"Example output:"},{heading:"interactive-mode",content:"Add -i / --interactive to enter interactive mode:"},{heading:"interactive-mode",content:"Three navigation options are available:"},{heading:"interactive-mode",content:"Press Enter to print the current depth layer."},{heading:"interactive-mode",content:"Type a number to jump to and print all nodes at that depth."},{heading:"interactive-mode",content:"Enter an absolute path (starting with /) to print the immediate subtree of that node."},{heading:"interactive-mode",content:"Note: only nodes passing the bytes and nodes thresholds are shown."},{heading:"interactive-mode",content:"Press Enter to move to the next depth layer:"},{heading:"interactive-mode",content:"Type a number to jump forward or backward to a specific depth:"},{heading:"interactive-mode",content:"Out-of-range depth is handled gracefully:"},{heading:"interactive-mode",content:"Enter an absolute path to print the immediate subtree of a node:"},{heading:"interactive-mode",content:"Invalid path and invalid input are handled:"},{heading:"interactive-mode",content:"The tool exits interactive mode automatically when all layers are compared, or press ^C to exit at any time."},{heading:"ycsb",content:"YCSB (Yahoo Cloud Serving Benchmark) can be used to benchmark ZooKeeper. Follow the steps below to get started."},{heading:"ycsb",content:"Start ZooKeeper Server(s)"},{heading:"ycsb",content:"Start your ZooKeeper ensemble before running any benchmark."},{heading:"ycsb",content:"Install Java and Maven"},{heading:"ycsb",content:"Ensure a JDK and Maven are installed on the machine running the benchmark."},{heading:"ycsb",content:"Set Up YCSB"},{heading:"ycsb",content:"Clone and build the ZooKeeper binding:"},{heading:"ycsb",content:"See the YCSB README for more details."},{heading:"ycsb",content:"Configure ZooKeeper Connection Parameters"},{heading:"ycsb",content:"Set the following properties in your workload file or via the shell:"},{heading:"ycsb",content:"zookeeper.connectString — connection string (e.g. 127.0.0.1:2181/benchmark)"},{heading:"ycsb",content:"zookeeper.sessionTimeout — session timeout in milliseconds"},{heading:"ycsb",content:"zookeeper.watchFlag — enable ZooKeeper watches (true or false, default false). This measures the effect of watch overhead on read/write performance, not watch notification latency."},{heading:"ycsb",content:"Or set properties directly on the command line (create the /benchmark namespace first using create /benchmark in the CLI):"},{heading:"ycsb",content:"Load Data and Run Tests"},{heading:"ycsb",content:"Load data:"},{heading:"ycsb",content:"Run the workload (workloadb is recommended as the most representative read-heavy workload):"},{heading:"zk-smoketest",content:`zk-smoketest provides a simple smoketest client
for a ZooKeeper ensemble. Useful for verifying new, updated, or existing installations.`},{heading:"byteman",content:`Byteman is a tool for tracing, monitoring, and testing Java
application and JDK runtime code. It injects Java code into methods without requiring
recompilation, repackaging, or redeployment — and injection can be performed at JVM startup
or while the application is running. See the Byteman tutorial
for a quick introduction.`},{heading:"byteman",content:"Example 1: Force a leader re-election by rolling over the leader's zxid."},{heading:"byteman",content:`Example 2: Make the leader drop ping packets to a specific follower. The leader will close
the LearnerHandler for that follower, and the follower will re-enter the quorum.`},{heading:"byteman",content:`Example 3: Make a follower drop ACK packets. This has limited effect during broadcast since
the leader only needs a majority of ACKs to commit a proposal.`},{heading:"jepsen-test",content:`Jepsen is a framework for distributed systems
verification with fault injection. It has been used to verify eventually-consistent databases,
linearizable coordination systems, and distributed task schedulers.`},{heading:"jepsen-test",content:`Running the Dockerized Jepsen
is the simplest way to get started.`},{heading:"jepsen-test",content:"Installation:"},{heading:"jepsen-test",content:"Running the test:"},{heading:"jepsen-test",content:`Read this blog post to learn more
about the Jepsen analysis of ZooKeeper.`}],headings:[{id:"scripts",content:"Scripts"},{id:"zkserversh",content:"zkServer.sh"},{id:"zkclish",content:"zkCli.sh"},{id:"zkenvsh",content:"zkEnv.sh"},{id:"zkcleanupsh",content:"zkCleanup.sh"},{id:"zktxnlogtoolkitsh",content:"zkTxnLogToolkit.sh"},{id:"zksnapshottoolkitsh",content:"zkSnapShotToolkit.sh"},{id:"zksnapshotrecursivesummarytoolkitsh",content:"zkSnapshotRecursiveSummaryToolkit.sh"},{id:"zksnapshotcomparersh",content:"zkSnapshotComparer.sh"},{id:"locating-snapshots",content:"Locating Snapshots"},{id:"supported-snapshot-formats",content:"Supported Snapshot Formats"},{id:"running-the-tool",content:"Running the Tool"},{id:"interactive-mode",content:"Interactive Mode"},{id:"benchmark",content:"Benchmark"},{id:"ycsb",content:"YCSB"},{id:"zk-smoketest",content:"zk-smoketest"},{id:"testing",content:"Testing"},{id:"fault-injection-framework",content:"Fault Injection Framework"},{id:"byteman",content:"Byteman"},{id:"jepsen-test",content:"Jepsen Test"}]};const o=[{depth:2,url:"#scripts",title:s.jsx(s.Fragment,{children:"Scripts"})},{depth:3,url:"#zkserversh",title:s.jsx(s.Fragment,{children:"zkServer.sh"})},{depth:3,url:"#zkclish",title:s.jsx(s.Fragment,{children:"zkCli.sh"})},{depth:3,url:"#zkenvsh",title:s.jsx(s.Fragment,{children:"zkEnv.sh"})},{depth:3,url:"#zkcleanupsh",title:s.jsx(s.Fragment,{children:"zkCleanup.sh"})},{depth:3,url:"#zktxnlogtoolkitsh",title:s.jsx(s.Fragment,{children:"zkTxnLogToolkit.sh"})},{depth:3,url:"#zksnapshottoolkitsh",title:s.jsx(s.Fragment,{children:"zkSnapShotToolkit.sh"})},{depth:3,url:"#zksnapshotrecursivesummarytoolkitsh",title:s.jsx(s.Fragment,{children:"zkSnapshotRecursiveSummaryToolkit.sh"})},{depth:3,url:"#zksnapshotcomparersh",title:s.jsx(s.Fragment,{children:"zkSnapshotComparer.sh"})},{depth:4,url:"#locating-snapshots",title:s.jsx(s.Fragment,{children:"Locating Snapshots"})},{depth:4,url:"#supported-snapshot-formats",title:s.jsx(s.Fragment,{children:"Supported Snapshot Formats"})},{depth:4,url:"#running-the-tool",title:s.jsx(s.Fragment,{children:"Running the Tool"})},{depth:4,url:"#interactive-mode",title:s.jsx(s.Fragment,{children:"Interactive Mode"})},{depth:2,url:"#benchmark",title:s.jsx(s.Fragment,{children:"Benchmark"})},{depth:3,url:"#ycsb",title:s.jsx(s.Fragment,{children:"YCSB"})},{depth:3,url:"#zk-smoketest",title:s.jsx(s.Fragment,{children:"zk-smoketest"})},{depth:2,url:"#testing",title:s.jsx(s.Fragment,{children:"Testing"})},{depth:3,url:"#fault-injection-framework",title:s.jsx(s.Fragment,{children:"Fault Injection Framework"})},{depth:4,url:"#byteman",title:s.jsx(s.Fragment,{children:"Byteman"})},{depth:3,url:"#jepsen-test",title:s.jsx(s.Fragment,{children:"Jepsen Test"})}];function l(e){const i={a:"a",code:"code",h2:"h2",h3:"h3",h4:"h4",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...e.components},{Step:n,Steps:h}=i;return n||a("Step"),h||a("Steps"),s.jsxs(s.Fragment,{children:[s.jsx(i.h2,{id:"scripts",children:"Scripts"}),`
`,s.jsx(i.h3,{id:"zkserversh",children:"zkServer.sh"}),`
`,s.jsx(i.p,{children:"Manage the ZooKeeper server process."}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# start the server"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" start"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# start the server in the foreground (useful for debugging)"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" start-foreground"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# stop the server"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" stop"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# restart the server"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" restart"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# show the status, mode, and role of the server"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" status"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"JMX"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" enabled"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" by"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" default"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Using"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" config:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /data/software/zookeeper/conf/zoo.cfg"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Mode:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" standalone"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# print the startup parameters"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" print-cmd"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# show the ZooKeeper server version"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Apache"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ZooKeeper,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 3.6.0-SNAPSHOT"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 06/11/2019"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 05:39"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" GMT"})]})]})})}),`
`,s.jsxs(i.p,{children:["The ",s.jsx(i.code,{children:"status"}),` command establishes a client connection to execute diagnostic commands.
When the ZooKeeper cluster is started in TLS-only mode (by omitting `,s.jsx(i.code,{children:"clientPort"}),` from
`,s.jsx(i.code,{children:"zoo.cfg"}),"), additional SSL configuration must be provided:"]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"CLIENT_JVMFLAGS"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"-Dzookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty '}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  -Dzookeeper.ssl.trustStore.location=/tmp/clienttrust.jks "}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  -Dzookeeper.ssl.trustStore.password=password "}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  -Dzookeeper.ssl.keyStore.location=/tmp/client.jks "}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  -Dzookeeper.ssl.keyStore.password=password "}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'  -Dzookeeper.client.secure=true"'}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  ./zkServer.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" status"})]})]})})}),`
`,s.jsx(i.h3,{id:"zkclish",children:"zkCli.sh"}),`
`,s.jsxs(i.p,{children:["See ",s.jsx(i.a,{href:"/admin-ops/cli",children:"ZooKeeper CLI"}),"."]}),`
`,s.jsx(i.h3,{id:"zkenvsh",children:"zkEnv.sh"}),`
`,s.jsx(i.p,{children:"Sets environment variables for the ZooKeeper server. Key variables:"}),`
`,s.jsxs(i.ul,{children:[`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"ZOO_LOG_DIR"})," — the directory where logs are stored."]}),`
`]}),`
`,s.jsx(i.h3,{id:"zkcleanupsh",children:"zkCleanup.sh"}),`
`,s.jsx(i.p,{children:"Clean up old snapshots and transaction logs."}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Usage: ./zkCleanup.sh dataLogDir [snapDir] -n count"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"#   dataLogDir -- path to the transaction log directory"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"#   snapDir    -- path to the snapshot directory (optional)"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"#   count      -- number of recent snaps/logs to keep (must be >= 3)"})}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Keep the latest 5 logs and snapshots"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkCleanup.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -n"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 5"})]})]})})}),`
`,s.jsx(i.h3,{id:"zktxnlogtoolkitsh",children:"zkTxnLogToolkit.sh"}),`
`,s.jsx(i.p,{children:"Dump and recover transaction log files with broken CRC entries."}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkTxnLogToolkit.sh"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"usage:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" TxnLogToolkit"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [-dhrv] txn_log_file_name"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-d,--dump"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"      Dump"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mode."}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Dump"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" all"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" entries"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" of"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" log"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" file."}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (this "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"is"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" default"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:")"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-h,--help"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"      Print"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" help"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" message"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-r,--recover"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   Recovery"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mode."}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Re-calculate"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" for"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" broken"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" entries."})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-v,--verbose"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   Be"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" verbose"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" in"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" recovery"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mode:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" print"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" all"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" entries,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" not"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" just"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fixed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ones."})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-y,--yes"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"       Non-interactive"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mode:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" repair"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" all"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" errors"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" without"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" asking"})]})]})})}),`
`,s.jsxs(i.p,{children:[`The default behavior is safe — it dumps the entries of the given transaction log file
to the screen (same as `,s.jsx(i.code,{children:"-d,--dump"}),"):"]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkTxnLogToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" log.100000001"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ZooKeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Transactional"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Log"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" File"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" with"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" dbid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" txnlog"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" format"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:15:58"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bafcc40000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000001"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" createSession"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 30000"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ERROR"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:16:05"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bafcc40000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000002"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" closeSession"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" null"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:16:05"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bafcc40000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000002"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" closeSession"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" null"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:16:12"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x26295bafcc90000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000003"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" createSession"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 30000"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:17:34"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x26295bafcc90000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x200000001"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" closeSession"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" null"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:17:34"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bd23720000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x200000002"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" createSession"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 30000"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:17:34"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bd23720000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x2"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x200000003"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" create"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" '/andor,#626262,v{s{31,s{'world,'anyone}}},F,1"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"EOF reached after 6 txns."})})]})})}),`
`,s.jsxs(i.p,{children:["In ",s.jsx(i.strong,{children:"recovery mode"})," (",s.jsx(i.code,{children:"-r,--recover"}),`), the original file is left untouched and all transactions
are copied to a new file with a `,s.jsx(i.code,{children:".fixed"}),` suffix. CRC values are recalculated; if the calculated
value does not match the original, the new value is used. By default the tool is interactive,
asking for confirmation on each CRC error:`]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkTxnLogToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -r"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" log.100000001"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ZooKeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Transactional"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Log"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" File"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" with"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" dbid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" txnlog"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" format"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ERROR"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:16:05"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bafcc40000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000002"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" closeSession"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" null"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Would"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" you"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" like"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" to"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fix"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" it"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (Yes/No/Abort) "}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"?"})]})]})})}),`
`,s.jsxs(i.ul,{children:[`
`,s.jsxs(i.li,{children:[s.jsx(i.strong,{children:"Yes"})," — write the recalculated CRC to the new file."]}),`
`,s.jsxs(i.li,{children:[s.jsx(i.strong,{children:"No"})," — copy the original CRC value."]}),`
`,s.jsxs(i.li,{children:[s.jsx(i.strong,{children:"Abort"})," — abort the operation. The ",s.jsx(i.code,{children:".fixed"})," file will not be deleted and may be in a half-complete state."]}),`
`]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkTxnLogToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -r"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" log.100000001"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ZooKeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Transactional"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Log"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" File"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" with"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" dbid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" txnlog"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" format"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ERROR"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 4/5/18"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 2:16:05"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" PM"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CEST"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" session"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x16295bafcc40000"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" cxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x100000002"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" closeSession"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" null"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Would"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" you"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" like"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" to"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fix"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" it"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (Yes/No/Abort) "}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"?"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" y"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"EOF"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" reached"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" after"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 6"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" txns."})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Recovery"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" file"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" log.100000001.fixed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" has"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" been"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" written"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" with"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fixed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CRC"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" error"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"s"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:")"})]})]})})}),`
`,s.jsxs(i.p,{children:["Use ",s.jsx(i.code,{children:"-v,--verbose"})," to print all records (not just broken ones). Use ",s.jsx(i.code,{children:"-y,--yes"}),` to fix all
CRC errors automatically without prompting.`]}),`
`,s.jsx(i.h3,{id:"zksnapshottoolkitsh",children:"zkSnapShotToolkit.sh"}),`
`,s.jsx(i.p,{children:"Dump a snapshot file to stdout, showing detailed information for each znode."}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# show usage"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkSnapShotToolkit.sh"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"USAGE:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" SnapshotFormatter"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [-d"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"|"}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"-json]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" snapshot_file"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"       -d"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" dump"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" data"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" for"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" each"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" znode"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"       -json"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" dump"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" znode"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" info"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" in"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" json"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" format"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# show each znode's metadata without data content"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkSnapShotToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /data/zkdata/version-2/snapshot.fa01000186d"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"/zk-latencies_4/session_946"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  cZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000f0003110b"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  ctime"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Wed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Sep"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 19"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 21:58:22"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CST"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2018"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  mZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000f0003110b"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  mtime"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Wed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Sep"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 19"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 21:58:22"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CST"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2018"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  pZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000f0003110b"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  cversion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  dataVersion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  aclVersion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  ephemeralOwner"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000000000000"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  dataLength"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 100"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# -d: include data content"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkSnapShotToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -d"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /data/zkdata/version-2/snapshot.fa01000186d"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"/zk-latencies2/session_26229"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  cZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000900007ba0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  ctime"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Wed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Aug"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 15"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 20:13:52"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CST"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2018"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  mZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000900007ba0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  mtime"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Wed"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Aug"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 15"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 20:13:52"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CST"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2018"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  pZxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000900007ba0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  cversion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  dataVersion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  aclVersion"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  ephemeralOwner"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0x00000000000000"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  data"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" eHh4eHh4eHh4eHh4eA=="})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# -json: output in JSON format"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkSnapShotToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -json"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /data/zkdata/version-2/snapshot.fa01000186d"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[[1,0,{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"progname"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"SnapshotFormatter.java"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"progver"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"0.01"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"timestamp"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1559788148637},[{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"\\/"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1001},[{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"zookeeper"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1002},{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"config"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1003},[{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"quota"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1004},[{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"test"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1005},{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"zookeeper_limits"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":52,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":52,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1006},{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"zookeeper_stats"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":15,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":15,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1007}]]],{"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"name"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"test"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"asize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dsize"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dev"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":0,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"ino"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":1008}]]"})]})]})})}),`
`,s.jsx(i.h3,{id:"zksnapshotrecursivesummarytoolkitsh",children:"zkSnapshotRecursiveSummaryToolkit.sh"}),`
`,s.jsx(i.p,{children:"Recursively collect and display child count and data size for a selected node."}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ./zkSnapshotRecursiveSummaryToolkit.sh"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"USAGE:"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"SnapshotRecursiveSummary"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  <"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"snapshot_fil"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"e"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  <"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"starting_nod"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"e"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  <"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"max_dept"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"h"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"snapshot_file:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  path"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" to"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ZooKeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" snapshot"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"starting_node:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" path"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" in"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ZooKeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" tree"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" where"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" traversal"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" begins"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"max_depth:"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"      depth"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" limit"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" for"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" output"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (0 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" no"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" limit"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"; "}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" starting"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" node"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" +"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" direct"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" children"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"                2"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" one"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" more"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" level,"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" etc."}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"). Only affects display, NOT the calculation."})]})]})})}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# display stats for the root node and 2 levels of descendants"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./zkSnapshotRecursiveSummaryToolkit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /data/zkdata/version-2/snapshot.fa01000186d"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"/"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1250511"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1952186580"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /zookeeper"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /solr"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1773"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"--"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 8419162"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /solr/configs"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1640"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 8407643"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /solr/overseer"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 6"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /solr/live_nodes"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   children:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"----"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   data:"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]})]})})}),`
`,s.jsx(i.h3,{id:"zksnapshotcomparersh",children:"zkSnapshotComparer.sh"}),`
`,s.jsx(i.p,{children:`Compare two snapshots with configurable thresholds and filters, outputting the delta —
which znodes were added, updated, or deleted. Useful for offline consistency checks and
data trend analysis. Only permanent nodes are reported; sessions and ephemeral nodes are ignored.`}),`
`,s.jsx(i.p,{children:"Tuning parameters:"}),`
`,s.jsxs(i.ul,{children:[`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"--nodes"})," — threshold for number of descendant nodes added/removed."]}),`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"--bytes"})," — threshold for bytes added/removed."]}),`
`]}),`
`,s.jsx(i.h4,{id:"locating-snapshots",children:"Locating Snapshots"}),`
`,s.jsxs(i.p,{children:["Snapshots are stored in the ",s.jsx(i.a,{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory",children:"ZooKeeper data directory"}),`
configured in `,s.jsx(i.code,{children:"conf/zoo.cfg"}),"."]}),`
`,s.jsx(i.h4,{id:"supported-snapshot-formats",children:"Supported Snapshot Formats"}),`
`,s.jsxs(i.p,{children:["Uncompressed snapshots and compressed formats (",s.jsx(i.code,{children:"snappy"}),", ",s.jsx(i.code,{children:"gz"}),`) are all supported.
Snapshots in different formats can be compared directly without manual decompression.`]}),`
`,s.jsx(i.h4,{id:"running-the-tool",children:"Running the Tool"}),`
`,s.jsx(i.p,{children:"Running the tool with no arguments prints the help page:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"usage: java -cp <classPath> org.apache.zookeeper.server.SnapshotComparer"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -b,--bytes <BYTETHRESHOLD>   (Required) The node data delta size threshold, in bytes, for printing the node."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -d,--debug                   Use debug output."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -i,--interactive             Enter interactive mode."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -l,--left <LEFT>             (Required) The left snapshot file."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -n,--nodes <NODETHRESHOLD>   (Required) The descendant node delta size threshold, in nodes, for printing the node."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:" -r,--right <RIGHT>           (Required) The right snapshot file."})})]})})}),`
`,s.jsx(i.p,{children:"Example command:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsx(i.code,{children:s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1"})})})})}),`
`,s.jsx(i.p,{children:"Example output:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Deserialized snapshot in snapshot.44 in 0.002741 seconds"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Processed data tree in 0.000361 seconds"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node count: 10"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Total size: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Max depth: 4"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 0: 1"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 1: 2"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 2: 4"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 3: 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node count: 22"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Total size: 2903"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Max depth: 5"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 0: 1"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 1: 2"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 2: 4"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 3: 7"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Count of nodes at depth 4: 8"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node  found in both trees. Delta: 2903 bytes, 12 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 1"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test found in both trees. Delta: 2903 bytes, 12 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 2"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz found in both trees. Delta: 730 bytes, 3 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy found in both trees. Delta: 2173 bytes, 9 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz/12345 found in both trees. Delta: 9 bytes, 1 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz/a found only in right tree. Descendant size: 721. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/anotherTest found in both trees. Delta: 1738 bytes, 2 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_1 found only in right tree. Descendant size: 344. Descendant count: 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_2 found only in right tree. Descendant size: 91. Descendant count: 2"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 4"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz/12345/abcdef found only in right tree. Descendant size: 9. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/anotherTest/abc found only in right tree. Descendant size: 1738. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_1/a found only in right tree. Descendant size: 93. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_1/b found only in right tree. Descendant size: 251. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_2/xyz found only in right tree. Descendant size: 33. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_2/y found only in right tree. Descendant size: 58. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"All layers compared."})})]})})}),`
`,s.jsx(i.h4,{id:"interactive-mode",children:"Interactive Mode"}),`
`,s.jsxs(i.p,{children:["Add ",s.jsx(i.code,{children:"-i"})," / ",s.jsx(i.code,{children:"--interactive"})," to enter interactive mode:"]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsx(i.code,{children:s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1 -i"})})})})}),`
`,s.jsx(i.p,{children:"Three navigation options are available:"}),`
`,s.jsxs(i.ul,{children:[`
`,s.jsxs(i.li,{children:["Press ",s.jsx(i.strong,{children:"Enter"})," to print the current depth layer."]}),`
`,s.jsxs(i.li,{children:["Type a ",s.jsx(i.strong,{children:"number"})," to jump to and print all nodes at that depth."]}),`
`,s.jsxs(i.li,{children:["Enter an ",s.jsx(i.strong,{children:"absolute path"})," (starting with ",s.jsx(i.code,{children:"/"}),") to print the immediate subtree of that node."]}),`
`]}),`
`,s.jsx(i.p,{children:"Note: only nodes passing the bytes and nodes thresholds are shown."}),`
`,s.jsx(i.p,{children:"Press Enter to move to the next depth layer:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Current depth is 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Press enter to move to print current depth layer;"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node  found in both trees. Delta: 2903 bytes, 12 descendants"})})]})})}),`
`,s.jsx(i.p,{children:"Type a number to jump forward or backward to a specific depth:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Current depth is 1"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Type a number to jump to and print all nodes at a given depth;"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz/12345 found in both trees. Delta: 9 bytes, 1 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz/a found only in right tree. Descendant size: 721. Descendant count: 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Filtered node /zk_test/gz/anotherOne of left size 0, right size 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Filtered right node /zk_test/gz/b of size 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/anotherTest found in both trees. Delta: 1738 bytes, 2 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_1 found only in right tree. Descendant size: 344. Descendant count: 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy/test_2 found only in right tree. Descendant size: 91. Descendant count: 2"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Current depth is 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Type a number to jump to and print all nodes at a given depth;"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for depth 0"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node  found in both trees. Delta: 2903 bytes, 12 descendants"})})]})})}),`
`,s.jsx(i.p,{children:"Out-of-range depth is handled gracefully:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Current depth is 1"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"10"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Depth must be in range [0, 4]"})})]})})}),`
`,s.jsx(i.p,{children:"Enter an absolute path to print the immediate subtree of a node:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Current depth is 3"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"..."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Enter an ABSOLUTE path to print the immediate subtree of a node."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"/zk_test"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for node /zk_test"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/gz found in both trees. Delta: 730 bytes, 3 descendants"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Node /zk_test/snappy found in both trees. Delta: 2173 bytes, 9 descendants"})})]})})}),`
`,s.jsx(i.p,{children:"Invalid path and invalid input are handled:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Enter an ABSOLUTE path to print the immediate subtree of a node."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"/non-exist-path"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Analysis for node /non-exist-path"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Path /non-exist-path is neither found in left tree nor right tree."})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"12223999999999999999999999999999999999999"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{children:"Input 12223999999999999999999999999999999999999 is not valid. Depth must be in range [0, 4]. Path must be an absolute path which starts with '/'."})})]})})}),`
`,s.jsxs(i.p,{children:["The tool exits interactive mode automatically when all layers are compared, or press ",s.jsx(i.code,{children:"^C"})," to exit at any time."]}),`
`,s.jsx(i.h2,{id:"benchmark",children:"Benchmark"}),`
`,s.jsx(i.h3,{id:"ycsb",children:"YCSB"}),`
`,s.jsxs(i.p,{children:[s.jsx(i.a,{href:"https://github.com/brianfrankcooper/YCSB",children:"YCSB"})," (Yahoo Cloud Serving Benchmark) can be used to benchmark ZooKeeper. Follow the steps below to get started."]}),`
`,s.jsxs(h,{children:[s.jsxs(n,{children:[s.jsx(i.p,{children:s.jsx(i.strong,{children:"Start ZooKeeper Server(s)"})}),s.jsx(i.p,{children:"Start your ZooKeeper ensemble before running any benchmark."})]}),s.jsxs(n,{children:[s.jsx(i.p,{children:s.jsx(i.strong,{children:"Install Java and Maven"})}),s.jsx(i.p,{children:"Ensure a JDK and Maven are installed on the machine running the benchmark."})]}),s.jsxs(n,{children:[s.jsx(i.p,{children:s.jsx(i.strong,{children:"Set Up YCSB"})}),s.jsx(i.p,{children:"Clone and build the ZooKeeper binding:"}),s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"git"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" clone"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" http://github.com/brianfrankcooper/YCSB.git"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"cd"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" YCSB"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"mvn"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -pl"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" site.ycsb:zookeeper-binding"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -am"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" clean"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" package"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -DskipTests"})]})]})})}),s.jsxs(i.p,{children:["See the ",s.jsx(i.a,{href:"https://github.com/brianfrankcooper/YCSB#getting-started",children:"YCSB README"})," for more details."]})]}),s.jsxs(n,{children:[s.jsx(i.p,{children:s.jsx(i.strong,{children:"Configure ZooKeeper Connection Parameters"})}),s.jsx(i.p,{children:"Set the following properties in your workload file or via the shell:"}),s.jsxs(i.ul,{children:[`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"zookeeper.connectString"})," — connection string (e.g. ",s.jsx(i.code,{children:"127.0.0.1:2181/benchmark"}),")"]}),`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"zookeeper.sessionTimeout"})," — session timeout in milliseconds"]}),`
`,s.jsxs(i.li,{children:[s.jsx(i.code,{children:"zookeeper.watchFlag"})," — enable ZooKeeper watches (",s.jsx(i.code,{children:"true"})," or ",s.jsx(i.code,{children:"false"}),", default ",s.jsx(i.code,{children:"false"}),"). This measures the effect of watch overhead on read/write performance, not watch notification latency."]}),`
`]}),s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.watchFlag="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"true"})]})]})})}),s.jsxs(i.p,{children:["Or set properties directly on the command line (create the ",s.jsx(i.code,{children:"/benchmark"})," namespace first using ",s.jsx(i.code,{children:"create /benchmark"})," in the CLI):"]}),s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.sessionTimeout="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"30000"})]})]})})})]}),s.jsxs(n,{children:[s.jsx(i.p,{children:s.jsx(i.strong,{children:"Load Data and Run Tests"})}),s.jsx(i.p,{children:"Load data:"}),s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# -p recordcount: number of znodes to insert"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" load"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" recordcount="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"10000"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" >"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" outputLoad.txt"})]})]})})}),s.jsxs(i.p,{children:["Run the workload (",s.jsx(i.code,{children:"workloadb"})," is recommended as the most representative read-heavy workload):"]}),s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# test the effect of value size on performance"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fieldlength="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1000"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# test with multiple fields"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" fieldcount="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"20"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# HDR histogram output"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -threads"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hdrhistogram.percentiles=10,25,50,75,90,95,99,99.9"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" histogram.buckets="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"500"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# multi-client test (increase maxClientCnxns in zoo.cfg as needed)"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -threads"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 10"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# timeseries output"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -threads"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" measurementtype=timeseries"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" timeseries.granularity="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"50"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# cluster test"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=192.168.10.43:2181,192.168.10.45:2181,192.168.10.27:2181/benchmark"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# test leader performance only"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadb"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=192.168.10.43:2181/benchmark"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# large znodes (default jute.maxbuffer = 1 MB; set the same value on all ZK servers)"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bin/ycsb"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -jvm-args="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"-Djute.maxbuffer=4194304"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -s"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -P"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" workloads/workloadc"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -p"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.connectString=127.0.0.1:2181/benchmark"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# clean up after benchmarking: CLI: deleteall /benchmark"})})]})})})]})]}),`
`,s.jsx(i.h3,{id:"zk-smoketest",children:"zk-smoketest"}),`
`,s.jsxs(i.p,{children:[s.jsx(i.a,{href:"https://github.com/phunt/zk-smoketest",children:"zk-smoketest"}),` provides a simple smoketest client
for a ZooKeeper ensemble. Useful for verifying new, updated, or existing installations.`]}),`
`,s.jsx(i.h2,{id:"testing",children:"Testing"}),`
`,s.jsx(i.h3,{id:"fault-injection-framework",children:"Fault Injection Framework"}),`
`,s.jsx(i.h4,{id:"byteman",children:"Byteman"}),`
`,s.jsxs(i.p,{children:[s.jsx(i.a,{href:"https://byteman.jboss.org/",children:"Byteman"}),` is a tool for tracing, monitoring, and testing Java
application and JDK runtime code. It injects Java code into methods without requiring
recompilation, repackaging, or redeployment — and injection can be performed at JVM startup
or while the application is running. See the `,s.jsx(i.a,{href:"https://developer.jboss.org/wiki/ABytemanTutorial",children:"Byteman tutorial"}),`
for a quick introduction.`]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Attach Byteman to 3 ZooKeeper servers at runtime"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# (55001/55002/55003 = Byteman ports; 714/740/758 = ZK server PIDs)"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bminstall.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -b"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.transform.all"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.verbose"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 55001"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 714"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bminstall.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -b"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.transform.all"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.verbose"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 55002"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 740"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bminstall.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -b"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.transform.all"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -Dorg.jboss.byteman.verbose"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 55003"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 758"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# load a fault injection script"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bmsubmit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 55002"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -l"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" my_zk_fault_injection.btm"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# unload a fault injection script"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./bmsubmit.sh"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -p"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 55002"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -u"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" my_zk_fault_injection.btm"})]})]})})}),`
`,s.jsxs(i.p,{children:[s.jsx(i.strong,{children:"Example 1:"})," Force a leader re-election by rolling over the leader's zxid."]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"cat"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk_leader_zxid_roll_over.btm"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"RULE"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" trace"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk_leader_zxid_roll_over"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CLASS"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" org.apache.zookeeper.server.quorum.Leader"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"METHOD"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" propose"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"IF"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" true"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"DO"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  traceln("}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:'"*** Leader zxid has rolled over, forcing re-election ***"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"  $1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:".zxid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ="}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 4294967295L"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ENDRULE"})})]})})}),`
`,s.jsxs(i.p,{children:[s.jsx(i.strong,{children:"Example 2:"}),` Make the leader drop ping packets to a specific follower. The leader will close
the `,s.jsx(i.code,{children:"LearnerHandler"})," for that follower, and the follower will re-enter the quorum."]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"cat"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk_leader_drop_ping_packet.btm"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"RULE"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" trace"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk_leader_drop_ping_packet"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CLASS"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" org.apache.zookeeper.server.quorum.LearnerHandler"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"METHOD"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ping"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"AT"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ENTRY"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"IF"}),s.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" $0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:".sid"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" =="}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"DO"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  traceln("}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:'"*** Leader drops ping packet to sid: 2 ***"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  return"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ENDRULE"})})]})})}),`
`,s.jsxs(i.p,{children:[s.jsx(i.strong,{children:"Example 3:"}),` Make a follower drop ACK packets. This has limited effect during broadcast since
the leader only needs a majority of ACKs to commit a proposal.`]}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"cat"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk_follower_drop_ack_packet.btm"})]}),`
`,s.jsx(i.span,{className:"line"}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"RULE"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" trace"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zk.follower_drop_ack_packet"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"CLASS"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" org.apache.zookeeper.server.quorum.SendAckRequestProcessor"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"METHOD"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" processRequest"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"AT"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ENTRY"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"IF"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" true"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"DO"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  traceln("}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:'"*** Follower drops ACK packet ***"'}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  return"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ENDRULE"})})]})})}),`
`,s.jsx(i.h3,{id:"jepsen-test",children:"Jepsen Test"}),`
`,s.jsxs(i.p,{children:[s.jsx(i.a,{href:"https://github.com/jepsen-io/jepsen",children:"Jepsen"}),` is a framework for distributed systems
verification with fault injection. It has been used to verify eventually-consistent databases,
linearizable coordination systems, and distributed task schedulers.`]}),`
`,s.jsxs(i.p,{children:["Running the ",s.jsx(i.a,{href:"https://github.com/jepsen-io/jepsen/blob/master/docker/README.md",children:"Dockerized Jepsen"}),`
is the simplest way to get started.`]}),`
`,s.jsx(i.p,{children:"Installation:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"git"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" clone"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" git@github.com:jepsen-io/jepsen.git"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"cd"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" docker"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# initial setup may take a while"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./up.sh"})}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# verify one control node and five DB nodes are running"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"docker"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ps"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     CONTAINER"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ID"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        IMAGE"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"               COMMAND"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                 CREATED"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"             STATUS"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"              PORTS"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                     NAMES"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     8265f1d3f89c"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_control"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'      "/bin/sh -c /init.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"   9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 4"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          0.0.0.0:32769"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"-"}),s.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"8080/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   jepsen-control"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     8a646102da44"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_n5"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'           "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-n5"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     385454d7e520"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_n1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'           "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-n1"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     a62d6a9d5f8e"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_n2"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'           "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-n2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     1485e89d0d9a"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_n3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'           "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-n3"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     27ae01e1a0c5"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_node"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'         "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-node"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"     53c444b00ebd"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"        docker_n4"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'           "/run.sh"'}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"               9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ago"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"         Up"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 9"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" hours"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"          22/tcp"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    jepsen-n4"})]})]})})}),`
`,s.jsx(i.p,{children:"Running the test:"}),`
`,s.jsx(s.Fragment,{children:s.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:s.jsxs(i.code,{children:[s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# enter the control container"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"docker"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" exec"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -it"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen-control"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bash"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# run the ZooKeeper test"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"cd"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" && "}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"lein"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" run"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" test"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" --concurrency"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 10"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# passing output looks like:"})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:23,719]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 8"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 8"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:ok"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:read"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"	2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:23,722]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:invoke"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:cas"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"	[0 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"4]"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:23,760]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:fail"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:cas"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"	[0 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"4]"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:23,791]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:invoke"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:read"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	nil"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:23,794]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:ok"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:read"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"	2"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:24,038]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:invoke"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:write"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"	4"})]}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"INFO"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" [2019-04-01 "}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"11:25:24,073]"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" worker"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" -"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" jepsen.util"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:ok"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"	:write"}),s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"	4"})]}),`
`,s.jsx(i.span,{className:"line",children:s.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"..............................................................................."})}),`
`,s.jsxs(i.span,{className:"line",children:[s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"Everything"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" looks"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" good!"}),s.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ヽ"}),s.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),s.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"'ー`)ノ"})]})]})})}),`
`,s.jsxs(i.p,{children:["Read ",s.jsx(i.a,{href:"https://aphyr.com/posts/291-call-me-maybe-zookeeper",children:"this blog post"}),` to learn more
about the Jepsen analysis of ZooKeeper.`]})]})}function p(e={}){const{wrapper:i}=e.components||{};return i?s.jsx(i,{...e,children:s.jsx(l,{...e})}):l(e)}function a(e,i){throw new Error("Expected component `"+e+"` to be defined: you likely forgot to import, pass, or provide it.")}export{r as _markdown,p as default,k as extractedReferences,d as frontmatter,c as structuredData,o as toc};
