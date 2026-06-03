import{j as n}from"./chunk-EPOLDU6W-BACfhBcx.js";let a=`ZooKeeper is a high performance, scalable service. Both reads and
write operations are designed to be fast, though reads are faster than
writes. The reason for this is that in the case of reads, ZooKeeper can
serve older data, which in turn is due to ZooKeeper's consistency
guarantees:

* *Sequential Consistency* :
  Updates from a client will be applied in the order that they
  were sent.

* *Atomicity* :
  Updates either succeed or fail — there are no partial
  results.

* *Single System Image* :
  A client will see the same view of the service regardless of
  the server that it connects to. i.e., a client will never see an
  older view of the system even if the client fails over to a
  different server with the same session.

* *Reliability* :
  Once an update has been applied, it will persist from that
  time forward until a client overwrites the update. This guarantee
  has two corollaries:
  1. If a client gets a successful return code, the update will
     have been applied. On some failures (communication errors,
     timeouts, etc) the client will not know if the update has
     applied or not. We take steps to minimize the failures, but the
     guarantee is only present with successful return codes.
     (This is called the *monotonicity condition* in Paxos.)
  2. Any updates that are seen by the client, through a read
     request or successful update, will never be rolled back when
     recovering from server failures.

* *Timeliness* :
  The clients view of the system is guaranteed to be up-to-date
  within a certain time bound (on the order of tens of seconds).
  Either system changes will be seen by a client within this bound, or
  the client will detect a service outage.

Using these consistency guarantees it is easy to build higher level
functions such as leader election, barriers, queues, and read/write
revocable locks solely at the ZooKeeper client (no additions needed to
ZooKeeper). See [Recipes and Solutions](/developer/recipes)
for more details.

<Callout type="info">
  Sometimes developers mistakenly assume one other guarantee that ZooKeeper does
  *not* in fact make. This is: *Simultaneously Consistent Cross-Client Views*:
  ZooKeeper does not guarantee that at every instance in time, two different
  clients will have identical views of ZooKeeper data. Due to factors like
  network delays, one client may perform an update before another client gets
  notified of the change. Consider the scenario of two clients, A and B. If
  client A sets the value of a znode /a from 0 to 1, then tells client B to read
  /a, client B may read the old value of 0, depending on which server it is
  connected to. If it is important that Client A and Client B read the same
  value, Client B should call the **sync()** method from the ZooKeeper API
  method before it performs its read. So, ZooKeeper by itself doesn't guarantee
  that changes occur synchronously across all servers, but ZooKeeper primitives
  can be used to construct higher level functions that provide useful client
  synchronization. (For more information, see the [ZooKeeper
  Recipes](/developer/recipes)).
</Callout>
`,l={title:"Consistency Guarantees",description:"Details ZooKeeper's consistency guarantees: sequential consistency, atomicity, single system image, reliability, and timeliness, and how they compare to other distributed systems."},c=[{href:"/developer/recipes"},{href:"/developer/recipes"}],d={contents:[{heading:void 0,content:`ZooKeeper is a high performance, scalable service. Both reads and
write operations are designed to be fast, though reads are faster than
writes. The reason for this is that in the case of reads, ZooKeeper can
serve older data, which in turn is due to ZooKeeper's consistency
guarantees:`},{heading:void 0,content:`Sequential Consistency :
Updates from a client will be applied in the order that they
were sent.`},{heading:void 0,content:`Atomicity :
Updates either succeed or fail — there are no partial
results.`},{heading:void 0,content:`Single System Image :
A client will see the same view of the service regardless of
the server that it connects to. i.e., a client will never see an
older view of the system even if the client fails over to a
different server with the same session.`},{heading:void 0,content:`Reliability :
Once an update has been applied, it will persist from that
time forward until a client overwrites the update. This guarantee
has two corollaries:`},{heading:void 0,content:`If a client gets a successful return code, the update will
have been applied. On some failures (communication errors,
timeouts, etc) the client will not know if the update has
applied or not. We take steps to minimize the failures, but the
guarantee is only present with successful return codes.
(This is called the monotonicity condition in Paxos.)`},{heading:void 0,content:`Any updates that are seen by the client, through a read
request or successful update, will never be rolled back when
recovering from server failures.`},{heading:void 0,content:`Timeliness :
The clients view of the system is guaranteed to be up-to-date
within a certain time bound (on the order of tens of seconds).
Either system changes will be seen by a client within this bound, or
the client will detect a service outage.`},{heading:void 0,content:`Using these consistency guarantees it is easy to build higher level
functions such as leader election, barriers, queues, and read/write
revocable locks solely at the ZooKeeper client (no additions needed to
ZooKeeper). See Recipes and Solutions
for more details.`},{heading:void 0,content:"type: info"},{heading:void 0,content:`Sometimes developers mistakenly assume one other guarantee that ZooKeeper does
not in fact make. This is: Simultaneously Consistent Cross-Client Views:
ZooKeeper does not guarantee that at every instance in time, two different
clients will have identical views of ZooKeeper data. Due to factors like
network delays, one client may perform an update before another client gets
notified of the change. Consider the scenario of two clients, A and B. If
client A sets the value of a znode /a from 0 to 1, then tells client B to read
/a, client B may read the old value of 0, depending on which server it is
connected to. If it is important that Client A and Client B read the same
value, Client B should call the sync() method from the ZooKeeper API
method before it performs its read. So, ZooKeeper by itself doesn't guarantee
that changes occur synchronously across all servers, but ZooKeeper primitives
can be used to construct higher level functions that provide useful client
synchronization. (For more information, see the ZooKeeper
Recipes).`}],headings:[]};const h=[];function s(t){const e={a:"a",em:"em",li:"li",ol:"ol",p:"p",strong:"strong",ul:"ul",...t.components},{Callout:i}=e;return i||o("Callout"),n.jsxs(n.Fragment,{children:[n.jsx(e.p,{children:`ZooKeeper is a high performance, scalable service. Both reads and
write operations are designed to be fast, though reads are faster than
writes. The reason for this is that in the case of reads, ZooKeeper can
serve older data, which in turn is due to ZooKeeper's consistency
guarantees:`}),`
`,n.jsxs(e.ul,{children:[`
`,n.jsxs(e.li,{children:[`
`,n.jsxs(e.p,{children:[n.jsx(e.em,{children:"Sequential Consistency"}),` :
Updates from a client will be applied in the order that they
were sent.`]}),`
`]}),`
`,n.jsxs(e.li,{children:[`
`,n.jsxs(e.p,{children:[n.jsx(e.em,{children:"Atomicity"}),` :
Updates either succeed or fail — there are no partial
results.`]}),`
`]}),`
`,n.jsxs(e.li,{children:[`
`,n.jsxs(e.p,{children:[n.jsx(e.em,{children:"Single System Image"}),` :
A client will see the same view of the service regardless of
the server that it connects to. i.e., a client will never see an
older view of the system even if the client fails over to a
different server with the same session.`]}),`
`]}),`
`,n.jsxs(e.li,{children:[`
`,n.jsxs(e.p,{children:[n.jsx(e.em,{children:"Reliability"}),` :
Once an update has been applied, it will persist from that
time forward until a client overwrites the update. This guarantee
has two corollaries:`]}),`
`,n.jsxs(e.ol,{children:[`
`,n.jsxs(e.li,{children:[`If a client gets a successful return code, the update will
have been applied. On some failures (communication errors,
timeouts, etc) the client will not know if the update has
applied or not. We take steps to minimize the failures, but the
guarantee is only present with successful return codes.
(This is called the `,n.jsx(e.em,{children:"monotonicity condition"})," in Paxos.)"]}),`
`,n.jsx(e.li,{children:`Any updates that are seen by the client, through a read
request or successful update, will never be rolled back when
recovering from server failures.`}),`
`]}),`
`]}),`
`,n.jsxs(e.li,{children:[`
`,n.jsxs(e.p,{children:[n.jsx(e.em,{children:"Timeliness"}),` :
The clients view of the system is guaranteed to be up-to-date
within a certain time bound (on the order of tens of seconds).
Either system changes will be seen by a client within this bound, or
the client will detect a service outage.`]}),`
`]}),`
`]}),`
`,n.jsxs(e.p,{children:[`Using these consistency guarantees it is easy to build higher level
functions such as leader election, barriers, queues, and read/write
revocable locks solely at the ZooKeeper client (no additions needed to
ZooKeeper). See `,n.jsx(e.a,{href:"/developer/recipes",children:"Recipes and Solutions"}),`
for more details.`]}),`
`,n.jsx(i,{type:"info",children:n.jsxs(e.p,{children:[`Sometimes developers mistakenly assume one other guarantee that ZooKeeper does
`,n.jsx(e.em,{children:"not"})," in fact make. This is: ",n.jsx(e.em,{children:"Simultaneously Consistent Cross-Client Views"}),`:
ZooKeeper does not guarantee that at every instance in time, two different
clients will have identical views of ZooKeeper data. Due to factors like
network delays, one client may perform an update before another client gets
notified of the change. Consider the scenario of two clients, A and B. If
client A sets the value of a znode /a from 0 to 1, then tells client B to read
/a, client B may read the old value of 0, depending on which server it is
connected to. If it is important that Client A and Client B read the same
value, Client B should call the `,n.jsx(e.strong,{children:"sync()"}),` method from the ZooKeeper API
method before it performs its read. So, ZooKeeper by itself doesn't guarantee
that changes occur synchronously across all servers, but ZooKeeper primitives
can be used to construct higher level functions that provide useful client
synchronization. (For more information, see the `,n.jsx(e.a,{href:"/developer/recipes",children:`ZooKeeper
Recipes`}),")."]})})]})}function u(t={}){const{wrapper:e}=t.components||{};return e?n.jsx(e,{...t,children:n.jsx(s,{...t})}):s(t)}function o(t,e){throw new Error("Expected component `"+t+"` to be defined: you likely forgot to import, pass, or provide it.")}export{a as _markdown,u as default,c as extractedReferences,l as frontmatter,d as structuredData,h as toc};
