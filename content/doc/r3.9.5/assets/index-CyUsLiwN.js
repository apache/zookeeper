import{j as e}from"./chunk-EPOLDU6W-CDTnuKF9.js";const i="/doc/r3.9.5/assets/zkservice-BXuKweev.jpg",s="/doc/r3.9.5/assets/zknamespace-btUn8Dhx.jpg",r="/doc/r3.9.5/assets/zkcomponents-D9aXtr_d.jpg",l="/doc/r3.9.5/assets/zkperfRW-3.2-BSqdgeeF.jpg",h="/doc/r3.9.5/assets/zkperfreliability-Ch8Xwzch.jpg";let p=`









export const pageTitle = \`\${__CURRENT_VERSION__} Overview\`;
export const pageDescription = \`Official Apache ZooKeeper \${__CURRENT_VERSION__} documentation covering installation, configuration, the data model, client APIs, administration, and operations.\`;

## ZooKeeper: A Distributed Coordination Service for Distributed Applications

ZooKeeper is a distributed, open-source coordination service for
distributed applications. It exposes a simple set of primitives that
distributed applications can build upon to implement higher level services
for synchronization, configuration maintenance, and groups and naming. It
is designed to be easy to program to, and uses a data model styled after
the familiar directory tree structure of file systems. It runs in Java and
has bindings for both Java and C.

Coordination services are notoriously hard to get right. They are
especially prone to errors such as race conditions and deadlock. The
motivation behind ZooKeeper is to relieve distributed applications the
responsibility of implementing coordination services from scratch.

### Design Goals

**ZooKeeper is simple.** ZooKeeper
allows distributed processes to coordinate with each other through a
shared hierarchical namespace which is organized similarly to a standard
file system. The namespace consists of data registers - called znodes,
in ZooKeeper parlance - and these are similar to files and directories.
Unlike a typical file system, which is designed for storage, ZooKeeper
data is kept in-memory, which means ZooKeeper can achieve high
throughput and low latency numbers.

The ZooKeeper implementation puts a premium on high performance,
highly available, strictly ordered access. The performance aspects of
ZooKeeper means it can be used in large, distributed systems. The
reliability aspects keep it from being a single point of failure. The
strict ordering means that sophisticated synchronization primitives can
be implemented at the client.

**ZooKeeper is replicated.** Like the
distributed processes it coordinates, ZooKeeper itself is intended to be
replicated over a set of hosts called an ensemble.

<img alt="ZooKeeper Service" src={__img0} placeholder="blur" />

The servers that make up the ZooKeeper service must all know about
each other. They maintain an in-memory image of state, along with a
transaction logs and snapshots in a persistent store. As long as a
majority of the servers are available, the ZooKeeper service will be
available.

Clients connect to a single ZooKeeper server. The client maintains
a TCP connection through which it sends requests, gets responses, gets
watch events, and sends heart beats. If the TCP connection to the server
breaks, the client will connect to a different server.

**ZooKeeper is ordered.** ZooKeeper
stamps each update with a number that reflects the order of all
ZooKeeper transactions. Subsequent operations can use the order to
implement higher-level abstractions, such as synchronization
primitives.

**ZooKeeper is fast.** It is
especially fast in "read-dominant" workloads. ZooKeeper applications run
on thousands of machines, and it performs best where reads are more
common than writes, at ratios of around 10:1.

### Data model and the hierarchical namespace

The namespace provided by ZooKeeper is much like that of a
standard file system. A name is a sequence of path elements separated by
a slash (/). Every node in ZooKeeper's namespace is identified by a
path.

#### ZooKeeper's Hierarchical Namespace

<img alt="ZooKeeper's Hierarchical Namespace" src={__img1} placeholder="blur" />

### Nodes and ephemeral nodes

Unlike standard file systems, each node in a ZooKeeper
namespace can have data associated with it as well as children. It is
like having a file-system that allows a file to also be a directory.
(ZooKeeper was designed to store coordination data: status information,
configuration, location information, etc., so the data stored at each
node is usually small, in the byte to kilobyte range.) We use the term
*znode* to make it clear that we are talking about
ZooKeeper data nodes.

Znodes maintain a stat structure that includes version numbers for
data changes, ACL changes, and timestamps, to allow cache validations
and coordinated updates. Each time a znode's data changes, the version
number increases. For instance, whenever a client retrieves data it also
receives the version of the data.

The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List (ACL)
that restricts who can do what.

ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When the
session ends the znode is deleted.

### Conditional updates and watches

ZooKeeper supports the concept of *watches*.
Clients can set a watch on a znode. A watch will be triggered and
removed when the znode changes. When a watch is triggered, the client
receives a packet saying that the znode has changed. If the
connection between the client and one of the ZooKeeper servers is
broken, the client will receive a local notification.

**New in 3.6.0:** Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.

### Guarantees

ZooKeeper is very fast and very simple. Since its goal, though, is
to be a basis for the construction of more complicated services, such as
synchronization, it provides a set of guarantees. These are:

* Sequential Consistency - Updates from a client will be applied
  in the order that they were sent.
* Atomicity - Updates either succeed or fail. No partial
  results.
* Single System Image - A client will see the same view of the
  service regardless of the server that it connects to. i.e., a
  client will never see an older view of the system even if the
  client fails over to a different server with the same session.
* Reliability - Once an update has been applied, it will persist
  from that time forward until a client overwrites the update.
* Timeliness - The clients view of the system is guaranteed to
  be up-to-date within a certain time bound.

### Simple API

One of the design goals of ZooKeeper is providing a very simple
programming interface. As a result, it supports only these
operations:

* *create* : creates a node at a location in the tree
* *delete* : deletes a node
* *exists* : tests if a node exists at a location
* *get data* : reads the data from a node
* *set data* : writes data to a node
* *get children* : retrieves a list of children of a node
* *sync* : waits for data to be propagated

### Implementation

ZooKeeper Components shows the high-level components
of the ZooKeeper service. With the exception of the request processor,
each of
the servers that make up the ZooKeeper service replicates its own copy
of each of the components.

<img alt="ZooKeeper Components" src={__img2} placeholder="blur" />

The replicated database is an in-memory database containing the
entire data tree. Updates are logged to disk for recoverability, and
writes are serialized to disk before they are applied to the in-memory
database.

Every ZooKeeper server services clients. Clients connect to
exactly one server to submit requests. Read requests are serviced from
the local replica of each server database. Requests that change the
state of the service, write requests, are processed by an agreement
protocol.

As part of the agreement protocol all write requests from clients
are forwarded to a single server, called the
*leader*. The rest of the ZooKeeper servers, called
*followers*, receive message proposals from the
leader and agree upon message delivery. The messaging layer takes care
of replacing leaders on failures and syncing followers with
leaders.

ZooKeeper uses a custom atomic messaging protocol. Since the
messaging layer is atomic, ZooKeeper can guarantee that the local
replicas never diverge. When the leader receives a write request, it
calculates what the state of the system is when the write is to be
applied and transforms this into a transaction that captures this new
state.

### Uses

The programming interface to ZooKeeper is deliberately simple.
With it, however, you can implement higher order operations, such as
synchronizations primitives, group membership, ownership, etc.

### Performance

ZooKeeper is designed to be highly performance. But is it? The
results of the ZooKeeper's development team at Yahoo! Research indicate
that it is. It is especially high
performance in applications where reads outnumber writes, since writes
involve synchronizing the state of all servers. (Reads outnumbering
writes is typically the case for a coordination service.)

<img alt="ZooKeeper Throughput as the Read-Write Ratio Varies" src={__img3} placeholder="blur" />

The "ZooKeeper Throughput as the Read-Write Ratio Varies" is a throughput
graph of ZooKeeper release 3.2 running on servers with dual 2Ghz
Xeon and two SATA 15K RPM drives. One drive was used as a
dedicated ZooKeeper log device. The snapshots were written to
the OS drive. Write requests were 1K writes and the reads were
1K reads. "Servers" indicate the size of the ZooKeeper
ensemble, the number of servers that make up the
service. Approximately 30 other servers were used to simulate
the clients. The ZooKeeper ensemble was configured such that
leaders do not allow connections from clients.

<Callout type="info">
  In version 3.2 r/w performance improved by \\~2x compared to
</Callout>

the [previous 3.1 release](http://zookeeper.apache.org/docs/r3.1.1/zookeeperOver.html#Performance).

Benchmarks also indicate that it is reliable, too.
[Reliability in the Presence of Errors](#reliability) shows how a deployment responds to
various failures. The events marked in the figure are the following:

1. Failure and recovery of a follower
2. Failure and recovery of a different follower
3. Failure of the leader
4. Failure and recovery of two followers
5. Failure of another leader

### Reliability

To show the behavior of the system over time as
failures are injected we ran a ZooKeeper service made up of
7 machines. We ran the same saturation benchmark as before,
but this time we kept the write percentage at a constant
30%, which is a conservative ratio of our expected
workloads.

<img alt="Reliability in the Presence of Errors" src={__img4} placeholder="blur" />

There are a few important observations from this graph. First, if
followers fail and recover quickly, then ZooKeeper is able to sustain a
high throughput despite the failure. But maybe more importantly, the
leader election algorithm allows for the system to recover fast enough
to prevent throughput from dropping substantially. In our observations,
ZooKeeper takes less than 200ms to elect a new leader. Third, as
followers recover, ZooKeeper is able to raise throughput again once they
start processing requests.

### The ZooKeeper Project

ZooKeeper has been
[successfully used](https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy)
in many industrial applications. It is used at Yahoo! as the
coordination and failure recovery service for Yahoo! Message
Broker, which is a highly scalable publish-subscribe system
managing thousands of topics for replication and data
delivery. It is used by the Fetching Service for Yahoo!
crawler, where it also manages failure recovery. A number of
Yahoo! advertising systems also use ZooKeeper to implement
reliable services.

All users and developers are encouraged to join the
community and contribute their expertise. See the
[Zookeeper Project on Apache](https://zookeeper.apache.org/)
for more information.
`,m={title:"Overview",description:"Official Apache ZooKeeper documentation covering installation, configuration, the data model, client APIs, administration, and operations."},u=[{href:"http://zookeeper.apache.org/docs/r3.1.1/zookeeperOver.html#Performance"},{href:"#reliability"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy"},{href:"https://zookeeper.apache.org/"}],g={contents:[{heading:"zookeeper-a-distributed-coordination-service-for-distributed-applications",content:`ZooKeeper is a distributed, open-source coordination service for
distributed applications. It exposes a simple set of primitives that
distributed applications can build upon to implement higher level services
for synchronization, configuration maintenance, and groups and naming. It
is designed to be easy to program to, and uses a data model styled after
the familiar directory tree structure of file systems. It runs in Java and
has bindings for both Java and C.`},{heading:"zookeeper-a-distributed-coordination-service-for-distributed-applications",content:`Coordination services are notoriously hard to get right. They are
especially prone to errors such as race conditions and deadlock. The
motivation behind ZooKeeper is to relieve distributed applications the
responsibility of implementing coordination services from scratch.`},{heading:"design-goals",content:`ZooKeeper is simple. ZooKeeper
allows distributed processes to coordinate with each other through a
shared hierarchical namespace which is organized similarly to a standard
file system. The namespace consists of data registers - called znodes,
in ZooKeeper parlance - and these are similar to files and directories.
Unlike a typical file system, which is designed for storage, ZooKeeper
data is kept in-memory, which means ZooKeeper can achieve high
throughput and low latency numbers.`},{heading:"design-goals",content:`The ZooKeeper implementation puts a premium on high performance,
highly available, strictly ordered access. The performance aspects of
ZooKeeper means it can be used in large, distributed systems. The
reliability aspects keep it from being a single point of failure. The
strict ordering means that sophisticated synchronization primitives can
be implemented at the client.`},{heading:"design-goals",content:`ZooKeeper is replicated. Like the
distributed processes it coordinates, ZooKeeper itself is intended to be
replicated over a set of hosts called an ensemble.`},{heading:"design-goals",content:`The servers that make up the ZooKeeper service must all know about
each other. They maintain an in-memory image of state, along with a
transaction logs and snapshots in a persistent store. As long as a
majority of the servers are available, the ZooKeeper service will be
available.`},{heading:"design-goals",content:`Clients connect to a single ZooKeeper server. The client maintains
a TCP connection through which it sends requests, gets responses, gets
watch events, and sends heart beats. If the TCP connection to the server
breaks, the client will connect to a different server.`},{heading:"design-goals",content:`ZooKeeper is ordered. ZooKeeper
stamps each update with a number that reflects the order of all
ZooKeeper transactions. Subsequent operations can use the order to
implement higher-level abstractions, such as synchronization
primitives.`},{heading:"design-goals",content:`ZooKeeper is fast. It is
especially fast in "read-dominant" workloads. ZooKeeper applications run
on thousands of machines, and it performs best where reads are more
common than writes, at ratios of around 10:1.`},{heading:"data-model-and-the-hierarchical-namespace",content:`The namespace provided by ZooKeeper is much like that of a
standard file system. A name is a sequence of path elements separated by
a slash (/). Every node in ZooKeeper's namespace is identified by a
path.`},{heading:"nodes-and-ephemeral-nodes",content:`Unlike standard file systems, each node in a ZooKeeper
namespace can have data associated with it as well as children. It is
like having a file-system that allows a file to also be a directory.
(ZooKeeper was designed to store coordination data: status information,
configuration, location information, etc., so the data stored at each
node is usually small, in the byte to kilobyte range.) We use the term
znode to make it clear that we are talking about
ZooKeeper data nodes.`},{heading:"nodes-and-ephemeral-nodes",content:`Znodes maintain a stat structure that includes version numbers for
data changes, ACL changes, and timestamps, to allow cache validations
and coordinated updates. Each time a znode's data changes, the version
number increases. For instance, whenever a client retrieves data it also
receives the version of the data.`},{heading:"nodes-and-ephemeral-nodes",content:`The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List (ACL)
that restricts who can do what.`},{heading:"nodes-and-ephemeral-nodes",content:`ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When the
session ends the znode is deleted.`},{heading:"conditional-updates-and-watches",content:`ZooKeeper supports the concept of watches.
Clients can set a watch on a znode. A watch will be triggered and
removed when the znode changes. When a watch is triggered, the client
receives a packet saying that the znode has changed. If the
connection between the client and one of the ZooKeeper servers is
broken, the client will receive a local notification.`},{heading:"conditional-updates-and-watches",content:`New in 3.6.0: Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.`},{heading:"guarantees",content:`ZooKeeper is very fast and very simple. Since its goal, though, is
to be a basis for the construction of more complicated services, such as
synchronization, it provides a set of guarantees. These are:`},{heading:"guarantees",content:`Sequential Consistency - Updates from a client will be applied
in the order that they were sent.`},{heading:"guarantees",content:`Atomicity - Updates either succeed or fail. No partial
results.`},{heading:"guarantees",content:`Single System Image - A client will see the same view of the
service regardless of the server that it connects to. i.e., a
client will never see an older view of the system even if the
client fails over to a different server with the same session.`},{heading:"guarantees",content:`Reliability - Once an update has been applied, it will persist
from that time forward until a client overwrites the update.`},{heading:"guarantees",content:`Timeliness - The clients view of the system is guaranteed to
be up-to-date within a certain time bound.`},{heading:"simple-api",content:`One of the design goals of ZooKeeper is providing a very simple
programming interface. As a result, it supports only these
operations:`},{heading:"simple-api",content:"create : creates a node at a location in the tree"},{heading:"simple-api",content:"delete : deletes a node"},{heading:"simple-api",content:"exists : tests if a node exists at a location"},{heading:"simple-api",content:"get data : reads the data from a node"},{heading:"simple-api",content:"set data : writes data to a node"},{heading:"simple-api",content:"get children : retrieves a list of children of a node"},{heading:"simple-api",content:"sync : waits for data to be propagated"},{heading:"implementation",content:`ZooKeeper Components shows the high-level components
of the ZooKeeper service. With the exception of the request processor,
each of
the servers that make up the ZooKeeper service replicates its own copy
of each of the components.`},{heading:"implementation",content:`The replicated database is an in-memory database containing the
entire data tree. Updates are logged to disk for recoverability, and
writes are serialized to disk before they are applied to the in-memory
database.`},{heading:"implementation",content:`Every ZooKeeper server services clients. Clients connect to
exactly one server to submit requests. Read requests are serviced from
the local replica of each server database. Requests that change the
state of the service, write requests, are processed by an agreement
protocol.`},{heading:"implementation",content:`As part of the agreement protocol all write requests from clients
are forwarded to a single server, called the
leader. The rest of the ZooKeeper servers, called
followers, receive message proposals from the
leader and agree upon message delivery. The messaging layer takes care
of replacing leaders on failures and syncing followers with
leaders.`},{heading:"implementation",content:`ZooKeeper uses a custom atomic messaging protocol. Since the
messaging layer is atomic, ZooKeeper can guarantee that the local
replicas never diverge. When the leader receives a write request, it
calculates what the state of the system is when the write is to be
applied and transforms this into a transaction that captures this new
state.`},{heading:"uses",content:`The programming interface to ZooKeeper is deliberately simple.
With it, however, you can implement higher order operations, such as
synchronizations primitives, group membership, ownership, etc.`},{heading:"performance",content:`ZooKeeper is designed to be highly performance. But is it? The
results of the ZooKeeper's development team at Yahoo! Research indicate
that it is. It is especially high
performance in applications where reads outnumber writes, since writes
involve synchronizing the state of all servers. (Reads outnumbering
writes is typically the case for a coordination service.)`},{heading:"performance",content:`The "ZooKeeper Throughput as the Read-Write Ratio Varies" is a throughput
graph of ZooKeeper release 3.2 running on servers with dual 2Ghz
Xeon and two SATA 15K RPM drives. One drive was used as a
dedicated ZooKeeper log device. The snapshots were written to
the OS drive. Write requests were 1K writes and the reads were
1K reads. "Servers" indicate the size of the ZooKeeper
ensemble, the number of servers that make up the
service. Approximately 30 other servers were used to simulate
the clients. The ZooKeeper ensemble was configured such that
leaders do not allow connections from clients.`},{heading:"performance",content:"type: info"},{heading:"performance",content:"In version 3.2 r/w performance improved by ~2x compared to"},{heading:"performance",content:"the previous 3.1 release."},{heading:"performance",content:`Benchmarks also indicate that it is reliable, too.
Reliability in the Presence of Errors shows how a deployment responds to
various failures. The events marked in the figure are the following:`},{heading:"performance",content:"Failure and recovery of a follower"},{heading:"performance",content:"Failure and recovery of a different follower"},{heading:"performance",content:"Failure of the leader"},{heading:"performance",content:"Failure and recovery of two followers"},{heading:"performance",content:"Failure of another leader"},{heading:"reliability",content:`To show the behavior of the system over time as
failures are injected we ran a ZooKeeper service made up of
7 machines. We ran the same saturation benchmark as before,
but this time we kept the write percentage at a constant
30%, which is a conservative ratio of our expected
workloads.`},{heading:"reliability",content:`There are a few important observations from this graph. First, if
followers fail and recover quickly, then ZooKeeper is able to sustain a
high throughput despite the failure. But maybe more importantly, the
leader election algorithm allows for the system to recover fast enough
to prevent throughput from dropping substantially. In our observations,
ZooKeeper takes less than 200ms to elect a new leader. Third, as
followers recover, ZooKeeper is able to raise throughput again once they
start processing requests.`},{heading:"the-zookeeper-project",content:`ZooKeeper has been
successfully used
in many industrial applications. It is used at Yahoo! as the
coordination and failure recovery service for Yahoo! Message
Broker, which is a highly scalable publish-subscribe system
managing thousands of topics for replication and data
delivery. It is used by the Fetching Service for Yahoo!
crawler, where it also manages failure recovery. A number of
Yahoo! advertising systems also use ZooKeeper to implement
reliable services.`},{heading:"the-zookeeper-project",content:`All users and developers are encouraged to join the
community and contribute their expertise. See the
Zookeeper Project on Apache
for more information.`}],headings:[{id:"zookeeper-a-distributed-coordination-service-for-distributed-applications",content:"ZooKeeper: A Distributed Coordination Service for Distributed Applications"},{id:"design-goals",content:"Design Goals"},{id:"data-model-and-the-hierarchical-namespace",content:"Data model and the hierarchical namespace"},{id:"zookeepers-hierarchical-namespace",content:"ZooKeeper's Hierarchical Namespace"},{id:"nodes-and-ephemeral-nodes",content:"Nodes and ephemeral nodes"},{id:"conditional-updates-and-watches",content:"Conditional updates and watches"},{id:"guarantees",content:"Guarantees"},{id:"simple-api",content:"Simple API"},{id:"implementation",content:"Implementation"},{id:"uses",content:"Uses"},{id:"performance",content:"Performance"},{id:"reliability",content:"Reliability"},{id:"the-zookeeper-project",content:"The ZooKeeper Project"}]};const f="3.9.5 Overview",v="Official Apache ZooKeeper 3.9.5 documentation covering installation, configuration, the data model, client APIs, administration, and operations.",w=[{depth:2,url:"#zookeeper-a-distributed-coordination-service-for-distributed-applications",title:e.jsx(e.Fragment,{children:"ZooKeeper: A Distributed Coordination Service for Distributed Applications"})},{depth:3,url:"#design-goals",title:e.jsx(e.Fragment,{children:"Design Goals"})},{depth:3,url:"#data-model-and-the-hierarchical-namespace",title:e.jsx(e.Fragment,{children:"Data model and the hierarchical namespace"})},{depth:4,url:"#zookeepers-hierarchical-namespace",title:e.jsx(e.Fragment,{children:"ZooKeeper's Hierarchical Namespace"})},{depth:3,url:"#nodes-and-ephemeral-nodes",title:e.jsx(e.Fragment,{children:"Nodes and ephemeral nodes"})},{depth:3,url:"#conditional-updates-and-watches",title:e.jsx(e.Fragment,{children:"Conditional updates and watches"})},{depth:3,url:"#guarantees",title:e.jsx(e.Fragment,{children:"Guarantees"})},{depth:3,url:"#simple-api",title:e.jsx(e.Fragment,{children:"Simple API"})},{depth:3,url:"#implementation",title:e.jsx(e.Fragment,{children:"Implementation"})},{depth:3,url:"#uses",title:e.jsx(e.Fragment,{children:"Uses"})},{depth:3,url:"#performance",title:e.jsx(e.Fragment,{children:"Performance"})},{depth:3,url:"#reliability",title:e.jsx(e.Fragment,{children:"Reliability"})},{depth:3,url:"#the-zookeeper-project",title:e.jsx(e.Fragment,{children:"The ZooKeeper Project"})}];function o(n){const t={a:"a",em:"em",h2:"h2",h3:"h3",h4:"h4",img:"img",li:"li",ol:"ol",p:"p",strong:"strong",ul:"ul",...n.components},{Callout:a}=t;return a||c("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(t.h2,{id:"zookeeper-a-distributed-coordination-service-for-distributed-applications",children:"ZooKeeper: A Distributed Coordination Service for Distributed Applications"}),`
`,e.jsx(t.p,{children:`ZooKeeper is a distributed, open-source coordination service for
distributed applications. It exposes a simple set of primitives that
distributed applications can build upon to implement higher level services
for synchronization, configuration maintenance, and groups and naming. It
is designed to be easy to program to, and uses a data model styled after
the familiar directory tree structure of file systems. It runs in Java and
has bindings for both Java and C.`}),`
`,e.jsx(t.p,{children:`Coordination services are notoriously hard to get right. They are
especially prone to errors such as race conditions and deadlock. The
motivation behind ZooKeeper is to relieve distributed applications the
responsibility of implementing coordination services from scratch.`}),`
`,e.jsx(t.h3,{id:"design-goals",children:"Design Goals"}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"ZooKeeper is simple."}),` ZooKeeper
allows distributed processes to coordinate with each other through a
shared hierarchical namespace which is organized similarly to a standard
file system. The namespace consists of data registers - called znodes,
in ZooKeeper parlance - and these are similar to files and directories.
Unlike a typical file system, which is designed for storage, ZooKeeper
data is kept in-memory, which means ZooKeeper can achieve high
throughput and low latency numbers.`]}),`
`,e.jsx(t.p,{children:`The ZooKeeper implementation puts a premium on high performance,
highly available, strictly ordered access. The performance aspects of
ZooKeeper means it can be used in large, distributed systems. The
reliability aspects keep it from being a single point of failure. The
strict ordering means that sophisticated synchronization primitives can
be implemented at the client.`}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"ZooKeeper is replicated."}),` Like the
distributed processes it coordinates, ZooKeeper itself is intended to be
replicated over a set of hosts called an ensemble.`]}),`
`,e.jsx(t.p,{children:e.jsx(t.img,{alt:"ZooKeeper Service",src:i,placeholder:"blur"})}),`
`,e.jsx(t.p,{children:`The servers that make up the ZooKeeper service must all know about
each other. They maintain an in-memory image of state, along with a
transaction logs and snapshots in a persistent store. As long as a
majority of the servers are available, the ZooKeeper service will be
available.`}),`
`,e.jsx(t.p,{children:`Clients connect to a single ZooKeeper server. The client maintains
a TCP connection through which it sends requests, gets responses, gets
watch events, and sends heart beats. If the TCP connection to the server
breaks, the client will connect to a different server.`}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"ZooKeeper is ordered."}),` ZooKeeper
stamps each update with a number that reflects the order of all
ZooKeeper transactions. Subsequent operations can use the order to
implement higher-level abstractions, such as synchronization
primitives.`]}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"ZooKeeper is fast."}),` It is
especially fast in "read-dominant" workloads. ZooKeeper applications run
on thousands of machines, and it performs best where reads are more
common than writes, at ratios of around 10:1.`]}),`
`,e.jsx(t.h3,{id:"data-model-and-the-hierarchical-namespace",children:"Data model and the hierarchical namespace"}),`
`,e.jsx(t.p,{children:`The namespace provided by ZooKeeper is much like that of a
standard file system. A name is a sequence of path elements separated by
a slash (/). Every node in ZooKeeper's namespace is identified by a
path.`}),`
`,e.jsx(t.h4,{id:"zookeepers-hierarchical-namespace",children:"ZooKeeper's Hierarchical Namespace"}),`
`,e.jsx(t.p,{children:e.jsx(t.img,{alt:"ZooKeeper's Hierarchical Namespace",src:s,placeholder:"blur"})}),`
`,e.jsx(t.h3,{id:"nodes-and-ephemeral-nodes",children:"Nodes and ephemeral nodes"}),`
`,e.jsxs(t.p,{children:[`Unlike standard file systems, each node in a ZooKeeper
namespace can have data associated with it as well as children. It is
like having a file-system that allows a file to also be a directory.
(ZooKeeper was designed to store coordination data: status information,
configuration, location information, etc., so the data stored at each
node is usually small, in the byte to kilobyte range.) We use the term
`,e.jsx(t.em,{children:"znode"}),` to make it clear that we are talking about
ZooKeeper data nodes.`]}),`
`,e.jsx(t.p,{children:`Znodes maintain a stat structure that includes version numbers for
data changes, ACL changes, and timestamps, to allow cache validations
and coordinated updates. Each time a znode's data changes, the version
number increases. For instance, whenever a client retrieves data it also
receives the version of the data.`}),`
`,e.jsx(t.p,{children:`The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List (ACL)
that restricts who can do what.`}),`
`,e.jsx(t.p,{children:`ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When the
session ends the znode is deleted.`}),`
`,e.jsx(t.h3,{id:"conditional-updates-and-watches",children:"Conditional updates and watches"}),`
`,e.jsxs(t.p,{children:["ZooKeeper supports the concept of ",e.jsx(t.em,{children:"watches"}),`.
Clients can set a watch on a znode. A watch will be triggered and
removed when the znode changes. When a watch is triggered, the client
receives a packet saying that the znode has changed. If the
connection between the client and one of the ZooKeeper servers is
broken, the client will receive a local notification.`]}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"New in 3.6.0:"}),` Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.`]}),`
`,e.jsx(t.h3,{id:"guarantees",children:"Guarantees"}),`
`,e.jsx(t.p,{children:`ZooKeeper is very fast and very simple. Since its goal, though, is
to be a basis for the construction of more complicated services, such as
synchronization, it provides a set of guarantees. These are:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Sequential Consistency - Updates from a client will be applied
in the order that they were sent.`}),`
`,e.jsx(t.li,{children:`Atomicity - Updates either succeed or fail. No partial
results.`}),`
`,e.jsx(t.li,{children:`Single System Image - A client will see the same view of the
service regardless of the server that it connects to. i.e., a
client will never see an older view of the system even if the
client fails over to a different server with the same session.`}),`
`,e.jsx(t.li,{children:`Reliability - Once an update has been applied, it will persist
from that time forward until a client overwrites the update.`}),`
`,e.jsx(t.li,{children:`Timeliness - The clients view of the system is guaranteed to
be up-to-date within a certain time bound.`}),`
`]}),`
`,e.jsx(t.h3,{id:"simple-api",children:"Simple API"}),`
`,e.jsx(t.p,{children:`One of the design goals of ZooKeeper is providing a very simple
programming interface. As a result, it supports only these
operations:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"create"})," : creates a node at a location in the tree"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"delete"})," : deletes a node"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"exists"})," : tests if a node exists at a location"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"get data"})," : reads the data from a node"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"set data"})," : writes data to a node"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"get children"})," : retrieves a list of children of a node"]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"sync"})," : waits for data to be propagated"]}),`
`]}),`
`,e.jsx(t.h3,{id:"implementation",children:"Implementation"}),`
`,e.jsx(t.p,{children:`ZooKeeper Components shows the high-level components
of the ZooKeeper service. With the exception of the request processor,
each of
the servers that make up the ZooKeeper service replicates its own copy
of each of the components.`}),`
`,e.jsx(t.p,{children:e.jsx(t.img,{alt:"ZooKeeper Components",src:r,placeholder:"blur"})}),`
`,e.jsx(t.p,{children:`The replicated database is an in-memory database containing the
entire data tree. Updates are logged to disk for recoverability, and
writes are serialized to disk before they are applied to the in-memory
database.`}),`
`,e.jsx(t.p,{children:`Every ZooKeeper server services clients. Clients connect to
exactly one server to submit requests. Read requests are serviced from
the local replica of each server database. Requests that change the
state of the service, write requests, are processed by an agreement
protocol.`}),`
`,e.jsxs(t.p,{children:[`As part of the agreement protocol all write requests from clients
are forwarded to a single server, called the
`,e.jsx(t.em,{children:"leader"}),`. The rest of the ZooKeeper servers, called
`,e.jsx(t.em,{children:"followers"}),`, receive message proposals from the
leader and agree upon message delivery. The messaging layer takes care
of replacing leaders on failures and syncing followers with
leaders.`]}),`
`,e.jsx(t.p,{children:`ZooKeeper uses a custom atomic messaging protocol. Since the
messaging layer is atomic, ZooKeeper can guarantee that the local
replicas never diverge. When the leader receives a write request, it
calculates what the state of the system is when the write is to be
applied and transforms this into a transaction that captures this new
state.`}),`
`,e.jsx(t.h3,{id:"uses",children:"Uses"}),`
`,e.jsx(t.p,{children:`The programming interface to ZooKeeper is deliberately simple.
With it, however, you can implement higher order operations, such as
synchronizations primitives, group membership, ownership, etc.`}),`
`,e.jsx(t.h3,{id:"performance",children:"Performance"}),`
`,e.jsx(t.p,{children:`ZooKeeper is designed to be highly performance. But is it? The
results of the ZooKeeper's development team at Yahoo! Research indicate
that it is. It is especially high
performance in applications where reads outnumber writes, since writes
involve synchronizing the state of all servers. (Reads outnumbering
writes is typically the case for a coordination service.)`}),`
`,e.jsx(t.p,{children:e.jsx(t.img,{alt:"ZooKeeper Throughput as the Read-Write Ratio Varies",src:l,placeholder:"blur"})}),`
`,e.jsx(t.p,{children:`The "ZooKeeper Throughput as the Read-Write Ratio Varies" is a throughput
graph of ZooKeeper release 3.2 running on servers with dual 2Ghz
Xeon and two SATA 15K RPM drives. One drive was used as a
dedicated ZooKeeper log device. The snapshots were written to
the OS drive. Write requests were 1K writes and the reads were
1K reads. "Servers" indicate the size of the ZooKeeper
ensemble, the number of servers that make up the
service. Approximately 30 other servers were used to simulate
the clients. The ZooKeeper ensemble was configured such that
leaders do not allow connections from clients.`}),`
`,e.jsx(a,{type:"info",children:e.jsx(t.p,{children:"In version 3.2 r/w performance improved by ~2x compared to"})}),`
`,e.jsxs(t.p,{children:["the ",e.jsx(t.a,{href:"http://zookeeper.apache.org/docs/r3.1.1/zookeeperOver.html#Performance",children:"previous 3.1 release"}),"."]}),`
`,e.jsxs(t.p,{children:[`Benchmarks also indicate that it is reliable, too.
`,e.jsx(t.a,{href:"#reliability",children:"Reliability in the Presence of Errors"}),` shows how a deployment responds to
various failures. The events marked in the figure are the following:`]}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsx(t.li,{children:"Failure and recovery of a follower"}),`
`,e.jsx(t.li,{children:"Failure and recovery of a different follower"}),`
`,e.jsx(t.li,{children:"Failure of the leader"}),`
`,e.jsx(t.li,{children:"Failure and recovery of two followers"}),`
`,e.jsx(t.li,{children:"Failure of another leader"}),`
`]}),`
`,e.jsx(t.h3,{id:"reliability",children:"Reliability"}),`
`,e.jsx(t.p,{children:`To show the behavior of the system over time as
failures are injected we ran a ZooKeeper service made up of
7 machines. We ran the same saturation benchmark as before,
but this time we kept the write percentage at a constant
30%, which is a conservative ratio of our expected
workloads.`}),`
`,e.jsx(t.p,{children:e.jsx(t.img,{alt:"Reliability in the Presence of Errors",src:h,placeholder:"blur"})}),`
`,e.jsx(t.p,{children:`There are a few important observations from this graph. First, if
followers fail and recover quickly, then ZooKeeper is able to sustain a
high throughput despite the failure. But maybe more importantly, the
leader election algorithm allows for the system to recover fast enough
to prevent throughput from dropping substantially. In our observations,
ZooKeeper takes less than 200ms to elect a new leader. Third, as
followers recover, ZooKeeper is able to raise throughput again once they
start processing requests.`}),`
`,e.jsx(t.h3,{id:"the-zookeeper-project",children:"The ZooKeeper Project"}),`
`,e.jsxs(t.p,{children:[`ZooKeeper has been
`,e.jsx(t.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/PoweredBy",children:"successfully used"}),`
in many industrial applications. It is used at Yahoo! as the
coordination and failure recovery service for Yahoo! Message
Broker, which is a highly scalable publish-subscribe system
managing thousands of topics for replication and data
delivery. It is used by the Fetching Service for Yahoo!
crawler, where it also manages failure recovery. A number of
Yahoo! advertising systems also use ZooKeeper to implement
reliable services.`]}),`
`,e.jsxs(t.p,{children:[`All users and developers are encouraged to join the
community and contribute their expertise. See the
`,e.jsx(t.a,{href:"https://zookeeper.apache.org/",children:"Zookeeper Project on Apache"}),`
for more information.`]})]})}function y(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(o,{...n})}):o(n)}function c(n,t){throw new Error("Expected component `"+n+"` to be defined: you likely forgot to import, pass, or provide it.")}export{p as _markdown,y as default,u as extractedReferences,m as frontmatter,v as pageDescription,f as pageTitle,g as structuredData,w as toc};
