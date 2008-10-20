Welcome to ZooKeeper!

-------------------
Documentation

See docs/index.html

New Users -- see the overview, getting started, and programmer guides
Existing Users -- if you are upgrading to 3.0 review the releasenotes and upgrade guide

-------------------
Quick Start

Run "ant" command in this directory to build the server/client. "zookeeper-dev.jar" will be output to this directory on a successful build.

-------------------
Starting the server:

1) in the conf directory make a copy of zoo_sample.cfg (ie zoo.cfg) and edit as necessary. Default values will support a "standalone" instance.

2) start the server with the following comand line:

java -cp conf:zookeeper-dev.jar:src/java/lib/log4j-1.2.15.jar org.apache.zookeeper.server.quorum.QuorumPeerMain conf/zoo.cfg

Notice that the server is picking up the log4j.properties file from the conf directory (default).


-----------------------
Starting a client shell

1) run the following command

java -cp conf:zookeeper-dev.jar:src/java/lib/log4j-1.2.15.jar org.apache.zookeeper.ZooKeeperMain <server>:<port>

where server and port correspond to the ZooKeeper configuration.

Notice that the client is picking up the log4j.properties file from the conf directory (default).
