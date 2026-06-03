import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let i=`All of the read operations in ZooKeeper - **getData()**, **getChildren()**, and **exists()** - have the option of setting a watch as a
side effect. Here is ZooKeeper's definition of a watch: a watch event is
one-time trigger, sent to the client that set the watch, which occurs when
the data for which the watch was set changes. There are three key points
to consider in this definition of a watch:

* **One-time trigger**
  One watch event will be sent to the client when the data has changed.
  For example, if a client does a getData("/znode1", true) and later the
  data for /znode1 is changed or deleted, the client will get a watch
  event for /znode1. If /znode1 changes again, no watch event will be
  sent unless the client has done another read that sets a new
  watch.
* **Sent to the client**
  This implies that an event is on the way to the client, but may
  not reach the client before the successful return code to the change
  operation reaches the client that initiated the change. Watches are
  sent asynchronously to watchers. ZooKeeper provides an ordering
  guarantee: a client will never see a change for which it has set a
  watch until it first sees the watch event. Network delays or other
  factors may cause different clients to see watches and return codes
  from updates at different times. The key point is that everything seen
  by the different clients will have a consistent order.
* **The data for which the watch was
  set**
  This refers to the different ways a node can change. It
  helps to think of ZooKeeper as maintaining two lists of
  watches: data watches and child watches. getData() and
  exists() set data watches. getChildren() sets child
  watches. Alternatively, it may help to think of watches being
  set according to the kind of data returned. getData() and
  exists() return information about the data of the node,
  whereas getChildren() returns a list of children. Thus,
  setData() will trigger data watches for the znode being set
  (assuming the set is successful). A successful create() will
  trigger a data watch for the znode being created and a child
  watch for the parent znode. A successful delete() will trigger
  both a data watch and a child watch (since there can be no
  more children) for a znode being deleted as well as a child
  watch for the parent znode.

Watches are maintained locally at the ZooKeeper server to which the
client is connected. This allows watches to be lightweight to set,
maintain, and dispatch. When a client connects to a new server, the watch
will be triggered for any session events. Watches will not be received
while disconnected from a server. When a client reconnects, any previously
registered watches will be reregistered and triggered if needed. In
general this all occurs transparently. There is one case where a watch
may be missed: a watch for the existence of a znode not yet created will
be missed if the znode is created and deleted while disconnected.

**New in 3.6.0:** Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.

## Semantics of Watches

We can set watches with the three calls that read the state of
ZooKeeper: exists, getData, and getChildren. The following list details
the events that a watch can trigger and the calls that enable them:

* **Created event:**
  Enabled with a call to exists.
* **Deleted event:**
  Enabled with a call to exists, getData, and getChildren.
* **Changed event:**
  Enabled with a call to exists and getData.
* **Child event:**
  Enabled with a call to getChildren.

## Persistent, Recursive Watches

**New in 3.6.0:** There is now a variation on the standard
watch described above whereby you can set a watch that does not get removed when triggered.
Additionally, these watches trigger the event types *NodeCreated*, *NodeDeleted*, and *NodeDataChanged*
and, optionally, recursively for all znodes starting at the znode that the watch is registered for. Note
that *NodeChildrenChanged* events are not triggered for persistent recursive watches as it would be redundant.

Persistent watches are set using the method *addWatch()*. The triggering semantics and guarantees
(other than one-time triggering) are the same as standard watches. The only exception regarding events is that
recursive persistent watchers never trigger child changed events as they are redundant.
Persistent watches are removed using *removeWatches()* with watcher type *WatcherType.Any*.

## Remove Watches

We can remove the watches registered on a znode with a call to
removeWatches. Also, a ZooKeeper client can remove watches locally even
if there is no server connection by setting the local flag to true. The
following list details the events which will be triggered after the
successful watch removal.

* **Child Remove event:**
  Watcher which was added with a call to getChildren.
* **Data Remove event:**
  Watcher which was added with a call to exists or getData.
* **Persistent Remove event:**
  Watcher which was added with a call to add a persistent watch.

## What ZooKeeper Guarantees about Watches

With regard to watches, ZooKeeper maintains these
guarantees:

* Watches are ordered with respect to other events, other
  watches, and asynchronous replies. The ZooKeeper client libraries
  ensures that everything is dispatched in order.

* A client will see a watch event for a znode it is watching
  before seeing the new data that corresponds to that znode.

* The order of watch events from ZooKeeper corresponds to the
  order of the updates as seen by the ZooKeeper service.

## Things to Remember about Watches

* Standard watches are one time triggers; if you get a watch event and
  you want to get notified of future changes, you must set another
  watch.

* Because standard watches are one time triggers and there is latency
  between getting the event and sending a new request to get a watch
  you cannot reliably see every change that happens to a node in
  ZooKeeper. Be prepared to handle the case where the znode changes
  multiple times between getting the event and setting the watch
  again. (You may not care, but at least realize it may
  happen.)

* A watch object, or function/context pair, will only be
  triggered once for a given notification. For example, if the same
  watch object is registered for an exists and a getData call for the
  same file and that file is then deleted, the watch object would
  only be invoked once with the deletion notification for the file.

* When you disconnect from a server (for example, when the
  server fails), you will not get any watches until the connection
  is reestablished. For this reason session events are sent to all
  outstanding watch handlers. Use session events to go into a safe
  mode: you will not be receiving events while disconnected, so your
  process should act conservatively in that mode.
`,h={title:"Watches",description:"Describes ZooKeeper watches — one-time event triggers sent to clients when znode data or children change — including watch semantics, guarantees, and removal."},r=[],o={contents:[{heading:void 0,content:`All of the read operations in ZooKeeper - getData(), getChildren(), and exists() - have the option of setting a watch as a
side effect. Here is ZooKeeper's definition of a watch: a watch event is
one-time trigger, sent to the client that set the watch, which occurs when
the data for which the watch was set changes. There are three key points
to consider in this definition of a watch:`},{heading:void 0,content:`One-time trigger
One watch event will be sent to the client when the data has changed.
For example, if a client does a getData("/znode1", true) and later the
data for /znode1 is changed or deleted, the client will get a watch
event for /znode1. If /znode1 changes again, no watch event will be
sent unless the client has done another read that sets a new
watch.`},{heading:void 0,content:`Sent to the client
This implies that an event is on the way to the client, but may
not reach the client before the successful return code to the change
operation reaches the client that initiated the change. Watches are
sent asynchronously to watchers. ZooKeeper provides an ordering
guarantee: a client will never see a change for which it has set a
watch until it first sees the watch event. Network delays or other
factors may cause different clients to see watches and return codes
from updates at different times. The key point is that everything seen
by the different clients will have a consistent order.`},{heading:void 0,content:`The data for which the watch was
set
This refers to the different ways a node can change. It
helps to think of ZooKeeper as maintaining two lists of
watches: data watches and child watches. getData() and
exists() set data watches. getChildren() sets child
watches. Alternatively, it may help to think of watches being
set according to the kind of data returned. getData() and
exists() return information about the data of the node,
whereas getChildren() returns a list of children. Thus,
setData() will trigger data watches for the znode being set
(assuming the set is successful). A successful create() will
trigger a data watch for the znode being created and a child
watch for the parent znode. A successful delete() will trigger
both a data watch and a child watch (since there can be no
more children) for a znode being deleted as well as a child
watch for the parent znode.`},{heading:void 0,content:`Watches are maintained locally at the ZooKeeper server to which the
client is connected. This allows watches to be lightweight to set,
maintain, and dispatch. When a client connects to a new server, the watch
will be triggered for any session events. Watches will not be received
while disconnected from a server. When a client reconnects, any previously
registered watches will be reregistered and triggered if needed. In
general this all occurs transparently. There is one case where a watch
may be missed: a watch for the existence of a znode not yet created will
be missed if the znode is created and deleted while disconnected.`},{heading:void 0,content:`New in 3.6.0: Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.`},{heading:"semantics-of-watches",content:`We can set watches with the three calls that read the state of
ZooKeeper: exists, getData, and getChildren. The following list details
the events that a watch can trigger and the calls that enable them:`},{heading:"semantics-of-watches",content:`Created event:
Enabled with a call to exists.`},{heading:"semantics-of-watches",content:`Deleted event:
Enabled with a call to exists, getData, and getChildren.`},{heading:"semantics-of-watches",content:`Changed event:
Enabled with a call to exists and getData.`},{heading:"semantics-of-watches",content:`Child event:
Enabled with a call to getChildren.`},{heading:"persistent-recursive-watches",content:`New in 3.6.0: There is now a variation on the standard
watch described above whereby you can set a watch that does not get removed when triggered.
Additionally, these watches trigger the event types NodeCreated, NodeDeleted, and NodeDataChanged
and, optionally, recursively for all znodes starting at the znode that the watch is registered for. Note
that NodeChildrenChanged events are not triggered for persistent recursive watches as it would be redundant.`},{heading:"persistent-recursive-watches",content:`Persistent watches are set using the method addWatch(). The triggering semantics and guarantees
(other than one-time triggering) are the same as standard watches. The only exception regarding events is that
recursive persistent watchers never trigger child changed events as they are redundant.
Persistent watches are removed using removeWatches() with watcher type WatcherType.Any.`},{heading:"remove-watches",content:`We can remove the watches registered on a znode with a call to
removeWatches. Also, a ZooKeeper client can remove watches locally even
if there is no server connection by setting the local flag to true. The
following list details the events which will be triggered after the
successful watch removal.`},{heading:"remove-watches",content:`Child Remove event:
Watcher which was added with a call to getChildren.`},{heading:"remove-watches",content:`Data Remove event:
Watcher which was added with a call to exists or getData.`},{heading:"remove-watches",content:`Persistent Remove event:
Watcher which was added with a call to add a persistent watch.`},{heading:"what-zookeeper-guarantees-about-watches",content:`With regard to watches, ZooKeeper maintains these
guarantees:`},{heading:"what-zookeeper-guarantees-about-watches",content:`Watches are ordered with respect to other events, other
watches, and asynchronous replies. The ZooKeeper client libraries
ensures that everything is dispatched in order.`},{heading:"what-zookeeper-guarantees-about-watches",content:`A client will see a watch event for a znode it is watching
before seeing the new data that corresponds to that znode.`},{heading:"what-zookeeper-guarantees-about-watches",content:`The order of watch events from ZooKeeper corresponds to the
order of the updates as seen by the ZooKeeper service.`},{heading:"things-to-remember-about-watches",content:`Standard watches are one time triggers; if you get a watch event and
you want to get notified of future changes, you must set another
watch.`},{heading:"things-to-remember-about-watches",content:`Because standard watches are one time triggers and there is latency
between getting the event and sending a new request to get a watch
you cannot reliably see every change that happens to a node in
ZooKeeper. Be prepared to handle the case where the znode changes
multiple times between getting the event and setting the watch
again. (You may not care, but at least realize it may
happen.)`},{heading:"things-to-remember-about-watches",content:`A watch object, or function/context pair, will only be
triggered once for a given notification. For example, if the same
watch object is registered for an exists and a getData call for the
same file and that file is then deleted, the watch object would
only be invoked once with the deletion notification for the file.`},{heading:"things-to-remember-about-watches",content:`When you disconnect from a server (for example, when the
server fails), you will not get any watches until the connection
is reestablished. For this reason session events are sent to all
outstanding watch handlers. Use session events to go into a safe
mode: you will not be receiving events while disconnected, so your
process should act conservatively in that mode.`}],headings:[{id:"semantics-of-watches",content:"Semantics of Watches"},{id:"persistent-recursive-watches",content:"Persistent, Recursive Watches"},{id:"remove-watches",content:"Remove Watches"},{id:"what-zookeeper-guarantees-about-watches",content:"What ZooKeeper Guarantees about Watches"},{id:"things-to-remember-about-watches",content:"Things to Remember about Watches"}]};const c=[{depth:2,url:"#semantics-of-watches",title:e.jsx(e.Fragment,{children:"Semantics of Watches"})},{depth:2,url:"#persistent-recursive-watches",title:e.jsx(e.Fragment,{children:"Persistent, Recursive Watches"})},{depth:2,url:"#remove-watches",title:e.jsx(e.Fragment,{children:"Remove Watches"})},{depth:2,url:"#what-zookeeper-guarantees-about-watches",title:e.jsx(e.Fragment,{children:"What ZooKeeper Guarantees about Watches"})},{depth:2,url:"#things-to-remember-about-watches",title:e.jsx(e.Fragment,{children:"Things to Remember about Watches"})}];function a(n){const t={em:"em",h2:"h2",li:"li",p:"p",strong:"strong",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsxs(t.p,{children:["All of the read operations in ZooKeeper - ",e.jsx(t.strong,{children:"getData()"}),", ",e.jsx(t.strong,{children:"getChildren()"}),", and ",e.jsx(t.strong,{children:"exists()"}),` - have the option of setting a watch as a
side effect. Here is ZooKeeper's definition of a watch: a watch event is
one-time trigger, sent to the client that set the watch, which occurs when
the data for which the watch was set changes. There are three key points
to consider in this definition of a watch:`]}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"One-time trigger"}),`
One watch event will be sent to the client when the data has changed.
For example, if a client does a getData("/znode1", true) and later the
data for /znode1 is changed or deleted, the client will get a watch
event for /znode1. If /znode1 changes again, no watch event will be
sent unless the client has done another read that sets a new
watch.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Sent to the client"}),`
This implies that an event is on the way to the client, but may
not reach the client before the successful return code to the change
operation reaches the client that initiated the change. Watches are
sent asynchronously to watchers. ZooKeeper provides an ordering
guarantee: a client will never see a change for which it has set a
watch until it first sees the watch event. Network delays or other
factors may cause different clients to see watches and return codes
from updates at different times. The key point is that everything seen
by the different clients will have a consistent order.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:`The data for which the watch was
set`}),`
This refers to the different ways a node can change. It
helps to think of ZooKeeper as maintaining two lists of
watches: data watches and child watches. getData() and
exists() set data watches. getChildren() sets child
watches. Alternatively, it may help to think of watches being
set according to the kind of data returned. getData() and
exists() return information about the data of the node,
whereas getChildren() returns a list of children. Thus,
setData() will trigger data watches for the znode being set
(assuming the set is successful). A successful create() will
trigger a data watch for the znode being created and a child
watch for the parent znode. A successful delete() will trigger
both a data watch and a child watch (since there can be no
more children) for a znode being deleted as well as a child
watch for the parent znode.`]}),`
`]}),`
`,e.jsx(t.p,{children:`Watches are maintained locally at the ZooKeeper server to which the
client is connected. This allows watches to be lightweight to set,
maintain, and dispatch. When a client connects to a new server, the watch
will be triggered for any session events. Watches will not be received
while disconnected from a server. When a client reconnects, any previously
registered watches will be reregistered and triggered if needed. In
general this all occurs transparently. There is one case where a watch
may be missed: a watch for the existence of a znode not yet created will
be missed if the znode is created and deleted while disconnected.`}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"New in 3.6.0:"}),` Clients can also set
permanent, recursive watches on a znode that are not removed when triggered
and that trigger for changes on the registered znode as well as any children
znodes recursively.`]}),`
`,e.jsx(t.h2,{id:"semantics-of-watches",children:"Semantics of Watches"}),`
`,e.jsx(t.p,{children:`We can set watches with the three calls that read the state of
ZooKeeper: exists, getData, and getChildren. The following list details
the events that a watch can trigger and the calls that enable them:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Created event:"}),`
Enabled with a call to exists.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Deleted event:"}),`
Enabled with a call to exists, getData, and getChildren.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Changed event:"}),`
Enabled with a call to exists and getData.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Child event:"}),`
Enabled with a call to getChildren.`]}),`
`]}),`
`,e.jsx(t.h2,{id:"persistent-recursive-watches",children:"Persistent, Recursive Watches"}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"New in 3.6.0:"}),` There is now a variation on the standard
watch described above whereby you can set a watch that does not get removed when triggered.
Additionally, these watches trigger the event types `,e.jsx(t.em,{children:"NodeCreated"}),", ",e.jsx(t.em,{children:"NodeDeleted"}),", and ",e.jsx(t.em,{children:"NodeDataChanged"}),`
and, optionally, recursively for all znodes starting at the znode that the watch is registered for. Note
that `,e.jsx(t.em,{children:"NodeChildrenChanged"})," events are not triggered for persistent recursive watches as it would be redundant."]}),`
`,e.jsxs(t.p,{children:["Persistent watches are set using the method ",e.jsx(t.em,{children:"addWatch()"}),`. The triggering semantics and guarantees
(other than one-time triggering) are the same as standard watches. The only exception regarding events is that
recursive persistent watchers never trigger child changed events as they are redundant.
Persistent watches are removed using `,e.jsx(t.em,{children:"removeWatches()"})," with watcher type ",e.jsx(t.em,{children:"WatcherType.Any"}),"."]}),`
`,e.jsx(t.h2,{id:"remove-watches",children:"Remove Watches"}),`
`,e.jsx(t.p,{children:`We can remove the watches registered on a znode with a call to
removeWatches. Also, a ZooKeeper client can remove watches locally even
if there is no server connection by setting the local flag to true. The
following list details the events which will be triggered after the
successful watch removal.`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Child Remove event:"}),`
Watcher which was added with a call to getChildren.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Data Remove event:"}),`
Watcher which was added with a call to exists or getData.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Persistent Remove event:"}),`
Watcher which was added with a call to add a persistent watch.`]}),`
`]}),`
`,e.jsx(t.h2,{id:"what-zookeeper-guarantees-about-watches",children:"What ZooKeeper Guarantees about Watches"}),`
`,e.jsx(t.p,{children:`With regard to watches, ZooKeeper maintains these
guarantees:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`Watches are ordered with respect to other events, other
watches, and asynchronous replies. The ZooKeeper client libraries
ensures that everything is dispatched in order.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`A client will see a watch event for a znode it is watching
before seeing the new data that corresponds to that znode.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`The order of watch events from ZooKeeper corresponds to the
order of the updates as seen by the ZooKeeper service.`}),`
`]}),`
`]}),`
`,e.jsx(t.h2,{id:"things-to-remember-about-watches",children:"Things to Remember about Watches"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`Standard watches are one time triggers; if you get a watch event and
you want to get notified of future changes, you must set another
watch.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`Because standard watches are one time triggers and there is latency
between getting the event and sending a new request to get a watch
you cannot reliably see every change that happens to a node in
ZooKeeper. Be prepared to handle the case where the znode changes
multiple times between getting the event and setting the watch
again. (You may not care, but at least realize it may
happen.)`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`A watch object, or function/context pair, will only be
triggered once for a given notification. For example, if the same
watch object is registered for an exists and a getData call for the
same file and that file is then deleted, the watch object would
only be invoked once with the deletion notification for the file.`}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`When you disconnect from a server (for example, when the
server fails), you will not get any watches until the connection
is reestablished. For this reason session events are sent to all
outstanding watch handlers. Use session events to go into a safe
mode: you will not be receiving events while disconnected, so your
process should act conservatively in that mode.`}),`
`]}),`
`]})]})}function d(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(a,{...n})}):a(n)}export{i as _markdown,d as default,r as extractedReferences,h as frontmatter,o as structuredData,c as toc};
