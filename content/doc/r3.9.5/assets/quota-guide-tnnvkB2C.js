import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let a=`ZooKeeper prints *WARN* messages if users exceed the quota assigned to them. The messages
are printed in the log of the ZooKeeper.

Notice: What the \`namespace\` quota means is the count quota which limits the number of children
under the path (included itself).

\`\`\`bash
$ bin/zkCli.sh -server host:port
\`\`\`

The above command gives you a command line option of using quotas.

## Setting Quotas

* You can use \`setquota\` to set a quota on a ZooKeeper node. It has an option of setting quota with
  \`-n\` (for namespace/count) and \`-b\` (for bytes/data length).

* The ZooKeeper quota is stored in ZooKeeper itself in **/zookeeper/quota**. To disable other people from
  changing the quotas, users can set the ACL for **/zookeeper/quota** so that only admins are able to read and write to it.

* If the quota doesn't exist in the specified path, create the quota, otherwise update the quota.

* The scope of the quota users set is all the nodes under the path specified (included itself).

* In order to simplify the calculation of quota in the current directory/hierarchy structure, a complete
  tree path (from root to leaf node) can be set only one quota. In the situation when setting a quota in
  a path which its parent or child node already has a quota, \`setquota\` will reject and tell the specified
  parent or child path. Users can adjust allocations of quotas (delete/move-up/move-down the quota)
  according to specific circumstances.

* Combined with the Chroot, the quota will have a better isolation effectiveness between different
  applications. For example:

  \`\`\`bash
  # Chroot is:
  192.168.0.1:2181,192.168.0.2:2181,192.168.0.3:2181/apps/app1
  setquota -n 100000 /apps/app1
  \`\`\`

* Users cannot set the quota on the path under **/zookeeper/quota**.

* The quota supports soft and hard quotas. The soft quota just logs a warning when exceeding the quota,
  but the hard quota also throws a \`QuotaExceededException\`. When setting soft and hard quota on the same
  path, the hard quota has priority.

## Listing Quotas

You can use *listquota* to list a quota on a ZooKeeper node.

## Deleting Quotas

You can use *delquota* to delete quota on a ZooKeeper node.
`,i={title:"Quota Guide",description:"ZooKeeper has both namespace and bytes quotas. You can use the ZooKeeperMain class to setup quotas."},h=[],r={contents:[{heading:void 0,content:`ZooKeeper prints WARN messages if users exceed the quota assigned to them. The messages
are printed in the log of the ZooKeeper.`},{heading:void 0,content:`Notice: What the namespace quota means is the count quota which limits the number of children
under the path (included itself).`},{heading:void 0,content:"The above command gives you a command line option of using quotas."},{heading:"setting-quotas",content:`You can use setquota to set a quota on a ZooKeeper node. It has an option of setting quota with
-n (for namespace/count) and -b (for bytes/data length).`},{heading:"setting-quotas",content:`The ZooKeeper quota is stored in ZooKeeper itself in /zookeeper/quota. To disable other people from
changing the quotas, users can set the ACL for /zookeeper/quota so that only admins are able to read and write to it.`},{heading:"setting-quotas",content:"If the quota doesn't exist in the specified path, create the quota, otherwise update the quota."},{heading:"setting-quotas",content:"The scope of the quota users set is all the nodes under the path specified (included itself)."},{heading:"setting-quotas",content:`In order to simplify the calculation of quota in the current directory/hierarchy structure, a complete
tree path (from root to leaf node) can be set only one quota. In the situation when setting a quota in
a path which its parent or child node already has a quota, setquota will reject and tell the specified
parent or child path. Users can adjust allocations of quotas (delete/move-up/move-down the quota)
according to specific circumstances.`},{heading:"setting-quotas",content:`Combined with the Chroot, the quota will have a better isolation effectiveness between different
applications. For example:`},{heading:"setting-quotas",content:"Users cannot set the quota on the path under /zookeeper/quota."},{heading:"setting-quotas",content:`The quota supports soft and hard quotas. The soft quota just logs a warning when exceeding the quota,
but the hard quota also throws a QuotaExceededException. When setting soft and hard quota on the same
path, the hard quota has priority.`},{heading:"listing-quotas",content:"You can use listquota to list a quota on a ZooKeeper node."},{heading:"deleting-quotas",content:"You can use delquota to delete quota on a ZooKeeper node."}],headings:[{id:"setting-quotas",content:"Setting Quotas"},{id:"listing-quotas",content:"Listing Quotas"},{id:"deleting-quotas",content:"Deleting Quotas"}]};const d=[{depth:2,url:"#setting-quotas",title:e.jsx(e.Fragment,{children:"Setting Quotas"})},{depth:2,url:"#listing-quotas",title:e.jsx(e.Fragment,{children:"Listing Quotas"})},{depth:2,url:"#deleting-quotas",title:e.jsx(e.Fragment,{children:"Deleting Quotas"})}];function o(n){const t={code:"code",em:"em",h2:"h2",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsxs(t.p,{children:["ZooKeeper prints ",e.jsx(t.em,{children:"WARN"}),` messages if users exceed the quota assigned to them. The messages
are printed in the log of the ZooKeeper.`]}),`
`,e.jsxs(t.p,{children:["Notice: What the ",e.jsx(t.code,{children:"namespace"}),` quota means is the count quota which limits the number of children
under the path (included itself).`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsxs(t.span,{className:"line",children:[e.jsx(t.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkCli.sh"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -server"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" host:port"})]})})})}),`
`,e.jsx(t.p,{children:"The above command gives you a command line option of using quotas."}),`
`,e.jsx(t.h2,{id:"setting-quotas",children:"Setting Quotas"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:["You can use ",e.jsx(t.code,{children:"setquota"}),` to set a quota on a ZooKeeper node. It has an option of setting quota with
`,e.jsx(t.code,{children:"-n"})," (for namespace/count) and ",e.jsx(t.code,{children:"-b"})," (for bytes/data length)."]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:["The ZooKeeper quota is stored in ZooKeeper itself in ",e.jsx(t.strong,{children:"/zookeeper/quota"}),`. To disable other people from
changing the quotas, users can set the ACL for `,e.jsx(t.strong,{children:"/zookeeper/quota"})," so that only admins are able to read and write to it."]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:"If the quota doesn't exist in the specified path, create the quota, otherwise update the quota."}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:"The scope of the quota users set is all the nodes under the path specified (included itself)."}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[`In order to simplify the calculation of quota in the current directory/hierarchy structure, a complete
tree path (from root to leaf node) can be set only one quota. In the situation when setting a quota in
a path which its parent or child node already has a quota, `,e.jsx(t.code,{children:"setquota"}),` will reject and tell the specified
parent or child path. Users can adjust allocations of quotas (delete/move-up/move-down the quota)
according to specific circumstances.`]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsx(t.p,{children:`Combined with the Chroot, the quota will have a better isolation effectiveness between different
applications. For example:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(t.code,{children:[e.jsx(t.span,{className:"line",children:e.jsx(t.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Chroot is:"})}),`
`,e.jsx(t.span,{className:"line",children:e.jsx(t.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"192.168.0.1:2181,192.168.0.2:2181,192.168.0.3:2181/apps/app1"})}),`
`,e.jsxs(t.span,{className:"line",children:[e.jsx(t.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"setquota"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -n"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 100000"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /apps/app1"})]})]})})}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:["Users cannot set the quota on the path under ",e.jsx(t.strong,{children:"/zookeeper/quota"}),"."]}),`
`]}),`
`,e.jsxs(t.li,{children:[`
`,e.jsxs(t.p,{children:[`The quota supports soft and hard quotas. The soft quota just logs a warning when exceeding the quota,
but the hard quota also throws a `,e.jsx(t.code,{children:"QuotaExceededException"}),`. When setting soft and hard quota on the same
path, the hard quota has priority.`]}),`
`]}),`
`]}),`
`,e.jsx(t.h2,{id:"listing-quotas",children:"Listing Quotas"}),`
`,e.jsxs(t.p,{children:["You can use ",e.jsx(t.em,{children:"listquota"})," to list a quota on a ZooKeeper node."]}),`
`,e.jsx(t.h2,{id:"deleting-quotas",children:"Deleting Quotas"}),`
`,e.jsxs(t.p,{children:["You can use ",e.jsx(t.em,{children:"delquota"})," to delete quota on a ZooKeeper node."]})]})}function l(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(o,{...n})}):o(n)}export{a as _markdown,l as default,h as extractedReferences,i as frontmatter,r as structuredData,d as toc};
