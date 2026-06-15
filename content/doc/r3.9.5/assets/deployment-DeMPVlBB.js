import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let h=`This section contains information about deploying Zookeeper and
covers these topics:

* [System Requirements](#system-requirements)
* [Clustered (Multi-Server) Setup](#clustered-multi-server-setup)
* [Single Server and Developer Setup](#single-server-and-developer-setup)

The first two sections assume you are interested in installing
ZooKeeper in a production environment such as a datacenter. The final
section covers situations in which you are setting up ZooKeeper on a
limited basis - for evaluation, testing, or development - but not in a
production environment.

## System Requirements

### Supported Platforms

ZooKeeper consists of multiple components. Some components are
supported broadly, and other components are supported only on a smaller
set of platforms.

* **Client** is the Java client
  library, used by applications to connect to a ZooKeeper ensemble.
* **Server** is the Java server
  that runs on the ZooKeeper ensemble nodes.
* **Native Client** is a client
  implemented in C, similar to the Java client, used by applications
  to connect to a ZooKeeper ensemble.
* **Contrib** refers to multiple
  optional add-on components.

The following matrix describes the level of support committed for
running each component on different operating system platforms.

### Support Matrix

| Operating System | Client                     | Server                     | Native Client              | Contrib                    |
| ---------------- | -------------------------- | -------------------------- | -------------------------- | -------------------------- |
| GNU/Linux        | Development and Production | Development and Production | Development and Production | Development and Production |
| Solaris          | Development and Production | Development and Production | Not Supported              | Not Supported              |
| FreeBSD          | Development and Production | Development and Production | Not Supported              | Not Supported              |
| Windows          | Development and Production | Development and Production | Not Supported              | Not Supported              |
| Mac OS X         | Development Only           | Development Only           | Not Supported              | Not Supported              |

For any operating system not explicitly mentioned as supported in
the matrix, components may or may not work. The ZooKeeper community
will fix obvious bugs that are reported for other platforms, but there
is no full support.

### Required Software

ZooKeeper runs in Java, release 1.8 or greater
(JDK 8 LTS, JDK 11 LTS, JDK 12 - Java 9 and 10 are not supported).
It runs as an *ensemble* of ZooKeeper servers. Three
ZooKeeper servers is the minimum recommended size for an
ensemble, and we also recommend that they run on separate
machines. At Yahoo!, ZooKeeper is usually deployed on
dedicated RHEL boxes, with dual-core processors, 2GB of RAM,
and 80GB IDE hard drives.

## Clustered (Multi-Server) Setup

For reliable ZooKeeper service, you should deploy ZooKeeper in a
cluster known as an *ensemble*. As long as a majority
of the ensemble are up, the service will be available. Because Zookeeper
requires a majority, it is best to use an
odd number of machines. For example, with four machines ZooKeeper can
only handle the failure of a single machine; if two machines fail, the
remaining two machines do not constitute a majority. However, with five
machines ZooKeeper can handle the failure of two machines.

<Callout type="info">
  As mentioned in the [ZooKeeper Getting Started
  Guide](/overview/quick-start), a minimum of three servers are required
  for a fault tolerant clustered setup, and it is strongly recommended that you
  have an odd number of servers.

  Usually three servers is more than enough for a production
  install, but for maximum reliability during maintenance, you may
  wish to install five servers. With three servers, if you perform
  maintenance on one of them, you are vulnerable to a failure on one
  of the other two servers during that maintenance. If you have five
  of them running, you can take one down for maintenance, and know
  that you're still OK if one of the other four suddenly fails.

  Your redundancy considerations should include all aspects of
  your environment. If you have three ZooKeeper servers, but their
  network cables are all plugged into the same network switch, then
  the failure of that switch will take down your entire ensemble.
</Callout>

Here are the steps to set a server that will be part of an
ensemble. These steps should be performed on every host in the
ensemble:

<Steps>
  <Step>
    Install the Java JDK. You can use the native packaging system for your system, or download
    the JDK from: [http://java.sun.com/javase/downloads/index.jsp](http://java.sun.com/javase/downloads/index.jsp)
  </Step>

  <Step>
    Set the Java heap size. This is very important to avoid swapping, which will seriously degrade
    ZooKeeper performance. To determine the correct value, use load tests, and make sure you are
    well below the usage limit that would cause you to swap. Be conservative — use a maximum heap
    size of 3GB for a 4GB machine.
  </Step>

  <Step>
    Install the ZooKeeper Server Package. It can be downloaded
    from: [/releases](/releases)
  </Step>

  <Step>
    Create a configuration file. This file can be called anything. Use the following settings as a
    starting point:

    \`\`\`
    tickTime=2000
    dataDir=/var/lib/zookeeper/
    clientPort=2181
    initLimit=5
    syncLimit=2
    server.1=zoo1:2888:3888
    server.2=zoo2:2888:3888
    server.3=zoo3:2888:3888
    \`\`\`

    You can find the meanings of these and other configuration settings in the section
    [Configuration Parameters](/admin-ops/administrators-guide/configuration-parameters). Every machine that is part of the ZooKeeper
    ensemble should know about every other machine in the ensemble. You accomplish this with the
    series of lines of the form **server.id=host:port:port**. (The parameters **host** and **port**
    are straightforward; for each server you need to specify first a Quorum port then a dedicated
    port for ZooKeeper leader election). Since ZooKeeper 3.6.0 you can also
    specify multiple addresses for each ZooKeeper server instance (this can
    increase availability when multiple physical network interfaces can be used parallel in the
    cluster). You attribute the server id to each machine by creating a file named *myid*, one for
    each server, which resides in that server's data directory, as specified by the configuration
    file parameter **dataDir**.
  </Step>

  <Step>
    The *myid* file consists of a single line containing only the text of that machine's id. So
    *myid* of server 1 would contain the text "1" and nothing else. The id must be unique within
    the ensemble and should have a value between 1 and 255. **IMPORTANT:** if you enable extended
    features such as TTL Nodes (see below) the id must be between 1 and 254 due to internal
    limitations.
  </Step>

  <Step>
    Create an initialization marker file *initialize* in the same directory as *myid*. This file
    indicates that an empty data directory is expected. When present, an empty database is created
    and the marker file deleted. When not present, an empty data directory will mean this peer will
    not have voting rights and it will not populate the data directory until it communicates with
    an active leader. Intended use is to only create this file when bringing up a new ensemble.
  </Step>

  <Step>
    If your configuration file is set up, you can start a ZooKeeper server:

    \`\`\`
    $ java -cp zookeeper.jar:lib/*:conf org.apache.zookeeper.server.quorum.QuorumPeerMain zoo.conf
    \`\`\`

    QuorumPeerMain starts a ZooKeeper server; [JMX](http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/)
    management beans are also registered which allows management through a JMX management console.
    The [ZooKeeper JMX document](/admin-ops/jmx) contains details on managing ZooKeeper with JMX.
    See the script *bin/zkServer.sh*, which is included in the release, for an example of starting
    server instances.
  </Step>

  <Step>
    Test your deployment by connecting to the hosts. In Java, you can run the following command to
    execute simple operations:

    \`\`\`
    $ bin/zkCli.sh -server 127.0.0.1:2181
    \`\`\`
  </Step>
</Steps>

## Single Server and Developer Setup

If you want to set up ZooKeeper for development purposes, you will
probably want to set up a single server instance of ZooKeeper, and then
install either the Java or C client-side libraries and bindings on your
development machine.

The steps to setting up a single server instance are the similar
to the above, except the configuration file is simpler. You can find the
complete instructions in the [Installing and
Running ZooKeeper in Single Server Mode](/overview/quick-start#standalone-operation) section of the [ZooKeeper Getting Started
Guide](/overview/quick-start).

For information on installing the client side libraries, refer to
the [Bindings](/developer/programmers-guide/bindings)
section of the [ZooKeeper
Programmer's Guide](/developer/programmers-guide).

## Designing a ZooKeeper Deployment

The reliability of ZooKeeper rests on two basic assumptions.

1. Only a minority of servers in a deployment
   will fail. *Failure* in this context
   means a machine crash, or some error in the network that
   partitions a server off from the majority.
2. Deployed machines operate correctly. To
   operate correctly means to execute code correctly, to have
   clocks that work properly, and to have storage and network
   components that perform consistently.

The sections below contain considerations for ZooKeeper
administrators to maximize the probability for these assumptions
to hold true. Some of these are cross-machines considerations,
and others are things you should consider for each and every
machine in your deployment.

### Cross Machine Requirements

For the ZooKeeper service to be active, there must be a
majority of non-failing machines that can communicate with
each other. For a ZooKeeper ensemble with N servers,
if N is odd, the ensemble is able to tolerate up to N/2
server failures without losing any znode data;
if N is even, the ensemble is able to tolerate up to N/2-1
server failures.

For example, if we have a ZooKeeper ensemble with 3 servers,
the ensemble is able to tolerate up to 1 (3/2) server failures.
If we have a ZooKeeper ensemble with 5 servers,
the ensemble is able to tolerate up to 2 (5/2) server failures.
If the ZooKeeper ensemble with 6 servers, the ensemble
is also able to tolerate up to 2 (6/2-1) server failures
without losing data and prevent the "brain split" issue.

ZooKeeper ensemble is usually has odd number of servers.
This is because with the even number of servers,
the capacity of failure tolerance is the same as
the ensemble with one less server
(2 failures for both 5-node ensemble and 6-node ensemble),
but the ensemble has to maintain extra connections and
data transfers for one more server.

To achieve the highest probability of tolerating a failure
you should try to make machine failures independent. For
example, if most of the machines share the same switch,
failure of that switch could cause a correlated failure and
bring down the service. The same holds true of shared power
circuits, cooling systems, etc.

### Single Machine Requirements

If ZooKeeper has to contend with other applications for
access to resources like storage media, CPU, network, or
memory, its performance will suffer markedly. ZooKeeper has
strong durability guarantees, which means it uses storage
media to log changes before the operation responsible for the
change is allowed to complete. You should be aware of this
dependency then, and take great care if you want to ensure
that ZooKeeper operations aren’t held up by your media. Here
are some things you can do to minimize that sort of
degradation:

* ZooKeeper's transaction log must be on a dedicated
  device. (A dedicated partition is not enough.) ZooKeeper
  writes the log sequentially, without seeking Sharing your
  log device with other processes can cause seeks and
  contention, which in turn can cause multi-second
  delays.
* Do not put ZooKeeper in a situation that can cause a
  swap. In order for ZooKeeper to function with any sort of
  timeliness, it simply cannot be allowed to swap.
  Therefore, make certain that the maximum heap size given
  to ZooKeeper is not bigger than the amount of real memory
  available to ZooKeeper. For more on this, see
  [Things to Avoid](/admin-ops/administrators-guide/best-practices#things-to-avoid)
  in the Best Practices guide.
`,d={title:"Deployment",description:"Covers ZooKeeper deployment requirements and setup: supported platforms, system requirements, clustered multi-server ensemble configuration, and single-server developer setup."},c=[{href:"#system-requirements"},{href:"#clustered-multi-server-setup"},{href:"#single-server-and-developer-setup"},{href:"/overview/quick-start"},{href:"http://java.sun.com/javase/downloads/index.jsp"},{href:"/releases"},{href:"/admin-ops/administrators-guide/configuration-parameters"},{href:"http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/"},{href:"/admin-ops/jmx"},{href:"/overview/quick-start#standalone-operation"},{href:"/overview/quick-start"},{href:"/developer/programmers-guide/bindings"},{href:"/developer/programmers-guide"},{href:"/admin-ops/administrators-guide/best-practices#things-to-avoid"}],p={contents:[{heading:void 0,content:`This section contains information about deploying Zookeeper and
covers these topics:`},{heading:void 0,content:"System Requirements"},{heading:void 0,content:"Clustered (Multi-Server) Setup"},{heading:void 0,content:"Single Server and Developer Setup"},{heading:void 0,content:`The first two sections assume you are interested in installing
ZooKeeper in a production environment such as a datacenter. The final
section covers situations in which you are setting up ZooKeeper on a
limited basis - for evaluation, testing, or development - but not in a
production environment.`},{heading:"supported-platforms",content:`ZooKeeper consists of multiple components. Some components are
supported broadly, and other components are supported only on a smaller
set of platforms.`},{heading:"supported-platforms",content:`Client is the Java client
library, used by applications to connect to a ZooKeeper ensemble.`},{heading:"supported-platforms",content:`Server is the Java server
that runs on the ZooKeeper ensemble nodes.`},{heading:"supported-platforms",content:`Native Client is a client
implemented in C, similar to the Java client, used by applications
to connect to a ZooKeeper ensemble.`},{heading:"supported-platforms",content:`Contrib refers to multiple
optional add-on components.`},{heading:"supported-platforms",content:`The following matrix describes the level of support committed for
running each component on different operating system platforms.`},{heading:"support-matrix",content:"Operating System"},{heading:"support-matrix",content:"Client"},{heading:"support-matrix",content:"Server"},{heading:"support-matrix",content:"Native Client"},{heading:"support-matrix",content:"Contrib"},{heading:"support-matrix",content:"GNU/Linux"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Solaris"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"FreeBSD"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Windows"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Development and Production"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Mac OS X"},{heading:"support-matrix",content:"Development Only"},{heading:"support-matrix",content:"Development Only"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:"Not Supported"},{heading:"support-matrix",content:`For any operating system not explicitly mentioned as supported in
the matrix, components may or may not work. The ZooKeeper community
will fix obvious bugs that are reported for other platforms, but there
is no full support.`},{heading:"required-software",content:`ZooKeeper runs in Java, release 1.8 or greater
(JDK 8 LTS, JDK 11 LTS, JDK 12 - Java 9 and 10 are not supported).
It runs as an ensemble of ZooKeeper servers. Three
ZooKeeper servers is the minimum recommended size for an
ensemble, and we also recommend that they run on separate
machines. At Yahoo!, ZooKeeper is usually deployed on
dedicated RHEL boxes, with dual-core processors, 2GB of RAM,
and 80GB IDE hard drives.`},{heading:"clustered-multi-server-setup",content:`For reliable ZooKeeper service, you should deploy ZooKeeper in a
cluster known as an ensemble. As long as a majority
of the ensemble are up, the service will be available. Because Zookeeper
requires a majority, it is best to use an
odd number of machines. For example, with four machines ZooKeeper can
only handle the failure of a single machine; if two machines fail, the
remaining two machines do not constitute a majority. However, with five
machines ZooKeeper can handle the failure of two machines.`},{heading:"clustered-multi-server-setup",content:"type: info"},{heading:"clustered-multi-server-setup",content:`As mentioned in the ZooKeeper Getting Started
Guide, a minimum of three servers are required
for a fault tolerant clustered setup, and it is strongly recommended that you
have an odd number of servers.`},{heading:"clustered-multi-server-setup",content:`Usually three servers is more than enough for a production
install, but for maximum reliability during maintenance, you may
wish to install five servers. With three servers, if you perform
maintenance on one of them, you are vulnerable to a failure on one
of the other two servers during that maintenance. If you have five
of them running, you can take one down for maintenance, and know
that you're still OK if one of the other four suddenly fails.`},{heading:"clustered-multi-server-setup",content:`Your redundancy considerations should include all aspects of
your environment. If you have three ZooKeeper servers, but their
network cables are all plugged into the same network switch, then
the failure of that switch will take down your entire ensemble.`},{heading:"clustered-multi-server-setup",content:`Here are the steps to set a server that will be part of an
ensemble. These steps should be performed on every host in the
ensemble:`},{heading:"clustered-multi-server-setup",content:`Install the Java JDK. You can use the native packaging system for your system, or download
the JDK from: http://java.sun.com/javase/downloads/index.jsp`},{heading:"clustered-multi-server-setup",content:`Set the Java heap size. This is very important to avoid swapping, which will seriously degrade
ZooKeeper performance. To determine the correct value, use load tests, and make sure you are
well below the usage limit that would cause you to swap. Be conservative — use a maximum heap
size of 3GB for a 4GB machine.`},{heading:"clustered-multi-server-setup",content:`Install the ZooKeeper Server Package. It can be downloaded
from: /releases`},{heading:"clustered-multi-server-setup",content:`Create a configuration file. This file can be called anything. Use the following settings as a
starting point:`},{heading:"clustered-multi-server-setup",content:`You can find the meanings of these and other configuration settings in the section
Configuration Parameters. Every machine that is part of the ZooKeeper
ensemble should know about every other machine in the ensemble. You accomplish this with the
series of lines of the form server.id=host:port:port. (The parameters host and port
are straightforward; for each server you need to specify first a Quorum port then a dedicated
port for ZooKeeper leader election). Since ZooKeeper 3.6.0 you can also
specify multiple addresses for each ZooKeeper server instance (this can
increase availability when multiple physical network interfaces can be used parallel in the
cluster). You attribute the server id to each machine by creating a file named myid, one for
each server, which resides in that server's data directory, as specified by the configuration
file parameter dataDir.`},{heading:"clustered-multi-server-setup",content:`The myid file consists of a single line containing only the text of that machine's id. So
myid of server 1 would contain the text "1" and nothing else. The id must be unique within
the ensemble and should have a value between 1 and 255. IMPORTANT: if you enable extended
features such as TTL Nodes (see below) the id must be between 1 and 254 due to internal
limitations.`},{heading:"clustered-multi-server-setup",content:`Create an initialization marker file initialize in the same directory as myid. This file
indicates that an empty data directory is expected. When present, an empty database is created
and the marker file deleted. When not present, an empty data directory will mean this peer will
not have voting rights and it will not populate the data directory until it communicates with
an active leader. Intended use is to only create this file when bringing up a new ensemble.`},{heading:"clustered-multi-server-setup",content:"If your configuration file is set up, you can start a ZooKeeper server:"},{heading:"clustered-multi-server-setup",content:`QuorumPeerMain starts a ZooKeeper server; JMX
management beans are also registered which allows management through a JMX management console.
The ZooKeeper JMX document contains details on managing ZooKeeper with JMX.
See the script bin/zkServer.sh, which is included in the release, for an example of starting
server instances.`},{heading:"clustered-multi-server-setup",content:`Test your deployment by connecting to the hosts. In Java, you can run the following command to
execute simple operations:`},{heading:"single-server-and-developer-setup",content:`If you want to set up ZooKeeper for development purposes, you will
probably want to set up a single server instance of ZooKeeper, and then
install either the Java or C client-side libraries and bindings on your
development machine.`},{heading:"single-server-and-developer-setup",content:`The steps to setting up a single server instance are the similar
to the above, except the configuration file is simpler. You can find the
complete instructions in the Installing and
Running ZooKeeper in Single Server Mode section of the ZooKeeper Getting Started
Guide.`},{heading:"single-server-and-developer-setup",content:`For information on installing the client side libraries, refer to
the Bindings
section of the ZooKeeper
Programmer's Guide.`},{heading:"designing-a-zookeeper-deployment",content:"The reliability of ZooKeeper rests on two basic assumptions."},{heading:"designing-a-zookeeper-deployment",content:`Only a minority of servers in a deployment
will fail. Failure in this context
means a machine crash, or some error in the network that
partitions a server off from the majority.`},{heading:"designing-a-zookeeper-deployment",content:`Deployed machines operate correctly. To
operate correctly means to execute code correctly, to have
clocks that work properly, and to have storage and network
components that perform consistently.`},{heading:"designing-a-zookeeper-deployment",content:`The sections below contain considerations for ZooKeeper
administrators to maximize the probability for these assumptions
to hold true. Some of these are cross-machines considerations,
and others are things you should consider for each and every
machine in your deployment.`},{heading:"cross-machine-requirements",content:`For the ZooKeeper service to be active, there must be a
majority of non-failing machines that can communicate with
each other. For a ZooKeeper ensemble with N servers,
if N is odd, the ensemble is able to tolerate up to N/2
server failures without losing any znode data;
if N is even, the ensemble is able to tolerate up to N/2-1
server failures.`},{heading:"cross-machine-requirements",content:`For example, if we have a ZooKeeper ensemble with 3 servers,
the ensemble is able to tolerate up to 1 (3/2) server failures.
If we have a ZooKeeper ensemble with 5 servers,
the ensemble is able to tolerate up to 2 (5/2) server failures.
If the ZooKeeper ensemble with 6 servers, the ensemble
is also able to tolerate up to 2 (6/2-1) server failures
without losing data and prevent the "brain split" issue.`},{heading:"cross-machine-requirements",content:`ZooKeeper ensemble is usually has odd number of servers.
This is because with the even number of servers,
the capacity of failure tolerance is the same as
the ensemble with one less server
(2 failures for both 5-node ensemble and 6-node ensemble),
but the ensemble has to maintain extra connections and
data transfers for one more server.`},{heading:"cross-machine-requirements",content:`To achieve the highest probability of tolerating a failure
you should try to make machine failures independent. For
example, if most of the machines share the same switch,
failure of that switch could cause a correlated failure and
bring down the service. The same holds true of shared power
circuits, cooling systems, etc.`},{heading:"single-machine-requirements",content:`If ZooKeeper has to contend with other applications for
access to resources like storage media, CPU, network, or
memory, its performance will suffer markedly. ZooKeeper has
strong durability guarantees, which means it uses storage
media to log changes before the operation responsible for the
change is allowed to complete. You should be aware of this
dependency then, and take great care if you want to ensure
that ZooKeeper operations aren’t held up by your media. Here
are some things you can do to minimize that sort of
degradation:`},{heading:"single-machine-requirements",content:`ZooKeeper's transaction log must be on a dedicated
device. (A dedicated partition is not enough.) ZooKeeper
writes the log sequentially, without seeking Sharing your
log device with other processes can cause seeks and
contention, which in turn can cause multi-second
delays.`},{heading:"single-machine-requirements",content:`Do not put ZooKeeper in a situation that can cause a
swap. In order for ZooKeeper to function with any sort of
timeliness, it simply cannot be allowed to swap.
Therefore, make certain that the maximum heap size given
to ZooKeeper is not bigger than the amount of real memory
available to ZooKeeper. For more on this, see
Things to Avoid
in the Best Practices guide.`}],headings:[{id:"system-requirements",content:"System Requirements"},{id:"supported-platforms",content:"Supported Platforms"},{id:"support-matrix",content:"Support Matrix"},{id:"required-software",content:"Required Software"},{id:"clustered-multi-server-setup",content:"Clustered (Multi-Server) Setup"},{id:"single-server-and-developer-setup",content:"Single Server and Developer Setup"},{id:"designing-a-zookeeper-deployment",content:"Designing a ZooKeeper Deployment"},{id:"cross-machine-requirements",content:"Cross Machine Requirements"},{id:"single-machine-requirements",content:"Single Machine Requirements"}]};const u=[{depth:2,url:"#system-requirements",title:e.jsx(e.Fragment,{children:"System Requirements"})},{depth:3,url:"#supported-platforms",title:e.jsx(e.Fragment,{children:"Supported Platforms"})},{depth:3,url:"#support-matrix",title:e.jsx(e.Fragment,{children:"Support Matrix"})},{depth:3,url:"#required-software",title:e.jsx(e.Fragment,{children:"Required Software"})},{depth:2,url:"#clustered-multi-server-setup",title:e.jsx(e.Fragment,{children:"Clustered (Multi-Server) Setup"})},{depth:2,url:"#single-server-and-developer-setup",title:e.jsx(e.Fragment,{children:"Single Server and Developer Setup"})},{depth:2,url:"#designing-a-zookeeper-deployment",title:e.jsx(e.Fragment,{children:"Designing a ZooKeeper Deployment"})},{depth:3,url:"#cross-machine-requirements",title:e.jsx(e.Fragment,{children:"Cross Machine Requirements"})},{depth:3,url:"#single-machine-requirements",title:e.jsx(e.Fragment,{children:"Single Machine Requirements"})}];function a(o){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...o.components},{Callout:i,Step:t,Steps:s}=n;return i||r("Callout"),t||r("Step"),s||r("Steps"),e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`This section contains information about deploying Zookeeper and
covers these topics:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:e.jsx(n.a,{href:"#system-requirements",children:"System Requirements"})}),`
`,e.jsx(n.li,{children:e.jsx(n.a,{href:"#clustered-multi-server-setup",children:"Clustered (Multi-Server) Setup"})}),`
`,e.jsx(n.li,{children:e.jsx(n.a,{href:"#single-server-and-developer-setup",children:"Single Server and Developer Setup"})}),`
`]}),`
`,e.jsx(n.p,{children:`The first two sections assume you are interested in installing
ZooKeeper in a production environment such as a datacenter. The final
section covers situations in which you are setting up ZooKeeper on a
limited basis - for evaluation, testing, or development - but not in a
production environment.`}),`
`,e.jsx(n.h2,{id:"system-requirements",children:"System Requirements"}),`
`,e.jsx(n.h3,{id:"supported-platforms",children:"Supported Platforms"}),`
`,e.jsx(n.p,{children:`ZooKeeper consists of multiple components. Some components are
supported broadly, and other components are supported only on a smaller
set of platforms.`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Client"}),` is the Java client
library, used by applications to connect to a ZooKeeper ensemble.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Server"}),` is the Java server
that runs on the ZooKeeper ensemble nodes.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Native Client"}),` is a client
implemented in C, similar to the Java client, used by applications
to connect to a ZooKeeper ensemble.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Contrib"}),` refers to multiple
optional add-on components.`]}),`
`]}),`
`,e.jsx(n.p,{children:`The following matrix describes the level of support committed for
running each component on different operating system platforms.`}),`
`,e.jsx(n.h3,{id:"support-matrix",children:"Support Matrix"}),`
`,e.jsxs(n.table,{children:[e.jsx(n.thead,{children:e.jsxs(n.tr,{children:[e.jsx(n.th,{children:"Operating System"}),e.jsx(n.th,{children:"Client"}),e.jsx(n.th,{children:"Server"}),e.jsx(n.th,{children:"Native Client"}),e.jsx(n.th,{children:"Contrib"})]})}),e.jsxs(n.tbody,{children:[e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"GNU/Linux"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Solaris"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Not Supported"}),e.jsx(n.td,{children:"Not Supported"})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"FreeBSD"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Not Supported"}),e.jsx(n.td,{children:"Not Supported"})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Windows"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Development and Production"}),e.jsx(n.td,{children:"Not Supported"}),e.jsx(n.td,{children:"Not Supported"})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Mac OS X"}),e.jsx(n.td,{children:"Development Only"}),e.jsx(n.td,{children:"Development Only"}),e.jsx(n.td,{children:"Not Supported"}),e.jsx(n.td,{children:"Not Supported"})]})]})]}),`
`,e.jsx(n.p,{children:`For any operating system not explicitly mentioned as supported in
the matrix, components may or may not work. The ZooKeeper community
will fix obvious bugs that are reported for other platforms, but there
is no full support.`}),`
`,e.jsx(n.h3,{id:"required-software",children:"Required Software"}),`
`,e.jsxs(n.p,{children:[`ZooKeeper runs in Java, release 1.8 or greater
(JDK 8 LTS, JDK 11 LTS, JDK 12 - Java 9 and 10 are not supported).
It runs as an `,e.jsx(n.em,{children:"ensemble"}),` of ZooKeeper servers. Three
ZooKeeper servers is the minimum recommended size for an
ensemble, and we also recommend that they run on separate
machines. At Yahoo!, ZooKeeper is usually deployed on
dedicated RHEL boxes, with dual-core processors, 2GB of RAM,
and 80GB IDE hard drives.`]}),`
`,e.jsx(n.h2,{id:"clustered-multi-server-setup",children:"Clustered (Multi-Server) Setup"}),`
`,e.jsxs(n.p,{children:[`For reliable ZooKeeper service, you should deploy ZooKeeper in a
cluster known as an `,e.jsx(n.em,{children:"ensemble"}),`. As long as a majority
of the ensemble are up, the service will be available. Because Zookeeper
requires a majority, it is best to use an
odd number of machines. For example, with four machines ZooKeeper can
only handle the failure of a single machine; if two machines fail, the
remaining two machines do not constitute a majority. However, with five
machines ZooKeeper can handle the failure of two machines.`]}),`
`,e.jsxs(i,{type:"info",children:[e.jsxs(n.p,{children:["As mentioned in the ",e.jsx(n.a,{href:"/overview/quick-start",children:`ZooKeeper Getting Started
Guide`}),`, a minimum of three servers are required
for a fault tolerant clustered setup, and it is strongly recommended that you
have an odd number of servers.`]}),e.jsx(n.p,{children:`Usually three servers is more than enough for a production
install, but for maximum reliability during maintenance, you may
wish to install five servers. With three servers, if you perform
maintenance on one of them, you are vulnerable to a failure on one
of the other two servers during that maintenance. If you have five
of them running, you can take one down for maintenance, and know
that you're still OK if one of the other four suddenly fails.`}),e.jsx(n.p,{children:`Your redundancy considerations should include all aspects of
your environment. If you have three ZooKeeper servers, but their
network cables are all plugged into the same network switch, then
the failure of that switch will take down your entire ensemble.`})]}),`
`,e.jsx(n.p,{children:`Here are the steps to set a server that will be part of an
ensemble. These steps should be performed on every host in the
ensemble:`}),`
`,e.jsxs(s,{children:[e.jsx(t,{children:e.jsxs(n.p,{children:[`Install the Java JDK. You can use the native packaging system for your system, or download
the JDK from: `,e.jsx(n.a,{href:"http://java.sun.com/javase/downloads/index.jsp",children:"http://java.sun.com/javase/downloads/index.jsp"})]})}),e.jsx(t,{children:e.jsx(n.p,{children:`Set the Java heap size. This is very important to avoid swapping, which will seriously degrade
ZooKeeper performance. To determine the correct value, use load tests, and make sure you are
well below the usage limit that would cause you to swap. Be conservative — use a maximum heap
size of 3GB for a 4GB machine.`})}),e.jsx(t,{children:e.jsxs(n.p,{children:[`Install the ZooKeeper Server Package. It can be downloaded
from: `,e.jsx(n.a,{href:"/releases",children:"/releases"})]})}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Create a configuration file. This file can be called anything. Use the following settings as a
starting point:`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/var/lib/zookeeper/"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"clientPort=2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=zoo1:2888:3888"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2:2888:3888"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=zoo3:2888:3888"})})]})})}),e.jsxs(n.p,{children:[`You can find the meanings of these and other configuration settings in the section
`,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters",children:"Configuration Parameters"}),`. Every machine that is part of the ZooKeeper
ensemble should know about every other machine in the ensemble. You accomplish this with the
series of lines of the form `,e.jsx(n.strong,{children:"server.id=host:port:port"}),". (The parameters ",e.jsx(n.strong,{children:"host"})," and ",e.jsx(n.strong,{children:"port"}),`
are straightforward; for each server you need to specify first a Quorum port then a dedicated
port for ZooKeeper leader election). Since ZooKeeper 3.6.0 you can also
specify multiple addresses for each ZooKeeper server instance (this can
increase availability when multiple physical network interfaces can be used parallel in the
cluster). You attribute the server id to each machine by creating a file named `,e.jsx(n.em,{children:"myid"}),`, one for
each server, which resides in that server's data directory, as specified by the configuration
file parameter `,e.jsx(n.strong,{children:"dataDir"}),"."]})]}),e.jsx(t,{children:e.jsxs(n.p,{children:["The ",e.jsx(n.em,{children:"myid"}),` file consists of a single line containing only the text of that machine's id. So
`,e.jsx(n.em,{children:"myid"}),` of server 1 would contain the text "1" and nothing else. The id must be unique within
the ensemble and should have a value between 1 and 255. `,e.jsx(n.strong,{children:"IMPORTANT:"}),` if you enable extended
features such as TTL Nodes (see below) the id must be between 1 and 254 due to internal
limitations.`]})}),e.jsx(t,{children:e.jsxs(n.p,{children:["Create an initialization marker file ",e.jsx(n.em,{children:"initialize"})," in the same directory as ",e.jsx(n.em,{children:"myid"}),`. This file
indicates that an empty data directory is expected. When present, an empty database is created
and the marker file deleted. When not present, an empty data directory will mean this peer will
not have voting rights and it will not populate the data directory until it communicates with
an active leader. Intended use is to only create this file when bringing up a new ensemble.`]})}),e.jsxs(t,{children:[e.jsx(n.p,{children:"If your configuration file is set up, you can start a ZooKeeper server:"}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"$ java -cp zookeeper.jar:lib/*:conf org.apache.zookeeper.server.quorum.QuorumPeerMain zoo.conf"})})})})}),e.jsxs(n.p,{children:["QuorumPeerMain starts a ZooKeeper server; ",e.jsx(n.a,{href:"http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/",children:"JMX"}),`
management beans are also registered which allows management through a JMX management console.
The `,e.jsx(n.a,{href:"/admin-ops/jmx",children:"ZooKeeper JMX document"}),` contains details on managing ZooKeeper with JMX.
See the script `,e.jsx(n.em,{children:"bin/zkServer.sh"}),`, which is included in the release, for an example of starting
server instances.`]})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Test your deployment by connecting to the hosts. In Java, you can run the following command to
execute simple operations:`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"$ bin/zkCli.sh -server 127.0.0.1:2181"})})})})})]})]}),`
`,e.jsx(n.h2,{id:"single-server-and-developer-setup",children:"Single Server and Developer Setup"}),`
`,e.jsx(n.p,{children:`If you want to set up ZooKeeper for development purposes, you will
probably want to set up a single server instance of ZooKeeper, and then
install either the Java or C client-side libraries and bindings on your
development machine.`}),`
`,e.jsxs(n.p,{children:[`The steps to setting up a single server instance are the similar
to the above, except the configuration file is simpler. You can find the
complete instructions in the `,e.jsx(n.a,{href:"/overview/quick-start#standalone-operation",children:`Installing and
Running ZooKeeper in Single Server Mode`})," section of the ",e.jsx(n.a,{href:"/overview/quick-start",children:`ZooKeeper Getting Started
Guide`}),"."]}),`
`,e.jsxs(n.p,{children:[`For information on installing the client side libraries, refer to
the `,e.jsx(n.a,{href:"/developer/programmers-guide/bindings",children:"Bindings"}),`
section of the `,e.jsx(n.a,{href:"/developer/programmers-guide",children:`ZooKeeper
Programmer's Guide`}),"."]}),`
`,e.jsx(n.h2,{id:"designing-a-zookeeper-deployment",children:"Designing a ZooKeeper Deployment"}),`
`,e.jsx(n.p,{children:"The reliability of ZooKeeper rests on two basic assumptions."}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:[`Only a minority of servers in a deployment
will fail. `,e.jsx(n.em,{children:"Failure"}),` in this context
means a machine crash, or some error in the network that
partitions a server off from the majority.`]}),`
`,e.jsx(n.li,{children:`Deployed machines operate correctly. To
operate correctly means to execute code correctly, to have
clocks that work properly, and to have storage and network
components that perform consistently.`}),`
`]}),`
`,e.jsx(n.p,{children:`The sections below contain considerations for ZooKeeper
administrators to maximize the probability for these assumptions
to hold true. Some of these are cross-machines considerations,
and others are things you should consider for each and every
machine in your deployment.`}),`
`,e.jsx(n.h3,{id:"cross-machine-requirements",children:"Cross Machine Requirements"}),`
`,e.jsx(n.p,{children:`For the ZooKeeper service to be active, there must be a
majority of non-failing machines that can communicate with
each other. For a ZooKeeper ensemble with N servers,
if N is odd, the ensemble is able to tolerate up to N/2
server failures without losing any znode data;
if N is even, the ensemble is able to tolerate up to N/2-1
server failures.`}),`
`,e.jsx(n.p,{children:`For example, if we have a ZooKeeper ensemble with 3 servers,
the ensemble is able to tolerate up to 1 (3/2) server failures.
If we have a ZooKeeper ensemble with 5 servers,
the ensemble is able to tolerate up to 2 (5/2) server failures.
If the ZooKeeper ensemble with 6 servers, the ensemble
is also able to tolerate up to 2 (6/2-1) server failures
without losing data and prevent the "brain split" issue.`}),`
`,e.jsx(n.p,{children:`ZooKeeper ensemble is usually has odd number of servers.
This is because with the even number of servers,
the capacity of failure tolerance is the same as
the ensemble with one less server
(2 failures for both 5-node ensemble and 6-node ensemble),
but the ensemble has to maintain extra connections and
data transfers for one more server.`}),`
`,e.jsx(n.p,{children:`To achieve the highest probability of tolerating a failure
you should try to make machine failures independent. For
example, if most of the machines share the same switch,
failure of that switch could cause a correlated failure and
bring down the service. The same holds true of shared power
circuits, cooling systems, etc.`}),`
`,e.jsx(n.h3,{id:"single-machine-requirements",children:"Single Machine Requirements"}),`
`,e.jsx(n.p,{children:`If ZooKeeper has to contend with other applications for
access to resources like storage media, CPU, network, or
memory, its performance will suffer markedly. ZooKeeper has
strong durability guarantees, which means it uses storage
media to log changes before the operation responsible for the
change is allowed to complete. You should be aware of this
dependency then, and take great care if you want to ensure
that ZooKeeper operations aren’t held up by your media. Here
are some things you can do to minimize that sort of
degradation:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`ZooKeeper's transaction log must be on a dedicated
device. (A dedicated partition is not enough.) ZooKeeper
writes the log sequentially, without seeking Sharing your
log device with other processes can cause seeks and
contention, which in turn can cause multi-second
delays.`}),`
`,e.jsxs(n.li,{children:[`Do not put ZooKeeper in a situation that can cause a
swap. In order for ZooKeeper to function with any sort of
timeliness, it simply cannot be allowed to swap.
Therefore, make certain that the maximum heap size given
to ZooKeeper is not bigger than the amount of real memory
available to ZooKeeper. For more on this, see
`,e.jsx(n.a,{href:"/admin-ops/administrators-guide/best-practices#things-to-avoid",children:"Things to Avoid"}),`
in the Best Practices guide.`]}),`
`]})]})}function m(o={}){const{wrapper:n}=o.components||{};return n?e.jsx(n,{...o,children:e.jsx(a,{...o})}):a(o)}function r(o,n){throw new Error("Expected component `"+o+"` to be defined: you likely forgot to import, pass, or provide it.")}export{h as _markdown,m as default,c as extractedReferences,d as frontmatter,p as structuredData,u as toc};
