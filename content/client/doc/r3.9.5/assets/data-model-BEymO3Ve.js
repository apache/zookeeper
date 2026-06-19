import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let r=`ZooKeeper has a hierarchal namespace, much like a distributed file
system. The only difference is that each node in the namespace can have
data associated with it as well as children. It is like having a file
system that allows a file to also be a directory. Paths to nodes are
always expressed as canonical, absolute, slash-separated paths; there are
no relative reference. Any unicode character can be used in a path subject
to the following constraints:

* The null character (\\u0000) cannot be part of a path name. (This
  causes problems with the C binding.)
* The following characters can't be used because they don't
  display well, or render in confusing ways: \\u0001 - \\u001F and \\u007F
* \\u009F.
* The following characters are not allowed: \\ud800 - uF8FF,
  \\uFFF0 - uFFFF.
* The "." character can be used as part of another name, but "."
  and ".." cannot alone be used to indicate a node along a path,
  because ZooKeeper doesn't use relative paths. The following would be
  invalid: "/a/b/./c" or "/a/b/../c".
* The token "zookeeper" is reserved.

## ZNodes

Every node in a ZooKeeper tree is referred to as a
*znode*. Znodes maintain a stat structure that
includes version numbers for data changes, acl changes. The stat
structure also has timestamps. The version number, together with the
timestamp, allows ZooKeeper to validate the cache and to coordinate
updates. Each time a znode's data changes, the version number increases.
For instance, whenever a client retrieves data, it also receives the
version of the data. And when a client performs an update or a delete,
it must supply the version of the data of the znode it is changing. If
the version it supplies doesn't match the actual version of the data,
the update will fail. (This behavior can be overridden.)

<Callout type="info">
  In distributed application engineering, the words *node* can refer to a
  generic host machine, a server, a member of an ensemble, a client process,
  etc. In the ZooKeeper documentation, *znodes* refer to the data nodes.
  *Servers* refers to machines that make up the ZooKeeper service; *quorum
  peers* refer to the servers that make up an ensemble; client refers to any
  host or process which uses a ZooKeeper service.
</Callout>

Znodes are the main entity that a programmer access. They have
several characteristics that are worth mentioning here.

### Watches

Clients can set watches on znodes. Changes to that znode trigger
the watch and then clear the watch. When a watch triggers, ZooKeeper
sends the client a notification. More information about watches can be
found in the section
[ZooKeeper Watches](/developer/programmers-guide/watches).

### Data Access

The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List
(ACL) that restricts who can do what.

ZooKeeper was not designed to be a general database or large
object store. Instead, it manages coordination data. This data can
come in the form of configuration, status information, rendezvous, etc.
A common property of the various forms of coordination data is that
they are relatively small: measured in kilobytes.
The ZooKeeper client and the server implementations have sanity checks
to ensure that znodes have less than 1M of data, but the data should
be much less than that on average. Operating on relatively large data
sizes will cause some operations to take much more time than others and
will affect the latencies of some operations because of the extra time
needed to move more data over the network and onto storage media. If
large data storage is needed, the usual pattern of dealing with such
data is to store it on a bulk storage system, such as NFS or HDFS, and
store pointers to the storage locations in ZooKeeper.

### Ephemeral Nodes

ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When
the session ends the znode is deleted. Because of this behavior
ephemeral znodes are not allowed to have children. The list of ephemerals
for the session can be retrieved using **getEphemerals()** api.

#### getEphemerals()

Retrieves the list of ephemeral nodes created by the session for the
given path. If the path is empty, it will list all the ephemeral nodes
for the session.
**Use Case** - A sample use case might be, if the list of ephemeral
nodes for the session needs to be collected for duplicate data entry check
and the nodes are created in a sequential manner so you do not know the name
for duplicate check. In that case, getEphemerals() api could be used to
get the list of nodes for the session. This might be a typical use case
for service discovery.

### Sequence Nodes — Unique Naming

When creating a znode you can also request that
ZooKeeper append a monotonically increasing counter to the end
of path. This counter is unique to the parent znode. The
counter has a format of %010d — that is 10 digits with 0
(zero) padding (the counter is formatted in this way to
simplify sorting), i.e. "\`<path>0000000001\`". See
[Queue
Recipe](/developer/recipes#queues) for an example use of this feature. Note: the
counter used to store the next sequence number is a signed int
(4bytes) maintained by the parent node, the counter will
overflow when incremented beyond 2147483647 (resulting in a
name "\`<path>-2147483648\`").

### Container Nodes

#### Added in 3.5.3

ZooKeeper has the notion of container znodes. Container znodes are
special purpose znodes useful for recipes such as leader, lock, etc.
When the last child of a container is deleted, the container becomes
a candidate to be deleted by the server at some point in the future.

Given this property, you should be prepared to get
KeeperException.NoNodeException when creating children inside of
container znodes. i.e. when creating child znodes inside of container znodes
always check for KeeperException.NoNodeException and recreate the container
znode when it occurs.

### TTL Nodes

#### Added in 3.5.3

When creating PERSISTENT or PERSISTENT\\_SEQUENTIAL znodes,
you can optionally set a TTL in milliseconds for the znode. If the znode
is not modified within the TTL and has no children it will become a candidate
to be deleted by the server at some point in the future.

Note: TTL Nodes must be enabled via System property as they
are disabled by default. See the [Administrator's Guide](/admin-ops/administrators-guide/configuration-parameters#advanced-configuration) for
details. If you attempt to create TTL Nodes without the
proper System property set the server will throw
KeeperException.UnimplementedException.

## Time in ZooKeeper

ZooKeeper tracks time multiple ways:

* **Zxid**
  Every change to the ZooKeeper state receives a stamp in the
  form of a *zxid* (ZooKeeper Transaction Id).
  This exposes the total ordering of all changes to ZooKeeper. Each
  change will have a unique zxid and if zxid1 is smaller than zxid2
  then zxid1 happened before zxid2.
* **Version numbers**
  Every change to a node will cause an increase to one of the
  version numbers of that node. The three version numbers are version
  (number of changes to the data of a znode), cversion (number of
  changes to the children of a znode), and aversion (number of changes
  to the ACL of a znode).
* **Ticks**
  When using multi-server ZooKeeper, servers use ticks to define
  timing of events such as status uploads, session timeouts,
  connection timeouts between peers, etc. The tick time is only
  indirectly exposed through the minimum session timeout (2 times the
  tick time); if a client requests a session timeout less than the
  minimum session timeout, the server will tell the client that the
  session timeout is actually the minimum session timeout.
* **Real time**
  ZooKeeper doesn't use real time, or clock time, at all except
  to put timestamps into the stat structure on znode creation and
  znode modification.

## ZooKeeper Stat Structure

The Stat structure for each znode in ZooKeeper is made up of the
following fields:

* **czxid**
  The zxid of the change that caused this znode to be
  created.
* **mzxid**
  The zxid of the change that last modified this znode.
* **pzxid**
  The zxid of the change that last modified children of this znode.
* **ctime**
  The time in milliseconds from epoch when this znode was
  created.
* **mtime**
  The time in milliseconds from epoch when this znode was last
  modified.
* **version**
  The number of changes to the data of this znode.
* **cversion**
  The number of changes to the children of this znode.
* **aversion**
  The number of changes to the ACL of this znode.
* **ephemeralOwner**
  The session id of the owner of this znode if the znode is an
  ephemeral node. If it is not an ephemeral node, it will be
  zero.
* **dataLength**
  The length of the data field of this znode.
* **numChildren**
  The number of children of this znode.
`,h={title:"Data Model",description:"Explains ZooKeeper's hierarchical namespace of znodes, including data access rules, ephemeral and sequence nodes, container nodes, TTL nodes, and the stat structure."},d=[{href:"/developer/programmers-guide/watches"},{href:"/developer/recipes#queues"},{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration"}],c={contents:[{heading:void 0,content:`ZooKeeper has a hierarchal namespace, much like a distributed file
system. The only difference is that each node in the namespace can have
data associated with it as well as children. It is like having a file
system that allows a file to also be a directory. Paths to nodes are
always expressed as canonical, absolute, slash-separated paths; there are
no relative reference. Any unicode character can be used in a path subject
to the following constraints:`},{heading:void 0,content:`The null character (\\u0000) cannot be part of a path name. (This
causes problems with the C binding.)`},{heading:void 0,content:`The following characters can't be used because they don't
display well, or render in confusing ways: \\u0001 - \\u001F and \\u007F`},{heading:void 0,content:"\\u009F."},{heading:void 0,content:`The following characters are not allowed: \\ud800 - uF8FF,
\\uFFF0 - uFFFF.`},{heading:void 0,content:`The "." character can be used as part of another name, but "."
and ".." cannot alone be used to indicate a node along a path,
because ZooKeeper doesn't use relative paths. The following would be
invalid: "/a/b/./c" or "/a/b/../c".`},{heading:void 0,content:'The token "zookeeper" is reserved.'},{heading:"znodes",content:`Every node in a ZooKeeper tree is referred to as a
znode. Znodes maintain a stat structure that
includes version numbers for data changes, acl changes. The stat
structure also has timestamps. The version number, together with the
timestamp, allows ZooKeeper to validate the cache and to coordinate
updates. Each time a znode's data changes, the version number increases.
For instance, whenever a client retrieves data, it also receives the
version of the data. And when a client performs an update or a delete,
it must supply the version of the data of the znode it is changing. If
the version it supplies doesn't match the actual version of the data,
the update will fail. (This behavior can be overridden.)`},{heading:"znodes",content:"type: info"},{heading:"znodes",content:`In distributed application engineering, the words node can refer to a
generic host machine, a server, a member of an ensemble, a client process,
etc. In the ZooKeeper documentation, znodes refer to the data nodes.
Servers refers to machines that make up the ZooKeeper service; quorum
peers refer to the servers that make up an ensemble; client refers to any
host or process which uses a ZooKeeper service.`},{heading:"znodes",content:`Znodes are the main entity that a programmer access. They have
several characteristics that are worth mentioning here.`},{heading:"watches",content:`Clients can set watches on znodes. Changes to that znode trigger
the watch and then clear the watch. When a watch triggers, ZooKeeper
sends the client a notification. More information about watches can be
found in the section
ZooKeeper Watches.`},{heading:"data-access",content:`The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List
(ACL) that restricts who can do what.`},{heading:"data-access",content:`ZooKeeper was not designed to be a general database or large
object store. Instead, it manages coordination data. This data can
come in the form of configuration, status information, rendezvous, etc.
A common property of the various forms of coordination data is that
they are relatively small: measured in kilobytes.
The ZooKeeper client and the server implementations have sanity checks
to ensure that znodes have less than 1M of data, but the data should
be much less than that on average. Operating on relatively large data
sizes will cause some operations to take much more time than others and
will affect the latencies of some operations because of the extra time
needed to move more data over the network and onto storage media. If
large data storage is needed, the usual pattern of dealing with such
data is to store it on a bulk storage system, such as NFS or HDFS, and
store pointers to the storage locations in ZooKeeper.`},{heading:"ephemeral-nodes",content:`ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When
the session ends the znode is deleted. Because of this behavior
ephemeral znodes are not allowed to have children. The list of ephemerals
for the session can be retrieved using getEphemerals() api.`},{heading:"getephemerals",content:`Retrieves the list of ephemeral nodes created by the session for the
given path. If the path is empty, it will list all the ephemeral nodes
for the session.
Use Case - A sample use case might be, if the list of ephemeral
nodes for the session needs to be collected for duplicate data entry check
and the nodes are created in a sequential manner so you do not know the name
for duplicate check. In that case, getEphemerals() api could be used to
get the list of nodes for the session. This might be a typical use case
for service discovery.`},{heading:"sequence-nodes--unique-naming",content:`When creating a znode you can also request that
ZooKeeper append a monotonically increasing counter to the end
of path. This counter is unique to the parent znode. The
counter has a format of %010d — that is 10 digits with 0
(zero) padding (the counter is formatted in this way to
simplify sorting), i.e. "<path>0000000001". See
Queue
Recipe for an example use of this feature. Note: the
counter used to store the next sequence number is a signed int
(4bytes) maintained by the parent node, the counter will
overflow when incremented beyond 2147483647 (resulting in a
name "<path>-2147483648").`},{heading:"added-in-353",content:`ZooKeeper has the notion of container znodes. Container znodes are
special purpose znodes useful for recipes such as leader, lock, etc.
When the last child of a container is deleted, the container becomes
a candidate to be deleted by the server at some point in the future.`},{heading:"added-in-353",content:`Given this property, you should be prepared to get
KeeperException.NoNodeException when creating children inside of
container znodes. i.e. when creating child znodes inside of container znodes
always check for KeeperException.NoNodeException and recreate the container
znode when it occurs.`},{heading:"added-in-353-1",content:`When creating PERSISTENT or PERSISTENT_SEQUENTIAL znodes,
you can optionally set a TTL in milliseconds for the znode. If the znode
is not modified within the TTL and has no children it will become a candidate
to be deleted by the server at some point in the future.`},{heading:"added-in-353-1",content:`Note: TTL Nodes must be enabled via System property as they
are disabled by default. See the Administrator's Guide for
details. If you attempt to create TTL Nodes without the
proper System property set the server will throw
KeeperException.UnimplementedException.`},{heading:"time-in-zookeeper",content:"ZooKeeper tracks time multiple ways:"},{heading:"time-in-zookeeper",content:`Zxid
Every change to the ZooKeeper state receives a stamp in the
form of a zxid (ZooKeeper Transaction Id).
This exposes the total ordering of all changes to ZooKeeper. Each
change will have a unique zxid and if zxid1 is smaller than zxid2
then zxid1 happened before zxid2.`},{heading:"time-in-zookeeper",content:`Version numbers
Every change to a node will cause an increase to one of the
version numbers of that node. The three version numbers are version
(number of changes to the data of a znode), cversion (number of
changes to the children of a znode), and aversion (number of changes
to the ACL of a znode).`},{heading:"time-in-zookeeper",content:`Ticks
When using multi-server ZooKeeper, servers use ticks to define
timing of events such as status uploads, session timeouts,
connection timeouts between peers, etc. The tick time is only
indirectly exposed through the minimum session timeout (2 times the
tick time); if a client requests a session timeout less than the
minimum session timeout, the server will tell the client that the
session timeout is actually the minimum session timeout.`},{heading:"time-in-zookeeper",content:`Real time
ZooKeeper doesn't use real time, or clock time, at all except
to put timestamps into the stat structure on znode creation and
znode modification.`},{heading:"zookeeper-stat-structure",content:`The Stat structure for each znode in ZooKeeper is made up of the
following fields:`},{heading:"zookeeper-stat-structure",content:`czxid
The zxid of the change that caused this znode to be
created.`},{heading:"zookeeper-stat-structure",content:`mzxid
The zxid of the change that last modified this znode.`},{heading:"zookeeper-stat-structure",content:`pzxid
The zxid of the change that last modified children of this znode.`},{heading:"zookeeper-stat-structure",content:`ctime
The time in milliseconds from epoch when this znode was
created.`},{heading:"zookeeper-stat-structure",content:`mtime
The time in milliseconds from epoch when this znode was last
modified.`},{heading:"zookeeper-stat-structure",content:`version
The number of changes to the data of this znode.`},{heading:"zookeeper-stat-structure",content:`cversion
The number of changes to the children of this znode.`},{heading:"zookeeper-stat-structure",content:`aversion
The number of changes to the ACL of this znode.`},{heading:"zookeeper-stat-structure",content:`ephemeralOwner
The session id of the owner of this znode if the znode is an
ephemeral node. If it is not an ephemeral node, it will be
zero.`},{heading:"zookeeper-stat-structure",content:`dataLength
The length of the data field of this znode.`},{heading:"zookeeper-stat-structure",content:`numChildren
The number of children of this znode.`}],headings:[{id:"znodes",content:"ZNodes"},{id:"watches",content:"Watches"},{id:"data-access",content:"Data Access"},{id:"ephemeral-nodes",content:"Ephemeral Nodes"},{id:"getephemerals",content:"getEphemerals()"},{id:"sequence-nodes--unique-naming",content:"Sequence Nodes — Unique Naming"},{id:"container-nodes",content:"Container Nodes"},{id:"added-in-353",content:"Added in 3.5.3"},{id:"ttl-nodes",content:"TTL Nodes"},{id:"added-in-353-1",content:"Added in 3.5.3"},{id:"time-in-zookeeper",content:"Time in ZooKeeper"},{id:"zookeeper-stat-structure",content:"ZooKeeper Stat Structure"}]};const l=[{depth:2,url:"#znodes",title:e.jsx(e.Fragment,{children:"ZNodes"})},{depth:3,url:"#watches",title:e.jsx(e.Fragment,{children:"Watches"})},{depth:3,url:"#data-access",title:e.jsx(e.Fragment,{children:"Data Access"})},{depth:3,url:"#ephemeral-nodes",title:e.jsx(e.Fragment,{children:"Ephemeral Nodes"})},{depth:4,url:"#getephemerals",title:e.jsx(e.Fragment,{children:"getEphemerals()"})},{depth:3,url:"#sequence-nodes--unique-naming",title:e.jsx(e.Fragment,{children:"Sequence Nodes — Unique Naming"})},{depth:3,url:"#container-nodes",title:e.jsx(e.Fragment,{children:"Container Nodes"})},{depth:4,url:"#added-in-353",title:e.jsx(e.Fragment,{children:"Added in 3.5.3"})},{depth:3,url:"#ttl-nodes",title:e.jsx(e.Fragment,{children:"TTL Nodes"})},{depth:4,url:"#added-in-353-1",title:e.jsx(e.Fragment,{children:"Added in 3.5.3"})},{depth:2,url:"#time-in-zookeeper",title:e.jsx(e.Fragment,{children:"Time in ZooKeeper"})},{depth:2,url:"#zookeeper-stat-structure",title:e.jsx(e.Fragment,{children:"ZooKeeper Stat Structure"})}];function a(t){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",h4:"h4",li:"li",p:"p",strong:"strong",ul:"ul",...t.components},{Callout:o}=n;return o||s("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`ZooKeeper has a hierarchal namespace, much like a distributed file
system. The only difference is that each node in the namespace can have
data associated with it as well as children. It is like having a file
system that allows a file to also be a directory. Paths to nodes are
always expressed as canonical, absolute, slash-separated paths; there are
no relative reference. Any unicode character can be used in a path subject
to the following constraints:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`The null character (\\u0000) cannot be part of a path name. (This
causes problems with the C binding.)`}),`
`,e.jsx(n.li,{children:`The following characters can't be used because they don't
display well, or render in confusing ways: \\u0001 - \\u001F and \\u007F`}),`
`,e.jsx(n.li,{children:"\\u009F."}),`
`,e.jsx(n.li,{children:`The following characters are not allowed: \\ud800 - uF8FF,
\\uFFF0 - uFFFF.`}),`
`,e.jsx(n.li,{children:`The "." character can be used as part of another name, but "."
and ".." cannot alone be used to indicate a node along a path,
because ZooKeeper doesn't use relative paths. The following would be
invalid: "/a/b/./c" or "/a/b/../c".`}),`
`,e.jsx(n.li,{children:'The token "zookeeper" is reserved.'}),`
`]}),`
`,e.jsx(n.h2,{id:"znodes",children:"ZNodes"}),`
`,e.jsxs(n.p,{children:[`Every node in a ZooKeeper tree is referred to as a
`,e.jsx(n.em,{children:"znode"}),`. Znodes maintain a stat structure that
includes version numbers for data changes, acl changes. The stat
structure also has timestamps. The version number, together with the
timestamp, allows ZooKeeper to validate the cache and to coordinate
updates. Each time a znode's data changes, the version number increases.
For instance, whenever a client retrieves data, it also receives the
version of the data. And when a client performs an update or a delete,
it must supply the version of the data of the znode it is changing. If
the version it supplies doesn't match the actual version of the data,
the update will fail. (This behavior can be overridden.)`]}),`
`,e.jsx(o,{type:"info",children:e.jsxs(n.p,{children:["In distributed application engineering, the words ",e.jsx(n.em,{children:"node"}),` can refer to a
generic host machine, a server, a member of an ensemble, a client process,
etc. In the ZooKeeper documentation, `,e.jsx(n.em,{children:"znodes"}),` refer to the data nodes.
`,e.jsx(n.em,{children:"Servers"})," refers to machines that make up the ZooKeeper service; ",e.jsx(n.em,{children:`quorum
peers`}),` refer to the servers that make up an ensemble; client refers to any
host or process which uses a ZooKeeper service.`]})}),`
`,e.jsx(n.p,{children:`Znodes are the main entity that a programmer access. They have
several characteristics that are worth mentioning here.`}),`
`,e.jsx(n.h3,{id:"watches",children:"Watches"}),`
`,e.jsxs(n.p,{children:[`Clients can set watches on znodes. Changes to that znode trigger
the watch and then clear the watch. When a watch triggers, ZooKeeper
sends the client a notification. More information about watches can be
found in the section
`,e.jsx(n.a,{href:"/developer/programmers-guide/watches",children:"ZooKeeper Watches"}),"."]}),`
`,e.jsx(n.h3,{id:"data-access",children:"Data Access"}),`
`,e.jsx(n.p,{children:`The data stored at each znode in a namespace is read and written
atomically. Reads get all the data bytes associated with a znode and a
write replaces all the data. Each node has an Access Control List
(ACL) that restricts who can do what.`}),`
`,e.jsx(n.p,{children:`ZooKeeper was not designed to be a general database or large
object store. Instead, it manages coordination data. This data can
come in the form of configuration, status information, rendezvous, etc.
A common property of the various forms of coordination data is that
they are relatively small: measured in kilobytes.
The ZooKeeper client and the server implementations have sanity checks
to ensure that znodes have less than 1M of data, but the data should
be much less than that on average. Operating on relatively large data
sizes will cause some operations to take much more time than others and
will affect the latencies of some operations because of the extra time
needed to move more data over the network and onto storage media. If
large data storage is needed, the usual pattern of dealing with such
data is to store it on a bulk storage system, such as NFS or HDFS, and
store pointers to the storage locations in ZooKeeper.`}),`
`,e.jsx(n.h3,{id:"ephemeral-nodes",children:"Ephemeral Nodes"}),`
`,e.jsxs(n.p,{children:[`ZooKeeper also has the notion of ephemeral nodes. These znodes
exists as long as the session that created the znode is active. When
the session ends the znode is deleted. Because of this behavior
ephemeral znodes are not allowed to have children. The list of ephemerals
for the session can be retrieved using `,e.jsx(n.strong,{children:"getEphemerals()"})," api."]}),`
`,e.jsx(n.h4,{id:"getephemerals",children:"getEphemerals()"}),`
`,e.jsxs(n.p,{children:[`Retrieves the list of ephemeral nodes created by the session for the
given path. If the path is empty, it will list all the ephemeral nodes
for the session.
`,e.jsx(n.strong,{children:"Use Case"}),` - A sample use case might be, if the list of ephemeral
nodes for the session needs to be collected for duplicate data entry check
and the nodes are created in a sequential manner so you do not know the name
for duplicate check. In that case, getEphemerals() api could be used to
get the list of nodes for the session. This might be a typical use case
for service discovery.`]}),`
`,e.jsx(n.h3,{id:"sequence-nodes--unique-naming",children:"Sequence Nodes — Unique Naming"}),`
`,e.jsxs(n.p,{children:[`When creating a znode you can also request that
ZooKeeper append a monotonically increasing counter to the end
of path. This counter is unique to the parent znode. The
counter has a format of %010d — that is 10 digits with 0
(zero) padding (the counter is formatted in this way to
simplify sorting), i.e. "`,e.jsx(n.code,{children:"<path>0000000001"}),`". See
`,e.jsx(n.a,{href:"/developer/recipes#queues",children:`Queue
Recipe`}),` for an example use of this feature. Note: the
counter used to store the next sequence number is a signed int
(4bytes) maintained by the parent node, the counter will
overflow when incremented beyond 2147483647 (resulting in a
name "`,e.jsx(n.code,{children:"<path>-2147483648"}),'").']}),`
`,e.jsx(n.h3,{id:"container-nodes",children:"Container Nodes"}),`
`,e.jsx(n.h4,{id:"added-in-353",children:"Added in 3.5.3"}),`
`,e.jsx(n.p,{children:`ZooKeeper has the notion of container znodes. Container znodes are
special purpose znodes useful for recipes such as leader, lock, etc.
When the last child of a container is deleted, the container becomes
a candidate to be deleted by the server at some point in the future.`}),`
`,e.jsx(n.p,{children:`Given this property, you should be prepared to get
KeeperException.NoNodeException when creating children inside of
container znodes. i.e. when creating child znodes inside of container znodes
always check for KeeperException.NoNodeException and recreate the container
znode when it occurs.`}),`
`,e.jsx(n.h3,{id:"ttl-nodes",children:"TTL Nodes"}),`
`,e.jsx(n.h4,{id:"added-in-353-1",children:"Added in 3.5.3"}),`
`,e.jsx(n.p,{children:`When creating PERSISTENT or PERSISTENT_SEQUENTIAL znodes,
you can optionally set a TTL in milliseconds for the znode. If the znode
is not modified within the TTL and has no children it will become a candidate
to be deleted by the server at some point in the future.`}),`
`,e.jsxs(n.p,{children:[`Note: TTL Nodes must be enabled via System property as they
are disabled by default. See the `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration",children:"Administrator's Guide"}),` for
details. If you attempt to create TTL Nodes without the
proper System property set the server will throw
KeeperException.UnimplementedException.`]}),`
`,e.jsx(n.h2,{id:"time-in-zookeeper",children:"Time in ZooKeeper"}),`
`,e.jsx(n.p,{children:"ZooKeeper tracks time multiple ways:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Zxid"}),`
Every change to the ZooKeeper state receives a stamp in the
form of a `,e.jsx(n.em,{children:"zxid"}),` (ZooKeeper Transaction Id).
This exposes the total ordering of all changes to ZooKeeper. Each
change will have a unique zxid and if zxid1 is smaller than zxid2
then zxid1 happened before zxid2.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Version numbers"}),`
Every change to a node will cause an increase to one of the
version numbers of that node. The three version numbers are version
(number of changes to the data of a znode), cversion (number of
changes to the children of a znode), and aversion (number of changes
to the ACL of a znode).`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Ticks"}),`
When using multi-server ZooKeeper, servers use ticks to define
timing of events such as status uploads, session timeouts,
connection timeouts between peers, etc. The tick time is only
indirectly exposed through the minimum session timeout (2 times the
tick time); if a client requests a session timeout less than the
minimum session timeout, the server will tell the client that the
session timeout is actually the minimum session timeout.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"Real time"}),`
ZooKeeper doesn't use real time, or clock time, at all except
to put timestamps into the stat structure on znode creation and
znode modification.`]}),`
`]}),`
`,e.jsx(n.h2,{id:"zookeeper-stat-structure",children:"ZooKeeper Stat Structure"}),`
`,e.jsx(n.p,{children:`The Stat structure for each znode in ZooKeeper is made up of the
following fields:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"czxid"}),`
The zxid of the change that caused this znode to be
created.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"mzxid"}),`
The zxid of the change that last modified this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"pzxid"}),`
The zxid of the change that last modified children of this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"ctime"}),`
The time in milliseconds from epoch when this znode was
created.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"mtime"}),`
The time in milliseconds from epoch when this znode was last
modified.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"version"}),`
The number of changes to the data of this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"cversion"}),`
The number of changes to the children of this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"aversion"}),`
The number of changes to the ACL of this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"ephemeralOwner"}),`
The session id of the owner of this znode if the znode is an
ephemeral node. If it is not an ephemeral node, it will be
zero.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"dataLength"}),`
The length of the data field of this znode.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"numChildren"}),`
The number of children of this znode.`]}),`
`]})]})}function u(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(a,{...t})}):a(t)}function s(t,n){throw new Error("Expected component `"+t+"` to be defined: you likely forgot to import, pass, or provide it.")}export{r as _markdown,u as default,d as extractedReferences,h as frontmatter,c as structuredData,l as toc};
