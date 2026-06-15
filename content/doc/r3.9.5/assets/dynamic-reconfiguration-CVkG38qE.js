import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let o=`Prior to the 3.5.0 release, the membership and all other configuration
parameters of ZooKeeper were static — loaded during boot and immutable at
runtime. Operators resorted to "rolling restarts" — a manually intensive
and error-prone method of changing the configuration that has caused data
loss and inconsistency in production.

Starting with 3.5.0, rolling restarts are no longer needed.
ZooKeeper comes with full support for automated configuration changes: the
set of ZooKeeper servers, their roles (participant / observer), all ports,
and even the quorum system can be changed dynamically, without service
interruption and while maintaining data consistency. Reconfigurations are
performed immediately, just like other operations in ZooKeeper. Multiple
changes can be done using a single reconfiguration command. The dynamic
reconfiguration functionality does not limit operation concurrency, does
not require client operations to be stopped during reconfigurations, has a
very simple interface for administrators, and adds no complexity to other
client operations.

New client-side features allow clients to find out about configuration
changes and to update the connection string (list of servers and their
client ports) stored in their ZooKeeper handle. A probabilistic algorithm
is used to rebalance clients across the new configuration servers while
keeping the extent of client migrations proportional to the change in
ensemble membership.

This document provides the administrator manual for reconfiguration.
For a detailed description of the reconfiguration algorithms, performance
measurements, and more, see:

*Shraer, A., Reed, B., Malkhi, D., Junqueira, F. Dynamic Reconfiguration of Primary/Backup Clusters. In USENIX Annual Technical Conference (ATC) (2012), 425–437.*
Links: [paper (pdf)](https://www.usenix.org/system/files/conference/atc12/atc12-final74.pdf), [slides (pdf)](https://www.usenix.org/sites/default/files/conference/protected-files/shraer_atc12_slides.pdf), [video](https://www.usenix.org/conference/atc12/technical-sessions/presentation/shraer), [hadoop summit slides](http://www.slideshare.net/Hadoop_Summit/dynamic-reconfiguration-of-zookeeper)

<Callout type="warn">
  Starting with 3.5.3, the dynamic reconfiguration feature is disabled by
  default and must be explicitly turned on via the \`reconfigEnabled\`
  configuration option.
</Callout>

## Changes to Configuration Format

### Specifying the Client Port

A client port is the port on which the server accepts plaintext (non-TLS) client connection requests.
A secure client port is the port on which the server accepts TLS client connection requests.

Starting with 3.5.0, the \`clientPort\` and \`clientPortAddress\` configuration parameters should no longer be used in \`zoo.cfg\`.

Starting with 3.10.0, the \`secureClientPort\` and \`secureClientPortAddress\` configuration parameters should no longer be used in \`zoo.cfg\`.

Instead, this information is now part of the server keyword specification:

\`\`\`
server.<positive id> = <address1>:<quorum port>:<leader election port>[:role];[[<client port address>:]<client port>][;[<secure client port address>:]<secure client port>]
\`\`\`

* \\[New in ZK 3.10.0] The client port specification is optional and is to the right of the
  first semicolon. The secure client port specification is also optional and is to the right
  of the second semicolon. However, both the client port and secure client port specification
  cannot be omitted — at least one of them must be present. If the user intends to omit the client
  port specification and provide only the secure client port (TLS-only server), a second
  semicolon should still be specified to indicate an empty client port specification (see last
  example below). In either spec, the port address is optional and defaults to \`0.0.0.0\`.
* Role is also optional — it can be \`participant\` or \`observer\` (\`participant\` by default).

Examples of legal server statements:

\`\`\`
server.5 = 125.23.63.23:1234:1235;1236                                             (non-TLS server)
server.5 = 125.23.63.23:1234:1235;1236;1237                                        (non-TLS + TLS server)
server.5 = 125.23.63.23:1234:1235;;1237                                            (TLS-only server)
server.5 = 125.23.63.23:1234:1235:participant;1236                                 (non-TLS server)
server.5 = 125.23.63.23:1234:1235:observer;1236                                    (non-TLS server)
server.5 = 125.23.63.23:1234:1235;125.23.63.24:1236                                (non-TLS server)
server.5 = 125.23.63.23:1234:1235:participant;125.23.63.23:1236                    (non-TLS server)
server.5 = 125.23.63.23:1234:1235:participant;125.23.63.23:1236;125.23.63.23:1237  (non-TLS + TLS server)
server.5 = 125.23.63.23:1234:1235:participant;;125.23.63.23:1237                   (TLS-only server)
\`\`\`

### Specifying Multiple Server Addresses

Since ZooKeeper 3.6.0 it is possible to specify multiple addresses for each
ZooKeeper server (see [ZOOKEEPER-3188](https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188)).
This helps to increase availability and adds network-level resiliency to ZooKeeper. When multiple
physical network interfaces are used for the servers, ZooKeeper is able to bind on all interfaces
and switch to a working interface at runtime in case of a network error. Multiple addresses are
separated by a pipe (\`|\`) character.

Examples of valid configurations using multiple addresses:

\`\`\`
server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889;2188
server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889|zoo2-net3:2890:3890;2188
server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889;zoo2-net1:2188
server.2=zoo2-net1:2888:3888:observer|zoo2-net2:2889:3889:observer;2188
\`\`\`

### The \`standaloneEnabled\` Flag

Prior to 3.5.0, one could run ZooKeeper in Standalone mode or in
Distributed mode. These are separate implementation stacks and switching
between them at runtime is not possible. By default (for backward
compatibility) \`standaloneEnabled\` is set to \`true\`. The consequence is that
if started with a single server the ensemble will not be allowed to grow,
and if started with more than one server it will not be allowed to shrink
below two participants.

Setting the flag to \`false\` instructs the system to run the Distributed
software stack even if there is only a single participant in the ensemble.
To achieve this the static configuration file should contain:

\`\`\`
standaloneEnabled=false
\`\`\`

With this setting it is possible to start a ZooKeeper ensemble containing
a single participant and to dynamically grow it by adding more servers.
Similarly, it is possible to shrink an ensemble so that just a single
participant remains by removing servers.

Since running the Distributed mode allows more flexibility, we recommend
setting the flag to \`false\`. The legacy Standalone mode is expected to be
deprecated in the future.

### The \`reconfigEnabled\` Flag

Starting with 3.5.0 and prior to 3.5.3, there is no way to disable
dynamic reconfiguration. To address the security concern that a malicious
actor could make arbitrary changes to the ensemble configuration, the
\`reconfigEnabled\` option was introduced in 3.5.3. Any attempt
to reconfigure a cluster through the reconfig API — with or without authentication —
will fail by default unless \`reconfigEnabled\` is set to \`true\`.

To enable reconfiguration, add the following to \`zoo.cfg\`:

\`\`\`
reconfigEnabled=true
\`\`\`

### Dynamic Configuration File

Starting with 3.5.0, ZooKeeper distinguishes between dynamic configuration
parameters (which can be changed during runtime) and static configuration
parameters (which are read from a configuration file at boot and do not change
during execution). The dynamic configuration keywords are: \`server\`, \`group\`,
and \`weight\`.

Dynamic configuration parameters are stored in a separate file on the server
(the dynamic configuration file), linked from the static config file using the
\`dynamicConfigFile\` keyword.

#### Example 1

**\`zoo_replicated1.cfg\`**

\`\`\`
tickTime=2000
dataDir=/zookeeper/data/zookeeper1
initLimit=5
syncLimit=2
dynamicConfigFile=/zookeeper/conf/zoo_replicated1.cfg.dynamic
\`\`\`

**\`zoo_replicated1.cfg.dynamic\`**

\`\`\`
server.1=125.23.63.23:2780:2783:participant;2791
server.2=125.23.63.24:2781:2784:participant;2792
server.3=125.23.63.25:2782:2785:participant;2793
\`\`\`

When the ensemble configuration changes, the static configuration parameters
remain the same. The dynamic parameters are pushed by ZooKeeper and overwrite
the dynamic configuration files on all servers. Thus the dynamic configuration
files on different servers are usually identical (they can differ momentarily
while a reconfiguration is in progress or before a new configuration has
propagated to all servers). Once created, the dynamic configuration file should
not be manually altered — changes are made only through the reconfiguration
commands described below. Changing the config of an offline cluster could
result in an inconsistency with configuration information stored in the
ZooKeeper log and is therefore highly discouraged.

#### Example 2

Users may prefer to initially specify a single configuration file. The
following is also legal:

**\`zoo_replicated1.cfg\`**

\`\`\`
tickTime=2000
dataDir=/zookeeper/data/zookeeper1
initLimit=5
syncLimit=2
clientPort=
\`\`\`

The configuration files on each server will be automatically split into
dynamic and static files if they are not already in this format. The
configuration above will be automatically transformed into the two files in
Example 1. Note that the \`clientPort\` and \`clientPortAddress\` lines (if
specified) will be automatically removed during this process if they are
redundant (as in the example above). The original static configuration file
is backed up (as a \`.bak\` file).

### Backward Compatibility

The old configuration format is still supported. For example, the
following configuration file is acceptable (but not recommended):

**\`zoo_replicated1.cfg\`**

\`\`\`
tickTime=2000
dataDir=/zookeeper/data/zookeeper1
initLimit=5
syncLimit=2
clientPort=2791
server.1=125.23.63.23:2780:2783:participant
server.2=125.23.63.24:2781:2784:participant
server.3=125.23.63.25:2782:2785:participant
\`\`\`

During boot, a dynamic configuration file is created containing the dynamic
part of the configuration as explained earlier. In this case, however, the
line \`clientPort=2791\` will remain in the static configuration file of
server 1 since it is not redundant — it was not specified as part of
\`server.1=...\` using the format described in
[Changes to Configuration Format](#changes-to-configuration-format). If a
reconfiguration is invoked that sets the client port of server 1, \`clientPort=2791\`
is removed from the static configuration file (the dynamic file then contains
this information as part of the server 1 specification).

## Upgrading to 3.5.0

Upgrading a running ZooKeeper ensemble to 3.5.0 should be done only
after upgrading to the 3.4.6 release. This is only necessary for rolling
upgrades — if you are fine with shutting down the system completely, you
do not need to go through 3.4.6. If you attempt a rolling upgrade without
going through 3.4.6 (for example from 3.4.5), you may get the following error:

\`\`\`
2013-01-30 11:32:10,663 [myid:2] - INFO [localhost/127.0.0.1:2784:QuorumCnxManager$Listener@498] - Received connection request /127.0.0.1:60876
2013-01-30 11:32:10,663 [myid:2] - WARN [localhost/127.0.0.1:2784:QuorumCnxManager@349] - Invalid server id: -65536
\`\`\`

During a rolling upgrade, each server is taken down in turn and rebooted
with the new 3.5.0 binaries. Before starting a server with 3.5.0 binaries,
we highly recommend updating the configuration file so that all server
statements \`server.x=...\` contain client ports (see
[Specifying the Client Port](#specifying-the-client-port)). You may leave
the configuration in a single file and may also leave the
\`clientPort\`/\`clientPortAddress\` statements (although if you specify client
ports in the new format, these statements become redundant).

## Dynamic Reconfiguration of the ZooKeeper Ensemble

The ZooKeeper Java and C APIs were extended with \`getConfig\` and \`reconfig\`
commands that facilitate reconfiguration. Both commands have a synchronous
(blocking) variant and an asynchronous one. The examples below use the Java
CLI, but you can similarly use the C CLI or invoke the commands directly from
a program just like any other ZooKeeper command.

### API

There are two sets of APIs for both Java and C clients.

* **Reconfiguration API:** Used to reconfigure the ZooKeeper cluster.
  Starting with 3.5.3, reconfiguration Java APIs are moved into the \`ZooKeeperAdmin\`
  class from the \`ZooKeeper\` class. Use of this API requires ACL setup and user
  authentication (see [Security](#security) for more information).

* **Get Configuration API:** Used to retrieve ZooKeeper cluster configuration
  information stored in the \`/zookeeper/config\` znode. Use of this API does not
  require specific setup or authentication, because \`/zookeeper/config\` is readable
  by any user.

### Security

Prior to **3.5.3**, there is no enforced security mechanism over reconfig,
so any ZooKeeper client that can connect to the ensemble has the ability to
change its state via reconfig. It is thus possible for a malicious client to
add a compromised server to an ensemble or remove legitimate servers, which
can be a security vulnerability.

Starting from **3.5.3**, access control over reconfig is enforced: only a
specific set of explicitly configured users can use reconfig commands or APIs,
and the ZooKeeper cluster must have authentication enabled so that clients can
be authenticated.

An escape hatch is provided for users operating in a secured environment (e.g.
behind a company firewall) who want to use reconfiguration without the overhead
of configuring an explicit authorized user list: setting
\`skipACL\` to \`yes\` skips
ACL checks and allows any user to reconfigure the cluster.

* **Access Control:** The dynamic configuration is stored in the special znode
  \`ZooDefs.CONFIG_NODE = /zookeeper/config\`. This node is read-only for all users
  by default, except the super user and users explicitly configured for write access.
  Clients that need to use reconfig commands or the reconfig API must be configured
  with write access to \`CONFIG_NODE\`. Additional users can be granted write access
  through the superuser by setting an ACL with write permission. Examples of how to
  set up ACLs and use the reconfiguration API with authentication can be found in
  \`ReconfigExceptionTest.java\` and \`TestReconfigServer.cc\`.

* **Authentication:** Authentication is orthogonal to access control and is
  delegated to ZooKeeper's pluggable authentication schemes. See
  [ZooKeeper and SASL](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zookeeper+and+SASL)
  for more details.

* **Disable ACL check:** ZooKeeper supports the
  \`skipACL\` option such that
  ACL checks are completely skipped when \`skipACL\` is set to \`yes\`. In such cases
  any unauthenticated user can use the reconfig API.

### Retrieving the Current Dynamic Configuration

The dynamic configuration is stored in a special znode
\`ZooDefs.CONFIG_NODE = /zookeeper/config\`. The \`config\` CLI command reads
this znode (it is essentially a wrapper around \`get /zookeeper/config\`). As
with normal reads, to retrieve the latest committed value you should do a
\`sync\` first.

\`\`\`
[zk: 127.0.0.1:2791(CONNECTED) 3] config
server.1=localhost:2780:2783:participant;localhost:2791
server.2=localhost:2781:2784:participant;localhost:2792
server.3=localhost:2782:2785:participant;localhost:2793
\`\`\`

Notice the last line of the output — this is the configuration version. The
version equals the zxid of the reconfiguration command that created this
configuration. The version of the first established configuration equals the
zxid of the NEWLEADER message sent by the first successfully established leader.
When a configuration is written to a dynamic configuration file, the version
automatically becomes part of the filename and the static configuration file is
updated with the path to the new dynamic configuration file. Configuration files
corresponding to earlier versions are retained for backup purposes.

During boot, the version (if it exists) is extracted from the filename. The
version should never be manually altered — it is used by the system to determine
which configuration is most up-to-date, and manipulating it can result in data
loss and inconsistency.

Like the \`get\` command, the \`config\` CLI command accepts the \`-w\` flag for
setting a watch on the znode and the \`-s\` flag for displaying its stats. It
additionally accepts a \`-c\` flag that outputs only the version and the client
connection string for the current configuration. For example:

\`\`\`
[zk: 127.0.0.1:2791(CONNECTED) 17] config -c
400000003 localhost:2791,localhost:2793,localhost:2792
\`\`\`

Note that when using the API directly, this command is called \`getConfig\`.

As any read command, it returns the configuration known to the follower your
client is connected to, which may be slightly out-of-date. Use the \`sync\`
command for stronger guarantees. For example using the Java API:

\`\`\`java
zk.sync(ZooDefs.CONFIG_NODE, void_callback, context);
zk.getConfig(watcher, callback, context);
\`\`\`

Note: in 3.5.0 it doesn't matter which path is passed to \`sync()\` since all
server state is brought up to date with the leader (a different path could be
used instead of \`ZooDefs.CONFIG_NODE\`). However, this may change in the future.

### Modifying the Current Dynamic Configuration

Configuration is modified through the \`reconfig\` command. There are two
modes: incremental and non-incremental (bulk). The non-incremental mode
specifies the complete new dynamic configuration. The incremental mode specifies
changes to the current configuration. The \`reconfig\` command returns the new
configuration.

A few examples can be found in: \`ReconfigTest.java\`, \`ReconfigRecoveryTest.java\`,
and \`TestReconfigServer.cc\`.

#### General

**Removing servers:** Any server can be removed, including the leader (although
removing the leader will result in a short unavailability, see Figures 6 and 8 in
the [paper](https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters)).
The server will not be shut down automatically. Instead, it becomes a
"non-voting follower" — similar to an observer in that its votes don't count
towards the quorum, but unlike an observer, a non-voting follower still sees
operation proposals and ACKs them. Thus a non-voting follower has a more
significant negative effect on throughput compared to an observer.
Non-voting follower mode should only be used temporarily before shutting the
server down or adding it back as a follower or observer. Servers are not
shut down automatically for two main reasons: first, to avoid immediately
disconnecting all connected clients and causing a flood of reconnection requests
to other servers; second, because removing a server may sometimes be necessary
as a step to change it from observer to participant (see
[Additional comments](#additional-comments)).

Note that the new configuration must have some minimum number of participants
to be considered legal. If the proposed change would leave fewer than 2
participants and standalone mode is enabled (\`standaloneEnabled=true\`, see
[The \`standaloneEnabled\` Flag](#the-standaloneenabled-flag)), the reconfig
will not be processed (\`BadArgumentsException\`). If standalone mode is disabled
(\`standaloneEnabled=false\`) then 1 or more participants is legal.

**Adding servers:** Before invoking a reconfiguration, the administrator must
ensure that a quorum (majority) of participants from the new configuration are
already connected and synced with the current leader. To achieve this, a new
joining server must be connected to the leader before it officially becomes part
of the ensemble. This is done by starting the joining server with an initial
server list that is not a legal configuration but (a) contains the joiner, and
(b) gives the joiner enough information to find and connect to the current leader.
A few safe options for doing this:

1. The initial configuration of joiners consists of servers in the last committed
   configuration plus one or more joiners, where **joiners are listed as observers.**
   For example, if servers D and E are added at the same time to (A, B, C) and C
   is being removed, D's initial config could be (A, B, C, D) or (A, B, C, D, E)
   with D and E listed as observers. **Note that listing joiners as observers does
   not actually make them observers** — it only prevents them from accidentally
   forming a quorum with other joiners. Instead, they contact the servers in the
   current configuration and adopt the last committed configuration (A, B, C).
   Configuration files of joiners are backed up and replaced automatically.
   After connecting to the current leader, joiners become non-voting followers until
   the system is reconfigured and they are added to the ensemble.

2. The initial configuration of each joiner consists of servers in the last committed
   configuration **plus the joiner itself, listed as a participant.** For example,
   to add a new server D to (A, B, C), start D with an initial config consisting of
   (A, B, C, D). If both D and E are added at the same time, D's initial config
   could be (A, B, C, D) and E's could be (A, B, C, E). Never list more than one
   joiner as participant in the initial configuration (see warning below).

3. Whether listing the joiner as an observer or participant, it is also fine not
   to list all current configuration servers, as long as the current leader is
   included. For example, when adding D, it could be started with just (A, D) if
   A is the current leader. However, this is more fragile since if A fails before
   D officially joins, D has no other servers to contact and the administrator
   must restart D with another server list.

<Callout type="warn">
  Never specify more than one joining server in the same initial configuration
  as participants. The joining servers do not know they are joining an existing
  ensemble — if multiple joiners are listed as participants they may form an
  independent quorum, creating a split-brain situation and processing operations
  independently from the main ensemble. It is safe to list multiple joiners as
  observers in an initial config.
</Callout>

If the configuration of existing servers changes or they become unavailable before
the joiner succeeds in connecting and learning about configuration changes, the
joiner may need to be restarted with an updated configuration file.

Finally, note that once connected to the leader, a joiner adopts the last committed
configuration (in which it is absent), and the initial config is backed up before
being rewritten. If the joiner restarts in this state it will not be able to boot
since it is absent from its configuration file — you will need to specify an initial
configuration again.

**Modifying server parameters:** Any of the ports or the role (participant/observer)
of a server can be modified by adding it to the ensemble with different parameters.
This works in both incremental and bulk reconfiguration modes — it is not necessary
to remove the server and re-add it; just specify the new parameters as if the server
is not yet in the system. The server will detect the configuration change and perform
the necessary adjustments. See an example in [Incremental mode](#incremental-mode)
and an exception in [Additional comments](#additional-comments).

It is also possible to change the Quorum System used by the ensemble (for example,
change from a Majority Quorum System to a Hierarchical Quorum System on the fly).
This is only allowed using the bulk (non-incremental) reconfiguration mode. Incremental
reconfiguration only works with the Majority Quorum System. Bulk reconfiguration
works with both Hierarchical and Majority Quorum Systems.

**Performance impact:** There is practically no performance impact when removing a
follower, since it is not automatically shut down (the effect of removal is that the
server's votes are no longer counted). When adding a server, there is no leader change
and no noticeable performance disruption. For details and graphs see Figures 6, 7 and
8 in the [paper](https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters).

The most significant disruption occurs when a leader change is triggered, in the
following cases:

1. Leader is removed from the ensemble.
2. Leader's role is changed from participant to observer.
3. The port used by the leader to send transactions to others (quorum port) is modified.

In these cases a leader hand-off is performed where the old leader nominates a new
leader. The resulting unavailability is usually shorter than when a leader crashes
since failure detection is unnecessary and electing a new leader can usually be
avoided during a hand-off (see Figures 6 and 8 in the [paper](https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters)).

When the client port of a server is modified, existing client connections are not
dropped. New connections to the server will use the new client port.

**Progress guarantees:** Up to the invocation of the reconfig operation, a quorum of
the old configuration must be available and connected for ZooKeeper to make progress.
Once reconfig is invoked, a quorum of both the old and new configurations must be
available. The final transition happens once (a) the new configuration is activated and
(b) all operations scheduled before the new configuration was activated by the leader
are committed. Once both (a) and (b) have happened, only a quorum of the new
configuration is required. Note that neither (a) nor (b) are visible to a client —
when a reconfiguration operation commits it only means that an activation message was
sent by the leader, not that a quorum of the new configuration has received it.
To ensure both (a) and (b) have occurred (for example, before safely shutting down
removed servers), invoke an update (\`set-data\` or another quorum operation, but not
\`sync\`) and wait for it to commit.

#### Incremental Mode

The incremental mode allows adding and removing servers from the current
configuration. Multiple changes are allowed at once. For example:

\`\`\`bash
> reconfig -remove 3 -add \\
server.5=125.23.63.23:1234:1235;1236
\`\`\`

Both the add and remove options take comma-separated arguments (no spaces):

\`\`\`bash
> reconfig -remove 3,4 -add \\
server.5=localhost:2111:2112;2113,6=localhost:2114:2115:observer;2116
\`\`\`

The format of the server statement is exactly as described in
[Specifying the Client Port](#specifying-the-client-port) and includes the client
port. Note that \`5=\` can be used as a shorthand for \`server.5=\`. In the example
above, if server 5 is already in the system with different ports or is not an
observer, it is updated — once the configuration commits it becomes an observer
using the new ports. This is an easy way to turn participants into observers and
vice versa, or change any ports, without rebooting the server.

ZooKeeper supports two types of Quorum Systems: the simple Majority system (where
the leader commits operations after receiving ACKs from a majority of voters) and a
more complex Hierarchical system (where votes of different servers have different
weights and servers are divided into voting groups). Incremental reconfiguration is
currently allowed only if the last proposed configuration uses a Majority Quorum
System (\`BadArgumentsException\` is thrown otherwise).

Incremental mode — examples using the Java API:

\`\`\`java
List<String> leavingServers = new ArrayList<String>();
leavingServers.add("1");
leavingServers.add("2");
byte[] config = zk.reconfig(null, leavingServers, null, -1, new Stat());

List<String> leavingServers = new ArrayList<String>();
List<String> joiningServers = new ArrayList<String>();
leavingServers.add("1");
joiningServers.add("server.4=localhost:1234:1235;1236");
byte[] config = zk.reconfig(joiningServers, leavingServers, null, -1, new Stat());

String configStr = new String(config);
System.out.println(configStr);
\`\`\`

There is also an asynchronous API, and an API accepting comma-separated Strings
instead of \`List<String>\`. See
\`src/java/main/org/apache/zookeeper/ZooKeeper.java\`.

#### Non-incremental Mode

The non-incremental mode accepts a complete specification of the new dynamic
configuration. The new configuration can be given inline or read from a file:

\`\`\`bash
> reconfig -file newconfig.cfg
\`\`\`

\`newconfig.cfg\` is a dynamic config file — see [Dynamic Configuration File](#dynamic-configuration-file).

\`\`\`bash
> reconfig -members \\
server.1=125.23.63.23:2780:2783:participant;2791,server.2=125.23.63.24:2781:2784:participant;2792,server.3=125.23.63.25:2782:2785:participant;2793
\`\`\`

The new configuration may use a different Quorum System. For example, you may
specify a Hierarchical Quorum System even if the current ensemble uses a Majority
Quorum System.

Bulk mode — example using the Java API:

\`\`\`java
List<String> newMembers = new ArrayList<String>();
newMembers.add("server.1=1111:1234:1235;1236");
newMembers.add("server.2=1112:1237:1238;1239");
newMembers.add("server.3=1114:1240:1241:observer;1242");

byte[] config = zk.reconfig(null, null, newMembers, -1, new Stat());

String configStr = new String(config);
System.out.println(configStr);
\`\`\`

There is also an asynchronous API, and an API accepting a comma-separated String
containing the new members instead of \`List<String>\`. See
\`src/java/main/org/apache/zookeeper/ZooKeeper.java\`.

#### Conditional Reconfig

Sometimes (especially in non-incremental mode) a proposed configuration depends
on what the client believes to be the current configuration, and should only be
applied to that configuration. The \`reconfig\` succeeds only if the last
configuration at the leader has the specified version:

\`\`\`bash
> reconfig -file <filename> -v <version>
\`\`\`

In the Java examples above, instead of \`-1\` you can specify a configuration
version to condition the reconfiguration.

#### Error Conditions

In addition to normal ZooKeeper error conditions, a reconfiguration may fail for
the following reasons:

1. Another reconfig is currently in progress (\`ReconfigInProgress\`).
2. The proposed change would leave fewer than 2 participants and standalone mode
   is enabled, or, if standalone mode is disabled, fewer than 1 participant would
   remain (\`BadArgumentsException\`).
3. No quorum of the new configuration was connected and up-to-date with the leader
   when reconfiguration processing began (\`NewConfigNoQuorum\`).
4. \`-v x\` was specified but the latest configuration version \`y\` is not \`x\`
   (\`BadVersionException\`).
5. An incremental reconfiguration was requested but the last configuration at the
   leader uses a Quorum System other than Majority (\`BadArgumentsException\`).
6. Syntax error (\`BadArgumentsException\`).
7. I/O exception when reading the configuration from a file (\`BadArgumentsException\`).

Most of these are illustrated by test cases in \`ReconfigFailureCases.java\`.

#### Additional Comments

**Liveness:** To understand the difference between incremental and non-incremental
reconfiguration, suppose client C1 adds server D while a different client C2 adds
server E. With non-incremental mode, each client first invokes \`config\` to find out
the current configuration, then locally creates a new server list by adding its own
suggested server. After both reconfigurations complete, only one of D or E will be
added (not both), depending on which request arrives second at the leader. The other
client can repeat the process until its change takes effect. This guarantees
system-wide progress (for one client) but not for every client. C2 may use
[Conditional reconfig](#conditional-reconfig) to avoid blindly overwriting C1's
configuration if C1's request arrived first.

With incremental reconfiguration, both changes take effect as they are applied by
the leader one after the other to the current configuration. Since both clients are
guaranteed to make progress, this method guarantees stronger liveness. In practice,
multiple concurrent reconfigurations are probably rare. Non-incremental reconfiguration
is currently the only way to dynamically change the Quorum System. Incremental
reconfiguration is currently only allowed with the Majority Quorum System.

**Changing an observer into a follower:** Changing a voting server into an observer
may fail if fewer than the minimal allowed number of participants would remain (error 2).
Converting an observer into a participant may sometimes fail for a more subtle reason.
Suppose the current configuration is (A, B, C, D), where A is the leader, B and C are
followers, and D is an observer, and B has crashed. If a reconfiguration makes D a
follower, it will fail with error 3 since a majority of voters in the new configuration
(any 3 voters) must be connected and up-to-date with the leader. An observer cannot
acknowledge the history prefix sent during reconfiguration and therefore does not count
towards the 3 required servers. In this case, a client can achieve the task with two
reconfig commands: first remove D from the configuration, then add it back as a
participant. During the intermediate state D is a non-voting follower and can ACK the
state transfer performed during the second reconfig command.

## Rebalancing Client Connections

When a ZooKeeper cluster is started and each client is given the same connection
string, the client randomly chooses a server to connect to, making the expected
number of client connections per server equal across all servers. ZooKeeper preserves
this property when the set of servers changes through reconfiguration (see Sections
4 and 5.1 in the [paper](https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters)).

For the method to work, all clients must subscribe to configuration changes by setting
a watch on \`/zookeeper/config\` — either directly or through the \`getConfig\` API. When
the watch is triggered, the client should read the new configuration by invoking
\`sync\` and \`getConfig\`, and if the configuration is indeed new, invoke \`updateServerList\`.
To avoid mass client migration at the same time, each client should sleep a random
short period before invoking \`updateServerList\`.

A few examples can be found in \`StaticHostProviderTest.java\` and \`TestReconfig.cc\`.

Example (simplified to illustrate the general idea, not a production recipe):

\`\`\`java
public void process(WatchedEvent event) {
    synchronized (this) {
        if (event.getType() == EventType.None) {
            connected = (event.getState() == KeeperState.SyncConnected);
            notifyAll();
        } else if (event.getPath() != null && event.getPath().equals(ZooDefs.CONFIG_NODE)) {
            // in prod code never block the event thread!
            zk.sync(ZooDefs.CONFIG_NODE, this, null);
            zk.getConfig(this, this, null);
        }
    }
}

public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
    if (path != null && path.equals(ZooDefs.CONFIG_NODE)) {
        String config[] = ConfigUtils.getClientConfigStr(new String(data)).split(" ");   // similar to config -c
        long version = Long.parseLong(config[0], 16);
        if (this.configVersion == null) {
            this.configVersion = version;
        } else if (version > this.configVersion) {
            hostList = config[1];
            try {
                // not blocking, but may cause the client to close the socket and migrate
                // to a different server; in practice wait a short random period so clients
                // migrate at different times
                zk.updateServerList(hostList);
            } catch (IOException e) {
                System.err.println("Error updating server list");
                e.printStackTrace();
            }
            this.configVersion = version;
        }
    }
}
\`\`\`
`,l={title:"Dynamic Reconfiguration",description:"How to use ZooKeeper's dynamic reconfiguration support (available since 3.5.0) to change ensemble membership, server roles, ports, and the quorum system at runtime without service interruption."},c=[{href:"https://www.usenix.org/system/files/conference/atc12/atc12-final74.pdf"},{href:"https://www.usenix.org/sites/default/files/conference/protected-files/shraer_atc12_slides.pdf"},{href:"https://www.usenix.org/conference/atc12/technical-sessions/presentation/shraer"},{href:"http://www.slideshare.net/Hadoop_Summit/dynamic-reconfiguration-of-zookeeper"},{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188"},{href:"#changes-to-configuration-format"},{href:"#specifying-the-client-port"},{href:"#security"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zookeeper+and+SASL"},{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters"},{href:"#additional-comments"},{href:"#the-standaloneenabled-flag"},{href:"#incremental-mode"},{href:"#additional-comments"},{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters"},{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters"},{href:"#specifying-the-client-port"},{href:"#dynamic-configuration-file"},{href:"#conditional-reconfig"},{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters"}],h={contents:[{heading:void 0,content:`Prior to the 3.5.0 release, the membership and all other configuration
parameters of ZooKeeper were static — loaded during boot and immutable at
runtime. Operators resorted to "rolling restarts" — a manually intensive
and error-prone method of changing the configuration that has caused data
loss and inconsistency in production.`},{heading:void 0,content:`Starting with 3.5.0, rolling restarts are no longer needed.
ZooKeeper comes with full support for automated configuration changes: the
set of ZooKeeper servers, their roles (participant / observer), all ports,
and even the quorum system can be changed dynamically, without service
interruption and while maintaining data consistency. Reconfigurations are
performed immediately, just like other operations in ZooKeeper. Multiple
changes can be done using a single reconfiguration command. The dynamic
reconfiguration functionality does not limit operation concurrency, does
not require client operations to be stopped during reconfigurations, has a
very simple interface for administrators, and adds no complexity to other
client operations.`},{heading:void 0,content:`New client-side features allow clients to find out about configuration
changes and to update the connection string (list of servers and their
client ports) stored in their ZooKeeper handle. A probabilistic algorithm
is used to rebalance clients across the new configuration servers while
keeping the extent of client migrations proportional to the change in
ensemble membership.`},{heading:void 0,content:`This document provides the administrator manual for reconfiguration.
For a detailed description of the reconfiguration algorithms, performance
measurements, and more, see:`},{heading:void 0,content:`Shraer, A., Reed, B., Malkhi, D., Junqueira, F. Dynamic Reconfiguration of Primary/Backup Clusters. In USENIX Annual Technical Conference (ATC) (2012), 425–437.
Links: paper (pdf), slides (pdf), video, hadoop summit slides`},{heading:void 0,content:"type: warn"},{heading:void 0,content:`Starting with 3.5.3, the dynamic reconfiguration feature is disabled by
default and must be explicitly turned on via the reconfigEnabled
configuration option.`},{heading:"specifying-the-client-port",content:`A client port is the port on which the server accepts plaintext (non-TLS) client connection requests.
A secure client port is the port on which the server accepts TLS client connection requests.`},{heading:"specifying-the-client-port",content:"Starting with 3.5.0, the clientPort and clientPortAddress configuration parameters should no longer be used in zoo.cfg."},{heading:"specifying-the-client-port",content:"Starting with 3.10.0, the secureClientPort and secureClientPortAddress configuration parameters should no longer be used in zoo.cfg."},{heading:"specifying-the-client-port",content:"Instead, this information is now part of the server keyword specification:"},{heading:"specifying-the-client-port",content:`[New in ZK 3.10.0] The client port specification is optional and is to the right of the
first semicolon. The secure client port specification is also optional and is to the right
of the second semicolon. However, both the client port and secure client port specification
cannot be omitted — at least one of them must be present. If the user intends to omit the client
port specification and provide only the secure client port (TLS-only server), a second
semicolon should still be specified to indicate an empty client port specification (see last
example below). In either spec, the port address is optional and defaults to 0.0.0.0.`},{heading:"specifying-the-client-port",content:"Role is also optional — it can be participant or observer (participant by default)."},{heading:"specifying-the-client-port",content:"Examples of legal server statements:"},{heading:"specifying-multiple-server-addresses",content:`Since ZooKeeper 3.6.0 it is possible to specify multiple addresses for each
ZooKeeper server (see ZOOKEEPER-3188).
This helps to increase availability and adds network-level resiliency to ZooKeeper. When multiple
physical network interfaces are used for the servers, ZooKeeper is able to bind on all interfaces
and switch to a working interface at runtime in case of a network error. Multiple addresses are
separated by a pipe (|) character.`},{heading:"specifying-multiple-server-addresses",content:"Examples of valid configurations using multiple addresses:"},{heading:"the-standaloneenabled-flag",content:`Prior to 3.5.0, one could run ZooKeeper in Standalone mode or in
Distributed mode. These are separate implementation stacks and switching
between them at runtime is not possible. By default (for backward
compatibility) standaloneEnabled is set to true. The consequence is that
if started with a single server the ensemble will not be allowed to grow,
and if started with more than one server it will not be allowed to shrink
below two participants.`},{heading:"the-standaloneenabled-flag",content:`Setting the flag to false instructs the system to run the Distributed
software stack even if there is only a single participant in the ensemble.
To achieve this the static configuration file should contain:`},{heading:"the-standaloneenabled-flag",content:`With this setting it is possible to start a ZooKeeper ensemble containing
a single participant and to dynamically grow it by adding more servers.
Similarly, it is possible to shrink an ensemble so that just a single
participant remains by removing servers.`},{heading:"the-standaloneenabled-flag",content:`Since running the Distributed mode allows more flexibility, we recommend
setting the flag to false. The legacy Standalone mode is expected to be
deprecated in the future.`},{heading:"the-reconfigenabled-flag",content:`Starting with 3.5.0 and prior to 3.5.3, there is no way to disable
dynamic reconfiguration. To address the security concern that a malicious
actor could make arbitrary changes to the ensemble configuration, the
reconfigEnabled option was introduced in 3.5.3. Any attempt
to reconfigure a cluster through the reconfig API — with or without authentication —
will fail by default unless reconfigEnabled is set to true.`},{heading:"the-reconfigenabled-flag",content:"To enable reconfiguration, add the following to zoo.cfg:"},{heading:"dynamic-configuration-file",content:`Starting with 3.5.0, ZooKeeper distinguishes between dynamic configuration
parameters (which can be changed during runtime) and static configuration
parameters (which are read from a configuration file at boot and do not change
during execution). The dynamic configuration keywords are: server, group,
and weight.`},{heading:"dynamic-configuration-file",content:`Dynamic configuration parameters are stored in a separate file on the server
(the dynamic configuration file), linked from the static config file using the
dynamicConfigFile keyword.`},{heading:"example-1",content:"zoo_replicated1.cfg"},{heading:"example-1",content:"zoo_replicated1.cfg.dynamic"},{heading:"example-1",content:`When the ensemble configuration changes, the static configuration parameters
remain the same. The dynamic parameters are pushed by ZooKeeper and overwrite
the dynamic configuration files on all servers. Thus the dynamic configuration
files on different servers are usually identical (they can differ momentarily
while a reconfiguration is in progress or before a new configuration has
propagated to all servers). Once created, the dynamic configuration file should
not be manually altered — changes are made only through the reconfiguration
commands described below. Changing the config of an offline cluster could
result in an inconsistency with configuration information stored in the
ZooKeeper log and is therefore highly discouraged.`},{heading:"example-2",content:`Users may prefer to initially specify a single configuration file. The
following is also legal:`},{heading:"example-2",content:"zoo_replicated1.cfg"},{heading:"example-2",content:`The configuration files on each server will be automatically split into
dynamic and static files if they are not already in this format. The
configuration above will be automatically transformed into the two files in
Example 1. Note that the clientPort and clientPortAddress lines (if
specified) will be automatically removed during this process if they are
redundant (as in the example above). The original static configuration file
is backed up (as a .bak file).`},{heading:"backward-compatibility",content:`The old configuration format is still supported. For example, the
following configuration file is acceptable (but not recommended):`},{heading:"backward-compatibility",content:"zoo_replicated1.cfg"},{heading:"backward-compatibility",content:`During boot, a dynamic configuration file is created containing the dynamic
part of the configuration as explained earlier. In this case, however, the
line clientPort=2791 will remain in the static configuration file of
server 1 since it is not redundant — it was not specified as part of
server.1=... using the format described in
Changes to Configuration Format. If a
reconfiguration is invoked that sets the client port of server 1, clientPort=2791
is removed from the static configuration file (the dynamic file then contains
this information as part of the server 1 specification).`},{heading:"upgrading-to-350",content:`Upgrading a running ZooKeeper ensemble to 3.5.0 should be done only
after upgrading to the 3.4.6 release. This is only necessary for rolling
upgrades — if you are fine with shutting down the system completely, you
do not need to go through 3.4.6. If you attempt a rolling upgrade without
going through 3.4.6 (for example from 3.4.5), you may get the following error:`},{heading:"upgrading-to-350",content:`During a rolling upgrade, each server is taken down in turn and rebooted
with the new 3.5.0 binaries. Before starting a server with 3.5.0 binaries,
we highly recommend updating the configuration file so that all server
statements server.x=... contain client ports (see
Specifying the Client Port). You may leave
the configuration in a single file and may also leave the
clientPort/clientPortAddress statements (although if you specify client
ports in the new format, these statements become redundant).`},{heading:"dynamic-reconfiguration-of-the-zookeeper-ensemble",content:`The ZooKeeper Java and C APIs were extended with getConfig and reconfig
commands that facilitate reconfiguration. Both commands have a synchronous
(blocking) variant and an asynchronous one. The examples below use the Java
CLI, but you can similarly use the C CLI or invoke the commands directly from
a program just like any other ZooKeeper command.`},{heading:"api",content:"There are two sets of APIs for both Java and C clients."},{heading:"api",content:`Reconfiguration API: Used to reconfigure the ZooKeeper cluster.
Starting with 3.5.3, reconfiguration Java APIs are moved into the ZooKeeperAdmin
class from the ZooKeeper class. Use of this API requires ACL setup and user
authentication (see Security for more information).`},{heading:"api",content:`Get Configuration API: Used to retrieve ZooKeeper cluster configuration
information stored in the /zookeeper/config znode. Use of this API does not
require specific setup or authentication, because /zookeeper/config is readable
by any user.`},{heading:"security",content:`Prior to 3.5.3, there is no enforced security mechanism over reconfig,
so any ZooKeeper client that can connect to the ensemble has the ability to
change its state via reconfig. It is thus possible for a malicious client to
add a compromised server to an ensemble or remove legitimate servers, which
can be a security vulnerability.`},{heading:"security",content:`Starting from 3.5.3, access control over reconfig is enforced: only a
specific set of explicitly configured users can use reconfig commands or APIs,
and the ZooKeeper cluster must have authentication enabled so that clients can
be authenticated.`},{heading:"security",content:`An escape hatch is provided for users operating in a secured environment (e.g.
behind a company firewall) who want to use reconfiguration without the overhead
of configuring an explicit authorized user list: setting
skipACL to yes skips
ACL checks and allows any user to reconfigure the cluster.`},{heading:"security",content:`Access Control: The dynamic configuration is stored in the special znode
ZooDefs.CONFIG_NODE = /zookeeper/config. This node is read-only for all users
by default, except the super user and users explicitly configured for write access.
Clients that need to use reconfig commands or the reconfig API must be configured
with write access to CONFIG_NODE. Additional users can be granted write access
through the superuser by setting an ACL with write permission. Examples of how to
set up ACLs and use the reconfiguration API with authentication can be found in
ReconfigExceptionTest.java and TestReconfigServer.cc.`},{heading:"security",content:`Authentication: Authentication is orthogonal to access control and is
delegated to ZooKeeper's pluggable authentication schemes. See
ZooKeeper and SASL
for more details.`},{heading:"security",content:`Disable ACL check: ZooKeeper supports the
skipACL option such that
ACL checks are completely skipped when skipACL is set to yes. In such cases
any unauthenticated user can use the reconfig API.`},{heading:"retrieving-the-current-dynamic-configuration",content:`The dynamic configuration is stored in a special znode
ZooDefs.CONFIG_NODE = /zookeeper/config. The config CLI command reads
this znode (it is essentially a wrapper around get /zookeeper/config). As
with normal reads, to retrieve the latest committed value you should do a
sync first.`},{heading:"retrieving-the-current-dynamic-configuration",content:`Notice the last line of the output — this is the configuration version. The
version equals the zxid of the reconfiguration command that created this
configuration. The version of the first established configuration equals the
zxid of the NEWLEADER message sent by the first successfully established leader.
When a configuration is written to a dynamic configuration file, the version
automatically becomes part of the filename and the static configuration file is
updated with the path to the new dynamic configuration file. Configuration files
corresponding to earlier versions are retained for backup purposes.`},{heading:"retrieving-the-current-dynamic-configuration",content:`During boot, the version (if it exists) is extracted from the filename. The
version should never be manually altered — it is used by the system to determine
which configuration is most up-to-date, and manipulating it can result in data
loss and inconsistency.`},{heading:"retrieving-the-current-dynamic-configuration",content:`Like the get command, the config CLI command accepts the -w flag for
setting a watch on the znode and the -s flag for displaying its stats. It
additionally accepts a -c flag that outputs only the version and the client
connection string for the current configuration. For example:`},{heading:"retrieving-the-current-dynamic-configuration",content:"Note that when using the API directly, this command is called getConfig."},{heading:"retrieving-the-current-dynamic-configuration",content:`As any read command, it returns the configuration known to the follower your
client is connected to, which may be slightly out-of-date. Use the sync
command for stronger guarantees. For example using the Java API:`},{heading:"retrieving-the-current-dynamic-configuration",content:`Note: in 3.5.0 it doesn't matter which path is passed to sync() since all
server state is brought up to date with the leader (a different path could be
used instead of ZooDefs.CONFIG_NODE). However, this may change in the future.`},{heading:"modifying-the-current-dynamic-configuration",content:`Configuration is modified through the reconfig command. There are two
modes: incremental and non-incremental (bulk). The non-incremental mode
specifies the complete new dynamic configuration. The incremental mode specifies
changes to the current configuration. The reconfig command returns the new
configuration.`},{heading:"modifying-the-current-dynamic-configuration",content:`A few examples can be found in: ReconfigTest.java, ReconfigRecoveryTest.java,
and TestReconfigServer.cc.`},{heading:"general",content:`Removing servers: Any server can be removed, including the leader (although
removing the leader will result in a short unavailability, see Figures 6 and 8 in
the paper).
The server will not be shut down automatically. Instead, it becomes a
"non-voting follower" — similar to an observer in that its votes don't count
towards the quorum, but unlike an observer, a non-voting follower still sees
operation proposals and ACKs them. Thus a non-voting follower has a more
significant negative effect on throughput compared to an observer.
Non-voting follower mode should only be used temporarily before shutting the
server down or adding it back as a follower or observer. Servers are not
shut down automatically for two main reasons: first, to avoid immediately
disconnecting all connected clients and causing a flood of reconnection requests
to other servers; second, because removing a server may sometimes be necessary
as a step to change it from observer to participant (see
Additional comments).`},{heading:"general",content:`Note that the new configuration must have some minimum number of participants
to be considered legal. If the proposed change would leave fewer than 2
participants and standalone mode is enabled (standaloneEnabled=true, see
The standaloneEnabled Flag), the reconfig
will not be processed (BadArgumentsException). If standalone mode is disabled
(standaloneEnabled=false) then 1 or more participants is legal.`},{heading:"general",content:`Adding servers: Before invoking a reconfiguration, the administrator must
ensure that a quorum (majority) of participants from the new configuration are
already connected and synced with the current leader. To achieve this, a new
joining server must be connected to the leader before it officially becomes part
of the ensemble. This is done by starting the joining server with an initial
server list that is not a legal configuration but (a) contains the joiner, and
(b) gives the joiner enough information to find and connect to the current leader.
A few safe options for doing this:`},{heading:"general",content:`The initial configuration of joiners consists of servers in the last committed
configuration plus one or more joiners, where joiners are listed as observers.
For example, if servers D and E are added at the same time to (A, B, C) and C
is being removed, D's initial config could be (A, B, C, D) or (A, B, C, D, E)
with D and E listed as observers. Note that listing joiners as observers does
not actually make them observers — it only prevents them from accidentally
forming a quorum with other joiners. Instead, they contact the servers in the
current configuration and adopt the last committed configuration (A, B, C).
Configuration files of joiners are backed up and replaced automatically.
After connecting to the current leader, joiners become non-voting followers until
the system is reconfigured and they are added to the ensemble.`},{heading:"general",content:`The initial configuration of each joiner consists of servers in the last committed
configuration plus the joiner itself, listed as a participant. For example,
to add a new server D to (A, B, C), start D with an initial config consisting of
(A, B, C, D). If both D and E are added at the same time, D's initial config
could be (A, B, C, D) and E's could be (A, B, C, E). Never list more than one
joiner as participant in the initial configuration (see warning below).`},{heading:"general",content:`Whether listing the joiner as an observer or participant, it is also fine not
to list all current configuration servers, as long as the current leader is
included. For example, when adding D, it could be started with just (A, D) if
A is the current leader. However, this is more fragile since if A fails before
D officially joins, D has no other servers to contact and the administrator
must restart D with another server list.`},{heading:"general",content:"type: warn"},{heading:"general",content:`Never specify more than one joining server in the same initial configuration
as participants. The joining servers do not know they are joining an existing
ensemble — if multiple joiners are listed as participants they may form an
independent quorum, creating a split-brain situation and processing operations
independently from the main ensemble. It is safe to list multiple joiners as
observers in an initial config.`},{heading:"general",content:`If the configuration of existing servers changes or they become unavailable before
the joiner succeeds in connecting and learning about configuration changes, the
joiner may need to be restarted with an updated configuration file.`},{heading:"general",content:`Finally, note that once connected to the leader, a joiner adopts the last committed
configuration (in which it is absent), and the initial config is backed up before
being rewritten. If the joiner restarts in this state it will not be able to boot
since it is absent from its configuration file — you will need to specify an initial
configuration again.`},{heading:"general",content:`Modifying server parameters: Any of the ports or the role (participant/observer)
of a server can be modified by adding it to the ensemble with different parameters.
This works in both incremental and bulk reconfiguration modes — it is not necessary
to remove the server and re-add it; just specify the new parameters as if the server
is not yet in the system. The server will detect the configuration change and perform
the necessary adjustments. See an example in Incremental mode
and an exception in Additional comments.`},{heading:"general",content:`It is also possible to change the Quorum System used by the ensemble (for example,
change from a Majority Quorum System to a Hierarchical Quorum System on the fly).
This is only allowed using the bulk (non-incremental) reconfiguration mode. Incremental
reconfiguration only works with the Majority Quorum System. Bulk reconfiguration
works with both Hierarchical and Majority Quorum Systems.`},{heading:"general",content:`Performance impact: There is practically no performance impact when removing a
follower, since it is not automatically shut down (the effect of removal is that the
server's votes are no longer counted). When adding a server, there is no leader change
and no noticeable performance disruption. For details and graphs see Figures 6, 7 and
8 in the paper.`},{heading:"general",content:`The most significant disruption occurs when a leader change is triggered, in the
following cases:`},{heading:"general",content:"Leader is removed from the ensemble."},{heading:"general",content:"Leader's role is changed from participant to observer."},{heading:"general",content:"The port used by the leader to send transactions to others (quorum port) is modified."},{heading:"general",content:`In these cases a leader hand-off is performed where the old leader nominates a new
leader. The resulting unavailability is usually shorter than when a leader crashes
since failure detection is unnecessary and electing a new leader can usually be
avoided during a hand-off (see Figures 6 and 8 in the paper).`},{heading:"general",content:`When the client port of a server is modified, existing client connections are not
dropped. New connections to the server will use the new client port.`},{heading:"general",content:`Progress guarantees: Up to the invocation of the reconfig operation, a quorum of
the old configuration must be available and connected for ZooKeeper to make progress.
Once reconfig is invoked, a quorum of both the old and new configurations must be
available. The final transition happens once (a) the new configuration is activated and
(b) all operations scheduled before the new configuration was activated by the leader
are committed. Once both (a) and (b) have happened, only a quorum of the new
configuration is required. Note that neither (a) nor (b) are visible to a client —
when a reconfiguration operation commits it only means that an activation message was
sent by the leader, not that a quorum of the new configuration has received it.
To ensure both (a) and (b) have occurred (for example, before safely shutting down
removed servers), invoke an update (set-data or another quorum operation, but not
sync) and wait for it to commit.`},{heading:"incremental-mode",content:`The incremental mode allows adding and removing servers from the current
configuration. Multiple changes are allowed at once. For example:`},{heading:"incremental-mode",content:"Both the add and remove options take comma-separated arguments (no spaces):"},{heading:"incremental-mode",content:`The format of the server statement is exactly as described in
Specifying the Client Port and includes the client
port. Note that 5= can be used as a shorthand for server.5=. In the example
above, if server 5 is already in the system with different ports or is not an
observer, it is updated — once the configuration commits it becomes an observer
using the new ports. This is an easy way to turn participants into observers and
vice versa, or change any ports, without rebooting the server.`},{heading:"incremental-mode",content:`ZooKeeper supports two types of Quorum Systems: the simple Majority system (where
the leader commits operations after receiving ACKs from a majority of voters) and a
more complex Hierarchical system (where votes of different servers have different
weights and servers are divided into voting groups). Incremental reconfiguration is
currently allowed only if the last proposed configuration uses a Majority Quorum
System (BadArgumentsException is thrown otherwise).`},{heading:"incremental-mode",content:"Incremental mode — examples using the Java API:"},{heading:"incremental-mode",content:`There is also an asynchronous API, and an API accepting comma-separated Strings
instead of List<String>. See
src/java/main/org/apache/zookeeper/ZooKeeper.java.`},{heading:"non-incremental-mode",content:`The non-incremental mode accepts a complete specification of the new dynamic
configuration. The new configuration can be given inline or read from a file:`},{heading:"non-incremental-mode",content:"newconfig.cfg is a dynamic config file — see Dynamic Configuration File."},{heading:"non-incremental-mode",content:`The new configuration may use a different Quorum System. For example, you may
specify a Hierarchical Quorum System even if the current ensemble uses a Majority
Quorum System.`},{heading:"non-incremental-mode",content:"Bulk mode — example using the Java API:"},{heading:"non-incremental-mode",content:`There is also an asynchronous API, and an API accepting a comma-separated String
containing the new members instead of List<String>. See
src/java/main/org/apache/zookeeper/ZooKeeper.java.`},{heading:"conditional-reconfig",content:`Sometimes (especially in non-incremental mode) a proposed configuration depends
on what the client believes to be the current configuration, and should only be
applied to that configuration. The reconfig succeeds only if the last
configuration at the leader has the specified version:`},{heading:"conditional-reconfig",content:`In the Java examples above, instead of -1 you can specify a configuration
version to condition the reconfiguration.`},{heading:"error-conditions",content:`In addition to normal ZooKeeper error conditions, a reconfiguration may fail for
the following reasons:`},{heading:"error-conditions",content:"Another reconfig is currently in progress (ReconfigInProgress)."},{heading:"error-conditions",content:`The proposed change would leave fewer than 2 participants and standalone mode
is enabled, or, if standalone mode is disabled, fewer than 1 participant would
remain (BadArgumentsException).`},{heading:"error-conditions",content:`No quorum of the new configuration was connected and up-to-date with the leader
when reconfiguration processing began (NewConfigNoQuorum).`},{heading:"error-conditions",content:`-v x was specified but the latest configuration version y is not x
(BadVersionException).`},{heading:"error-conditions",content:`An incremental reconfiguration was requested but the last configuration at the
leader uses a Quorum System other than Majority (BadArgumentsException).`},{heading:"error-conditions",content:"Syntax error (BadArgumentsException)."},{heading:"error-conditions",content:"I/O exception when reading the configuration from a file (BadArgumentsException)."},{heading:"error-conditions",content:"Most of these are illustrated by test cases in ReconfigFailureCases.java."},{heading:"additional-comments",content:`Liveness: To understand the difference between incremental and non-incremental
reconfiguration, suppose client C1 adds server D while a different client C2 adds
server E. With non-incremental mode, each client first invokes config to find out
the current configuration, then locally creates a new server list by adding its own
suggested server. After both reconfigurations complete, only one of D or E will be
added (not both), depending on which request arrives second at the leader. The other
client can repeat the process until its change takes effect. This guarantees
system-wide progress (for one client) but not for every client. C2 may use
Conditional reconfig to avoid blindly overwriting C1's
configuration if C1's request arrived first.`},{heading:"additional-comments",content:`With incremental reconfiguration, both changes take effect as they are applied by
the leader one after the other to the current configuration. Since both clients are
guaranteed to make progress, this method guarantees stronger liveness. In practice,
multiple concurrent reconfigurations are probably rare. Non-incremental reconfiguration
is currently the only way to dynamically change the Quorum System. Incremental
reconfiguration is currently only allowed with the Majority Quorum System.`},{heading:"additional-comments",content:`Changing an observer into a follower: Changing a voting server into an observer
may fail if fewer than the minimal allowed number of participants would remain (error 2).
Converting an observer into a participant may sometimes fail for a more subtle reason.
Suppose the current configuration is (A, B, C, D), where A is the leader, B and C are
followers, and D is an observer, and B has crashed. If a reconfiguration makes D a
follower, it will fail with error 3 since a majority of voters in the new configuration
(any 3 voters) must be connected and up-to-date with the leader. An observer cannot
acknowledge the history prefix sent during reconfiguration and therefore does not count
towards the 3 required servers. In this case, a client can achieve the task with two
reconfig commands: first remove D from the configuration, then add it back as a
participant. During the intermediate state D is a non-voting follower and can ACK the
state transfer performed during the second reconfig command.`},{heading:"rebalancing-client-connections",content:`When a ZooKeeper cluster is started and each client is given the same connection
string, the client randomly chooses a server to connect to, making the expected
number of client connections per server equal across all servers. ZooKeeper preserves
this property when the set of servers changes through reconfiguration (see Sections
4 and 5.1 in the paper).`},{heading:"rebalancing-client-connections",content:`For the method to work, all clients must subscribe to configuration changes by setting
a watch on /zookeeper/config — either directly or through the getConfig API. When
the watch is triggered, the client should read the new configuration by invoking
sync and getConfig, and if the configuration is indeed new, invoke updateServerList.
To avoid mass client migration at the same time, each client should sleep a random
short period before invoking updateServerList.`},{heading:"rebalancing-client-connections",content:"A few examples can be found in StaticHostProviderTest.java and TestReconfig.cc."},{heading:"rebalancing-client-connections",content:"Example (simplified to illustrate the general idea, not a production recipe):"}],headings:[{id:"changes-to-configuration-format",content:"Changes to Configuration Format"},{id:"specifying-the-client-port",content:"Specifying the Client Port"},{id:"specifying-multiple-server-addresses",content:"Specifying Multiple Server Addresses"},{id:"the-standaloneenabled-flag",content:"The standaloneEnabled Flag"},{id:"the-reconfigenabled-flag",content:"The reconfigEnabled Flag"},{id:"dynamic-configuration-file",content:"Dynamic Configuration File"},{id:"example-1",content:"Example 1"},{id:"example-2",content:"Example 2"},{id:"backward-compatibility",content:"Backward Compatibility"},{id:"upgrading-to-350",content:"Upgrading to 3.5.0"},{id:"dynamic-reconfiguration-of-the-zookeeper-ensemble",content:"Dynamic Reconfiguration of the ZooKeeper Ensemble"},{id:"api",content:"API"},{id:"security",content:"Security"},{id:"retrieving-the-current-dynamic-configuration",content:"Retrieving the Current Dynamic Configuration"},{id:"modifying-the-current-dynamic-configuration",content:"Modifying the Current Dynamic Configuration"},{id:"general",content:"General"},{id:"incremental-mode",content:"Incremental Mode"},{id:"non-incremental-mode",content:"Non-incremental Mode"},{id:"conditional-reconfig",content:"Conditional Reconfig"},{id:"error-conditions",content:"Error Conditions"},{id:"additional-comments",content:"Additional Comments"},{id:"rebalancing-client-connections",content:"Rebalancing Client Connections"}]};const d=[{depth:2,url:"#changes-to-configuration-format",title:e.jsx(e.Fragment,{children:"Changes to Configuration Format"})},{depth:3,url:"#specifying-the-client-port",title:e.jsx(e.Fragment,{children:"Specifying the Client Port"})},{depth:3,url:"#specifying-multiple-server-addresses",title:e.jsx(e.Fragment,{children:"Specifying Multiple Server Addresses"})},{depth:3,url:"#the-standaloneenabled-flag",title:e.jsxs(e.Fragment,{children:["The ",e.jsx("code",{children:"standaloneEnabled"})," Flag"]})},{depth:3,url:"#the-reconfigenabled-flag",title:e.jsxs(e.Fragment,{children:["The ",e.jsx("code",{children:"reconfigEnabled"})," Flag"]})},{depth:3,url:"#dynamic-configuration-file",title:e.jsx(e.Fragment,{children:"Dynamic Configuration File"})},{depth:4,url:"#example-1",title:e.jsx(e.Fragment,{children:"Example 1"})},{depth:4,url:"#example-2",title:e.jsx(e.Fragment,{children:"Example 2"})},{depth:3,url:"#backward-compatibility",title:e.jsx(e.Fragment,{children:"Backward Compatibility"})},{depth:2,url:"#upgrading-to-350",title:e.jsx(e.Fragment,{children:"Upgrading to 3.5.0"})},{depth:2,url:"#dynamic-reconfiguration-of-the-zookeeper-ensemble",title:e.jsx(e.Fragment,{children:"Dynamic Reconfiguration of the ZooKeeper Ensemble"})},{depth:3,url:"#api",title:e.jsx(e.Fragment,{children:"API"})},{depth:3,url:"#security",title:e.jsx(e.Fragment,{children:"Security"})},{depth:3,url:"#retrieving-the-current-dynamic-configuration",title:e.jsx(e.Fragment,{children:"Retrieving the Current Dynamic Configuration"})},{depth:3,url:"#modifying-the-current-dynamic-configuration",title:e.jsx(e.Fragment,{children:"Modifying the Current Dynamic Configuration"})},{depth:4,url:"#general",title:e.jsx(e.Fragment,{children:"General"})},{depth:4,url:"#incremental-mode",title:e.jsx(e.Fragment,{children:"Incremental Mode"})},{depth:4,url:"#non-incremental-mode",title:e.jsx(e.Fragment,{children:"Non-incremental Mode"})},{depth:4,url:"#conditional-reconfig",title:e.jsx(e.Fragment,{children:"Conditional Reconfig"})},{depth:4,url:"#error-conditions",title:e.jsx(e.Fragment,{children:"Error Conditions"})},{depth:4,url:"#additional-comments",title:e.jsx(e.Fragment,{children:"Additional Comments"})},{depth:2,url:"#rebalancing-client-connections",title:e.jsx(e.Fragment,{children:"Rebalancing Client Connections"})}];function s(i){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",h4:"h4",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...i.components},{Callout:t}=n;return t||r("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`Prior to the 3.5.0 release, the membership and all other configuration
parameters of ZooKeeper were static — loaded during boot and immutable at
runtime. Operators resorted to "rolling restarts" — a manually intensive
and error-prone method of changing the configuration that has caused data
loss and inconsistency in production.`}),`
`,e.jsx(n.p,{children:`Starting with 3.5.0, rolling restarts are no longer needed.
ZooKeeper comes with full support for automated configuration changes: the
set of ZooKeeper servers, their roles (participant / observer), all ports,
and even the quorum system can be changed dynamically, without service
interruption and while maintaining data consistency. Reconfigurations are
performed immediately, just like other operations in ZooKeeper. Multiple
changes can be done using a single reconfiguration command. The dynamic
reconfiguration functionality does not limit operation concurrency, does
not require client operations to be stopped during reconfigurations, has a
very simple interface for administrators, and adds no complexity to other
client operations.`}),`
`,e.jsx(n.p,{children:`New client-side features allow clients to find out about configuration
changes and to update the connection string (list of servers and their
client ports) stored in their ZooKeeper handle. A probabilistic algorithm
is used to rebalance clients across the new configuration servers while
keeping the extent of client migrations proportional to the change in
ensemble membership.`}),`
`,e.jsx(n.p,{children:`This document provides the administrator manual for reconfiguration.
For a detailed description of the reconfiguration algorithms, performance
measurements, and more, see:`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"Shraer, A., Reed, B., Malkhi, D., Junqueira, F. Dynamic Reconfiguration of Primary/Backup Clusters. In USENIX Annual Technical Conference (ATC) (2012), 425–437."}),`
Links: `,e.jsx(n.a,{href:"https://www.usenix.org/system/files/conference/atc12/atc12-final74.pdf",children:"paper (pdf)"}),", ",e.jsx(n.a,{href:"https://www.usenix.org/sites/default/files/conference/protected-files/shraer_atc12_slides.pdf",children:"slides (pdf)"}),", ",e.jsx(n.a,{href:"https://www.usenix.org/conference/atc12/technical-sessions/presentation/shraer",children:"video"}),", ",e.jsx(n.a,{href:"http://www.slideshare.net/Hadoop_Summit/dynamic-reconfiguration-of-zookeeper",children:"hadoop summit slides"})]}),`
`,e.jsx(t,{type:"warn",children:e.jsxs(n.p,{children:[`Starting with 3.5.3, the dynamic reconfiguration feature is disabled by
default and must be explicitly turned on via the `,e.jsx(n.code,{children:"reconfigEnabled"}),`
configuration option.`]})}),`
`,e.jsx(n.h2,{id:"changes-to-configuration-format",children:"Changes to Configuration Format"}),`
`,e.jsx(n.h3,{id:"specifying-the-client-port",children:"Specifying the Client Port"}),`
`,e.jsx(n.p,{children:`A client port is the port on which the server accepts plaintext (non-TLS) client connection requests.
A secure client port is the port on which the server accepts TLS client connection requests.`}),`
`,e.jsxs(n.p,{children:["Starting with 3.5.0, the ",e.jsx(n.code,{children:"clientPort"})," and ",e.jsx(n.code,{children:"clientPortAddress"})," configuration parameters should no longer be used in ",e.jsx(n.code,{children:"zoo.cfg"}),"."]}),`
`,e.jsxs(n.p,{children:["Starting with 3.10.0, the ",e.jsx(n.code,{children:"secureClientPort"})," and ",e.jsx(n.code,{children:"secureClientPortAddress"})," configuration parameters should no longer be used in ",e.jsx(n.code,{children:"zoo.cfg"}),"."]}),`
`,e.jsx(n.p,{children:"Instead, this information is now part of the server keyword specification:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.<positive id> = <address1>:<quorum port>:<leader election port>[:role];[[<client port address>:]<client port>][;[<secure client port address>:]<secure client port>]"})})})})}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`[New in ZK 3.10.0] The client port specification is optional and is to the right of the
first semicolon. The secure client port specification is also optional and is to the right
of the second semicolon. However, both the client port and secure client port specification
cannot be omitted — at least one of them must be present. If the user intends to omit the client
port specification and provide only the secure client port (TLS-only server), a second
semicolon should still be specified to indicate an empty client port specification (see last
example below). In either spec, the port address is optional and defaults to `,e.jsx(n.code,{children:"0.0.0.0"}),"."]}),`
`,e.jsxs(n.li,{children:["Role is also optional — it can be ",e.jsx(n.code,{children:"participant"})," or ",e.jsx(n.code,{children:"observer"})," (",e.jsx(n.code,{children:"participant"})," by default)."]}),`
`]}),`
`,e.jsx(n.p,{children:"Examples of legal server statements:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235;1236                                             (non-TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235;1236;1237                                        (non-TLS + TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235;;1237                                            (TLS-only server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235:participant;1236                                 (non-TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235:observer;1236                                    (non-TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235;125.23.63.24:1236                                (non-TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235:participant;125.23.63.23:1236                    (non-TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235:participant;125.23.63.23:1236;125.23.63.23:1237  (non-TLS + TLS server)"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.5 = 125.23.63.23:1234:1235:participant;;125.23.63.23:1237                   (TLS-only server)"})})]})})}),`
`,e.jsx(n.h3,{id:"specifying-multiple-server-addresses",children:"Specifying Multiple Server Addresses"}),`
`,e.jsxs(n.p,{children:[`Since ZooKeeper 3.6.0 it is possible to specify multiple addresses for each
ZooKeeper server (see `,e.jsx(n.a,{href:"https://issues.apache.org/jira/projects/ZOOKEEPER/issues/ZOOKEEPER-3188",children:"ZOOKEEPER-3188"}),`).
This helps to increase availability and adds network-level resiliency to ZooKeeper. When multiple
physical network interfaces are used for the servers, ZooKeeper is able to bind on all interfaces
and switch to a working interface at runtime in case of a network error. Multiple addresses are
separated by a pipe (`,e.jsx(n.code,{children:"|"}),") character."]}),`
`,e.jsx(n.p,{children:"Examples of valid configurations using multiple addresses:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889;2188"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889|zoo2-net3:2890:3890;2188"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2-net1:2888:3888|zoo2-net2:2889:3889;zoo2-net1:2188"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=zoo2-net1:2888:3888:observer|zoo2-net2:2889:3889:observer;2188"})})]})})}),`
`,e.jsxs(n.h3,{id:"the-standaloneenabled-flag",children:["The ",e.jsx(n.code,{children:"standaloneEnabled"})," Flag"]}),`
`,e.jsxs(n.p,{children:[`Prior to 3.5.0, one could run ZooKeeper in Standalone mode or in
Distributed mode. These are separate implementation stacks and switching
between them at runtime is not possible. By default (for backward
compatibility) `,e.jsx(n.code,{children:"standaloneEnabled"})," is set to ",e.jsx(n.code,{children:"true"}),`. The consequence is that
if started with a single server the ensemble will not be allowed to grow,
and if started with more than one server it will not be allowed to shrink
below two participants.`]}),`
`,e.jsxs(n.p,{children:["Setting the flag to ",e.jsx(n.code,{children:"false"}),` instructs the system to run the Distributed
software stack even if there is only a single participant in the ensemble.
To achieve this the static configuration file should contain:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"standaloneEnabled=false"})})})})}),`
`,e.jsx(n.p,{children:`With this setting it is possible to start a ZooKeeper ensemble containing
a single participant and to dynamically grow it by adding more servers.
Similarly, it is possible to shrink an ensemble so that just a single
participant remains by removing servers.`}),`
`,e.jsxs(n.p,{children:[`Since running the Distributed mode allows more flexibility, we recommend
setting the flag to `,e.jsx(n.code,{children:"false"}),`. The legacy Standalone mode is expected to be
deprecated in the future.`]}),`
`,e.jsxs(n.h3,{id:"the-reconfigenabled-flag",children:["The ",e.jsx(n.code,{children:"reconfigEnabled"})," Flag"]}),`
`,e.jsxs(n.p,{children:[`Starting with 3.5.0 and prior to 3.5.3, there is no way to disable
dynamic reconfiguration. To address the security concern that a malicious
actor could make arbitrary changes to the ensemble configuration, the
`,e.jsx(n.code,{children:"reconfigEnabled"}),` option was introduced in 3.5.3. Any attempt
to reconfigure a cluster through the reconfig API — with or without authentication —
will fail by default unless `,e.jsx(n.code,{children:"reconfigEnabled"})," is set to ",e.jsx(n.code,{children:"true"}),"."]}),`
`,e.jsxs(n.p,{children:["To enable reconfiguration, add the following to ",e.jsx(n.code,{children:"zoo.cfg"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"reconfigEnabled=true"})})})})}),`
`,e.jsx(n.h3,{id:"dynamic-configuration-file",children:"Dynamic Configuration File"}),`
`,e.jsxs(n.p,{children:[`Starting with 3.5.0, ZooKeeper distinguishes between dynamic configuration
parameters (which can be changed during runtime) and static configuration
parameters (which are read from a configuration file at boot and do not change
during execution). The dynamic configuration keywords are: `,e.jsx(n.code,{children:"server"}),", ",e.jsx(n.code,{children:"group"}),`,
and `,e.jsx(n.code,{children:"weight"}),"."]}),`
`,e.jsxs(n.p,{children:[`Dynamic configuration parameters are stored in a separate file on the server
(the dynamic configuration file), linked from the static config file using the
`,e.jsx(n.code,{children:"dynamicConfigFile"})," keyword."]}),`
`,e.jsx(n.h4,{id:"example-1",children:"Example 1"}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:e.jsx(n.code,{children:"zoo_replicated1.cfg"})})}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/zookeeper/data/zookeeper1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dynamicConfigFile=/zookeeper/conf/zoo_replicated1.cfg.dynamic"})})]})})}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:e.jsx(n.code,{children:"zoo_replicated1.cfg.dynamic"})})}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=125.23.63.23:2780:2783:participant;2791"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=125.23.63.24:2781:2784:participant;2792"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=125.23.63.25:2782:2785:participant;2793"})})]})})}),`
`,e.jsx(n.p,{children:`When the ensemble configuration changes, the static configuration parameters
remain the same. The dynamic parameters are pushed by ZooKeeper and overwrite
the dynamic configuration files on all servers. Thus the dynamic configuration
files on different servers are usually identical (they can differ momentarily
while a reconfiguration is in progress or before a new configuration has
propagated to all servers). Once created, the dynamic configuration file should
not be manually altered — changes are made only through the reconfiguration
commands described below. Changing the config of an offline cluster could
result in an inconsistency with configuration information stored in the
ZooKeeper log and is therefore highly discouraged.`}),`
`,e.jsx(n.h4,{id:"example-2",children:"Example 2"}),`
`,e.jsx(n.p,{children:`Users may prefer to initially specify a single configuration file. The
following is also legal:`}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:e.jsx(n.code,{children:"zoo_replicated1.cfg"})})}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/zookeeper/data/zookeeper1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"clientPort="})})]})})}),`
`,e.jsxs(n.p,{children:[`The configuration files on each server will be automatically split into
dynamic and static files if they are not already in this format. The
configuration above will be automatically transformed into the two files in
Example 1. Note that the `,e.jsx(n.code,{children:"clientPort"})," and ",e.jsx(n.code,{children:"clientPortAddress"}),` lines (if
specified) will be automatically removed during this process if they are
redundant (as in the example above). The original static configuration file
is backed up (as a `,e.jsx(n.code,{children:".bak"})," file)."]}),`
`,e.jsx(n.h3,{id:"backward-compatibility",children:"Backward Compatibility"}),`
`,e.jsx(n.p,{children:`The old configuration format is still supported. For example, the
following configuration file is acceptable (but not recommended):`}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:e.jsx(n.code,{children:"zoo_replicated1.cfg"})})}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/zookeeper/data/zookeeper1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"clientPort=2791"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=125.23.63.23:2780:2783:participant"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=125.23.63.24:2781:2784:participant"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=125.23.63.25:2782:2785:participant"})})]})})}),`
`,e.jsxs(n.p,{children:[`During boot, a dynamic configuration file is created containing the dynamic
part of the configuration as explained earlier. In this case, however, the
line `,e.jsx(n.code,{children:"clientPort=2791"}),` will remain in the static configuration file of
server 1 since it is not redundant — it was not specified as part of
`,e.jsx(n.code,{children:"server.1=..."}),` using the format described in
`,e.jsx(n.a,{href:"#changes-to-configuration-format",children:"Changes to Configuration Format"}),`. If a
reconfiguration is invoked that sets the client port of server 1, `,e.jsx(n.code,{children:"clientPort=2791"}),`
is removed from the static configuration file (the dynamic file then contains
this information as part of the server 1 specification).`]}),`
`,e.jsx(n.h2,{id:"upgrading-to-350",children:"Upgrading to 3.5.0"}),`
`,e.jsx(n.p,{children:`Upgrading a running ZooKeeper ensemble to 3.5.0 should be done only
after upgrading to the 3.4.6 release. This is only necessary for rolling
upgrades — if you are fine with shutting down the system completely, you
do not need to go through 3.4.6. If you attempt a rolling upgrade without
going through 3.4.6 (for example from 3.4.5), you may get the following error:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2013-01-30 11:32:10,663 [myid:2] - INFO [localhost/127.0.0.1:2784:QuorumCnxManager$Listener@498] - Received connection request /127.0.0.1:60876"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2013-01-30 11:32:10,663 [myid:2] - WARN [localhost/127.0.0.1:2784:QuorumCnxManager@349] - Invalid server id: -65536"})})]})})}),`
`,e.jsxs(n.p,{children:[`During a rolling upgrade, each server is taken down in turn and rebooted
with the new 3.5.0 binaries. Before starting a server with 3.5.0 binaries,
we highly recommend updating the configuration file so that all server
statements `,e.jsx(n.code,{children:"server.x=..."}),` contain client ports (see
`,e.jsx(n.a,{href:"#specifying-the-client-port",children:"Specifying the Client Port"}),`). You may leave
the configuration in a single file and may also leave the
`,e.jsx(n.code,{children:"clientPort"}),"/",e.jsx(n.code,{children:"clientPortAddress"}),` statements (although if you specify client
ports in the new format, these statements become redundant).`]}),`
`,e.jsx(n.h2,{id:"dynamic-reconfiguration-of-the-zookeeper-ensemble",children:"Dynamic Reconfiguration of the ZooKeeper Ensemble"}),`
`,e.jsxs(n.p,{children:["The ZooKeeper Java and C APIs were extended with ",e.jsx(n.code,{children:"getConfig"})," and ",e.jsx(n.code,{children:"reconfig"}),`
commands that facilitate reconfiguration. Both commands have a synchronous
(blocking) variant and an asynchronous one. The examples below use the Java
CLI, but you can similarly use the C CLI or invoke the commands directly from
a program just like any other ZooKeeper command.`]}),`
`,e.jsx(n.h3,{id:"api",children:"API"}),`
`,e.jsx(n.p,{children:"There are two sets of APIs for both Java and C clients."}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Reconfiguration API:"}),` Used to reconfigure the ZooKeeper cluster.
Starting with 3.5.3, reconfiguration Java APIs are moved into the `,e.jsx(n.code,{children:"ZooKeeperAdmin"}),`
class from the `,e.jsx(n.code,{children:"ZooKeeper"}),` class. Use of this API requires ACL setup and user
authentication (see `,e.jsx(n.a,{href:"#security",children:"Security"})," for more information)."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Get Configuration API:"}),` Used to retrieve ZooKeeper cluster configuration
information stored in the `,e.jsx(n.code,{children:"/zookeeper/config"}),` znode. Use of this API does not
require specific setup or authentication, because `,e.jsx(n.code,{children:"/zookeeper/config"}),` is readable
by any user.`]}),`
`]}),`
`]}),`
`,e.jsx(n.h3,{id:"security",children:"Security"}),`
`,e.jsxs(n.p,{children:["Prior to ",e.jsx(n.strong,{children:"3.5.3"}),`, there is no enforced security mechanism over reconfig,
so any ZooKeeper client that can connect to the ensemble has the ability to
change its state via reconfig. It is thus possible for a malicious client to
add a compromised server to an ensemble or remove legitimate servers, which
can be a security vulnerability.`]}),`
`,e.jsxs(n.p,{children:["Starting from ",e.jsx(n.strong,{children:"3.5.3"}),`, access control over reconfig is enforced: only a
specific set of explicitly configured users can use reconfig commands or APIs,
and the ZooKeeper cluster must have authentication enabled so that clients can
be authenticated.`]}),`
`,e.jsxs(n.p,{children:[`An escape hatch is provided for users operating in a secured environment (e.g.
behind a company firewall) who want to use reconfiguration without the overhead
of configuring an explicit authorized user list: setting
`,e.jsx(n.code,{children:"skipACL"})," to ",e.jsx(n.code,{children:"yes"}),` skips
ACL checks and allows any user to reconfigure the cluster.`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Access Control:"}),` The dynamic configuration is stored in the special znode
`,e.jsx(n.code,{children:"ZooDefs.CONFIG_NODE = /zookeeper/config"}),`. This node is read-only for all users
by default, except the super user and users explicitly configured for write access.
Clients that need to use reconfig commands or the reconfig API must be configured
with write access to `,e.jsx(n.code,{children:"CONFIG_NODE"}),`. Additional users can be granted write access
through the superuser by setting an ACL with write permission. Examples of how to
set up ACLs and use the reconfiguration API with authentication can be found in
`,e.jsx(n.code,{children:"ReconfigExceptionTest.java"})," and ",e.jsx(n.code,{children:"TestReconfigServer.cc"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Authentication:"}),` Authentication is orthogonal to access control and is
delegated to ZooKeeper's pluggable authentication schemes. See
`,e.jsx(n.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zookeeper+and+SASL",children:"ZooKeeper and SASL"}),`
for more details.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Disable ACL check:"}),` ZooKeeper supports the
`,e.jsx(n.code,{children:"skipACL"}),` option such that
ACL checks are completely skipped when `,e.jsx(n.code,{children:"skipACL"})," is set to ",e.jsx(n.code,{children:"yes"}),`. In such cases
any unauthenticated user can use the reconfig API.`]}),`
`]}),`
`]}),`
`,e.jsx(n.h3,{id:"retrieving-the-current-dynamic-configuration",children:"Retrieving the Current Dynamic Configuration"}),`
`,e.jsxs(n.p,{children:[`The dynamic configuration is stored in a special znode
`,e.jsx(n.code,{children:"ZooDefs.CONFIG_NODE = /zookeeper/config"}),". The ",e.jsx(n.code,{children:"config"}),` CLI command reads
this znode (it is essentially a wrapper around `,e.jsx(n.code,{children:"get /zookeeper/config"}),`). As
with normal reads, to retrieve the latest committed value you should do a
`,e.jsx(n.code,{children:"sync"})," first."]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zk: 127.0.0.1:2791(CONNECTED) 3] config"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=localhost:2780:2783:participant;localhost:2791"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=localhost:2781:2784:participant;localhost:2792"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.3=localhost:2782:2785:participant;localhost:2793"})})]})})}),`
`,e.jsx(n.p,{children:`Notice the last line of the output — this is the configuration version. The
version equals the zxid of the reconfiguration command that created this
configuration. The version of the first established configuration equals the
zxid of the NEWLEADER message sent by the first successfully established leader.
When a configuration is written to a dynamic configuration file, the version
automatically becomes part of the filename and the static configuration file is
updated with the path to the new dynamic configuration file. Configuration files
corresponding to earlier versions are retained for backup purposes.`}),`
`,e.jsx(n.p,{children:`During boot, the version (if it exists) is extracted from the filename. The
version should never be manually altered — it is used by the system to determine
which configuration is most up-to-date, and manipulating it can result in data
loss and inconsistency.`}),`
`,e.jsxs(n.p,{children:["Like the ",e.jsx(n.code,{children:"get"})," command, the ",e.jsx(n.code,{children:"config"})," CLI command accepts the ",e.jsx(n.code,{children:"-w"}),` flag for
setting a watch on the znode and the `,e.jsx(n.code,{children:"-s"}),` flag for displaying its stats. It
additionally accepts a `,e.jsx(n.code,{children:"-c"}),` flag that outputs only the version and the client
connection string for the current configuration. For example:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"[zk: 127.0.0.1:2791(CONNECTED) 17] config -c"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"400000003 localhost:2791,localhost:2793,localhost:2792"})})]})})}),`
`,e.jsxs(n.p,{children:["Note that when using the API directly, this command is called ",e.jsx(n.code,{children:"getConfig"}),"."]}),`
`,e.jsxs(n.p,{children:[`As any read command, it returns the configuration known to the follower your
client is connected to, which may be slightly out-of-date. Use the `,e.jsx(n.code,{children:"sync"}),`
command for stronger guarantees. For example using the Java API:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"sync"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZooDefs.CONFIG_NODE, void_callback, context);"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getConfig"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(watcher, callback, context);"})]})]})})}),`
`,e.jsxs(n.p,{children:["Note: in 3.5.0 it doesn't matter which path is passed to ",e.jsx(n.code,{children:"sync()"}),` since all
server state is brought up to date with the leader (a different path could be
used instead of `,e.jsx(n.code,{children:"ZooDefs.CONFIG_NODE"}),"). However, this may change in the future."]}),`
`,e.jsx(n.h3,{id:"modifying-the-current-dynamic-configuration",children:"Modifying the Current Dynamic Configuration"}),`
`,e.jsxs(n.p,{children:["Configuration is modified through the ",e.jsx(n.code,{children:"reconfig"}),` command. There are two
modes: incremental and non-incremental (bulk). The non-incremental mode
specifies the complete new dynamic configuration. The incremental mode specifies
changes to the current configuration. The `,e.jsx(n.code,{children:"reconfig"}),` command returns the new
configuration.`]}),`
`,e.jsxs(n.p,{children:["A few examples can be found in: ",e.jsx(n.code,{children:"ReconfigTest.java"}),", ",e.jsx(n.code,{children:"ReconfigRecoveryTest.java"}),`,
and `,e.jsx(n.code,{children:"TestReconfigServer.cc"}),"."]}),`
`,e.jsx(n.h4,{id:"general",children:"General"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Removing servers:"}),` Any server can be removed, including the leader (although
removing the leader will result in a short unavailability, see Figures 6 and 8 in
the `,e.jsx(n.a,{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters",children:"paper"}),`).
The server will not be shut down automatically. Instead, it becomes a
"non-voting follower" — similar to an observer in that its votes don't count
towards the quorum, but unlike an observer, a non-voting follower still sees
operation proposals and ACKs them. Thus a non-voting follower has a more
significant negative effect on throughput compared to an observer.
Non-voting follower mode should only be used temporarily before shutting the
server down or adding it back as a follower or observer. Servers are not
shut down automatically for two main reasons: first, to avoid immediately
disconnecting all connected clients and causing a flood of reconnection requests
to other servers; second, because removing a server may sometimes be necessary
as a step to change it from observer to participant (see
`,e.jsx(n.a,{href:"#additional-comments",children:"Additional comments"}),")."]}),`
`,e.jsxs(n.p,{children:[`Note that the new configuration must have some minimum number of participants
to be considered legal. If the proposed change would leave fewer than 2
participants and standalone mode is enabled (`,e.jsx(n.code,{children:"standaloneEnabled=true"}),`, see
`,e.jsxs(n.a,{href:"#the-standaloneenabled-flag",children:["The ",e.jsx(n.code,{children:"standaloneEnabled"})," Flag"]}),`), the reconfig
will not be processed (`,e.jsx(n.code,{children:"BadArgumentsException"}),`). If standalone mode is disabled
(`,e.jsx(n.code,{children:"standaloneEnabled=false"}),") then 1 or more participants is legal."]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Adding servers:"}),` Before invoking a reconfiguration, the administrator must
ensure that a quorum (majority) of participants from the new configuration are
already connected and synced with the current leader. To achieve this, a new
joining server must be connected to the leader before it officially becomes part
of the ensemble. This is done by starting the joining server with an initial
server list that is not a legal configuration but (a) contains the joiner, and
(b) gives the joiner enough information to find and connect to the current leader.
A few safe options for doing this:`]}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[`The initial configuration of joiners consists of servers in the last committed
configuration plus one or more joiners, where `,e.jsx(n.strong,{children:"joiners are listed as observers."}),`
For example, if servers D and E are added at the same time to (A, B, C) and C
is being removed, D's initial config could be (A, B, C, D) or (A, B, C, D, E)
with D and E listed as observers. `,e.jsx(n.strong,{children:`Note that listing joiners as observers does
not actually make them observers`}),` — it only prevents them from accidentally
forming a quorum with other joiners. Instead, they contact the servers in the
current configuration and adopt the last committed configuration (A, B, C).
Configuration files of joiners are backed up and replaced automatically.
After connecting to the current leader, joiners become non-voting followers until
the system is reconfigured and they are added to the ensemble.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[`The initial configuration of each joiner consists of servers in the last committed
configuration `,e.jsx(n.strong,{children:"plus the joiner itself, listed as a participant."}),` For example,
to add a new server D to (A, B, C), start D with an initial config consisting of
(A, B, C, D). If both D and E are added at the same time, D's initial config
could be (A, B, C, D) and E's could be (A, B, C, E). Never list more than one
joiner as participant in the initial configuration (see warning below).`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:`Whether listing the joiner as an observer or participant, it is also fine not
to list all current configuration servers, as long as the current leader is
included. For example, when adding D, it could be started with just (A, D) if
A is the current leader. However, this is more fragile since if A fails before
D officially joins, D has no other servers to contact and the administrator
must restart D with another server list.`}),`
`]}),`
`]}),`
`,e.jsx(t,{type:"warn",children:e.jsx(n.p,{children:`Never specify more than one joining server in the same initial configuration
as participants. The joining servers do not know they are joining an existing
ensemble — if multiple joiners are listed as participants they may form an
independent quorum, creating a split-brain situation and processing operations
independently from the main ensemble. It is safe to list multiple joiners as
observers in an initial config.`})}),`
`,e.jsx(n.p,{children:`If the configuration of existing servers changes or they become unavailable before
the joiner succeeds in connecting and learning about configuration changes, the
joiner may need to be restarted with an updated configuration file.`}),`
`,e.jsx(n.p,{children:`Finally, note that once connected to the leader, a joiner adopts the last committed
configuration (in which it is absent), and the initial config is backed up before
being rewritten. If the joiner restarts in this state it will not be able to boot
since it is absent from its configuration file — you will need to specify an initial
configuration again.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Modifying server parameters:"}),` Any of the ports or the role (participant/observer)
of a server can be modified by adding it to the ensemble with different parameters.
This works in both incremental and bulk reconfiguration modes — it is not necessary
to remove the server and re-add it; just specify the new parameters as if the server
is not yet in the system. The server will detect the configuration change and perform
the necessary adjustments. See an example in `,e.jsx(n.a,{href:"#incremental-mode",children:"Incremental mode"}),`
and an exception in `,e.jsx(n.a,{href:"#additional-comments",children:"Additional comments"}),"."]}),`
`,e.jsx(n.p,{children:`It is also possible to change the Quorum System used by the ensemble (for example,
change from a Majority Quorum System to a Hierarchical Quorum System on the fly).
This is only allowed using the bulk (non-incremental) reconfiguration mode. Incremental
reconfiguration only works with the Majority Quorum System. Bulk reconfiguration
works with both Hierarchical and Majority Quorum Systems.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Performance impact:"}),` There is practically no performance impact when removing a
follower, since it is not automatically shut down (the effect of removal is that the
server's votes are no longer counted). When adding a server, there is no leader change
and no noticeable performance disruption. For details and graphs see Figures 6, 7 and
8 in the `,e.jsx(n.a,{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters",children:"paper"}),"."]}),`
`,e.jsx(n.p,{children:`The most significant disruption occurs when a leader change is triggered, in the
following cases:`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:"Leader is removed from the ensemble."}),`
`,e.jsx(n.li,{children:"Leader's role is changed from participant to observer."}),`
`,e.jsx(n.li,{children:"The port used by the leader to send transactions to others (quorum port) is modified."}),`
`]}),`
`,e.jsxs(n.p,{children:[`In these cases a leader hand-off is performed where the old leader nominates a new
leader. The resulting unavailability is usually shorter than when a leader crashes
since failure detection is unnecessary and electing a new leader can usually be
avoided during a hand-off (see Figures 6 and 8 in the `,e.jsx(n.a,{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters",children:"paper"}),")."]}),`
`,e.jsx(n.p,{children:`When the client port of a server is modified, existing client connections are not
dropped. New connections to the server will use the new client port.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Progress guarantees:"}),` Up to the invocation of the reconfig operation, a quorum of
the old configuration must be available and connected for ZooKeeper to make progress.
Once reconfig is invoked, a quorum of both the old and new configurations must be
available. The final transition happens once (a) the new configuration is activated and
(b) all operations scheduled before the new configuration was activated by the leader
are committed. Once both (a) and (b) have happened, only a quorum of the new
configuration is required. Note that neither (a) nor (b) are visible to a client —
when a reconfiguration operation commits it only means that an activation message was
sent by the leader, not that a quorum of the new configuration has received it.
To ensure both (a) and (b) have occurred (for example, before safely shutting down
removed servers), invoke an update (`,e.jsx(n.code,{children:"set-data"}),` or another quorum operation, but not
`,e.jsx(n.code,{children:"sync"}),") and wait for it to commit."]}),`
`,e.jsx(n.h4,{id:"incremental-mode",children:"Incremental Mode"}),`
`,e.jsx(n.p,{children:`The incremental mode allows adding and removing servers from the current
configuration. Multiple changes are allowed at once. For example:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" reconfig -remove 3 -add "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"server.5"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=125.23.63.23:1234:1235"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"1236"})]})]})})}),`
`,e.jsx(n.p,{children:"Both the add and remove options take comma-separated arguments (no spaces):"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" reconfig -remove 3,4 -add "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"server.5"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=localhost:2111:2112"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"2113,6"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=localhost:2114:2115:observer"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"2116"})]})]})})}),`
`,e.jsxs(n.p,{children:[`The format of the server statement is exactly as described in
`,e.jsx(n.a,{href:"#specifying-the-client-port",children:"Specifying the Client Port"}),` and includes the client
port. Note that `,e.jsx(n.code,{children:"5="})," can be used as a shorthand for ",e.jsx(n.code,{children:"server.5="}),`. In the example
above, if server 5 is already in the system with different ports or is not an
observer, it is updated — once the configuration commits it becomes an observer
using the new ports. This is an easy way to turn participants into observers and
vice versa, or change any ports, without rebooting the server.`]}),`
`,e.jsxs(n.p,{children:[`ZooKeeper supports two types of Quorum Systems: the simple Majority system (where
the leader commits operations after receiving ACKs from a majority of voters) and a
more complex Hierarchical system (where votes of different servers have different
weights and servers are divided into voting groups). Incremental reconfiguration is
currently allowed only if the last proposed configuration uses a Majority Quorum
System (`,e.jsx(n.code,{children:"BadArgumentsException"})," is thrown otherwise)."]}),`
`,e.jsx(n.p,{children:"Incremental mode — examples using the Java API:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"List<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"> leavingServers "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ArrayList<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:">();"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"leavingServers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"1"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"leavingServers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"2"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[] config "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"reconfig"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", leavingServers, "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"-"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" Stat"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"());"})]}),`
`,e.jsx(n.span,{className:"line"}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"List<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"> leavingServers "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ArrayList<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:">();"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"List<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"> joiningServers "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ArrayList<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:">();"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"leavingServers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"1"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"joiningServers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"server.4=localhost:1234:1235;1236"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[] config "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"reconfig"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(joiningServers, leavingServers, "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"-"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" Stat"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"());"})]}),`
`,e.jsx(n.span,{className:"line"}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"String configStr "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(config);"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"System.out."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"println"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(configStr);"})]})]})})}),`
`,e.jsxs(n.p,{children:[`There is also an asynchronous API, and an API accepting comma-separated Strings
instead of `,e.jsx(n.code,{children:"List<String>"}),`. See
`,e.jsx(n.code,{children:"src/java/main/org/apache/zookeeper/ZooKeeper.java"}),"."]}),`
`,e.jsx(n.h4,{id:"non-incremental-mode",children:"Non-incremental Mode"}),`
`,e.jsx(n.p,{children:`The non-incremental mode accepts a complete specification of the new dynamic
configuration. The new configuration can be given inline or read from a file:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" reconfig -file newconfig.cfg"})]})})})}),`
`,e.jsxs(n.p,{children:[e.jsx(n.code,{children:"newconfig.cfg"})," is a dynamic config file — see ",e.jsx(n.a,{href:"#dynamic-configuration-file",children:"Dynamic Configuration File"}),"."]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" reconfig -members "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"server.1"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=125.23.63.23:2780:2783:participant"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"2791,server.2"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=125.23.63.24:2781:2784:participant"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"2792,server.3"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"=125.23.63.25:2782:2785:participant"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"2793"})]})]})})}),`
`,e.jsx(n.p,{children:`The new configuration may use a different Quorum System. For example, you may
specify a Hierarchical Quorum System even if the current ensemble uses a Majority
Quorum System.`}),`
`,e.jsx(n.p,{children:"Bulk mode — example using the Java API:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"List<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"> newMembers "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ArrayList<"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:">();"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"newMembers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"server.1=1111:1234:1235;1236"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"newMembers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"server.2=1112:1237:1238;1239"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"newMembers."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"add"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"server.3=1114:1240:1241:observer;1242"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(n.span,{className:"line"}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[] config "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"reconfig"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", newMembers, "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"-"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" Stat"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"());"})]}),`
`,e.jsx(n.span,{className:"line"}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"String configStr "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(config);"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"System.out."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"println"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(configStr);"})]})]})})}),`
`,e.jsxs(n.p,{children:[`There is also an asynchronous API, and an API accepting a comma-separated String
containing the new members instead of `,e.jsx(n.code,{children:"List<String>"}),`. See
`,e.jsx(n.code,{children:"src/java/main/org/apache/zookeeper/ZooKeeper.java"}),"."]}),`
`,e.jsx(n.h4,{id:"conditional-reconfig",children:"Conditional Reconfig"}),`
`,e.jsxs(n.p,{children:[`Sometimes (especially in non-incremental mode) a proposed configuration depends
on what the client believes to be the current configuration, and should only be
applied to that configuration. The `,e.jsx(n.code,{children:"reconfig"}),` succeeds only if the last
configuration at the leader has the specified version:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" reconfig -file "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"<"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"filename"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" -v "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"<"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"version"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"})]})})})}),`
`,e.jsxs(n.p,{children:["In the Java examples above, instead of ",e.jsx(n.code,{children:"-1"}),` you can specify a configuration
version to condition the reconfiguration.`]}),`
`,e.jsx(n.h4,{id:"error-conditions",children:"Error Conditions"}),`
`,e.jsx(n.p,{children:`In addition to normal ZooKeeper error conditions, a reconfiguration may fail for
the following reasons:`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:["Another reconfig is currently in progress (",e.jsx(n.code,{children:"ReconfigInProgress"}),")."]}),`
`,e.jsxs(n.li,{children:[`The proposed change would leave fewer than 2 participants and standalone mode
is enabled, or, if standalone mode is disabled, fewer than 1 participant would
remain (`,e.jsx(n.code,{children:"BadArgumentsException"}),")."]}),`
`,e.jsxs(n.li,{children:[`No quorum of the new configuration was connected and up-to-date with the leader
when reconfiguration processing began (`,e.jsx(n.code,{children:"NewConfigNoQuorum"}),")."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.code,{children:"-v x"})," was specified but the latest configuration version ",e.jsx(n.code,{children:"y"})," is not ",e.jsx(n.code,{children:"x"}),`
(`,e.jsx(n.code,{children:"BadVersionException"}),")."]}),`
`,e.jsxs(n.li,{children:[`An incremental reconfiguration was requested but the last configuration at the
leader uses a Quorum System other than Majority (`,e.jsx(n.code,{children:"BadArgumentsException"}),")."]}),`
`,e.jsxs(n.li,{children:["Syntax error (",e.jsx(n.code,{children:"BadArgumentsException"}),")."]}),`
`,e.jsxs(n.li,{children:["I/O exception when reading the configuration from a file (",e.jsx(n.code,{children:"BadArgumentsException"}),")."]}),`
`]}),`
`,e.jsxs(n.p,{children:["Most of these are illustrated by test cases in ",e.jsx(n.code,{children:"ReconfigFailureCases.java"}),"."]}),`
`,e.jsx(n.h4,{id:"additional-comments",children:"Additional Comments"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Liveness:"}),` To understand the difference between incremental and non-incremental
reconfiguration, suppose client C1 adds server D while a different client C2 adds
server E. With non-incremental mode, each client first invokes `,e.jsx(n.code,{children:"config"}),` to find out
the current configuration, then locally creates a new server list by adding its own
suggested server. After both reconfigurations complete, only one of D or E will be
added (not both), depending on which request arrives second at the leader. The other
client can repeat the process until its change takes effect. This guarantees
system-wide progress (for one client) but not for every client. C2 may use
`,e.jsx(n.a,{href:"#conditional-reconfig",children:"Conditional reconfig"}),` to avoid blindly overwriting C1's
configuration if C1's request arrived first.`]}),`
`,e.jsx(n.p,{children:`With incremental reconfiguration, both changes take effect as they are applied by
the leader one after the other to the current configuration. Since both clients are
guaranteed to make progress, this method guarantees stronger liveness. In practice,
multiple concurrent reconfigurations are probably rare. Non-incremental reconfiguration
is currently the only way to dynamically change the Quorum System. Incremental
reconfiguration is currently only allowed with the Majority Quorum System.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Changing an observer into a follower:"}),` Changing a voting server into an observer
may fail if fewer than the minimal allowed number of participants would remain (error 2).
Converting an observer into a participant may sometimes fail for a more subtle reason.
Suppose the current configuration is (A, B, C, D), where A is the leader, B and C are
followers, and D is an observer, and B has crashed. If a reconfiguration makes D a
follower, it will fail with error 3 since a majority of voters in the new configuration
(any 3 voters) must be connected and up-to-date with the leader. An observer cannot
acknowledge the history prefix sent during reconfiguration and therefore does not count
towards the 3 required servers. In this case, a client can achieve the task with two
reconfig commands: first remove D from the configuration, then add it back as a
participant. During the intermediate state D is a non-voting follower and can ACK the
state transfer performed during the second reconfig command.`]}),`
`,e.jsx(n.h2,{id:"rebalancing-client-connections",children:"Rebalancing Client Connections"}),`
`,e.jsxs(n.p,{children:[`When a ZooKeeper cluster is started and each client is given the same connection
string, the client randomly chooses a server to connect to, making the expected
number of client connections per server equal across all servers. ZooKeeper preserves
this property when the set of servers changes through reconfiguration (see Sections
4 and 5.1 in the `,e.jsx(n.a,{href:"https://www.usenix.org/conference/usenixfederatedconferencesweek/dynamic-recon%EF%AC%81guration-primarybackup-clusters",children:"paper"}),")."]}),`
`,e.jsxs(n.p,{children:[`For the method to work, all clients must subscribe to configuration changes by setting
a watch on `,e.jsx(n.code,{children:"/zookeeper/config"})," — either directly or through the ",e.jsx(n.code,{children:"getConfig"}),` API. When
the watch is triggered, the client should read the new configuration by invoking
`,e.jsx(n.code,{children:"sync"})," and ",e.jsx(n.code,{children:"getConfig"}),", and if the configuration is indeed new, invoke ",e.jsx(n.code,{children:"updateServerList"}),`.
To avoid mass client migration at the same time, each client should sleep a random
short period before invoking `,e.jsx(n.code,{children:"updateServerList"}),"."]}),`
`,e.jsxs(n.p,{children:["A few examples can be found in ",e.jsx(n.code,{children:"StaticHostProviderTest.java"})," and ",e.jsx(n.code,{children:"TestReconfig.cc"}),"."]}),`
`,e.jsx(n.p,{children:"Example (simplified to illustrate the general idea, not a production recipe):"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"public"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" void"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" process"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(WatchedEvent event) {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    synchronized"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ("}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"        if"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (event."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getType"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"() "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" EventType.None) {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            connected "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (event."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getState"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"() "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" KeeperState.SyncConnected);"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            notifyAll"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"();"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        } "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" if"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (event."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getPath"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"() "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!="}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" null"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" &&"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" event."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getPath"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"()."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"equals"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZooDefs.CONFIG_NODE)) {"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"            // in prod code never block the event thread!"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"sync"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZooDefs.CONFIG_NODE, "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getConfig"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        }"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})}),`
`,e.jsx(n.span,{className:"line"}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"public"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" void"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" processResult"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" rc, String path, Object ctx, "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[] data, Stat stat) {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    if"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (path "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!="}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" null"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" &&"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" path."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"equals"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZooDefs.CONFIG_NODE)) {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        String config[] "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ConfigUtils."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getClientConfigStr"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"new"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" String"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(data))."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"split"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'" "'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");   "}),e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"// similar to config -c"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"        long"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" version "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" Long."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"parseLong"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(config["}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"], "}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"16"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"        if"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ("}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:".configVersion "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" null"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"            this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:".configVersion "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" version;"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        } "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" if"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (version "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:".configVersion) {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            hostList "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" config["}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"];"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"            try"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"                // not blocking, but may cause the client to close the socket and migrate"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"                // to a different server; in practice wait a short random period so clients"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"                // migrate at different times"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"                zk."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"updateServerList"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(hostList);"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            } "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"catch"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (IOException "}),e.jsx(n.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"e"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"                System.err."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"println"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Error updating server list"'}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"                e."}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"printStackTrace"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"();"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            }"})}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"            this"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:".configVersion "}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" version;"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        }"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})})]})}function g(i={}){const{wrapper:n}=i.components||{};return n?e.jsx(n,{...i,children:e.jsx(s,{...i})}):s(i)}function r(i,n){throw new Error("Expected component `"+i+"` to be defined: you likely forgot to import, pass, or provide it.")}export{o as _markdown,g as default,c as extractedReferences,l as frontmatter,h as structuredData,d as toc};
