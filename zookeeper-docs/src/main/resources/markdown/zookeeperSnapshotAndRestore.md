<!--
Copyright 2002-2004 The Apache Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# ZooKeeper Snapshot and Restore Guide

Zookeeper is designed to withstand machine failures. A Zookeeper cluster can automatically recover 
from temporary failures such as machine reboot. It can also tolerate up to (N-1)/2 permanent 
failures for a cluster of N members due to hardware failures or disk corruption, etc. When a member 
permanently fails, it loses access to the cluster. If the cluster permanently loses more than 
(N-1)/2 members, it disastrously fails and loses quorum. Once the quorum is lost, the cluster 
cannot reach consensus and therefore cannot continue to accept updates.

To recover from such disastrous failures, Zookeeper provides snapshot and restore functionalities to
restore a cluster from a snapshot.

1. Snapshot and restore operate on the connected server via Admin Server APIs
1. Snapshot and restore are rate limited to protect the server from being overloaded
1. Snapshot and restore require authentication and authorization on the root path with ALL permission.
The supported auth schemas are digest, x509 and IP.
   
* [Snapshot](#zookeeper_snapshot)
* [Restore](#zookeeper_restore)  

<a name="zookeeper_snapshot"></a>

## Snapshot
Recovering a cluster needs a snapshot from a ZooKeeper cluster. Users can periodically take
snapshots from a live server which has the highest zxid and stream out data to a local 
or external storage/file system (e.g., S3).

 ```bash
 # The snapshot command takes snapshot from the server it connects to and rate limited to once every 5 mins by default
 curl -H 'Authorization: digest root:root_passwd' http://hostname:adminPort/commands/snapshot?streaming=true --output snapshotFileName
 ```

<a name="zookeeper_restore"></a>
## Restore

Restoring a cluster needs a single snapshot as input stream. Restore can be used for recovering a 
cluster for quorum lost or building a brand-new cluster with seed data. 

All members should restore using the same snapshot. The following are the recommended steps:
 
- Blocking traffic on the client port or client secure port before restore starts
- Take a snapshot of the latest database state using the snapshot admin server command if applicable
- For each server
  - Moving the files in dataDir and dataLogDir to different location to prevent the restored database 
    from being overwritten when server restarts after restore
  - Restore the server using restore admin server command
- Unblocking traffic on the client port or client secure port after restore completes

 ```bash
 # The restore command takes a snapshot as input stream and restore the db of the server it connects. It is rate limited to once every 5 mins by default
 curl -H 'Content-Type:application/octet-stream' -H 'Authorization: digest root:root_passwd' -POST http://hostname:adminPort/commands/restore --data-binary "@snapshotFileName" 
 ```
