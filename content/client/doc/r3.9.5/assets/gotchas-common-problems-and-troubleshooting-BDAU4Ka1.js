import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let r=`So now you know ZooKeeper. It's fast, simple, your application
works, but wait ... something's wrong. Here are some pitfalls that
ZooKeeper users fall into:

1. If you are using watches, you must look for the connected watch
   event. When a ZooKeeper client disconnects from a server, you will
   not receive notification of changes until reconnected. If you are
   watching for a znode to come into existence, you will miss the event
   if the znode is created and deleted while you are disconnected.
2. You must test ZooKeeper server failures. The ZooKeeper service
   can survive failures as long as a majority of servers are active. The
   question to ask is: can your application handle it? In the real world
   a client's connection to ZooKeeper can break. (ZooKeeper server
   failures and network partitions are common reasons for connection
   loss.) The ZooKeeper client library takes care of recovering your
   connection and letting you know what happened, but you must make sure
   that you recover your state and any outstanding requests that failed.
   Find out if you got it right in the test lab, not in production - test
   with a ZooKeeper service made up of a several of servers and subject
   them to reboots.
3. The list of ZooKeeper servers used by the client must match the
   list of ZooKeeper servers that each ZooKeeper server has. Things can
   work, although not optimally, if the client list is a subset of the
   real list of ZooKeeper servers, but not if the client lists ZooKeeper
   servers not in the ZooKeeper cluster.
4. Be careful where you put that transaction log. The most
   performance-critical part of ZooKeeper is the transaction log.
   ZooKeeper must sync transactions to media before it returns a
   response. A dedicated transaction log device is key to consistent good
   performance. Putting the log on a busy device will adversely effect
   performance. If you only have one storage device, put trace files on
   NFS and increase the snapshotCount; it doesn't eliminate the problem,
   but it can mitigate it.
5. Set your Java max heap size correctly. It is very important to
   *avoid swapping.* Going to disk unnecessarily will
   almost certainly degrade your performance unacceptably. Remember, in
   ZooKeeper, everything is ordered, so if one request hits the disk, all
   other queued requests hit the disk.
   To avoid swapping, try to set the heapsize to the amount of
   physical memory you have, minus the amount needed by the OS and cache.
   The best way to determine an optimal heap size for your configurations
   is to *run load tests*. If for some reason you
   can't, be conservative in your estimates and choose a number well
   below the limit that would cause your machine to swap. For example, on
   a 4G machine, a 3G heap is a conservative estimate to start
   with.
`,s={title:"Gotchas: Common Problems and Troubleshooting",description:"Common pitfalls and troubleshooting tips for ZooKeeper application developers, covering watches, server failures, connection loss, performance, and recoverable errors."},i=[],c={contents:[{heading:void 0,content:`So now you know ZooKeeper. It's fast, simple, your application
works, but wait ... something's wrong. Here are some pitfalls that
ZooKeeper users fall into:`},{heading:void 0,content:`If you are using watches, you must look for the connected watch
event. When a ZooKeeper client disconnects from a server, you will
not receive notification of changes until reconnected. If you are
watching for a znode to come into existence, you will miss the event
if the znode is created and deleted while you are disconnected.`},{heading:void 0,content:`You must test ZooKeeper server failures. The ZooKeeper service
can survive failures as long as a majority of servers are active. The
question to ask is: can your application handle it? In the real world
a client's connection to ZooKeeper can break. (ZooKeeper server
failures and network partitions are common reasons for connection
loss.) The ZooKeeper client library takes care of recovering your
connection and letting you know what happened, but you must make sure
that you recover your state and any outstanding requests that failed.
Find out if you got it right in the test lab, not in production - test
with a ZooKeeper service made up of a several of servers and subject
them to reboots.`},{heading:void 0,content:`The list of ZooKeeper servers used by the client must match the
list of ZooKeeper servers that each ZooKeeper server has. Things can
work, although not optimally, if the client list is a subset of the
real list of ZooKeeper servers, but not if the client lists ZooKeeper
servers not in the ZooKeeper cluster.`},{heading:void 0,content:`Be careful where you put that transaction log. The most
performance-critical part of ZooKeeper is the transaction log.
ZooKeeper must sync transactions to media before it returns a
response. A dedicated transaction log device is key to consistent good
performance. Putting the log on a busy device will adversely effect
performance. If you only have one storage device, put trace files on
NFS and increase the snapshotCount; it doesn't eliminate the problem,
but it can mitigate it.`},{heading:void 0,content:`Set your Java max heap size correctly. It is very important to
avoid swapping. Going to disk unnecessarily will
almost certainly degrade your performance unacceptably. Remember, in
ZooKeeper, everything is ordered, so if one request hits the disk, all
other queued requests hit the disk.
To avoid swapping, try to set the heapsize to the amount of
physical memory you have, minus the amount needed by the OS and cache.
The best way to determine an optimal heap size for your configurations
is to run load tests. If for some reason you
can't, be conservative in your estimates and choose a number well
below the limit that would cause your machine to swap. For example, on
a 4G machine, a 3G heap is a conservative estimate to start
with.`}],headings:[]};const l=[];function n(o){const t={em:"em",li:"li",ol:"ol",p:"p",...o.components};return e.jsxs(e.Fragment,{children:[e.jsx(t.p,{children:`So now you know ZooKeeper. It's fast, simple, your application
works, but wait ... something's wrong. Here are some pitfalls that
ZooKeeper users fall into:`}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsx(t.li,{children:`If you are using watches, you must look for the connected watch
event. When a ZooKeeper client disconnects from a server, you will
not receive notification of changes until reconnected. If you are
watching for a znode to come into existence, you will miss the event
if the znode is created and deleted while you are disconnected.`}),`
`,e.jsx(t.li,{children:`You must test ZooKeeper server failures. The ZooKeeper service
can survive failures as long as a majority of servers are active. The
question to ask is: can your application handle it? In the real world
a client's connection to ZooKeeper can break. (ZooKeeper server
failures and network partitions are common reasons for connection
loss.) The ZooKeeper client library takes care of recovering your
connection and letting you know what happened, but you must make sure
that you recover your state and any outstanding requests that failed.
Find out if you got it right in the test lab, not in production - test
with a ZooKeeper service made up of a several of servers and subject
them to reboots.`}),`
`,e.jsx(t.li,{children:`The list of ZooKeeper servers used by the client must match the
list of ZooKeeper servers that each ZooKeeper server has. Things can
work, although not optimally, if the client list is a subset of the
real list of ZooKeeper servers, but not if the client lists ZooKeeper
servers not in the ZooKeeper cluster.`}),`
`,e.jsx(t.li,{children:`Be careful where you put that transaction log. The most
performance-critical part of ZooKeeper is the transaction log.
ZooKeeper must sync transactions to media before it returns a
response. A dedicated transaction log device is key to consistent good
performance. Putting the log on a busy device will adversely effect
performance. If you only have one storage device, put trace files on
NFS and increase the snapshotCount; it doesn't eliminate the problem,
but it can mitigate it.`}),`
`,e.jsxs(t.li,{children:[`Set your Java max heap size correctly. It is very important to
`,e.jsx(t.em,{children:"avoid swapping."}),` Going to disk unnecessarily will
almost certainly degrade your performance unacceptably. Remember, in
ZooKeeper, everything is ordered, so if one request hits the disk, all
other queued requests hit the disk.
To avoid swapping, try to set the heapsize to the amount of
physical memory you have, minus the amount needed by the OS and cache.
The best way to determine an optimal heap size for your configurations
is to `,e.jsx(t.em,{children:"run load tests"}),`. If for some reason you
can't, be conservative in your estimates and choose a number well
below the limit that would cause your machine to swap. For example, on
a 4G machine, a 3G heap is a conservative estimate to start
with.`]}),`
`]})]})}function u(o={}){const{wrapper:t}=o.components||{};return t?e.jsx(t,{...o,children:e.jsx(n,{...o})}):n(o)}export{r as _markdown,u as default,i as extractedReferences,s as frontmatter,c as structuredData,l as toc};
