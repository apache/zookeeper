<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

<html>
<body>
<h1>ZooKeeper server theory of operation</h1>
ZooKeeperServer is designed to work in standalone mode and also
be extensible so that it can be used to implement the quorum based
version of ZooKeeper.
<p>
ZooKeeper maintains a order when processing requests:
<ul>
<li>All requests will be processed in order.
<li>All responses will return in order.
<li>All watches will be sent in the order that the update takes place.
</ul>
<p>
We will explain the three aspects of ZooKeeperServer: request processing, data
structure maintenance, and session tracking.

<h2>Request processing</h2>

Requests are received by the ServerCnxn. Demarshalling of a request is
done by ClientRequestHandler. After a request has been demarshalled,
ClientRequestHandler invokes the relevant method in ZooKeeper and marshals
the result.
<p>
If the request is just a query, it will be processed by ZooKeeper and returned.
Otherwise, the request will be validated and a transaction will be generated
and logged. This the request will then wait until the request has been logged
before continuing processing.
<p>
Requests are logged as a group. Transactions are queued up and the SyncThread
will process them at predefined intervals. (Currently 20ms) The SyncThread
interacts with ZooKeeperServer the txnQueue. Transactions are added to the
txnQueue of SyncThread via queueItem. When the transaction has been synced to
disk, its callback will be invoked which will cause the request processing to
be completed.

<h2>Data structure maintenance</h2>

ZooKeeper data is stored in-memory. Each znode is stored in a DataNode object.
This object is accessed through a hash table that maps paths to DataNodes.
DataNodes also organize themselves into a tree. This tree is only used for
serializing nodes.
<p>
We guarantee that changes to nodes are stored to non-volatile media before
responding to a client. We do this quickly by writing changes as a sequence
of transactions in a log file. Even though we flush transactions as a group,
we need to avoid seeks as much as possible. Also, since the server can fail
at any point, we need to be careful of partial records.
<p>
We address the above problems by
<ul>
<li>Pre-allocating 1M chunks of file space. This allows us to append to the
file without causing seeks to update file size. It also means that we need
to check for the end of the log by looking for a zero length transaction
rather than simply end of file.
<li>Writing a signature at the end of each transaction. When processing
transactions, we only use transactions that have a valid signature at the end.
</ul>
<p>
As the server runs, the log file will grow quite large. To avoid long startup
times we periodically take a snapshot of the tree of DataNodes. We cannot
take the snapshot synchronously as the data takes a while to write out, so
instead we asynchronously write out the tree. This means that we end up
with a "corrupt" snapshot of the data tree. More formally if we define T
to be the real snapshot of the tree at the time we begin taking the snapshot
and l as the sequence of transactions that are applied to the tree between
the time the snapshot begins and the time the snapshot completes, we write
to disk T+l' where l' is a subset of the transactions in l. While we do not
have a way of figuring out which transactions make up l', it doesn't really
matter. T+l'+l = T+l since the transactions we log are idempotent (applying
the transaction multiple times has the same result as applying the transaction
once). So when we restore the snapshot we also play all transactions in the log
that occur after the snapshot was begun. We can easily figure out where to
start the replay because we start a new logfile when we start a snapshot. Both
the snapshot file and log file have a numeric suffix that represent the
transaction id that created the respective files.

<h2>Session tracking</h2>
Rather than tracking sessions exactly, we track them in batches. That are
processed at fixed intervals. This is easier to implement than exact
session tracking and it is more efficient in terms of performance. It also 
provides a small grace period for session renewal.
</body>
</html>