To run the system test we need to create processing containers that we can
spawn tasks, called Instances, in. (how is that for a dangling preposition!?!)
Start up InstanceContainers first. Then run the system test. The system test
finds the InstanceContainers and directs them through ZooKeeper, so you are
going to need an instance of ZooKeeper running that they can all access.
The easiest way to do all of this is to use the zookeeper fat jar.

Steps to run system test
------------------------
1) transfer the fatjar from the release directory to all systems
   participating in the test. fatjar is in contrib/fatjar directory.

   (developers can generate by running "ant jar compile-test"
   targets in trunk, then compiling using "ant jar" in src/contrib/jarjar)

2) run a zookeeper standalone instance (cluster is ok too)

e.g. java -jar zookeeper-<version>-fatjar.jar server <zoo.cfg>

Note: you must provide zoo.cfg, see sample in conf directory

3) on each host start the system test container

e.g. java -jar zookeeper-<version>-fatjar.jar ic <name> <zkHostPort> /sysTest

name : name of the test container - must be unique 
  typically it's a good idea to name after the host to aid debugging

zkHostPort : the host:port of the server from step 2)

4) initiate the system test using the fatjar:

java -jar build/contrib/fatjar/zookeeper-dev-fatjar.jar systest org.apache.zookeeper.test.system.SimpleSysTest

by default it will access the zk server started in 2) on localhost:2181 

or you can specify a remote host:port using
  -DsysTest.zkHostPort=<host>:<port>,<host>:<port>,...

java -DsysTest.zkHostPort=hostA:2181  -jar build/contrib/fatjar/zookeeper-dev-fatjar.jar systest org.apache.zookeeper.test.system.SimpleSysTest

where hostA is running the zk server started in step 2) above
