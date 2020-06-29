<!--
Copyright 2002-2020 The Apache Software Foundation

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

# A series of tools for ZooKeeper

* [Scripts](#Scripts)
    * [zkServer.sh](#zkServer)
    * [zkCli.sh](#zkCli)
    * [zkEnv.sh](#zkEnv)
    * [zkCleanup.sh](#zkCleanup)
    * [zkTxnLogToolkit.sh](#zkTxnLogToolkit)
    * [zkSnapShotToolkit.sh](#zkSnapShotToolkit)
    * [zkSnapshotComparer.sh](#zkSnapshotComparer)
    
* [Testing](#Testing)
    * [Jepsen Test](#jepsen-test)
    
<a name="Scripts"></a>

## Scripts

<a name="zkServer"></a>

### zkServer.sh
A command for the operations for the ZooKeeper server.

```bash
Usage: ./zkServer.sh {start|start-foreground|stop|version|restart|status|upgrade|print-cmd}
# start the server
./zkServer.sh start

# start the server in the foreground for debugging
./zkServer.sh start-foreground

# stop the server
./zkServer.sh stop

# restart the server
./zkServer.sh restart

# show the status,mode,role of the server
./zkServer.sh status
JMX enabled by default
Using config: /data/software/zookeeper/conf/zoo.cfg
Mode: standalone

# Deprecated
./zkServer.sh upgrade

# print the parameters of the start-up
./zkServer.sh print-cmd

# show the version of the ZooKeeper server
./zkServer.sh version
Apache ZooKeeper, version 3.6.0-SNAPSHOT 06/11/2019 05:39 GMT

```

The `status` command establishes a client connection to the server to execute diagnostic commands. 
When the ZooKeeper cluster is started in client SSL only mode (by omitting the clientPort
from the zoo.cfg), then additional SSL related configuration has to be provided before using 
the `./zkServer.sh status` command to find out if the ZooKeeper server is running. An example:

    CLIENT_JVMFLAGS="-Dzookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty -Dzookeeper.ssl.trustStore.location=/tmp/clienttrust.jks -Dzookeeper.ssl.trustStore.password=password -Dzookeeper.ssl.keyStore.location=/tmp/client.jks -Dzookeeper.ssl.keyStore.password=password -Dzookeeper.client.secure=true" ./zkServer.sh status


<a name="zkCli"></a>

### zkCli.sh
Look at the [ZooKeeperCLI](zookeeperCLI.html)

<a name="zkEnv"></a>

### zkEnv.sh
The environment setting for the ZooKeeper server

```bash
# the setting of log property
ZOO_LOG_DIR: the directory to store the logs
ZOO_LOG4J_PROP: the level of logs to print
```

<a name="zkCleanup"></a>

### zkCleanup.sh
Clean up the old snapshots and transaction logs.

```bash
Usage:
     * args dataLogDir [snapDir] -n count
     * dataLogDir -- path to the txn log directory
     * snapDir -- path to the snapshot directory
     * count -- the number of old snaps/logs you want to keep, value should be greater than or equal to 3
# Keep the latest 5 logs and snapshots
./zkCleanup.sh -n 5
```

<a name="zkTxnLogToolkit"></a>

### zkTxnLogToolkit.sh
TxnLogToolkit is a command line tool shipped with ZooKeeper which
is capable of recovering transaction log entries with broken CRC.

Running it without any command line parameters or with the `-h,--help` argument, it outputs the following help page:

    $ bin/zkTxnLogToolkit.sh
    usage: TxnLogToolkit [-dhrv] txn_log_file_name
    -d,--dump      Dump mode. Dump all entries of the log file. (this is the default)
    -h,--help      Print help message
    -r,--recover   Recovery mode. Re-calculate CRC for broken entries.
    -v,--verbose   Be verbose in recovery mode: print all entries, not just fixed ones.
    -y,--yes       Non-interactive mode: repair all CRC errors without asking

The default behaviour is safe: it dumps the entries of the given
transaction log file to the screen: (same as using `-d,--dump` parameter)

    $ bin/zkTxnLogToolkit.sh log.100000001
    ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
    4/5/18 2:15:58 PM CEST session 0x16295bafcc40000 cxid 0x0 zxid 0x100000001 createSession 30000
    CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
    4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
    4/5/18 2:16:12 PM CEST session 0x26295bafcc90000 cxid 0x0 zxid 0x100000003 createSession 30000
    4/5/18 2:17:34 PM CEST session 0x26295bafcc90000 cxid 0x0 zxid 0x200000001 closeSession null
    4/5/18 2:17:34 PM CEST session 0x16295bd23720000 cxid 0x0 zxid 0x200000002 createSession 30000
    4/5/18 2:18:02 PM CEST session 0x16295bd23720000 cxid 0x2 zxid 0x200000003 create '/andor,#626262,v{s{31,s{'world,'anyone}}},F,1
    EOF reached after 6 txns.

There's a CRC error in the 2nd entry of the above transaction log file. In **dump**
mode, the toolkit only prints this information to the screen without touching the original file. In
**recovery** mode (`-r,--recover` flag) the original file still remains
untouched and all transactions will be copied over to a new txn log file with ".fixed" suffix. It recalculates
CRC values and copies the calculated value, if it doesn't match the original txn entry.
By default, the tool works interactively: it asks for confirmation whenever CRC error encountered.

    $ bin/zkTxnLogToolkit.sh -r log.100000001
    ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
    CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
    Would you like to fix it (Yes/No/Abort) ?

Answering **Yes** means the newly calculated CRC value will be outputted
to the new file. **No** means that the original CRC value will be copied over.
**Abort** will abort the entire operation and exits.
(In this case the ".fixed" will not be deleted and left in a half-complete state: contains only entries which
have already been processed or only the header if the operation was aborted at the first entry.)

    $ bin/zkTxnLogToolkit.sh -r log.100000001
    ZooKeeper Transactional Log File with dbid 0 txnlog format version 2
    CRC ERROR - 4/5/18 2:16:05 PM CEST session 0x16295bafcc40000 cxid 0x1 zxid 0x100000002 closeSession null
    Would you like to fix it (Yes/No/Abort) ? y
    EOF reached after 6 txns.
    Recovery file log.100000001.fixed has been written with 1 fixed CRC error(s)

The default behaviour of recovery is to be silent: only entries with CRC error get printed to the screen.
One can turn on verbose mode with the `-v,--verbose` parameter to see all records.
Interactive mode can be turned off with the `-y,--yes` parameter. In this case all CRC errors will be fixed
in the new transaction file.

<a name="zkSnapShotToolkit"></a>

### zkSnapShotToolkit.sh
Dump a snapshot file to stdout, showing the detailed information of the each zk-node.

```bash
# help
./zkSnapShotToolkit.sh
/usr/bin/java
USAGE: SnapshotFormatter [-d|-json] snapshot_file
       -d dump the data for each znode
       -json dump znode info in json format

# show the each zk-node info without data content
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

# [-d] show the each zk-node info with data content
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

# [-json] show the each zk-node info with json format
./zkSnapShotToolkit.sh -json /data/zkdata/version-2/snapshot.fa01000186d
[[1,0,{"progname":"SnapshotFormatter.java","progver":"0.01","timestamp":1559788148637},[{"name":"\/","asize":0,"dsize":0,"dev":0,"ino":1001},[{"name":"zookeeper","asize":0,"dsize":0,"dev":0,"ino":1002},{"name":"config","asize":0,"dsize":0,"dev":0,"ino":1003},[{"name":"quota","asize":0,"dsize":0,"dev":0,"ino":1004},[{"name":"test","asize":0,"dsize":0,"dev":0,"ino":1005},{"name":"zookeeper_limits","asize":52,"dsize":52,"dev":0,"ino":1006},{"name":"zookeeper_stats","asize":15,"dsize":15,"dev":0,"ino":1007}]]],{"name":"test","asize":0,"dsize":0,"dev":0,"ino":1008}]]
```

<a name="zkSnapshotComparer"></a>

### zkSnapshotComparer.sh
SnapshotComparer is a tool that loads and compares two snapshots with configurable threshold and various filters, and outputs information about the delta. 

The delta includes specific znode paths added, updated, deleted comparing one snapshot to another. 

It's useful in use cases that involve snapshot analysis, such as offline data consistency checking, and data trending analysis (e.g. what's growing under which zNode path during when).

This tool only outputs information about permanent nodes, ignoring both sessions and ephemeral nodes.

It provides two tuning parameters to help filter out noise: 
1. `--nodes` Threshold number of children added/removed; 
2. `--bytes` Threshold number of bytes added/removed.

#### Locate Snapshots
Snapshots can be found in [Zookeeper Data Directory](zookeeperAdmin.html#The+Data+Directory) which configured in [conf/zoo.cfg](zookeeperStarted.html#sc_InstallingSingleMode) when set up Zookeeper server. 

#### Supported Snapshot Formats
This tool supports uncompressed snapshot format, and compressed snapshot file formats: `snappy` and `gz`. Snapshots with different formats can be compared using this tool directly without decompression.

#### Running the Tool
Running the tool with no command line argument or an unrecognized argument, it outputs the following help page:

```
usage: java -cp <classPath> org.apache.zookeeper.server.SnapshotComparer
 -b,--bytes <BYTETHRESHOLD>   (Required) The node data delta size threshold, in bytes, for printing the node.
 -d,--debug                   Use debug output.
 -i,--interactive             Enter interactive mode.
 -l,--left <LEFT>             (Required) The left snapshot file.
 -n,--nodes <NODETHRESHOLD>   (Required) The descendant node delta size threshold, in nodes, for printing the node.
 -r,--right <RIGHT>           (Required) The right snapshot file.
```
Example Command:

```
./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1
```

Example Output:
```
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
```

#### Interactive Mode
Use "-i" or "--interactive" to enter interactive mode:
```
./bin/zkSnapshotComparer.sh -l /zookeeper-data/backup/snapshot.d.snappy -r /zookeeper-data/backup/snapshot.44 -b 2 -n 1 -i
```

There are three options to proceed:
```
- Press enter to move to print current depth layer;
- Type a number to jump to and print all nodes at a given depth;
- Enter an ABSOLUTE path to print the immediate subtree of a node. Path must start with '/'.
```

Note: As indicated by the interactive messages, the tool only shows analysis on the result that filtered by tuning parameters bytes threshold and nodes threshold.  

Press enter to print current depth layer:

```
Current depth is 0
Press enter to move to print current depth layer;
...
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 0
Node  found in both trees. Delta: 2903 bytes, 12 descendants
```

Type a number to jump to and print all nodes at a given depth:

(Jump forward)

```
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
```

(Jump back)

```
Current depth is 3
...
Type a number to jump to and print all nodes at a given depth;
...
0
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 0
Node  found in both trees. Delta: 2903 bytes, 12 descendants
```

Out of range depth is handled:

```
Current depth is 1
...
Type a number to jump to and print all nodes at a given depth;
...
10
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Depth must be in range [0, 4]
```

Enter an ABSOLUTE path to print the immediate subtree of a node:

```
Current depth is 3
...
Enter an ABSOLUTE path to print the immediate subtree of a node.
/zk_test
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for node /zk_test
Node /zk_test/gz found in both trees. Delta: 730 bytes, 3 descendants
Node /zk_test/snappy found in both trees. Delta: 2173 bytes, 9 descendants
```

Invalid path is handled:

```
Current depth is 3
...
Enter an ABSOLUTE path to print the immediate subtree of a node.
/non-exist-path
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for node /non-exist-path
Path /non-exist-path is neither found in left tree nor right tree.
```

Invalid input is handled:
```
Current depth is 1
- Press enter to move to print current depth layer;
- Type a number to jump to and print all nodes at a given depth;
- Enter an ABSOLUTE path to print the immediate subtree of a node. Path must start with '/'.
12223999999999999999999999999999999999999
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Input 12223999999999999999999999999999999999999 is not valid. Depth must be in range [0, 4]. Path must be an absolute path which starts with '/'.
```

Exit interactive mode automatically when all layers are compared:

```
Printing analysis for nodes difference larger than 2 bytes or node count difference larger than 1.
Analysis for depth 4
Node /zk_test/gz/12345/abcdef found only in right tree. Descendant size: 9. Descendant count: 0
Node /zk_test/snappy/anotherTest/abc found only in right tree. Descendant size: 1738. Descendant count: 0
Filtered right node /zk_test/snappy/anotherTest/abcd of size 0
Node /zk_test/snappy/test_1/a found only in right tree. Descendant size: 93. Descendant count: 0
Node /zk_test/snappy/test_1/b found only in right tree. Descendant size: 251. Descendant count: 0
Filtered right node /zk_test/snappy/test_1/c of size 0
Node /zk_test/snappy/test_2/xyz found only in right tree. Descendant size: 33. Descendant count: 0
Node /zk_test/snappy/test_2/y found only in right tree. Descendant size: 58. Descendant count: 0
All layers compared.
```

Or use `^c` to exit interactive mode anytime.

<a name="Testing"></a>

## Testing

<a name="jepsen-test"></a>

### Jepsen Test
A framework for distributed systems verification, with fault injection.
Jepsen has been used to verify everything from eventually-consistent commutative databases to linearizable coordination systems to distributed task schedulers.
more details can be found in [jepsen-io](https://github.com/jepsen-io/jepsen)

Running the [Dockerized Jepsen](https://github.com/jepsen-io/jepsen/blob/master/docker/README.md) is the simplest way to use the Jepsen.

Installation:

```bash
git clone git@github.com:jepsen-io/jepsen.git
cd docker
# maybe a long time for the first init.
./up.sh
# docker ps to check one control node and five db nodes are up
docker ps
     CONTAINER ID        IMAGE               COMMAND                 CREATED             STATUS              PORTS                     NAMES
     8265f1d3f89c        docker_control      "/bin/sh -c /init.sh"   9 hours ago         Up 4 hours          0.0.0.0:32769->8080/tcp   jepsen-control
     8a646102da44        docker_n5           "/run.sh"               9 hours ago         Up 3 hours          22/tcp                    jepsen-n5
     385454d7e520        docker_n1           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n1
     a62d6a9d5f8e        docker_n2           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n2
     1485e89d0d9a        docker_n3           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n3
     27ae01e1a0c5        docker_node         "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-node
     53c444b00ebd        docker_n4           "/run.sh"               9 hours ago         Up 9 hours          22/tcp                    jepsen-n4
```

Running & Test

```bash
# Enter into the container:jepsen-control
docker exec -it jepsen-control bash
# Test
cd zookeeper && lein run test --concurrency 10
# See something like the following to assert that ZooKeeper has passed the Jepsen test
INFO [2019-04-01 11:25:23,719] jepsen worker 8 - jepsen.util 8	:ok	:read	2
INFO [2019-04-01 11:25:23,722] jepsen worker 3 - jepsen.util 3	:invoke	:cas	[0 4]
INFO [2019-04-01 11:25:23,760] jepsen worker 3 - jepsen.util 3	:fail	:cas	[0 4]
INFO [2019-04-01 11:25:23,791] jepsen worker 1 - jepsen.util 1	:invoke	:read	nil
INFO [2019-04-01 11:25:23,794] jepsen worker 1 - jepsen.util 1	:ok	:read	2
INFO [2019-04-01 11:25:24,038] jepsen worker 0 - jepsen.util 0	:invoke	:write	4
INFO [2019-04-01 11:25:24,073] jepsen worker 0 - jepsen.util 0	:ok	:write	4
...............................................................................
Everything looks good! ヽ(‘ー`)ノ

```

Reference:
read [this blog](https://aphyr.com/posts/291-call-me-maybe-zookeeper) to learn more about the Jepsen test for the Zookeeper.
