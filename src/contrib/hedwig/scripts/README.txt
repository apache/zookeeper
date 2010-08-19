To start up a Hedwig server, you can make use of the scripts in this directory.
The hw.bash script is used to setup a Hedwig region cluster on remote boxes.
It contains methods that allow the user to start up a ZooKeeper server
(currently only a single quorum) and also any number of Bookkeeper servers.
It can also startup any number of Hedwig server hubs that point to this
ZooKeeper/Bookkeeper setup.

To simplify and generalize things, the hwServer.sh script is used to start
up a single Hedwig server hub on the current local machine. It assumes that
the ZooKeeper and Bookkeeper servers are setup and running already. The order
of operations prior to starting up the Hedwig server hub(s) is:

1. Startup a quorum of ZooKeeper servers (could be a single one).
2. Using a ZooKeeper client to connect to the servers, create the following 
ZK nodes to be used by Bookkeeper as directory path nodes.
	/ledgers and /ledgers/available
3. Startup Bookkeeper servers pointing them to this ZooKeeper quorum.
4. For each machine you want to run a Hedwig server hub on, the Hedwig code
needs to be there. Compile/build it from the top level with the command: 
	mvn install -Dmaven.test.skip=true
In the server/target directory, this creates the following fat jar that
contains all dependencies the Hedwig server needs to run:
	server-1.0-SNAPSHOT-jar-with-dependencies.jar
5. Define your Hedwig server configuration before you start the server. The
default location and file for this is: 
	conf/hw_server.conf
However, you can override this by setting the appropriate environment
variables or passing in your config file directly when invoking the
hwServer.sh script. A sample config file is available for you at:
	conf/hw_server_sample.conf
The important config parameter is the "zk_host" one which will point the
Hedwig server hub to the ZooKeeper quorum which manages and coordinates all of
the hubs.
6. Run the hwServer.sh script to start up the server:
	scripts/hwServer.sh start
OR	scripts/hwServer.sh start <path to your Hedwig server config file>
7. Stop or restart the Hedwig server hub using the following commands:
	scripts/hwServer.sh stop
	scripts/hwServer.sh restart
