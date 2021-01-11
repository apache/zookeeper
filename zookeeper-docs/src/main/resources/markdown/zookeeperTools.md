<!--
Copyright 2002-2021 The Apache Software Foundation

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
