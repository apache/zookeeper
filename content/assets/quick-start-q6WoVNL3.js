import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let c=`## Coordinating Distributed Applications with ZooKeeper

The document is aimed primarily at developers hoping to try it out, and
contains simple installation instructions for a single ZooKeeper server, a
few commands to verify that it is running, and a simple programming
example. Finally, as a convenience, there are a few sections regarding
more complicated installations, for example running replicated
deployments, and optimizing the transaction log. However for the complete
instructions for commercial deployments, please refer to the [ZooKeeper
Administrator's Guide](/admin-ops/administrators-guide).

### Pre-requisites

See [System Requirements](/admin-ops/administrators-guide/deployment#system-requirements) in the Admin guide.

### Download

To get a ZooKeeper distribution, download a recent
[stable](/releases) release from one of the Apache Download
Mirrors.

### Standalone Operation

Setting up a ZooKeeper server in standalone mode is
straightforward. The server is contained in a single JAR file,
so installation consists of creating a configuration.

Once you've downloaded a stable ZooKeeper release unpack
it and cd to the root

To start ZooKeeper you need a configuration file. Here is a sample,
create it in **conf/zoo.cfg**:

\`\`\`
tickTime=2000
dataDir=/var/lib/zookeeper
clientPort=2181
\`\`\`

This file can be called anything, but for the sake of this
discussion call
it **conf/zoo.cfg**. Change the
value of **dataDir** to specify an
existing (empty to start with) directory. Here are the meanings
for each of the fields:

* ***tickTime*** :
  the basic time unit in milliseconds used by ZooKeeper. It is
  used to do heartbeats and the minimum session timeout will be
  twice the tickTime.
* ***dataDir*** :
  the location to store the in-memory database snapshots and,
  unless specified otherwise, the transaction log of updates to the
  database.
* ***clientPort*** :
  the port to listen for client connections

Now that you created the configuration file, you can start
ZooKeeper:

\`\`\`bash
bin/zkServer.sh start
\`\`\`

ZooKeeper logs messages using *logback* — more detail
available in the
[Logging](/admin-ops/administrators-guide/administration#logging)
section of the Administrator's Guide. You will see log messages
coming to the console (default) and/or a log file depending on
the logback configuration.

The steps outlined here run ZooKeeper in standalone mode. There is
no replication, so if ZooKeeper process fails, the service will go down.
This is fine for most development situations, but to run ZooKeeper in
replicated mode, please see [Running Replicated
ZooKeeper](#running-replicated-zookeeper).

### Managing ZooKeeper Storage

For long running production systems ZooKeeper storage must
be managed externally (dataDir and logs). See the section on
[maintenance](/admin-ops/administrators-guide/administration#maintenance) for
more details.

### Connecting to ZooKeeper

<Steps>
  <Step>
    Start \`zkCli.sh\` and connect to your ZooKeeper server. \`bin/zkCli.sh -server
        127.0.0.1:2181\`
  </Step>

  <Step>
    Run 

    \`help\`

     to list commands and verify client connectivity.
  </Step>

  <Step>
    Create, inspect, update, and delete a test znode to validate end-to-end
    operation.
  </Step>
</Steps>

This lets you perform simple, file-like operations.

Once you have connected, you should see something like:

\`\`\`
Connecting to localhost:2181
...
Welcome to ZooKeeper!
JLine support is enabled
[zkshell: 0]
\`\`\`

From the shell, type \`help\` to get a listing of commands that can be executed from the client, as in:

\`\`\`
[zkshell: 0] help
ZooKeeper -server host:port cmd args
addauth scheme auth
close
config [-c] [-w] [-s]
connect host:port
create [-s] [-e] [-c] [-t ttl] path [data] [acl]
delete [-v version] path
deleteall path
delquota [-n|-b] path
get [-s] [-w] path
getAcl [-s] path
getAllChildrenNumber path
getEphemerals path
history
listquota path
ls [-s] [-w] [-R] path
printwatches on|off
quit
reconfig [-s] [-v version] [[-file path] | [-members serverID=host:port1:port2;port3[,...]*]] | [-add serverId=host:port1:port2;port3[,...]]* [-remove serverId[,...]*]
redo cmdno
removewatches path [-c|-d|-a] [-l]
set [-s] [-v version] path data
setAcl [-s] [-v version] [-R] path acl
setquota -n|-b val path
stat [-w] path
sync path
\`\`\`

From here, you can try a few simple commands to get a feel for this simple command line interface. First, start by issuing the list command, as
in \`ls\`, yielding:

\`\`\`
[zkshell: 8] ls /
[zookeeper]
\`\`\`

Next, create a new znode by running \`create /zk_test my_data\`. This creates a new znode and associates the string "my\\_data" with the node.
You should see:

\`\`\`
[zkshell: 9] create /zk_test my_data
Created /zk_test
\`\`\`

Issue another \`ls /\` command to see what the directory looks like:

\`\`\`
[zkshell: 11] ls /
[zookeeper, zk_test]
\`\`\`

Notice that the zk\\_test directory has now been created.

Next, verify that the data was associated with the znode by running the \`get\` command, as in:

\`\`\`
[zkshell: 12] get /zk_test
my_data
cZxid = 5
ctime = Fri Jun 05 13:57:06 PDT 2009
mZxid = 5
mtime = Fri Jun 05 13:57:06 PDT 2009
pZxid = 5
cversion = 0
dataVersion = 0
aclVersion = 0
ephemeralOwner = 0
dataLength = 7
numChildren = 0
\`\`\`

We can change the data associated with zk\\_test by issuing the \`set\` command, as in:

\`\`\`
[zkshell: 14] set /zk_test junk
cZxid = 5
ctime = Fri Jun 05 13:57:06 PDT 2009
mZxid = 6
mtime = Fri Jun 05 14:01:52 PDT 2009
pZxid = 5
cversion = 0
dataVersion = 1
aclVersion = 0
ephemeralOwner = 0
dataLength = 4
numChildren = 0
[zkshell: 15] get /zk_test
junk
cZxid = 5
ctime = Fri Jun 05 13:57:06 PDT 2009
mZxid = 6
mtime = Fri Jun 05 14:01:52 PDT 2009
pZxid = 5
cversion = 0
dataVersion = 1
aclVersion = 0
ephemeralOwner = 0
dataLength = 4
numChildren = 0
\`\`\`

(Notice we did a \`get\` after setting the data and it did, indeed, change.

Finally, let's \`delete\` the node by issuing:

\`\`\`
[zkshell: 16] delete /zk_test
[zkshell: 17] ls /
[zookeeper]
[zkshell: 18]
\`\`\`

That's it for now. To explore more, see the [Zookeeper CLI](/admin-ops/cli).

### Programming to ZooKeeper

ZooKeeper has a Java bindings and C bindings. They are
functionally equivalent. The C bindings exist in two variants: single
threaded and multi-threaded. These differ only in how the messaging loop
is done. For more information, see the [Programming
Examples in the ZooKeeper Programmer's Guide](/developer/java-example#program-design) for
sample code using the different APIs.

### Running Replicated ZooKeeper

Running ZooKeeper in standalone mode is convenient for evaluation,
some development, and testing. But in production, you should run
ZooKeeper in replicated mode. A replicated group of servers in the same
application is called a *quorum*, and in replicated
mode, all servers in the quorum have copies of the same configuration
file.

<Callout type="info">
  For replicated mode, a minimum of three servers are required,
</Callout>

and it is strongly recommended that you have an odd number of
servers. If you only have two servers, then you are in a
situation where if one of them fails, there are not enough
machines to form a majority quorum. Two servers are inherently
**less** stable than a single server, because there are two single
points of failure.

The required
**conf/zoo.cfg**
file for replicated mode is similar to the one used in standalone
mode, but with a few differences. Here is an example:

\`\`\`
tickTime=2000
dataDir=/var/lib/zookeeper
clientPort=2181
initLimit=5
syncLimit=2
server.1=zoo1:2888:3888
server.2=zoo2:2888:3888
server.3=zoo3:2888:3888
\`\`\`

The new entry, **initLimit** is
timeouts ZooKeeper uses to limit the length of time the ZooKeeper
servers in quorum have to connect to a leader. The entry **syncLimit** limits how far out of date a server can
be from a leader.

With both of these timeouts, you specify the unit of time using
**tickTime**. In this example, the timeout
for initLimit is 5 ticks at 2000 milliseconds a tick, or 10
seconds.

The entries of the form *server.X* list the
servers that make up the ZooKeeper service. When the server starts up,
it knows which server it is by looking for the file
*myid* in the data directory. That file has the
contains the server number, in ASCII.

Finally, note the two port numbers after each server
name: " 2888" and "3888". Peers use the former port to connect
to other peers. Such a connection is necessary so that peers
can communicate, for example, to agree upon the order of
updates. More specifically, a ZooKeeper server uses this port
to connect followers to the leader. When a new leader arises, a
follower opens a TCP connection to the leader using this
port. Because the default leader election also uses TCP, we
currently require another port for leader election. This is the
second port in the server entry.

<Callout type="info">
  If you want to test multiple servers on a single machine, specify the
  servername as *localhost* with unique quorum & leader election ports (i.e.
  2888:3888, 2889:3889, 2890:3890 in the example above) for each server.X in
  that server's config file. Of course separate \\_dataDir\\_s and distinct
  \\_clientPort\\_s are also necessary (in the above replicated example, running on
  a single *localhost*, you would still have three config files).
</Callout>

> Please be aware that setting up multiple servers on a single
> machine will not create any redundancy. If something were to
> happen which caused the machine to die, all of the zookeeper
> servers would be offline. Full redundancy requires that each
> server have its own machine. It must be a completely separate
> physical server. Multiple virtual machines on the same physical
> host are still vulnerable to the complete failure of that host.

> If you have multiple network interfaces in your ZooKeeper machines,
> you can also instruct ZooKeeper to bind on all of your interfaces and
> automatically switch to a healthy interface in case of a network failure.
> For details, see the [Configuration Parameters](/admin-ops/administrators-guide/configuration-parameters#cluster-options).

### Other Optimizations

There are a couple of other configuration parameters that can
greatly increase performance:

* To get low latencies on updates it is important to
  have a dedicated transaction log directory. By default
  transaction logs are put in the same directory as the data
  snapshots and *myid* file. The dataLogDir
  parameters indicates a different directory to use for the
  transaction logs.
`,h={title:"Quick Start",description:"This document contains information to get you started quickly with ZooKeeper."},d=[{href:"/admin-ops/administrators-guide"},{href:"/admin-ops/administrators-guide/deployment#system-requirements"},{href:"/releases"},{href:"/admin-ops/administrators-guide/administration#logging"},{href:"#running-replicated-zookeeper"},{href:"/admin-ops/administrators-guide/administration#maintenance"},{href:"/admin-ops/cli"},{href:"/developer/java-example#program-design"},{href:"/admin-ops/administrators-guide/configuration-parameters#cluster-options"}],p={contents:[{heading:"coordinating-distributed-applications-with-zookeeper",content:`The document is aimed primarily at developers hoping to try it out, and
contains simple installation instructions for a single ZooKeeper server, a
few commands to verify that it is running, and a simple programming
example. Finally, as a convenience, there are a few sections regarding
more complicated installations, for example running replicated
deployments, and optimizing the transaction log. However for the complete
instructions for commercial deployments, please refer to the ZooKeeper
Administrator's Guide.`},{heading:"pre-requisites",content:"See System Requirements in the Admin guide."},{heading:"download",content:`To get a ZooKeeper distribution, download a recent
stable release from one of the Apache Download
Mirrors.`},{heading:"standalone-operation",content:`Setting up a ZooKeeper server in standalone mode is
straightforward. The server is contained in a single JAR file,
so installation consists of creating a configuration.`},{heading:"standalone-operation",content:`Once you've downloaded a stable ZooKeeper release unpack
it and cd to the root`},{heading:"standalone-operation",content:`To start ZooKeeper you need a configuration file. Here is a sample,
create it in conf/zoo.cfg:`},{heading:"standalone-operation",content:`This file can be called anything, but for the sake of this
discussion call
it conf/zoo.cfg. Change the
value of dataDir to specify an
existing (empty to start with) directory. Here are the meanings
for each of the fields:`},{heading:"standalone-operation",content:`tickTime :
the basic time unit in milliseconds used by ZooKeeper. It is
used to do heartbeats and the minimum session timeout will be
twice the tickTime.`},{heading:"standalone-operation",content:`dataDir :
the location to store the in-memory database snapshots and,
unless specified otherwise, the transaction log of updates to the
database.`},{heading:"standalone-operation",content:`clientPort :
the port to listen for client connections`},{heading:"standalone-operation",content:`Now that you created the configuration file, you can start
ZooKeeper:`},{heading:"standalone-operation",content:`ZooKeeper logs messages using logback — more detail
available in the
Logging
section of the Administrator's Guide. You will see log messages
coming to the console (default) and/or a log file depending on
the logback configuration.`},{heading:"standalone-operation",content:`The steps outlined here run ZooKeeper in standalone mode. There is
no replication, so if ZooKeeper process fails, the service will go down.
This is fine for most development situations, but to run ZooKeeper in
replicated mode, please see Running Replicated
ZooKeeper.`},{heading:"managing-zookeeper-storage",content:`For long running production systems ZooKeeper storage must
be managed externally (dataDir and logs). See the section on
maintenance for
more details.`},{heading:"connecting-to-zookeeper",content:`Start zkCli.sh and connect to your ZooKeeper server. bin/zkCli.sh -server
    127.0.0.1:2181`},{heading:"connecting-to-zookeeper",content:`Create, inspect, update, and delete a test znode to validate end-to-end
operation.`},{heading:"connecting-to-zookeeper",content:"This lets you perform simple, file-like operations."},{heading:"connecting-to-zookeeper",content:"Once you have connected, you should see something like:"},{heading:"connecting-to-zookeeper",content:"From the shell, type help to get a listing of commands that can be executed from the client, as in:"},{heading:"connecting-to-zookeeper",content:`From here, you can try a few simple commands to get a feel for this simple command line interface. First, start by issuing the list command, as
in ls, yielding:`},{heading:"connecting-to-zookeeper",content:`Next, create a new znode by running create /zk_test my_data. This creates a new znode and associates the string "my_data" with the node.
You should see:`},{heading:"connecting-to-zookeeper",content:"Issue another ls / command to see what the directory looks like:"},{heading:"connecting-to-zookeeper",content:"Notice that the zk_test directory has now been created."},{heading:"connecting-to-zookeeper",content:"Next, verify that the data was associated with the znode by running the get command, as in:"},{heading:"connecting-to-zookeeper",content:"We can change the data associated with zk_test by issuing the set command, as in:"},{heading:"connecting-to-zookeeper",content:"(Notice we did a get after setting the data and it did, indeed, change."},{heading:"connecting-to-zookeeper",content:"Finally, let's delete the node by issuing:"},{heading:"connecting-to-zookeeper",content:"That's it for now. To explore more, see the Zookeeper CLI."},{heading:"programming-to-zookeeper",content:`ZooKeeper has a Java bindings and C bindings. They are
functionally equivalent. The C bindings exist in two variants: single
threaded and multi-threaded. These differ only in how the messaging loop
is done. For more information, see the Programming
Examples in the ZooKeeper Programmer's Guide for
sample code using the different APIs.`},{heading:"running-replicated-zookeeper",content:`Running ZooKeeper in standalone mode is convenient for evaluation,
some development, and testing. But in production, you should run
ZooKeeper in replicated mode. A replicated group of servers in the same
application is called a quorum, and in replicated
mode, all servers in the quorum have copies of the same configuration
file.`},{heading:"running-replicated-zookeeper",content:"type: info"},{heading:"running-replicated-zookeeper",content:"For replicated mode, a minimum of three servers are required,"},{heading:"running-replicated-zookeeper",content:`and it is strongly recommended that you have an odd number of
servers. If you only have two servers, then you are in a
situation where if one of them fails, there are not enough
machines to form a majority quorum. Two servers are inherently
less stable than a single server, because there are two single
points of failure.`},{heading:"running-replicated-zookeeper",content:`The required
conf/zoo.cfg
file for replicated mode is similar to the one used in standalone
mode, but with a few differences. Here is an example:`},{heading:"running-replicated-zookeeper",content:`The new entry, initLimit is
timeouts ZooKeeper uses to limit the length of time the ZooKeeper
servers in quorum have to connect to a leader. The entry syncLimit limits how far out of date a server can
be from a leader.`},{heading:"running-replicated-zookeeper",content:`With both of these timeouts, you specify the unit of time using
tickTime. In this example, the timeout
for initLimit is 5 ticks at 2000 milliseconds a tick, or 10
seconds.`},{heading:"running-replicated-zookeeper",content:`The entries of the form server.X list the
servers that make up the ZooKeeper service. When the server starts up,
it knows which server it is by looking for the file
myid in the data directory. That file has the
contains the server number, in ASCII.`},{heading:"running-replicated-zookeeper",content:`Finally, note the two port numbers after each server
name: " 2888" and "3888". Peers use the former port to connect
to other peers. Such a connection is necessary so that peers
can communicate, for example, to agree upon the order of
updates. More specifically, a ZooKeeper server uses this port
to connect followers to the leader. When a new leader arises, a
follower opens a TCP connection to the leader using this
port. Because the default leader election also uses TCP, we
currently require another port for leader election. This is the
second port in the server entry.`},{heading:"running-replicated-zookeeper",content:"type: info"},{heading:"running-replicated-zookeeper",content:`If you want to test multiple servers on a single machine, specify the
servername as localhost with unique quorum & leader election ports (i.e.
2888:3888, 2889:3889, 2890:3890 in the example above) for each server.X in
that server's config file. Of course separate _dataDir_s and distinct
_clientPort_s are also necessary (in the above replicated example, running on
a single localhost, you would still have three config files).`},{heading:"running-replicated-zookeeper",content:`Please be aware that setting up multiple servers on a single
machine will not create any redundancy. If something were to
happen which caused the machine to die, all of the zookeeper
servers would be offline. Full redundancy requires that each
server have its own machine. It must be a completely separate
physical server. Multiple virtual machines on the same physical
host are still vulnerable to the complete failure of that host.`},{heading:"running-replicated-zookeeper",content:`If you have multiple network interfaces in your ZooKeeper machines,
you can also instruct ZooKeeper to bind on all of your interfaces and
automatically switch to a healthy interface in case of a network failure.
For details, see the Configuration Parameters.`},{heading:"other-optimizations",content:`There are a couple of other configuration parameters that can
greatly increase performance:`},{heading:"other-optimizations",content:`To get low latencies on updates it is important to
have a dedicated transaction log directory. By default
transaction logs are put in the same directory as the data
snapshots and myid file. The dataLogDir
parameters indicates a different directory to use for the
transaction logs.`}],headings:[{id:"coordinating-distributed-applications-with-zookeeper",content:"Coordinating Distributed Applications with ZooKeeper"},{id:"pre-requisites",content:"Pre-requisites"},{id:"download",content:"Download"},{id:"standalone-operation",content:"Standalone Operation"},{id:"managing-zookeeper-storage",content:"Managing ZooKeeper Storage"},{id:"connecting-to-zookeeper",content:"Connecting to ZooKeeper"},{id:"programming-to-zookeeper",content:"Programming to ZooKeeper"},{id:"running-replicated-zookeeper",content:"Running Replicated ZooKeeper"},{id:"other-optimizations",content:"Other Optimizations"}]};const m=[{depth:2,url:"#coordinating-distributed-applications-with-zookeeper",title:e.jsx(e.Fragment,{children:"Coordinating Distributed Applications with ZooKeeper"})},{depth:3,url:"#pre-requisites",title:e.jsx(e.Fragment,{children:"Pre-requisites"})},{depth:3,url:"#download",title:e.jsx(e.Fragment,{children:"Download"})},{depth:3,url:"#standalone-operation",title:e.jsx(e.Fragment,{children:"Standalone Operation"})},{depth:3,url:"#managing-zookeeper-storage",title:e.jsx(e.Fragment,{children:"Managing ZooKeeper Storage"})},{depth:3,url:"#connecting-to-zookeeper",title:e.jsx(e.Fragment,{children:"Connecting to ZooKeeper"})},{depth:3,url:"#programming-to-zookeeper",title:e.jsx(e.Fragment,{children:"Programming to ZooKeeper"})},{depth:3,url:"#running-replicated-zookeeper",title:e.jsx(e.Fragment,{children:"Running Replicated ZooKeeper"})},{depth:3,url:"#other-optimizations",title:e.jsx(e.Fragment,{children:"Other Optimizations"})}];function r(i){const n={a:"a",blockquote:"blockquote",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...i.components},{Callout:s,Step:t,Steps:o}=n;return s||a("Callout"),t||a("Step"),o||a("Steps"),e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"coordinating-distributed-applications-with-zookeeper",children:"Coordinating Distributed Applications with ZooKeeper"}),`
`,e.jsxs(n.p,{children:[`The document is aimed primarily at developers hoping to try it out, and
contains simple installation instructions for a single ZooKeeper server, a
few commands to verify that it is running, and a simple programming
example. Finally, as a convenience, there are a few sections regarding
more complicated installations, for example running replicated
deployments, and optimizing the transaction log. However for the complete
instructions for commercial deployments, please refer to the `,e.jsx(n.a,{href:"/admin-ops/administrators-guide",children:`ZooKeeper
Administrator's Guide`}),"."]}),`
`,e.jsx(n.h3,{id:"pre-requisites",children:"Pre-requisites"}),`
`,e.jsxs(n.p,{children:["See ",e.jsx(n.a,{href:"/admin-ops/administrators-guide/deployment#system-requirements",children:"System Requirements"})," in the Admin guide."]}),`
`,e.jsx(n.h3,{id:"download",children:"Download"}),`
`,e.jsxs(n.p,{children:[`To get a ZooKeeper distribution, download a recent
`,e.jsx(n.a,{href:"/releases",children:"stable"}),` release from one of the Apache Download
Mirrors.`]}),`
`,e.jsx(n.h3,{id:"standalone-operation",children:"Standalone Operation"}),`
`,e.jsx(n.p,{children:`Setting up a ZooKeeper server in standalone mode is
straightforward. The server is contained in a single JAR file,
so installation consists of creating a configuration.`}),`
`,e.jsx(n.p,{children:`Once you've downloaded a stable ZooKeeper release unpack
it and cd to the root`}),`
`,e.jsxs(n.p,{children:[`To start ZooKeeper you need a configuration file. Here is a sample,
create it in `,e.jsx(n.strong,{children:"conf/zoo.cfg"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/var/lib/zookeeper"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"clientPort=2181"})})]})})}),`
`,e.jsxs(n.p,{children:[`This file can be called anything, but for the sake of this
discussion call
it `,e.jsx(n.strong,{children:"conf/zoo.cfg"}),`. Change the
value of `,e.jsx(n.strong,{children:"dataDir"}),` to specify an
existing (empty to start with) directory. Here are the meanings
for each of the fields:`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:e.jsx(n.em,{children:"tickTime"})}),` :
the basic time unit in milliseconds used by ZooKeeper. It is
used to do heartbeats and the minimum session timeout will be
twice the tickTime.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:e.jsx(n.em,{children:"dataDir"})}),` :
the location to store the in-memory database snapshots and,
unless specified otherwise, the transaction log of updates to the
database.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:e.jsx(n.em,{children:"clientPort"})}),` :
the port to listen for client connections`]}),`
`]}),`
`,e.jsx(n.p,{children:`Now that you created the configuration file, you can start
ZooKeeper:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"bin/zkServer.sh"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" start"})]})})})}),`
`,e.jsxs(n.p,{children:["ZooKeeper logs messages using ",e.jsx(n.em,{children:"logback"}),` — more detail
available in the
`,e.jsx(n.a,{href:"/admin-ops/administrators-guide/administration#logging",children:"Logging"}),`
section of the Administrator's Guide. You will see log messages
coming to the console (default) and/or a log file depending on
the logback configuration.`]}),`
`,e.jsxs(n.p,{children:[`The steps outlined here run ZooKeeper in standalone mode. There is
no replication, so if ZooKeeper process fails, the service will go down.
This is fine for most development situations, but to run ZooKeeper in
replicated mode, please see `,e.jsx(n.a,{href:"#running-replicated-zookeeper",children:`Running Replicated
ZooKeeper`}),"."]}),`
`,e.jsx(n.h3,{id:"managing-zookeeper-storage",children:"Managing ZooKeeper Storage"}),`
`,e.jsxs(n.p,{children:[`For long running production systems ZooKeeper storage must
be managed externally (dataDir and logs). See the section on
`,e.jsx(n.a,{href:"/admin-ops/administrators-guide/administration#maintenance",children:"maintenance"}),` for
more details.`]}),`
`,e.jsx(n.h3,{id:"connecting-to-zookeeper",children:"Connecting to ZooKeeper"}),`
`,e.jsxs(o,{children:[e.jsx(t,{children:e.jsxs(n.p,{children:["Start ",e.jsx(n.code,{children:"zkCli.sh"})," and connect to your ZooKeeper server. ",e.jsx(n.code,{children:"bin/zkCli.sh -server     127.0.0.1:2181"})]})}),e.jsxs(t,{children:["Run ",e.jsx(n.code,{children:"help"})," to list commands and verify client connectivity."]}),e.jsx(t,{children:e.jsx(n.p,{children:`Create, inspect, update, and delete a test znode to validate end-to-end
operation.`})})]}),`
`,e.jsx(n.p,{children:"This lets you perform simple, file-like operations."}),`
`,e.jsx(n.p,{children:"Once you have connected, you should see something like:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Connecting to localhost:2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"..."})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Welcome to ZooKeeper!"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"JLine support is enabled"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 0]"})})]})})}),`
`,e.jsxs(n.p,{children:["From the shell, type ",e.jsx(n.code,{children:"help"})," to get a listing of commands that can be executed from the client, as in:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 0] help"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ZooKeeper -server host:port cmd args"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"addauth scheme auth"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"close"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"config [-c] [-w] [-s]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"connect host:port"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"create [-s] [-e] [-c] [-t ttl] path [data] [acl]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"delete [-v version] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"deleteall path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"delquota [-n|-b] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"get [-s] [-w] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getAcl [-s] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getAllChildrenNumber path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getEphemerals path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"history"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"listquota path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ls [-s] [-w] [-R] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"printwatches on|off"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"quit"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"reconfig [-s] [-v version] [[-file path] | [-members serverID=host:port1:port2;port3[,...]*]] | [-add serverId=host:port1:port2;port3[,...]]* [-remove serverId[,...]*]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"redo cmdno"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"removewatches path [-c|-d|-a] [-l]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"set [-s] [-v version] path data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"setAcl [-s] [-v version] [-R] path acl"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"setquota -n|-b val path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"stat [-w] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sync path"})})]})})}),`
`,e.jsxs(n.p,{children:[`From here, you can try a few simple commands to get a feel for this simple command line interface. First, start by issuing the list command, as
in `,e.jsx(n.code,{children:"ls"}),", yielding:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 8] ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zookeeper]"})})]})})}),`
`,e.jsxs(n.p,{children:["Next, create a new znode by running ",e.jsx(n.code,{children:"create /zk_test my_data"}),`. This creates a new znode and associates the string "my_data" with the node.
You should see:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 9] create /zk_test my_data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /zk_test"})})]})})}),`
`,e.jsxs(n.p,{children:["Issue another ",e.jsx(n.code,{children:"ls /"})," command to see what the directory looks like:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 11] ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zookeeper, zk_test]"})})]})})}),`
`,e.jsx(n.p,{children:"Notice that the zk_test directory has now been created."}),`
`,e.jsxs(n.p,{children:["Next, verify that the data was associated with the znode by running the ",e.jsx(n.code,{children:"get"})," command, as in:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 12] get /zk_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"my_data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Fri Jun 05 13:57:06 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Fri Jun 05 13:57:06 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 7"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 0"})})]})})}),`
`,e.jsxs(n.p,{children:["We can change the data associated with zk_test by issuing the ",e.jsx(n.code,{children:"set"})," command, as in:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 14] set /zk_test junk"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Fri Jun 05 13:57:06 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 6"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Fri Jun 05 14:01:52 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 4"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 15] get /zk_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"junk"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Fri Jun 05 13:57:06 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 6"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Fri Jun 05 14:01:52 PDT 2009"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 4"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 0"})})]})})}),`
`,e.jsxs(n.p,{children:["(Notice we did a ",e.jsx(n.code,{children:"get"})," after setting the data and it did, indeed, change."]}),`
`,e.jsxs(n.p,{children:["Finally, let's ",e.jsx(n.code,{children:"delete"})," the node by issuing:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 16] delete /zk_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 17] ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zookeeper]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 18]"})})]})})}),`
`,e.jsxs(n.p,{children:["That's it for now. To explore more, see the ",e.jsx(n.a,{href:"/admin-ops/cli",children:"Zookeeper CLI"}),"."]}),`
`,e.jsx(n.h3,{id:"programming-to-zookeeper",children:"Programming to ZooKeeper"}),`
`,e.jsxs(n.p,{children:[`ZooKeeper has a Java bindings and C bindings. They are
functionally equivalent. The C bindings exist in two variants: single
threaded and multi-threaded. These differ only in how the messaging loop
is done. For more information, see the `,e.jsx(n.a,{href:"/developer/java-example#program-design",children:`Programming
Examples in the ZooKeeper Programmer's Guide`}),` for
sample code using the different APIs.`]}),`
`,e.jsx(n.h3,{id:"running-replicated-zookeeper",children:"Running Replicated ZooKeeper"}),`
`,e.jsxs(n.p,{children:[`Running ZooKeeper in standalone mode is convenient for evaluation,
some development, and testing. But in production, you should run
ZooKeeper in replicated mode. A replicated group of servers in the same
application is called a `,e.jsx(n.em,{children:"quorum"}),`, and in replicated
mode, all servers in the quorum have copies of the same configuration
file.`]}),`
`,e.jsx(s,{type:"info",children:e.jsx(n.p,{children:"For replicated mode, a minimum of three servers are required,"})}),`
`,e.jsxs(n.p,{children:[`and it is strongly recommended that you have an odd number of
servers. If you only have two servers, then you are in a
situation where if one of them fails, there are not enough
machines to form a majority quorum. Two servers are inherently
`,e.jsx(n.strong,{children:"less"}),` stable than a single server, because there are two single
points of failure.`]}),`
`,e.jsxs(n.p,{children:[`The required
`,e.jsx(n.strong,{children:"conf/zoo.cfg"}),`
file for replicated mode is similar to the one used in standalone
mode, but with a few differences. Here is an example:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/var/lib/zookeeper"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"clientPort=2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=zoo1:2888:3888"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2:2888:3888"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=zoo3:2888:3888"})})]})})}),`
`,e.jsxs(n.p,{children:["The new entry, ",e.jsx(n.strong,{children:"initLimit"}),` is
timeouts ZooKeeper uses to limit the length of time the ZooKeeper
servers in quorum have to connect to a leader. The entry `,e.jsx(n.strong,{children:"syncLimit"}),` limits how far out of date a server can
be from a leader.`]}),`
`,e.jsxs(n.p,{children:[`With both of these timeouts, you specify the unit of time using
`,e.jsx(n.strong,{children:"tickTime"}),`. In this example, the timeout
for initLimit is 5 ticks at 2000 milliseconds a tick, or 10
seconds.`]}),`
`,e.jsxs(n.p,{children:["The entries of the form ",e.jsx(n.em,{children:"server.X"}),` list the
servers that make up the ZooKeeper service. When the server starts up,
it knows which server it is by looking for the file
`,e.jsx(n.em,{children:"myid"}),` in the data directory. That file has the
contains the server number, in ASCII.`]}),`
`,e.jsx(n.p,{children:`Finally, note the two port numbers after each server
name: " 2888" and "3888". Peers use the former port to connect
to other peers. Such a connection is necessary so that peers
can communicate, for example, to agree upon the order of
updates. More specifically, a ZooKeeper server uses this port
to connect followers to the leader. When a new leader arises, a
follower opens a TCP connection to the leader using this
port. Because the default leader election also uses TCP, we
currently require another port for leader election. This is the
second port in the server entry.`}),`
`,e.jsx(s,{type:"info",children:e.jsxs(n.p,{children:[`If you want to test multiple servers on a single machine, specify the
servername as `,e.jsx(n.em,{children:"localhost"}),` with unique quorum & leader election ports (i.e.
2888:3888, 2889:3889, 2890:3890 in the example above) for each server.X in
that server's config file. Of course separate _dataDir_s and distinct
_clientPort_s are also necessary (in the above replicated example, running on
a single `,e.jsx(n.em,{children:"localhost"}),", you would still have three config files)."]})}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsx(n.p,{children:`Please be aware that setting up multiple servers on a single
machine will not create any redundancy. If something were to
happen which caused the machine to die, all of the zookeeper
servers would be offline. Full redundancy requires that each
server have its own machine. It must be a completely separate
physical server. Multiple virtual machines on the same physical
host are still vulnerable to the complete failure of that host.`}),`
`]}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsxs(n.p,{children:[`If you have multiple network interfaces in your ZooKeeper machines,
you can also instruct ZooKeeper to bind on all of your interfaces and
automatically switch to a healthy interface in case of a network failure.
For details, see the `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters#cluster-options",children:"Configuration Parameters"}),"."]}),`
`]}),`
`,e.jsx(n.h3,{id:"other-optimizations",children:"Other Optimizations"}),`
`,e.jsx(n.p,{children:`There are a couple of other configuration parameters that can
greatly increase performance:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`To get low latencies on updates it is important to
have a dedicated transaction log directory. By default
transaction logs are put in the same directory as the data
snapshots and `,e.jsx(n.em,{children:"myid"}),` file. The dataLogDir
parameters indicates a different directory to use for the
transaction logs.`]}),`
`]})]})}function g(i={}){const{wrapper:n}=i.components||{};return n?e.jsx(n,{...i,children:e.jsx(r,{...i})}):r(i)}function a(i,n){throw new Error("Expected component `"+i+"` to be defined: you likely forgot to import, pass, or provide it.")}export{c as _markdown,g as default,d as extractedReferences,h as frontmatter,p as structuredData,m as toc};
