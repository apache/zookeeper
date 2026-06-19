import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let h=`ZooKeeper is designed to withstand machine failures. A ZooKeeper cluster can automatically recover
from temporary failures such as machine reboots, and can tolerate up to (N-1)/2 permanent failures
for a cluster of N members due to hardware failures or disk corruption. When a member permanently
fails, it loses access to the cluster. If the cluster permanently loses more than (N-1)/2 members,
it disastrously fails and loses quorum. Once quorum is lost, the cluster cannot reach consensus and
therefore cannot continue to accept updates.

To recover from such disastrous failures, ZooKeeper provides snapshot and restore functionalities to
restore a cluster from a snapshot.

Key characteristics of snapshot and restore:

1. They operate on the connected server via Admin Server APIs.
2. They are rate-limited to protect the server from being overloaded.
3. They require authentication and authorization on the root path with ALL permission. The supported
   auth schemes are digest, x509, and IP.

## Snapshot

Recovering a cluster needs a snapshot from a ZooKeeper cluster. Users can periodically take
snapshots from a live server which has the highest zxid and stream out data to local or external
storage/file system (e.g., S3).

\`\`\`bash
# Takes a snapshot from the connected server. Rate-limited to once every 5 minutes by default.
curl -H 'Authorization: digest root:root_passwd' \\
  http://hostname:adminPort/commands/snapshot?streaming=true \\
  --output snapshotFileName
\`\`\`

## Restore

Restoring a cluster needs a single snapshot as input stream. Restore can be used for recovering a
cluster after quorum loss or for building a brand-new cluster with seed data.

All members should restore using the same snapshot. The recommended steps are:

<Steps>
  <Step>
    Block traffic on the client port or client secure port before restore starts.
  </Step>

  <Step>
    Take a snapshot of the latest database state using the snapshot admin server command, if
    applicable.
  </Step>

  <Step>
    For each server:

    * Move the files in \`dataDir\` and \`dataLogDir\` to a different location to prevent the restored
      database from being overwritten when the server restarts after restore.
    * Restore the server using the restore admin server command:

    \`\`\`bash
    # Restores the db of the connected server from a snapshot input stream. Rate-limited to once every 5 minutes by default.
    curl -H 'Content-Type: application/octet-stream' \\
      -H 'Authorization: digest root:root_passwd' \\
      -X POST http://hostname:adminPort/commands/restore \\
      --data-binary "@snapshotFileName"
    \`\`\`
  </Step>

  <Step>
    Unblock traffic on the client port or client secure port after restore completes.
  </Step>
</Steps>
`,l={title:"Snapshot and Restore Guide",description:"How to use ZooKeeper's snapshot and restore APIs to recover a cluster from quorum loss or catastrophic failure."},c=[],d={contents:[{heading:void 0,content:`ZooKeeper is designed to withstand machine failures. A ZooKeeper cluster can automatically recover
from temporary failures such as machine reboots, and can tolerate up to (N-1)/2 permanent failures
for a cluster of N members due to hardware failures or disk corruption. When a member permanently
fails, it loses access to the cluster. If the cluster permanently loses more than (N-1)/2 members,
it disastrously fails and loses quorum. Once quorum is lost, the cluster cannot reach consensus and
therefore cannot continue to accept updates.`},{heading:void 0,content:`To recover from such disastrous failures, ZooKeeper provides snapshot and restore functionalities to
restore a cluster from a snapshot.`},{heading:void 0,content:"Key characteristics of snapshot and restore:"},{heading:void 0,content:"They operate on the connected server via Admin Server APIs."},{heading:void 0,content:"They are rate-limited to protect the server from being overloaded."},{heading:void 0,content:`They require authentication and authorization on the root path with ALL permission. The supported
auth schemes are digest, x509, and IP.`},{heading:"snapshot",content:`Recovering a cluster needs a snapshot from a ZooKeeper cluster. Users can periodically take
snapshots from a live server which has the highest zxid and stream out data to local or external
storage/file system (e.g., S3).`},{heading:"restore",content:`Restoring a cluster needs a single snapshot as input stream. Restore can be used for recovering a
cluster after quorum loss or for building a brand-new cluster with seed data.`},{heading:"restore",content:"All members should restore using the same snapshot. The recommended steps are:"},{heading:"restore",content:`Take a snapshot of the latest database state using the snapshot admin server command, if
applicable.`},{heading:"restore",content:"For each server:"},{heading:"restore",content:`Move the files in dataDir and dataLogDir to a different location to prevent the restored
database from being overwritten when the server restarts after restore.`},{heading:"restore",content:"Restore the server using the restore admin server command:"}],headings:[{id:"snapshot",content:"Snapshot"},{id:"restore",content:"Restore"}]};const p=[{depth:2,url:"#snapshot",title:e.jsx(e.Fragment,{children:"Snapshot"})},{depth:2,url:"#restore",title:e.jsx(e.Fragment,{children:"Restore"})}];function a(t){const s={code:"code",h2:"h2",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",ul:"ul",...t.components},{Step:r,Steps:n}=s;return r||i("Step"),n||i("Steps"),e.jsxs(e.Fragment,{children:[e.jsx(s.p,{children:`ZooKeeper is designed to withstand machine failures. A ZooKeeper cluster can automatically recover
from temporary failures such as machine reboots, and can tolerate up to (N-1)/2 permanent failures
for a cluster of N members due to hardware failures or disk corruption. When a member permanently
fails, it loses access to the cluster. If the cluster permanently loses more than (N-1)/2 members,
it disastrously fails and loses quorum. Once quorum is lost, the cluster cannot reach consensus and
therefore cannot continue to accept updates.`}),`
`,e.jsx(s.p,{children:`To recover from such disastrous failures, ZooKeeper provides snapshot and restore functionalities to
restore a cluster from a snapshot.`}),`
`,e.jsx(s.p,{children:"Key characteristics of snapshot and restore:"}),`
`,e.jsxs(s.ol,{children:[`
`,e.jsx(s.li,{children:"They operate on the connected server via Admin Server APIs."}),`
`,e.jsx(s.li,{children:"They are rate-limited to protect the server from being overloaded."}),`
`,e.jsx(s.li,{children:`They require authentication and authorization on the root path with ALL permission. The supported
auth schemes are digest, x509, and IP.`}),`
`]}),`
`,e.jsx(s.h2,{id:"snapshot",children:"Snapshot"}),`
`,e.jsx(s.p,{children:`Recovering a cluster needs a snapshot from a ZooKeeper cluster. Users can periodically take
snapshots from a live server which has the highest zxid and stream out data to local or external
storage/file system (e.g., S3).`}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Takes a snapshot from the connected server. Rate-limited to once every 5 minutes by default."})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"curl"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -H"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 'Authorization: digest root:root_passwd'"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  http://hostname:adminPort/commands/snapshot?streaming="}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"true"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  --output"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" snapshotFileName"})]})]})})}),`
`,e.jsx(s.h2,{id:"restore",children:"Restore"}),`
`,e.jsx(s.p,{children:`Restoring a cluster needs a single snapshot as input stream. Restore can be used for recovering a
cluster after quorum loss or for building a brand-new cluster with seed data.`}),`
`,e.jsx(s.p,{children:"All members should restore using the same snapshot. The recommended steps are:"}),`
`,e.jsxs(n,{children:[e.jsx(r,{children:"Block traffic on the client port or client secure port before restore starts."}),e.jsx(r,{children:e.jsx(s.p,{children:`Take a snapshot of the latest database state using the snapshot admin server command, if
applicable.`})}),e.jsxs(r,{children:[e.jsx(s.p,{children:"For each server:"}),e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:["Move the files in ",e.jsx(s.code,{children:"dataDir"})," and ",e.jsx(s.code,{children:"dataLogDir"}),` to a different location to prevent the restored
database from being overwritten when the server restarts after restore.`]}),`
`,e.jsx(s.li,{children:"Restore the server using the restore admin server command:"}),`
`]}),e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Restores the db of the connected server from a snapshot input stream. Rate-limited to once every 5 minutes by default."})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"curl"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -H"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 'Content-Type: application/octet-stream'"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -H"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 'Authorization: digest root:root_passwd'"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  -X"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" POST"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" http://hostname:adminPort/commands/restore"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  --data-binary"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:' "@snapshotFileName"'})]})]})})})]}),e.jsx(r,{children:"Unblock traffic on the client port or client secure port after restore completes."})]})]})}function u(t={}){const{wrapper:s}=t.components||{};return s?e.jsx(s,{...t,children:e.jsx(a,{...t})}):a(t)}function i(t,s){throw new Error("Expected component `"+t+"` to be defined: you likely forgot to import, pass, or provide it.")}export{h as _markdown,u as default,c as extractedReferences,l as frontmatter,d as structuredData,p as toc};
