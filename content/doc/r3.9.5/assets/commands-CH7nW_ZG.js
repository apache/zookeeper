import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let r=`## The Four Letter Words

ZooKeeper responds to a small set of commands. Each command is
composed of four letters. You issue the commands to ZooKeeper via telnet
or nc, at the client port.

Three of the more interesting commands: "stat" gives some
general information about the server and connected clients,
while "srvr" and "cons" give extended details on server and
connections respectively.

**New in 3.5.3:**
Four Letter Words need to be explicitly white listed before using.
Please refer to **4lw\\.commands.whitelist**
described in [cluster configuration section](/admin-ops/administrators-guide/configuration-parameters#cluster-options) for details.
Moving forward, Four Letter Words will be deprecated, please use
[AdminServer](#the-adminserver) instead.

* *conf* :
  **New in 3.3.0:** Print
  details about serving configuration.

* *cons* :
  **New in 3.3.0:** List
  full connection/session details for all clients connected
  to this server. Includes information on numbers of packets
  received/sent, session id, operation latencies, last
  operation performed, etc...

* *crst* :
  **New in 3.3.0:** Reset
  connection/session statistics for all connections.

* *dump* :
  Lists the outstanding sessions and ephemeral nodes.

* *envi* :
  Print details about serving environment

* *ruok* :
  Tests if the server is running in a non-error state.
  When the whitelist enables ruok, the server will respond with \`imok\`
  if it is running, otherwise it will not respond at all.
  When ruok is disabled, the server responds with:
  "ruok is not executed because it is not in the whitelist."
  A response of "imok" does not necessarily indicate that the
  server has joined the quorum, just that the server process is active
  and bound to the specified client port. Use "stat" for details on
  state wrt quorum and client connection information.

* *srst* :
  Reset server statistics.

* *srvr* :
  **New in 3.3.0:** Lists
  full details for the server.

* *stat* :
  Lists brief details for the server and connected
  clients.

* *wchs* :
  **New in 3.3.0:** Lists
  brief information on watches for the server.

* *wchc* :
  **New in 3.3.0:** Lists
  detailed information on watches for the server, by
  session. This outputs a list of sessions(connections)
  with associated watches (paths). Note, depending on the
  number of watches this operation may be expensive (ie
  impact server performance), use it carefully.

* *dirs* :
  **New in 3.5.1:**
  Shows the total size of snapshot and log files in bytes

* *wchp* :
  **New in 3.3.0:** Lists
  detailed information on watches for the server, by path.
  This outputs a list of paths (znodes) with associated
  sessions. Note, depending on the number of watches this
  operation may be expensive (ie impact server performance),
  use it carefully.

* *mntr* :
  **New in 3.4.0:** Outputs a list
  of variables that could be used for monitoring the health of the cluster.

  \`\`\`bash
  $ echo mntr | nc localhost 2185
              zk_version  3.4.0
              zk_avg_latency  0.7561              - be account to four decimal places
              zk_max_latency  0
              zk_min_latency  0
              zk_packets_received 70
              zk_packets_sent 69
              zk_outstanding_requests 0
              zk_server_state leader
              zk_znode_count   4
              zk_watch_count  0
              zk_ephemerals_count 0
              zk_approximate_data_size    27
              zk_learners    4                    - only exposed by the Leader
              zk_synced_followers 4               - only exposed by the Leader
              zk_pending_syncs    0               - only exposed by the Leader
              zk_open_file_descriptor_count 23    - only available on Unix platforms
              zk_max_file_descriptor_count 1024   - only available on Unix platforms
  \`\`\`

  The output is compatible with java properties format and the content
  may change over time (new keys added). Your scripts should expect changes.
  ATTENTION: Some of the keys are platform specific and some of the keys are only exported by the Leader.
  The output contains multiple lines with the following format:

  \`\`\`
  key \\t value
  \`\`\`

* *isro* :
  **New in 3.4.0:** Tests if
  server is running in read-only mode. The server will respond with
  "ro" if in read-only mode or "rw" if not in read-only mode.

* *hash* :
  **New in 3.6.0:**
  Return the latest history of the tree digest associated with zxid.

* *gtmk* :
  Gets the current trace mask as a 64-bit signed long value in
  decimal format. See \`stmk\` for an explanation of
  the possible values.

* *stmk* :
  Sets the current trace mask. The trace mask is 64 bits,
  where each bit enables or disables a specific category of trace
  logging on the server. Logback must be configured to enable
  \`TRACE\` level first in order to see trace logging
  messages. The bits of the trace mask correspond to the following
  trace logging categories.

  | Trace Mask Bit Values |                                                                                                 |
  | --------------------- | ----------------------------------------------------------------------------------------------- |
  | 0b0000000000          | Unused, reserved for future use.                                                                |
  | 0b0000000010          | Logs client requests, excluding ping requests.                                                  |
  | 0b0000000100          | Unused, reserved for future use.                                                                |
  | 0b0000001000          | Logs client ping requests.                                                                      |
  | 0b0000010000          | Logs packets received from the quorum peer that is the current leader, excluding ping requests. |
  | 0b0000100000          | Logs addition, removal and validation of client sessions.                                       |
  | 0b0001000000          | Logs delivery of watch events to client sessions.                                               |
  | 0b0010000000          | Logs ping packets received from the quorum peer that is the current leader.                     |
  | 0b0100000000          | Unused, reserved for future use.                                                                |
  | 0b1000000000          | Unused, reserved for future use.                                                                |

  All remaining bits in the 64-bit value are unused and reserved for future use.
  Multiple trace logging categories are specified by calculating the bitwise OR of the documented values.
  The default trace mask is 0b0100110010. Thus, by default, trace logging includes client requests,
  packets received from the leader and sessions.
  To set a different trace mask, send a request containing the \`stmk\` four-letter word followed
  by the trace mask represented as a 64-bit signed long value. This example uses
  the Perl \`pack\` function to construct a trace mask that enables all trace logging categories
  described above and convert it to a 64-bit signed long value with big-endian byte order.
  The result is appended to \`stmk\` and sent to the server using netcat.
  The server responds with the new trace mask in decimal format.

  \`\`\`bash
  $ perl -e "print 'stmk', pack('q>', 0b0011111010)" | nc localhost 2181
  250
  \`\`\`

Here's an example of the **ruok**
command:

\`\`\`bash
$ echo ruok | nc 127.0.0.1 5111
    imok
\`\`\`

## The AdminServer

**New in 3.5.0:** The AdminServer is
an embedded Jetty server that provides an HTTP interface to the four-letter
word commands. By default, the server is started on port 8080,
and commands are issued by going to the URL "/commands/\\[command name]",
e.g., [http://localhost:8080/commands/stat](http://localhost:8080/commands/stat). The command response is
returned as JSON. Unlike the original protocol, commands are not
restricted to four-letter names, and commands can have multiple names;
for instance, "stmk" can also be referred to as "set\\_trace\\_mask". To
view a list of all available commands, point a browser to the URL
/commands (e.g., [http://localhost:8080/commands](http://localhost:8080/commands)). See the [AdminServer configuration options](#configuring-adminserver-for-ssltls)
for how to change the port and URLs.

The AdminServer is enabled by default, but can be disabled by either:

* Setting the zookeeper.admin.enableServer system
  property to false.
* Removing Jetty from the classpath. (This option is
  useful if you would like to override ZooKeeper's jetty
  dependency.)

Note that the TCP four-letter word interface is still available if
the AdminServer is disabled.

## Configuring AdminServer for SSL/TLS

* Generating the **keystore.jks** and **truststore.jks** which can be found in the [Quorum TLS](/admin-ops/administrators-guide/communication-using-the-netty-framework#quorum-tls).

* Add the following configuration settings to the \`zoo.cfg\` config file:

  \`\`\`
  admin.portUnification=true
  ssl.quorum.keyStore.location=/path/to/keystore.jks
  ssl.quorum.keyStore.password=password
  ssl.quorum.trustStore.location=/path/to/truststore.jks
  ssl.quorum.trustStore.password=password
  \`\`\`

* Verify that the following entries in the logs can be seen:

  \`\`\`
  2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer@123] - Successfully loaded private key from /data/software/cert/keystore.jks
  2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer@124] - Successfully loaded certificate authority from /data/software/cert/truststore.jks
  2019-08-03 15:44:55,403 [myid:] - INFO  [main:JettyAdminServer@170] - Started AdminServer on address 0.0.0.0, port 8080 and command URL /commands
  \`\`\`

### Restricting TLS protocols and cipher suites for AdminServer

**New in 3.10.0:** The AdminServer reuses these existing properties:

* **ssl.quorum.enabledProtocols** to specify the enabled protocols,
* **ssl.quorum.ciphersuites** to specify the enabled cipher suites.

Add the following configuration settings to the \`zoo.cfg\` config file:

\`\`\`
ssl.quorum.enabledProtocols=TLSv1.2,TLSv1.3
ssl.quorum.ciphersuites=TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
\`\`\`

To verify, raise the log level of \`JettyAdminServer\` to \`DEBUG\` and confirm these entries appear in the logs:

\`\`\`
DEBUG [main:o.a.z.s.a.JettyAdminServer] - Setting enabled protocols: 'TLSv1.2,TLSv1.3'
DEBUG [main:o.a.z.s.a.JettyAdminServer] - Setting enabled cipherSuites: 'TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384'
\`\`\`

Available commands include:

* *connection\\_stat\\_reset/crst*:
  Reset all client connection statistics.
  No new fields returned.

* *configuration/conf/config* :
  Print basic details about serving configuration, e.g.
  client port, absolute path to data directory.

* *connections/cons* :
  Information on client connections to server.
  Note, depending on the number of client connections this operation may be expensive
  (i.e. impact server performance).
  Returns "connections", a list of connection info objects.

* *hash*:
  Txn digests in the historical digest list.
  One is recorded every 128 transactions.
  Returns "digests", a list to transaction digest objects.

* *dirs* :
  Information on logfile directory and snapshot directory
  size in bytes.
  Returns "datadir\\_size" and "logdir\\_size".

* *dump* :
  Information on session expirations and ephemerals.
  Note, depending on the number of global sessions and ephemerals
  this operation may be expensive (i.e. impact server performance).
  Returns "expiry\\_time\\_to\\_session\\_ids" and "session\\_id\\_to\\_ephemeral\\_paths" as maps.

* *environment/env/envi* :
  All defined environment variables.
  Returns each as its own field.

* *get\\_trace\\_mask/gtmk* :
  The current trace mask. Read-only version of *set\\_trace\\_mask*.
  See the description of the four letter command *stmk* for
  more details.
  Returns "tracemask".

* *initial\\_configuration/icfg* :
  Print the text of the configuration file used to start the peer.
  Returns "initial\\_configuration".

* *is\\_read\\_only/isro* :
  A true/false if this server is in read-only mode.
  Returns "read\\_only".

* *last\\_snapshot/lsnp* :
  Information of the last snapshot that zookeeper server has finished saving to disk.
  If called during the initial time period between the server starting up
  and the server finishing saving its first snapshot, the command returns the
  information of the snapshot read when starting up the server.
  Returns "zxid" and "timestamp", the latter using a time unit of seconds.

* *leader/lead* :
  If the ensemble is configured in quorum mode then emits the current leader
  status of the peer and the current leader location.
  Returns "is\\_leader", "leader\\_id", and "leader\\_ip".

* *monitor/mntr* :
  Emits a wide variety of useful info for monitoring.
  Includes performance stats, information about internal queues, and
  summaries of the data tree (among other things).
  Returns each as its own field.

* *observer\\_connection\\_stat\\_reset/orst* :
  Reset all observer connection statistics. Companion command to *observers*.
  No new fields returned.

* *restore/rest* :
  Restore database from snapshot input stream on the current server.
  Returns the following data in response payload:
  "last\\_zxid": String
  Note: this API is rate-limited (once every 5 mins by default) to protect the server
  from being over-loaded.

* *ruok* :
  No-op command, check if the server is running.
  A response does not necessarily indicate that the
  server has joined the quorum, just that the admin server
  is active and bound to the specified port.
  No new fields returned.

* *set\\_trace\\_mask/stmk* :
  Sets the trace mask (as such, it requires a parameter).
  Write version of *get\\_trace\\_mask*.
  See the description of the four letter command *stmk* for
  more details.
  Returns "tracemask".

* *server\\_stats/srvr* :
  Server information.
  Returns multiple fields giving a brief overview of server state.

* *shed\\_connections/shed* :
  Attempts to shed approximately the specified percentage of connections.
  Requires "percentage": (int)
  Returns "connections\\_shed" (int) and "percentage\\_requested" (int)

* *snapshot/snap* :
  Takes a snapshot of the current server in the datadir and stream out data.
  Optional query parameter:
  "streaming": Boolean (defaults to true if the parameter is not present)
  Returns the following via Http headers:
  "last\\_zxid": String
  "snapshot\\_size": String
  Note: this API is rate-limited (once every 5 mins by default) to protect the server
  from being over-loaded.

* *stats/stat* :
  Same as *server\\_stats* but also returns the "connections" field (see *connections*
  for details).
  Note, depending on the number of client connections this operation may be expensive
  (i.e. impact server performance).

* *stat\\_reset/srst* :
  Resets server statistics. This is a subset of the information returned
  by *server\\_stats* and *stats*.
  No new fields returned.

* *observers/obsr* :
  Information on observer connections to server.
  Always available on a Leader, available on a Follower if its
  acting as a learner master.
  Returns "synced\\_observers" (int) and "observers" (list of per-observer properties).

* *system\\_properties/sysp* :
  All defined system properties.
  Returns each as its own field.

* *voting\\_view* :
  Provides the current voting members in the ensemble.
  Returns "current\\_config" as a map.

* *watches/wchc* :
  Watch information aggregated by session.
  Note, depending on the number of watches this operation may be expensive
  (i.e. impact server performance).
  Returns "session\\_id\\_to\\_watched\\_paths" as a map.

* *watches\\_by\\_path/wchp* :
  Watch information aggregated by path.
  Note, depending on the number of watches this operation may be expensive
  (i.e. impact server performance).
  Returns "path\\_to\\_session\\_ids" as a map.

* *watch\\_summary/wchs* :
  Summarized watch information.
  Returns "num\\_total\\_watches", "num\\_paths", and "num\\_connections".

* *zabstate* :
  The current phase of Zab protocol that peer is running and whether it is a
  voting member.
  Peers can be in one of these phases: ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST.
  Returns fields "voting" and "zabstate".
`,o={title:"Commands",description:"Reference for ZooKeeper's built-in commands: the four-letter words issued via telnet or nc, and the HTTP-based AdminServer interface with its endpoints and SSL/TLS configuration."},a=[{href:"/admin-ops/administrators-guide/configuration-parameters#cluster-options"},{href:"#the-adminserver"},{href:"http://localhost:8080/commands/stat"},{href:"http://localhost:8080/commands"},{href:"#configuring-adminserver-for-ssltls"},{href:"/admin-ops/administrators-guide/communication-using-the-netty-framework#quorum-tls"}],d={contents:[{heading:"the-four-letter-words",content:`ZooKeeper responds to a small set of commands. Each command is
composed of four letters. You issue the commands to ZooKeeper via telnet
or nc, at the client port.`},{heading:"the-four-letter-words",content:`Three of the more interesting commands: "stat" gives some
general information about the server and connected clients,
while "srvr" and "cons" give extended details on server and
connections respectively.`},{heading:"the-four-letter-words",content:`New in 3.5.3:
Four Letter Words need to be explicitly white listed before using.
Please refer to 4lw.commands.whitelist
described in cluster configuration section for details.
Moving forward, Four Letter Words will be deprecated, please use
AdminServer instead.`},{heading:"the-four-letter-words",content:`conf :
New in 3.3.0: Print
details about serving configuration.`},{heading:"the-four-letter-words",content:`cons :
New in 3.3.0: List
full connection/session details for all clients connected
to this server. Includes information on numbers of packets
received/sent, session id, operation latencies, last
operation performed, etc...`},{heading:"the-four-letter-words",content:`crst :
New in 3.3.0: Reset
connection/session statistics for all connections.`},{heading:"the-four-letter-words",content:`dump :
Lists the outstanding sessions and ephemeral nodes.`},{heading:"the-four-letter-words",content:`envi :
Print details about serving environment`},{heading:"the-four-letter-words",content:`ruok :
Tests if the server is running in a non-error state.
When the whitelist enables ruok, the server will respond with imok
if it is running, otherwise it will not respond at all.
When ruok is disabled, the server responds with:
"ruok is not executed because it is not in the whitelist."
A response of "imok" does not necessarily indicate that the
server has joined the quorum, just that the server process is active
and bound to the specified client port. Use "stat" for details on
state wrt quorum and client connection information.`},{heading:"the-four-letter-words",content:`srst :
Reset server statistics.`},{heading:"the-four-letter-words",content:`srvr :
New in 3.3.0: Lists
full details for the server.`},{heading:"the-four-letter-words",content:`stat :
Lists brief details for the server and connected
clients.`},{heading:"the-four-letter-words",content:`wchs :
New in 3.3.0: Lists
brief information on watches for the server.`},{heading:"the-four-letter-words",content:`wchc :
New in 3.3.0: Lists
detailed information on watches for the server, by
session. This outputs a list of sessions(connections)
with associated watches (paths). Note, depending on the
number of watches this operation may be expensive (ie
impact server performance), use it carefully.`},{heading:"the-four-letter-words",content:`dirs :
New in 3.5.1:
Shows the total size of snapshot and log files in bytes`},{heading:"the-four-letter-words",content:`wchp :
New in 3.3.0: Lists
detailed information on watches for the server, by path.
This outputs a list of paths (znodes) with associated
sessions. Note, depending on the number of watches this
operation may be expensive (ie impact server performance),
use it carefully.`},{heading:"the-four-letter-words",content:`mntr :
New in 3.4.0: Outputs a list
of variables that could be used for monitoring the health of the cluster.`},{heading:"the-four-letter-words",content:`The output is compatible with java properties format and the content
may change over time (new keys added). Your scripts should expect changes.
ATTENTION: Some of the keys are platform specific and some of the keys are only exported by the Leader.
The output contains multiple lines with the following format:`},{heading:"the-four-letter-words",content:`isro :
New in 3.4.0: Tests if
server is running in read-only mode. The server will respond with
"ro" if in read-only mode or "rw" if not in read-only mode.`},{heading:"the-four-letter-words",content:`hash :
New in 3.6.0:
Return the latest history of the tree digest associated with zxid.`},{heading:"the-four-letter-words",content:`gtmk :
Gets the current trace mask as a 64-bit signed long value in
decimal format. See stmk for an explanation of
the possible values.`},{heading:"the-four-letter-words",content:`stmk :
Sets the current trace mask. The trace mask is 64 bits,
where each bit enables or disables a specific category of trace
logging on the server. Logback must be configured to enable
TRACE level first in order to see trace logging
messages. The bits of the trace mask correspond to the following
trace logging categories.`},{heading:"the-four-letter-words",content:"Trace Mask Bit Values"},{heading:"the-four-letter-words",content:"0b0000000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b0000000010"},{heading:"the-four-letter-words",content:"Logs client requests, excluding ping requests."},{heading:"the-four-letter-words",content:"0b0000000100"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b0000001000"},{heading:"the-four-letter-words",content:"Logs client ping requests."},{heading:"the-four-letter-words",content:"0b0000010000"},{heading:"the-four-letter-words",content:"Logs packets received from the quorum peer that is the current leader, excluding ping requests."},{heading:"the-four-letter-words",content:"0b0000100000"},{heading:"the-four-letter-words",content:"Logs addition, removal and validation of client sessions."},{heading:"the-four-letter-words",content:"0b0001000000"},{heading:"the-four-letter-words",content:"Logs delivery of watch events to client sessions."},{heading:"the-four-letter-words",content:"0b0010000000"},{heading:"the-four-letter-words",content:"Logs ping packets received from the quorum peer that is the current leader."},{heading:"the-four-letter-words",content:"0b0100000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b1000000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:`All remaining bits in the 64-bit value are unused and reserved for future use.
Multiple trace logging categories are specified by calculating the bitwise OR of the documented values.
The default trace mask is 0b0100110010. Thus, by default, trace logging includes client requests,
packets received from the leader and sessions.
To set a different trace mask, send a request containing the stmk four-letter word followed
by the trace mask represented as a 64-bit signed long value. This example uses
the Perl pack function to construct a trace mask that enables all trace logging categories
described above and convert it to a 64-bit signed long value with big-endian byte order.
The result is appended to stmk and sent to the server using netcat.
The server responds with the new trace mask in decimal format.`},{heading:"the-four-letter-words",content:`Here's an example of the ruok
command:`},{heading:"the-adminserver",content:`New in 3.5.0: The AdminServer is
an embedded Jetty server that provides an HTTP interface to the four-letter
word commands. By default, the server is started on port 8080,
and commands are issued by going to the URL "/commands/[command name]",
e.g., http://localhost:8080/commands/stat. The command response is
returned as JSON. Unlike the original protocol, commands are not
restricted to four-letter names, and commands can have multiple names;
for instance, "stmk" can also be referred to as "set_trace_mask". To
view a list of all available commands, point a browser to the URL
/commands (e.g., http://localhost:8080/commands). See the AdminServer configuration options
for how to change the port and URLs.`},{heading:"the-adminserver",content:"The AdminServer is enabled by default, but can be disabled by either:"},{heading:"the-adminserver",content:`Setting the zookeeper.admin.enableServer system
property to false.`},{heading:"the-adminserver",content:`Removing Jetty from the classpath. (This option is
useful if you would like to override ZooKeeper's jetty
dependency.)`},{heading:"the-adminserver",content:`Note that the TCP four-letter word interface is still available if
the AdminServer is disabled.`},{heading:"configuring-adminserver-for-ssltls",content:"Generating the keystore.jks and truststore.jks which can be found in the Quorum TLS."},{heading:"configuring-adminserver-for-ssltls",content:"Add the following configuration settings to the zoo.cfg config file:"},{heading:"configuring-adminserver-for-ssltls",content:"Verify that the following entries in the logs can be seen:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"New in 3.10.0: The AdminServer reuses these existing properties:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"ssl.quorum.enabledProtocols to specify the enabled protocols,"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"ssl.quorum.ciphersuites to specify the enabled cipher suites."},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Add the following configuration settings to the zoo.cfg config file:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"To verify, raise the log level of JettyAdminServer to DEBUG and confirm these entries appear in the logs:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Available commands include:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`connection_stat_reset/crst:
Reset all client connection statistics.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`configuration/conf/config :
Print basic details about serving configuration, e.g.
client port, absolute path to data directory.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`connections/cons :
Information on client connections to server.
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).
Returns "connections", a list of connection info objects.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`hash:
Txn digests in the historical digest list.
One is recorded every 128 transactions.
Returns "digests", a list to transaction digest objects.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`dirs :
Information on logfile directory and snapshot directory
size in bytes.
Returns "datadir_size" and "logdir_size".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`dump :
Information on session expirations and ephemerals.
Note, depending on the number of global sessions and ephemerals
this operation may be expensive (i.e. impact server performance).
Returns "expiry_time_to_session_ids" and "session_id_to_ephemeral_paths" as maps.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`environment/env/envi :
All defined environment variables.
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`get_trace_mask/gtmk :
The current trace mask. Read-only version of set_trace_mask.
See the description of the four letter command stmk for
more details.
Returns "tracemask".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`initial_configuration/icfg :
Print the text of the configuration file used to start the peer.
Returns "initial_configuration".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`is_read_only/isro :
A true/false if this server is in read-only mode.
Returns "read_only".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`last_snapshot/lsnp :
Information of the last snapshot that zookeeper server has finished saving to disk.
If called during the initial time period between the server starting up
and the server finishing saving its first snapshot, the command returns the
information of the snapshot read when starting up the server.
Returns "zxid" and "timestamp", the latter using a time unit of seconds.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`leader/lead :
If the ensemble is configured in quorum mode then emits the current leader
status of the peer and the current leader location.
Returns "is_leader", "leader_id", and "leader_ip".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`monitor/mntr :
Emits a wide variety of useful info for monitoring.
Includes performance stats, information about internal queues, and
summaries of the data tree (among other things).
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`observer_connection_stat_reset/orst :
Reset all observer connection statistics. Companion command to observers.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`restore/rest :
Restore database from snapshot input stream on the current server.
Returns the following data in response payload:
"last_zxid": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`ruok :
No-op command, check if the server is running.
A response does not necessarily indicate that the
server has joined the quorum, just that the admin server
is active and bound to the specified port.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`set_trace_mask/stmk :
Sets the trace mask (as such, it requires a parameter).
Write version of get_trace_mask.
See the description of the four letter command stmk for
more details.
Returns "tracemask".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`server_stats/srvr :
Server information.
Returns multiple fields giving a brief overview of server state.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`shed_connections/shed :
Attempts to shed approximately the specified percentage of connections.
Requires "percentage": (int)
Returns "connections_shed" (int) and "percentage_requested" (int)`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`snapshot/snap :
Takes a snapshot of the current server in the datadir and stream out data.
Optional query parameter:
"streaming": Boolean (defaults to true if the parameter is not present)
Returns the following via Http headers:
"last_zxid": String
"snapshot_size": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`stats/stat :
Same as server_stats but also returns the "connections" field (see connections
for details).
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`stat_reset/srst :
Resets server statistics. This is a subset of the information returned
by server_stats and stats.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`observers/obsr :
Information on observer connections to server.
Always available on a Leader, available on a Follower if its
acting as a learner master.
Returns "synced_observers" (int) and "observers" (list of per-observer properties).`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`system_properties/sysp :
All defined system properties.
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`voting_view :
Provides the current voting members in the ensemble.
Returns "current_config" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`watches/wchc :
Watch information aggregated by session.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "session_id_to_watched_paths" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`watches_by_path/wchp :
Watch information aggregated by path.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "path_to_session_ids" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`watch_summary/wchs :
Summarized watch information.
Returns "num_total_watches", "num_paths", and "num_connections".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`zabstate :
The current phase of Zab protocol that peer is running and whether it is a
voting member.
Peers can be in one of these phases: ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST.
Returns fields "voting" and "zabstate".`}],headings:[{id:"the-four-letter-words",content:"The Four Letter Words"},{id:"the-adminserver",content:"The AdminServer"},{id:"configuring-adminserver-for-ssltls",content:"Configuring AdminServer for SSL/TLS"},{id:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Restricting TLS protocols and cipher suites for AdminServer"}]};const l=[{depth:2,url:"#the-four-letter-words",title:e.jsx(e.Fragment,{children:"The Four Letter Words"})},{depth:2,url:"#the-adminserver",title:e.jsx(e.Fragment,{children:"The AdminServer"})},{depth:2,url:"#configuring-adminserver-for-ssltls",title:e.jsx(e.Fragment,{children:"Configuring AdminServer for SSL/TLS"})},{depth:3,url:"#restricting-tls-protocols-and-cipher-suites-for-adminserver",title:e.jsx(e.Fragment,{children:"Restricting TLS protocols and cipher suites for AdminServer"})}];function t(s){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...s.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"the-four-letter-words",children:"The Four Letter Words"}),`
`,e.jsx(n.p,{children:`ZooKeeper responds to a small set of commands. Each command is
composed of four letters. You issue the commands to ZooKeeper via telnet
or nc, at the client port.`}),`
`,e.jsx(n.p,{children:`Three of the more interesting commands: "stat" gives some
general information about the server and connected clients,
while "srvr" and "cons" give extended details on server and
connections respectively.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5.3:"}),`
Four Letter Words need to be explicitly white listed before using.
Please refer to `,e.jsx(n.strong,{children:"4lw.commands.whitelist"}),`
described in `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters#cluster-options",children:"cluster configuration section"}),` for details.
Moving forward, Four Letter Words will be deprecated, please use
`,e.jsx(n.a,{href:"#the-adminserver",children:"AdminServer"})," instead."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"conf"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Print
details about serving configuration.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"cons"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` List
full connection/session details for all clients connected
to this server. Includes information on numbers of packets
received/sent, session id, operation latencies, last
operation performed, etc...`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"crst"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Reset
connection/session statistics for all connections.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dump"}),` :
Lists the outstanding sessions and ephemeral nodes.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"envi"}),` :
Print details about serving environment`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ruok"}),` :
Tests if the server is running in a non-error state.
When the whitelist enables ruok, the server will respond with `,e.jsx(n.code,{children:"imok"}),`
if it is running, otherwise it will not respond at all.
When ruok is disabled, the server responds with:
"ruok is not executed because it is not in the whitelist."
A response of "imok" does not necessarily indicate that the
server has joined the quorum, just that the server process is active
and bound to the specified client port. Use "stat" for details on
state wrt quorum and client connection information.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"srst"}),` :
Reset server statistics.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"srvr"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Lists
full details for the server.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"stat"}),` :
Lists brief details for the server and connected
clients.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"wchs"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Lists
brief information on watches for the server.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"wchc"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Lists
detailed information on watches for the server, by
session. This outputs a list of sessions(connections)
with associated watches (paths). Note, depending on the
number of watches this operation may be expensive (ie
impact server performance), use it carefully.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dirs"}),` :
`,e.jsx(n.strong,{children:"New in 3.5.1:"}),`
Shows the total size of snapshot and log files in bytes`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"wchp"}),` :
`,e.jsx(n.strong,{children:"New in 3.3.0:"}),` Lists
detailed information on watches for the server, by path.
This outputs a list of paths (znodes) with associated
sessions. Note, depending on the number of watches this
operation may be expensive (ie impact server performance),
use it carefully.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"mntr"}),` :
`,e.jsx(n.strong,{children:"New in 3.4.0:"}),` Outputs a list
of variables that could be used for monitoring the health of the cluster.`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" echo"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mntr"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" |"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" nc"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" localhost"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2185"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_version"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  3.4.0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_avg_latency"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  0.7561"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"              -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" be"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" account"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" to"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" four"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" decimal"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" places"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_max_latency"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_min_latency"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_packets_received"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 70"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_packets_sent"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 69"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_outstanding_requests"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_server_state"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" leader"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_znode_count"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"   4"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_watch_count"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"  0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_ephemerals_count"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_approximate_data_size"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    27"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_learners"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    4"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"                    -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" only"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" exposed"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" by"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Leader"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_synced_followers"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 4"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"               -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" only"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" exposed"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" by"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Leader"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_pending_syncs"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    0"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"               -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" only"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" exposed"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" by"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" the"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Leader"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_open_file_descriptor_count"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 23"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"    -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" only"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" available"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" on"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Unix"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" platforms"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zk_max_file_descriptor_count"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1024"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"   -"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" only"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" available"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" on"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" Unix"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" platforms"})]})]})})}),`
`,e.jsx(n.p,{children:`The output is compatible with java properties format and the content
may change over time (new keys added). Your scripts should expect changes.
ATTENTION: Some of the keys are platform specific and some of the keys are only exported by the Leader.
The output contains multiple lines with the following format:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"key \\t value"})})})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"isro"}),` :
`,e.jsx(n.strong,{children:"New in 3.4.0:"}),` Tests if
server is running in read-only mode. The server will respond with
"ro" if in read-only mode or "rw" if not in read-only mode.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"hash"}),` :
`,e.jsx(n.strong,{children:"New in 3.6.0:"}),`
Return the latest history of the tree digest associated with zxid.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"gtmk"}),` :
Gets the current trace mask as a 64-bit signed long value in
decimal format. See `,e.jsx(n.code,{children:"stmk"}),` for an explanation of
the possible values.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"stmk"}),` :
Sets the current trace mask. The trace mask is 64 bits,
where each bit enables or disables a specific category of trace
logging on the server. Logback must be configured to enable
`,e.jsx(n.code,{children:"TRACE"}),` level first in order to see trace logging
messages. The bits of the trace mask correspond to the following
trace logging categories.`]}),`
`,e.jsxs(n.table,{children:[e.jsx(n.thead,{children:e.jsxs(n.tr,{children:[e.jsx(n.th,{children:"Trace Mask Bit Values"}),e.jsx(n.th,{})]})}),e.jsxs(n.tbody,{children:[e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000000000"}),e.jsx(n.td,{children:"Unused, reserved for future use."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000000010"}),e.jsx(n.td,{children:"Logs client requests, excluding ping requests."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000000100"}),e.jsx(n.td,{children:"Unused, reserved for future use."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000001000"}),e.jsx(n.td,{children:"Logs client ping requests."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000010000"}),e.jsx(n.td,{children:"Logs packets received from the quorum peer that is the current leader, excluding ping requests."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0000100000"}),e.jsx(n.td,{children:"Logs addition, removal and validation of client sessions."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0001000000"}),e.jsx(n.td,{children:"Logs delivery of watch events to client sessions."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0010000000"}),e.jsx(n.td,{children:"Logs ping packets received from the quorum peer that is the current leader."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b0100000000"}),e.jsx(n.td,{children:"Unused, reserved for future use."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"0b1000000000"}),e.jsx(n.td,{children:"Unused, reserved for future use."})]})]})]}),`
`,e.jsxs(n.p,{children:[`All remaining bits in the 64-bit value are unused and reserved for future use.
Multiple trace logging categories are specified by calculating the bitwise OR of the documented values.
The default trace mask is 0b0100110010. Thus, by default, trace logging includes client requests,
packets received from the leader and sessions.
To set a different trace mask, send a request containing the `,e.jsx(n.code,{children:"stmk"}),` four-letter word followed
by the trace mask represented as a 64-bit signed long value. This example uses
the Perl `,e.jsx(n.code,{children:"pack"}),` function to construct a trace mask that enables all trace logging categories
described above and convert it to a 64-bit signed long value with big-endian byte order.
The result is appended to `,e.jsx(n.code,{children:"stmk"}),` and sent to the server using netcat.
The server responds with the new trace mask in decimal format.`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" perl"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -e"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:` "print 'stmk', pack('q>', 0b0011111010)"`}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" |"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" nc"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" localhost"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2181"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"250"})})]})})}),`
`]}),`
`]}),`
`,e.jsxs(n.p,{children:["Here's an example of the ",e.jsx(n.strong,{children:"ruok"}),`
command:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" echo"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" ruok"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" |"}),e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" nc"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 127.0.0.1"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 5111"})]}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    imok"})})]})})}),`
`,e.jsx(n.h2,{id:"the-adminserver",children:"The AdminServer"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5.0:"}),` The AdminServer is
an embedded Jetty server that provides an HTTP interface to the four-letter
word commands. By default, the server is started on port 8080,
and commands are issued by going to the URL "/commands/[command name]",
e.g., `,e.jsx(n.a,{href:"http://localhost:8080/commands/stat",children:"http://localhost:8080/commands/stat"}),`. The command response is
returned as JSON. Unlike the original protocol, commands are not
restricted to four-letter names, and commands can have multiple names;
for instance, "stmk" can also be referred to as "set_trace_mask". To
view a list of all available commands, point a browser to the URL
/commands (e.g., `,e.jsx(n.a,{href:"http://localhost:8080/commands",children:"http://localhost:8080/commands"}),"). See the ",e.jsx(n.a,{href:"#configuring-adminserver-for-ssltls",children:"AdminServer configuration options"}),`
for how to change the port and URLs.`]}),`
`,e.jsx(n.p,{children:"The AdminServer is enabled by default, but can be disabled by either:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`Setting the zookeeper.admin.enableServer system
property to false.`}),`
`,e.jsx(n.li,{children:`Removing Jetty from the classpath. (This option is
useful if you would like to override ZooKeeper's jetty
dependency.)`}),`
`]}),`
`,e.jsx(n.p,{children:`Note that the TCP four-letter word interface is still available if
the AdminServer is disabled.`}),`
`,e.jsx(n.h2,{id:"configuring-adminserver-for-ssltls",children:"Configuring AdminServer for SSL/TLS"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Generating the ",e.jsx(n.strong,{children:"keystore.jks"})," and ",e.jsx(n.strong,{children:"truststore.jks"})," which can be found in the ",e.jsx(n.a,{href:"/admin-ops/administrators-guide/communication-using-the-netty-framework#quorum-tls",children:"Quorum TLS"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Add the following configuration settings to the ",e.jsx(n.code,{children:"zoo.cfg"})," config file:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"admin.portUnification=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.location=/path/to/keystore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.password=password"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.location=/path/to/truststore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.password=password"})})]})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"Verify that the following entries in the logs can be seen:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer@123] - Successfully loaded private key from /data/software/cert/keystore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer@124] - Successfully loaded certificate authority from /data/software/cert/truststore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,403 [myid:] - INFO  [main:JettyAdminServer@170] - Started AdminServer on address 0.0.0.0, port 8080 and command URL /commands"})})]})})}),`
`]}),`
`]}),`
`,e.jsx(n.h3,{id:"restricting-tls-protocols-and-cipher-suites-for-adminserver",children:"Restricting TLS protocols and cipher suites for AdminServer"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.10.0:"})," The AdminServer reuses these existing properties:"]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"ssl.quorum.enabledProtocols"})," to specify the enabled protocols,"]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"ssl.quorum.ciphersuites"})," to specify the enabled cipher suites."]}),`
`]}),`
`,e.jsxs(n.p,{children:["Add the following configuration settings to the ",e.jsx(n.code,{children:"zoo.cfg"})," config file:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.enabledProtocols=TLSv1.2,TLSv1.3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.ciphersuites=TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"})})]})})}),`
`,e.jsxs(n.p,{children:["To verify, raise the log level of ",e.jsx(n.code,{children:"JettyAdminServer"})," to ",e.jsx(n.code,{children:"DEBUG"})," and confirm these entries appear in the logs:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"DEBUG [main:o.a.z.s.a.JettyAdminServer] - Setting enabled protocols: 'TLSv1.2,TLSv1.3'"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"DEBUG [main:o.a.z.s.a.JettyAdminServer] - Setting enabled cipherSuites: 'TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384'"})})]})})}),`
`,e.jsx(n.p,{children:"Available commands include:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connection_stat_reset/crst"}),`:
Reset all client connection statistics.
No new fields returned.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"configuration/conf/config"}),` :
Print basic details about serving configuration, e.g.
client port, absolute path to data directory.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"connections/cons"}),` :
Information on client connections to server.
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).
Returns "connections", a list of connection info objects.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"hash"}),`:
Txn digests in the historical digest list.
One is recorded every 128 transactions.
Returns "digests", a list to transaction digest objects.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dirs"}),` :
Information on logfile directory and snapshot directory
size in bytes.
Returns "datadir_size" and "logdir_size".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"dump"}),` :
Information on session expirations and ephemerals.
Note, depending on the number of global sessions and ephemerals
this operation may be expensive (i.e. impact server performance).
Returns "expiry_time_to_session_ids" and "session_id_to_ephemeral_paths" as maps.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"environment/env/envi"}),` :
All defined environment variables.
Returns each as its own field.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"get_trace_mask/gtmk"}),` :
The current trace mask. Read-only version of `,e.jsx(n.em,{children:"set_trace_mask"}),`.
See the description of the four letter command `,e.jsx(n.em,{children:"stmk"}),` for
more details.
Returns "tracemask".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"initial_configuration/icfg"}),` :
Print the text of the configuration file used to start the peer.
Returns "initial_configuration".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"is_read_only/isro"}),` :
A true/false if this server is in read-only mode.
Returns "read_only".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"last_snapshot/lsnp"}),` :
Information of the last snapshot that zookeeper server has finished saving to disk.
If called during the initial time period between the server starting up
and the server finishing saving its first snapshot, the command returns the
information of the snapshot read when starting up the server.
Returns "zxid" and "timestamp", the latter using a time unit of seconds.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"leader/lead"}),` :
If the ensemble is configured in quorum mode then emits the current leader
status of the peer and the current leader location.
Returns "is_leader", "leader_id", and "leader_ip".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"monitor/mntr"}),` :
Emits a wide variety of useful info for monitoring.
Includes performance stats, information about internal queues, and
summaries of the data tree (among other things).
Returns each as its own field.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"observer_connection_stat_reset/orst"}),` :
Reset all observer connection statistics. Companion command to `,e.jsx(n.em,{children:"observers"}),`.
No new fields returned.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"restore/rest"}),` :
Restore database from snapshot input stream on the current server.
Returns the following data in response payload:
"last_zxid": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ruok"}),` :
No-op command, check if the server is running.
A response does not necessarily indicate that the
server has joined the quorum, just that the admin server
is active and bound to the specified port.
No new fields returned.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"set_trace_mask/stmk"}),` :
Sets the trace mask (as such, it requires a parameter).
Write version of `,e.jsx(n.em,{children:"get_trace_mask"}),`.
See the description of the four letter command `,e.jsx(n.em,{children:"stmk"}),` for
more details.
Returns "tracemask".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"server_stats/srvr"}),` :
Server information.
Returns multiple fields giving a brief overview of server state.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"shed_connections/shed"}),` :
Attempts to shed approximately the specified percentage of connections.
Requires "percentage": (int)
Returns "connections_shed" (int) and "percentage_requested" (int)`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"snapshot/snap"}),` :
Takes a snapshot of the current server in the datadir and stream out data.
Optional query parameter:
"streaming": Boolean (defaults to true if the parameter is not present)
Returns the following via Http headers:
"last_zxid": String
"snapshot_size": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"stats/stat"}),` :
Same as `,e.jsx(n.em,{children:"server_stats"}),' but also returns the "connections" field (see ',e.jsx(n.em,{children:"connections"}),`
for details).
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"stat_reset/srst"}),` :
Resets server statistics. This is a subset of the information returned
by `,e.jsx(n.em,{children:"server_stats"})," and ",e.jsx(n.em,{children:"stats"}),`.
No new fields returned.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"observers/obsr"}),` :
Information on observer connections to server.
Always available on a Leader, available on a Follower if its
acting as a learner master.
Returns "synced_observers" (int) and "observers" (list of per-observer properties).`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"system_properties/sysp"}),` :
All defined system properties.
Returns each as its own field.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"voting_view"}),` :
Provides the current voting members in the ensemble.
Returns "current_config" as a map.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watches/wchc"}),` :
Watch information aggregated by session.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "session_id_to_watched_paths" as a map.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watches_by_path/wchp"}),` :
Watch information aggregated by path.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "path_to_session_ids" as a map.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"watch_summary/wchs"}),` :
Summarized watch information.
Returns "num_total_watches", "num_paths", and "num_connections".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zabstate"}),` :
The current phase of Zab protocol that peer is running and whether it is a
voting member.
Peers can be in one of these phases: ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST.
Returns fields "voting" and "zabstate".`]}),`
`]}),`
`]})]})}function h(s={}){const{wrapper:n}=s.components||{};return n?e.jsx(n,{...s,children:e.jsx(t,{...s})}):t(s)}export{r as _markdown,h as default,a as extractedReferences,o as frontmatter,d as structuredData,l as toc};
