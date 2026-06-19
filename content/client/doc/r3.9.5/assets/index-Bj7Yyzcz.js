import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let i=`The first four sections of this guide present a higher level
discussions of various ZooKeeper concepts. These are necessary both for an
understanding of how ZooKeeper works as well how to work with it. It does
not contain source code, but it does assume a familiarity with the
problems associated with distributed computing.

The next four sections provide practical programming
information.

Most of the information in this document is written to be accessible as
stand-alone reference material. However, before starting your first
ZooKeeper application, you should probably at least read the chapters on
the [ZooKeeper Data Model](/developer/programmers-guide/data-model) and [ZooKeeper Basic Operations](/developer/programmers-guide/building-blocks-a-guide-to-zookeeper-operations).

## Links to Other Information

Outside the formal documentation, there're several other sources of
information for ZooKeeper developers.

* *[API Reference](/apidocs/zookeeper-server/index.html)* :
  The complete reference to the ZooKeeper API
* *[ZooKeeper Talk at the Hadoop Summit 2008](https://www.youtube.com/watch?v=rXI9xiesUV8)* :
  A video introduction to ZooKeeper, by Benjamin Reed of Yahoo!
  Research
* *[Barrier and Queue Tutorial](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Tutorial)* :
  The excellent Java tutorial by Flavio Junqueira, implementing
  simple barriers and producer-consumer queues using ZooKeeper.
* *[ZooKeeper - A Reliable, Scalable Distributed Coordination System](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeperArticles)* :
  An article by Todd Hoff (07/15/2008)
* *[ZooKeeper Recipes](/developer/recipes)* :
  Pseudo-level discussion of the implementation of various
  synchronization solutions with ZooKeeper: Event Handles, Queues,
  Locks, and Two-phase Commits.
`,s={title:"Programmer's Guide",description:"This document is a guide for developers wishing to create distributed applications that take advantage of ZooKeeper's coordination services. It contains conceptual and practical information."},a=[{href:"/developer/programmers-guide/data-model"},{href:"/developer/programmers-guide/building-blocks-a-guide-to-zookeeper-operations"},{href:"/apidocs/zookeeper-server/index.html"},{href:"https://www.youtube.com/watch?v=rXI9xiesUV8"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/Tutorial"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeperArticles"},{href:"/developer/recipes"}],c={contents:[{heading:void 0,content:`The first four sections of this guide present a higher level
discussions of various ZooKeeper concepts. These are necessary both for an
understanding of how ZooKeeper works as well how to work with it. It does
not contain source code, but it does assume a familiarity with the
problems associated with distributed computing.`},{heading:void 0,content:`The next four sections provide practical programming
information.`},{heading:void 0,content:`Most of the information in this document is written to be accessible as
stand-alone reference material. However, before starting your first
ZooKeeper application, you should probably at least read the chapters on
the ZooKeeper Data Model and ZooKeeper Basic Operations.`},{heading:"links-to-other-information",content:`Outside the formal documentation, there're several other sources of
information for ZooKeeper developers.`},{heading:"links-to-other-information",content:`API Reference :
The complete reference to the ZooKeeper API`},{heading:"links-to-other-information",content:`ZooKeeper Talk at the Hadoop Summit 2008 :
A video introduction to ZooKeeper, by Benjamin Reed of Yahoo!
Research`},{heading:"links-to-other-information",content:`Barrier and Queue Tutorial :
The excellent Java tutorial by Flavio Junqueira, implementing
simple barriers and producer-consumer queues using ZooKeeper.`},{heading:"links-to-other-information",content:`ZooKeeper - A Reliable, Scalable Distributed Coordination System :
An article by Todd Hoff (07/15/2008)`},{heading:"links-to-other-information",content:`ZooKeeper Recipes :
Pseudo-level discussion of the implementation of various
synchronization solutions with ZooKeeper: Event Handles, Queues,
Locks, and Two-phase Commits.`}],headings:[{id:"links-to-other-information",content:"Links to Other Information"}]};const l=[{depth:2,url:"#links-to-other-information",title:e.jsx(e.Fragment,{children:"Links to Other Information"})}];function t(n){const o={a:"a",em:"em",h2:"h2",li:"li",p:"p",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsx(o.p,{children:`The first four sections of this guide present a higher level
discussions of various ZooKeeper concepts. These are necessary both for an
understanding of how ZooKeeper works as well how to work with it. It does
not contain source code, but it does assume a familiarity with the
problems associated with distributed computing.`}),`
`,e.jsx(o.p,{children:`The next four sections provide practical programming
information.`}),`
`,e.jsxs(o.p,{children:[`Most of the information in this document is written to be accessible as
stand-alone reference material. However, before starting your first
ZooKeeper application, you should probably at least read the chapters on
the `,e.jsx(o.a,{href:"/developer/programmers-guide/data-model",children:"ZooKeeper Data Model"})," and ",e.jsx(o.a,{href:"/developer/programmers-guide/building-blocks-a-guide-to-zookeeper-operations",children:"ZooKeeper Basic Operations"}),"."]}),`
`,e.jsx(o.h2,{id:"links-to-other-information",children:"Links to Other Information"}),`
`,e.jsx(o.p,{children:`Outside the formal documentation, there're several other sources of
information for ZooKeeper developers.`}),`
`,e.jsxs(o.ul,{children:[`
`,e.jsxs(o.li,{children:[e.jsx(o.em,{children:e.jsx(o.a,{href:"/apidocs/zookeeper-server/index.html",children:"API Reference"})}),` :
The complete reference to the ZooKeeper API`]}),`
`,e.jsxs(o.li,{children:[e.jsx(o.em,{children:e.jsx(o.a,{href:"https://www.youtube.com/watch?v=rXI9xiesUV8",children:"ZooKeeper Talk at the Hadoop Summit 2008"})}),` :
A video introduction to ZooKeeper, by Benjamin Reed of Yahoo!
Research`]}),`
`,e.jsxs(o.li,{children:[e.jsx(o.em,{children:e.jsx(o.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/Tutorial",children:"Barrier and Queue Tutorial"})}),` :
The excellent Java tutorial by Flavio Junqueira, implementing
simple barriers and producer-consumer queues using ZooKeeper.`]}),`
`,e.jsxs(o.li,{children:[e.jsx(o.em,{children:e.jsx(o.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeperArticles",children:"ZooKeeper - A Reliable, Scalable Distributed Coordination System"})}),` :
An article by Todd Hoff (07/15/2008)`]}),`
`,e.jsxs(o.li,{children:[e.jsx(o.em,{children:e.jsx(o.a,{href:"/developer/recipes",children:"ZooKeeper Recipes"})}),` :
Pseudo-level discussion of the implementation of various
synchronization solutions with ZooKeeper: Event Handles, Queues,
Locks, and Two-phase Commits.`]}),`
`]})]})}function d(n={}){const{wrapper:o}=n.components||{};return o?e.jsx(o,{...n,children:e.jsx(t,{...n})}):t(n)}export{i as _markdown,d as default,a as extractedReferences,s as frontmatter,c as structuredData,l as toc};
