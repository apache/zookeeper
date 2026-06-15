import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let a=`ZooKeeper's behavior is governed by the ZooKeeper configuration
file. This file is designed so that the exact same file can be used by
all the servers that make up a ZooKeeper server assuming the disk
layouts are the same. If servers use different configuration files, care
must be taken to ensure that the list of servers in all of the different
configuration files match.

<Callout type="info">
  In 3.5.0 and later, some of these parameters should be placed in a dynamic
  configuration file. If they are placed in the static configuration file,
  ZooKeeper will automatically move them over to the dynamic configuration file.
  See [Dynamic Reconfiguration](/admin-ops/dynamic-reconfiguration) for more
  information.
</Callout>

## Minimum Configuration

Here are the minimum configuration keywords that must be defined
in the configuration file:

* *clientPort* :
  the port to listen for client connections; that is, the
  port that clients attempt to connect to.

* *secureClientPort* :
  the port to listen on for secure client connections using SSL.
  **clientPort** specifies
  the port for plaintext connections while **secureClientPort** specifies the port for SSL
  connections. Specifying both enables mixed-mode while omitting
  either will disable that mode.
  Note that SSL feature will be enabled when user plugs-in
  zookeeper.serverCnxnFactory, zookeeper.clientCnxnSocket as Netty.

* *observerMasterPort* :
  the port to listen for observer connections; that is, the
  port that observers attempt to connect to.
  if the property is set then the server will host observer connections
  when in follower mode in addition to when in leader mode and correspondingly
  attempt to connect to any voting peer when in observer mode.

* *dataDir* :
  the location where ZooKeeper will store the in-memory
  database snapshots and, unless specified otherwise, the
  transaction log of updates to the database.

  <Callout type="info">
    Be careful where you put the transaction log. A dedicated transaction log
    device is key to consistent good performance. Putting the log on a busy
    device will adversely affect performance.
  </Callout>

* *tickTime* :
  the length of a single tick, which is the basic time unit
  used by ZooKeeper, as measured in milliseconds. It is used to
  regulate heartbeats, and timeouts. For example, the minimum
  session timeout will be two ticks.

## Advanced Configuration

The configuration settings in the section are optional. You can
use them to further fine tune the behaviour of your ZooKeeper servers.
Some can also be set using Java system properties, generally of the
form *zookeeper.keyword*. The exact system
property, when available, is noted below.

* *dataLogDir* :
  (No Java system property)
  This option will direct the machine to write the
  transaction log to the **dataLogDir** rather than the **dataDir**. This allows a dedicated log
  device to be used, and helps avoid competition between logging
  and snapshots.

  <Callout type="info">
    Having a dedicated log device has a large impact on throughput and stable
    latencies. It is highly recommended dedicating a log device and set
    **dataLogDir** to point to a directory on that device, and then make sure to
    point **dataDir** to a directory *not* residing on that device.
  </Callout>

* *globalOutstandingLimit* :
  (Java system property: **zookeeper.globalOutstandingLimit.**)
  Clients can submit requests faster than ZooKeeper can
  process them, especially if there are a lot of clients. To
  prevent ZooKeeper from running out of memory due to queued
  requests, ZooKeeper will throttle clients so that there are no
  more than globalOutstandingLimit outstanding requests across
  entire ensemble, equally divided. The default limit is 1,000
  and, for example, with 3 members each of them will have
  1000 / 2 = 500 individual limit.

* *preAllocSize* :
  (Java system property: **zookeeper.preAllocSize**)
  To avoid seeks ZooKeeper allocates space in the
  transaction log file in blocks of preAllocSize kilobytes. The
  default block size is 64M. One reason for changing the size of
  the blocks is to reduce the block size if snapshots are taken
  more often. (Also, see **snapCount** and **snapSizeLimitInKb**).

* *snapCount* :
  (Java system property: **zookeeper.snapCount**)
  ZooKeeper records its transactions using snapshots and
  a transaction log (think write-ahead log). The number of
  transactions recorded in the transaction log before a snapshot
  can be taken (and the transaction log rolled) is determined
  by snapCount. In order to prevent all of the machines in the quorum
  from taking a snapshot at the same time, each ZooKeeper server
  will take a snapshot when the number of transactions in the transaction log
  reaches a runtime generated random value in the \\[snapCount/2+1, snapCount]
  range. The default snapCount is 100,000.

* *commitLogCount* \\* :
  (Java system property: **zookeeper.commitLogCount**)
  Zookeeper maintains an in-memory list of last committed requests for fast synchronization with
  followers when the followers are not too behind. This improves sync performance in case when your
  snapshots are large (>100,000). The default value is 500 which is the recommended minimum.

* *snapSizeLimitInKb* :
  (Java system property: **zookeeper.snapSizeLimitInKb**)
  ZooKeeper records its transactions using snapshots and
  a transaction log (think write-ahead log). The total size in bytes allowed
  in the set of transactions recorded in the transaction log before a snapshot
  can be taken (and the transaction log rolled) is determined
  by snapSize. In order to prevent all of the machines in the quorum
  from taking a snapshot at the same time, each ZooKeeper server
  will take a snapshot when the size in bytes of the set of transactions in the
  transaction log reaches a runtime generated random value in the \\[snapSize/2+1, snapSize]
  range. Each file system has a minimum standard file size and in order
  to for valid functioning of this feature, the number chosen must be larger
  than that value. The default snapSizeLimitInKb is 4,194,304 (4GB).
  A non-positive value will disable the feature.

* *txnLogSizeLimitInKb* :
  (Java system property: **zookeeper.txnLogSizeLimitInKb**)
  Zookeeper transaction log file can also be controlled more
  directly using txnLogSizeLimitInKb. Larger txn logs can lead to
  slower follower syncs when sync is done using transaction log.
  This is because leader has to scan through the appropriate log
  file on disk to find the transaction to start sync from.
  This feature is turned off by default and snapCount and snapSizeLimitInKb are the
  only values that limit transaction log size. When enabled
  Zookeeper will roll the log when any of the limits is hit.
  Please note that actual log size can exceed this value by the size
  of the serialized transaction. On the other hand, if this value is
  set too close to (or smaller than) **preAllocSize**,
  it can cause Zookeeper to roll the log for every transaction. While
  this is not a correctness issue, this may cause severely degraded
  performance. To avoid this and to get most out of this feature, it is
  recommended to set the value to N \\* **preAllocSize**
  where N >= 2.

* *maxCnxns* :
  (Java system property: **zookeeper.maxCnxns**)
  Limits the total number of concurrent connections that can be made to a
  zookeeper server (per client Port of each server ). This is used to prevent certain
  classes of DoS attacks. The default is 0 and setting it to 0 entirely removes
  the limit on total number of concurrent connections. Accounting for the
  number of connections for serverCnxnFactory and a secureServerCnxnFactory is done
  separately, so a peer is allowed to host up to 2\\*maxCnxns provided they are of appropriate types.

* *maxClientCnxns* :
  (No Java system property)
  Limits the number of concurrent connections (at the socket
  level) that a single client, identified by IP address, may make
  to a single member of the ZooKeeper ensemble. This is used to
  prevent certain classes of DoS attacks, including file
  descriptor exhaustion. The default is 60. Setting this to 0
  entirely removes the limit on concurrent connections.

* *clientPortAddress* :
  **New in 3.3.0:** the
  address (ipv4, ipv6 or hostname) to listen for client
  connections; that is, the address that clients attempt
  to connect to. This is optional, by default we bind in
  such a way that any connection to the **clientPort** for any
  address/interface/nic on the server will be
  accepted.

* *minSessionTimeout* :
  (No Java system property)
  **New in 3.3.0:** the
  minimum session timeout in milliseconds that the server
  will allow the client to negotiate. Defaults to 2 times
  the **tickTime**.

* *maxSessionTimeout* :
  (No Java system property)
  **New in 3.3.0:** the
  maximum session timeout in milliseconds that the server
  will allow the client to negotiate. Defaults to 20 times
  the **tickTime**.

* *fsync.warningthresholdms* :
  (Java system property: **zookeeper.fsync.warningthresholdms**)
  **New in 3.3.4:** A
  warning message will be output to the log whenever an
  fsync in the Transactional Log (WAL) takes longer than
  this value. The values is specified in milliseconds and
  defaults to 1000. This value can only be set as a
  system property.

* *maxResponseCacheSize* :
  (Java system property: **zookeeper.maxResponseCacheSize**)
  When set to a positive integer, it determines the size
  of the cache that stores the serialized form of recently
  read records. Helps save the serialization cost on
  popular znodes. The metrics **response\\_packet\\_cache\\_hits**
  and **response\\_packet\\_cache\\_misses** can be used to tune
  this value to a given workload. The feature is turned on
  by default with a value of 400, set to 0 or a negative
  integer to turn the feature off.

* *maxGetChildrenResponseCacheSize* :
  (Java system property: **zookeeper.maxGetChildrenResponseCacheSize**)
  **New in 3.6.0:**
  Similar to **maxResponseCacheSize**, but applies to get children
  requests. The metrics **response\\_packet\\_get\\_children\\_cache\\_hits**
  and **response\\_packet\\_get\\_children\\_cache\\_misses** can be used to tune
  this value to a given workload. The feature is turned on
  by default with a value of 400, set to 0 or a negative
  integer to turn the feature off.

* *autopurge.snapRetainCount* :
  (No Java system property)
  **New in 3.4.0:**
  When enabled, ZooKeeper auto purge feature retains
  the **autopurge.snapRetainCount** most
  recent snapshots and the corresponding transaction logs in the
  **dataDir** and **dataLogDir** respectively and deletes the rest.
  Defaults to 3. Minimum value is 3.

* *autopurge.purgeInterval* :
  (No Java system property)
  **New in 3.4.0:** The
  time interval in hours for which the purge task has to
  be triggered. Set to a positive integer (1 and above)
  to enable the auto purging. Defaults to 0.
  **Suffix support added in 3.10.0:** The interval is specified as an integer with an optional suffix to indicate the time unit.
  Supported suffixes are: \`ms\` for milliseconds, \`s\` for seconds, \`m\` for minutes, \`h\` for hours, and \`d\` for days.
  For example, "10m" represents 10 minutes, and "5h" represents 5 hours.
  If no suffix is provided, the default unit is hours.

* *syncEnabled* :
  (Java system property: **zookeeper.observer.syncEnabled**)
  **New in 3.4.6, 3.5.0:**
  The observers now log transaction and write snapshot to disk
  by default like the participants. This reduces the recovery time
  of the observers on restart. Set to "false" to disable this
  feature. Default is "true"

* *extendedTypesEnabled* :
  (Java system property only: **zookeeper.extendedTypesEnabled**)
  **New in 3.5.4, 3.6.0:** Define to \`true\` to enable
  extended features such as the creation of [TTL Nodes](/developer/programmers-guide/data-model#ttl-nodes).
  They are disabled by default. IMPORTANT: when enabled server IDs must
  be less than 255 due to internal limitations.

* *emulate353TTLNodes* :
  (Java system property only:**zookeeper.emulate353TTLNodes**).
  **New in 3.5.4, 3.6.0:** Due to \\[ZOOKEEPER-2901]
  ([https://issues.apache.org/jira/browse/ZOOKEEPER-2901](https://issues.apache.org/jira/browse/ZOOKEEPER-2901)) TTL nodes
  created in version 3.5.3 are not supported in 3.5.4/3.6.0. However, a workaround is provided via the
  zookeeper.emulate353TTLNodes system property. If you used TTL nodes in ZooKeeper 3.5.3 and need to maintain
  compatibility set **zookeeper.emulate353TTLNodes** to \`true\` in addition to
  **zookeeper.extendedTypesEnabled**. NOTE: due to the bug, server IDs
  must be 127 or less. Additionally, the maximum support TTL value is \`1099511627775\` which is smaller
  than what was allowed in 3.5.3 (\`1152921504606846975\`)

* *watchManagerName* :
  (Java system property only: **zookeeper.watchManagerName**)
  **New in 3.6.0:** Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  New watcher manager WatchManagerOptimized is added to optimize the memory overhead in heavy watch use cases. This
  config is used to define which watcher manager to be used. Currently, we only support WatchManager and
  WatchManagerOptimized.

* *watcherCleanThreadsNum* :
  (Java system property only: **zookeeper.watcherCleanThreadsNum**)
  **New in 3.6.0:** Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, this config is used to decide how
  many thread is used in the WatcherCleaner. More thread usually means larger clean up throughput. The
  default value is 2, which is good enough even for heavy and continuous session closing/recreating cases.

* *watcherCleanThreshold* :
  (Java system property only: **zookeeper.watcherCleanThreshold**)
  **New in 3.6.0:** Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
  heavy, batch processing will reduce the cost and improve the performance. This setting is used to decide
  the batch size. The default one is 1000, we don't need to change it if there is no memory or clean up
  speed issue.

* *watcherCleanIntervalInSeconds* :
  (Java system property only:**zookeeper.watcherCleanIntervalInSeconds**)
  **New in 3.6.0:** Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
  heavy, batch processing will reduce the cost and improve the performance. Besides watcherCleanThreshold,
  this setting is used to clean up the dead watchers after certain time even the dead watchers are not larger
  than watcherCleanThreshold, so that we won't leave the dead watchers there for too long. The default setting
  is 10 minutes, which usually don't need to be changed.

* *maxInProcessingDeadWatchers* :
  (Java system property only: **zookeeper.maxInProcessingDeadWatchers**)
  **New in 3.6.0:** Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  This is used to control how many backlog can we have in the WatcherCleaner, when it reaches this number, it will
  slow down adding the dead watcher to WatcherCleaner, which will in turn slow down adding and closing
  watchers, so that we can avoid OOM issue. By default there is no limit, you can set it to values like
  watcherCleanThreshold \\* 1000.

* *bitHashCacheSize* :
  (Java system property only: **zookeeper.bitHashCacheSize**)
  **New 3.6.0**: Added in [ZOOKEEPER-1179](https://issues.apache.org/jira/browse/ZOOKEEPER-1179)
  This is the setting used to decide the HashSet cache size in the BitHashSet implementation. Without HashSet, we
  need to use O(N) time to get the elements, N is the bit numbers in elementBits. But we need to
  keep the size small to make sure it doesn't cost too much in memory, there is a trade off between memory
  and time complexity. The default value is 10, which seems a relatively reasonable cache size.

* *fastleader.minNotificationInterval* :
  (Java system property: **zookeeper.fastleader.minNotificationInterval**)
  Lower bound for length of time between two consecutive notification
  checks on the leader election. This interval determines how long a
  peer waits to check the set of election votes and effects how
  quickly an election can resolve. The interval follows a backoff
  strategy from the configured minimum (this) and the configured maximum
  (fastleader.maxNotificationInterval) for long elections.

* *fastleader.maxNotificationInterval* :
  (Java system property: **zookeeper.fastleader.maxNotificationInterval**)
  Upper bound for length of time between two consecutive notification
  checks on the leader election. This interval determines how long a
  peer waits to check the set of election votes and effects how
  quickly an election can resolve. The interval follows a backoff
  strategy from the configured minimum (fastleader.minNotificationInterval)
  and the configured maximum (this) for long elections.

* *connectionMaxTokens* :
  (Java system property: **zookeeper.connection\\_throttle\\_tokens**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the maximum number of tokens in the token-bucket.
  When set to 0, throttling is disabled. Default is 0.

* *connectionTokenFillTime* :
  (Java system property: **zookeeper.connection\\_throttle\\_fill\\_time**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the interval in milliseconds when the token bucket is re-filled with
  *connectionTokenFillCount* tokens. Default is 1.

* *connectionTokenFillCount* :
  (Java system property: **zookeeper.connection\\_throttle\\_fill\\_count**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the number of tokens to add to the token bucket every
  *connectionTokenFillTime* milliseconds. Default is 1.

* *connectionFreezeTime* :
  (Java system property: **zookeeper.connection\\_throttle\\_freeze\\_time**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the interval in milliseconds when the dropping
  probability is adjusted. When set to -1, probabilistic dropping is disabled.
  Default is -1.

* *connectionDropIncrease* :
  (Java system property: **zookeeper.connection\\_throttle\\_drop\\_increase**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the dropping probability to increase. The throttler
  checks every *connectionFreezeTime* milliseconds and if the token bucket is
  empty, the dropping probability will be increased by *connectionDropIncrease*.
  The default is 0.02.

* *connectionDropDecrease* :
  (Java system property: **zookeeper.connection\\_throttle\\_drop\\_decrease**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping.
  This parameter defines the dropping probability to decrease. The throttler
  checks every *connectionFreezeTime* milliseconds and if the token bucket has
  more tokens than a threshold, the dropping probability will be decreased by
  *connectionDropDecrease*. The threshold is *connectionMaxTokens* \\*
  *connectionDecreaseRatio*. The default is 0.002.

* *connectionDecreaseRatio* :
  (Java system property: **zookeeper.connection\\_throttle\\_decrease\\_ratio**)
  **New in 3.6.0:**
  This is one of the parameters to tune the server-side connection throttler,
  which is a token-based rate limiting mechanism with optional probabilistic
  dropping. This parameter defines the threshold to decrease the dropping
  probability. The default is 0.

* *zookeeper.connection\\_throttle\\_weight\\_enabled* :
  (Java system property only)
  **New in 3.6.0:**
  Whether to consider connection weights when throttling. Only useful when connection throttle is enabled, that is, connectionMaxTokens is larger than 0. The default is false.

* *zookeeper.connection\\_throttle\\_global\\_session\\_weight* :
  (Java system property only)
  **New in 3.6.0:**
  The weight of a global session. It is the number of tokens required for a global session request to get through the connection throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 3.

* *zookeeper.connection\\_throttle\\_local\\_session\\_weight* :
  (Java system property only)
  **New in 3.6.0:**
  The weight of a local session. It is the number of tokens required for a local session request to get through the connection throttler. It has to be a positive integer no larger than the weight of a global session or a renew session. The default is 1.

* *zookeeper.connection\\_throttle\\_renew\\_session\\_weight* :
  (Java system property only)
  **New in 3.6.0:**
  The weight of renewing a session. It is also the number of tokens required for a reconnect request to get through the throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 2.

* *clientPortListenBacklog* :
  (No Java system property)
  **New in 3.4.14, 3.5.5, 3.6.0:**
  The socket backlog length for the ZooKeeper server socket. This controls
  the number of requests that will be queued server-side to be processed
  by the ZooKeeper server. Connections that exceed this length will receive
  a network timeout (30s) which may cause ZooKeeper session expiry issues.
  By default, this value is unset (\`-1\`) which, on Linux, uses a backlog of
  \`50\`. This value must be a positive number.

* *serverCnxnFactory* :
  (Java system property: **zookeeper.serverCnxnFactory**)
  Specifies ServerCnxnFactory implementation.
  This should be set to \`NettyServerCnxnFactory\` in order to use TLS based server communication.
  Default is \`NIOServerCnxnFactory\`.

* *flushDelay* :
  (Java system property: **zookeeper.flushDelay**)
  Time in milliseconds to delay the flush of the commit log.
  Does not affect the limit defined by *maxBatchSize*.
  Disabled by default (with value 0). Ensembles with high write rates
  may see throughput improved with a value of 10-20 ms.

* *maxWriteQueuePollTime* :
  (Java system property: **zookeeper.maxWriteQueuePollTime**)
  If *flushDelay* is enabled, this determines the amount of time in milliseconds
  to wait before flushing when no new requests are being queued.
  Set to *flushDelay*/3 by default (implicitly disabled by default).

* *maxBatchSize* :
  (Java system property: **zookeeper.maxBatchSize**)
  The number of transactions allowed in the server before a flush of the
  commit log is triggered.
  Does not affect the limit defined by *flushDelay*.
  Default is 1000.

* *enforceQuota* :
  (Java system property: **zookeeper.enforceQuota**)
  **New in 3.7.0:**
  Enforce the quota check. When enabled and the client exceeds the total bytes or children count hard quota under a znode, the server will reject the request and reply the client a \`QuotaExceededException\` by force.
  The default value is: false. Exploring [quota feature](/admin-ops/quota-guide) for more details.

* *requestThrottleLimit* :
  (Java system property: **zookeeper.request\\_throttle\\_max\\_requests**)
  **New in 3.6.0:**
  The total number of outstanding requests allowed before the RequestThrottler starts stalling. When set to 0, throttling is disabled. The default is 0.

* *requestThrottleStallTime* :
  (Java system property: **zookeeper.request\\_throttle\\_stall\\_time**)
  **New in 3.6.0:**
  The maximum time (in milliseconds) for which a thread may wait to be notified that it may proceed processing a request. The default is 100.

* *requestThrottleDropStale* :
  (Java system property: **request\\_throttle\\_drop\\_stale**)
  **New in 3.6.0:**
  When enabled, the throttler will drop stale requests rather than issue them to the request pipeline. A stale request is a request sent by a connection that is now closed, and/or a request that will have a request latency higher than the sessionTimeout. The default is true.

* *requestStaleLatencyCheck* :
  (Java system property: **zookeeper.request\\_stale\\_latency\\_check**)
  **New in 3.6.0:**
  When enabled, a request is considered stale if the request latency is higher than its associated session timeout. Disabled by default.

* *requestStaleConnectionCheck* :
  (Java system property: **zookeeper.request\\_stale\\_connection\\_check**)
  **New in 3.6.0:**
  When enabled, a request is considered stale if the request's connection has closed. Enabled by default.

* *zookeeper.request\\_throttler.shutdownTimeout* :
  (Java system property only)
  **New in 3.6.0:**
  The time (in milliseconds) the RequestThrottler waits for the request queue to drain during shutdown before it shuts down forcefully. The default is 10000.

* *advancedFlowControlEnabled* :
  (Java system property: **zookeeper.netty.advancedFlowControl.enabled**)
  Using accurate flow control in netty based on the status of ZooKeeper
  pipeline to avoid direct buffer OOM. It will disable the AUTO\\_READ in
  Netty.

* *enableEagerACLCheck* :
  (Java system property only: **zookeeper.enableEagerACLCheck**)
  When set to "true", enables eager ACL check on write requests on each local
  server before sending the requests to quorum. Default is "false".

* *maxConcurrentSnapSyncs* :
  (Java system property: **zookeeper.leader.maxConcurrentSnapSyncs**)
  The maximum number of snap syncs a leader or a follower can serve at the same
  time. The default is 10.

* *maxConcurrentDiffSyncs* :
  (Java system property: **zookeeper.leader.maxConcurrentDiffSyncs**)
  The maximum number of diff syncs a leader or a follower can serve at the same
  time. The default is 100.

* *digest.enabled* :
  (Java system property only: **zookeeper.digest.enabled**)
  **New in 3.6.0:**
  The digest feature is added to detect the data inconsistency inside
  ZooKeeper when loading database from disk, catching up and following
  leader, its doing incrementally hash check for the DataTree based on
  the adHash paper mentioned in

  [https://cseweb.ucsd.edu/\\~daniele/papers/IncHash.pdf](https://cseweb.ucsd.edu/~daniele/papers/IncHash.pdf)

  The idea is simple, the hash value of DataTree will be updated incrementally
  based on the changes to the set of data. When the leader is preparing the txn,
  it will pre-calculate the hash of the tree based on the changes happened with
  formula:

  \`current_hash = current_hash + hash(new node data) - hash(old node data)\`

  If it’s creating a new node, the hash(old node data) will be 0, and if it’s a
  delete node op, the hash(new node data) will be 0.

  This hash will be associated with each txn to represent the expected hash value
  after applying the txn to the data tree, it will be sent to followers with
  original proposals. Learner will compare the actual hash value with the one in
  the txn after applying the txn to the data tree, and report mismatch if it’s not
  the same.

  These digest value will also be persisted with each txn and snapshot on the disk,
  so when servers restarted and load data from disk, it will compare and see if
  there is hash mismatch, which will help detect data loss issue on disk.

  For the actual hash function, we’re using CRC internally, it’s not a collisionless
  hash function, but it’s more efficient compared to collisionless hash, and the
  collision possibility is really really rare and can already meet our needs here.

  This feature is backward and forward compatible, so it can safely roll upgrade,
  downgrade, enabled and later disabled without any compatible issue. Here are the
  scenarios have been covered and tested:

  1. When leader runs with new code while follower runs with old one, the digest will
     be appended to the end of each txn, follower will only read header and txn data,
     digest value in the txn will be ignored. It won't affect the follower reads and
     processes the next txn.
  2. When leader runs with old code while follower runs with new one, the digest won't
     be sent with txn, when follower tries to read the digest, it will throw EOF which
     is caught and handled gracefully with digest value set to null.
  3. When loading old snapshot with new code, it will throw IOException when trying to
     read the non-exist digest value, and the exception will be caught and digest will
     be set to null, which means we won't compare digest when loading this snapshot,
     which is expected to happen during rolling upgrade
  4. When loading new snapshot with old code, it will finish successfully after deserializing
     the data tree, the digest value at the end of snapshot file will be ignored
  5. The scenarios of rolling restart with flags change are similar to the 1st and 2nd
     scenarios discussed above, if the leader enabled but follower not, digest value will
     be ignored, and follower won't compare the digest during runtime; if leader disabled
     but follower enabled, follower will get EOF exception which is handled gracefully.

  Note: the current digest calculation excluded nodes under /zookeeper
  due to the potential inconsistency in the /zookeeper/quota stat node,
  we can include that after that issue is fixed.

  By default, this feature is enabled, set "false" to disable it.

* *snapshot.compression.method* :
  (Java system property: **zookeeper.snapshot.compression.method**)
  **New in 3.6.0:**
  This property controls whether or not ZooKeeper should compress snapshots
  before storing them on disk (see [ZOOKEEPER-3179](https://issues.apache.org/jira/browse/ZOOKEEPER-3179)).
  Possible values are:
  * "": Disabled (no snapshot compression). This is the default behavior.
  * "gz": See [gzip compression](https://en.wikipedia.org/wiki/Gzip).
  * "snappy": See [Snappy compression](https://en.wikipedia.org/wiki/Snappy_\\(compression\\)).

* *snapshot.trust.empty* :
  (Java system property: **zookeeper.snapshot.trust.empty**)
  **New in 3.5.6:**
  This property controls whether or not ZooKeeper should treat missing
  snapshot files as a fatal state that can't be recovered from.
  Set to true to allow ZooKeeper servers recover without snapshot
  files. This should only be set during upgrading from old versions of
  ZooKeeper (3.4.x, pre 3.5.3) where ZooKeeper might only have transaction
  log files but without presence of snapshot files. If the value is set
  during upgrade, we recommend setting the value back to false after upgrading
  and restart ZooKeeper process so ZooKeeper can continue normal data
  consistency check during recovery process.
  Default value is false.

* *audit.enable* :
  (Java system property: **zookeeper.audit.enable**)
  **New in 3.6.0:**
  By default audit logs are disabled. Set to "true" to enable it. Default value is "false".
  See the [ZooKeeper audit logs](/admin-ops/monitor-and-audit-logs) for more information.

* *audit.impl.class* :
  (Java system property: **zookeeper.audit.impl.class**)
  **New in 3.6.0:**
  Class to implement the audit logger. By default logback based audit logger org.apache.zookeeper.audit
  .Slf4jAuditLogger is used.
  See the [ZooKeeper audit logs](/admin-ops/monitor-and-audit-logs) for more information.

* *largeRequestMaxBytes* :
  (Java system property: **zookeeper.largeRequestMaxBytes**)
  **New in 3.6.0:**
  The maximum number of bytes of all inflight large request. The connection will be closed if a coming large request causes the limit exceeded. The default is 100 \\* 1024 \\* 1024.

* *largeRequestThreshold* :
  (Java system property: **zookeeper.largeRequestThreshold**)
  **New in 3.6.0:**
  The size threshold after which a request is considered a large request. If it is -1, then all requests are considered small, effectively turning off large request throttling. The default is -1.

* *outstandingHandshake.limit*
  (Java system property only: **zookeeper.netty.server.outstandingHandshake.limit**)
  The maximum in-flight TLS handshake connections could have in ZooKeeper,
  the connections exceed this limit will be rejected before starting handshake.
  This setting doesn't limit the max TLS concurrency, but helps avoid herd
  effect due to TLS handshake timeout when there are too many in-flight TLS
  handshakes. Set it to something like 250 is good enough to avoid herd effect.

* *netty.server.earlyDropSecureConnectionHandshakes*
  (Java system property: **zookeeper.netty.server.earlyDropSecureConnectionHandshakes**)
  If the ZooKeeper server is not fully started, drop TCP connections before performing the TLS handshake.
  This is useful in order to prevent flooding the server with many concurrent TLS handshakes after a restart.
  Please note that if you enable this flag the server won't answer to 'ruok' commands if it is not fully started.
  The behaviour of dropping the connection has been introduced in ZooKeeper 3.7 and it was not possible to disable it.
  Since 3.7.1 and 3.8.0 this feature is disabled by default.

* *throttledOpWaitTime*
  (Java system property: **zookeeper.throttled\\_op\\_wait\\_time**)
  The time in the RequestThrottler queue longer than which a request will be marked as throttled.
  A throttled requests will not be processed other than being fed down the pipeline of the server it belongs
  to preserve the order of all requests.
  The FinalProcessor will issue an error response (new error code: ZTHROTTLEDOP) for these undigested requests.
  The intent is for the clients not to retry them immediately.
  When set to 0, no requests will be throttled. The default is 0.

* *learner.closeSocketAsync*
  (Java system property: **zookeeper.learner.closeSocketAsync**)
  (Java system property: **learner.closeSocketAsync**)(Added for backward compatibility)
  **New in 3.7.0:**
  When enabled, a learner will close the quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time, block the shutdown process, potentially delay a new leader election, and leave the quorum unavailable. Closing the socket asynchronously avoids blocking the shutdown process despite the long socket closing time and a new leader election can be started while the socket being closed.
  The default is false.

* *leader.closeSocketAsync*
  (Java system property: **zookeeper.leader.closeSocketAsync**)
  (Java system property: **leader.closeSocketAsync**)(Added for backward compatibility)
  **New in 3.7.0:**
  When enabled, the leader will close a quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time. If disconnecting a follower is initiated in ping() because of a failed SyncLimitCheck then the long socket closing time will block the sending of pings to other followers. Without receiving pings, the other followers will not send session information to the leader, which causes sessions to expire. Setting this flag to true ensures that pings will be sent regularly.
  The default is false.

* *learner.asyncSending*
  (Java system property: **zookeeper.learner.asyncSending**)
  (Java system property: **learner.asyncSending**)(Added for backward compatibility)
  **New in 3.7.0:**
  The sending and receiving packets in Learner were done synchronously in a critical section. An untimely network issue could cause the followers to hang (see [ZOOKEEPER-3575](https://issues.apache.org/jira/browse/ZOOKEEPER-3575) and [ZOOKEEPER-4074](https://issues.apache.org/jira/browse/ZOOKEEPER-4074)). The new design moves sending packets in Learner to a separate thread and sends the packets asynchronously. The new design is enabled with this parameter (learner.asyncSending).
  The default is false.

* *forward\\_learner\\_requests\\_to\\_commit\\_processor\\_disabled*
  (Java system property: **zookeeper.forward\\_learner\\_requests\\_to\\_commit\\_processor\\_disabled**)
  When this property is set, the requests from learners won't be enqueued to
  CommitProcessor queue, which will help save the resources and GC time on
  leader. The default value is false.

* *serializeLastProcessedZxid.enabled*
  (Java system property: **zookeeper.serializeLastProcessedZxid.enabled**)
  **New in 3.9.0:**
  If enabled, ZooKeeper serializes the lastProcessedZxid when snapshot and deserializes it
  when restore. Defaults to true. Needs to be enabled for performing snapshot and restore
  via admin server commands, as there is no snapshot file name to extract the lastProcessedZxid.

  This feature is backward and forward compatible. Here are the different scenarios.

  1. Snapshot triggered by server internally
     * When loading old snapshot with new code, it will throw EOFException when trying to
       read the non-exist lastProcessedZxid value, and the exception will be caught.
       The lastProcessedZxid will be set using the snapshot file name.
     * When loading new snapshot with old code, it will finish successfully after deserializing
       the digest value, the lastProcessedZxid at the end of snapshot file will be ignored.
       The lastProcessedZxid will be set using the snapshot file name.

  2. Sync up between leader and follower: The lastProcessedZxid will not be serialized by
     leader and deserialized by follower in both new and old code. It will be set to the
     lastProcessedZxid sent from leader via QuorumPacket.

  3. Snapshot triggered via admin server APIs: The feature flag need to be enabled for the
     snapshot command to work.

## Cluster Options

The options in this section are designed for use with an ensemble
of servers — that is, when deploying clusters of servers.

* *electionAlg* :
  (No Java system property)
  Election implementation to use. A value of "1" corresponds to the
  non-authenticated UDP-based version of fast leader election, "2"
  corresponds to the authenticated UDP-based version of fast
  leader election, and "3" corresponds to TCP-based version of
  fast leader election. Algorithm 3 was made default in 3.2.0 and
  prior versions (3.0.0 and 3.1.0) were using algorithm 1 and 2 as well.

  <Callout type="info">
    The implementations of leader election 1, and 2 were **deprecated** in
    3.4.0. Since 3.6.0 only FastLeaderElection is available, in case of upgrade
    you have to shut down all of your servers and restart them with
    electionAlg=3 (or by removing the line from the configuration file).
  </Callout>

* *maxTimeToWaitForEpoch* :
  (Java system property: **zookeeper.leader.maxTimeToWaitForEpoch**)
  **New in 3.6.0:**
  The maximum time to wait for epoch from voters when activating
  leader. If leader received a LOOKING notification from one of
  its voters, and it hasn't received epoch packets from majority
  within maxTimeToWaitForEpoch, then it will goto LOOKING and
  elect leader again.
  This can be tuned to reduce the quorum or server unavailable
  time, it can be set to be much smaller than initLimit \\* tickTime.
  In cross datacenter environment, it can be set to something
  like 2s.

* *initLimit* :
  (No Java system property)
  Amount of time, in ticks (see [tickTime](#minimum-configuration)), to allow followers to
  connect and sync to a leader. Increased this value as needed, if
  the amount of data managed by ZooKeeper is large.

* *connectToLearnerMasterLimit* :
  (Java system property: zookeeper.**connectToLearnerMasterLimit**)
  Amount of time, in ticks (see [tickTime](#minimum-configuration)), to allow followers to
  connect to the leader after leader election. Defaults to the value of initLimit.
  Use when initLimit is high so connecting to learner master doesn't result in higher timeout.

* *leaderServes* :
  (Java system property: zookeeper.**leaderServes**)
  Leader accepts client connections. Default value is "yes".
  The leader machine coordinates updates. For higher update
  throughput at the slight expense of read throughput the leader
  can be configured to not accept clients and focus on
  coordination. The default to this option is yes, which means
  that a leader will accept client connections.

  <Callout type="info">
    Turning on leader selection is highly recommended when you have more than
    three ZooKeeper servers in an ensemble.
  </Callout>

* *server.x=\\[hostname]:nnnnn\\[:nnnnn] etc* :
  (No Java system property)
  Servers making up the ZooKeeper ensemble. When the server
  starts up, it determines which server it is by looking for the
  file *myid* in the data directory. That file contains the server number, in ASCII,
  and it should match **x** in **server.x** in the left hand side of this setting.
  The list of servers that make up ZooKeeper servers that is
  used by the clients must match the list of ZooKeeper servers
  that each ZooKeeper server has.
  There are two port numbers **nnnnn**.
  The first followers used to connect to the leader, and the second is for
  leader election. If you want to test multiple servers on a single machine, then
  different ports can be used for each server.

  Since ZooKeeper 3.6.0 it is possible to specify **multiple addresses** for each
  ZooKeeper server (see [ZOOKEEPER-3188](https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188)).
  To enable this feature, you must set the *multiAddress.enabled* configuration property
  to *true*. This helps to increase availability and adds network level
  resiliency to ZooKeeper. When multiple physical network interfaces are used
  for the servers, ZooKeeper is able to bind on all interfaces and runtime switching
  to a working interface in case a network error. The different addresses can be specified
  in the config using a pipe ('|') character. A valid configuration using multiple addresses looks like:

  \`\`\`
  server.1=zoo1-net1:2888:3888|zoo1-net2:2889:3889
  server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889
  server.3=zoo3-net1:2888:3888|zoo3-net2:2889:3889
  \`\`\`

  <Callout type="info">
    By enabling this feature, the Quorum protocol (ZooKeeper Server-Server
    protocol) will change. The users will not notice this and when anyone starts
    a ZooKeeper cluster with the new config, everything will work normally.
    However, it's not possible to enable this feature and specify multiple
    addresses during a rolling upgrade if the old ZooKeeper cluster didn't
    support the *multiAddress* feature (and the new Quorum protocol). In case if
    you need this feature but you also need to perform a rolling upgrade from a
    ZooKeeper cluster older than *3.6.0*, then you first need to do the rolling
    upgrade without enabling the MultiAddress feature and later make a separate
    rolling restart with the new configuration where **multiAddress.enabled** is
    set to **true** and multiple addresses are provided.
  </Callout>

* *syncLimit* :
  (No Java system property)
  Amount of time, in ticks (see [tickTime](#minimum-configuration)), to allow followers to sync
  with ZooKeeper. If followers fall too far behind a leader, they
  will be dropped.

* *group.x=nnnnn\\[:nnnnn]* :
  (No Java system property)
  Enables a hierarchical quorum construction."x" is a group identifier
  and the numbers following the "=" sign correspond to server identifiers.
  The left-hand side of the assignment is a colon-separated list of server
  identifiers. Note that groups must be disjoint and the union of all groups
  must be the ZooKeeper ensemble.
  You will find an example [here](/admin-ops/quorums)

* *weight.x=nnnnn* :
  (No Java system property)
  Used along with "group", it assigns a weight to a server when
  forming quorums. Such a value corresponds to the weight of a server
  when voting. There are a few parts of ZooKeeper that require voting
  such as leader election and the atomic broadcast protocol. By default
  the weight of server is 1. If the configuration defines groups, but not
  weights, then a value of 1 will be assigned to all servers.
  You will find an example [here](/admin-ops/quorums)

* *cnxTimeout* :
  (Java system property: zookeeper.**cnxTimeout**)
  Sets the timeout value for opening connections for leader election notifications.
  Only applicable if you are using electionAlg 3.

  <Callout type="info">
    Default value is 5 seconds.
  </Callout>

* *quorumCnxnTimeoutMs* :
  (Java system property: zookeeper.**quorumCnxnTimeoutMs**)
  Sets the read timeout value for the connections for leader election notifications.
  Only applicable if you are using electionAlg 3.

  <Callout type="info">
    Default value is -1, which will then use the syncLimit \\* tickTime as the
    timeout.
  </Callout>

* *standaloneEnabled* :
  (No Java system property)
  **New in 3.5.0:**
  When set to false, a single server can be started in replicated
  mode, a lone participant can run with observers, and a cluster
  can reconfigure down to one node, and up from one node. The
  default is true for backwards compatibility. It can be set
  using QuorumPeerConfig's setStandaloneEnabled method or by
  adding "standaloneEnabled=false" or "standaloneEnabled=true"
  to a server's config file.

* *reconfigEnabled* :
  (No Java system property)
  **New in 3.5.3:**
  This controls the enabling or disabling of
  [Dynamic Reconfiguration](/admin-ops/dynamic-reconfiguration) feature. When the feature
  is enabled, users can perform reconfigure operations through
  the ZooKeeper client API or through ZooKeeper command line tools
  assuming users are authorized to perform such operations.
  When the feature is disabled, no user, including the super user,
  can perform a reconfiguration. Any attempt to reconfigure will return an error.
  **"reconfigEnabled"** option can be set as
  **"reconfigEnabled=false"** or
  **"reconfigEnabled=true"**
  to a server's config file, or using QuorumPeerConfig's
  setReconfigEnabled method. The default value is false.
  If present, the value should be consistent across every server in
  the entire ensemble. Setting the value as true on some servers and false
  on other servers will cause inconsistent behavior depending on which server
  is elected as leader. If the leader has a setting of
  **"reconfigEnabled=true"**, then the ensemble
  will have reconfig feature enabled. If the leader has a setting of
  **"reconfigEnabled=false"**, then the ensemble
  will have reconfig feature disabled. It is thus recommended having a consistent
  value for **"reconfigEnabled"** across servers
  in the ensemble.

* *4lw\\.commands.whitelist* :
  (Java system property: **zookeeper.4lw\\.commands.whitelist**)
  **New in 3.5.3:**
  A list of comma separated [Four Letter Words](/admin-ops/administrators-guide/commands#the-four-letter-words)
  commands that user wants to use. A valid Four Letter Words
  command must be put in this list else ZooKeeper server will
  not enable the command.
  By default the whitelist only contains "srvr" command
  which zkServer.sh uses. Additionally, if Read Only Mode is enabled by setting
  Java system property **readonlymode.enabled**, then the "isro" command is
  added to the whitelist. The rest of four-letter word commands are disabled
  by default: attempting to use them will gain a response
  ".... is not executed because it is not in the whitelist."
  Here's an example of the configuration that enables stat, ruok, conf, and isro
  command while disabling the rest of Four Letter Words command:

  \`\`\`
  4lw.commands.whitelist=stat, ruok, conf, isro
  \`\`\`

  If you really need enable all four-letter word commands by default, you can use
  the asterisk option so you don't have to include every command one by one in the list.
  As an example, this will enable all four-letter word commands:

  \`\`\`
  4lw.commands.whitelist=*
  \`\`\`

* *tcpKeepAlive* :
  (Java system property: **zookeeper.tcpKeepAlive**)
  **New in 3.5.4:**
  Setting this to true sets the TCP keepAlive flag on the
  sockets used by quorum members to perform elections.
  This will allow for connections between quorum members to
  remain up when there is network infrastructure that may
  otherwise break them. Some NATs and firewalls may terminate
  or lose state for long-running or idle connections.
  Enabling this option relies on OS level settings to work
  properly, check your operating system's options regarding TCP
  keepalive for more information. Defaults to
  **false**.

* *clientTcpKeepAlive* :
  (Java system property: **zookeeper.clientTcpKeepAlive**)
  **New in 3.6.1:**
  Setting this to true sets the TCP keepAlive flag on the
  client sockets. Some broken network infrastructure may lose
  the FIN packet that is sent from closing client. These never
  closed client sockets cause OS resource leak. Enabling this
  option terminates these zombie sockets by idle check.
  Enabling this option relies on OS level settings to work
  properly, check your operating system's options regarding TCP
  keepalive for more information. Defaults to **false**. Please
  note the distinction between it and **tcpKeepAlive**. It is
  applied for the client sockets while **tcpKeepAlive** is for
  the sockets used by quorum members. Currently this option is
  only available when default \`NIOServerCnxnFactory\` is used.

* *electionPortBindRetry* :
  (Java system property only: **zookeeper.electionPortBindRetry**)
  Property set max retry count when Zookeeper server fails to bind
  leader election port. Such errors can be temporary and recoverable,
  such as DNS issue described in [ZOOKEEPER-3320](https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3320),
  or non-retryable, such as port already in use.
  In case of transient errors, this property can improve availability
  of Zookeeper server and help it to self recover.
  Default value 3. In container environment, especially in Kubernetes,
  this value should be increased or set to 0(infinite retry) to overcome issues
  related to DNS name resolving.

* *observer.reconnectDelayMs* :
  (Java system property: **zookeeper.observer.reconnectDelayMs**)
  When observer loses its connection with the leader, it waits for the
  specified value before trying to reconnect with the leader so that
  the entire observer fleet won't try to run leader election and reconnect
  to the leader at once.
  Defaults to 0 ms.

* *observer.election.DelayMs* :
  (Java system property: **zookeeper.observer.election.DelayMs**)
  Delay the observer's participation in a leader election upon disconnect
  so as to prevent unexpected additional load on the voting peers during
  the process. Defaults to 200 ms.

* *localSessionsEnabled* and *localSessionsUpgradingEnabled* :
  **New in 3.5:**
  Optional value is true or false. Their default values are false.
  Turning on the local session feature by setting *localSessionsEnabled=true*. Turning on
  *localSessionsUpgradingEnabled* can upgrade a local session to a global session automatically as required (e.g. creating ephemeral nodes),
  which only matters when *localSessionsEnabled* is enabled.

## Encryption, Authentication, Authorization Options

The options in this section allow control over
encryption/authentication/authorization performed by the service.

Beside this page, you can also find useful information about client side configuration in the
[Programmers Guide](/developer/programmers-guide/bindings#client-configuration-parameters).
The ZooKeeper Wiki also has useful pages about [ZooKeeper SSL support](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide),
and [SASL authentication for ZooKeeper](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL).

* *DigestAuthenticationProvider.enabled* :
  (Java system property: **zookeeper.DigestAuthenticationProvider.enabled**)
  **New in 3.7:**
  Determines whether the \`digest\` authentication provider is
  enabled. The default value is **true** for backwards
  compatibility, but it may be a good idea to disable this provider
  if not used, as it can result in misleading entries appearing in
  audit logs
  (see [ZOOKEEPER-3979](https://issues.apache.org/jira/browse/ZOOKEEPER-3979))

* *DigestAuthenticationProvider.superDigest* :
  (Java system property: **zookeeper.DigestAuthenticationProvider.superDigest**)
  By default this feature is **disabled**
  **New in 3.2:**
  Enables a ZooKeeper ensemble administrator to access the
  znode hierarchy as a "super" user. In particular no ACL
  checking occurs for a user authenticated as
  super.
  org.apache.zookeeper.server.auth.DigestAuthenticationProvider
  can be used to generate the superDigest, call it with
  one parameter of \`"super:<password>"\`. Provide the
  generated \`"super:<data>"\` as the system property value
  when starting each server of the ensemble.
  When authenticating to a ZooKeeper server (from a
  ZooKeeper client) pass a scheme of \`"digest"\` and authdata
  of \`"super:<password>"\`. Note that digest auth passes
  the authdata in plaintext to the server, it would be
  prudent to use this authentication method only on
  localhost (not over the network) or over an encrypted
  connection.

* *DigestAuthenticationProvider.digestAlg* :
  (Java system property: **zookeeper.DigestAuthenticationProvider.digestAlg**)
  **New in 3.7.0:**
  Set ACL digest algorithm. The default value is: \`SHA1\` which will be deprecated in the future for security issues.

  Set this property the same value in all the servers.

  * How to support other more algorithms?
    * Modify the \`java.security\` configuration file under \`$JAVA_HOME/jre/lib/security/java.security\` by specifying
      \`security.provider.<n>=<provider class name>\`.

      For example:

      \`\`\`
      set zookeeper.DigestAuthenticationProvider.digestAlg=RipeMD160
      security.provider.3=org.bouncycastle.jce.provider.BouncyCastleProvider
      \`\`\`

    * Copy the jar file to \`$JAVA_HOME/jre/lib/ext/\`.

      For example:

      \`\`\`
      copy bcprov-jdk18on-1.60.jar to $JAVA_HOME/jre/lib/ext/
      \`\`\`

  * How to migrate from one digest algorithm to another?
    * Regenerate \`superDigest\` when migrating to new algorithm.
    * Run \`SetAcl\` for a znode which already had a digest auth of old algorithm.

* *IPAuthenticationProvider.usexforwardedfor* :
  (Java system property: **zookeeper.IPAuthenticationProvider.usexforwardedfor**)
  **New in 3.9.3:**
  IPAuthenticationProvider uses the client IP address to authenticate the user. By
  default it reads the **Host** HTTP header to detect client IP address. In some
  proxy configurations the proxy server adds the **X-Forwarded-For** header to
  the request in order to provide the IP address of the original client request.
  By enabling **usexforwardedfor** ZooKeeper setting, **X-Forwarded-For** will be preferred
  over the standard **Host** header.
  Default value is **false**.

* *X509AuthenticationProvider.superUser* :
  (Java system property: **zookeeper.X509AuthenticationProvider.superUser**)
  The SSL-backed way to enable a ZooKeeper ensemble
  administrator to access the znode hierarchy as a "super" user.
  When this parameter is set to an X500 principal name, only an
  authenticated client with that principal will be able to bypass
  ACL checking and have full privileges to all znodes.

* *zookeeper.superUser* :
  (Java system property: **zookeeper.superUser**)
  Similar to **zookeeper.X509AuthenticationProvider.superUser**
  but is generic for SASL based logins. It stores the name of
  a user that can access the znode hierarchy as a "super" user.
  You can specify multiple SASL super users using the
  **zookeeper.superUser.\\[suffix]** notation, e.g.:
  \`zookeeper.superUser.1=...\`.

* *ssl.authProvider* :
  (Java system property: **zookeeper.ssl.authProvider**)
  Specifies a subclass of **org.apache.zookeeper.auth.X509AuthenticationProvider**
  to use for secure client authentication. This is useful in
  certificate key infrastructures that do not use JKS. It may be
  necessary to extend **javax.net.ssl.X509KeyManager** and **javax.net.ssl.X509TrustManager**
  to get the desired behavior from the SSL stack. To configure the
  ZooKeeper server to use the custom provider for authentication,
  choose a scheme name for the custom AuthenticationProvider and
  set the property **zookeeper.authProvider.\\[scheme]** to the fully-qualified class name of the custom
  implementation. This will load the provider into the ProviderRegistry.
  Then set this property **zookeeper.ssl.authProvider=\\[scheme]** and that provider
  will be used for secure authentication.

* *zookeeper.ensembleAuthName* :
  (Java system property only: **zookeeper.ensembleAuthName**)
  **New in 3.6.0:**
  Specify a list of comma-separated valid names/aliases of an ensemble. A client
  can provide the ensemble name it intends to connect as the credential for scheme "ensemble". The EnsembleAuthenticationProvider will check the credential against
  the list of names/aliases of the ensemble that receives the connection request.
  If the credential is not in the list, the connection request will be refused.
  This prevents a client accidentally connecting to a wrong ensemble.

* *sessionRequireClientSASLAuth* :
  (Java system property: **zookeeper.sessionRequireClientSASLAuth**)
  **New in 3.6.0:**
  When set to **true**, ZooKeeper server will only accept connections and requests from clients
  that have authenticated with server via SASL. Clients that are not configured with SASL
  authentication, or configured with SASL but failed authentication (i.e. with invalid credential)
  will not be able to establish a session with server. A typed error code (-124) will be delivered
  in such case, both Java and C client will close the session with server thereafter,
  without further attempts on retrying to reconnect.

This configuration is shorthand for **enforce.auth.enabled=true** and **enforce.auth.scheme=sasl**

By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting **sessionRequireClientSASLAuth** to **true**.

This feature overrules the <emphasis role="bold">zookeeper.allowSaslFailedClients</emphasis> option, so even if server is
configured to allow clients that fail SASL authentication to login, client will not be able to
establish a session with server if this feature is enabled.

* *enforce.auth.enabled* :
  (Java system property : **zookeeper.enforce.auth.enabled**)
  **New in 3.7.0:**
  When set to **true**, ZooKeeper server will only accept connections and requests from clients
  that have authenticated with server via configured auth scheme. Authentication schemes
  can be configured using property enforce.auth.schemes. Clients that are not
  configured with the any of the auth scheme configured at server or configured but failed authentication (i.e. with invalid credential)
  will not be able to establish a session with server. A typed error code (-124) will be delivered
  in such case, both Java and C client will close the session with server thereafter,
  without further attempts on retrying to reconnect.

By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting **enforce.auth.enabled** to **true**.

When **enforce.auth.enabled=true** and **enforce.auth.schemes=sasl** then

<emphasis role="bold">zookeeper.allowSaslFailedClients</emphasis> configuration
is overruled. So even if server is configured to allow clients that fail SASL
authentication to login, client will not be able to establish a session with
server if this feature is enabled with sasl as authentication scheme.

* *enforce.auth.schemes* :
  (Java system property : **zookeeper.enforce.auth.schemes**)
  **New in 3.7.0:**
  Comma separated list of authentication schemes. Clients must be authenticated with at least one
  authentication scheme before doing any zookeeper operations.
  This property is used only when **enforce.auth.enabled** is to **true**.

* *sslQuorum* :
  (Java system property: **zookeeper.sslQuorum**)
  **New in 3.5.5:**
  Enables encrypted quorum communication. Default is \`false\`. When enabling this feature, please also consider enabling *leader.closeSocketAsync*
  and *learner.closeSocketAsync* to avoid issues associated with the potentially long socket closing time when shutting down an SSL connection.

* *ssl.keyStore.location and ssl.keyStore.password* and *ssl.quorum.keyStore.location* and *ssl.quorum.keyStore.password* :
  (Java system properties: **zookeeper.ssl.keyStore.location** and **zookeeper.ssl.keyStore.password** and **zookeeper.ssl.quorum.keyStore.location** and **zookeeper.ssl.quorum.keyStore.password**)
  **New in 3.5.5:**
  Specifies the file path to a Java keystore containing the local
  credentials to be used for client and quorum TLS connections, and the
  password to unlock the file.

* *ssl.keyStore.passwordPath* and *ssl.quorum.keyStore.passwordPath* :
  (Java system properties: **zookeeper.ssl.keyStore.passwordPath** and **zookeeper.ssl.quorum.keyStore.passwordPath**)
  **New in 3.8.0:**
  Specifies the file path that contains the keystore password. Reading the password from a file takes precedence over
  the explicit password property.

* *ssl.keyStore.type* and *ssl.quorum.keyStore.type* :
  (Java system properties: **zookeeper.ssl.keyStore.type** and **zookeeper.ssl.quorum.keyStore.type**)
  **New in 3.5.5:**
  Specifies the file format of client and quorum keystores. Values: JKS, PEM, PKCS12 or null (detect by filename).
  Default: null.
  **New in 3.5.10, 3.6.3, 3.7.0:**
  The format BCFKS was added.

* *ssl.trustStore.location* and *ssl.trustStore.password* and *ssl.quorum.trustStore.location* and *ssl.quorum.trustStore.password* :
  (Java system properties: **zookeeper.ssl.trustStore.location** and **zookeeper.ssl.trustStore.password** and **zookeeper.ssl.quorum.trustStore.location** and **zookeeper.ssl.quorum.trustStore.password**)
  **New in 3.5.5:**
  Specifies the file path to a Java truststore containing the remote
  credentials to be used for client and quorum TLS connections, and the
  password to unlock the file.

* *ssl.trustStore.passwordPath* and *ssl.quorum.trustStore.passwordPath* :
  (Java system properties: **zookeeper.ssl.trustStore.passwordPath** and **zookeeper.ssl.quorum.trustStore.passwordPath**)
  **New in 3.8.0:**
  Specifies the file path that contains the truststore password. Reading the password from a file takes precedence over
  the explicit password property.

* *ssl.trustStore.type* and *ssl.quorum.trustStore.type* :
  (Java system properties: **zookeeper.ssl.trustStore.type** and **zookeeper.ssl.quorum.trustStore.type**)
  **New in 3.5.5:**
  Specifies the file format of client and quorum trustStores. Values: JKS, PEM, PKCS12 or null (detect by filename).
  Default: null.
  **New in 3.5.10, 3.6.3, 3.7.0:**
  The format BCFKS was added.

* *ssl.protocol* and *ssl.quorum.protocol* :
  (Java system properties: **zookeeper.ssl.protocol** and **zookeeper.ssl.quorum.protocol**)
  **New in 3.5.5:**
  Specifies to protocol to be used in client and quorum TLS negotiation.
  Default: TLSv1.3 or TLSv1.2 depending on Java runtime version being used.

* *ssl.enabledProtocols* and *ssl.quorum.enabledProtocols* :
  (Java system properties: **zookeeper.ssl.enabledProtocols** and **zookeeper.ssl.quorum.enabledProtocols**)
  **New in 3.5.5:**
  Specifies the enabled protocols in client and quorum TLS negotiation.
  Default: TLSv1.3, TLSv1.2 if value of \`protocol\` property is TLSv1.3. TLSv1.2 if \`protocol\` is TLSv1.2.

* *ssl.ciphersuites* and *ssl.quorum.ciphersuites* :
  (Java system properties: **zookeeper.ssl.ciphersuites** and **zookeeper.ssl.quorum.ciphersuites**)
  **New in 3.5.5:**
  Specifies the enabled cipher suites to be used in client and quorum TLS negotiation.
  Default: JDK defaults since 3.10.0, and hard coded cipher suites for 3.9 and earlier versions. See [TLS Cipher Suites](#tls-cipher-suites).

* *ssl.context.supplier.class* and *ssl.quorum.context.supplier.class* :
  (Java system properties: **zookeeper.ssl.context.supplier.class** and **zookeeper.ssl.quorum.context.supplier.class**)
  **New in 3.5.5:**
  Specifies the class to be used for creating SSL context in client and quorum SSL communication.
  This allows you to use custom SSL context and implement the following scenarios: 1. Use hardware keystore, loaded in using PKCS11 or something similar. 2. You don't have access to the software keystore, but can retrieve an already-constructed SSLContext from their container.
  Default: null

* *ssl.hostnameVerification* and *ssl.quorum.hostnameVerification* :
  (Java system properties: **zookeeper.ssl.hostnameVerification** and **zookeeper.ssl.quorum.hostnameVerification**)
  **New in 3.5.5:**
  Specifies whether the hostname verification is enabled in client and quorum TLS negotiation process.
  Disabling it only recommended for testing purposes.
  Default: true

* *ssl.clientHostnameVerification* and *ssl.quorum.clientHostnameVerification* :
  (Java system properties: **zookeeper.ssl.clientHostnameVerification** and **zookeeper.ssl.quorum.clientHostnameVerification**)
  **New in 3.9.4:**
  Specifies whether the client's hostname verification is enabled in client and quorum TLS negotiation process.
  This option requires the corresponding *hostnameVerification* option to be \`true\`, or it will be ignored.
  Default: true for quorum, false for clients

* *ssl.allowReverseDnsLookup* and *ssl.quorum.allowReverseDnsLookup* :
  (Java system properties: **zookeeper.ssl.allowReverseDnsLookup** and **zookeeper.ssl.quorum.allowReverseDnsLookup**)
  **New in 3.9.5:**
  Allow reverse DNS lookup in both server- and client hostname verifications if the hostname verification is enabled in
  \`ZKTrustManager\`. Supported in both quorum and client TLS protocols. Not supported in FIPS mode. Reverse DNS lookups are
  expensive and unnecessary in most cases. Make sure that certificates are created with all required Subject Alternative
  Names (SAN) for successful identity verification. It's recommended to add SAN:IP entries for identity verification
  of client certificates.
  Default: false

* *ssl.crl* and *ssl.quorum.crl* :
  (Java system properties: **zookeeper.ssl.crl** and **zookeeper.ssl.quorum.crl**)
  **New in 3.5.5:**
  Specifies whether Certificate Revocation List is enabled in client and quorum TLS protocols.
  Default: jvm property "com.sun.net.ssl.checkRevocation" since 3.10.0, false otherwise

* *ssl.ocsp* and *ssl.quorum.ocsp* :
  (Java system properties: **zookeeper.ssl.ocsp** and **zookeeper.ssl.quorum.ocsp**)
  **New in 3.5.5:**
  Specifies whether Online Certificate Status Protocol is enabled in client and quorum TLS protocols.
  **Changed in 3.10.0:**
  Before 3.10.0, *ssl.ocsp* and *ssl.quorum.ocsp* implies *ssl.crl* and *ssl.quorum.crl* correspondingly.
  After 3.10.0, one has to setup both *ssl.crl* and *ssl.ocsp* (or *ssl.quorum.crl* and *ssl.quorum.ocsp*)
  to enable OCSP. This is consistent with jdk's method of [Setting up a Java Client to use Client-Driven OCSP](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/ocsp.html#setting-up-a-java-client-to-use-client-driven-ocsp).
  Default: jvm security property "ocsp.enable" since 3.10.0, false otherwise

* *ssl.clientAuth* and *ssl.quorum.clientAuth* :
  (Java system properties: **zookeeper.ssl.clientAuth** and **zookeeper.ssl.quorum.clientAuth**)
  **Added in 3.5.5, but broken until 3.5.7:**
  Specifies options to authenticate ssl connections from clients. Valid values are

  * "none": server will not request client authentication
  * "want": server will "request" client authentication
  * "need": server will "require" client authentication

  Default: "need"

* *ssl.handshakeDetectionTimeoutMillis* and *ssl.quorum.handshakeDetectionTimeoutMillis* :
  (Java system properties: **zookeeper.ssl.handshakeDetectionTimeoutMillis** and **zookeeper.ssl.quorum.handshakeDetectionTimeoutMillis**)
  **New in 3.5.5:**
  TBD

* *ssl.sslProvider* :
  (Java system property: **zookeeper.ssl.sslProvider**)
  **New in 3.9.0:**
  Allows to select SSL provider in the client-server communication when TLS is enabled. Netty-tcnative native library
  has been added to ZooKeeper in version 3.9.0 which allows us to use native SSL libraries like OpenSSL on supported
  platforms. See the available options in Netty-tcnative documentation. Default value is "JDK".

* *sslQuorumReloadCertFiles* :
  (No Java system property)
  **New in 3.5.5, 3.6.0:**
  Allows Quorum SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false

* *client.certReload* :
  (Java system property: **zookeeper.client.certReload**)
  **New in 3.7.2, 3.8.1, 3.9.0:**
  Allows client SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false

* *client.portUnification*:
  (Java system property: **zookeeper.client.portUnification**)
  Specifies that the client port should accept SSL connections
  (using the same configuration as the secure client port).
  Default: false

* *authProvider*:
  (Java system property: **zookeeper.authProvider**)
  You can specify multiple authentication provider classes for ZooKeeper.
  Usually you use this parameter to specify the SASL authentication provider
  like: \`authProvider.1=org.apache.zookeeper.server.auth.SASLAuthenticationProvider\`

* *kerberos.removeHostFromPrincipal*
  (Java system property: **zookeeper.kerberos.removeHostFromPrincipal**)
  You can instruct ZooKeeper to remove the host from the client principal name during authentication.
  (e.g. the zk/myhost\\@EXAMPLE.COM client principal will be authenticated in ZooKeeper as [zk@EXAMPLE.COM](mailto:zk@EXAMPLE.COM))
  Default: false

* *kerberos.removeRealmFromPrincipal*
  (Java system property: **zookeeper.kerberos.removeRealmFromPrincipal**)
  You can instruct ZooKeeper to remove the realm from the client principal name during authentication.
  (e.g. the zk/myhost\\@EXAMPLE.COM client principal will be authenticated in ZooKeeper as zk/myhost)
  Default: false

* *kerberos.canonicalizeHostNames*
  (Java system property: **zookeeper.kerberos.canonicalizeHostNames**)
  **New in 3.7.0:**
  Instructs ZooKeeper to canonicalize server host names extracted from *server.x* lines.
  This allows using e.g. \`CNAME\` records to reference servers in configuration files, while still enabling SASL Kerberos authentication between quorum members.
  It is essentially the quorum equivalent of the *zookeeper.sasl.client.canonicalize.hostname* property for clients.
  The default value is **false** for backwards compatibility.

* *multiAddress.enabled* :
  (Java system property: **zookeeper.multiAddress.enabled**)
  **New in 3.6.0:**
  Since ZooKeeper 3.6.0 you can also [specify multiple addresses](#cluster-options)
  for each ZooKeeper server instance (this can increase availability when multiple physical
  network interfaces can be used parallel in the cluster). Setting this parameter to
  **true** will enable this feature. Please note, that you can not enable this feature
  during a rolling upgrade if the version of the old ZooKeeper cluster is prior to 3.6.0.
  The default value is **false**.

* *multiAddress.reachabilityCheckTimeoutMs* :
  (Java system property: **zookeeper.multiAddress.reachabilityCheckTimeoutMs**)
  **New in 3.6.0:**
  Since ZooKeeper 3.6.0 you can also [specify multiple addresses](#cluster-options)
  for each ZooKeeper server instance (this can increase availability when multiple physical
  network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
  or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
  the reachable addresses. This happens only if you provide multiple addresses in the configuration.
  In this property you can set the timeout in milliseconds for the reachability check. The check happens
  in parallel for the different addresses, so the timeout you set here is the maximum time will be taken
  by checking the reachability of all addresses.
  The default value is **1000**.

  This parameter has no effect, unless you enable the MultiAddress feature by setting *multiAddress.enabled=true*.

* *fips-mode* :
  (Java system property: **zookeeper.fips-mode**)
  **New in 3.8.2:**
  Enable FIPS compatibility mode in ZooKeeper. If enabled, the following things will be changed in order to comply
  with FIPS requirements:

  * Custom trust manager (\`ZKTrustManager\`) that is used for hostname verification will be disabled. As a consequence,
    hostname verification is not available in the Quorum protocol, but still can be set in client-server communication.
  * DIGEST-MD5 Sasl auth mechanism will be disabled in Quorum and ZooKeeper Sasl clients. Only GSSAPI (Kerberos)
    can be used.

  Default: **true** (3.9.0+), **false** (3.8.x)

## TLS Cipher Suites

From 3.5.5 to 3.9 a hard coded default cipher list was used, with the ordering
dependent on whether it is run Java 8 or a later version.

The list on Java 8 includes TLSv1.2 CBC, GCM and TLSv1.3 ciphers in ordering: *TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_CBC\\_SHA256, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_CBC\\_SHA256, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_CBC\\_SHA, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_CBC\\_SHA, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_CBC\\_SHA384, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_CBC\\_SHA384, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_CBC\\_SHA, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_CBC\\_SHA, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_GCM\\_SHA256, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_GCM\\_SHA256, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_GCM\\_SHA384, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_GCM\\_SHA384, TLS\\_AES\\_256\\_GCM\\_SHA384,TLS\\_AES\\_128\\_GCM\\_SHA256, TLS\\_CHACHA20\\_POLY1305\\_SHA256*

The list on Java 9+ includes TLSv1.2 GCM, CBC and TLSv1.3 ciphers in ordering: *TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_GCM\\_SHA256, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_GCM\\_SHA256, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_GCM\\_SHA384, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_GCM\\_SHA384, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_CBC\\_SHA256, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_CBC\\_SHA256, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_128\\_CBC\\_SHA, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_128\\_CBC\\_SHA, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_CBC\\_SHA384, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_CBC\\_SHA384, TLS\\_ECDHE\\_ECDSA\\_WITH\\_AES\\_256\\_CBC\\_SHA, TLS\\_ECDHE\\_RSA\\_WITH\\_AES\\_256\\_CBC\\_SHA, TLS\\_AES\\_256\\_GCM\\_SHA384,TLS\\_AES\\_128\\_GCM\\_SHA256, TLS\\_CHACHA20\\_POLY1305\\_SHA256*

Since 3.10 there is no hardcoded list, and the JDK defaults are used.

## Experimental Options/Features

New features that are currently considered experimental.

* *Read Only Mode Server* :
  (Java system property: **readonlymode.enabled**)
  **New in 3.4.0:**
  Setting this value to true enables Read Only Mode server
  support (disabled by default).
  *localSessionsEnabled* has to be activated to serve clients.
  A downgrade of an existing connections is currently not supported.
  ROM allows clients sessions which requested ROM support to connect to the
  server even when the server might be partitioned from
  the quorum. In this mode ROM clients can still read
  values from the ZK service, but will be unable to write
  values and see changes from other clients. See
  ZOOKEEPER-784 for more details.

* *zookeeper.follower.skipLearnerRequestToNextProcessor* :
  (Java system property: **zookeeper.follower.skipLearnerRequestToNextProcessor**)
  When our cluster has observers which are connected with ObserverMaster, then turning on this flag might help
  you reduce some memory pressure on the Observer Master. If your cluster doesn't have any observers or
  they are not connected with ObserverMaster or your Observer's don't make much writes, then using this flag
  won't help you.
  Currently the change here is guarded behind the flag to help us get more confidence around the memory gains.
  In Long run, we might want to remove this flag and set its behavior as the default codepath.

## Unsafe Options

The following options can be useful, but be careful when you use
them. The risk of each is explained along with the explanation of what
the variable does.

* *forceSync* :
  (Java system property: **zookeeper.forceSync**)
  Requires updates to be synced to media of the transaction
  log before finishing processing the update. If this option is
  set to no, ZooKeeper will not require updates to be synced to
  the media.

* *jute.maxbuffer* :
  (Java system property:**jute.maxbuffer**).
  * This option can only be set as a Java system property.
    There is no zookeeper prefix on it. It specifies the maximum
    size of the data that can be stored in a znode. The unit is: byte. The default is
    0xfffff(1048575) bytes, or just under 1M.
  * If this option is changed, the system property must be set on all servers and clients otherwise
    problems will arise.
  * When *jute.maxbuffer* in the client side is greater than the server side, the client wants to write the data
    exceeds *jute.maxbuffer* in the server side, the server side will get **java.io.IOException: Len error**
  * When *jute.maxbuffer* in the client side is less than the server side, the client wants to read the data
    exceeds *jute.maxbuffer* in the client side, the client side will get **java.io.IOException: Unreasonable length**
    or **Packet len is out of range!**
  * This is really a sanity check. ZooKeeper is designed to store data on the order of kilobytes in size.
    In the production environment, increasing this property to exceed the default value is not recommended for the following reasons:
  * Large size znodes cause unwarranted latency spikes, worsen the throughput
  * Large size znodes make the synchronization time between leader and followers unpredictable and non-convergent(sometimes timeout), cause the quorum unstable

* *jute.maxbuffer.extrasize*:
  (Java system property: **zookeeper.jute.maxbuffer.extrasize**)
  **New in 3.5.7:**
  While processing client requests ZooKeeper server adds some additional information into
  the requests before persisting it as a transaction. Earlier this additional information size
  was fixed to 1024 bytes. For many scenarios, specially scenarios where jute.maxbuffer value
  is more than 1 MB and request type is multi, this fixed size was insufficient.
  To handle all the scenarios additional information size is increased from 1024 byte
  to same as jute.maxbuffer size and also it is made configurable through jute.maxbuffer.extrasize.
  Generally this property is not required to be configured as default value is the most optimal value.

* *skipACL* :
  (Java system property: **zookeeper.skipACL**)
  Skips ACL checks. This results in a boost in throughput,
  but opens up full access to the data tree to everyone.

* *quorumListenOnAllIPs* :
  When set to true the ZooKeeper server will listen
  for connections from its peers on all available IP addresses,
  and not only the address configured in the server list of the
  configuration file. It affects the connections handling the
  ZAB protocol and the Fast Leader Election protocol. Default
  value is **false**.

* *multiAddress.reachabilityCheckEnabled* :
  (Java system property: **zookeeper.multiAddress.reachabilityCheckEnabled**)
  **New in 3.6.0:**
  Since ZooKeeper 3.6.0 you can also [specify multiple addresses](#cluster-options)
  for each ZooKeeper server instance (this can increase availability when multiple physical
  network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
  or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
  the reachable addresses. This happens only if you provide multiple addresses in the configuration.
  The reachable check can fail if you hit some ICMP rate-limitation, (e.g. on macOS) when you try to
  start a large (e.g. 11+) ensemble members cluster on a single machine for testing.

  Default value is **true**. By setting this parameter to 'false' you can disable the reachability checks.
  Please note, disabling the reachability check will cause the cluster not to be able to reconfigure
  itself properly during network problems, so the disabling is advised only during testing.

  This parameter has no effect, unless you enable the MultiAddress feature by setting *multiAddress.enabled=true*.

## Disabling data directory autocreation

**New in 3.5:** The default
behavior of a ZooKeeper server is to automatically create the
data directory (specified in the configuration file) when
started if that directory does not already exist. This can be
inconvenient and even dangerous in some cases. Take the case
where a configuration change is made to a running server,
wherein the **dataDir** parameter
is accidentally changed. When the ZooKeeper server is
restarted it will create this non-existent directory and begin
serving - with an empty znode namespace. This scenario can
result in an effective "split brain" situation (i.e. data in
both the new invalid directory and the original valid data
store). As such is would be good to have an option to turn off
this autocreate behavior. In general for production
environments this should be done, unfortunately however the
default legacy behavior cannot be changed at this point and
therefore this must be done on a case by case basis. This is
left to users and to packagers of ZooKeeper distributions.

When running **zkServer.sh** autocreate can be disabled
by setting the environment variable **ZOO\\_DATADIR\\_AUTOCREATE\\_DISABLE** to 1.
When running ZooKeeper servers directly from class files this
can be accomplished by setting **zookeeper.datadir.autocreate=false** on
the java command line, i.e. **-Dzookeeper.datadir.autocreate=false**

When this feature is disabled, and the ZooKeeper server
determines that the required directories do not exist it will
generate an error and refuse to start.

A new script **zkServer-initialize.sh** is provided to
support this new feature. If autocreate is disabled it is
necessary for the user to first install ZooKeeper, then create
the data directory (and potentially txnlog directory), and
then start the server. Otherwise as mentioned in the previous
paragraph the server will not start. Running **zkServer-initialize.sh** will create the
required directories, and optionally set up the myid file
(optional command line parameter). This script can be used
even if the autocreate feature itself is not used, and will
likely be of use to users as this (setup, including creation
of the myid file) has been an issue for users in the past.
Note that this script ensures the data directories exist only,
it does not create a config file, but rather requires a config
file to be available in order to execute.

## Enabling db existence validation

**New in 3.6.0:** The default
behavior of a ZooKeeper server on startup when no data tree
is found is to set zxid to zero and join the quorum as a
voting member. This can be dangerous if some event (e.g. a
rogue 'rm -rf') has removed the data directory while the
server was down since this server may help elect a leader
that is missing transactions. Enabling db existence validation
will change the behavior on startup when no data tree is
found: the server joins the ensemble as a non-voting participant
until it is able to sync with the leader and acquire an up-to-date
version of the ensemble data. To indicate an empty data tree is
expected (ensemble creation), the user should place a file
'initialize' in the same directory as 'myid'. This file will
be detected and deleted by the server on startup.

Initialization validation can be enabled when running
ZooKeeper servers directly from class files by setting
**zookeeper.db.autocreate=false**
on the java command line, i.e.
**-Dzookeeper.db.autocreate=false**.
Running **zkServer-initialize.sh**
will create the required initialization file.

## Performance Tuning Options

**New in 3.5.0:** Several subsystems have been reworked
to improve read throughput. This includes multi-threading of the NIO communication subsystem and
request processing pipeline (Commit Processor). NIO is the default client/server communication
subsystem. Its threading model comprises 1 acceptor thread, 1-N selector threads and 0-M
socket I/O worker threads. In the request processing pipeline the system can be configured
to process multiple read request at once while maintaining the same consistency guarantee
(same-session read-after-write). The Commit Processor threading model comprises 1 main
thread and 0-N worker threads.

The default values are aimed at maximizing read throughput on a dedicated ZooKeeper machine.
Both subsystems need to have sufficient amount of threads to achieve peak read throughput.

* *zookeeper.nio.numSelectorThreads* :
  (Java system property only: **zookeeper.nio.numSelectorThreads**)
  **New in 3.5.0:**
  Number of NIO selector threads. At least 1 selector thread required.
  It is recommended to use more than one selector for large numbers
  of client connections. The default value is sqrt( number of cpu cores / 2 ).

* *zookeeper.nio.numWorkerThreads* :
  (Java system property only: **zookeeper.nio.numWorkerThreads**)
  **New in 3.5.0:**
  Number of NIO worker threads. If configured with 0 worker threads, the selector threads
  do the socket I/O directly. The default value is 2 times the number of cpu cores.

* *zookeeper.commitProcessor.numWorkerThreads* :
  (Java system property only: **zookeeper.commitProcessor.numWorkerThreads**)
  **New in 3.5.0:**
  Number of Commit Processor worker threads. If configured with 0 worker threads, the main thread
  will process the request directly. The default value is the number of cpu cores.

* *zookeeper.commitProcessor.maxReadBatchSize* :
  (Java system property only: **zookeeper.commitProcessor.maxReadBatchSize**)
  Max number of reads to process from queuedRequests before switching to processing commits.
  If the value \\< 0 (default), we switch whenever we have a local write, and pending commits.
  A high read batch size will delay commit processing, causing stale data to be served.
  If reads are known to arrive in fixed size batches then matching that batch size with
  the value of this property can smooth queue performance. Since reads are handled in parallel,
  one recommendation is to set this property to match *zookeeper.commitProcessor.numWorkerThread*
  (default is the number of cpu cores) or lower.

* *zookeeper.commitProcessor.maxCommitBatchSize* :
  (Java system property only: **zookeeper.commitProcessor.maxCommitBatchSize**)
  Max number of commits to process before processing reads. We will try to process as many
  remote/local commits as we can till we reach this count. A high commit batch size will delay
  reads while processing more commits. A low commit batch size will favor reads.
  It is recommended to only set this property when an ensemble is serving a workload with a high
  commit rate. If writes are known to arrive in a set number of batches then matching that
  batch size with the value of this property can smooth queue performance. A generic
  approach would be to set this value to equal the ensemble size so that with the processing
  of each batch the current server will probabilistically handle a write related to one of
  its direct clients.
  Default is "1". Negative and zero values are not supported.

* *znode.container.checkIntervalMs* :
  (Java system property only)
  **New in 3.6.0:** The
  time interval in milliseconds for each check of candidate container
  and ttl nodes. Default is "60000".

* *znode.container.maxPerMinute* :
  (Java system property only)
  **New in 3.6.0:** The
  maximum number of container and ttl nodes that can be deleted per
  minute. This prevents herding during container deletion.
  Default is "10000".

* *znode.container.maxNeverUsedIntervalMs* :
  (Java system property only)
  **New in 3.6.0:** The
  maximum interval in milliseconds that a container that has never had
  any children is retained. Should be long enough for your client to
  create the container, do any needed work and then create children.
  Default is "300000"(a.k.a. 5 minutes) since 3.10.0, for earlier versions,
  it is "0" which is used to indicate that containers that have never had
  any children are never deleted.

## Debug Observability Configurations

**New in 3.6.0:** The following options are introduced to make zookeeper easier to debug.

* *zookeeper.messageTracker.BufferSize* :
  (Java system property only)
  Controls the maximum number of messages stored in **MessageTracker**. Value should be positive
  integers. The default value is 10. **MessageTracker** is introduced in **3.6.0** to record the
  last set of messages between a server (follower or observer) and a leader, when a server
  disconnects with leader. These set of messages will then be dumped to zookeeper's log file,
  and will help reconstruct the state of the servers at the time of the disconnection and
  will be useful for debugging purpose.

* *zookeeper.messageTracker.Enabled* :
  (Java system property only)
  When set to "true", will enable **MessageTracker** to track and record messages. Default value
  is "false".

## AdminServer configuration

**New in 3.10.0:** The [AdminServer](#adminserver-configuration) will use the following existing properties:

* *ssl.quorum.ciphersuites* :
  (Java system property: **zookeeper.ssl.quorum.ciphersuites**)
  The enabled cipher suites to be used in TLS negotiation for AdminServer.
  Default: Jetty default.
* *ssl.quorum.enabledProtocols* :
  (Java system property: **zookeeper.ssl.quorum.enabledProtocols**)
  The enabled protocols to be used in TLS negotiation for AdminServer.
  Default: Jetty default.

**New in 3.9.0:** The following
options are used to configure the [AdminServer](#adminserver-configuration).

* *admin.rateLimiterIntervalInMS* :
  (Java system property: **zookeeper.admin.rateLimiterIntervalInMS**)
  The time interval for rate limiting admin command to protect the server.
  Defaults to 5 mins.

* *admin.snapshot.enabled* :
  (Java system property: **zookeeper.admin.snapshot.enabled**)
  The flag for enabling the snapshot command. Defaults to true.

* *admin.restore.enabled* :
  (Java system property: **zookeeper.admin.restore.enabled**)
  The flag for enabling the restore command. Defaults to true.

* *admin.needClientAuth* :
  (Java system property: **zookeeper.admin.needClientAuth**)
  The flag to control whether client auth is needed. Using x509 auth requires true.
  Defaults to false.

  **New in 3.7.1:** The following
  options are used to configure the [AdminServer](#adminserver-configuration).

* *admin.forceHttps* :
  (Java system property: **zookeeper.admin.forceHttps**)
  Force AdminServer to use SSL, thus allowing only HTTPS traffic.
  Defaults to disabled.
  Overwrites **admin.portUnification** settings.

  **New in 3.6.0:** The following
  options are used to configure the [AdminServer](#adminserver-configuration).

* *admin.portUnification* :
  (Java system property: **zookeeper.admin.portUnification**)
  Enable the admin port to accept both HTTP and HTTPS traffic.
  Defaults to disabled.

  **New in 3.5.0:** The following
  options are used to configure the [AdminServer](#adminserver-configuration).

* *admin.enableServer* :
  (Java system property: **zookeeper.admin.enableServer**)
  Set to "false" to disable the AdminServer. By default the
  AdminServer is enabled.

* *admin.serverAddress* :
  (Java system property: **zookeeper.admin.serverAddress**)
  The address the embedded Jetty server listens on. Defaults to 0.0.0.0.

* *admin.serverPort* :
  (Java system property: **zookeeper.admin.serverPort**)
  The port the embedded Jetty server listens on. Defaults to 8080.

* *admin.idleTimeout* :
  (Java system property: **zookeeper.admin.idleTimeout**)
  Set the maximum idle time in milliseconds that a connection can wait
  before sending or receiving data. Defaults to 30000 ms.

* *admin.commandURL* :
  (Java system property: **zookeeper.admin.commandURL**)
  The URL for listing and issuing commands relative to the
  root URL. Defaults to "/commands".
`,l={title:"Configuration Parameters",description:"Complete reference for all ZooKeeper configuration parameters, including minimum and advanced settings, cluster options, encryption and authentication, performance tuning, and the AdminServer."},h=[{href:"/admin-ops/dynamic-reconfiguration"},{href:"/developer/programmers-guide/data-model#ttl-nodes"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2901"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179"},{href:"/admin-ops/quota-guide"},{href:"https://cseweb.ucsd.edu/~daniele/papers/IncHash.pdf"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3179"},{href:"https://en.wikipedia.org/wiki/Gzip"},{href:"https://en.wikipedia.org/wiki/Snappy_(compression)"},{href:"/admin-ops/monitor-and-audit-logs"},{href:"/admin-ops/monitor-and-audit-logs"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3575"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4074"},{href:"#minimum-configuration"},{href:"#minimum-configuration"},{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188"},{href:"#minimum-configuration"},{href:"/admin-ops/quorums"},{href:"/admin-ops/quorums"},{href:"/admin-ops/dynamic-reconfiguration"},{href:"/admin-ops/administrators-guide/commands#the-four-letter-words"},{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3320"},{href:"/developer/programmers-guide/bindings#client-configuration-parameters"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3979"},{href:"#tls-cipher-suites"},{href:"https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/ocsp.html#setting-up-a-java-client-to-use-client-driven-ocsp"},{href:"mailto:zk@EXAMPLE.COM"},{href:"#cluster-options"},{href:"#cluster-options"},{href:"#cluster-options"},{href:"#adminserver-configuration"},{href:"#adminserver-configuration"},{href:"#adminserver-configuration"},{href:"#adminserver-configuration"},{href:"#adminserver-configuration"}],c={contents:[{heading:void 0,content:`ZooKeeper's behavior is governed by the ZooKeeper configuration
file. This file is designed so that the exact same file can be used by
all the servers that make up a ZooKeeper server assuming the disk
layouts are the same. If servers use different configuration files, care
must be taken to ensure that the list of servers in all of the different
configuration files match.`},{heading:void 0,content:"type: info"},{heading:void 0,content:`In 3.5.0 and later, some of these parameters should be placed in a dynamic
configuration file. If they are placed in the static configuration file,
ZooKeeper will automatically move them over to the dynamic configuration file.
See Dynamic Reconfiguration for more
information.`},{heading:"minimum-configuration",content:`Here are the minimum configuration keywords that must be defined
in the configuration file:`},{heading:"minimum-configuration",content:`clientPort :
the port to listen for client connections; that is, the
port that clients attempt to connect to.`},{heading:"minimum-configuration",content:`secureClientPort :
the port to listen on for secure client connections using SSL.
clientPort specifies
the port for plaintext connections while secureClientPort specifies the port for SSL
connections. Specifying both enables mixed-mode while omitting
either will disable that mode.
Note that SSL feature will be enabled when user plugs-in
zookeeper.serverCnxnFactory, zookeeper.clientCnxnSocket as Netty.`},{heading:"minimum-configuration",content:`observerMasterPort :
the port to listen for observer connections; that is, the
port that observers attempt to connect to.
if the property is set then the server will host observer connections
when in follower mode in addition to when in leader mode and correspondingly
attempt to connect to any voting peer when in observer mode.`},{heading:"minimum-configuration",content:`dataDir :
the location where ZooKeeper will store the in-memory
database snapshots and, unless specified otherwise, the
transaction log of updates to the database.`},{heading:"minimum-configuration",content:"type: info"},{heading:"minimum-configuration",content:`Be careful where you put the transaction log. A dedicated transaction log
device is key to consistent good performance. Putting the log on a busy
device will adversely affect performance.`},{heading:"minimum-configuration",content:`tickTime :
the length of a single tick, which is the basic time unit
used by ZooKeeper, as measured in milliseconds. It is used to
regulate heartbeats, and timeouts. For example, the minimum
session timeout will be two ticks.`},{heading:"advanced-configuration",content:`The configuration settings in the section are optional. You can
use them to further fine tune the behaviour of your ZooKeeper servers.
Some can also be set using Java system properties, generally of the
form zookeeper.keyword. The exact system
property, when available, is noted below.`},{heading:"advanced-configuration",content:`dataLogDir :
(No Java system property)
This option will direct the machine to write the
transaction log to the dataLogDir rather than the dataDir. This allows a dedicated log
device to be used, and helps avoid competition between logging
and snapshots.`},{heading:"advanced-configuration",content:"type: info"},{heading:"advanced-configuration",content:`Having a dedicated log device has a large impact on throughput and stable
latencies. It is highly recommended dedicating a log device and set
dataLogDir to point to a directory on that device, and then make sure to
point dataDir to a directory not residing on that device.`},{heading:"advanced-configuration",content:`globalOutstandingLimit :
(Java system property: zookeeper.globalOutstandingLimit.)
Clients can submit requests faster than ZooKeeper can
process them, especially if there are a lot of clients. To
prevent ZooKeeper from running out of memory due to queued
requests, ZooKeeper will throttle clients so that there are no
more than globalOutstandingLimit outstanding requests across
entire ensemble, equally divided. The default limit is 1,000
and, for example, with 3 members each of them will have
1000 / 2 = 500 individual limit.`},{heading:"advanced-configuration",content:`preAllocSize :
(Java system property: zookeeper.preAllocSize)
To avoid seeks ZooKeeper allocates space in the
transaction log file in blocks of preAllocSize kilobytes. The
default block size is 64M. One reason for changing the size of
the blocks is to reduce the block size if snapshots are taken
more often. (Also, see snapCount and snapSizeLimitInKb).`},{heading:"advanced-configuration",content:`snapCount :
(Java system property: zookeeper.snapCount)
ZooKeeper records its transactions using snapshots and
a transaction log (think write-ahead log). The number of
transactions recorded in the transaction log before a snapshot
can be taken (and the transaction log rolled) is determined
by snapCount. In order to prevent all of the machines in the quorum
from taking a snapshot at the same time, each ZooKeeper server
will take a snapshot when the number of transactions in the transaction log
reaches a runtime generated random value in the [snapCount/2+1, snapCount]
range. The default snapCount is 100,000.`},{heading:"advanced-configuration",content:`commitLogCount * :
(Java system property: zookeeper.commitLogCount)
Zookeeper maintains an in-memory list of last committed requests for fast synchronization with
followers when the followers are not too behind. This improves sync performance in case when your
snapshots are large (>100,000). The default value is 500 which is the recommended minimum.`},{heading:"advanced-configuration",content:`snapSizeLimitInKb :
(Java system property: zookeeper.snapSizeLimitInKb)
ZooKeeper records its transactions using snapshots and
a transaction log (think write-ahead log). The total size in bytes allowed
in the set of transactions recorded in the transaction log before a snapshot
can be taken (and the transaction log rolled) is determined
by snapSize. In order to prevent all of the machines in the quorum
from taking a snapshot at the same time, each ZooKeeper server
will take a snapshot when the size in bytes of the set of transactions in the
transaction log reaches a runtime generated random value in the [snapSize/2+1, snapSize]
range. Each file system has a minimum standard file size and in order
to for valid functioning of this feature, the number chosen must be larger
than that value. The default snapSizeLimitInKb is 4,194,304 (4GB).
A non-positive value will disable the feature.`},{heading:"advanced-configuration",content:`txnLogSizeLimitInKb :
(Java system property: zookeeper.txnLogSizeLimitInKb)
Zookeeper transaction log file can also be controlled more
directly using txnLogSizeLimitInKb. Larger txn logs can lead to
slower follower syncs when sync is done using transaction log.
This is because leader has to scan through the appropriate log
file on disk to find the transaction to start sync from.
This feature is turned off by default and snapCount and snapSizeLimitInKb are the
only values that limit transaction log size. When enabled
Zookeeper will roll the log when any of the limits is hit.
Please note that actual log size can exceed this value by the size
of the serialized transaction. On the other hand, if this value is
set too close to (or smaller than) preAllocSize,
it can cause Zookeeper to roll the log for every transaction. While
this is not a correctness issue, this may cause severely degraded
performance. To avoid this and to get most out of this feature, it is
recommended to set the value to N * preAllocSize
where N >= 2.`},{heading:"advanced-configuration",content:`maxCnxns :
(Java system property: zookeeper.maxCnxns)
Limits the total number of concurrent connections that can be made to a
zookeeper server (per client Port of each server ). This is used to prevent certain
classes of DoS attacks. The default is 0 and setting it to 0 entirely removes
the limit on total number of concurrent connections. Accounting for the
number of connections for serverCnxnFactory and a secureServerCnxnFactory is done
separately, so a peer is allowed to host up to 2*maxCnxns provided they are of appropriate types.`},{heading:"advanced-configuration",content:`maxClientCnxns :
(No Java system property)
Limits the number of concurrent connections (at the socket
level) that a single client, identified by IP address, may make
to a single member of the ZooKeeper ensemble. This is used to
prevent certain classes of DoS attacks, including file
descriptor exhaustion. The default is 60. Setting this to 0
entirely removes the limit on concurrent connections.`},{heading:"advanced-configuration",content:`clientPortAddress :
New in 3.3.0: the
address (ipv4, ipv6 or hostname) to listen for client
connections; that is, the address that clients attempt
to connect to. This is optional, by default we bind in
such a way that any connection to the clientPort for any
address/interface/nic on the server will be
accepted.`},{heading:"advanced-configuration",content:`minSessionTimeout :
(No Java system property)
New in 3.3.0: the
minimum session timeout in milliseconds that the server
will allow the client to negotiate. Defaults to 2 times
the tickTime.`},{heading:"advanced-configuration",content:`maxSessionTimeout :
(No Java system property)
New in 3.3.0: the
maximum session timeout in milliseconds that the server
will allow the client to negotiate. Defaults to 20 times
the tickTime.`},{heading:"advanced-configuration",content:`fsync.warningthresholdms :
(Java system property: zookeeper.fsync.warningthresholdms)
New in 3.3.4: A
warning message will be output to the log whenever an
fsync in the Transactional Log (WAL) takes longer than
this value. The values is specified in milliseconds and
defaults to 1000. This value can only be set as a
system property.`},{heading:"advanced-configuration",content:`maxResponseCacheSize :
(Java system property: zookeeper.maxResponseCacheSize)
When set to a positive integer, it determines the size
of the cache that stores the serialized form of recently
read records. Helps save the serialization cost on
popular znodes. The metrics response_packet_cache_hits
and response_packet_cache_misses can be used to tune
this value to a given workload. The feature is turned on
by default with a value of 400, set to 0 or a negative
integer to turn the feature off.`},{heading:"advanced-configuration",content:`maxGetChildrenResponseCacheSize :
(Java system property: zookeeper.maxGetChildrenResponseCacheSize)
New in 3.6.0:
Similar to maxResponseCacheSize, but applies to get children
requests. The metrics response_packet_get_children_cache_hits
and response_packet_get_children_cache_misses can be used to tune
this value to a given workload. The feature is turned on
by default with a value of 400, set to 0 or a negative
integer to turn the feature off.`},{heading:"advanced-configuration",content:`autopurge.snapRetainCount :
(No Java system property)
New in 3.4.0:
When enabled, ZooKeeper auto purge feature retains
the autopurge.snapRetainCount most
recent snapshots and the corresponding transaction logs in the
dataDir and dataLogDir respectively and deletes the rest.
Defaults to 3. Minimum value is 3.`},{heading:"advanced-configuration",content:`autopurge.purgeInterval :
(No Java system property)
New in 3.4.0: The
time interval in hours for which the purge task has to
be triggered. Set to a positive integer (1 and above)
to enable the auto purging. Defaults to 0.
Suffix support added in 3.10.0: The interval is specified as an integer with an optional suffix to indicate the time unit.
Supported suffixes are: ms for milliseconds, s for seconds, m for minutes, h for hours, and d for days.
For example, "10m" represents 10 minutes, and "5h" represents 5 hours.
If no suffix is provided, the default unit is hours.`},{heading:"advanced-configuration",content:`syncEnabled :
(Java system property: zookeeper.observer.syncEnabled)
New in 3.4.6, 3.5.0:
The observers now log transaction and write snapshot to disk
by default like the participants. This reduces the recovery time
of the observers on restart. Set to "false" to disable this
feature. Default is "true"`},{heading:"advanced-configuration",content:`extendedTypesEnabled :
(Java system property only: zookeeper.extendedTypesEnabled)
New in 3.5.4, 3.6.0: Define to true to enable
extended features such as the creation of TTL Nodes.
They are disabled by default. IMPORTANT: when enabled server IDs must
be less than 255 due to internal limitations.`},{heading:"advanced-configuration",content:`emulate353TTLNodes :
(Java system property only:zookeeper.emulate353TTLNodes).
New in 3.5.4, 3.6.0: Due to [ZOOKEEPER-2901]
(https://issues.apache.org/jira/browse/ZOOKEEPER-2901) TTL nodes
created in version 3.5.3 are not supported in 3.5.4/3.6.0. However, a workaround is provided via the
zookeeper.emulate353TTLNodes system property. If you used TTL nodes in ZooKeeper 3.5.3 and need to maintain
compatibility set zookeeper.emulate353TTLNodes to true in addition to
zookeeper.extendedTypesEnabled. NOTE: due to the bug, server IDs
must be 127 or less. Additionally, the maximum support TTL value is 1099511627775 which is smaller
than what was allowed in 3.5.3 (1152921504606846975)`},{heading:"advanced-configuration",content:`watchManagerName :
(Java system property only: zookeeper.watchManagerName)
New in 3.6.0: Added in ZOOKEEPER-1179
New watcher manager WatchManagerOptimized is added to optimize the memory overhead in heavy watch use cases. This
config is used to define which watcher manager to be used. Currently, we only support WatchManager and
WatchManagerOptimized.`},{heading:"advanced-configuration",content:`watcherCleanThreadsNum :
(Java system property only: zookeeper.watcherCleanThreadsNum)
New in 3.6.0: Added in ZOOKEEPER-1179
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, this config is used to decide how
many thread is used in the WatcherCleaner. More thread usually means larger clean up throughput. The
default value is 2, which is good enough even for heavy and continuous session closing/recreating cases.`},{heading:"advanced-configuration",content:`watcherCleanThreshold :
(Java system property only: zookeeper.watcherCleanThreshold)
New in 3.6.0: Added in ZOOKEEPER-1179
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
heavy, batch processing will reduce the cost and improve the performance. This setting is used to decide
the batch size. The default one is 1000, we don't need to change it if there is no memory or clean up
speed issue.`},{heading:"advanced-configuration",content:`watcherCleanIntervalInSeconds :
(Java system property only:zookeeper.watcherCleanIntervalInSeconds)
New in 3.6.0: Added in ZOOKEEPER-1179
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
heavy, batch processing will reduce the cost and improve the performance. Besides watcherCleanThreshold,
this setting is used to clean up the dead watchers after certain time even the dead watchers are not larger
than watcherCleanThreshold, so that we won't leave the dead watchers there for too long. The default setting
is 10 minutes, which usually don't need to be changed.`},{heading:"advanced-configuration",content:`maxInProcessingDeadWatchers :
(Java system property only: zookeeper.maxInProcessingDeadWatchers)
New in 3.6.0: Added in ZOOKEEPER-1179
This is used to control how many backlog can we have in the WatcherCleaner, when it reaches this number, it will
slow down adding the dead watcher to WatcherCleaner, which will in turn slow down adding and closing
watchers, so that we can avoid OOM issue. By default there is no limit, you can set it to values like
watcherCleanThreshold * 1000.`},{heading:"advanced-configuration",content:`bitHashCacheSize :
(Java system property only: zookeeper.bitHashCacheSize)
New 3.6.0: Added in ZOOKEEPER-1179
This is the setting used to decide the HashSet cache size in the BitHashSet implementation. Without HashSet, we
need to use O(N) time to get the elements, N is the bit numbers in elementBits. But we need to
keep the size small to make sure it doesn't cost too much in memory, there is a trade off between memory
and time complexity. The default value is 10, which seems a relatively reasonable cache size.`},{heading:"advanced-configuration",content:`fastleader.minNotificationInterval :
(Java system property: zookeeper.fastleader.minNotificationInterval)
Lower bound for length of time between two consecutive notification
checks on the leader election. This interval determines how long a
peer waits to check the set of election votes and effects how
quickly an election can resolve. The interval follows a backoff
strategy from the configured minimum (this) and the configured maximum
(fastleader.maxNotificationInterval) for long elections.`},{heading:"advanced-configuration",content:`fastleader.maxNotificationInterval :
(Java system property: zookeeper.fastleader.maxNotificationInterval)
Upper bound for length of time between two consecutive notification
checks on the leader election. This interval determines how long a
peer waits to check the set of election votes and effects how
quickly an election can resolve. The interval follows a backoff
strategy from the configured minimum (fastleader.minNotificationInterval)
and the configured maximum (this) for long elections.`},{heading:"advanced-configuration",content:`connectionMaxTokens :
(Java system property: zookeeper.connection_throttle_tokens)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the maximum number of tokens in the token-bucket.
When set to 0, throttling is disabled. Default is 0.`},{heading:"advanced-configuration",content:`connectionTokenFillTime :
(Java system property: zookeeper.connection_throttle_fill_time)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the interval in milliseconds when the token bucket is re-filled with
connectionTokenFillCount tokens. Default is 1.`},{heading:"advanced-configuration",content:`connectionTokenFillCount :
(Java system property: zookeeper.connection_throttle_fill_count)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the number of tokens to add to the token bucket every
connectionTokenFillTime milliseconds. Default is 1.`},{heading:"advanced-configuration",content:`connectionFreezeTime :
(Java system property: zookeeper.connection_throttle_freeze_time)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the interval in milliseconds when the dropping
probability is adjusted. When set to -1, probabilistic dropping is disabled.
Default is -1.`},{heading:"advanced-configuration",content:`connectionDropIncrease :
(Java system property: zookeeper.connection_throttle_drop_increase)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the dropping probability to increase. The throttler
checks every connectionFreezeTime milliseconds and if the token bucket is
empty, the dropping probability will be increased by connectionDropIncrease.
The default is 0.02.`},{heading:"advanced-configuration",content:`connectionDropDecrease :
(Java system property: zookeeper.connection_throttle_drop_decrease)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the dropping probability to decrease. The throttler
checks every connectionFreezeTime milliseconds and if the token bucket has
more tokens than a threshold, the dropping probability will be decreased by
connectionDropDecrease. The threshold is connectionMaxTokens *
connectionDecreaseRatio. The default is 0.002.`},{heading:"advanced-configuration",content:`connectionDecreaseRatio :
(Java system property: zookeeper.connection_throttle_decrease_ratio)
New in 3.6.0:
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping. This parameter defines the threshold to decrease the dropping
probability. The default is 0.`},{heading:"advanced-configuration",content:`zookeeper.connection_throttle_weight_enabled :
(Java system property only)
New in 3.6.0:
Whether to consider connection weights when throttling. Only useful when connection throttle is enabled, that is, connectionMaxTokens is larger than 0. The default is false.`},{heading:"advanced-configuration",content:`zookeeper.connection_throttle_global_session_weight :
(Java system property only)
New in 3.6.0:
The weight of a global session. It is the number of tokens required for a global session request to get through the connection throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 3.`},{heading:"advanced-configuration",content:`zookeeper.connection_throttle_local_session_weight :
(Java system property only)
New in 3.6.0:
The weight of a local session. It is the number of tokens required for a local session request to get through the connection throttler. It has to be a positive integer no larger than the weight of a global session or a renew session. The default is 1.`},{heading:"advanced-configuration",content:`zookeeper.connection_throttle_renew_session_weight :
(Java system property only)
New in 3.6.0:
The weight of renewing a session. It is also the number of tokens required for a reconnect request to get through the throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 2.`},{heading:"advanced-configuration",content:`clientPortListenBacklog :
(No Java system property)
New in 3.4.14, 3.5.5, 3.6.0:
The socket backlog length for the ZooKeeper server socket. This controls
the number of requests that will be queued server-side to be processed
by the ZooKeeper server. Connections that exceed this length will receive
a network timeout (30s) which may cause ZooKeeper session expiry issues.
By default, this value is unset (-1) which, on Linux, uses a backlog of
50. This value must be a positive number.`},{heading:"advanced-configuration",content:`serverCnxnFactory :
(Java system property: zookeeper.serverCnxnFactory)
Specifies ServerCnxnFactory implementation.
This should be set to NettyServerCnxnFactory in order to use TLS based server communication.
Default is NIOServerCnxnFactory.`},{heading:"advanced-configuration",content:`flushDelay :
(Java system property: zookeeper.flushDelay)
Time in milliseconds to delay the flush of the commit log.
Does not affect the limit defined by maxBatchSize.
Disabled by default (with value 0). Ensembles with high write rates
may see throughput improved with a value of 10-20 ms.`},{heading:"advanced-configuration",content:`maxWriteQueuePollTime :
(Java system property: zookeeper.maxWriteQueuePollTime)
If flushDelay is enabled, this determines the amount of time in milliseconds
to wait before flushing when no new requests are being queued.
Set to flushDelay/3 by default (implicitly disabled by default).`},{heading:"advanced-configuration",content:`maxBatchSize :
(Java system property: zookeeper.maxBatchSize)
The number of transactions allowed in the server before a flush of the
commit log is triggered.
Does not affect the limit defined by flushDelay.
Default is 1000.`},{heading:"advanced-configuration",content:`enforceQuota :
(Java system property: zookeeper.enforceQuota)
New in 3.7.0:
Enforce the quota check. When enabled and the client exceeds the total bytes or children count hard quota under a znode, the server will reject the request and reply the client a QuotaExceededException by force.
The default value is: false. Exploring quota feature for more details.`},{heading:"advanced-configuration",content:`requestThrottleLimit :
(Java system property: zookeeper.request_throttle_max_requests)
New in 3.6.0:
The total number of outstanding requests allowed before the RequestThrottler starts stalling. When set to 0, throttling is disabled. The default is 0.`},{heading:"advanced-configuration",content:`requestThrottleStallTime :
(Java system property: zookeeper.request_throttle_stall_time)
New in 3.6.0:
The maximum time (in milliseconds) for which a thread may wait to be notified that it may proceed processing a request. The default is 100.`},{heading:"advanced-configuration",content:`requestThrottleDropStale :
(Java system property: request_throttle_drop_stale)
New in 3.6.0:
When enabled, the throttler will drop stale requests rather than issue them to the request pipeline. A stale request is a request sent by a connection that is now closed, and/or a request that will have a request latency higher than the sessionTimeout. The default is true.`},{heading:"advanced-configuration",content:`requestStaleLatencyCheck :
(Java system property: zookeeper.request_stale_latency_check)
New in 3.6.0:
When enabled, a request is considered stale if the request latency is higher than its associated session timeout. Disabled by default.`},{heading:"advanced-configuration",content:`requestStaleConnectionCheck :
(Java system property: zookeeper.request_stale_connection_check)
New in 3.6.0:
When enabled, a request is considered stale if the request's connection has closed. Enabled by default.`},{heading:"advanced-configuration",content:`zookeeper.request_throttler.shutdownTimeout :
(Java system property only)
New in 3.6.0:
The time (in milliseconds) the RequestThrottler waits for the request queue to drain during shutdown before it shuts down forcefully. The default is 10000.`},{heading:"advanced-configuration",content:`advancedFlowControlEnabled :
(Java system property: zookeeper.netty.advancedFlowControl.enabled)
Using accurate flow control in netty based on the status of ZooKeeper
pipeline to avoid direct buffer OOM. It will disable the AUTO_READ in
Netty.`},{heading:"advanced-configuration",content:`enableEagerACLCheck :
(Java system property only: zookeeper.enableEagerACLCheck)
When set to "true", enables eager ACL check on write requests on each local
server before sending the requests to quorum. Default is "false".`},{heading:"advanced-configuration",content:`maxConcurrentSnapSyncs :
(Java system property: zookeeper.leader.maxConcurrentSnapSyncs)
The maximum number of snap syncs a leader or a follower can serve at the same
time. The default is 10.`},{heading:"advanced-configuration",content:`maxConcurrentDiffSyncs :
(Java system property: zookeeper.leader.maxConcurrentDiffSyncs)
The maximum number of diff syncs a leader or a follower can serve at the same
time. The default is 100.`},{heading:"advanced-configuration",content:`digest.enabled :
(Java system property only: zookeeper.digest.enabled)
New in 3.6.0:
The digest feature is added to detect the data inconsistency inside
ZooKeeper when loading database from disk, catching up and following
leader, its doing incrementally hash check for the DataTree based on
the adHash paper mentioned in`},{heading:"advanced-configuration",content:"https://cseweb.ucsd.edu/~daniele/papers/IncHash.pdf"},{heading:"advanced-configuration",content:`The idea is simple, the hash value of DataTree will be updated incrementally
based on the changes to the set of data. When the leader is preparing the txn,
it will pre-calculate the hash of the tree based on the changes happened with
formula:`},{heading:"advanced-configuration",content:"current_hash = current_hash + hash(new node data) - hash(old node data)"},{heading:"advanced-configuration",content:`If it’s creating a new node, the hash(old node data) will be 0, and if it’s a
delete node op, the hash(new node data) will be 0.`},{heading:"advanced-configuration",content:`This hash will be associated with each txn to represent the expected hash value
after applying the txn to the data tree, it will be sent to followers with
original proposals. Learner will compare the actual hash value with the one in
the txn after applying the txn to the data tree, and report mismatch if it’s not
the same.`},{heading:"advanced-configuration",content:`These digest value will also be persisted with each txn and snapshot on the disk,
so when servers restarted and load data from disk, it will compare and see if
there is hash mismatch, which will help detect data loss issue on disk.`},{heading:"advanced-configuration",content:`For the actual hash function, we’re using CRC internally, it’s not a collisionless
hash function, but it’s more efficient compared to collisionless hash, and the
collision possibility is really really rare and can already meet our needs here.`},{heading:"advanced-configuration",content:`This feature is backward and forward compatible, so it can safely roll upgrade,
downgrade, enabled and later disabled without any compatible issue. Here are the
scenarios have been covered and tested:`},{heading:"advanced-configuration",content:`When leader runs with new code while follower runs with old one, the digest will
be appended to the end of each txn, follower will only read header and txn data,
digest value in the txn will be ignored. It won't affect the follower reads and
processes the next txn.`},{heading:"advanced-configuration",content:`When leader runs with old code while follower runs with new one, the digest won't
be sent with txn, when follower tries to read the digest, it will throw EOF which
is caught and handled gracefully with digest value set to null.`},{heading:"advanced-configuration",content:`When loading old snapshot with new code, it will throw IOException when trying to
read the non-exist digest value, and the exception will be caught and digest will
be set to null, which means we won't compare digest when loading this snapshot,
which is expected to happen during rolling upgrade`},{heading:"advanced-configuration",content:`When loading new snapshot with old code, it will finish successfully after deserializing
the data tree, the digest value at the end of snapshot file will be ignored`},{heading:"advanced-configuration",content:`The scenarios of rolling restart with flags change are similar to the 1st and 2nd
scenarios discussed above, if the leader enabled but follower not, digest value will
be ignored, and follower won't compare the digest during runtime; if leader disabled
but follower enabled, follower will get EOF exception which is handled gracefully.`},{heading:"advanced-configuration",content:`Note: the current digest calculation excluded nodes under /zookeeper
due to the potential inconsistency in the /zookeeper/quota stat node,
we can include that after that issue is fixed.`},{heading:"advanced-configuration",content:'By default, this feature is enabled, set "false" to disable it.'},{heading:"advanced-configuration",content:`snapshot.compression.method :
(Java system property: zookeeper.snapshot.compression.method)
New in 3.6.0:
This property controls whether or not ZooKeeper should compress snapshots
before storing them on disk (see ZOOKEEPER-3179).
Possible values are:`},{heading:"advanced-configuration",content:'"": Disabled (no snapshot compression). This is the default behavior.'},{heading:"advanced-configuration",content:'"gz": See gzip compression.'},{heading:"advanced-configuration",content:'"snappy": See Snappy compression.'},{heading:"advanced-configuration",content:`snapshot.trust.empty :
(Java system property: zookeeper.snapshot.trust.empty)
New in 3.5.6:
This property controls whether or not ZooKeeper should treat missing
snapshot files as a fatal state that can't be recovered from.
Set to true to allow ZooKeeper servers recover without snapshot
files. This should only be set during upgrading from old versions of
ZooKeeper (3.4.x, pre 3.5.3) where ZooKeeper might only have transaction
log files but without presence of snapshot files. If the value is set
during upgrade, we recommend setting the value back to false after upgrading
and restart ZooKeeper process so ZooKeeper can continue normal data
consistency check during recovery process.
Default value is false.`},{heading:"advanced-configuration",content:`audit.enable :
(Java system property: zookeeper.audit.enable)
New in 3.6.0:
By default audit logs are disabled. Set to "true" to enable it. Default value is "false".
See the ZooKeeper audit logs for more information.`},{heading:"advanced-configuration",content:`audit.impl.class :
(Java system property: zookeeper.audit.impl.class)
New in 3.6.0:
Class to implement the audit logger. By default logback based audit logger org.apache.zookeeper.audit
.Slf4jAuditLogger is used.
See the ZooKeeper audit logs for more information.`},{heading:"advanced-configuration",content:`largeRequestMaxBytes :
(Java system property: zookeeper.largeRequestMaxBytes)
New in 3.6.0:
The maximum number of bytes of all inflight large request. The connection will be closed if a coming large request causes the limit exceeded. The default is 100 * 1024 * 1024.`},{heading:"advanced-configuration",content:`largeRequestThreshold :
(Java system property: zookeeper.largeRequestThreshold)
New in 3.6.0:
The size threshold after which a request is considered a large request. If it is -1, then all requests are considered small, effectively turning off large request throttling. The default is -1.`},{heading:"advanced-configuration",content:`outstandingHandshake.limit
(Java system property only: zookeeper.netty.server.outstandingHandshake.limit)
The maximum in-flight TLS handshake connections could have in ZooKeeper,
the connections exceed this limit will be rejected before starting handshake.
This setting doesn't limit the max TLS concurrency, but helps avoid herd
effect due to TLS handshake timeout when there are too many in-flight TLS
handshakes. Set it to something like 250 is good enough to avoid herd effect.`},{heading:"advanced-configuration",content:`netty.server.earlyDropSecureConnectionHandshakes
(Java system property: zookeeper.netty.server.earlyDropSecureConnectionHandshakes)
If the ZooKeeper server is not fully started, drop TCP connections before performing the TLS handshake.
This is useful in order to prevent flooding the server with many concurrent TLS handshakes after a restart.
Please note that if you enable this flag the server won't answer to 'ruok' commands if it is not fully started.
The behaviour of dropping the connection has been introduced in ZooKeeper 3.7 and it was not possible to disable it.
Since 3.7.1 and 3.8.0 this feature is disabled by default.`},{heading:"advanced-configuration",content:`throttledOpWaitTime
(Java system property: zookeeper.throttled_op_wait_time)
The time in the RequestThrottler queue longer than which a request will be marked as throttled.
A throttled requests will not be processed other than being fed down the pipeline of the server it belongs
to preserve the order of all requests.
The FinalProcessor will issue an error response (new error code: ZTHROTTLEDOP) for these undigested requests.
The intent is for the clients not to retry them immediately.
When set to 0, no requests will be throttled. The default is 0.`},{heading:"advanced-configuration",content:`learner.closeSocketAsync
(Java system property: zookeeper.learner.closeSocketAsync)
(Java system property: learner.closeSocketAsync)(Added for backward compatibility)
New in 3.7.0:
When enabled, a learner will close the quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time, block the shutdown process, potentially delay a new leader election, and leave the quorum unavailable. Closing the socket asynchronously avoids blocking the shutdown process despite the long socket closing time and a new leader election can be started while the socket being closed.
The default is false.`},{heading:"advanced-configuration",content:`leader.closeSocketAsync
(Java system property: zookeeper.leader.closeSocketAsync)
(Java system property: leader.closeSocketAsync)(Added for backward compatibility)
New in 3.7.0:
When enabled, the leader will close a quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time. If disconnecting a follower is initiated in ping() because of a failed SyncLimitCheck then the long socket closing time will block the sending of pings to other followers. Without receiving pings, the other followers will not send session information to the leader, which causes sessions to expire. Setting this flag to true ensures that pings will be sent regularly.
The default is false.`},{heading:"advanced-configuration",content:`learner.asyncSending
(Java system property: zookeeper.learner.asyncSending)
(Java system property: learner.asyncSending)(Added for backward compatibility)
New in 3.7.0:
The sending and receiving packets in Learner were done synchronously in a critical section. An untimely network issue could cause the followers to hang (see ZOOKEEPER-3575 and ZOOKEEPER-4074). The new design moves sending packets in Learner to a separate thread and sends the packets asynchronously. The new design is enabled with this parameter (learner.asyncSending).
The default is false.`},{heading:"advanced-configuration",content:`forward_learner_requests_to_commit_processor_disabled
(Java system property: zookeeper.forward_learner_requests_to_commit_processor_disabled)
When this property is set, the requests from learners won't be enqueued to
CommitProcessor queue, which will help save the resources and GC time on
leader. The default value is false.`},{heading:"advanced-configuration",content:`serializeLastProcessedZxid.enabled
(Java system property: zookeeper.serializeLastProcessedZxid.enabled)
New in 3.9.0:
If enabled, ZooKeeper serializes the lastProcessedZxid when snapshot and deserializes it
when restore. Defaults to true. Needs to be enabled for performing snapshot and restore
via admin server commands, as there is no snapshot file name to extract the lastProcessedZxid.`},{heading:"advanced-configuration",content:"This feature is backward and forward compatible. Here are the different scenarios."},{heading:"advanced-configuration",content:"Snapshot triggered by server internally"},{heading:"advanced-configuration",content:`When loading old snapshot with new code, it will throw EOFException when trying to
read the non-exist lastProcessedZxid value, and the exception will be caught.
The lastProcessedZxid will be set using the snapshot file name.`},{heading:"advanced-configuration",content:`When loading new snapshot with old code, it will finish successfully after deserializing
the digest value, the lastProcessedZxid at the end of snapshot file will be ignored.
The lastProcessedZxid will be set using the snapshot file name.`},{heading:"advanced-configuration",content:`Sync up between leader and follower: The lastProcessedZxid will not be serialized by
leader and deserialized by follower in both new and old code. It will be set to the
lastProcessedZxid sent from leader via QuorumPacket.`},{heading:"advanced-configuration",content:`Snapshot triggered via admin server APIs: The feature flag need to be enabled for the
snapshot command to work.`},{heading:"cluster-options",content:`The options in this section are designed for use with an ensemble
of servers — that is, when deploying clusters of servers.`},{heading:"cluster-options",content:`electionAlg :
(No Java system property)
Election implementation to use. A value of "1" corresponds to the
non-authenticated UDP-based version of fast leader election, "2"
corresponds to the authenticated UDP-based version of fast
leader election, and "3" corresponds to TCP-based version of
fast leader election. Algorithm 3 was made default in 3.2.0 and
prior versions (3.0.0 and 3.1.0) were using algorithm 1 and 2 as well.`},{heading:"cluster-options",content:"type: info"},{heading:"cluster-options",content:`The implementations of leader election 1, and 2 were deprecated in
3.4.0. Since 3.6.0 only FastLeaderElection is available, in case of upgrade
you have to shut down all of your servers and restart them with
electionAlg=3 (or by removing the line from the configuration file).`},{heading:"cluster-options",content:`maxTimeToWaitForEpoch :
(Java system property: zookeeper.leader.maxTimeToWaitForEpoch)
New in 3.6.0:
The maximum time to wait for epoch from voters when activating
leader. If leader received a LOOKING notification from one of
its voters, and it hasn't received epoch packets from majority
within maxTimeToWaitForEpoch, then it will goto LOOKING and
elect leader again.
This can be tuned to reduce the quorum or server unavailable
time, it can be set to be much smaller than initLimit * tickTime.
In cross datacenter environment, it can be set to something
like 2s.`},{heading:"cluster-options",content:`initLimit :
(No Java system property)
Amount of time, in ticks (see tickTime), to allow followers to
connect and sync to a leader. Increased this value as needed, if
the amount of data managed by ZooKeeper is large.`},{heading:"cluster-options",content:`connectToLearnerMasterLimit :
(Java system property: zookeeper.connectToLearnerMasterLimit)
Amount of time, in ticks (see tickTime), to allow followers to
connect to the leader after leader election. Defaults to the value of initLimit.
Use when initLimit is high so connecting to learner master doesn't result in higher timeout.`},{heading:"cluster-options",content:`leaderServes :
(Java system property: zookeeper.leaderServes)
Leader accepts client connections. Default value is "yes".
The leader machine coordinates updates. For higher update
throughput at the slight expense of read throughput the leader
can be configured to not accept clients and focus on
coordination. The default to this option is yes, which means
that a leader will accept client connections.`},{heading:"cluster-options",content:"type: info"},{heading:"cluster-options",content:`Turning on leader selection is highly recommended when you have more than
three ZooKeeper servers in an ensemble.`},{heading:"cluster-options",content:`server.x=[hostname]:nnnnn[:nnnnn] etc :
(No Java system property)
Servers making up the ZooKeeper ensemble. When the server
starts up, it determines which server it is by looking for the
file myid in the data directory. That file contains the server number, in ASCII,
and it should match x in server.x in the left hand side of this setting.
The list of servers that make up ZooKeeper servers that is
used by the clients must match the list of ZooKeeper servers
that each ZooKeeper server has.
There are two port numbers nnnnn.
The first followers used to connect to the leader, and the second is for
leader election. If you want to test multiple servers on a single machine, then
different ports can be used for each server.`},{heading:"cluster-options",content:`Since ZooKeeper 3.6.0 it is possible to specify multiple addresses for each
ZooKeeper server (see ZOOKEEPER-3188).
To enable this feature, you must set the multiAddress.enabled configuration property
to true. This helps to increase availability and adds network level
resiliency to ZooKeeper. When multiple physical network interfaces are used
for the servers, ZooKeeper is able to bind on all interfaces and runtime switching
to a working interface in case a network error. The different addresses can be specified
in the config using a pipe ('|') character. A valid configuration using multiple addresses looks like:`},{heading:"cluster-options",content:"type: info"},{heading:"cluster-options",content:`By enabling this feature, the Quorum protocol (ZooKeeper Server-Server
protocol) will change. The users will not notice this and when anyone starts
a ZooKeeper cluster with the new config, everything will work normally.
However, it's not possible to enable this feature and specify multiple
addresses during a rolling upgrade if the old ZooKeeper cluster didn't
support the multiAddress feature (and the new Quorum protocol). In case if
you need this feature but you also need to perform a rolling upgrade from a
ZooKeeper cluster older than 3.6.0, then you first need to do the rolling
upgrade without enabling the MultiAddress feature and later make a separate
rolling restart with the new configuration where multiAddress.enabled is
set to true and multiple addresses are provided.`},{heading:"cluster-options",content:`syncLimit :
(No Java system property)
Amount of time, in ticks (see tickTime), to allow followers to sync
with ZooKeeper. If followers fall too far behind a leader, they
will be dropped.`},{heading:"cluster-options",content:`group.x=nnnnn[:nnnnn] :
(No Java system property)
Enables a hierarchical quorum construction."x" is a group identifier
and the numbers following the "=" sign correspond to server identifiers.
The left-hand side of the assignment is a colon-separated list of server
identifiers. Note that groups must be disjoint and the union of all groups
must be the ZooKeeper ensemble.
You will find an example here`},{heading:"cluster-options",content:`weight.x=nnnnn :
(No Java system property)
Used along with "group", it assigns a weight to a server when
forming quorums. Such a value corresponds to the weight of a server
when voting. There are a few parts of ZooKeeper that require voting
such as leader election and the atomic broadcast protocol. By default
the weight of server is 1. If the configuration defines groups, but not
weights, then a value of 1 will be assigned to all servers.
You will find an example here`},{heading:"cluster-options",content:`cnxTimeout :
(Java system property: zookeeper.cnxTimeout)
Sets the timeout value for opening connections for leader election notifications.
Only applicable if you are using electionAlg 3.`},{heading:"cluster-options",content:"type: info"},{heading:"cluster-options",content:`quorumCnxnTimeoutMs :
(Java system property: zookeeper.quorumCnxnTimeoutMs)
Sets the read timeout value for the connections for leader election notifications.
Only applicable if you are using electionAlg 3.`},{heading:"cluster-options",content:"type: info"},{heading:"cluster-options",content:`Default value is -1, which will then use the syncLimit * tickTime as the
timeout.`},{heading:"cluster-options",content:`standaloneEnabled :
(No Java system property)
New in 3.5.0:
When set to false, a single server can be started in replicated
mode, a lone participant can run with observers, and a cluster
can reconfigure down to one node, and up from one node. The
default is true for backwards compatibility. It can be set
using QuorumPeerConfig's setStandaloneEnabled method or by
adding "standaloneEnabled=false" or "standaloneEnabled=true"
to a server's config file.`},{heading:"cluster-options",content:`reconfigEnabled :
(No Java system property)
New in 3.5.3:
This controls the enabling or disabling of
Dynamic Reconfiguration feature. When the feature
is enabled, users can perform reconfigure operations through
the ZooKeeper client API or through ZooKeeper command line tools
assuming users are authorized to perform such operations.
When the feature is disabled, no user, including the super user,
can perform a reconfiguration. Any attempt to reconfigure will return an error.
"reconfigEnabled" option can be set as
"reconfigEnabled=false" or
"reconfigEnabled=true"
to a server's config file, or using QuorumPeerConfig's
setReconfigEnabled method. The default value is false.
If present, the value should be consistent across every server in
the entire ensemble. Setting the value as true on some servers and false
on other servers will cause inconsistent behavior depending on which server
is elected as leader. If the leader has a setting of
"reconfigEnabled=true", then the ensemble
will have reconfig feature enabled. If the leader has a setting of
"reconfigEnabled=false", then the ensemble
will have reconfig feature disabled. It is thus recommended having a consistent
value for "reconfigEnabled" across servers
in the ensemble.`},{heading:"cluster-options",content:`4lw.commands.whitelist :
(Java system property: zookeeper.4lw.commands.whitelist)
New in 3.5.3:
A list of comma separated Four Letter Words
commands that user wants to use. A valid Four Letter Words
command must be put in this list else ZooKeeper server will
not enable the command.
By default the whitelist only contains "srvr" command
which zkServer.sh uses. Additionally, if Read Only Mode is enabled by setting
Java system property readonlymode.enabled, then the "isro" command is
added to the whitelist. The rest of four-letter word commands are disabled
by default: attempting to use them will gain a response
".... is not executed because it is not in the whitelist."
Here's an example of the configuration that enables stat, ruok, conf, and isro
command while disabling the rest of Four Letter Words command:`},{heading:"cluster-options",content:`If you really need enable all four-letter word commands by default, you can use
the asterisk option so you don't have to include every command one by one in the list.
As an example, this will enable all four-letter word commands:`},{heading:"cluster-options",content:`tcpKeepAlive :
(Java system property: zookeeper.tcpKeepAlive)
New in 3.5.4:
Setting this to true sets the TCP keepAlive flag on the
sockets used by quorum members to perform elections.
This will allow for connections between quorum members to
remain up when there is network infrastructure that may
otherwise break them. Some NATs and firewalls may terminate
or lose state for long-running or idle connections.
Enabling this option relies on OS level settings to work
properly, check your operating system's options regarding TCP
keepalive for more information. Defaults to
false.`},{heading:"cluster-options",content:`clientTcpKeepAlive :
(Java system property: zookeeper.clientTcpKeepAlive)
New in 3.6.1:
Setting this to true sets the TCP keepAlive flag on the
client sockets. Some broken network infrastructure may lose
the FIN packet that is sent from closing client. These never
closed client sockets cause OS resource leak. Enabling this
option terminates these zombie sockets by idle check.
Enabling this option relies on OS level settings to work
properly, check your operating system's options regarding TCP
keepalive for more information. Defaults to false. Please
note the distinction between it and tcpKeepAlive. It is
applied for the client sockets while tcpKeepAlive is for
the sockets used by quorum members. Currently this option is
only available when default NIOServerCnxnFactory is used.`},{heading:"cluster-options",content:`electionPortBindRetry :
(Java system property only: zookeeper.electionPortBindRetry)
Property set max retry count when Zookeeper server fails to bind
leader election port. Such errors can be temporary and recoverable,
such as DNS issue described in ZOOKEEPER-3320,
or non-retryable, such as port already in use.
In case of transient errors, this property can improve availability
of Zookeeper server and help it to self recover.
Default value 3. In container environment, especially in Kubernetes,
this value should be increased or set to 0(infinite retry) to overcome issues
related to DNS name resolving.`},{heading:"cluster-options",content:`observer.reconnectDelayMs :
(Java system property: zookeeper.observer.reconnectDelayMs)
When observer loses its connection with the leader, it waits for the
specified value before trying to reconnect with the leader so that
the entire observer fleet won't try to run leader election and reconnect
to the leader at once.
Defaults to 0 ms.`},{heading:"cluster-options",content:`observer.election.DelayMs :
(Java system property: zookeeper.observer.election.DelayMs)
Delay the observer's participation in a leader election upon disconnect
so as to prevent unexpected additional load on the voting peers during
the process. Defaults to 200 ms.`},{heading:"cluster-options",content:`localSessionsEnabled and localSessionsUpgradingEnabled :
New in 3.5:
Optional value is true or false. Their default values are false.
Turning on the local session feature by setting localSessionsEnabled=true. Turning on
localSessionsUpgradingEnabled can upgrade a local session to a global session automatically as required (e.g. creating ephemeral nodes),
which only matters when localSessionsEnabled is enabled.`},{heading:"encryption-authentication-authorization-options",content:`The options in this section allow control over
encryption/authentication/authorization performed by the service.`},{heading:"encryption-authentication-authorization-options",content:`Beside this page, you can also find useful information about client side configuration in the
Programmers Guide.
The ZooKeeper Wiki also has useful pages about ZooKeeper SSL support,
and SASL authentication for ZooKeeper.`},{heading:"encryption-authentication-authorization-options",content:`DigestAuthenticationProvider.enabled :
(Java system property: zookeeper.DigestAuthenticationProvider.enabled)
New in 3.7:
Determines whether the digest authentication provider is
enabled. The default value is true for backwards
compatibility, but it may be a good idea to disable this provider
if not used, as it can result in misleading entries appearing in
audit logs
(see ZOOKEEPER-3979)`},{heading:"encryption-authentication-authorization-options",content:`DigestAuthenticationProvider.superDigest :
(Java system property: zookeeper.DigestAuthenticationProvider.superDigest)
By default this feature is disabled
New in 3.2:
Enables a ZooKeeper ensemble administrator to access the
znode hierarchy as a "super" user. In particular no ACL
checking occurs for a user authenticated as
super.
org.apache.zookeeper.server.auth.DigestAuthenticationProvider
can be used to generate the superDigest, call it with
one parameter of "super:<password>". Provide the
generated "super:<data>" as the system property value
when starting each server of the ensemble.
When authenticating to a ZooKeeper server (from a
ZooKeeper client) pass a scheme of "digest" and authdata
of "super:<password>". Note that digest auth passes
the authdata in plaintext to the server, it would be
prudent to use this authentication method only on
localhost (not over the network) or over an encrypted
connection.`},{heading:"encryption-authentication-authorization-options",content:`DigestAuthenticationProvider.digestAlg :
(Java system property: zookeeper.DigestAuthenticationProvider.digestAlg)
New in 3.7.0:
Set ACL digest algorithm. The default value is: SHA1 which will be deprecated in the future for security issues.`},{heading:"encryption-authentication-authorization-options",content:"Set this property the same value in all the servers."},{heading:"encryption-authentication-authorization-options",content:"How to support other more algorithms?"},{heading:"encryption-authentication-authorization-options",content:`Modify the java.security configuration file under $JAVA_HOME/jre/lib/security/java.security by specifying
security.provider.<n>=<provider class name>.`},{heading:"encryption-authentication-authorization-options",content:"For example:"},{heading:"encryption-authentication-authorization-options",content:"Copy the jar file to $JAVA_HOME/jre/lib/ext/."},{heading:"encryption-authentication-authorization-options",content:"For example:"},{heading:"encryption-authentication-authorization-options",content:"How to migrate from one digest algorithm to another?"},{heading:"encryption-authentication-authorization-options",content:"Regenerate superDigest when migrating to new algorithm."},{heading:"encryption-authentication-authorization-options",content:"Run SetAcl for a znode which already had a digest auth of old algorithm."},{heading:"encryption-authentication-authorization-options",content:`IPAuthenticationProvider.usexforwardedfor :
(Java system property: zookeeper.IPAuthenticationProvider.usexforwardedfor)
New in 3.9.3:
IPAuthenticationProvider uses the client IP address to authenticate the user. By
default it reads the Host HTTP header to detect client IP address. In some
proxy configurations the proxy server adds the X-Forwarded-For header to
the request in order to provide the IP address of the original client request.
By enabling usexforwardedfor ZooKeeper setting, X-Forwarded-For will be preferred
over the standard Host header.
Default value is false.`},{heading:"encryption-authentication-authorization-options",content:`X509AuthenticationProvider.superUser :
(Java system property: zookeeper.X509AuthenticationProvider.superUser)
The SSL-backed way to enable a ZooKeeper ensemble
administrator to access the znode hierarchy as a "super" user.
When this parameter is set to an X500 principal name, only an
authenticated client with that principal will be able to bypass
ACL checking and have full privileges to all znodes.`},{heading:"encryption-authentication-authorization-options",content:`zookeeper.superUser :
(Java system property: zookeeper.superUser)
Similar to zookeeper.X509AuthenticationProvider.superUser
but is generic for SASL based logins. It stores the name of
a user that can access the znode hierarchy as a "super" user.
You can specify multiple SASL super users using the
zookeeper.superUser.[suffix] notation, e.g.:
zookeeper.superUser.1=....`},{heading:"encryption-authentication-authorization-options",content:`ssl.authProvider :
(Java system property: zookeeper.ssl.authProvider)
Specifies a subclass of org.apache.zookeeper.auth.X509AuthenticationProvider
to use for secure client authentication. This is useful in
certificate key infrastructures that do not use JKS. It may be
necessary to extend javax.net.ssl.X509KeyManager and javax.net.ssl.X509TrustManager
to get the desired behavior from the SSL stack. To configure the
ZooKeeper server to use the custom provider for authentication,
choose a scheme name for the custom AuthenticationProvider and
set the property zookeeper.authProvider.[scheme] to the fully-qualified class name of the custom
implementation. This will load the provider into the ProviderRegistry.
Then set this property zookeeper.ssl.authProvider=[scheme] and that provider
will be used for secure authentication.`},{heading:"encryption-authentication-authorization-options",content:`zookeeper.ensembleAuthName :
(Java system property only: zookeeper.ensembleAuthName)
New in 3.6.0:
Specify a list of comma-separated valid names/aliases of an ensemble. A client
can provide the ensemble name it intends to connect as the credential for scheme "ensemble". The EnsembleAuthenticationProvider will check the credential against
the list of names/aliases of the ensemble that receives the connection request.
If the credential is not in the list, the connection request will be refused.
This prevents a client accidentally connecting to a wrong ensemble.`},{heading:"encryption-authentication-authorization-options",content:`sessionRequireClientSASLAuth :
(Java system property: zookeeper.sessionRequireClientSASLAuth)
New in 3.6.0:
When set to true, ZooKeeper server will only accept connections and requests from clients
that have authenticated with server via SASL. Clients that are not configured with SASL
authentication, or configured with SASL but failed authentication (i.e. with invalid credential)
will not be able to establish a session with server. A typed error code (-124) will be delivered
in such case, both Java and C client will close the session with server thereafter,
without further attempts on retrying to reconnect.`},{heading:"encryption-authentication-authorization-options",content:"This configuration is shorthand for enforce.auth.enabled=true and enforce.auth.scheme=sasl"},{heading:"encryption-authentication-authorization-options",content:`By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting sessionRequireClientSASLAuth to true.`},{heading:"encryption-authentication-authorization-options",content:`This feature overrules the zookeeper.allowSaslFailedClients option, so even if server is
configured to allow clients that fail SASL authentication to login, client will not be able to
establish a session with server if this feature is enabled.`},{heading:"encryption-authentication-authorization-options",content:`enforce.auth.enabled :
(Java system property : zookeeper.enforce.auth.enabled)
New in 3.7.0:
When set to true, ZooKeeper server will only accept connections and requests from clients
that have authenticated with server via configured auth scheme. Authentication schemes
can be configured using property enforce.auth.schemes. Clients that are not
configured with the any of the auth scheme configured at server or configured but failed authentication (i.e. with invalid credential)
will not be able to establish a session with server. A typed error code (-124) will be delivered
in such case, both Java and C client will close the session with server thereafter,
without further attempts on retrying to reconnect.`},{heading:"encryption-authentication-authorization-options",content:`By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting enforce.auth.enabled to true.`},{heading:"encryption-authentication-authorization-options",content:"When enforce.auth.enabled=true and enforce.auth.schemes=sasl then"},{heading:"encryption-authentication-authorization-options",content:`zookeeper.allowSaslFailedClients configuration
is overruled. So even if server is configured to allow clients that fail SASL
authentication to login, client will not be able to establish a session with
server if this feature is enabled with sasl as authentication scheme.`},{heading:"encryption-authentication-authorization-options",content:`enforce.auth.schemes :
(Java system property : zookeeper.enforce.auth.schemes)
New in 3.7.0:
Comma separated list of authentication schemes. Clients must be authenticated with at least one
authentication scheme before doing any zookeeper operations.
This property is used only when enforce.auth.enabled is to true.`},{heading:"encryption-authentication-authorization-options",content:`sslQuorum :
(Java system property: zookeeper.sslQuorum)
New in 3.5.5:
Enables encrypted quorum communication. Default is false. When enabling this feature, please also consider enabling leader.closeSocketAsync
and learner.closeSocketAsync to avoid issues associated with the potentially long socket closing time when shutting down an SSL connection.`},{heading:"encryption-authentication-authorization-options",content:`ssl.keyStore.location and ssl.keyStore.password and ssl.quorum.keyStore.location and ssl.quorum.keyStore.password :
(Java system properties: zookeeper.ssl.keyStore.location and zookeeper.ssl.keyStore.password and zookeeper.ssl.quorum.keyStore.location and zookeeper.ssl.quorum.keyStore.password)
New in 3.5.5:
Specifies the file path to a Java keystore containing the local
credentials to be used for client and quorum TLS connections, and the
password to unlock the file.`},{heading:"encryption-authentication-authorization-options",content:`ssl.keyStore.passwordPath and ssl.quorum.keyStore.passwordPath :
(Java system properties: zookeeper.ssl.keyStore.passwordPath and zookeeper.ssl.quorum.keyStore.passwordPath)
New in 3.8.0:
Specifies the file path that contains the keystore password. Reading the password from a file takes precedence over
the explicit password property.`},{heading:"encryption-authentication-authorization-options",content:`ssl.keyStore.type and ssl.quorum.keyStore.type :
(Java system properties: zookeeper.ssl.keyStore.type and zookeeper.ssl.quorum.keyStore.type)
New in 3.5.5:
Specifies the file format of client and quorum keystores. Values: JKS, PEM, PKCS12 or null (detect by filename).
Default: null.
New in 3.5.10, 3.6.3, 3.7.0:
The format BCFKS was added.`},{heading:"encryption-authentication-authorization-options",content:`ssl.trustStore.location and ssl.trustStore.password and ssl.quorum.trustStore.location and ssl.quorum.trustStore.password :
(Java system properties: zookeeper.ssl.trustStore.location and zookeeper.ssl.trustStore.password and zookeeper.ssl.quorum.trustStore.location and zookeeper.ssl.quorum.trustStore.password)
New in 3.5.5:
Specifies the file path to a Java truststore containing the remote
credentials to be used for client and quorum TLS connections, and the
password to unlock the file.`},{heading:"encryption-authentication-authorization-options",content:`ssl.trustStore.passwordPath and ssl.quorum.trustStore.passwordPath :
(Java system properties: zookeeper.ssl.trustStore.passwordPath and zookeeper.ssl.quorum.trustStore.passwordPath)
New in 3.8.0:
Specifies the file path that contains the truststore password. Reading the password from a file takes precedence over
the explicit password property.`},{heading:"encryption-authentication-authorization-options",content:`ssl.trustStore.type and ssl.quorum.trustStore.type :
(Java system properties: zookeeper.ssl.trustStore.type and zookeeper.ssl.quorum.trustStore.type)
New in 3.5.5:
Specifies the file format of client and quorum trustStores. Values: JKS, PEM, PKCS12 or null (detect by filename).
Default: null.
New in 3.5.10, 3.6.3, 3.7.0:
The format BCFKS was added.`},{heading:"encryption-authentication-authorization-options",content:`ssl.protocol and ssl.quorum.protocol :
(Java system properties: zookeeper.ssl.protocol and zookeeper.ssl.quorum.protocol)
New in 3.5.5:
Specifies to protocol to be used in client and quorum TLS negotiation.
Default: TLSv1.3 or TLSv1.2 depending on Java runtime version being used.`},{heading:"encryption-authentication-authorization-options",content:`ssl.enabledProtocols and ssl.quorum.enabledProtocols :
(Java system properties: zookeeper.ssl.enabledProtocols and zookeeper.ssl.quorum.enabledProtocols)
New in 3.5.5:
Specifies the enabled protocols in client and quorum TLS negotiation.
Default: TLSv1.3, TLSv1.2 if value of protocol property is TLSv1.3. TLSv1.2 if protocol is TLSv1.2.`},{heading:"encryption-authentication-authorization-options",content:`ssl.ciphersuites and ssl.quorum.ciphersuites :
(Java system properties: zookeeper.ssl.ciphersuites and zookeeper.ssl.quorum.ciphersuites)
New in 3.5.5:
Specifies the enabled cipher suites to be used in client and quorum TLS negotiation.
Default: JDK defaults since 3.10.0, and hard coded cipher suites for 3.9 and earlier versions. See TLS Cipher Suites.`},{heading:"encryption-authentication-authorization-options",content:`ssl.context.supplier.class and ssl.quorum.context.supplier.class :
(Java system properties: zookeeper.ssl.context.supplier.class and zookeeper.ssl.quorum.context.supplier.class)
New in 3.5.5:
Specifies the class to be used for creating SSL context in client and quorum SSL communication.
This allows you to use custom SSL context and implement the following scenarios: 1. Use hardware keystore, loaded in using PKCS11 or something similar. 2. You don't have access to the software keystore, but can retrieve an already-constructed SSLContext from their container.
Default: null`},{heading:"encryption-authentication-authorization-options",content:`ssl.hostnameVerification and ssl.quorum.hostnameVerification :
(Java system properties: zookeeper.ssl.hostnameVerification and zookeeper.ssl.quorum.hostnameVerification)
New in 3.5.5:
Specifies whether the hostname verification is enabled in client and quorum TLS negotiation process.
Disabling it only recommended for testing purposes.
Default: true`},{heading:"encryption-authentication-authorization-options",content:`ssl.clientHostnameVerification and ssl.quorum.clientHostnameVerification :
(Java system properties: zookeeper.ssl.clientHostnameVerification and zookeeper.ssl.quorum.clientHostnameVerification)
New in 3.9.4:
Specifies whether the client's hostname verification is enabled in client and quorum TLS negotiation process.
This option requires the corresponding hostnameVerification option to be true, or it will be ignored.
Default: true for quorum, false for clients`},{heading:"encryption-authentication-authorization-options",content:`ssl.allowReverseDnsLookup and ssl.quorum.allowReverseDnsLookup :
(Java system properties: zookeeper.ssl.allowReverseDnsLookup and zookeeper.ssl.quorum.allowReverseDnsLookup)
New in 3.9.5:
Allow reverse DNS lookup in both server- and client hostname verifications if the hostname verification is enabled in
ZKTrustManager. Supported in both quorum and client TLS protocols. Not supported in FIPS mode. Reverse DNS lookups are
expensive and unnecessary in most cases. Make sure that certificates are created with all required Subject Alternative
Names (SAN) for successful identity verification. It's recommended to add SAN:IP entries for identity verification
of client certificates.
Default: false`},{heading:"encryption-authentication-authorization-options",content:`ssl.crl and ssl.quorum.crl :
(Java system properties: zookeeper.ssl.crl and zookeeper.ssl.quorum.crl)
New in 3.5.5:
Specifies whether Certificate Revocation List is enabled in client and quorum TLS protocols.
Default: jvm property "com.sun.net.ssl.checkRevocation" since 3.10.0, false otherwise`},{heading:"encryption-authentication-authorization-options",content:`ssl.ocsp and ssl.quorum.ocsp :
(Java system properties: zookeeper.ssl.ocsp and zookeeper.ssl.quorum.ocsp)
New in 3.5.5:
Specifies whether Online Certificate Status Protocol is enabled in client and quorum TLS protocols.
Changed in 3.10.0:
Before 3.10.0, ssl.ocsp and ssl.quorum.ocsp implies ssl.crl and ssl.quorum.crl correspondingly.
After 3.10.0, one has to setup both ssl.crl and ssl.ocsp (or ssl.quorum.crl and ssl.quorum.ocsp)
to enable OCSP. This is consistent with jdk's method of Setting up a Java Client to use Client-Driven OCSP.
Default: jvm security property "ocsp.enable" since 3.10.0, false otherwise`},{heading:"encryption-authentication-authorization-options",content:`ssl.clientAuth and ssl.quorum.clientAuth :
(Java system properties: zookeeper.ssl.clientAuth and zookeeper.ssl.quorum.clientAuth)
Added in 3.5.5, but broken until 3.5.7:
Specifies options to authenticate ssl connections from clients. Valid values are`},{heading:"encryption-authentication-authorization-options",content:'"none": server will not request client authentication'},{heading:"encryption-authentication-authorization-options",content:'"want": server will "request" client authentication'},{heading:"encryption-authentication-authorization-options",content:'"need": server will "require" client authentication'},{heading:"encryption-authentication-authorization-options",content:'Default: "need"'},{heading:"encryption-authentication-authorization-options",content:`ssl.handshakeDetectionTimeoutMillis and ssl.quorum.handshakeDetectionTimeoutMillis :
(Java system properties: zookeeper.ssl.handshakeDetectionTimeoutMillis and zookeeper.ssl.quorum.handshakeDetectionTimeoutMillis)
New in 3.5.5:
TBD`},{heading:"encryption-authentication-authorization-options",content:`ssl.sslProvider :
(Java system property: zookeeper.ssl.sslProvider)
New in 3.9.0:
Allows to select SSL provider in the client-server communication when TLS is enabled. Netty-tcnative native library
has been added to ZooKeeper in version 3.9.0 which allows us to use native SSL libraries like OpenSSL on supported
platforms. See the available options in Netty-tcnative documentation. Default value is "JDK".`},{heading:"encryption-authentication-authorization-options",content:`sslQuorumReloadCertFiles :
(No Java system property)
New in 3.5.5, 3.6.0:
Allows Quorum SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false`},{heading:"encryption-authentication-authorization-options",content:`client.certReload :
(Java system property: zookeeper.client.certReload)
New in 3.7.2, 3.8.1, 3.9.0:
Allows client SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false`},{heading:"encryption-authentication-authorization-options",content:`client.portUnification:
(Java system property: zookeeper.client.portUnification)
Specifies that the client port should accept SSL connections
(using the same configuration as the secure client port).
Default: false`},{heading:"encryption-authentication-authorization-options",content:`authProvider:
(Java system property: zookeeper.authProvider)
You can specify multiple authentication provider classes for ZooKeeper.
Usually you use this parameter to specify the SASL authentication provider
like: authProvider.1=org.apache.zookeeper.server.auth.SASLAuthenticationProvider`},{heading:"encryption-authentication-authorization-options",content:`kerberos.removeHostFromPrincipal
(Java system property: zookeeper.kerberos.removeHostFromPrincipal)
You can instruct ZooKeeper to remove the host from the client principal name during authentication.
(e.g. the zk/myhost@EXAMPLE.COM client principal will be authenticated in ZooKeeper as zk@EXAMPLE.COM)
Default: false`},{heading:"encryption-authentication-authorization-options",content:`kerberos.removeRealmFromPrincipal
(Java system property: zookeeper.kerberos.removeRealmFromPrincipal)
You can instruct ZooKeeper to remove the realm from the client principal name during authentication.
(e.g. the zk/myhost@EXAMPLE.COM client principal will be authenticated in ZooKeeper as zk/myhost)
Default: false`},{heading:"encryption-authentication-authorization-options",content:`kerberos.canonicalizeHostNames
(Java system property: zookeeper.kerberos.canonicalizeHostNames)
New in 3.7.0:
Instructs ZooKeeper to canonicalize server host names extracted from server.x lines.
This allows using e.g. CNAME records to reference servers in configuration files, while still enabling SASL Kerberos authentication between quorum members.
It is essentially the quorum equivalent of the zookeeper.sasl.client.canonicalize.hostname property for clients.
The default value is false for backwards compatibility.`},{heading:"encryption-authentication-authorization-options",content:`multiAddress.enabled :
(Java system property: zookeeper.multiAddress.enabled)
New in 3.6.0:
Since ZooKeeper 3.6.0 you can also specify multiple addresses
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). Setting this parameter to
true will enable this feature. Please note, that you can not enable this feature
during a rolling upgrade if the version of the old ZooKeeper cluster is prior to 3.6.0.
The default value is false.`},{heading:"encryption-authentication-authorization-options",content:`multiAddress.reachabilityCheckTimeoutMs :
(Java system property: zookeeper.multiAddress.reachabilityCheckTimeoutMs)
New in 3.6.0:
Since ZooKeeper 3.6.0 you can also specify multiple addresses
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
the reachable addresses. This happens only if you provide multiple addresses in the configuration.
In this property you can set the timeout in milliseconds for the reachability check. The check happens
in parallel for the different addresses, so the timeout you set here is the maximum time will be taken
by checking the reachability of all addresses.
The default value is 1000.`},{heading:"encryption-authentication-authorization-options",content:"This parameter has no effect, unless you enable the MultiAddress feature by setting multiAddress.enabled=true."},{heading:"encryption-authentication-authorization-options",content:`fips-mode :
(Java system property: zookeeper.fips-mode)
New in 3.8.2:
Enable FIPS compatibility mode in ZooKeeper. If enabled, the following things will be changed in order to comply
with FIPS requirements:`},{heading:"encryption-authentication-authorization-options",content:`Custom trust manager (ZKTrustManager) that is used for hostname verification will be disabled. As a consequence,
hostname verification is not available in the Quorum protocol, but still can be set in client-server communication.`},{heading:"encryption-authentication-authorization-options",content:`DIGEST-MD5 Sasl auth mechanism will be disabled in Quorum and ZooKeeper Sasl clients. Only GSSAPI (Kerberos)
can be used.`},{heading:"encryption-authentication-authorization-options",content:"Default: true (3.9.0+), false (3.8.x)"},{heading:"tls-cipher-suites",content:`From 3.5.5 to 3.9 a hard coded default cipher list was used, with the ordering
dependent on whether it is run Java 8 or a later version.`},{heading:"tls-cipher-suites",content:"The list on Java 8 includes TLSv1.2 CBC, GCM and TLSv1.3 ciphers in ordering: TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384, TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256"},{heading:"tls-cipher-suites",content:"The list on Java 9+ includes TLSv1.2 GCM, CBC and TLSv1.3 ciphers in ordering: TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA, TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256"},{heading:"tls-cipher-suites",content:"Since 3.10 there is no hardcoded list, and the JDK defaults are used."},{heading:"experimental-optionsfeatures",content:"New features that are currently considered experimental."},{heading:"experimental-optionsfeatures",content:`Read Only Mode Server :
(Java system property: readonlymode.enabled)
New in 3.4.0:
Setting this value to true enables Read Only Mode server
support (disabled by default).
localSessionsEnabled has to be activated to serve clients.
A downgrade of an existing connections is currently not supported.
ROM allows clients sessions which requested ROM support to connect to the
server even when the server might be partitioned from
the quorum. In this mode ROM clients can still read
values from the ZK service, but will be unable to write
values and see changes from other clients. See
ZOOKEEPER-784 for more details.`},{heading:"experimental-optionsfeatures",content:`zookeeper.follower.skipLearnerRequestToNextProcessor :
(Java system property: zookeeper.follower.skipLearnerRequestToNextProcessor)
When our cluster has observers which are connected with ObserverMaster, then turning on this flag might help
you reduce some memory pressure on the Observer Master. If your cluster doesn't have any observers or
they are not connected with ObserverMaster or your Observer's don't make much writes, then using this flag
won't help you.
Currently the change here is guarded behind the flag to help us get more confidence around the memory gains.
In Long run, we might want to remove this flag and set its behavior as the default codepath.`},{heading:"unsafe-options",content:`The following options can be useful, but be careful when you use
them. The risk of each is explained along with the explanation of what
the variable does.`},{heading:"unsafe-options",content:`forceSync :
(Java system property: zookeeper.forceSync)
Requires updates to be synced to media of the transaction
log before finishing processing the update. If this option is
set to no, ZooKeeper will not require updates to be synced to
the media.`},{heading:"unsafe-options",content:`jute.maxbuffer :
(Java system property:jute.maxbuffer).`},{heading:"unsafe-options",content:`This option can only be set as a Java system property.
There is no zookeeper prefix on it. It specifies the maximum
size of the data that can be stored in a znode. The unit is: byte. The default is
0xfffff(1048575) bytes, or just under 1M.`},{heading:"unsafe-options",content:`If this option is changed, the system property must be set on all servers and clients otherwise
problems will arise.`},{heading:"unsafe-options",content:`When jute.maxbuffer in the client side is greater than the server side, the client wants to write the data
exceeds jute.maxbuffer in the server side, the server side will get java.io.IOException: Len error`},{heading:"unsafe-options",content:`When jute.maxbuffer in the client side is less than the server side, the client wants to read the data
exceeds jute.maxbuffer in the client side, the client side will get java.io.IOException: Unreasonable length
or Packet len is out of range!`},{heading:"unsafe-options",content:`This is really a sanity check. ZooKeeper is designed to store data on the order of kilobytes in size.
In the production environment, increasing this property to exceed the default value is not recommended for the following reasons:`},{heading:"unsafe-options",content:"Large size znodes cause unwarranted latency spikes, worsen the throughput"},{heading:"unsafe-options",content:"Large size znodes make the synchronization time between leader and followers unpredictable and non-convergent(sometimes timeout), cause the quorum unstable"},{heading:"unsafe-options",content:`jute.maxbuffer.extrasize:
(Java system property: zookeeper.jute.maxbuffer.extrasize)
New in 3.5.7:
While processing client requests ZooKeeper server adds some additional information into
the requests before persisting it as a transaction. Earlier this additional information size
was fixed to 1024 bytes. For many scenarios, specially scenarios where jute.maxbuffer value
is more than 1 MB and request type is multi, this fixed size was insufficient.
To handle all the scenarios additional information size is increased from 1024 byte
to same as jute.maxbuffer size and also it is made configurable through jute.maxbuffer.extrasize.
Generally this property is not required to be configured as default value is the most optimal value.`},{heading:"unsafe-options",content:`skipACL :
(Java system property: zookeeper.skipACL)
Skips ACL checks. This results in a boost in throughput,
but opens up full access to the data tree to everyone.`},{heading:"unsafe-options",content:`quorumListenOnAllIPs :
When set to true the ZooKeeper server will listen
for connections from its peers on all available IP addresses,
and not only the address configured in the server list of the
configuration file. It affects the connections handling the
ZAB protocol and the Fast Leader Election protocol. Default
value is false.`},{heading:"unsafe-options",content:`multiAddress.reachabilityCheckEnabled :
(Java system property: zookeeper.multiAddress.reachabilityCheckEnabled)
New in 3.6.0:
Since ZooKeeper 3.6.0 you can also specify multiple addresses
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
the reachable addresses. This happens only if you provide multiple addresses in the configuration.
The reachable check can fail if you hit some ICMP rate-limitation, (e.g. on macOS) when you try to
start a large (e.g. 11+) ensemble members cluster on a single machine for testing.`},{heading:"unsafe-options",content:`Default value is true. By setting this parameter to 'false' you can disable the reachability checks.
Please note, disabling the reachability check will cause the cluster not to be able to reconfigure
itself properly during network problems, so the disabling is advised only during testing.`},{heading:"unsafe-options",content:"This parameter has no effect, unless you enable the MultiAddress feature by setting multiAddress.enabled=true."},{heading:"disabling-data-directory-autocreation",content:`New in 3.5: The default
behavior of a ZooKeeper server is to automatically create the
data directory (specified in the configuration file) when
started if that directory does not already exist. This can be
inconvenient and even dangerous in some cases. Take the case
where a configuration change is made to a running server,
wherein the dataDir parameter
is accidentally changed. When the ZooKeeper server is
restarted it will create this non-existent directory and begin
serving - with an empty znode namespace. This scenario can
result in an effective "split brain" situation (i.e. data in
both the new invalid directory and the original valid data
store). As such is would be good to have an option to turn off
this autocreate behavior. In general for production
environments this should be done, unfortunately however the
default legacy behavior cannot be changed at this point and
therefore this must be done on a case by case basis. This is
left to users and to packagers of ZooKeeper distributions.`},{heading:"disabling-data-directory-autocreation",content:`When running zkServer.sh autocreate can be disabled
by setting the environment variable ZOO_DATADIR_AUTOCREATE_DISABLE to 1.
When running ZooKeeper servers directly from class files this
can be accomplished by setting zookeeper.datadir.autocreate=false on
the java command line, i.e. -Dzookeeper.datadir.autocreate=false`},{heading:"disabling-data-directory-autocreation",content:`When this feature is disabled, and the ZooKeeper server
determines that the required directories do not exist it will
generate an error and refuse to start.`},{heading:"disabling-data-directory-autocreation",content:`A new script zkServer-initialize.sh is provided to
support this new feature. If autocreate is disabled it is
necessary for the user to first install ZooKeeper, then create
the data directory (and potentially txnlog directory), and
then start the server. Otherwise as mentioned in the previous
paragraph the server will not start. Running zkServer-initialize.sh will create the
required directories, and optionally set up the myid file
(optional command line parameter). This script can be used
even if the autocreate feature itself is not used, and will
likely be of use to users as this (setup, including creation
of the myid file) has been an issue for users in the past.
Note that this script ensures the data directories exist only,
it does not create a config file, but rather requires a config
file to be available in order to execute.`},{heading:"enabling-db-existence-validation",content:`New in 3.6.0: The default
behavior of a ZooKeeper server on startup when no data tree
is found is to set zxid to zero and join the quorum as a
voting member. This can be dangerous if some event (e.g. a
rogue 'rm -rf') has removed the data directory while the
server was down since this server may help elect a leader
that is missing transactions. Enabling db existence validation
will change the behavior on startup when no data tree is
found: the server joins the ensemble as a non-voting participant
until it is able to sync with the leader and acquire an up-to-date
version of the ensemble data. To indicate an empty data tree is
expected (ensemble creation), the user should place a file
'initialize' in the same directory as 'myid'. This file will
be detected and deleted by the server on startup.`},{heading:"enabling-db-existence-validation",content:`Initialization validation can be enabled when running
ZooKeeper servers directly from class files by setting
zookeeper.db.autocreate=false
on the java command line, i.e.
-Dzookeeper.db.autocreate=false.
Running zkServer-initialize.sh
will create the required initialization file.`},{heading:"performance-tuning-options",content:`New in 3.5.0: Several subsystems have been reworked
to improve read throughput. This includes multi-threading of the NIO communication subsystem and
request processing pipeline (Commit Processor). NIO is the default client/server communication
subsystem. Its threading model comprises 1 acceptor thread, 1-N selector threads and 0-M
socket I/O worker threads. In the request processing pipeline the system can be configured
to process multiple read request at once while maintaining the same consistency guarantee
(same-session read-after-write). The Commit Processor threading model comprises 1 main
thread and 0-N worker threads.`},{heading:"performance-tuning-options",content:`The default values are aimed at maximizing read throughput on a dedicated ZooKeeper machine.
Both subsystems need to have sufficient amount of threads to achieve peak read throughput.`},{heading:"performance-tuning-options",content:`zookeeper.nio.numSelectorThreads :
(Java system property only: zookeeper.nio.numSelectorThreads)
New in 3.5.0:
Number of NIO selector threads. At least 1 selector thread required.
It is recommended to use more than one selector for large numbers
of client connections. The default value is sqrt( number of cpu cores / 2 ).`},{heading:"performance-tuning-options",content:`zookeeper.nio.numWorkerThreads :
(Java system property only: zookeeper.nio.numWorkerThreads)
New in 3.5.0:
Number of NIO worker threads. If configured with 0 worker threads, the selector threads
do the socket I/O directly. The default value is 2 times the number of cpu cores.`},{heading:"performance-tuning-options",content:`zookeeper.commitProcessor.numWorkerThreads :
(Java system property only: zookeeper.commitProcessor.numWorkerThreads)
New in 3.5.0:
Number of Commit Processor worker threads. If configured with 0 worker threads, the main thread
will process the request directly. The default value is the number of cpu cores.`},{heading:"performance-tuning-options",content:`zookeeper.commitProcessor.maxReadBatchSize :
(Java system property only: zookeeper.commitProcessor.maxReadBatchSize)
Max number of reads to process from queuedRequests before switching to processing commits.
If the value < 0 (default), we switch whenever we have a local write, and pending commits.
A high read batch size will delay commit processing, causing stale data to be served.
If reads are known to arrive in fixed size batches then matching that batch size with
the value of this property can smooth queue performance. Since reads are handled in parallel,
one recommendation is to set this property to match zookeeper.commitProcessor.numWorkerThread
(default is the number of cpu cores) or lower.`},{heading:"performance-tuning-options",content:`zookeeper.commitProcessor.maxCommitBatchSize :
(Java system property only: zookeeper.commitProcessor.maxCommitBatchSize)
Max number of commits to process before processing reads. We will try to process as many
remote/local commits as we can till we reach this count. A high commit batch size will delay
reads while processing more commits. A low commit batch size will favor reads.
It is recommended to only set this property when an ensemble is serving a workload with a high
commit rate. If writes are known to arrive in a set number of batches then matching that
batch size with the value of this property can smooth queue performance. A generic
approach would be to set this value to equal the ensemble size so that with the processing
of each batch the current server will probabilistically handle a write related to one of
its direct clients.
Default is "1". Negative and zero values are not supported.`},{heading:"performance-tuning-options",content:`znode.container.checkIntervalMs :
(Java system property only)
New in 3.6.0: The
time interval in milliseconds for each check of candidate container
and ttl nodes. Default is "60000".`},{heading:"performance-tuning-options",content:`znode.container.maxPerMinute :
(Java system property only)
New in 3.6.0: The
maximum number of container and ttl nodes that can be deleted per
minute. This prevents herding during container deletion.
Default is "10000".`},{heading:"performance-tuning-options",content:`znode.container.maxNeverUsedIntervalMs :
(Java system property only)
New in 3.6.0: The
maximum interval in milliseconds that a container that has never had
any children is retained. Should be long enough for your client to
create the container, do any needed work and then create children.
Default is "300000"(a.k.a. 5 minutes) since 3.10.0, for earlier versions,
it is "0" which is used to indicate that containers that have never had
any children are never deleted.`},{heading:"debug-observability-configurations",content:"New in 3.6.0: The following options are introduced to make zookeeper easier to debug."},{heading:"debug-observability-configurations",content:`zookeeper.messageTracker.BufferSize :
(Java system property only)
Controls the maximum number of messages stored in MessageTracker. Value should be positive
integers. The default value is 10. MessageTracker is introduced in 3.6.0 to record the
last set of messages between a server (follower or observer) and a leader, when a server
disconnects with leader. These set of messages will then be dumped to zookeeper's log file,
and will help reconstruct the state of the servers at the time of the disconnection and
will be useful for debugging purpose.`},{heading:"debug-observability-configurations",content:`zookeeper.messageTracker.Enabled :
(Java system property only)
When set to "true", will enable MessageTracker to track and record messages. Default value
is "false".`},{heading:"adminserver-configuration",content:"New in 3.10.0: The AdminServer will use the following existing properties:"},{heading:"adminserver-configuration",content:`ssl.quorum.ciphersuites :
(Java system property: zookeeper.ssl.quorum.ciphersuites)
The enabled cipher suites to be used in TLS negotiation for AdminServer.
Default: Jetty default.`},{heading:"adminserver-configuration",content:`ssl.quorum.enabledProtocols :
(Java system property: zookeeper.ssl.quorum.enabledProtocols)
The enabled protocols to be used in TLS negotiation for AdminServer.
Default: Jetty default.`},{heading:"adminserver-configuration",content:`New in 3.9.0: The following
options are used to configure the AdminServer.`},{heading:"adminserver-configuration",content:`admin.rateLimiterIntervalInMS :
(Java system property: zookeeper.admin.rateLimiterIntervalInMS)
The time interval for rate limiting admin command to protect the server.
Defaults to 5 mins.`},{heading:"adminserver-configuration",content:`admin.snapshot.enabled :
(Java system property: zookeeper.admin.snapshot.enabled)
The flag for enabling the snapshot command. Defaults to true.`},{heading:"adminserver-configuration",content:`admin.restore.enabled :
(Java system property: zookeeper.admin.restore.enabled)
The flag for enabling the restore command. Defaults to true.`},{heading:"adminserver-configuration",content:`admin.needClientAuth :
(Java system property: zookeeper.admin.needClientAuth)
The flag to control whether client auth is needed. Using x509 auth requires true.
Defaults to false.`},{heading:"adminserver-configuration",content:`New in 3.7.1: The following
options are used to configure the AdminServer.`},{heading:"adminserver-configuration",content:`admin.forceHttps :
(Java system property: zookeeper.admin.forceHttps)
Force AdminServer to use SSL, thus allowing only HTTPS traffic.
Defaults to disabled.
Overwrites admin.portUnification settings.`},{heading:"adminserver-configuration",content:`New in 3.6.0: The following
options are used to configure the AdminServer.`},{heading:"adminserver-configuration",content:`admin.portUnification :
(Java system property: zookeeper.admin.portUnification)
Enable the admin port to accept both HTTP and HTTPS traffic.
Defaults to disabled.`},{heading:"adminserver-configuration",content:`New in 3.5.0: The following
options are used to configure the AdminServer.`},{heading:"adminserver-configuration",content:`admin.enableServer :
(Java system property: zookeeper.admin.enableServer)
Set to "false" to disable the AdminServer. By default the
AdminServer is enabled.`},{heading:"adminserver-configuration",content:`admin.serverAddress :
(Java system property: zookeeper.admin.serverAddress)
The address the embedded Jetty server listens on. Defaults to 0.0.0.0.`},{heading:"adminserver-configuration",content:`admin.serverPort :
(Java system property: zookeeper.admin.serverPort)
The port the embedded Jetty server listens on. Defaults to 8080.`},{heading:"adminserver-configuration",content:`admin.idleTimeout :
(Java system property: zookeeper.admin.idleTimeout)
Set the maximum idle time in milliseconds that a connection can wait
before sending or receiving data. Defaults to 30000 ms.`},{heading:"adminserver-configuration",content:`admin.commandURL :
(Java system property: zookeeper.admin.commandURL)
The URL for listing and issuing commands relative to the
root URL. Defaults to "/commands".`}],headings:[{id:"minimum-configuration",content:"Minimum Configuration"},{id:"advanced-configuration",content:"Advanced Configuration"},{id:"cluster-options",content:"Cluster Options"},{id:"encryption-authentication-authorization-options",content:"Encryption, Authentication, Authorization Options"},{id:"tls-cipher-suites",content:"TLS Cipher Suites"},{id:"experimental-optionsfeatures",content:"Experimental Options/Features"},{id:"unsafe-options",content:"Unsafe Options"},{id:"disabling-data-directory-autocreation",content:"Disabling data directory autocreation"},{id:"enabling-db-existence-validation",content:"Enabling db existence validation"},{id:"performance-tuning-options",content:"Performance Tuning Options"},{id:"debug-observability-configurations",content:"Debug Observability Configurations"},{id:"adminserver-configuration",content:"AdminServer configuration"}]};const d=[{depth:2,url:"#minimum-configuration",title:e.jsx(e.Fragment,{children:"Minimum Configuration"})},{depth:2,url:"#advanced-configuration",title:e.jsx(e.Fragment,{children:"Advanced Configuration"})},{depth:2,url:"#cluster-options",title:e.jsx(e.Fragment,{children:"Cluster Options"})},{depth:2,url:"#encryption-authentication-authorization-options",title:e.jsx(e.Fragment,{children:"Encryption, Authentication, Authorization Options"})},{depth:2,url:"#tls-cipher-suites",title:e.jsx(e.Fragment,{children:"TLS Cipher Suites"})},{depth:2,url:"#experimental-optionsfeatures",title:e.jsx(e.Fragment,{children:"Experimental Options/Features"})},{depth:2,url:"#unsafe-options",title:e.jsx(e.Fragment,{children:"Unsafe Options"})},{depth:2,url:"#disabling-data-directory-autocreation",title:e.jsx(e.Fragment,{children:"Disabling data directory autocreation"})},{depth:2,url:"#enabling-db-existence-validation",title:e.jsx(e.Fragment,{children:"Enabling db existence validation"})},{depth:2,url:"#performance-tuning-options",title:e.jsx(e.Fragment,{children:"Performance Tuning Options"})},{depth:2,url:"#debug-observability-configurations",title:e.jsx(e.Fragment,{children:"Debug Observability Configurations"})},{depth:2,url:"#adminserver-configuration",title:e.jsx(e.Fragment,{children:"AdminServer configuration"})}];function s(o){const n={a:"a",code:"code",em:"em",h2:"h2",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...o.components},{Callout:t}=n;return t||i("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`ZooKeeper's behavior is governed by the ZooKeeper configuration
file. This file is designed so that the exact same file can be used by
all the servers that make up a ZooKeeper server assuming the disk
layouts are the same. If servers use different configuration files, care
must be taken to ensure that the list of servers in all of the different
configuration files match.`}),`
`,e.jsx(t,{type:"info",children:e.jsxs(n.p,{children:[`In 3.5.0 and later, some of these parameters should be placed in a dynamic
configuration file. If they are placed in the static configuration file,
ZooKeeper will automatically move them over to the dynamic configuration file.
See `,e.jsx(n.a,{href:"/admin-ops/dynamic-reconfiguration",children:"Dynamic Reconfiguration"}),` for more
information.`]})}),`
`,e.jsx(n.h2,{id:"minimum-configuration",children:"Minimum Configuration"}),`
`,e.jsx(n.p,{children:`Here are the minimum configuration keywords that must be defined
in the configuration file:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"clientPort"}),` :
the port to listen for client connections; that is, the
port that clients attempt to connect to.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"secureClientPort"}),` :
the port to listen on for secure client connections using SSL.
`,e.jsx(n.strong,{children:"clientPort"}),` specifies
the port for plaintext connections while `,e.jsx(n.strong,{children:"secureClientPort"}),` specifies the port for SSL
connections. Specifying both enables mixed-mode while omitting
either will disable that mode.
Note that SSL feature will be enabled when user plugs-in
zookeeper.serverCnxnFactory, zookeeper.clientCnxnSocket as Netty.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"observerMasterPort"}),` :
the port to listen for observer connections; that is, the
port that observers attempt to connect to.
if the property is set then the server will host observer connections
when in follower mode in addition to when in leader mode and correspondingly
attempt to connect to any voting peer when in observer mode.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dataDir"}),` :
the location where ZooKeeper will store the in-memory
database snapshots and, unless specified otherwise, the
transaction log of updates to the database.`]}),`
`,e.jsx(t,{type:"info",children:e.jsx(n.p,{children:`Be careful where you put the transaction log. A dedicated transaction log
device is key to consistent good performance. Putting the log on a busy
device will adversely affect performance.`})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"tickTime"}),` :
the length of a single tick, which is the basic time unit
used by ZooKeeper, as measured in milliseconds. It is used to
regulate heartbeats, and timeouts. For example, the minimum
session timeout will be two ticks.`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"advanced-configuration",children:"Advanced Configuration"}),`
`,e.jsxs(n.p,{children:[`The configuration settings in the section are optional. You can
use them to further fine tune the behaviour of your ZooKeeper servers.
Some can also be set using Java system properties, generally of the
form `,e.jsx(n.em,{children:"zookeeper.keyword"}),`. The exact system
property, when available, is noted below.`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dataLogDir"}),` :
(No Java system property)
This option will direct the machine to write the
transaction log to the `,e.jsx(n.strong,{children:"dataLogDir"})," rather than the ",e.jsx(n.strong,{children:"dataDir"}),`. This allows a dedicated log
device to be used, and helps avoid competition between logging
and snapshots.`]}),`
`,e.jsx(t,{type:"info",children:e.jsxs(n.p,{children:[`Having a dedicated log device has a large impact on throughput and stable
latencies. It is highly recommended dedicating a log device and set
`,e.jsx(n.strong,{children:"dataLogDir"}),` to point to a directory on that device, and then make sure to
point `,e.jsx(n.strong,{children:"dataDir"})," to a directory ",e.jsx(n.em,{children:"not"})," residing on that device."]})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"globalOutstandingLimit"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.globalOutstandingLimit."}),`)
Clients can submit requests faster than ZooKeeper can
process them, especially if there are a lot of clients. To
prevent ZooKeeper from running out of memory due to queued
requests, ZooKeeper will throttle clients so that there are no
more than globalOutstandingLimit outstanding requests across
entire ensemble, equally divided. The default limit is 1,000
and, for example, with 3 members each of them will have
1000 / 2 = 500 individual limit.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"preAllocSize"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.preAllocSize"}),`)
To avoid seeks ZooKeeper allocates space in the
transaction log file in blocks of preAllocSize kilobytes. The
default block size is 64M. One reason for changing the size of
the blocks is to reduce the block size if snapshots are taken
more often. (Also, see `,e.jsx(n.strong,{children:"snapCount"})," and ",e.jsx(n.strong,{children:"snapSizeLimitInKb"}),")."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"snapCount"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.snapCount"}),`)
ZooKeeper records its transactions using snapshots and
a transaction log (think write-ahead log). The number of
transactions recorded in the transaction log before a snapshot
can be taken (and the transaction log rolled) is determined
by snapCount. In order to prevent all of the machines in the quorum
from taking a snapshot at the same time, each ZooKeeper server
will take a snapshot when the number of transactions in the transaction log
reaches a runtime generated random value in the [snapCount/2+1, snapCount]
range. The default snapCount is 100,000.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"commitLogCount"}),` * :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.commitLogCount"}),`)
Zookeeper maintains an in-memory list of last committed requests for fast synchronization with
followers when the followers are not too behind. This improves sync performance in case when your
snapshots are large (>100,000). The default value is 500 which is the recommended minimum.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"snapSizeLimitInKb"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.snapSizeLimitInKb"}),`)
ZooKeeper records its transactions using snapshots and
a transaction log (think write-ahead log). The total size in bytes allowed
in the set of transactions recorded in the transaction log before a snapshot
can be taken (and the transaction log rolled) is determined
by snapSize. In order to prevent all of the machines in the quorum
from taking a snapshot at the same time, each ZooKeeper server
will take a snapshot when the size in bytes of the set of transactions in the
transaction log reaches a runtime generated random value in the [snapSize/2+1, snapSize]
range. Each file system has a minimum standard file size and in order
to for valid functioning of this feature, the number chosen must be larger
than that value. The default snapSizeLimitInKb is 4,194,304 (4GB).
A non-positive value will disable the feature.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"txnLogSizeLimitInKb"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.txnLogSizeLimitInKb"}),`)
Zookeeper transaction log file can also be controlled more
directly using txnLogSizeLimitInKb. Larger txn logs can lead to
slower follower syncs when sync is done using transaction log.
This is because leader has to scan through the appropriate log
file on disk to find the transaction to start sync from.
This feature is turned off by default and snapCount and snapSizeLimitInKb are the
only values that limit transaction log size. When enabled
Zookeeper will roll the log when any of the limits is hit.
Please note that actual log size can exceed this value by the size
of the serialized transaction. On the other hand, if this value is
set too close to (or smaller than) `,e.jsx(n.strong,{children:"preAllocSize"}),`,
it can cause Zookeeper to roll the log for every transaction. While
this is not a correctness issue, this may cause severely degraded
performance. To avoid this and to get most out of this feature, it is
recommended to set the value to N * `,e.jsx(n.strong,{children:"preAllocSize"}),`
where N >= 2.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxCnxns"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.maxCnxns"}),`)
Limits the total number of concurrent connections that can be made to a
zookeeper server (per client Port of each server ). This is used to prevent certain
classes of DoS attacks. The default is 0 and setting it to 0 entirely removes
the limit on total number of concurrent connections. Accounting for the
number of connections for serverCnxnFactory and a secureServerCnxnFactory is done
separately, so a peer is allowed to host up to 2*maxCnxns provided they are of appropriate types.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxClientCnxns"}),` :
(No Java system property)
Limits the number of concurrent connections (at the socket
level) that a single client, identified by IP address, may make
to a single member of the ZooKeeper ensemble. This is used to
prevent certain classes of DoS attacks, including file
descriptor exhaustion. The default is 60. Setting this to 0
entirely removes the limit on concurrent connections.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"clientPortAddress"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` the
address (ipv4, ipv6 or hostname) to listen for client
connections; that is, the address that clients attempt
to connect to. This is optional, by default we bind in
such a way that any connection to the `,e.jsx(n.strong,{children:"clientPort"}),` for any
address/interface/nic on the server will be
accepted.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"minSessionTimeout"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` the
minimum session timeout in milliseconds that the server
will allow the client to negotiate. Defaults to 2 times
the `,e.jsx(n.strong,{children:"tickTime"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxSessionTimeout"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` the
maximum session timeout in milliseconds that the server
will allow the client to negotiate. Defaults to 20 times
the `,e.jsx(n.strong,{children:"tickTime"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"fsync.warningthresholdms"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.fsync.warningthresholdms"}),`)
`,e.jsx(n.strong,{children:"New in 3.3.4:"}),` A
warning message will be output to the log whenever an
fsync in the Transactional Log (WAL) takes longer than
this value. The values is specified in milliseconds and
defaults to 1000. This value can only be set as a
system property.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxResponseCacheSize"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.maxResponseCacheSize"}),`)
When set to a positive integer, it determines the size
of the cache that stores the serialized form of recently
read records. Helps save the serialization cost on
popular znodes. The metrics `,e.jsx(n.strong,{children:"response_packet_cache_hits"}),`
and `,e.jsx(n.strong,{children:"response_packet_cache_misses"}),` can be used to tune
this value to a given workload. The feature is turned on
by default with a value of 400, set to 0 or a negative
integer to turn the feature off.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxGetChildrenResponseCacheSize"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.maxGetChildrenResponseCacheSize"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Similar to `,e.jsx(n.strong,{children:"maxResponseCacheSize"}),`, but applies to get children
requests. The metrics `,e.jsx(n.strong,{children:"response_packet_get_children_cache_hits"}),`
and `,e.jsx(n.strong,{children:"response_packet_get_children_cache_misses"}),` can be used to tune
this value to a given workload. The feature is turned on
by default with a value of 400, set to 0 or a negative
integer to turn the feature off.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"autopurge.snapRetainCount"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.4.0:"}),`
When enabled, ZooKeeper auto purge feature retains
the `,e.jsx(n.strong,{children:"autopurge.snapRetainCount"}),` most
recent snapshots and the corresponding transaction logs in the
`,e.jsx(n.strong,{children:"dataDir"})," and ",e.jsx(n.strong,{children:"dataLogDir"}),` respectively and deletes the rest.
Defaults to 3. Minimum value is 3.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"autopurge.purgeInterval"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.4.0:"}),` The
time interval in hours for which the purge task has to
be triggered. Set to a positive integer (1 and above)
to enable the auto purging. Defaults to 0.
`,e.jsx(n.strong,{children:"Suffix support added in 3.10.0:"}),` The interval is specified as an integer with an optional suffix to indicate the time unit.
Supported suffixes are: `,e.jsx(n.code,{children:"ms"})," for milliseconds, ",e.jsx(n.code,{children:"s"})," for seconds, ",e.jsx(n.code,{children:"m"})," for minutes, ",e.jsx(n.code,{children:"h"})," for hours, and ",e.jsx(n.code,{children:"d"}),` for days.
For example, "10m" represents 10 minutes, and "5h" represents 5 hours.
If no suffix is provided, the default unit is hours.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"syncEnabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.observer.syncEnabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.4.6, 3.5.0:"}),`
The observers now log transaction and write snapshot to disk
by default like the participants. This reduces the recovery time
of the observers on restart. Set to "false" to disable this
feature. Default is "true"`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"extendedTypesEnabled"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.extendedTypesEnabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.4, 3.6.0:"})," Define to ",e.jsx(n.code,{children:"true"}),` to enable
extended features such as the creation of `,e.jsx(n.a,{href:"/developer/programmers-guide/data-model#ttl-nodes",children:"TTL Nodes"}),`.
They are disabled by default. IMPORTANT: when enabled server IDs must
be less than 255 due to internal limitations.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"emulate353TTLNodes"}),` :
(Java system property only:`,e.jsx(n.strong,{children:"zookeeper.emulate353TTLNodes"}),`).
`,e.jsx(n.strong,{children:"New in 3.5.4, 3.6.0:"}),` Due to [ZOOKEEPER-2901]
(`,e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2901",children:"https://issues.apache.org/jira/browse/ZOOKEEPER-2901"}),`) TTL nodes
created in version 3.5.3 are not supported in 3.5.4/3.6.0. However, a workaround is provided via the
zookeeper.emulate353TTLNodes system property. If you used TTL nodes in ZooKeeper 3.5.3 and need to maintain
compatibility set `,e.jsx(n.strong,{children:"zookeeper.emulate353TTLNodes"})," to ",e.jsx(n.code,{children:"true"}),` in addition to
`,e.jsx(n.strong,{children:"zookeeper.extendedTypesEnabled"}),`. NOTE: due to the bug, server IDs
must be 127 or less. Additionally, the maximum support TTL value is `,e.jsx(n.code,{children:"1099511627775"}),` which is smaller
than what was allowed in 3.5.3 (`,e.jsx(n.code,{children:"1152921504606846975"}),")"]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watchManagerName"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.watchManagerName"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"})," Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
New watcher manager WatchManagerOptimized is added to optimize the memory overhead in heavy watch use cases. This
config is used to define which watcher manager to be used. Currently, we only support WatchManager and
WatchManagerOptimized.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watcherCleanThreadsNum"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.watcherCleanThreadsNum"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"})," Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, this config is used to decide how
many thread is used in the WatcherCleaner. More thread usually means larger clean up throughput. The
default value is 2, which is good enough even for heavy and continuous session closing/recreating cases.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watcherCleanThreshold"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.watcherCleanThreshold"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"})," Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
heavy, batch processing will reduce the cost and improve the performance. This setting is used to decide
the batch size. The default one is 1000, we don't need to change it if there is no memory or clean up
speed issue.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watcherCleanIntervalInSeconds"}),` :
(Java system property only:`,e.jsx(n.strong,{children:"zookeeper.watcherCleanIntervalInSeconds"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"})," Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
The new watcher manager WatchManagerOptimized will clean up the dead watchers lazily, the cleanup process is relatively
heavy, batch processing will reduce the cost and improve the performance. Besides watcherCleanThreshold,
this setting is used to clean up the dead watchers after certain time even the dead watchers are not larger
than watcherCleanThreshold, so that we won't leave the dead watchers there for too long. The default setting
is 10 minutes, which usually don't need to be changed.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxInProcessingDeadWatchers"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.maxInProcessingDeadWatchers"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"})," Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
This is used to control how many backlog can we have in the WatcherCleaner, when it reaches this number, it will
slow down adding the dead watcher to WatcherCleaner, which will in turn slow down adding and closing
watchers, so that we can avoid OOM issue. By default there is no limit, you can set it to values like
watcherCleanThreshold * 1000.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"bitHashCacheSize"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.bitHashCacheSize"}),`)
`,e.jsx(n.strong,{children:"New 3.6.0"}),": Added in ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1179",children:"ZOOKEEPER-1179"}),`
This is the setting used to decide the HashSet cache size in the BitHashSet implementation. Without HashSet, we
need to use O(N) time to get the elements, N is the bit numbers in elementBits. But we need to
keep the size small to make sure it doesn't cost too much in memory, there is a trade off between memory
and time complexity. The default value is 10, which seems a relatively reasonable cache size.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"fastleader.minNotificationInterval"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.fastleader.minNotificationInterval"}),`)
Lower bound for length of time between two consecutive notification
checks on the leader election. This interval determines how long a
peer waits to check the set of election votes and effects how
quickly an election can resolve. The interval follows a backoff
strategy from the configured minimum (this) and the configured maximum
(fastleader.maxNotificationInterval) for long elections.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"fastleader.maxNotificationInterval"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.fastleader.maxNotificationInterval"}),`)
Upper bound for length of time between two consecutive notification
checks on the leader election. This interval determines how long a
peer waits to check the set of election votes and effects how
quickly an election can resolve. The interval follows a backoff
strategy from the configured minimum (fastleader.minNotificationInterval)
and the configured maximum (this) for long elections.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionMaxTokens"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_tokens"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the maximum number of tokens in the token-bucket.
When set to 0, throttling is disabled. Default is 0.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionTokenFillTime"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_fill_time"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the interval in milliseconds when the token bucket is re-filled with
`,e.jsx(n.em,{children:"connectionTokenFillCount"})," tokens. Default is 1."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionTokenFillCount"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_fill_count"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the number of tokens to add to the token bucket every
`,e.jsx(n.em,{children:"connectionTokenFillTime"})," milliseconds. Default is 1."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionFreezeTime"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_freeze_time"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the interval in milliseconds when the dropping
probability is adjusted. When set to -1, probabilistic dropping is disabled.
Default is -1.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionDropIncrease"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_drop_increase"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the dropping probability to increase. The throttler
checks every `,e.jsx(n.em,{children:"connectionFreezeTime"}),` milliseconds and if the token bucket is
empty, the dropping probability will be increased by `,e.jsx(n.em,{children:"connectionDropIncrease"}),`.
The default is 0.02.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionDropDecrease"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_drop_decrease"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping.
This parameter defines the dropping probability to decrease. The throttler
checks every `,e.jsx(n.em,{children:"connectionFreezeTime"}),` milliseconds and if the token bucket has
more tokens than a threshold, the dropping probability will be decreased by
`,e.jsx(n.em,{children:"connectionDropDecrease"}),". The threshold is ",e.jsx(n.em,{children:"connectionMaxTokens"}),` *
`,e.jsx(n.em,{children:"connectionDecreaseRatio"}),". The default is 0.002."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectionDecreaseRatio"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.connection_throttle_decrease_ratio"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This is one of the parameters to tune the server-side connection throttler,
which is a token-based rate limiting mechanism with optional probabilistic
dropping. This parameter defines the threshold to decrease the dropping
probability. The default is 0.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.connection_throttle_weight_enabled"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Whether to consider connection weights when throttling. Only useful when connection throttle is enabled, that is, connectionMaxTokens is larger than 0. The default is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.connection_throttle_global_session_weight"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The weight of a global session. It is the number of tokens required for a global session request to get through the connection throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 3.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.connection_throttle_local_session_weight"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The weight of a local session. It is the number of tokens required for a local session request to get through the connection throttler. It has to be a positive integer no larger than the weight of a global session or a renew session. The default is 1.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.connection_throttle_renew_session_weight"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The weight of renewing a session. It is also the number of tokens required for a reconnect request to get through the throttler. It has to be a positive integer no smaller than the weight of a local session. The default is 2.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"clientPortListenBacklog"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.4.14, 3.5.5, 3.6.0:"}),`
The socket backlog length for the ZooKeeper server socket. This controls
the number of requests that will be queued server-side to be processed
by the ZooKeeper server. Connections that exceed this length will receive
a network timeout (30s) which may cause ZooKeeper session expiry issues.
By default, this value is unset (`,e.jsx(n.code,{children:"-1"}),`) which, on Linux, uses a backlog of
`,e.jsx(n.code,{children:"50"}),". This value must be a positive number."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"serverCnxnFactory"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.serverCnxnFactory"}),`)
Specifies ServerCnxnFactory implementation.
This should be set to `,e.jsx(n.code,{children:"NettyServerCnxnFactory"}),` in order to use TLS based server communication.
Default is `,e.jsx(n.code,{children:"NIOServerCnxnFactory"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"flushDelay"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.flushDelay"}),`)
Time in milliseconds to delay the flush of the commit log.
Does not affect the limit defined by `,e.jsx(n.em,{children:"maxBatchSize"}),`.
Disabled by default (with value 0). Ensembles with high write rates
may see throughput improved with a value of 10-20 ms.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxWriteQueuePollTime"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.maxWriteQueuePollTime"}),`)
If `,e.jsx(n.em,{children:"flushDelay"}),` is enabled, this determines the amount of time in milliseconds
to wait before flushing when no new requests are being queued.
Set to `,e.jsx(n.em,{children:"flushDelay"}),"/3 by default (implicitly disabled by default)."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxBatchSize"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.maxBatchSize"}),`)
The number of transactions allowed in the server before a flush of the
commit log is triggered.
Does not affect the limit defined by `,e.jsx(n.em,{children:"flushDelay"}),`.
Default is 1000.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"enforceQuota"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.enforceQuota"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
Enforce the quota check. When enabled and the client exceeds the total bytes or children count hard quota under a znode, the server will reject the request and reply the client a `,e.jsx(n.code,{children:"QuotaExceededException"}),` by force.
The default value is: false. Exploring `,e.jsx(n.a,{href:"/admin-ops/quota-guide",children:"quota feature"})," for more details."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"requestThrottleLimit"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.request_throttle_max_requests"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The total number of outstanding requests allowed before the RequestThrottler starts stalling. When set to 0, throttling is disabled. The default is 0.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"requestThrottleStallTime"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.request_throttle_stall_time"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The maximum time (in milliseconds) for which a thread may wait to be notified that it may proceed processing a request. The default is 100.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"requestThrottleDropStale"}),` :
(Java system property: `,e.jsx(n.strong,{children:"request_throttle_drop_stale"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
When enabled, the throttler will drop stale requests rather than issue them to the request pipeline. A stale request is a request sent by a connection that is now closed, and/or a request that will have a request latency higher than the sessionTimeout. The default is true.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"requestStaleLatencyCheck"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.request_stale_latency_check"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
When enabled, a request is considered stale if the request latency is higher than its associated session timeout. Disabled by default.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"requestStaleConnectionCheck"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.request_stale_connection_check"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
When enabled, a request is considered stale if the request's connection has closed. Enabled by default.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.request_throttler.shutdownTimeout"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The time (in milliseconds) the RequestThrottler waits for the request queue to drain during shutdown before it shuts down forcefully. The default is 10000.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"advancedFlowControlEnabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.netty.advancedFlowControl.enabled"}),`)
Using accurate flow control in netty based on the status of ZooKeeper
pipeline to avoid direct buffer OOM. It will disable the AUTO_READ in
Netty.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"enableEagerACLCheck"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.enableEagerACLCheck"}),`)
When set to "true", enables eager ACL check on write requests on each local
server before sending the requests to quorum. Default is "false".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxConcurrentSnapSyncs"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.leader.maxConcurrentSnapSyncs"}),`)
The maximum number of snap syncs a leader or a follower can serve at the same
time. The default is 10.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxConcurrentDiffSyncs"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.leader.maxConcurrentDiffSyncs"}),`)
The maximum number of diff syncs a leader or a follower can serve at the same
time. The default is 100.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"digest.enabled"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.digest.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The digest feature is added to detect the data inconsistency inside
ZooKeeper when loading database from disk, catching up and following
leader, its doing incrementally hash check for the DataTree based on
the adHash paper mentioned in`]}),`
`,e.jsx(n.p,{children:e.jsx(n.a,{href:"https://cseweb.ucsd.edu/~daniele/papers/IncHash.pdf",children:"https://cseweb.ucsd.edu/~daniele/papers/IncHash.pdf"})}),`
`,e.jsx(n.p,{children:`The idea is simple, the hash value of DataTree will be updated incrementally
based on the changes to the set of data. When the leader is preparing the txn,
it will pre-calculate the hash of the tree based on the changes happened with
formula:`}),`
`,e.jsx(n.p,{children:e.jsx(n.code,{children:"current_hash = current_hash + hash(new node data) - hash(old node data)"})}),`
`,e.jsx(n.p,{children:`If it’s creating a new node, the hash(old node data) will be 0, and if it’s a
delete node op, the hash(new node data) will be 0.`}),`
`,e.jsx(n.p,{children:`This hash will be associated with each txn to represent the expected hash value
after applying the txn to the data tree, it will be sent to followers with
original proposals. Learner will compare the actual hash value with the one in
the txn after applying the txn to the data tree, and report mismatch if it’s not
the same.`}),`
`,e.jsx(n.p,{children:`These digest value will also be persisted with each txn and snapshot on the disk,
so when servers restarted and load data from disk, it will compare and see if
there is hash mismatch, which will help detect data loss issue on disk.`}),`
`,e.jsx(n.p,{children:`For the actual hash function, we’re using CRC internally, it’s not a collisionless
hash function, but it’s more efficient compared to collisionless hash, and the
collision possibility is really really rare and can already meet our needs here.`}),`
`,e.jsx(n.p,{children:`This feature is backward and forward compatible, so it can safely roll upgrade,
downgrade, enabled and later disabled without any compatible issue. Here are the
scenarios have been covered and tested:`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:`When leader runs with new code while follower runs with old one, the digest will
be appended to the end of each txn, follower will only read header and txn data,
digest value in the txn will be ignored. It won't affect the follower reads and
processes the next txn.`}),`
`,e.jsx(n.li,{children:`When leader runs with old code while follower runs with new one, the digest won't
be sent with txn, when follower tries to read the digest, it will throw EOF which
is caught and handled gracefully with digest value set to null.`}),`
`,e.jsx(n.li,{children:`When loading old snapshot with new code, it will throw IOException when trying to
read the non-exist digest value, and the exception will be caught and digest will
be set to null, which means we won't compare digest when loading this snapshot,
which is expected to happen during rolling upgrade`}),`
`,e.jsx(n.li,{children:`When loading new snapshot with old code, it will finish successfully after deserializing
the data tree, the digest value at the end of snapshot file will be ignored`}),`
`,e.jsx(n.li,{children:`The scenarios of rolling restart with flags change are similar to the 1st and 2nd
scenarios discussed above, if the leader enabled but follower not, digest value will
be ignored, and follower won't compare the digest during runtime; if leader disabled
but follower enabled, follower will get EOF exception which is handled gracefully.`}),`
`]}),`
`,e.jsx(n.p,{children:`Note: the current digest calculation excluded nodes under /zookeeper
due to the potential inconsistency in the /zookeeper/quota stat node,
we can include that after that issue is fixed.`}),`
`,e.jsx(n.p,{children:'By default, this feature is enabled, set "false" to disable it.'}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"snapshot.compression.method"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.snapshot.compression.method"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
This property controls whether or not ZooKeeper should compress snapshots
before storing them on disk (see `,e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3179",children:"ZOOKEEPER-3179"}),`).
Possible values are:`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:'"": Disabled (no snapshot compression). This is the default behavior.'}),`
`,e.jsxs(n.li,{children:['"gz": See ',e.jsx(n.a,{href:"https://en.wikipedia.org/wiki/Gzip",children:"gzip compression"}),"."]}),`
`,e.jsxs(n.li,{children:['"snappy": See ',e.jsx(n.a,{href:"https://en.wikipedia.org/wiki/Snappy_(compression)",children:"Snappy compression"}),"."]}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"snapshot.trust.empty"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.snapshot.trust.empty"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.6:"}),`
This property controls whether or not ZooKeeper should treat missing
snapshot files as a fatal state that can't be recovered from.
Set to true to allow ZooKeeper servers recover without snapshot
files. This should only be set during upgrading from old versions of
ZooKeeper (3.4.x, pre 3.5.3) where ZooKeeper might only have transaction
log files but without presence of snapshot files. If the value is set
during upgrade, we recommend setting the value back to false after upgrading
and restart ZooKeeper process so ZooKeeper can continue normal data
consistency check during recovery process.
Default value is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"audit.enable"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.audit.enable"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
By default audit logs are disabled. Set to "true" to enable it. Default value is "false".
See the `,e.jsx(n.a,{href:"/admin-ops/monitor-and-audit-logs",children:"ZooKeeper audit logs"})," for more information."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"audit.impl.class"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.audit.impl.class"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Class to implement the audit logger. By default logback based audit logger org.apache.zookeeper.audit
.Slf4jAuditLogger is used.
See the `,e.jsx(n.a,{href:"/admin-ops/monitor-and-audit-logs",children:"ZooKeeper audit logs"})," for more information."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"largeRequestMaxBytes"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.largeRequestMaxBytes"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The maximum number of bytes of all inflight large request. The connection will be closed if a coming large request causes the limit exceeded. The default is 100 * 1024 * 1024.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"largeRequestThreshold"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.largeRequestThreshold"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The size threshold after which a request is considered a large request. If it is -1, then all requests are considered small, effectively turning off large request throttling. The default is -1.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"outstandingHandshake.limit"}),`
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.netty.server.outstandingHandshake.limit"}),`)
The maximum in-flight TLS handshake connections could have in ZooKeeper,
the connections exceed this limit will be rejected before starting handshake.
This setting doesn't limit the max TLS concurrency, but helps avoid herd
effect due to TLS handshake timeout when there are too many in-flight TLS
handshakes. Set it to something like 250 is good enough to avoid herd effect.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"netty.server.earlyDropSecureConnectionHandshakes"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.netty.server.earlyDropSecureConnectionHandshakes"}),`)
If the ZooKeeper server is not fully started, drop TCP connections before performing the TLS handshake.
This is useful in order to prevent flooding the server with many concurrent TLS handshakes after a restart.
Please note that if you enable this flag the server won't answer to 'ruok' commands if it is not fully started.
The behaviour of dropping the connection has been introduced in ZooKeeper 3.7 and it was not possible to disable it.
Since 3.7.1 and 3.8.0 this feature is disabled by default.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"throttledOpWaitTime"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.throttled_op_wait_time"}),`)
The time in the RequestThrottler queue longer than which a request will be marked as throttled.
A throttled requests will not be processed other than being fed down the pipeline of the server it belongs
to preserve the order of all requests.
The FinalProcessor will issue an error response (new error code: ZTHROTTLEDOP) for these undigested requests.
The intent is for the clients not to retry them immediately.
When set to 0, no requests will be throttled. The default is 0.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"learner.closeSocketAsync"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.learner.closeSocketAsync"}),`)
(Java system property: `,e.jsx(n.strong,{children:"learner.closeSocketAsync"}),`)(Added for backward compatibility)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
When enabled, a learner will close the quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time, block the shutdown process, potentially delay a new leader election, and leave the quorum unavailable. Closing the socket asynchronously avoids blocking the shutdown process despite the long socket closing time and a new leader election can be started while the socket being closed.
The default is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"leader.closeSocketAsync"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.leader.closeSocketAsync"}),`)
(Java system property: `,e.jsx(n.strong,{children:"leader.closeSocketAsync"}),`)(Added for backward compatibility)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
When enabled, the leader will close a quorum socket asynchronously. This is useful for TLS connections where closing a socket might take a long time. If disconnecting a follower is initiated in ping() because of a failed SyncLimitCheck then the long socket closing time will block the sending of pings to other followers. Without receiving pings, the other followers will not send session information to the leader, which causes sessions to expire. Setting this flag to true ensures that pings will be sent regularly.
The default is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"learner.asyncSending"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.learner.asyncSending"}),`)
(Java system property: `,e.jsx(n.strong,{children:"learner.asyncSending"}),`)(Added for backward compatibility)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
The sending and receiving packets in Learner were done synchronously in a critical section. An untimely network issue could cause the followers to hang (see `,e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3575",children:"ZOOKEEPER-3575"})," and ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4074",children:"ZOOKEEPER-4074"}),`). The new design moves sending packets in Learner to a separate thread and sends the packets asynchronously. The new design is enabled with this parameter (learner.asyncSending).
The default is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"forward_learner_requests_to_commit_processor_disabled"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.forward_learner_requests_to_commit_processor_disabled"}),`)
When this property is set, the requests from learners won't be enqueued to
CommitProcessor queue, which will help save the resources and GC time on
leader. The default value is false.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"serializeLastProcessedZxid.enabled"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.serializeLastProcessedZxid.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.9.0:"}),`
If enabled, ZooKeeper serializes the lastProcessedZxid when snapshot and deserializes it
when restore. Defaults to true. Needs to be enabled for performing snapshot and restore
via admin server commands, as there is no snapshot file name to extract the lastProcessedZxid.`]}),`
`,e.jsx(n.p,{children:"This feature is backward and forward compatible. Here are the different scenarios."}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"Snapshot triggered by server internally"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`When loading old snapshot with new code, it will throw EOFException when trying to
read the non-exist lastProcessedZxid value, and the exception will be caught.
The lastProcessedZxid will be set using the snapshot file name.`}),`
`,e.jsx(n.li,{children:`When loading new snapshot with old code, it will finish successfully after deserializing
the digest value, the lastProcessedZxid at the end of snapshot file will be ignored.
The lastProcessedZxid will be set using the snapshot file name.`}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:`Sync up between leader and follower: The lastProcessedZxid will not be serialized by
leader and deserialized by follower in both new and old code. It will be set to the
lastProcessedZxid sent from leader via QuorumPacket.`}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:`Snapshot triggered via admin server APIs: The feature flag need to be enabled for the
snapshot command to work.`}),`
`]}),`
`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"cluster-options",children:"Cluster Options"}),`
`,e.jsx(n.p,{children:`The options in this section are designed for use with an ensemble
of servers — that is, when deploying clusters of servers.`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"electionAlg"}),` :
(No Java system property)
Election implementation to use. A value of "1" corresponds to the
non-authenticated UDP-based version of fast leader election, "2"
corresponds to the authenticated UDP-based version of fast
leader election, and "3" corresponds to TCP-based version of
fast leader election. Algorithm 3 was made default in 3.2.0 and
prior versions (3.0.0 and 3.1.0) were using algorithm 1 and 2 as well.`]}),`
`,e.jsx(t,{type:"info",children:e.jsxs(n.p,{children:["The implementations of leader election 1, and 2 were ",e.jsx(n.strong,{children:"deprecated"}),` in
3.4.0. Since 3.6.0 only FastLeaderElection is available, in case of upgrade
you have to shut down all of your servers and restart them with
electionAlg=3 (or by removing the line from the configuration file).`]})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"maxTimeToWaitForEpoch"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.leader.maxTimeToWaitForEpoch"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
The maximum time to wait for epoch from voters when activating
leader. If leader received a LOOKING notification from one of
its voters, and it hasn't received epoch packets from majority
within maxTimeToWaitForEpoch, then it will goto LOOKING and
elect leader again.
This can be tuned to reduce the quorum or server unavailable
time, it can be set to be much smaller than initLimit * tickTime.
In cross datacenter environment, it can be set to something
like 2s.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"initLimit"}),` :
(No Java system property)
Amount of time, in ticks (see `,e.jsx(n.a,{href:"#minimum-configuration",children:"tickTime"}),`), to allow followers to
connect and sync to a leader. Increased this value as needed, if
the amount of data managed by ZooKeeper is large.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connectToLearnerMasterLimit"}),` :
(Java system property: zookeeper.`,e.jsx(n.strong,{children:"connectToLearnerMasterLimit"}),`)
Amount of time, in ticks (see `,e.jsx(n.a,{href:"#minimum-configuration",children:"tickTime"}),`), to allow followers to
connect to the leader after leader election. Defaults to the value of initLimit.
Use when initLimit is high so connecting to learner master doesn't result in higher timeout.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"leaderServes"}),` :
(Java system property: zookeeper.`,e.jsx(n.strong,{children:"leaderServes"}),`)
Leader accepts client connections. Default value is "yes".
The leader machine coordinates updates. For higher update
throughput at the slight expense of read throughput the leader
can be configured to not accept clients and focus on
coordination. The default to this option is yes, which means
that a leader will accept client connections.`]}),`
`,e.jsx(t,{type:"info",children:e.jsx(n.p,{children:`Turning on leader selection is highly recommended when you have more than
three ZooKeeper servers in an ensemble.`})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"server.x=[hostname]:nnnnn[:nnnnn] etc"}),` :
(No Java system property)
Servers making up the ZooKeeper ensemble. When the server
starts up, it determines which server it is by looking for the
file `,e.jsx(n.em,{children:"myid"}),` in the data directory. That file contains the server number, in ASCII,
and it should match `,e.jsx(n.strong,{children:"x"})," in ",e.jsx(n.strong,{children:"server.x"}),` in the left hand side of this setting.
The list of servers that make up ZooKeeper servers that is
used by the clients must match the list of ZooKeeper servers
that each ZooKeeper server has.
There are two port numbers `,e.jsx(n.strong,{children:"nnnnn"}),`.
The first followers used to connect to the leader, and the second is for
leader election. If you want to test multiple servers on a single machine, then
different ports can be used for each server.`]}),`
`,e.jsxs(n.p,{children:["Since ZooKeeper 3.6.0 it is possible to specify ",e.jsx(n.strong,{children:"multiple addresses"}),` for each
ZooKeeper server (see `,e.jsx(n.a,{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188",children:"ZOOKEEPER-3188"}),`).
To enable this feature, you must set the `,e.jsx(n.em,{children:"multiAddress.enabled"}),` configuration property
to `,e.jsx(n.em,{children:"true"}),`. This helps to increase availability and adds network level
resiliency to ZooKeeper. When multiple physical network interfaces are used
for the servers, ZooKeeper is able to bind on all interfaces and runtime switching
to a working interface in case a network error. The different addresses can be specified
in the config using a pipe ('|') character. A valid configuration using multiple addresses looks like:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=zoo1-net1:2888:3888|zoo1-net2:2889:3889"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=zoo3-net1:2888:3888|zoo3-net2:2889:3889"})})]})})}),`
`,e.jsx(t,{type:"info",children:e.jsxs(n.p,{children:[`By enabling this feature, the Quorum protocol (ZooKeeper Server-Server
protocol) will change. The users will not notice this and when anyone starts
a ZooKeeper cluster with the new config, everything will work normally.
However, it's not possible to enable this feature and specify multiple
addresses during a rolling upgrade if the old ZooKeeper cluster didn't
support the `,e.jsx(n.em,{children:"multiAddress"}),` feature (and the new Quorum protocol). In case if
you need this feature but you also need to perform a rolling upgrade from a
ZooKeeper cluster older than `,e.jsx(n.em,{children:"3.6.0"}),`, then you first need to do the rolling
upgrade without enabling the MultiAddress feature and later make a separate
rolling restart with the new configuration where `,e.jsx(n.strong,{children:"multiAddress.enabled"}),` is
set to `,e.jsx(n.strong,{children:"true"})," and multiple addresses are provided."]})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"syncLimit"}),` :
(No Java system property)
Amount of time, in ticks (see `,e.jsx(n.a,{href:"#minimum-configuration",children:"tickTime"}),`), to allow followers to sync
with ZooKeeper. If followers fall too far behind a leader, they
will be dropped.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"group.x=nnnnn[:nnnnn]"}),` :
(No Java system property)
Enables a hierarchical quorum construction."x" is a group identifier
and the numbers following the "=" sign correspond to server identifiers.
The left-hand side of the assignment is a colon-separated list of server
identifiers. Note that groups must be disjoint and the union of all groups
must be the ZooKeeper ensemble.
You will find an example `,e.jsx(n.a,{href:"/admin-ops/quorums",children:"here"})]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"weight.x=nnnnn"}),` :
(No Java system property)
Used along with "group", it assigns a weight to a server when
forming quorums. Such a value corresponds to the weight of a server
when voting. There are a few parts of ZooKeeper that require voting
such as leader election and the atomic broadcast protocol. By default
the weight of server is 1. If the configuration defines groups, but not
weights, then a value of 1 will be assigned to all servers.
You will find an example `,e.jsx(n.a,{href:"/admin-ops/quorums",children:"here"})]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"cnxTimeout"}),` :
(Java system property: zookeeper.`,e.jsx(n.strong,{children:"cnxTimeout"}),`)
Sets the timeout value for opening connections for leader election notifications.
Only applicable if you are using electionAlg 3.`]}),`
`,e.jsx(t,{type:"info",children:"Default value is 5 seconds."}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"quorumCnxnTimeoutMs"}),` :
(Java system property: zookeeper.`,e.jsx(n.strong,{children:"quorumCnxnTimeoutMs"}),`)
Sets the read timeout value for the connections for leader election notifications.
Only applicable if you are using electionAlg 3.`]}),`
`,e.jsx(t,{type:"info",children:e.jsx(n.p,{children:`Default value is -1, which will then use the syncLimit * tickTime as the
timeout.`})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"standaloneEnabled"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.5.0:"}),`
When set to false, a single server can be started in replicated
mode, a lone participant can run with observers, and a cluster
can reconfigure down to one node, and up from one node. The
default is true for backwards compatibility. It can be set
using QuorumPeerConfig's setStandaloneEnabled method or by
adding "standaloneEnabled=false" or "standaloneEnabled=true"
to a server's config file.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"reconfigEnabled"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.5.3:"}),`
This controls the enabling or disabling of
`,e.jsx(n.a,{href:"/admin-ops/dynamic-reconfiguration",children:"Dynamic Reconfiguration"}),` feature. When the feature
is enabled, users can perform reconfigure operations through
the ZooKeeper client API or through ZooKeeper command line tools
assuming users are authorized to perform such operations.
When the feature is disabled, no user, including the super user,
can perform a reconfiguration. Any attempt to reconfigure will return an error.
`,e.jsx(n.strong,{children:'"reconfigEnabled"'}),` option can be set as
`,e.jsx(n.strong,{children:'"reconfigEnabled=false"'}),` or
`,e.jsx(n.strong,{children:'"reconfigEnabled=true"'}),`
to a server's config file, or using QuorumPeerConfig's
setReconfigEnabled method. The default value is false.
If present, the value should be consistent across every server in
the entire ensemble. Setting the value as true on some servers and false
on other servers will cause inconsistent behavior depending on which server
is elected as leader. If the leader has a setting of
`,e.jsx(n.strong,{children:'"reconfigEnabled=true"'}),`, then the ensemble
will have reconfig feature enabled. If the leader has a setting of
`,e.jsx(n.strong,{children:'"reconfigEnabled=false"'}),`, then the ensemble
will have reconfig feature disabled. It is thus recommended having a consistent
value for `,e.jsx(n.strong,{children:'"reconfigEnabled"'}),` across servers
in the ensemble.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"4lw.commands.whitelist"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.4lw.commands.whitelist"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.3:"}),`
A list of comma separated `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/commands#the-four-letter-words",children:"Four Letter Words"}),`
commands that user wants to use. A valid Four Letter Words
command must be put in this list else ZooKeeper server will
not enable the command.
By default the whitelist only contains "srvr" command
which zkServer.sh uses. Additionally, if Read Only Mode is enabled by setting
Java system property `,e.jsx(n.strong,{children:"readonlymode.enabled"}),`, then the "isro" command is
added to the whitelist. The rest of four-letter word commands are disabled
by default: attempting to use them will gain a response
".... is not executed because it is not in the whitelist."
Here's an example of the configuration that enables stat, ruok, conf, and isro
command while disabling the rest of Four Letter Words command:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"4lw.commands.whitelist=stat, ruok, conf, isro"})})})})}),`
`,e.jsx(n.p,{children:`If you really need enable all four-letter word commands by default, you can use
the asterisk option so you don't have to include every command one by one in the list.
As an example, this will enable all four-letter word commands:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"4lw.commands.whitelist=*"})})})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"tcpKeepAlive"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.tcpKeepAlive"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.4:"}),`
Setting this to true sets the TCP keepAlive flag on the
sockets used by quorum members to perform elections.
This will allow for connections between quorum members to
remain up when there is network infrastructure that may
otherwise break them. Some NATs and firewalls may terminate
or lose state for long-running or idle connections.
Enabling this option relies on OS level settings to work
properly, check your operating system's options regarding TCP
keepalive for more information. Defaults to
`,e.jsx(n.strong,{children:"false"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"clientTcpKeepAlive"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.clientTcpKeepAlive"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.1:"}),`
Setting this to true sets the TCP keepAlive flag on the
client sockets. Some broken network infrastructure may lose
the FIN packet that is sent from closing client. These never
closed client sockets cause OS resource leak. Enabling this
option terminates these zombie sockets by idle check.
Enabling this option relies on OS level settings to work
properly, check your operating system's options regarding TCP
keepalive for more information. Defaults to `,e.jsx(n.strong,{children:"false"}),`. Please
note the distinction between it and `,e.jsx(n.strong,{children:"tcpKeepAlive"}),`. It is
applied for the client sockets while `,e.jsx(n.strong,{children:"tcpKeepAlive"}),` is for
the sockets used by quorum members. Currently this option is
only available when default `,e.jsx(n.code,{children:"NIOServerCnxnFactory"})," is used."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"electionPortBindRetry"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.electionPortBindRetry"}),`)
Property set max retry count when Zookeeper server fails to bind
leader election port. Such errors can be temporary and recoverable,
such as DNS issue described in `,e.jsx(n.a,{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3320",children:"ZOOKEEPER-3320"}),`,
or non-retryable, such as port already in use.
In case of transient errors, this property can improve availability
of Zookeeper server and help it to self recover.
Default value 3. In container environment, especially in Kubernetes,
this value should be increased or set to 0(infinite retry) to overcome issues
related to DNS name resolving.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"observer.reconnectDelayMs"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.observer.reconnectDelayMs"}),`)
When observer loses its connection with the leader, it waits for the
specified value before trying to reconnect with the leader so that
the entire observer fleet won't try to run leader election and reconnect
to the leader at once.
Defaults to 0 ms.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"observer.election.DelayMs"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.observer.election.DelayMs"}),`)
Delay the observer's participation in a leader election upon disconnect
so as to prevent unexpected additional load on the voting peers during
the process. Defaults to 200 ms.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"localSessionsEnabled"})," and ",e.jsx(n.em,{children:"localSessionsUpgradingEnabled"}),` :
`,e.jsx(n.strong,{children:"New in 3.5:"}),`
Optional value is true or false. Their default values are false.
Turning on the local session feature by setting `,e.jsx(n.em,{children:"localSessionsEnabled=true"}),`. Turning on
`,e.jsx(n.em,{children:"localSessionsUpgradingEnabled"}),` can upgrade a local session to a global session automatically as required (e.g. creating ephemeral nodes),
which only matters when `,e.jsx(n.em,{children:"localSessionsEnabled"})," is enabled."]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"encryption-authentication-authorization-options",children:"Encryption, Authentication, Authorization Options"}),`
`,e.jsx(n.p,{children:`The options in this section allow control over
encryption/authentication/authorization performed by the service.`}),`
`,e.jsxs(n.p,{children:[`Beside this page, you can also find useful information about client side configuration in the
`,e.jsx(n.a,{href:"/developer/programmers-guide/bindings#client-configuration-parameters",children:"Programmers Guide"}),`.
The ZooKeeper Wiki also has useful pages about `,e.jsx(n.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide",children:"ZooKeeper SSL support"}),`,
and `,e.jsx(n.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL",children:"SASL authentication for ZooKeeper"}),"."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"DigestAuthenticationProvider.enabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.DigestAuthenticationProvider.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.7:"}),`
Determines whether the `,e.jsx(n.code,{children:"digest"}),` authentication provider is
enabled. The default value is `,e.jsx(n.strong,{children:"true"}),` for backwards
compatibility, but it may be a good idea to disable this provider
if not used, as it can result in misleading entries appearing in
audit logs
(see `,e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-3979",children:"ZOOKEEPER-3979"}),")"]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"DigestAuthenticationProvider.superDigest"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.DigestAuthenticationProvider.superDigest"}),`)
By default this feature is `,e.jsx(n.strong,{children:"disabled"}),`
`,e.jsx(n.strong,{children:"New in 3.2:"}),`
Enables a ZooKeeper ensemble administrator to access the
znode hierarchy as a "super" user. In particular no ACL
checking occurs for a user authenticated as
super.
org.apache.zookeeper.server.auth.DigestAuthenticationProvider
can be used to generate the superDigest, call it with
one parameter of `,e.jsx(n.code,{children:'"super:<password>"'}),`. Provide the
generated `,e.jsx(n.code,{children:'"super:<data>"'}),` as the system property value
when starting each server of the ensemble.
When authenticating to a ZooKeeper server (from a
ZooKeeper client) pass a scheme of `,e.jsx(n.code,{children:'"digest"'}),` and authdata
of `,e.jsx(n.code,{children:'"super:<password>"'}),`. Note that digest auth passes
the authdata in plaintext to the server, it would be
prudent to use this authentication method only on
localhost (not over the network) or over an encrypted
connection.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"DigestAuthenticationProvider.digestAlg"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.DigestAuthenticationProvider.digestAlg"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
Set ACL digest algorithm. The default value is: `,e.jsx(n.code,{children:"SHA1"})," which will be deprecated in the future for security issues."]}),`
`,e.jsx(n.p,{children:"Set this property the same value in all the servers."}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"How to support other more algorithms?"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Modify the ",e.jsx(n.code,{children:"java.security"})," configuration file under ",e.jsx(n.code,{children:"$JAVA_HOME/jre/lib/security/java.security"}),` by specifying
`,e.jsx(n.code,{children:"security.provider.<n>=<provider class name>"}),"."]}),`
`,e.jsx(n.p,{children:"For example:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"set zookeeper.DigestAuthenticationProvider.digestAlg=RipeMD160"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"security.provider.3=org.bouncycastle.jce.provider.BouncyCastleProvider"})})]})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Copy the jar file to ",e.jsx(n.code,{children:"$JAVA_HOME/jre/lib/ext/"}),"."]}),`
`,e.jsx(n.p,{children:"For example:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"copy bcprov-jdk18on-1.60.jar to $JAVA_HOME/jre/lib/ext/"})})})})}),`
`]}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"How to migrate from one digest algorithm to another?"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:["Regenerate ",e.jsx(n.code,{children:"superDigest"})," when migrating to new algorithm."]}),`
`,e.jsxs(n.li,{children:["Run ",e.jsx(n.code,{children:"SetAcl"})," for a znode which already had a digest auth of old algorithm."]}),`
`]}),`
`]}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"IPAuthenticationProvider.usexforwardedfor"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.IPAuthenticationProvider.usexforwardedfor"}),`)
`,e.jsx(n.strong,{children:"New in 3.9.3:"}),`
IPAuthenticationProvider uses the client IP address to authenticate the user. By
default it reads the `,e.jsx(n.strong,{children:"Host"}),` HTTP header to detect client IP address. In some
proxy configurations the proxy server adds the `,e.jsx(n.strong,{children:"X-Forwarded-For"}),` header to
the request in order to provide the IP address of the original client request.
By enabling `,e.jsx(n.strong,{children:"usexforwardedfor"})," ZooKeeper setting, ",e.jsx(n.strong,{children:"X-Forwarded-For"}),` will be preferred
over the standard `,e.jsx(n.strong,{children:"Host"}),` header.
Default value is `,e.jsx(n.strong,{children:"false"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"X509AuthenticationProvider.superUser"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.X509AuthenticationProvider.superUser"}),`)
The SSL-backed way to enable a ZooKeeper ensemble
administrator to access the znode hierarchy as a "super" user.
When this parameter is set to an X500 principal name, only an
authenticated client with that principal will be able to bypass
ACL checking and have full privileges to all znodes.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.superUser"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.superUser"}),`)
Similar to `,e.jsx(n.strong,{children:"zookeeper.X509AuthenticationProvider.superUser"}),`
but is generic for SASL based logins. It stores the name of
a user that can access the znode hierarchy as a "super" user.
You can specify multiple SASL super users using the
`,e.jsx(n.strong,{children:"zookeeper.superUser.[suffix]"}),` notation, e.g.:
`,e.jsx(n.code,{children:"zookeeper.superUser.1=..."}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.authProvider"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.ssl.authProvider"}),`)
Specifies a subclass of `,e.jsx(n.strong,{children:"org.apache.zookeeper.auth.X509AuthenticationProvider"}),`
to use for secure client authentication. This is useful in
certificate key infrastructures that do not use JKS. It may be
necessary to extend `,e.jsx(n.strong,{children:"javax.net.ssl.X509KeyManager"})," and ",e.jsx(n.strong,{children:"javax.net.ssl.X509TrustManager"}),`
to get the desired behavior from the SSL stack. To configure the
ZooKeeper server to use the custom provider for authentication,
choose a scheme name for the custom AuthenticationProvider and
set the property `,e.jsx(n.strong,{children:"zookeeper.authProvider.[scheme]"}),` to the fully-qualified class name of the custom
implementation. This will load the provider into the ProviderRegistry.
Then set this property `,e.jsx(n.strong,{children:"zookeeper.ssl.authProvider=[scheme]"}),` and that provider
will be used for secure authentication.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ensembleAuthName"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.ensembleAuthName"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Specify a list of comma-separated valid names/aliases of an ensemble. A client
can provide the ensemble name it intends to connect as the credential for scheme "ensemble". The EnsembleAuthenticationProvider will check the credential against
the list of names/aliases of the ensemble that receives the connection request.
If the credential is not in the list, the connection request will be refused.
This prevents a client accidentally connecting to a wrong ensemble.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"sessionRequireClientSASLAuth"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.sessionRequireClientSASLAuth"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
When set to `,e.jsx(n.strong,{children:"true"}),`, ZooKeeper server will only accept connections and requests from clients
that have authenticated with server via SASL. Clients that are not configured with SASL
authentication, or configured with SASL but failed authentication (i.e. with invalid credential)
will not be able to establish a session with server. A typed error code (-124) will be delivered
in such case, both Java and C client will close the session with server thereafter,
without further attempts on retrying to reconnect.`]}),`
`]}),`
`]}),`
`,e.jsxs(n.p,{children:["This configuration is shorthand for ",e.jsx(n.strong,{children:"enforce.auth.enabled=true"})," and ",e.jsx(n.strong,{children:"enforce.auth.scheme=sasl"})]}),`
`,e.jsxs(n.p,{children:[`By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting `,e.jsx(n.strong,{children:"sessionRequireClientSASLAuth"})," to ",e.jsx(n.strong,{children:"true"}),"."]}),`
`,e.jsxs(n.p,{children:["This feature overrules the ",e.jsx("emphasis",{role:"bold",children:"zookeeper.allowSaslFailedClients"}),` option, so even if server is
configured to allow clients that fail SASL authentication to login, client will not be able to
establish a session with server if this feature is enabled.`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"enforce.auth.enabled"}),` :
(Java system property : `,e.jsx(n.strong,{children:"zookeeper.enforce.auth.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
When set to `,e.jsx(n.strong,{children:"true"}),`, ZooKeeper server will only accept connections and requests from clients
that have authenticated with server via configured auth scheme. Authentication schemes
can be configured using property enforce.auth.schemes. Clients that are not
configured with the any of the auth scheme configured at server or configured but failed authentication (i.e. with invalid credential)
will not be able to establish a session with server. A typed error code (-124) will be delivered
in such case, both Java and C client will close the session with server thereafter,
without further attempts on retrying to reconnect.`]}),`
`]}),`
`,e.jsxs(n.p,{children:[`By default, this feature is disabled. Users who would like to opt-in can enable the feature
by setting `,e.jsx(n.strong,{children:"enforce.auth.enabled"})," to ",e.jsx(n.strong,{children:"true"}),"."]}),`
`,e.jsxs(n.p,{children:["When ",e.jsx(n.strong,{children:"enforce.auth.enabled=true"})," and ",e.jsx(n.strong,{children:"enforce.auth.schemes=sasl"})," then"]}),`
`,e.jsxs(n.p,{children:[e.jsx("emphasis",{role:"bold",children:"zookeeper.allowSaslFailedClients"}),` configuration
is overruled. So even if server is configured to allow clients that fail SASL
authentication to login, client will not be able to establish a session with
server if this feature is enabled with sasl as authentication scheme.`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"enforce.auth.schemes"}),` :
(Java system property : `,e.jsx(n.strong,{children:"zookeeper.enforce.auth.schemes"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
Comma separated list of authentication schemes. Clients must be authenticated with at least one
authentication scheme before doing any zookeeper operations.
This property is used only when `,e.jsx(n.strong,{children:"enforce.auth.enabled"})," is to ",e.jsx(n.strong,{children:"true"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"sslQuorum"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.sslQuorum"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Enables encrypted quorum communication. Default is `,e.jsx(n.code,{children:"false"}),". When enabling this feature, please also consider enabling ",e.jsx(n.em,{children:"leader.closeSocketAsync"}),`
and `,e.jsx(n.em,{children:"learner.closeSocketAsync"})," to avoid issues associated with the potentially long socket closing time when shutting down an SSL connection."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.keyStore.location and ssl.keyStore.password"})," and ",e.jsx(n.em,{children:"ssl.quorum.keyStore.location"})," and ",e.jsx(n.em,{children:"ssl.quorum.keyStore.password"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.keyStore.location"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.keyStore.password"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.keyStore.location"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.keyStore.password"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file path to a Java keystore containing the local
credentials to be used for client and quorum TLS connections, and the
password to unlock the file.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.keyStore.passwordPath"})," and ",e.jsx(n.em,{children:"ssl.quorum.keyStore.passwordPath"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.keyStore.passwordPath"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.keyStore.passwordPath"}),`)
`,e.jsx(n.strong,{children:"New in 3.8.0:"}),`
Specifies the file path that contains the keystore password. Reading the password from a file takes precedence over
the explicit password property.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.keyStore.type"})," and ",e.jsx(n.em,{children:"ssl.quorum.keyStore.type"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.keyStore.type"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.keyStore.type"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file format of client and quorum keystores. Values: JKS, PEM, PKCS12 or null (detect by filename).
Default: null.
`,e.jsx(n.strong,{children:"New in 3.5.10, 3.6.3, 3.7.0:"}),`
The format BCFKS was added.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.trustStore.location"})," and ",e.jsx(n.em,{children:"ssl.trustStore.password"})," and ",e.jsx(n.em,{children:"ssl.quorum.trustStore.location"})," and ",e.jsx(n.em,{children:"ssl.quorum.trustStore.password"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.trustStore.location"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.trustStore.password"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.trustStore.location"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.trustStore.password"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file path to a Java truststore containing the remote
credentials to be used for client and quorum TLS connections, and the
password to unlock the file.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.trustStore.passwordPath"})," and ",e.jsx(n.em,{children:"ssl.quorum.trustStore.passwordPath"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.trustStore.passwordPath"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.trustStore.passwordPath"}),`)
`,e.jsx(n.strong,{children:"New in 3.8.0:"}),`
Specifies the file path that contains the truststore password. Reading the password from a file takes precedence over
the explicit password property.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.trustStore.type"})," and ",e.jsx(n.em,{children:"ssl.quorum.trustStore.type"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.trustStore.type"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.trustStore.type"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file format of client and quorum trustStores. Values: JKS, PEM, PKCS12 or null (detect by filename).
Default: null.
`,e.jsx(n.strong,{children:"New in 3.5.10, 3.6.3, 3.7.0:"}),`
The format BCFKS was added.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.protocol"})," and ",e.jsx(n.em,{children:"ssl.quorum.protocol"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.protocol"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.protocol"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies to protocol to be used in client and quorum TLS negotiation.
Default: TLSv1.3 or TLSv1.2 depending on Java runtime version being used.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.enabledProtocols"})," and ",e.jsx(n.em,{children:"ssl.quorum.enabledProtocols"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.enabledProtocols"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.enabledProtocols"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the enabled protocols in client and quorum TLS negotiation.
Default: TLSv1.3, TLSv1.2 if value of `,e.jsx(n.code,{children:"protocol"})," property is TLSv1.3. TLSv1.2 if ",e.jsx(n.code,{children:"protocol"})," is TLSv1.2."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.ciphersuites"})," and ",e.jsx(n.em,{children:"ssl.quorum.ciphersuites"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.ciphersuites"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.ciphersuites"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the enabled cipher suites to be used in client and quorum TLS negotiation.
Default: JDK defaults since 3.10.0, and hard coded cipher suites for 3.9 and earlier versions. See `,e.jsx(n.a,{href:"#tls-cipher-suites",children:"TLS Cipher Suites"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.context.supplier.class"})," and ",e.jsx(n.em,{children:"ssl.quorum.context.supplier.class"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.context.supplier.class"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.context.supplier.class"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the class to be used for creating SSL context in client and quorum SSL communication.
This allows you to use custom SSL context and implement the following scenarios: 1. Use hardware keystore, loaded in using PKCS11 or something similar. 2. You don't have access to the software keystore, but can retrieve an already-constructed SSLContext from their container.
Default: null`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.hostnameVerification"})," and ",e.jsx(n.em,{children:"ssl.quorum.hostnameVerification"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.hostnameVerification"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.hostnameVerification"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies whether the hostname verification is enabled in client and quorum TLS negotiation process.
Disabling it only recommended for testing purposes.
Default: true`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.clientHostnameVerification"})," and ",e.jsx(n.em,{children:"ssl.quorum.clientHostnameVerification"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.clientHostnameVerification"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.clientHostnameVerification"}),`)
`,e.jsx(n.strong,{children:"New in 3.9.4:"}),`
Specifies whether the client's hostname verification is enabled in client and quorum TLS negotiation process.
This option requires the corresponding `,e.jsx(n.em,{children:"hostnameVerification"})," option to be ",e.jsx(n.code,{children:"true"}),`, or it will be ignored.
Default: true for quorum, false for clients`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.allowReverseDnsLookup"})," and ",e.jsx(n.em,{children:"ssl.quorum.allowReverseDnsLookup"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.allowReverseDnsLookup"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.allowReverseDnsLookup"}),`)
`,e.jsx(n.strong,{children:"New in 3.9.5:"}),`
Allow reverse DNS lookup in both server- and client hostname verifications if the hostname verification is enabled in
`,e.jsx(n.code,{children:"ZKTrustManager"}),`. Supported in both quorum and client TLS protocols. Not supported in FIPS mode. Reverse DNS lookups are
expensive and unnecessary in most cases. Make sure that certificates are created with all required Subject Alternative
Names (SAN) for successful identity verification. It's recommended to add SAN:IP entries for identity verification
of client certificates.
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.crl"})," and ",e.jsx(n.em,{children:"ssl.quorum.crl"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.crl"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.crl"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies whether Certificate Revocation List is enabled in client and quorum TLS protocols.
Default: jvm property "com.sun.net.ssl.checkRevocation" since 3.10.0, false otherwise`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.ocsp"})," and ",e.jsx(n.em,{children:"ssl.quorum.ocsp"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.ocsp"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.ocsp"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies whether Online Certificate Status Protocol is enabled in client and quorum TLS protocols.
`,e.jsx(n.strong,{children:"Changed in 3.10.0:"}),`
Before 3.10.0, `,e.jsx(n.em,{children:"ssl.ocsp"})," and ",e.jsx(n.em,{children:"ssl.quorum.ocsp"})," implies ",e.jsx(n.em,{children:"ssl.crl"})," and ",e.jsx(n.em,{children:"ssl.quorum.crl"}),` correspondingly.
After 3.10.0, one has to setup both `,e.jsx(n.em,{children:"ssl.crl"})," and ",e.jsx(n.em,{children:"ssl.ocsp"})," (or ",e.jsx(n.em,{children:"ssl.quorum.crl"})," and ",e.jsx(n.em,{children:"ssl.quorum.ocsp"}),`)
to enable OCSP. This is consistent with jdk's method of `,e.jsx(n.a,{href:"https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/ocsp.html#setting-up-a-java-client-to-use-client-driven-ocsp",children:"Setting up a Java Client to use Client-Driven OCSP"}),`.
Default: jvm security property "ocsp.enable" since 3.10.0, false otherwise`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.clientAuth"})," and ",e.jsx(n.em,{children:"ssl.quorum.clientAuth"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.clientAuth"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.clientAuth"}),`)
`,e.jsx(n.strong,{children:"Added in 3.5.5, but broken until 3.5.7:"}),`
Specifies options to authenticate ssl connections from clients. Valid values are`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:'"none": server will not request client authentication'}),`
`,e.jsx(n.li,{children:'"want": server will "request" client authentication'}),`
`,e.jsx(n.li,{children:'"need": server will "require" client authentication'}),`
`]}),`
`,e.jsx(n.p,{children:'Default: "need"'}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.handshakeDetectionTimeoutMillis"})," and ",e.jsx(n.em,{children:"ssl.quorum.handshakeDetectionTimeoutMillis"}),` :
(Java system properties: `,e.jsx(n.strong,{children:"zookeeper.ssl.handshakeDetectionTimeoutMillis"})," and ",e.jsx(n.strong,{children:"zookeeper.ssl.quorum.handshakeDetectionTimeoutMillis"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
TBD`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ssl.sslProvider"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.ssl.sslProvider"}),`)
`,e.jsx(n.strong,{children:"New in 3.9.0:"}),`
Allows to select SSL provider in the client-server communication when TLS is enabled. Netty-tcnative native library
has been added to ZooKeeper in version 3.9.0 which allows us to use native SSL libraries like OpenSSL on supported
platforms. See the available options in Netty-tcnative documentation. Default value is "JDK".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"sslQuorumReloadCertFiles"}),` :
(No Java system property)
`,e.jsx(n.strong,{children:"New in 3.5.5, 3.6.0:"}),`
Allows Quorum SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"client.certReload"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.client.certReload"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.2, 3.8.1, 3.9.0:"}),`
Allows client SSL keyStore and trustStore reloading when the certificates on the filesystem change without having to restart the ZK process. Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"client.portUnification"}),`:
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.client.portUnification"}),`)
Specifies that the client port should accept SSL connections
(using the same configuration as the secure client port).
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"authProvider"}),`:
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.authProvider"}),`)
You can specify multiple authentication provider classes for ZooKeeper.
Usually you use this parameter to specify the SASL authentication provider
like: `,e.jsx(n.code,{children:"authProvider.1=org.apache.zookeeper.server.auth.SASLAuthenticationProvider"})]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"kerberos.removeHostFromPrincipal"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.kerberos.removeHostFromPrincipal"}),`)
You can instruct ZooKeeper to remove the host from the client principal name during authentication.
(e.g. the zk/myhost@EXAMPLE.COM client principal will be authenticated in ZooKeeper as `,e.jsx(n.a,{href:"mailto:zk@EXAMPLE.COM",children:"zk@EXAMPLE.COM"}),`)
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"kerberos.removeRealmFromPrincipal"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.kerberos.removeRealmFromPrincipal"}),`)
You can instruct ZooKeeper to remove the realm from the client principal name during authentication.
(e.g. the zk/myhost@EXAMPLE.COM client principal will be authenticated in ZooKeeper as zk/myhost)
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"kerberos.canonicalizeHostNames"}),`
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.kerberos.canonicalizeHostNames"}),`)
`,e.jsx(n.strong,{children:"New in 3.7.0:"}),`
Instructs ZooKeeper to canonicalize server host names extracted from `,e.jsx(n.em,{children:"server.x"}),` lines.
This allows using e.g. `,e.jsx(n.code,{children:"CNAME"}),` records to reference servers in configuration files, while still enabling SASL Kerberos authentication between quorum members.
It is essentially the quorum equivalent of the `,e.jsx(n.em,{children:"zookeeper.sasl.client.canonicalize.hostname"}),` property for clients.
The default value is `,e.jsx(n.strong,{children:"false"})," for backwards compatibility."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"multiAddress.enabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.multiAddress.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Since ZooKeeper 3.6.0 you can also `,e.jsx(n.a,{href:"#cluster-options",children:"specify multiple addresses"}),`
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). Setting this parameter to
`,e.jsx(n.strong,{children:"true"}),` will enable this feature. Please note, that you can not enable this feature
during a rolling upgrade if the version of the old ZooKeeper cluster is prior to 3.6.0.
The default value is `,e.jsx(n.strong,{children:"false"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"multiAddress.reachabilityCheckTimeoutMs"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.multiAddress.reachabilityCheckTimeoutMs"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Since ZooKeeper 3.6.0 you can also `,e.jsx(n.a,{href:"#cluster-options",children:"specify multiple addresses"}),`
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
the reachable addresses. This happens only if you provide multiple addresses in the configuration.
In this property you can set the timeout in milliseconds for the reachability check. The check happens
in parallel for the different addresses, so the timeout you set here is the maximum time will be taken
by checking the reachability of all addresses.
The default value is `,e.jsx(n.strong,{children:"1000"}),"."]}),`
`,e.jsxs(n.p,{children:["This parameter has no effect, unless you enable the MultiAddress feature by setting ",e.jsx(n.em,{children:"multiAddress.enabled=true"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"fips-mode"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.fips-mode"}),`)
`,e.jsx(n.strong,{children:"New in 3.8.2:"}),`
Enable FIPS compatibility mode in ZooKeeper. If enabled, the following things will be changed in order to comply
with FIPS requirements:`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:["Custom trust manager (",e.jsx(n.code,{children:"ZKTrustManager"}),`) that is used for hostname verification will be disabled. As a consequence,
hostname verification is not available in the Quorum protocol, but still can be set in client-server communication.`]}),`
`,e.jsx(n.li,{children:`DIGEST-MD5 Sasl auth mechanism will be disabled in Quorum and ZooKeeper Sasl clients. Only GSSAPI (Kerberos)
can be used.`}),`
`]}),`
`,e.jsxs(n.p,{children:["Default: ",e.jsx(n.strong,{children:"true"})," (3.9.0+), ",e.jsx(n.strong,{children:"false"})," (3.8.x)"]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"tls-cipher-suites",children:"TLS Cipher Suites"}),`
`,e.jsx(n.p,{children:`From 3.5.5 to 3.9 a hard coded default cipher list was used, with the ordering
dependent on whether it is run Java 8 or a later version.`}),`
`,e.jsxs(n.p,{children:["The list on Java 8 includes TLSv1.2 CBC, GCM and TLSv1.3 ciphers in ordering: ",e.jsx(n.em,{children:"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384, TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256"})]}),`
`,e.jsxs(n.p,{children:["The list on Java 9+ includes TLSv1.2 GCM, CBC and TLSv1.3 ciphers in ordering: ",e.jsx(n.em,{children:"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256, TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA, TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256"})]}),`
`,e.jsx(n.p,{children:"Since 3.10 there is no hardcoded list, and the JDK defaults are used."}),`
`,e.jsx(n.h2,{id:"experimental-optionsfeatures",children:"Experimental Options/Features"}),`
`,e.jsx(n.p,{children:"New features that are currently considered experimental."}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"Read Only Mode Server"}),` :
(Java system property: `,e.jsx(n.strong,{children:"readonlymode.enabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.4.0:"}),`
Setting this value to true enables Read Only Mode server
support (disabled by default).
`,e.jsx(n.em,{children:"localSessionsEnabled"}),` has to be activated to serve clients.
A downgrade of an existing connections is currently not supported.
ROM allows clients sessions which requested ROM support to connect to the
server even when the server might be partitioned from
the quorum. In this mode ROM clients can still read
values from the ZK service, but will be unable to write
values and see changes from other clients. See
ZOOKEEPER-784 for more details.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.follower.skipLearnerRequestToNextProcessor"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.follower.skipLearnerRequestToNextProcessor"}),`)
When our cluster has observers which are connected with ObserverMaster, then turning on this flag might help
you reduce some memory pressure on the Observer Master. If your cluster doesn't have any observers or
they are not connected with ObserverMaster or your Observer's don't make much writes, then using this flag
won't help you.
Currently the change here is guarded behind the flag to help us get more confidence around the memory gains.
In Long run, we might want to remove this flag and set its behavior as the default codepath.`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"unsafe-options",children:"Unsafe Options"}),`
`,e.jsx(n.p,{children:`The following options can be useful, but be careful when you use
them. The risk of each is explained along with the explanation of what
the variable does.`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"forceSync"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.forceSync"}),`)
Requires updates to be synced to media of the transaction
log before finishing processing the update. If this option is
set to no, ZooKeeper will not require updates to be synced to
the media.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"jute.maxbuffer"}),` :
(Java system property:`,e.jsx(n.strong,{children:"jute.maxbuffer"}),")."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`This option can only be set as a Java system property.
There is no zookeeper prefix on it. It specifies the maximum
size of the data that can be stored in a znode. The unit is: byte. The default is
0xfffff(1048575) bytes, or just under 1M.`}),`
`,e.jsx(n.li,{children:`If this option is changed, the system property must be set on all servers and clients otherwise
problems will arise.`}),`
`,e.jsxs(n.li,{children:["When ",e.jsx(n.em,{children:"jute.maxbuffer"}),` in the client side is greater than the server side, the client wants to write the data
exceeds `,e.jsx(n.em,{children:"jute.maxbuffer"})," in the server side, the server side will get ",e.jsx(n.strong,{children:"java.io.IOException: Len error"})]}),`
`,e.jsxs(n.li,{children:["When ",e.jsx(n.em,{children:"jute.maxbuffer"}),` in the client side is less than the server side, the client wants to read the data
exceeds `,e.jsx(n.em,{children:"jute.maxbuffer"})," in the client side, the client side will get ",e.jsx(n.strong,{children:"java.io.IOException: Unreasonable length"}),`
or `,e.jsx(n.strong,{children:"Packet len is out of range!"})]}),`
`,e.jsx(n.li,{children:`This is really a sanity check. ZooKeeper is designed to store data on the order of kilobytes in size.
In the production environment, increasing this property to exceed the default value is not recommended for the following reasons:`}),`
`,e.jsx(n.li,{children:"Large size znodes cause unwarranted latency spikes, worsen the throughput"}),`
`,e.jsx(n.li,{children:"Large size znodes make the synchronization time between leader and followers unpredictable and non-convergent(sometimes timeout), cause the quorum unstable"}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"jute.maxbuffer.extrasize"}),`:
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.jute.maxbuffer.extrasize"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.7:"}),`
While processing client requests ZooKeeper server adds some additional information into
the requests before persisting it as a transaction. Earlier this additional information size
was fixed to 1024 bytes. For many scenarios, specially scenarios where jute.maxbuffer value
is more than 1 MB and request type is multi, this fixed size was insufficient.
To handle all the scenarios additional information size is increased from 1024 byte
to same as jute.maxbuffer size and also it is made configurable through jute.maxbuffer.extrasize.
Generally this property is not required to be configured as default value is the most optimal value.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"skipACL"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.skipACL"}),`)
Skips ACL checks. This results in a boost in throughput,
but opens up full access to the data tree to everyone.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"quorumListenOnAllIPs"}),` :
When set to true the ZooKeeper server will listen
for connections from its peers on all available IP addresses,
and not only the address configured in the server list of the
configuration file. It affects the connections handling the
ZAB protocol and the Fast Leader Election protocol. Default
value is `,e.jsx(n.strong,{children:"false"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"multiAddress.reachabilityCheckEnabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.multiAddress.reachabilityCheckEnabled"}),`)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Since ZooKeeper 3.6.0 you can also `,e.jsx(n.a,{href:"#cluster-options",children:"specify multiple addresses"}),`
for each ZooKeeper server instance (this can increase availability when multiple physical
network interfaces can be used parallel in the cluster). ZooKeeper will perform ICMP ECHO requests
or try to establish a TCP connection on port 7 (Echo) of the destination host in order to find
the reachable addresses. This happens only if you provide multiple addresses in the configuration.
The reachable check can fail if you hit some ICMP rate-limitation, (e.g. on macOS) when you try to
start a large (e.g. 11+) ensemble members cluster on a single machine for testing.`]}),`
`,e.jsxs(n.p,{children:["Default value is ",e.jsx(n.strong,{children:"true"}),`. By setting this parameter to 'false' you can disable the reachability checks.
Please note, disabling the reachability check will cause the cluster not to be able to reconfigure
itself properly during network problems, so the disabling is advised only during testing.`]}),`
`,e.jsxs(n.p,{children:["This parameter has no effect, unless you enable the MultiAddress feature by setting ",e.jsx(n.em,{children:"multiAddress.enabled=true"}),"."]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"disabling-data-directory-autocreation",children:"Disabling data directory autocreation"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5:"}),` The default
behavior of a ZooKeeper server is to automatically create the
data directory (specified in the configuration file) when
started if that directory does not already exist. This can be
inconvenient and even dangerous in some cases. Take the case
where a configuration change is made to a running server,
wherein the `,e.jsx(n.strong,{children:"dataDir"}),` parameter
is accidentally changed. When the ZooKeeper server is
restarted it will create this non-existent directory and begin
serving - with an empty znode namespace. This scenario can
result in an effective "split brain" situation (i.e. data in
both the new invalid directory and the original valid data
store). As such is would be good to have an option to turn off
this autocreate behavior. In general for production
environments this should be done, unfortunately however the
default legacy behavior cannot be changed at this point and
therefore this must be done on a case by case basis. This is
left to users and to packagers of ZooKeeper distributions.`]}),`
`,e.jsxs(n.p,{children:["When running ",e.jsx(n.strong,{children:"zkServer.sh"}),` autocreate can be disabled
by setting the environment variable `,e.jsx(n.strong,{children:"ZOO_DATADIR_AUTOCREATE_DISABLE"}),` to 1.
When running ZooKeeper servers directly from class files this
can be accomplished by setting `,e.jsx(n.strong,{children:"zookeeper.datadir.autocreate=false"}),` on
the java command line, i.e. `,e.jsx(n.strong,{children:"-Dzookeeper.datadir.autocreate=false"})]}),`
`,e.jsx(n.p,{children:`When this feature is disabled, and the ZooKeeper server
determines that the required directories do not exist it will
generate an error and refuse to start.`}),`
`,e.jsxs(n.p,{children:["A new script ",e.jsx(n.strong,{children:"zkServer-initialize.sh"}),` is provided to
support this new feature. If autocreate is disabled it is
necessary for the user to first install ZooKeeper, then create
the data directory (and potentially txnlog directory), and
then start the server. Otherwise as mentioned in the previous
paragraph the server will not start. Running `,e.jsx(n.strong,{children:"zkServer-initialize.sh"}),` will create the
required directories, and optionally set up the myid file
(optional command line parameter). This script can be used
even if the autocreate feature itself is not used, and will
likely be of use to users as this (setup, including creation
of the myid file) has been an issue for users in the past.
Note that this script ensures the data directories exist only,
it does not create a config file, but rather requires a config
file to be available in order to execute.`]}),`
`,e.jsx(n.h2,{id:"enabling-db-existence-validation",children:"Enabling db existence validation"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.6.0:"}),` The default
behavior of a ZooKeeper server on startup when no data tree
is found is to set zxid to zero and join the quorum as a
voting member. This can be dangerous if some event (e.g. a
rogue 'rm -rf') has removed the data directory while the
server was down since this server may help elect a leader
that is missing transactions. Enabling db existence validation
will change the behavior on startup when no data tree is
found: the server joins the ensemble as a non-voting participant
until it is able to sync with the leader and acquire an up-to-date
version of the ensemble data. To indicate an empty data tree is
expected (ensemble creation), the user should place a file
'initialize' in the same directory as 'myid'. This file will
be detected and deleted by the server on startup.`]}),`
`,e.jsxs(n.p,{children:[`Initialization validation can be enabled when running
ZooKeeper servers directly from class files by setting
`,e.jsx(n.strong,{children:"zookeeper.db.autocreate=false"}),`
on the java command line, i.e.
`,e.jsx(n.strong,{children:"-Dzookeeper.db.autocreate=false"}),`.
Running `,e.jsx(n.strong,{children:"zkServer-initialize.sh"}),`
will create the required initialization file.`]}),`
`,e.jsx(n.h2,{id:"performance-tuning-options",children:"Performance Tuning Options"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5.0:"}),` Several subsystems have been reworked
to improve read throughput. This includes multi-threading of the NIO communication subsystem and
request processing pipeline (Commit Processor). NIO is the default client/server communication
subsystem. Its threading model comprises 1 acceptor thread, 1-N selector threads and 0-M
socket I/O worker threads. In the request processing pipeline the system can be configured
to process multiple read request at once while maintaining the same consistency guarantee
(same-session read-after-write). The Commit Processor threading model comprises 1 main
thread and 0-N worker threads.`]}),`
`,e.jsx(n.p,{children:`The default values are aimed at maximizing read throughput on a dedicated ZooKeeper machine.
Both subsystems need to have sufficient amount of threads to achieve peak read throughput.`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.nio.numSelectorThreads"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.nio.numSelectorThreads"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.0:"}),`
Number of NIO selector threads. At least 1 selector thread required.
It is recommended to use more than one selector for large numbers
of client connections. The default value is sqrt( number of cpu cores / 2 ).`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.nio.numWorkerThreads"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.nio.numWorkerThreads"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.0:"}),`
Number of NIO worker threads. If configured with 0 worker threads, the selector threads
do the socket I/O directly. The default value is 2 times the number of cpu cores.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.commitProcessor.numWorkerThreads"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.commitProcessor.numWorkerThreads"}),`)
`,e.jsx(n.strong,{children:"New in 3.5.0:"}),`
Number of Commit Processor worker threads. If configured with 0 worker threads, the main thread
will process the request directly. The default value is the number of cpu cores.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.commitProcessor.maxReadBatchSize"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.commitProcessor.maxReadBatchSize"}),`)
Max number of reads to process from queuedRequests before switching to processing commits.
If the value < 0 (default), we switch whenever we have a local write, and pending commits.
A high read batch size will delay commit processing, causing stale data to be served.
If reads are known to arrive in fixed size batches then matching that batch size with
the value of this property can smooth queue performance. Since reads are handled in parallel,
one recommendation is to set this property to match `,e.jsx(n.em,{children:"zookeeper.commitProcessor.numWorkerThread"}),`
(default is the number of cpu cores) or lower.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.commitProcessor.maxCommitBatchSize"}),` :
(Java system property only: `,e.jsx(n.strong,{children:"zookeeper.commitProcessor.maxCommitBatchSize"}),`)
Max number of commits to process before processing reads. We will try to process as many
remote/local commits as we can till we reach this count. A high commit batch size will delay
reads while processing more commits. A low commit batch size will favor reads.
It is recommended to only set this property when an ensemble is serving a workload with a high
commit rate. If writes are known to arrive in a set number of batches then matching that
batch size with the value of this property can smooth queue performance. A generic
approach would be to set this value to equal the ensemble size so that with the processing
of each batch the current server will probabilistically handle a write related to one of
its direct clients.
Default is "1". Negative and zero values are not supported.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"znode.container.checkIntervalMs"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),` The
time interval in milliseconds for each check of candidate container
and ttl nodes. Default is "60000".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"znode.container.maxPerMinute"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),` The
maximum number of container and ttl nodes that can be deleted per
minute. This prevents herding during container deletion.
Default is "10000".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"znode.container.maxNeverUsedIntervalMs"}),` :
(Java system property only)
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),` The
maximum interval in milliseconds that a container that has never had
any children is retained. Should be long enough for your client to
create the container, do any needed work and then create children.
Default is "300000"(a.k.a. 5 minutes) since 3.10.0, for earlier versions,
it is "0" which is used to indicate that containers that have never had
any children are never deleted.`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"debug-observability-configurations",children:"Debug Observability Configurations"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.6.0:"})," The following options are introduced to make zookeeper easier to debug."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.messageTracker.BufferSize"}),` :
(Java system property only)
Controls the maximum number of messages stored in `,e.jsx(n.strong,{children:"MessageTracker"}),`. Value should be positive
integers. The default value is 10. `,e.jsx(n.strong,{children:"MessageTracker"})," is introduced in ",e.jsx(n.strong,{children:"3.6.0"}),` to record the
last set of messages between a server (follower or observer) and a leader, when a server
disconnects with leader. These set of messages will then be dumped to zookeeper's log file,
and will help reconstruct the state of the servers at the time of the disconnection and
will be useful for debugging purpose.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.messageTracker.Enabled"}),` :
(Java system property only)
When set to "true", will enable `,e.jsx(n.strong,{children:"MessageTracker"}),` to track and record messages. Default value
is "false".`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"adminserver-configuration",children:"AdminServer configuration"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.10.0:"})," The ",e.jsx(n.a,{href:"#adminserver-configuration",children:"AdminServer"})," will use the following existing properties:"]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"ssl.quorum.ciphersuites"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.ssl.quorum.ciphersuites"}),`)
The enabled cipher suites to be used in TLS negotiation for AdminServer.
Default: Jetty default.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"ssl.quorum.enabledProtocols"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.ssl.quorum.enabledProtocols"}),`)
The enabled protocols to be used in TLS negotiation for AdminServer.
Default: Jetty default.`]}),`
`]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.9.0:"}),` The following
options are used to configure the `,e.jsx(n.a,{href:"#adminserver-configuration",children:"AdminServer"}),"."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.rateLimiterIntervalInMS"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.rateLimiterIntervalInMS"}),`)
The time interval for rate limiting admin command to protect the server.
Defaults to 5 mins.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.snapshot.enabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.snapshot.enabled"}),`)
The flag for enabling the snapshot command. Defaults to true.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.restore.enabled"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.restore.enabled"}),`)
The flag for enabling the restore command. Defaults to true.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.needClientAuth"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.needClientAuth"}),`)
The flag to control whether client auth is needed. Using x509 auth requires true.
Defaults to false.`]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.7.1:"}),` The following
options are used to configure the `,e.jsx(n.a,{href:"#adminserver-configuration",children:"AdminServer"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.forceHttps"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.forceHttps"}),`)
Force AdminServer to use SSL, thus allowing only HTTPS traffic.
Defaults to disabled.
Overwrites `,e.jsx(n.strong,{children:"admin.portUnification"})," settings."]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.6.0:"}),` The following
options are used to configure the `,e.jsx(n.a,{href:"#adminserver-configuration",children:"AdminServer"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.portUnification"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.portUnification"}),`)
Enable the admin port to accept both HTTP and HTTPS traffic.
Defaults to disabled.`]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5.0:"}),` The following
options are used to configure the `,e.jsx(n.a,{href:"#adminserver-configuration",children:"AdminServer"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.enableServer"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.enableServer"}),`)
Set to "false" to disable the AdminServer. By default the
AdminServer is enabled.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.serverAddress"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.serverAddress"}),`)
The address the embedded Jetty server listens on. Defaults to 0.0.0.0.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.serverPort"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.serverPort"}),`)
The port the embedded Jetty server listens on. Defaults to 8080.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.idleTimeout"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.idleTimeout"}),`)
Set the maximum idle time in milliseconds that a connection can wait
before sending or receiving data. Defaults to 30000 ms.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"admin.commandURL"}),` :
(Java system property: `,e.jsx(n.strong,{children:"zookeeper.admin.commandURL"}),`)
The URL for listing and issuing commands relative to the
root URL. Defaults to "/commands".`]}),`
`]}),`
`]})]})}function u(o={}){const{wrapper:n}=o.components||{};return n?e.jsx(n,{...o,children:e.jsx(s,{...o})}):s(o)}function i(o,n){throw new Error("Expected component `"+o+"` to be defined: you likely forgot to import, pass, or provide it.")}export{a as _markdown,u as default,h as extractedReferences,l as frontmatter,c as structuredData,d as toc};
