import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let i=`## Things to Avoid

Here are some common problems you can avoid by configuring
ZooKeeper correctly:

* *inconsistent lists of servers* :
  The list of ZooKeeper servers used by the clients must match
  the list of ZooKeeper servers that each ZooKeeper server has.
  Things work okay if the client list is a subset of the real list,
  but things will really act strange if clients have a list of
  ZooKeeper servers that are in different ZooKeeper clusters. Also,
  the server lists in each Zookeeper server configuration file
  should be consistent with one another.

* *incorrect placement of transaction log* :
  The most performance critical part of ZooKeeper is the
  transaction log. ZooKeeper syncs transactions to media before it
  returns a response. A dedicated transaction log device is key to
  consistent good performance. Putting the log on a busy device will
  adversely affect performance. If you only have one storage device,
  increase the snapCount so that snapshot files are generated less often;
  it does not eliminate the problem, but it makes more resources available
  for the transaction log.

* *incorrect Java heap size* :
  You should take special care to set your Java max heap size
  correctly. In particular, you should not create a situation in
  which ZooKeeper swaps to disk. The disk is death to ZooKeeper.
  Everything is ordered, so if processing one request swaps the
  disk, all other queued requests will probably do the same. DON'T SWAP.
  Be conservative in your estimates: if you have 4G of RAM, do
  not set the Java max heap size to 6G or even 4G. For example, it
  is more likely you would use a 3G heap for a 4G machine, as the
  operating system and the cache also need memory. The best and only
  recommend practice for estimating the heap size your system needs
  is to run load tests, and then make sure you are well below the
  usage limit that would cause the system to swap.

* *Publicly accessible deployment* :
  A ZooKeeper ensemble is expected to operate in a trusted computing environment.
  It is thus recommended deploying ZooKeeper behind a firewall.

## Best Practices

For best results, take note of the following list of good
Zookeeper practices:

For multi-tenant installations see the [section](/developer/programmers-guide/sessions)
detailing ZooKeeper "chroot" support, this can be very useful
when deploying many applications/services interfacing to a
single ZooKeeper cluster.
`,r={title:"Best Practices",description:"Recommended best practices and common pitfalls to avoid when deploying and configuring ZooKeeper, covering server lists, transaction log placement, Java heap sizing, and more."},a=[{href:"/developer/programmers-guide/sessions"}],l={contents:[{heading:"things-to-avoid",content:`Here are some common problems you can avoid by configuring
ZooKeeper correctly:`},{heading:"things-to-avoid",content:`inconsistent lists of servers :
The list of ZooKeeper servers used by the clients must match
the list of ZooKeeper servers that each ZooKeeper server has.
Things work okay if the client list is a subset of the real list,
but things will really act strange if clients have a list of
ZooKeeper servers that are in different ZooKeeper clusters. Also,
the server lists in each Zookeeper server configuration file
should be consistent with one another.`},{heading:"things-to-avoid",content:`incorrect placement of transaction log :
The most performance critical part of ZooKeeper is the
transaction log. ZooKeeper syncs transactions to media before it
returns a response. A dedicated transaction log device is key to
consistent good performance. Putting the log on a busy device will
adversely affect performance. If you only have one storage device,
increase the snapCount so that snapshot files are generated less often;
it does not eliminate the problem, but it makes more resources available
for the transaction log.`},{heading:"things-to-avoid",content:`incorrect Java heap size :
You should take special care to set your Java max heap size
correctly. In particular, you should not create a situation in
which ZooKeeper swaps to disk. The disk is death to ZooKeeper.
Everything is ordered, so if processing one request swaps the
disk, all other queued requests will probably do the same. DON'T SWAP.
Be conservative in your estimates: if you have 4G of RAM, do
not set the Java max heap size to 6G or even 4G. For example, it
is more likely you would use a 3G heap for a 4G machine, as the
operating system and the cache also need memory. The best and only
recommend practice for estimating the heap size your system needs
is to run load tests, and then make sure you are well below the
usage limit that would cause the system to swap.`},{heading:"things-to-avoid",content:`Publicly accessible deployment :
A ZooKeeper ensemble is expected to operate in a trusted computing environment.
It is thus recommended deploying ZooKeeper behind a firewall.`},{heading:"best-practices",content:`For best results, take note of the following list of good
Zookeeper practices:`},{heading:"best-practices",content:`For multi-tenant installations see the section
detailing ZooKeeper "chroot" support, this can be very useful
when deploying many applications/services interfacing to a
single ZooKeeper cluster.`}],headings:[{id:"things-to-avoid",content:"Things to Avoid"},{id:"best-practices",content:"Best Practices"}]};const c=[{depth:2,url:"#things-to-avoid",title:e.jsx(e.Fragment,{children:"Things to Avoid"})},{depth:2,url:"#best-practices",title:e.jsx(e.Fragment,{children:"Best Practices"})}];function n(o){const t={a:"a",em:"em",h2:"h2",li:"li",p:"p",ul:"ul",...o.components};return e.jsxs(e.Fragment,{children:[e.jsx(t.h2,{id:"things-to-avoid",children:"Things to Avoid"}),`
`,e.jsx(t.p,{children:`Here are some common problems you can avoid by configuring
ZooKeeper correctly:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.em,{children:"inconsistent lists of servers"}),` :
The list of ZooKeeper servers used by the clients must match
the list of ZooKeeper servers that each ZooKeeper server has.
Things work okay if the client list is a subset of the real list,
but things will really act strange if clients have a list of
ZooKeeper servers that are in different ZooKeeper clusters. Also,
the server lists in each Zookeeper server configuration file
should be consistent with one another.`]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.em,{children:"incorrect placement of transaction log"}),` :
The most performance critical part of ZooKeeper is the
transaction log. ZooKeeper syncs transactions to media before it
returns a response. A dedicated transaction log device is key to
consistent good performance. Putting the log on a busy device will
adversely affect performance. If you only have one storage device,
increase the snapCount so that snapshot files are generated less often;
it does not eliminate the problem, but it makes more resources available
for the transaction log.`]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.em,{children:"incorrect Java heap size"}),` :
You should take special care to set your Java max heap size
correctly. In particular, you should not create a situation in
which ZooKeeper swaps to disk. The disk is death to ZooKeeper.
Everything is ordered, so if processing one request swaps the
disk, all other queued requests will probably do the same. DON'T SWAP.
Be conservative in your estimates: if you have 4G of RAM, do
not set the Java max heap size to 6G or even 4G. For example, it
is more likely you would use a 3G heap for a 4G machine, as the
operating system and the cache also need memory. The best and only
recommend practice for estimating the heap size your system needs
is to run load tests, and then make sure you are well below the
usage limit that would cause the system to swap.`]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[e.jsx(t.em,{children:"Publicly accessible deployment"}),` :
A ZooKeeper ensemble is expected to operate in a trusted computing environment.
It is thus recommended deploying ZooKeeper behind a firewall.`]}),`
`]}),`
`]}),`
`,e.jsx(t.h2,{id:"best-practices",children:"Best Practices"}),`
`,e.jsx(t.p,{children:`For best results, take note of the following list of good
Zookeeper practices:`}),`
`,e.jsxs(t.p,{children:["For multi-tenant installations see the ",e.jsx(t.a,{href:"/developer/programmers-guide/sessions",children:"section"}),`
detailing ZooKeeper "chroot" support, this can be very useful
when deploying many applications/services interfacing to a
single ZooKeeper cluster.`]})]})}function h(o={}){const{wrapper:t}=o.components||{};return t?e.jsx(t,{...o,children:e.jsx(n,{...o})}):n(o)}export{i as _markdown,h as default,a as extractedReferences,r as frontmatter,l as structuredData,c as toc};
