import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let a=`## Connecting

\`\`\`bash
# connect to localhost on the default port 2181
bin/zkCli.sh
# connect to a remote host with a 3-second timeout
bin/zkCli.sh -timeout 3000 -server remoteIP:2181
# wait for connection before executing commands
bin/zkCli.sh -waitforconnection -timeout 3000 -server remoteIP:2181
# use a custom client configuration properties file
bin/zkCli.sh -client-configuration /path/to/client.properties
\`\`\`

## help

Show all available ZooKeeper commands.

\`\`\`
[zkshell: 1] help
ZooKeeper -server host:port cmd args
addauth scheme auth
close
config [-c] [-w] [-s]
connect host:port
create [-s] [-e] [-c] [-t ttl] path [data] [acl]
delete [-v version] path
deleteall path
delquota [-n|-b|-N|-B] path
get [-s] [-w] path
getAcl [-s] path
getAllChildrenNumber path
getEphemerals path
history
listquota path
ls [-s] [-w] [-R] path
printwatches on|off
quit
reconfig [-s] [-v version] [[-file path] | [-members serverID=host:port1:port2;port3[,...]*]] | [-add serverId=host:port1:port2;port3[,...]]* [-remove serverId[,...]*]
redo cmdno
removewatches path [-c|-d|-a] [-l]
set [-s] [-v version] path data
setAcl [-s] [-v version] [-R] path acl
setquota -n|-b|-N|-B val path
stat [-w] path
sync path
version
\`\`\`

## addauth

Add an authorized user for ACL authentication.

\`\`\`
[zkshell: 9] getAcl /acl_digest_test
Insufficient permission : /acl_digest_test

[zkshell: 10] addauth digest user1:12345
[zkshell: 11] getAcl /acl_digest_test
'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE=
: cdrwa

# add a super user
# set zookeeper.DigestAuthenticationProvider.superDigest, e.g.:
# zookeeper.DigestAuthenticationProvider.superDigest=zookeeper:qW/HnTfCSoQpB5G8LgkwT3IbiFc=
[zkshell: 12] addauth digest zookeeper:admin
\`\`\`

## close

Close the current client session.

\`\`\`
[zkshell: 0] close
2019-03-09 06:42:22,178 [myid:] - INFO  [main-EventThread:ClientCnxn$EventThread@528] - EventThread shut down for session: 0x10007ab7c550006
2019-03-09 06:42:22,179 [myid:] - INFO  [main:ZooKeeper@1346] - Session: 0x10007ab7c550006 closed
\`\`\`

## config

Show the current quorum membership configuration.

\`\`\`
[zkshell: 17] config
server.1=[2001:db8:1:0:0:242:ac11:2]:2888:3888:participant
server.2=[2001:db8:1:0:0:242:ac11:2]:12888:13888:participant
server.3=[2001:db8:1:0:0:242:ac11:2]:22888:23888:participant
version=0
\`\`\`

## connect

Connect to a ZooKeeper server.

\`\`\`
[zkshell: 4] connect
2019-03-09 06:43:33,179 [myid:localhost:2181] - INFO  [main-SendThread(localhost:2181):ClientCnxn$SendThread@986] - Socket connection established, initiating session, client: /127.0.0.1:35144, server: localhost/127.0.0.1:2181
2019-03-09 06:43:33,189 [myid:localhost:2181] - INFO  [main-SendThread(localhost:2181):ClientCnxn$SendThread@1421] - Session establishment complete on server localhost/127.0.0.1:2181, sessionid = 0x10007ab7c550007, negotiated timeout = 30000
connect "localhost:2181,localhost:2182,localhost:2183"

# connect to a remote server
[zkshell: 5] connect remoteIP:2181
\`\`\`

## create

Create a znode.

\`\`\`
# persistent node
[zkshell: 7] create /persistent_node
Created /persistent_node

# ephemeral node (deleted when session ends)
[zkshell: 8] create -e /ephemeral_node mydata
Created /ephemeral_node

# persistent sequential node
[zkshell: 9] create -s /persistent_sequential_node mydata
Created /persistent_sequential_node0000000176

# ephemeral sequential node
[zkshell: 10] create -s -e /ephemeral_sequential_node mydata
Created /ephemeral_sequential_node0000000174
\`\`\`

Create a node with an ACL schema:

\`\`\`
[zkshell: 11] create /zk-node-create-schema mydata digest:user1:+owfoSBn/am19roBPzR1/MfCblE=:crwad
Created /zk-node-create-schema
[zkshell: 12] addauth digest user1:12345
[zkshell: 13] getAcl /zk-node-create-schema
'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE=
: cdrwa
\`\`\`

Create a container node (automatically deleted when its last child is deleted):

\`\`\`
[zkshell: 14] create -c /container_node mydata
Created /container_node
[zkshell: 15] create -c /container_node/child_1 mydata
Created /container_node/child_1
[zkshell: 16] create -c /container_node/child_2 mydata
Created /container_node/child_2
[zkshell: 17] delete /container_node/child_1
[zkshell: 18] delete /container_node/child_2
[zkshell: 19] get /container_node
org.apache.zookeeper.KeeperException$NoNodeException: KeeperErrorCode = NoNode for /container_node
\`\`\`

Create a TTL node (requires \`zookeeper.extendedTypesEnabled=true\`; otherwise returns \`KeeperErrorCode = Unimplemented\`):

\`\`\`
[zkshell: 20] create -t 3000 /ttl_node mydata
Created /ttl_node

# after 3 seconds
[zkshell: 21] get /ttl_node
org.apache.zookeeper.KeeperException$NoNodeException: KeeperErrorCode = NoNode for /ttl_node
\`\`\`

## delete

Delete a node at the specified path.

\`\`\`
[zkshell: 2] delete /config/topics/test
[zkshell: 3] ls /config/topics/test
Node does not exist: /config/topics/test
\`\`\`

## deleteall

Delete a node and all of its descendants.

\`\`\`
[zkshell: 1] ls /config
[changes, clients, topics]
[zkshell: 2] deleteall /config
[zkshell: 3] ls /config
Node does not exist: /config
\`\`\`

## delquota

Delete the quota on a path.

\`\`\`
[zkshell: 1] delquota /quota_test
[zkshell: 2] listquota /quota_test
absolute path is /zookeeper/quota/quota_test/zookeeper_limits
quota for /quota_test does not exist.

# delete specific quota types
[zkshell: 3] delquota -n /c1
[zkshell: 4] delquota -N /c2
[zkshell: 5] delquota -b /c3
[zkshell: 6] delquota -B /c4
\`\`\`

## get

Get the data stored at a path.

\`\`\`
[zkshell: 10] get /latest_producer_id_block
{"version":1,"broker":0,"block_start":"0","block_end":"999"}

# -s: also show node stats
[zkshell: 11] get -s /latest_producer_id_block
{"version":1,"broker":0,"block_start":"0","block_end":"999"}
cZxid = 0x90000009a
ctime = Sat Jul 28 08:14:09 UTC 2018
mZxid = 0x9000000a2
mtime = Sat Jul 28 08:14:12 UTC 2018
pZxid = 0x90000009a
cversion = 0
dataVersion = 1
aclVersion = 0
ephemeralOwner = 0x0
dataLength = 60
numChildren = 0

# -w: set a watch on data changes (requires printwatches to be on)
[zkshell: 12] get -w /latest_producer_id_block
{"version":1,"broker":0,"block_start":"0","block_end":"999"}

[zkshell: 13] set /latest_producer_id_block mydata
WATCHER::
WatchedEvent state:SyncConnected type:NodeDataChanged path:/latest_producer_id_block
\`\`\`

## getAcl

Get the ACL permissions for a path.

\`\`\`
[zkshell: 4] create /acl_test mydata ip:127.0.0.1:crwda
Created /acl_test
[zkshell: 5] getAcl /acl_test
'ip,'127.0.0.1
: cdrwa
[zkshell: 6] getAcl /testwatch
'world,'anyone
: cdrwa
\`\`\`

## getAllChildrenNumber

Get the total number of descendant nodes under a path.

\`\`\`
[zkshell: 1] getAllChildrenNumber /
73779
[zkshell: 2] getAllChildrenNumber /ZooKeeper
2
[zkshell: 3] getAllChildrenNumber /ZooKeeper/quota
0
\`\`\`

## getEphemerals

Get all ephemeral nodes created by the current session.

\`\`\`
[zkshell: 1] create -e /test-get-ephemerals "ephemeral node"
Created /test-get-ephemerals
[zkshell: 2] getEphemerals
[/test-get-ephemerals]
[zkshell: 3] getEphemerals /
[/test-get-ephemerals]
[zkshell: 4] create -e /test-get-ephemerals-1 "ephemeral node"
Created /test-get-ephemerals-1
[zkshell: 5] getEphemerals /test-get-ephemerals
test-get-ephemerals     test-get-ephemerals-1
[zkshell: 6] getEphemerals /test-get-ephemerals
[/test-get-ephemerals-1, /test-get-ephemerals]
[zkshell: 7] getEphemerals /test-get-ephemerals-1
[/test-get-ephemerals-1]
\`\`\`

## history

Show the most recent 11 commands executed in this session.

\`\`\`
[zkshell: 7] history
0 - close
1 - close
2 - ls /
3 - ls /
4 - connect
5 - ls /
6 - ll
7 - history
\`\`\`

## listquota

List the quota configured for a path.

\`\`\`
[zkshell: 1] listquota /c1
absolute path is /zookeeper/quota/c1/zookeeper_limits
Output quota for /c1 count=-1,bytes=-1=;byteHardLimit=-1;countHardLimit=2
Output stat for /c1 count=4,bytes=0
\`\`\`

## ls

List the child nodes of a path.

\`\`\`
[zkshell: 36] ls /quota_test
[child_1, child_2, child_3]

# -s: also show node stats
[zkshell: 37] ls -s /quota_test
[child_1, child_2, child_3]
cZxid = 0x110000002d
ctime = Thu Mar 07 11:19:07 UTC 2019
mZxid = 0x110000002d
mtime = Thu Mar 07 11:19:07 UTC 2019
pZxid = 0x1100000033
cversion = 3
dataVersion = 0
aclVersion = 0
ephemeralOwner = 0x0
dataLength = 0
numChildren = 3

# -R: recursively list all descendant nodes
[zkshell: 38] ls -R /quota_test
/quota_test
/quota_test/child_1
/quota_test/child_2
/quota_test/child_3

# -w: set a watch on child changes (requires printwatches to be on)
[zkshell: 39] ls -w /brokers
[ids, seqid, topics]
[zkshell: 40] delete /brokers/ids
WATCHER::
WatchedEvent state:SyncConnected type:NodeChildrenChanged path:/brokers
\`\`\`

## printwatches

Toggle whether watch events are printed to the console.

\`\`\`
[zkshell: 0] printwatches
printwatches is on
[zkshell: 1] printwatches off
[zkshell: 2] printwatches
printwatches is off
[zkshell: 3] printwatches on
[zkshell: 4] printwatches
printwatches is on
\`\`\`

## quit

Quit the CLI.

\`\`\`bash
[zkshell: 1] quit
\`\`\`

## reconfig

Change ensemble membership at runtime. Before using this command, read the details in [Dynamic Reconfiguration](/admin-ops/dynamic-reconfiguration), especially the Security section.

Prerequisites:

1. Set \`reconfigEnabled=true\` in \`zoo.cfg\`.
2. Add a super user or set \`skipACL\`; otherwise you will get \`Insufficient permission\`. For example: \`addauth digest zookeeper:admin\`.

\`\`\`
# Change follower 2 to an observer on port 12182, add observer 5, remove observer 4
[zkshell: 1] reconfig --add 2=localhost:2781:2786:observer;12182 --add 5=localhost:2781:2786:observer;2185 -remove 4
Committed new configuration:
server.1=localhost:2780:2785:participant;0.0.0.0:2181
server.2=localhost:2781:2786:observer;0.0.0.0:12182
server.3=localhost:2782:2787:participant;0.0.0.0:2183
server.5=localhost:2784:2789:observer;0.0.0.0:2185
version=1c00000002

# -members: specify the full new membership list
[zkshell: 2] reconfig -members server.1=localhost:2780:2785:participant;0.0.0.0:2181,server.2=localhost:2781:2786:observer;0.0.0.0:12182,server.3=localhost:2782:2787:participant;0.0.0.0:12183
Committed new configuration:
server.1=localhost:2780:2785:participant;0.0.0.0:2181
server.2=localhost:2781:2786:observer;0.0.0.0:12182
server.3=localhost:2782:2787:participant;0.0.0.0:12183
version=f9fe0000000c

# -file with -v: apply config from file only if current version matches
[zkshell: 3] reconfig -file /data/software/zookeeper/zookeeper-test/conf/myNewConfig.txt -v 2100000010
Committed new configuration:
server.1=localhost:2780:2785:participant;0.0.0.0:2181
server.2=localhost:2781:2786:observer;0.0.0.0:12182
server.3=localhost:2782:2787:participant;0.0.0.0:2183
server.5=localhost:2784:2789:observer;0.0.0.0:2185
version=220000000c
\`\`\`

## redo

Re-execute a command from history by its index.

\`\`\`
[zkshell: 4] history
0 - ls /
1 - get /consumers
2 - get /hbase
3 - ls  /hbase
4 - history
[zkshell: 5] redo 3
[backup-masters, draining, flush-table-proc, hbaseid, master-maintenance, meta-region-server, namespace, online-snapshot, replication, rs, running, splitWAL, switch, table, table-lock]
\`\`\`

## removewatches

Remove watches from a node.

\`\`\`
[zkshell: 1] get -w /brokers
null
[zkshell: 2] removewatches /brokers
WATCHER::
WatchedEvent state:SyncConnected type:DataWatchRemoved path:/brokers
\`\`\`

## set

Set or update the data at a path.

\`\`\`
[zkshell: 50] set /brokers myNewData

# -s: show the node stats after the update
[zkshell: 51] set -s /quota_test mydata_for_quota_test
cZxid = 0x110000002d
ctime = Thu Mar 07 11:19:07 UTC 2019
mZxid = 0x1100000038
mtime = Thu Mar 07 11:42:41 UTC 2019
pZxid = 0x1100000033
cversion = 3
dataVersion = 2
aclVersion = 0
ephemeralOwner = 0x0
dataLength = 21
numChildren = 3

# -v: optimistic locking (CAS) — version from dataVersion in stat
[zkshell: 52] set -v 0 /brokers myNewData
[zkshell: 53] set -v 0 /brokers myNewData
version No is not valid : /brokers
\`\`\`

## setAcl

Set ACL permissions on a node.

\`\`\`
[zkshell: 28] addauth digest user1:12345
[zkshell: 30] setAcl /acl_auth_test auth:user1:12345:crwad
[zkshell: 31] getAcl /acl_auth_test
'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE=
: cdrwa
\`\`\`

Use \`-R\` to set ACL recursively on all child nodes:

\`\`\`
[zkshell: 32] ls /acl_auth_test
[child_1, child_2]
[zkshell: 33] getAcl /acl_auth_test/child_2
'world,'anyone
: cdrwa
[zkshell: 34] setAcl -R /acl_auth_test auth:user1:12345:crwad
[zkshell: 35] getAcl /acl_auth_test/child_2
'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE=
: cdrwa
\`\`\`

Use \`-v\` to set ACL with optimistic locking (version from \`aclVersion\` in stat):

\`\`\`
[zkshell: 36] stat /acl_auth_test
cZxid = 0xf9fc0000001c
ctime = Tue Mar 26 16:50:58 CST 2019
mZxid = 0xf9fc0000001c
mtime = Tue Mar 26 16:50:58 CST 2019
pZxid = 0xf9fc0000001f
cversion = 2
dataVersion = 0
aclVersion = 3
ephemeralOwner = 0x0
dataLength = 0
numChildren = 2
[zkshell: 37] setAcl -v 3 /acl_auth_test auth:user1:12345:crwad
\`\`\`

## setquota

Set a quota on a path. Soft limits (\`-n\`, \`-b\`) log a warning but do not block operations. Hard limits (\`-N\`, \`-B\`) reject operations that exceed the quota.

\`\`\`
# -n: soft limit on node count (includes the node itself)
[zkshell: 18] setquota -n 2 /quota_test
[zkshell: 19] create /quota_test/child_1
Created /quota_test/child_1
[zkshell: 20] create /quota_test/child_2
Created /quota_test/child_2
[zkshell: 21] create /quota_test/child_3
Created /quota_test/child_3
# soft limit: creation succeeds but a warning is logged
2019-03-07 11:22:36,680 [myid:1] - WARN  [SyncThread:0:DataTree@374] - Quota exceeded: /quota_test count=3 limit=2
2019-03-07 11:22:41,861 [myid:1] - WARN  [SyncThread:0:DataTree@374] - Quota exceeded: /quota_test count=4 limit=2

# -b: soft limit on data bytes
[zkshell: 22] setquota -b 5 /brokers
[zkshell: 23] set /brokers "I_love_zookeeper"
# soft limit: write succeeds but a warning is logged
WARN  [CommitProcWorkThread-7:DataTree@379] - Quota exceeded: /brokers bytes=4206 limit=5

# -N: hard limit on node count
[zkshell: 3] create /c1
Created /c1
[zkshell: 4] setquota -N 2 /c1
[zkshell: 5] listquota /c1
absolute path is /zookeeper/quota/c1/zookeeper_limits
Output quota for /c1 count=-1,bytes=-1=;byteHardLimit=-1;countHardLimit=2
Output stat for /c1 count=2,bytes=0
[zkshell: 6] create /c1/ch-3
Count Quota has exceeded : /c1/ch-3

# -B: hard limit on bytes
[zkshell: 3] create /c2
[zkshell: 4] setquota -B 4 /c2
[zkshell: 5] set /c2 "foo"
[zkshell: 6] set /c2 "foo-bar"
Bytes Quota has exceeded : /c2
[zkshell: 7] get /c2
foo
\`\`\`

## stat

Show the metadata (stat) of a node.

\`\`\`
[zkshell: 1] stat /hbase
cZxid = 0x4000013d9
ctime = Wed Jun 27 20:13:07 CST 2018
mZxid = 0x4000013d9
mtime = Wed Jun 27 20:13:07 CST 2018
pZxid = 0x500000001
cversion = 17
dataVersion = 0
aclVersion = 0
ephemeralOwner = 0x0
dataLength = 0
numChildren = 15
\`\`\`

## sync

Sync the data of a node between the leader and followers (asynchronous).

\`\`\`bash
[zkshell: 14] sync /
[zkshell: 15] Sync is OK
\`\`\`

## version

Show the ZooKeeper CLI version.

\`\`\`bash
[zkshell: 1] version
ZooKeeper CLI version: 3.6.0-SNAPSHOT-29f9b2c1c0e832081f94d59a6b88709c5f1bb3ca, built on 05/30/2019 09:26 GMT
\`\`\`

## whoami

Show all authentication information for the current session.

\`\`\`
[zkshell: 1] whoami
Auth scheme: User
ip: 127.0.0.1
[zkshell: 2] addauth digest user1:12345
[zkshell: 3] whoami
Auth scheme: User
ip: 127.0.0.1
digest: user1
\`\`\`
`,t={title:"CLI",description:"Reference guide for all zkCli.sh commands, with examples showing command syntax, options, and expected output."},c=[{href:"/admin-ops/dynamic-reconfiguration"}],h={contents:[{heading:"help",content:"Show all available ZooKeeper commands."},{heading:"addauth",content:"Add an authorized user for ACL authentication."},{heading:"close",content:"Close the current client session."},{heading:"config",content:"Show the current quorum membership configuration."},{heading:"connect",content:"Connect to a ZooKeeper server."},{heading:"create",content:"Create a znode."},{heading:"create",content:"Create a node with an ACL schema:"},{heading:"create",content:"Create a container node (automatically deleted when its last child is deleted):"},{heading:"create",content:"Create a TTL node (requires zookeeper.extendedTypesEnabled=true; otherwise returns KeeperErrorCode = Unimplemented):"},{heading:"delete",content:"Delete a node at the specified path."},{heading:"deleteall",content:"Delete a node and all of its descendants."},{heading:"delquota",content:"Delete the quota on a path."},{heading:"get",content:"Get the data stored at a path."},{heading:"getacl",content:"Get the ACL permissions for a path."},{heading:"getallchildrennumber",content:"Get the total number of descendant nodes under a path."},{heading:"getephemerals",content:"Get all ephemeral nodes created by the current session."},{heading:"history",content:"Show the most recent 11 commands executed in this session."},{heading:"listquota",content:"List the quota configured for a path."},{heading:"ls",content:"List the child nodes of a path."},{heading:"printwatches",content:"Toggle whether watch events are printed to the console."},{heading:"quit",content:"Quit the CLI."},{heading:"reconfig",content:"Change ensemble membership at runtime. Before using this command, read the details in Dynamic Reconfiguration, especially the Security section."},{heading:"reconfig",content:"Prerequisites:"},{heading:"reconfig",content:"Set reconfigEnabled=true in zoo.cfg."},{heading:"reconfig",content:"Add a super user or set skipACL; otherwise you will get Insufficient permission. For example: addauth digest zookeeper:admin."},{heading:"redo",content:"Re-execute a command from history by its index."},{heading:"removewatches",content:"Remove watches from a node."},{heading:"set",content:"Set or update the data at a path."},{heading:"setacl",content:"Set ACL permissions on a node."},{heading:"setacl",content:"Use -R to set ACL recursively on all child nodes:"},{heading:"setacl",content:"Use -v to set ACL with optimistic locking (version from aclVersion in stat):"},{heading:"setquota",content:"Set a quota on a path. Soft limits (-n, -b) log a warning but do not block operations. Hard limits (-N, -B) reject operations that exceed the quota."},{heading:"stat",content:"Show the metadata (stat) of a node."},{heading:"sync",content:"Sync the data of a node between the leader and followers (asynchronous)."},{heading:"version",content:"Show the ZooKeeper CLI version."},{heading:"whoami",content:"Show all authentication information for the current session."}],headings:[{id:"connecting",content:"Connecting"},{id:"help",content:"help"},{id:"addauth",content:"addauth"},{id:"close",content:"close"},{id:"config",content:"config"},{id:"connect",content:"connect"},{id:"create",content:"create"},{id:"delete",content:"delete"},{id:"deleteall",content:"deleteall"},{id:"delquota",content:"delquota"},{id:"get",content:"get"},{id:"getacl",content:"getAcl"},{id:"getallchildrennumber",content:"getAllChildrenNumber"},{id:"getephemerals",content:"getEphemerals"},{id:"history",content:"history"},{id:"listquota",content:"listquota"},{id:"ls",content:"ls"},{id:"printwatches",content:"printwatches"},{id:"quit",content:"quit"},{id:"reconfig",content:"reconfig"},{id:"redo",content:"redo"},{id:"removewatches",content:"removewatches"},{id:"set",content:"set"},{id:"setacl",content:"setAcl"},{id:"setquota",content:"setquota"},{id:"stat",content:"stat"},{id:"sync",content:"sync"},{id:"version",content:"version"},{id:"whoami",content:"whoami"}]};const r=[{depth:2,url:"#connecting",title:e.jsx(e.Fragment,{children:"Connecting"})},{depth:2,url:"#help",title:e.jsx(e.Fragment,{children:"help"})},{depth:2,url:"#addauth",title:e.jsx(e.Fragment,{children:"addauth"})},{depth:2,url:"#close",title:e.jsx(e.Fragment,{children:"close"})},{depth:2,url:"#config",title:e.jsx(e.Fragment,{children:"config"})},{depth:2,url:"#connect",title:e.jsx(e.Fragment,{children:"connect"})},{depth:2,url:"#create",title:e.jsx(e.Fragment,{children:"create"})},{depth:2,url:"#delete",title:e.jsx(e.Fragment,{children:"delete"})},{depth:2,url:"#deleteall",title:e.jsx(e.Fragment,{children:"deleteall"})},{depth:2,url:"#delquota",title:e.jsx(e.Fragment,{children:"delquota"})},{depth:2,url:"#get",title:e.jsx(e.Fragment,{children:"get"})},{depth:2,url:"#getacl",title:e.jsx(e.Fragment,{children:"getAcl"})},{depth:2,url:"#getallchildrennumber",title:e.jsx(e.Fragment,{children:"getAllChildrenNumber"})},{depth:2,url:"#getephemerals",title:e.jsx(e.Fragment,{children:"getEphemerals"})},{depth:2,url:"#history",title:e.jsx(e.Fragment,{children:"history"})},{depth:2,url:"#listquota",title:e.jsx(e.Fragment,{children:"listquota"})},{depth:2,url:"#ls",title:e.jsx(e.Fragment,{children:"ls"})},{depth:2,url:"#printwatches",title:e.jsx(e.Fragment,{children:"printwatches"})},{depth:2,url:"#quit",title:e.jsx(e.Fragment,{children:"quit"})},{depth:2,url:"#reconfig",title:e.jsx(e.Fragment,{children:"reconfig"})},{depth:2,url:"#redo",title:e.jsx(e.Fragment,{children:"redo"})},{depth:2,url:"#removewatches",title:e.jsx(e.Fragment,{children:"removewatches"})},{depth:2,url:"#set",title:e.jsx(e.Fragment,{children:"set"})},{depth:2,url:"#setacl",title:e.jsx(e.Fragment,{children:"setAcl"})},{depth:2,url:"#setquota",title:e.jsx(e.Fragment,{children:"setquota"})},{depth:2,url:"#stat",title:e.jsx(e.Fragment,{children:"stat"})},{depth:2,url:"#sync",title:e.jsx(e.Fragment,{children:"sync"})},{depth:2,url:"#version",title:e.jsx(e.Fragment,{children:"version"})},{depth:2,url:"#whoami",title:e.jsx(e.Fragment,{children:"whoami"})}];function l(s){const n={a:"a",code:"code",h2:"h2",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",...s.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"connecting",children:"Connecting"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# connect to localhost on the default port 2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"bin/zkCli.sh"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# connect to a remote host with a 3-second timeout"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"bin/zkCli.sh"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -timeout"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3000"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -server"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" remoteIP:2181"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# wait for connection before executing commands"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"bin/zkCli.sh"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -waitforconnection"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -timeout"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 3000"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -server"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" remoteIP:2181"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# use a custom client configuration properties file"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"bin/zkCli.sh"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -client-configuration"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /path/to/client.properties"})]})]})})}),`
`,e.jsx(n.h2,{id:"help",children:"help"}),`
`,e.jsx(n.p,{children:"Show all available ZooKeeper commands."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] help"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ZooKeeper -server host:port cmd args"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"addauth scheme auth"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"close"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"config [-c] [-w] [-s]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"connect host:port"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"create [-s] [-e] [-c] [-t ttl] path [data] [acl]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"delete [-v version] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"deleteall path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"delquota [-n|-b|-N|-B] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"get [-s] [-w] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getAcl [-s] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getAllChildrenNumber path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"getEphemerals path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"history"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"listquota path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ls [-s] [-w] [-R] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"printwatches on|off"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"quit"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"reconfig [-s] [-v version] [[-file path] | [-members serverID=host:port1:port2;port3[,...]*]] | [-add serverId=host:port1:port2;port3[,...]]* [-remove serverId[,...]*]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"redo cmdno"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"removewatches path [-c|-d|-a] [-l]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"set [-s] [-v version] path data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"setAcl [-s] [-v version] [-R] path acl"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"setquota -n|-b|-N|-B val path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"stat [-w] path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sync path"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version"})})]})})}),`
`,e.jsx(n.h2,{id:"addauth",children:"addauth"}),`
`,e.jsx(n.p,{children:"Add an authorized user for ACL authentication."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 9] getAcl /acl_digest_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Insufficient permission : /acl_digest_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 10] addauth digest user1:12345"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 11] getAcl /acl_digest_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE="})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# add a super user"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# set zookeeper.DigestAuthenticationProvider.superDigest, e.g.:"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# zookeeper.DigestAuthenticationProvider.superDigest=zookeeper:qW/HnTfCSoQpB5G8LgkwT3IbiFc="})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 12] addauth digest zookeeper:admin"})})]})})}),`
`,e.jsx(n.h2,{id:"close",children:"close"}),`
`,e.jsx(n.p,{children:"Close the current client session."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 0] close"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-09 06:42:22,178 [myid:] - INFO  [main-EventThread:ClientCnxn$EventThread@528] - EventThread shut down for session: 0x10007ab7c550006"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-09 06:42:22,179 [myid:] - INFO  [main:ZooKeeper@1346] - Session: 0x10007ab7c550006 closed"})})]})})}),`
`,e.jsx(n.h2,{id:"config",children:"config"}),`
`,e.jsx(n.p,{children:"Show the current quorum membership configuration."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 17] config"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=[2001:db8:1:0:0:242:ac11:2]:2888:3888:participant"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=[2001:db8:1:0:0:242:ac11:2]:12888:13888:participant"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=[2001:db8:1:0:0:242:ac11:2]:22888:23888:participant"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version=0"})})]})})}),`
`,e.jsx(n.h2,{id:"connect",children:"connect"}),`
`,e.jsx(n.p,{children:"Connect to a ZooKeeper server."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] connect"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-09 06:43:33,179 [myid:localhost:2181] - INFO  [main-SendThread(localhost:2181):ClientCnxn$SendThread@986] - Socket connection established, initiating session, client: /127.0.0.1:35144, server: localhost/127.0.0.1:2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-09 06:43:33,189 [myid:localhost:2181] - INFO  [main-SendThread(localhost:2181):ClientCnxn$SendThread@1421] - Session establishment complete on server localhost/127.0.0.1:2181, sessionid = 0x10007ab7c550007, negotiated timeout = 30000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'connect "localhost:2181,localhost:2182,localhost:2183"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# connect to a remote server"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] connect remoteIP:2181"})})]})})}),`
`,e.jsx(n.h2,{id:"create",children:"create"}),`
`,e.jsx(n.p,{children:"Create a znode."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# persistent node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 7] create /persistent_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /persistent_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# ephemeral node (deleted when session ends)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 8] create -e /ephemeral_node mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /ephemeral_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# persistent sequential node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 9] create -s /persistent_sequential_node mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /persistent_sequential_node0000000176"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# ephemeral sequential node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 10] create -s -e /ephemeral_sequential_node mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /ephemeral_sequential_node0000000174"})})]})})}),`
`,e.jsx(n.p,{children:"Create a node with an ACL schema:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 11] create /zk-node-create-schema mydata digest:user1:+owfoSBn/am19roBPzR1/MfCblE=:crwad"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /zk-node-create-schema"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 12] addauth digest user1:12345"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 13] getAcl /zk-node-create-schema"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE="})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})})]})})}),`
`,e.jsx(n.p,{children:"Create a container node (automatically deleted when its last child is deleted):"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 14] create -c /container_node mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /container_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 15] create -c /container_node/child_1 mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /container_node/child_1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 16] create -c /container_node/child_2 mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /container_node/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 17] delete /container_node/child_1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 18] delete /container_node/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 19] get /container_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"org.apache.zookeeper.KeeperException$NoNodeException: KeeperErrorCode = NoNode for /container_node"})})]})})}),`
`,e.jsxs(n.p,{children:["Create a TTL node (requires ",e.jsx(n.code,{children:"zookeeper.extendedTypesEnabled=true"}),"; otherwise returns ",e.jsx(n.code,{children:"KeeperErrorCode = Unimplemented"}),"):"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 20] create -t 3000 /ttl_node mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /ttl_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# after 3 seconds"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 21] get /ttl_node"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"org.apache.zookeeper.KeeperException$NoNodeException: KeeperErrorCode = NoNode for /ttl_node"})})]})})}),`
`,e.jsx(n.h2,{id:"delete",children:"delete"}),`
`,e.jsx(n.p,{children:"Delete a node at the specified path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] delete /config/topics/test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] ls /config/topics/test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Node does not exist: /config/topics/test"})})]})})}),`
`,e.jsx(n.h2,{id:"deleteall",children:"deleteall"}),`
`,e.jsx(n.p,{children:"Delete a node and all of its descendants."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] ls /config"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[changes, clients, topics]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] deleteall /config"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] ls /config"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Node does not exist: /config"})})]})})}),`
`,e.jsx(n.h2,{id:"delquota",children:"delquota"}),`
`,e.jsx(n.p,{children:"Delete the quota on a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] delquota /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] listquota /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"absolute path is /zookeeper/quota/quota_test/zookeeper_limits"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"quota for /quota_test does not exist."})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# delete specific quota types"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] delquota -n /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] delquota -N /c2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] delquota -b /c3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 6] delquota -B /c4"})})]})})}),`
`,e.jsx(n.h2,{id:"get",children:"get"}),`
`,e.jsx(n.p,{children:"Get the data stored at a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 10] get /latest_producer_id_block"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'{"version":1,"broker":0,"block_start":"0","block_end":"999"}'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -s: also show node stats"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 11] get -s /latest_producer_id_block"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'{"version":1,"broker":0,"block_start":"0","block_end":"999"}'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 0x90000009a"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Sat Jul 28 08:14:09 UTC 2018"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 0x9000000a2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Sat Jul 28 08:14:12 UTC 2018"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 0x90000009a"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0x0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 60"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -w: set a watch on data changes (requires printwatches to be on)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 12] get -w /latest_producer_id_block"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'{"version":1,"broker":0,"block_start":"0","block_end":"999"}'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 13] set /latest_producer_id_block mydata"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WATCHER::"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WatchedEvent state:SyncConnected type:NodeDataChanged path:/latest_producer_id_block"})})]})})}),`
`,e.jsx(n.h2,{id:"getacl",children:"getAcl"}),`
`,e.jsx(n.p,{children:"Get the ACL permissions for a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] create /acl_test mydata ip:127.0.0.1:crwda"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /acl_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] getAcl /acl_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'ip,'127.0.0.1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 6] getAcl /testwatch"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'world,'anyone"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})})]})})}),`
`,e.jsx(n.h2,{id:"getallchildrennumber",children:"getAllChildrenNumber"}),`
`,e.jsx(n.p,{children:"Get the total number of descendant nodes under a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] getAllChildrenNumber /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"73779"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] getAllChildrenNumber /ZooKeeper"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] getAllChildrenNumber /ZooKeeper/quota"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"0"})})]})})}),`
`,e.jsx(n.h2,{id:"getephemerals",children:"getEphemerals"}),`
`,e.jsx(n.p,{children:"Get all ephemeral nodes created by the current session."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'[zkshell: 1] create -e /test-get-ephemerals "ephemeral node"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /test-get-ephemerals"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] getEphemerals"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[/test-get-ephemerals]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] getEphemerals /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[/test-get-ephemerals]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'[zkshell: 4] create -e /test-get-ephemerals-1 "ephemeral node"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /test-get-ephemerals-1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] getEphemerals /test-get-ephemerals"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"test-get-ephemerals     test-get-ephemerals-1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 6] getEphemerals /test-get-ephemerals"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[/test-get-ephemerals-1, /test-get-ephemerals]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 7] getEphemerals /test-get-ephemerals-1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[/test-get-ephemerals-1]"})})]})})}),`
`,e.jsx(n.h2,{id:"history",children:"history"}),`
`,e.jsx(n.p,{children:"Show the most recent 11 commands executed in this session."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 7] history"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"0 - close"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"1 - close"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2 - ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"3 - ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"4 - connect"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"5 - ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"6 - ll"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"7 - history"})})]})})}),`
`,e.jsx(n.h2,{id:"listquota",children:"listquota"}),`
`,e.jsx(n.p,{children:"List the quota configured for a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] listquota /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"absolute path is /zookeeper/quota/c1/zookeeper_limits"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Output quota for /c1 count=-1,bytes=-1=;byteHardLimit=-1;countHardLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Output stat for /c1 count=4,bytes=0"})})]})})}),`
`,e.jsx(n.h2,{id:"ls",children:"ls"}),`
`,e.jsx(n.p,{children:"List the child nodes of a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 36] ls /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[child_1, child_2, child_3]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -s: also show node stats"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 37] ls -s /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[child_1, child_2, child_3]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 0x110000002d"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Thu Mar 07 11:19:07 UTC 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 0x110000002d"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Thu Mar 07 11:19:07 UTC 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 0x1100000033"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0x0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -R: recursively list all descendant nodes"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 38] ls -R /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"/quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"/quota_test/child_1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"/quota_test/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"/quota_test/child_3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -w: set a watch on child changes (requires printwatches to be on)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 39] ls -w /brokers"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[ids, seqid, topics]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 40] delete /brokers/ids"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WATCHER::"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WatchedEvent state:SyncConnected type:NodeChildrenChanged path:/brokers"})})]})})}),`
`,e.jsx(n.h2,{id:"printwatches",children:"printwatches"}),`
`,e.jsx(n.p,{children:"Toggle whether watch events are printed to the console."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 0] printwatches"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"printwatches is on"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] printwatches off"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] printwatches"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"printwatches is off"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] printwatches on"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] printwatches"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"printwatches is on"})})]})})}),`
`,e.jsx(n.h2,{id:"quit",children:"quit"}),`
`,e.jsx(n.p,{children:"Quit the CLI."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[zkshell: 1] quit"})})})})}),`
`,e.jsx(n.h2,{id:"reconfig",children:"reconfig"}),`
`,e.jsxs(n.p,{children:["Change ensemble membership at runtime. Before using this command, read the details in ",e.jsx(n.a,{href:"/admin-ops/dynamic-reconfiguration",children:"Dynamic Reconfiguration"}),", especially the Security section."]}),`
`,e.jsx(n.p,{children:"Prerequisites:"}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:["Set ",e.jsx(n.code,{children:"reconfigEnabled=true"})," in ",e.jsx(n.code,{children:"zoo.cfg"}),"."]}),`
`,e.jsxs(n.li,{children:["Add a super user or set ",e.jsx(n.code,{children:"skipACL"}),"; otherwise you will get ",e.jsx(n.code,{children:"Insufficient permission"}),". For example: ",e.jsx(n.code,{children:"addauth digest zookeeper:admin"}),"."]}),`
`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# Change follower 2 to an observer on port 12182, add observer 5, remove observer 4"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] reconfig --add 2=localhost:2781:2786:observer;12182 --add 5=localhost:2781:2786:observer;2185 -remove 4"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Committed new configuration:"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=localhost:2780:2785:participant;0.0.0.0:2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=localhost:2781:2786:observer;0.0.0.0:12182"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=localhost:2782:2787:participant;0.0.0.0:2183"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5=localhost:2784:2789:observer;0.0.0.0:2185"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version=1c00000002"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -members: specify the full new membership list"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] reconfig -members server.1=localhost:2780:2785:participant;0.0.0.0:2181,server.2=localhost:2781:2786:observer;0.0.0.0:12182,server.3=localhost:2782:2787:participant;0.0.0.0:12183"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Committed new configuration:"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=localhost:2780:2785:participant;0.0.0.0:2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=localhost:2781:2786:observer;0.0.0.0:12182"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=localhost:2782:2787:participant;0.0.0.0:12183"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version=f9fe0000000c"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -file with -v: apply config from file only if current version matches"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] reconfig -file /data/software/zookeeper/zookeeper-test/conf/myNewConfig.txt -v 2100000010"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Committed new configuration:"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=localhost:2780:2785:participant;0.0.0.0:2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=localhost:2781:2786:observer;0.0.0.0:12182"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=localhost:2782:2787:participant;0.0.0.0:2183"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5=localhost:2784:2789:observer;0.0.0.0:2185"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version=220000000c"})})]})})}),`
`,e.jsx(n.h2,{id:"redo",children:"redo"}),`
`,e.jsx(n.p,{children:"Re-execute a command from history by its index."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] history"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"0 - ls /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"1 - get /consumers"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2 - get /hbase"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"3 - ls  /hbase"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"4 - history"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] redo 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[backup-masters, draining, flush-table-proc, hbaseid, master-maintenance, meta-region-server, namespace, online-snapshot, replication, rs, running, splitWAL, switch, table, table-lock]"})})]})})}),`
`,e.jsx(n.h2,{id:"removewatches",children:"removewatches"}),`
`,e.jsx(n.p,{children:"Remove watches from a node."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] get -w /brokers"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"null"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] removewatches /brokers"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WATCHER::"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WatchedEvent state:SyncConnected type:DataWatchRemoved path:/brokers"})})]})})}),`
`,e.jsx(n.h2,{id:"set",children:"set"}),`
`,e.jsx(n.p,{children:"Set or update the data at a path."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 50] set /brokers myNewData"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -s: show the node stats after the update"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 51] set -s /quota_test mydata_for_quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 0x110000002d"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Thu Mar 07 11:19:07 UTC 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 0x1100000038"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Thu Mar 07 11:42:41 UTC 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 0x1100000033"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0x0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 21"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -v: optimistic locking (CAS) — version from dataVersion in stat"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 52] set -v 0 /brokers myNewData"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 53] set -v 0 /brokers myNewData"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"version No is not valid : /brokers"})})]})})}),`
`,e.jsx(n.h2,{id:"setacl",children:"setAcl"}),`
`,e.jsx(n.p,{children:"Set ACL permissions on a node."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 28] addauth digest user1:12345"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 30] setAcl /acl_auth_test auth:user1:12345:crwad"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 31] getAcl /acl_auth_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE="})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})})]})})}),`
`,e.jsxs(n.p,{children:["Use ",e.jsx(n.code,{children:"-R"})," to set ACL recursively on all child nodes:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 32] ls /acl_auth_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[child_1, child_2]"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 33] getAcl /acl_auth_test/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'world,'anyone"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 34] setAcl -R /acl_auth_test auth:user1:12345:crwad"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 35] getAcl /acl_auth_test/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"'digest,'user1:+owfoSBn/am19roBPzR1/MfCblE="})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:": cdrwa"})})]})})}),`
`,e.jsxs(n.p,{children:["Use ",e.jsx(n.code,{children:"-v"})," to set ACL with optimistic locking (version from ",e.jsx(n.code,{children:"aclVersion"})," in stat):"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 36] stat /acl_auth_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 0xf9fc0000001c"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Tue Mar 26 16:50:58 CST 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 0xf9fc0000001c"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Tue Mar 26 16:50:58 CST 2019"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 0xf9fc0000001f"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0x0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 37] setAcl -v 3 /acl_auth_test auth:user1:12345:crwad"})})]})})}),`
`,e.jsx(n.h2,{id:"setquota",children:"setquota"}),`
`,e.jsxs(n.p,{children:["Set a quota on a path. Soft limits (",e.jsx(n.code,{children:"-n"}),", ",e.jsx(n.code,{children:"-b"}),") log a warning but do not block operations. Hard limits (",e.jsx(n.code,{children:"-N"}),", ",e.jsx(n.code,{children:"-B"}),") reject operations that exceed the quota."]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -n: soft limit on node count (includes the node itself)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 18] setquota -n 2 /quota_test"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 19] create /quota_test/child_1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /quota_test/child_1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 20] create /quota_test/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /quota_test/child_2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 21] create /quota_test/child_3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /quota_test/child_3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# soft limit: creation succeeds but a warning is logged"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-07 11:22:36,680 [myid:1] - WARN  [SyncThread:0:DataTree@374] - Quota exceeded: /quota_test count=3 limit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-03-07 11:22:41,861 [myid:1] - WARN  [SyncThread:0:DataTree@374] - Quota exceeded: /quota_test count=4 limit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -b: soft limit on data bytes"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 22] setquota -b 5 /brokers"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'[zkshell: 23] set /brokers "I_love_zookeeper"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# soft limit: write succeeds but a warning is logged"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"WARN  [CommitProcWorkThread-7:DataTree@379] - Quota exceeded: /brokers bytes=4206 limit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -N: hard limit on node count"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] create /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Created /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] setquota -N 2 /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 5] listquota /c1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"absolute path is /zookeeper/quota/c1/zookeeper_limits"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Output quota for /c1 count=-1,bytes=-1=;byteHardLimit=-1;countHardLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Output stat for /c1 count=2,bytes=0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 6] create /c1/ch-3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Count Quota has exceeded : /c1/ch-3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"# -B: hard limit on bytes"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] create /c2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 4] setquota -B 4 /c2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'[zkshell: 5] set /c2 "foo"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'[zkshell: 6] set /c2 "foo-bar"'})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Bytes Quota has exceeded : /c2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 7] get /c2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"foo"})})]})})}),`
`,e.jsx(n.h2,{id:"stat",children:"stat"}),`
`,e.jsx(n.p,{children:"Show the metadata (stat) of a node."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] stat /hbase"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cZxid = 0x4000013d9"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ctime = Wed Jun 27 20:13:07 CST 2018"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mZxid = 0x4000013d9"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"mtime = Wed Jun 27 20:13:07 CST 2018"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"pZxid = 0x500000001"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"cversion = 17"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"aclVersion = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ephemeralOwner = 0x0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLength = 0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"numChildren = 15"})})]})})}),`
`,e.jsx(n.h2,{id:"sync",children:"sync"}),`
`,e.jsx(n.p,{children:"Sync the data of a node between the leader and followers (asynchronous)."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[zkshell: 14] sync /"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[zkshell: 15] Sync is OK"})})]})})}),`
`,e.jsx(n.h2,{id:"version",children:"version"}),`
`,e.jsx(n.p,{children:"Show the ZooKeeper CLI version."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[zkshell: 1] version"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"ZooKeeper"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" CLI"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" version:"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 3.6.0-SNAPSHOT-29f9b2c1c0e832081f94d59a6b88709c5f1bb3ca,"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" built"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" on"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 05/30/2019"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" 09:26"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" GMT"})]})]})})}),`
`,e.jsx(n.h2,{id:"whoami",children:"whoami"}),`
`,e.jsx(n.p,{children:"Show all authentication information for the current session."}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 1] whoami"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Auth scheme: User"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ip: 127.0.0.1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 2] addauth digest user1:12345"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zkshell: 3] whoami"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"Auth scheme: User"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ip: 127.0.0.1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"digest: user1"})})]})})})]})}function d(s={}){const{wrapper:n}=s.components||{};return n?e.jsx(n,{...s,children:e.jsx(l,{...s})}):l(s)}export{a as _markdown,d as default,c as extractedReferences,t as frontmatter,h as structuredData,r as toc};
