import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let r=`ZooKeeper stores its data in a data directory and its transaction
log in a transaction log directory. By default these two directories are
the same. The server can (and should) be configured to store the
transaction log files in a separate directory than the data files.
Throughput increases and latency decreases when transaction logs reside
on a dedicated log devices.

## The Data Directory

This directory has two or three files in it:

* *myid* - contains a single integer in
  human readable ASCII text that represents the server id.
* *initialize* - presence indicates lack of
  data tree is expected. Cleaned up once data tree is created.
* *snapshot.\\<zxid>* - holds the fuzzy
  snapshot of a data tree.

Each ZooKeeper server has a unique id. This id is used in two
places: the *myid* file and the configuration file.
The *myid* file identifies the server that
corresponds to the given data directory. The configuration file lists
the contact information for each server identified by its server id.
When a ZooKeeper server instance starts, it reads its id from the
*myid* file and then, using that id, reads from the
configuration file, looking up the port on which it should
listen.

The *snapshot* files stored in the data
directory are fuzzy snapshots in the sense that during the time the
ZooKeeper server is taking the snapshot, updates are occurring to the
data tree. The suffix of the *snapshot* file names
is the *zxid*, the ZooKeeper transaction id, of the
last committed transaction at the start of the snapshot. Thus, the
snapshot includes a subset of the updates to the data tree that
occurred while the snapshot was in process. The snapshot, then, may
not correspond to any data tree that actually existed, and for this
reason we refer to it as a fuzzy snapshot. Still, ZooKeeper can
recover using this snapshot because it takes advantage of the
idempotent nature of its updates. By replaying the transaction log
against fuzzy snapshots ZooKeeper gets the state of the system at the
end of the log.

## The Log Directory

The Log Directory contains the ZooKeeper transaction logs.
Before any update takes place, ZooKeeper ensures that the transaction
that represents the update is written to non-volatile storage. A new
log file is started when the number of transactions written to the
current log file reaches a (variable) threshold. The threshold is
computed using the same parameter which influences the frequency of
snapshotting (see snapCount and snapSizeLimitInKb above). The log file's
suffix is the first zxid written to that log.

## File Management

The format of snapshot and log files does not change between
standalone ZooKeeper servers and different configurations of
replicated ZooKeeper servers. Therefore, you can pull these files from
a running replicated ZooKeeper server to a development machine with a
stand-alone ZooKeeper server for troubleshooting.

Using older log and snapshot files, you can look at the previous
state of ZooKeeper servers and even restore that state.

The ZooKeeper server creates snapshot and log files, but
never deletes them. The retention policy of the data and log
files is implemented outside of the ZooKeeper server. The
server itself only needs the latest complete fuzzy snapshot, all log
files following it, and the last log file preceding it. The latter
requirement is necessary to include updates which happened after this
snapshot was started but went into the existing log file at that time.
This is possible because snapshotting and rolling over of logs
proceed somewhat independently in ZooKeeper. See the
[maintenance](/admin-ops/administrators-guide/administration#maintenance) section
for more details on setting a retention policy
and maintenance of ZooKeeper storage.

<Callout type="info">
  The data stored in these files is not encrypted. In the case of storing
  sensitive data in ZooKeeper, necessary measures need to be taken to prevent
  unauthorized access. Such measures are external to ZooKeeper (e.g., control
  access to the files) and depend on the individual settings in which it is
  being deployed.
</Callout>

## Recovery - TxnLogToolkit

More details can be found in [this](/admin-ops/tools#zktxnlogtoolkitsh)
`,h={title:"Data File Management",description:"Describes how ZooKeeper manages its data and transaction log directories, snapshot and log file lifecycle, file cleanup, and recovery using the TxnLogToolkit."},d=[{href:"/admin-ops/administrators-guide/administration#maintenance"},{href:"/admin-ops/tools#zktxnlogtoolkitsh"}],l={contents:[{heading:void 0,content:`ZooKeeper stores its data in a data directory and its transaction
log in a transaction log directory. By default these two directories are
the same. The server can (and should) be configured to store the
transaction log files in a separate directory than the data files.
Throughput increases and latency decreases when transaction logs reside
on a dedicated log devices.`},{heading:"the-data-directory",content:"This directory has two or three files in it:"},{heading:"the-data-directory",content:`myid - contains a single integer in
human readable ASCII text that represents the server id.`},{heading:"the-data-directory",content:`initialize - presence indicates lack of
data tree is expected. Cleaned up once data tree is created.`},{heading:"the-data-directory",content:`snapshot.<zxid> - holds the fuzzy
snapshot of a data tree.`},{heading:"the-data-directory",content:`Each ZooKeeper server has a unique id. This id is used in two
places: the myid file and the configuration file.
The myid file identifies the server that
corresponds to the given data directory. The configuration file lists
the contact information for each server identified by its server id.
When a ZooKeeper server instance starts, it reads its id from the
myid file and then, using that id, reads from the
configuration file, looking up the port on which it should
listen.`},{heading:"the-data-directory",content:`The snapshot files stored in the data
directory are fuzzy snapshots in the sense that during the time the
ZooKeeper server is taking the snapshot, updates are occurring to the
data tree. The suffix of the snapshot file names
is the zxid, the ZooKeeper transaction id, of the
last committed transaction at the start of the snapshot. Thus, the
snapshot includes a subset of the updates to the data tree that
occurred while the snapshot was in process. The snapshot, then, may
not correspond to any data tree that actually existed, and for this
reason we refer to it as a fuzzy snapshot. Still, ZooKeeper can
recover using this snapshot because it takes advantage of the
idempotent nature of its updates. By replaying the transaction log
against fuzzy snapshots ZooKeeper gets the state of the system at the
end of the log.`},{heading:"the-log-directory",content:`The Log Directory contains the ZooKeeper transaction logs.
Before any update takes place, ZooKeeper ensures that the transaction
that represents the update is written to non-volatile storage. A new
log file is started when the number of transactions written to the
current log file reaches a (variable) threshold. The threshold is
computed using the same parameter which influences the frequency of
snapshotting (see snapCount and snapSizeLimitInKb above). The log file's
suffix is the first zxid written to that log.`},{heading:"file-management",content:`The format of snapshot and log files does not change between
standalone ZooKeeper servers and different configurations of
replicated ZooKeeper servers. Therefore, you can pull these files from
a running replicated ZooKeeper server to a development machine with a
stand-alone ZooKeeper server for troubleshooting.`},{heading:"file-management",content:`Using older log and snapshot files, you can look at the previous
state of ZooKeeper servers and even restore that state.`},{heading:"file-management",content:`The ZooKeeper server creates snapshot and log files, but
never deletes them. The retention policy of the data and log
files is implemented outside of the ZooKeeper server. The
server itself only needs the latest complete fuzzy snapshot, all log
files following it, and the last log file preceding it. The latter
requirement is necessary to include updates which happened after this
snapshot was started but went into the existing log file at that time.
This is possible because snapshotting and rolling over of logs
proceed somewhat independently in ZooKeeper. See the
maintenance section
for more details on setting a retention policy
and maintenance of ZooKeeper storage.`},{heading:"file-management",content:"type: info"},{heading:"file-management",content:`The data stored in these files is not encrypted. In the case of storing
sensitive data in ZooKeeper, necessary measures need to be taken to prevent
unauthorized access. Such measures are external to ZooKeeper (e.g., control
access to the files) and depend on the individual settings in which it is
being deployed.`},{heading:"recovery---txnlogtoolkit",content:"More details can be found in this"}],headings:[{id:"the-data-directory",content:"The Data Directory"},{id:"the-log-directory",content:"The Log Directory"},{id:"file-management",content:"File Management"},{id:"recovery---txnlogtoolkit",content:"Recovery - TxnLogToolkit"}]};const c=[{depth:2,url:"#the-data-directory",title:e.jsx(e.Fragment,{children:"The Data Directory"})},{depth:2,url:"#the-log-directory",title:e.jsx(e.Fragment,{children:"The Log Directory"})},{depth:2,url:"#file-management",title:e.jsx(e.Fragment,{children:"File Management"})},{depth:2,url:"#recovery---txnlogtoolkit",title:e.jsx(e.Fragment,{children:"Recovery - TxnLogToolkit"})}];function a(n){const t={a:"a",em:"em",h2:"h2",li:"li",p:"p",ul:"ul",...n.components},{Callout:o}=t;return o||s("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(t.p,{children:`ZooKeeper stores its data in a data directory and its transaction
log in a transaction log directory. By default these two directories are
the same. The server can (and should) be configured to store the
transaction log files in a separate directory than the data files.
Throughput increases and latency decreases when transaction logs reside
on a dedicated log devices.`}),`
`,e.jsx(t.h2,{id:"the-data-directory",children:"The Data Directory"}),`
`,e.jsx(t.p,{children:"This directory has two or three files in it:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"myid"}),` - contains a single integer in
human readable ASCII text that represents the server id.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"initialize"}),` - presence indicates lack of
data tree is expected. Cleaned up once data tree is created.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"snapshot.<zxid>"}),` - holds the fuzzy
snapshot of a data tree.`]}),`
`]}),`
`,e.jsxs(t.p,{children:[`Each ZooKeeper server has a unique id. This id is used in two
places: the `,e.jsx(t.em,{children:"myid"}),` file and the configuration file.
The `,e.jsx(t.em,{children:"myid"}),` file identifies the server that
corresponds to the given data directory. The configuration file lists
the contact information for each server identified by its server id.
When a ZooKeeper server instance starts, it reads its id from the
`,e.jsx(t.em,{children:"myid"}),` file and then, using that id, reads from the
configuration file, looking up the port on which it should
listen.`]}),`
`,e.jsxs(t.p,{children:["The ",e.jsx(t.em,{children:"snapshot"}),` files stored in the data
directory are fuzzy snapshots in the sense that during the time the
ZooKeeper server is taking the snapshot, updates are occurring to the
data tree. The suffix of the `,e.jsx(t.em,{children:"snapshot"}),` file names
is the `,e.jsx(t.em,{children:"zxid"}),`, the ZooKeeper transaction id, of the
last committed transaction at the start of the snapshot. Thus, the
snapshot includes a subset of the updates to the data tree that
occurred while the snapshot was in process. The snapshot, then, may
not correspond to any data tree that actually existed, and for this
reason we refer to it as a fuzzy snapshot. Still, ZooKeeper can
recover using this snapshot because it takes advantage of the
idempotent nature of its updates. By replaying the transaction log
against fuzzy snapshots ZooKeeper gets the state of the system at the
end of the log.`]}),`
`,e.jsx(t.h2,{id:"the-log-directory",children:"The Log Directory"}),`
`,e.jsx(t.p,{children:`The Log Directory contains the ZooKeeper transaction logs.
Before any update takes place, ZooKeeper ensures that the transaction
that represents the update is written to non-volatile storage. A new
log file is started when the number of transactions written to the
current log file reaches a (variable) threshold. The threshold is
computed using the same parameter which influences the frequency of
snapshotting (see snapCount and snapSizeLimitInKb above). The log file's
suffix is the first zxid written to that log.`}),`
`,e.jsx(t.h2,{id:"file-management",children:"File Management"}),`
`,e.jsx(t.p,{children:`The format of snapshot and log files does not change between
standalone ZooKeeper servers and different configurations of
replicated ZooKeeper servers. Therefore, you can pull these files from
a running replicated ZooKeeper server to a development machine with a
stand-alone ZooKeeper server for troubleshooting.`}),`
`,e.jsx(t.p,{children:`Using older log and snapshot files, you can look at the previous
state of ZooKeeper servers and even restore that state.`}),`
`,e.jsxs(t.p,{children:[`The ZooKeeper server creates snapshot and log files, but
never deletes them. The retention policy of the data and log
files is implemented outside of the ZooKeeper server. The
server itself only needs the latest complete fuzzy snapshot, all log
files following it, and the last log file preceding it. The latter
requirement is necessary to include updates which happened after this
snapshot was started but went into the existing log file at that time.
This is possible because snapshotting and rolling over of logs
proceed somewhat independently in ZooKeeper. See the
`,e.jsx(t.a,{href:"/admin-ops/administrators-guide/administration#maintenance",children:"maintenance"}),` section
for more details on setting a retention policy
and maintenance of ZooKeeper storage.`]}),`
`,e.jsx(o,{type:"info",children:e.jsx(t.p,{children:`The data stored in these files is not encrypted. In the case of storing
sensitive data in ZooKeeper, necessary measures need to be taken to prevent
unauthorized access. Such measures are external to ZooKeeper (e.g., control
access to the files) and depend on the individual settings in which it is
being deployed.`})}),`
`,e.jsx(t.h2,{id:"recovery---txnlogtoolkit",children:"Recovery - TxnLogToolkit"}),`
`,e.jsxs(t.p,{children:["More details can be found in ",e.jsx(t.a,{href:"/admin-ops/tools#zktxnlogtoolkitsh",children:"this"})]})]})}function p(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(a,{...n})}):a(n)}function s(n,t){throw new Error("Expected component `"+n+"` to be defined: you likely forgot to import, pass, or provide it.")}export{r as _markdown,p as default,d as extractedReferences,h as frontmatter,l as structuredData,c as toc};
