import{j as e}from"./jsx-runtime-DtsG3kPy.js";let r=`## The Four Letter Words

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

**New in 3.5.0:** The AdminServer is an embedded Jetty server that provides an HTTP interface to the four-letter word
commands. In ZooKeeper releases 3.5.0 through 3.9.x, the AdminServer was enabled by default. Starting with ZooKeeper
3.10.0, the default configuration disables the AdminServer. When enabled, the server listens on port 8080 by default,
and commands are issued by accessing the URL \`/commands/[command name]\`, for example, \`http://localhost:8080/commands/stat\`.
Command responses are returned in JSON format.

Unlike the original protocol, commands are not restricted to four-letter names, and commands can have multiple aliases;
for example, \`stmk\` can also be referred to as \`set_trace_mask\`. To view a list of all available commands, access the
\`/commands\` endpoint (for example, \`http://localhost:8080/commands\`). See the AdminServer configuration options for
information on changing the port and URL mappings.

Beginning with ZooKeeper 3.10.0, the AdminServer is disabled by default and can be enabled by setting the
\`zookeeper.admin.enableServer\` system property to \`true\`. When enabled without additional configuration, the AdminServer
listens on all network interfaces (\`0.0.0.0\`), uses unencrypted HTTP, and does not require client authentication.
Administrators are strongly encouraged to restrict network access and configure appropriate transport security and
authentication before exposing the AdminServer in production environments.

Make sure that Jetty is available on the classpath, because the AdminServer will automatically remain disabled if Jetty
cannot be found. As of 3.10.0 that means \`jetty-server\`, \`jetty-ee10-servlet\` and \`jakarta.servlet-api\`; earlier
releases used \`jetty-servlet\` and \`javax.servlet-api\` instead. This behavior can be useful when overriding
ZooKeeper's Jetty dependency.

Note that the TCP four-letter word interface is still available for monitoring purposes if
the AdminServer is disabled.

### Security Considerations

> **Important:** The AdminServer is disabled by default. When enabled without additional configuration,
> it listens on all network interfaces (0.0.0.0) on port 8080, uses unencrypted HTTP, and does not
> require client authentication. As a result, most administrative commands are accessible to any client
> that can reach the AdminServer. Administrators should restrict access appropriately and enable transport
> security and authentication when deploying the AdminServer in production.

### Default Security Posture

The default AdminServer configuration is intended for ease of use in trusted environments, but it is **not secure for
exposure to untrusted networks**.

Default settings include:

* \`admin.enableServer=false\`
* \`admin.serverAddress=0.0.0.0\`
* \`admin.serverPort=8080\`
* \`admin.forceHttps=false\`
* \`admin.needClientAuth=false\`

With these defaults:

* All traffic is transmitted in clear text over HTTP.
* Administrative data, including configuration and runtime details, can be viewed by anyone with network access.
* Many commands (such as \`stat\`, \`srvr\`, \`conf\`, and \`cons\`) do not require authentication.
* Sensitive operational information may be disclosed.
* In some environments, unrestricted administrative access can increase the impact of vulnerabilities or misconfiguration.

### Recommended Deployment Practices

Administrators should ensure that the AdminServer is accessible only to authorized users.

#### Option 1: Restrict Access with Firewall Rules (Minimum Recommendation)

Limit access to the AdminServer port to trusted hosts or management networks only.

Examples:

* Bind the server to localhost:

  \`\`\`properties
  admin.serverAddress=127.0.0.1
  \`\`\`

* Use host-based firewall rules (such as \`iptables\`, \`firewalld\`, or cloud security groups) to allow access only from

* administrative systems.

This is the minimum recommended protection when HTTPS and client authentication are not enabled.

#### Option 2: Enable HTTPS

To encrypt all communication, configure SSL/TLS and force HTTPS:

\`\`\`properties
admin.forceHttps=true
\`\`\`

This prevents credentials and administrative data from being transmitted in clear text. ZooKeeper supports configuring
the AdminServer with TLS certificates and trust stores.

#### Option 3: Require Client Authentication

To restrict access to trusted clients using X.509 certificates:

\`\`\`properties
admin.needClientAuth=true
\`\`\`

When enabled, only clients presenting valid certificates trusted by the server will be allowed to connect.

### Recommended Secure Configuration

For production environments, the following configuration is strongly recommended:

\`\`\`properties
admin.forceHttps=true
admin.needClientAuth=true
admin.serverAddress=<management-network-ip>
\`\`\`

In addition, restrict access to the AdminServer port using firewall rules.

### Leave AdminServer disabled If Not Needed

If you do not use the AdminServer, disable it explicitly:

\`\`\`properties
admin.enableServer=false
\`\`\`

### Security Warning

Exposing the AdminServer to untrusted networks with the default configuration may allow unauthorized users to:

* Retrieve server configuration and runtime information
* Inspect connected clients and sessions
* Reset statistics
* Execute other administrative commands

Always protect the AdminServer with **network-level controls** and, preferably, **HTTPS with client certificate authentication**.

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
  2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer] - Successfully loaded private key from /data/software/cert/keystore.jks
  2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer] - Successfully loaded certificate authority from /data/software/cert/truststore.jks
  2019-08-03 15:44:55,403 [myid:] - INFO  [main:JettyAdminServer] - Started AdminServer on address 0.0.0.0, port 8080 and command URL /commands
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
`,o={title:"Commands",description:"Reference for ZooKeeper's built-in commands: the four-letter words issued via telnet or nc, and the HTTP-based AdminServer interface with its endpoints and SSL/TLS configuration."},a=[{href:"/admin-ops/administrators-guide/configuration-parameters#cluster-options"},{href:"#the-adminserver"},{href:"/admin-ops/administrators-guide/communication-using-the-netty-framework#quorum-tls"}],d={contents:[{heading:"the-four-letter-words",content:`ZooKeeper responds to a small set of commands. Each command is
composed of four letters. You issue the commands to ZooKeeper via telnet
or nc, at the client port.`},{heading:"the-four-letter-words",content:`Three of the more interesting commands: "stat" gives some
general information about the server and connected clients,
while "srvr" and "cons" give extended details on server and
connections respectively.`},{heading:"the-four-letter-words",content:`**New in 3.5.3:**
Four Letter Words need to be explicitly white listed before using.
Please refer to **4lw\\.commands.whitelist**
described in cluster configuration section for details.
Moving forward, Four Letter Words will be deprecated, please use
AdminServer instead.`},{heading:"the-four-letter-words",content:`*conf* :
&#x2A;*New in 3.3.0:** Print
details about serving configuration.`},{heading:"the-four-letter-words",content:`*cons* :
&#x2A;*New in 3.3.0:** List
full connection/session details for all clients connected
to this server. Includes information on numbers of packets
received/sent, session id, operation latencies, last
operation performed, etc...`},{heading:"the-four-letter-words",content:`*crst* :
&#x2A;*New in 3.3.0:** Reset
connection/session statistics for all connections.`},{heading:"the-four-letter-words",content:`*dump* :
Lists the outstanding sessions and ephemeral nodes.`},{heading:"the-four-letter-words",content:`*envi* :
Print details about serving environment`},{heading:"the-four-letter-words",content:`*ruok* :
Tests if the server is running in a non-error state.
When the whitelist enables ruok, the server will respond with \`imok\`
if it is running, otherwise it will not respond at all.
When ruok is disabled, the server responds with:
"ruok is not executed because it is not in the whitelist."
A response of "imok" does not necessarily indicate that the
server has joined the quorum, just that the server process is active
and bound to the specified client port. Use "stat" for details on
state wrt quorum and client connection information.`},{heading:"the-four-letter-words",content:`*srst* :
Reset server statistics.`},{heading:"the-four-letter-words",content:`*srvr* :
&#x2A;*New in 3.3.0:** Lists
full details for the server.`},{heading:"the-four-letter-words",content:`*stat* :
Lists brief details for the server and connected
clients.`},{heading:"the-four-letter-words",content:`*wchs* :
&#x2A;*New in 3.3.0:** Lists
brief information on watches for the server.`},{heading:"the-four-letter-words",content:`*wchc* :
&#x2A;*New in 3.3.0:** Lists
detailed information on watches for the server, by
session. This outputs a list of sessions(connections)
with associated watches (paths). Note, depending on the
number of watches this operation may be expensive (ie
impact server performance), use it carefully.`},{heading:"the-four-letter-words",content:`*dirs* :
&#x2A;*New in 3.5.1:**
Shows the total size of snapshot and log files in bytes`},{heading:"the-four-letter-words",content:`*wchp* :
&#x2A;*New in 3.3.0:** Lists
detailed information on watches for the server, by path.
This outputs a list of paths (znodes) with associated
sessions. Note, depending on the number of watches this
operation may be expensive (ie impact server performance),
use it carefully.`},{heading:"the-four-letter-words",content:`*mntr* :
&#x2A;*New in 3.4.0:** Outputs a list
of variables that could be used for monitoring the health of the cluster.`},{heading:"the-four-letter-words",content:`The output is compatible with java properties format and the content
may change over time (new keys added). Your scripts should expect changes.
ATTENTION: Some of the keys are platform specific and some of the keys are only exported by the Leader.
The output contains multiple lines with the following format:`},{heading:"the-four-letter-words",content:`*isro* :
&#x2A;*New in 3.4.0:** Tests if
server is running in read-only mode. The server will respond with
"ro" if in read-only mode or "rw" if not in read-only mode.`},{heading:"the-four-letter-words",content:`*hash* :
&#x2A;*New in 3.6.0:**
Return the latest history of the tree digest associated with zxid.`},{heading:"the-four-letter-words",content:`*gtmk* :
Gets the current trace mask as a 64-bit signed long value in
decimal format. See \`stmk\` for an explanation of
the possible values.`},{heading:"the-four-letter-words",content:`*stmk* :
Sets the current trace mask. The trace mask is 64 bits,
where each bit enables or disables a specific category of trace
logging on the server. Logback must be configured to enable
\`TRACE\` level first in order to see trace logging
messages. The bits of the trace mask correspond to the following
trace logging categories.`},{heading:"the-four-letter-words",content:"Trace Mask Bit Values"},{heading:"the-four-letter-words",content:"0b0000000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b0000000010"},{heading:"the-four-letter-words",content:"Logs client requests, excluding ping requests."},{heading:"the-four-letter-words",content:"0b0000000100"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b0000001000"},{heading:"the-four-letter-words",content:"Logs client ping requests."},{heading:"the-four-letter-words",content:"0b0000010000"},{heading:"the-four-letter-words",content:"Logs packets received from the quorum peer that is the current leader, excluding ping requests."},{heading:"the-four-letter-words",content:"0b0000100000"},{heading:"the-four-letter-words",content:"Logs addition, removal and validation of client sessions."},{heading:"the-four-letter-words",content:"0b0001000000"},{heading:"the-four-letter-words",content:"Logs delivery of watch events to client sessions."},{heading:"the-four-letter-words",content:"0b0010000000"},{heading:"the-four-letter-words",content:"Logs ping packets received from the quorum peer that is the current leader."},{heading:"the-four-letter-words",content:"0b0100000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:"0b1000000000"},{heading:"the-four-letter-words",content:"Unused, reserved for future use."},{heading:"the-four-letter-words",content:`All remaining bits in the 64-bit value are unused and reserved for future use.
Multiple trace logging categories are specified by calculating the bitwise OR of the documented values.
The default trace mask is 0b0100110010. Thus, by default, trace logging includes client requests,
packets received from the leader and sessions.
To set a different trace mask, send a request containing the \`stmk\` four-letter word followed
by the trace mask represented as a 64-bit signed long value. This example uses
the Perl \`pack\` function to construct a trace mask that enables all trace logging categories
described above and convert it to a 64-bit signed long value with big-endian byte order.
The result is appended to \`stmk\` and sent to the server using netcat.
The server responds with the new trace mask in decimal format.`},{heading:"the-four-letter-words",content:`Here's an example of the **ruok**
command:`},{heading:"the-adminserver",content:"**New in 3.5.0:** The AdminServer is an embedded Jetty server that provides an HTTP interface to the four-letter word\ncommands. In ZooKeeper releases 3.5.0 through 3.9.x, the AdminServer was enabled by default. Starting with ZooKeeper\n3.10.0, the default configuration disables the AdminServer. When enabled, the server listens on port 8080 by default,\nand commands are issued by accessing the URL `/commands/[command name]`, for example, `http://localhost:8080/commands/stat`.\nCommand responses are returned in JSON format."},{heading:"the-adminserver",content:"Unlike the original protocol, commands are not restricted to four-letter names, and commands can have multiple aliases;\nfor example, `stmk` can also be referred to as `set_trace_mask`. To view a list of all available commands, access the\n`/commands` endpoint (for example, `http://localhost:8080/commands`). See the AdminServer configuration options for\ninformation on changing the port and URL mappings."},{heading:"the-adminserver",content:"Beginning with ZooKeeper 3.10.0, the AdminServer is disabled by default and can be enabled by setting the\n`zookeeper.admin.enableServer` system property to `true`. When enabled without additional configuration, the AdminServer\nlistens on all network interfaces (`0.0.0.0`), uses unencrypted HTTP, and does not require client authentication.\nAdministrators are strongly encouraged to restrict network access and configure appropriate transport security and\nauthentication before exposing the AdminServer in production environments."},{heading:"the-adminserver",content:"Make sure that Jetty is available on the classpath, because the AdminServer will automatically remain disabled if Jetty\ncannot be found. As of 3.10.0 that means `jetty-server`, `jetty-ee10-servlet` and `jakarta.servlet-api`; earlier\nreleases used `jetty-servlet` and `javax.servlet-api` instead. This behavior can be useful when overriding\nZooKeeper's Jetty dependency."},{heading:"the-adminserver",content:`Note that the TCP four-letter word interface is still available for monitoring purposes if
the AdminServer is disabled.`},{heading:"security-considerations",content:`> **Important:** The AdminServer is disabled by default. When enabled without additional configuration,
> it listens on all network interfaces (0.0.0.0) on port 8080, uses unencrypted HTTP, and does not
> require client authentication. As a result, most administrative commands are accessible to any client
> that can reach the AdminServer. Administrators should restrict access appropriately and enable transport
> security and authentication when deploying the AdminServer in production.`},{heading:"default-security-posture",content:`The default AdminServer configuration is intended for ease of use in trusted environments, but it is **not secure for
exposure to untrusted networks**.`},{heading:"default-security-posture",content:"Default settings include:"},{heading:"default-security-posture",content:"`admin.enableServer=false`"},{heading:"default-security-posture",content:"`admin.serverAddress=0.0.0.0`"},{heading:"default-security-posture",content:"`admin.serverPort=8080`"},{heading:"default-security-posture",content:"`admin.forceHttps=false`"},{heading:"default-security-posture",content:"`admin.needClientAuth=false`"},{heading:"default-security-posture",content:"With these defaults:"},{heading:"default-security-posture",content:"All traffic is transmitted in clear text over HTTP."},{heading:"default-security-posture",content:"Administrative data, including configuration and runtime details, can be viewed by anyone with network access."},{heading:"default-security-posture",content:"Many commands (such as `stat`, `srvr`, `conf`, and `cons`) do not require authentication."},{heading:"default-security-posture",content:"Sensitive operational information may be disclosed."},{heading:"default-security-posture",content:"In some environments, unrestricted administrative access can increase the impact of vulnerabilities or misconfiguration."},{heading:"recommended-deployment-practices",content:"Administrators should ensure that the AdminServer is accessible only to authorized users."},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"Limit access to the AdminServer port to trusted hosts or management networks only."},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"Examples:"},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"Bind the server to localhost:"},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"Use host-based firewall rules (such as `iptables`, `firewalld`, or cloud security groups) to allow access only from"},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"administrative systems."},{heading:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"This is the minimum recommended protection when HTTPS and client authentication are not enabled."},{heading:"option-2-enable-https",content:"To encrypt all communication, configure SSL/TLS and force HTTPS:"},{heading:"option-2-enable-https",content:`This prevents credentials and administrative data from being transmitted in clear text. ZooKeeper supports configuring
the AdminServer with TLS certificates and trust stores.`},{heading:"option-3-require-client-authentication",content:"To restrict access to trusted clients using X.509 certificates:"},{heading:"option-3-require-client-authentication",content:"When enabled, only clients presenting valid certificates trusted by the server will be allowed to connect."},{heading:"recommended-secure-configuration",content:"For production environments, the following configuration is strongly recommended:"},{heading:"recommended-secure-configuration",content:"In addition, restrict access to the AdminServer port using firewall rules."},{heading:"leave-adminserver-disabled-if-not-needed",content:"If you do not use the AdminServer, disable it explicitly:"},{heading:"security-warning",content:"Exposing the AdminServer to untrusted networks with the default configuration may allow unauthorized users to:"},{heading:"security-warning",content:"Retrieve server configuration and runtime information"},{heading:"security-warning",content:"Inspect connected clients and sessions"},{heading:"security-warning",content:"Reset statistics"},{heading:"security-warning",content:"Execute other administrative commands"},{heading:"security-warning",content:"Always protect the AdminServer with **network-level controls** and, preferably, **HTTPS with client certificate authentication**."},{heading:"configuring-adminserver-for-ssltls",content:"Generating the **keystore.jks** and **truststore.jks** which can be found in the Quorum TLS."},{heading:"configuring-adminserver-for-ssltls",content:"Add the following configuration settings to the `zoo.cfg` config file:"},{heading:"configuring-adminserver-for-ssltls",content:"Verify that the following entries in the logs can be seen:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"**New in 3.10.0:** The AdminServer reuses these existing properties:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"**ssl.quorum.enabledProtocols** to specify the enabled protocols,"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"**ssl.quorum.ciphersuites** to specify the enabled cipher suites."},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Add the following configuration settings to the `zoo.cfg` config file:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"To verify, raise the log level of `JettyAdminServer` to `DEBUG` and confirm these entries appear in the logs:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Available commands include:"},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*connection\\_stat\\_reset/crst*:
Reset all client connection statistics.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*configuration/conf/config* :
Print basic details about serving configuration, e.g.
client port, absolute path to data directory.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*connections/cons* :
Information on client connections to server.
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).
Returns "connections", a list of connection info objects.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*hash*:
Txn digests in the historical digest list.
One is recorded every 128 transactions.
Returns "digests", a list to transaction digest objects.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*dirs* :
Information on logfile directory and snapshot directory
size in bytes.
Returns "datadir\\_size" and "logdir\\_size".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*dump* :
Information on session expirations and ephemerals.
Note, depending on the number of global sessions and ephemerals
this operation may be expensive (i.e. impact server performance).
Returns "expiry\\_time\\_to\\_session\\_ids" and "session\\_id\\_to\\_ephemeral\\_paths" as maps.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*environment/env/envi* :
All defined environment variables.
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*get\\_trace\\_mask/gtmk* :
The current trace mask. Read-only version of *set\\_trace\\_mask*.
See the description of the four letter command *stmk* for
more details.
Returns "tracemask".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*initial\\_configuration/icfg* :
Print the text of the configuration file used to start the peer.
Returns "initial\\_configuration".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*is\\_read\\_only/isro* :
A true/false if this server is in read-only mode.
Returns "read\\_only".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*last\\_snapshot/lsnp* :
Information of the last snapshot that zookeeper server has finished saving to disk.
If called during the initial time period between the server starting up
and the server finishing saving its first snapshot, the command returns the
information of the snapshot read when starting up the server.
Returns "zxid" and "timestamp", the latter using a time unit of seconds.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*leader/lead* :
If the ensemble is configured in quorum mode then emits the current leader
status of the peer and the current leader location.
Returns "is\\_leader", "leader\\_id", and "leader\\_ip".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*monitor/mntr* :
Emits a wide variety of useful info for monitoring.
Includes performance stats, information about internal queues, and
summaries of the data tree (among other things).
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*observer\\_connection\\_stat\\_reset/orst* :
Reset all observer connection statistics. Companion command to *observers*.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*restore/rest* :
Restore database from snapshot input stream on the current server.
Returns the following data in response payload:
"last\\_zxid": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*ruok* :
No-op command, check if the server is running.
A response does not necessarily indicate that the
server has joined the quorum, just that the admin server
is active and bound to the specified port.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*set\\_trace\\_mask/stmk* :
Sets the trace mask (as such, it requires a parameter).
Write version of *get\\_trace\\_mask*.
See the description of the four letter command *stmk* for
more details.
Returns "tracemask".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*server\\_stats/srvr* :
Server information.
Returns multiple fields giving a brief overview of server state.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*shed\\_connections/shed* :
Attempts to shed approximately the specified percentage of connections.
Requires "percentage": (int)
Returns "connections\\_shed" (int) and "percentage\\_requested" (int)`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*snapshot/snap* :
Takes a snapshot of the current server in the datadir and stream out data.
Optional query parameter:
"streaming": Boolean (defaults to true if the parameter is not present)
Returns the following via Http headers:
"last\\_zxid": String
"snapshot\\_size": String
Note: this API is rate-limited (once every 5 mins by default) to protect the server
from being over-loaded.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*stats/stat* :
Same as *server\\_stats* but also returns the "connections" field (see *connections*
for details).
Note, depending on the number of client connections this operation may be expensive
(i.e. impact server performance).`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*stat\\_reset/srst* :
Resets server statistics. This is a subset of the information returned
by *server\\_stats* and *stats*.
No new fields returned.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*observers/obsr* :
Information on observer connections to server.
Always available on a Leader, available on a Follower if its
acting as a learner master.
Returns "synced\\_observers" (int) and "observers" (list of per-observer properties).`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*system\\_properties/sysp* :
All defined system properties.
Returns each as its own field.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*voting\\_view* :
Provides the current voting members in the ensemble.
Returns "current\\_config" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*watches/wchc* :
Watch information aggregated by session.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "session\\_id\\_to\\_watched\\_paths" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*watches\\_by\\_path/wchp* :
Watch information aggregated by path.
Note, depending on the number of watches this operation may be expensive
(i.e. impact server performance).
Returns "path\\_to\\_session\\_ids" as a map.`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*watch\\_summary/wchs* :
Summarized watch information.
Returns "num\\_total\\_watches", "num\\_paths", and "num\\_connections".`},{heading:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:`*zabstate* :
The current phase of Zab protocol that peer is running and whether it is a
voting member.
Peers can be in one of these phases: ELECTION, DISCOVERY, SYNCHRONIZATION, BROADCAST.
Returns fields "voting" and "zabstate".`}],headings:[{id:"the-four-letter-words",content:"The Four Letter Words"},{id:"the-adminserver",content:"The AdminServer"},{id:"security-considerations",content:"Security Considerations"},{id:"default-security-posture",content:"Default Security Posture"},{id:"recommended-deployment-practices",content:"Recommended Deployment Practices"},{id:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",content:"Option 1: Restrict Access with Firewall Rules (Minimum Recommendation)"},{id:"option-2-enable-https",content:"Option 2: Enable HTTPS"},{id:"option-3-require-client-authentication",content:"Option 3: Require Client Authentication"},{id:"recommended-secure-configuration",content:"Recommended Secure Configuration"},{id:"leave-adminserver-disabled-if-not-needed",content:"Leave AdminServer disabled If Not Needed"},{id:"security-warning",content:"Security Warning"},{id:"configuring-adminserver-for-ssltls",content:"Configuring AdminServer for SSL/TLS"},{id:"restricting-tls-protocols-and-cipher-suites-for-adminserver",content:"Restricting TLS protocols and cipher suites for AdminServer"}]},l=[{depth:2,url:"#the-four-letter-words",title:e.jsx(e.Fragment,{children:"The Four Letter Words"})},{depth:2,url:"#the-adminserver",title:e.jsx(e.Fragment,{children:"The AdminServer"})},{depth:3,url:"#security-considerations",title:e.jsx(e.Fragment,{children:"Security Considerations"})},{depth:3,url:"#default-security-posture",title:e.jsx(e.Fragment,{children:"Default Security Posture"})},{depth:3,url:"#recommended-deployment-practices",title:e.jsx(e.Fragment,{children:"Recommended Deployment Practices"})},{depth:4,url:"#option-1-restrict-access-with-firewall-rules-minimum-recommendation",title:e.jsx(e.Fragment,{children:"Option 1: Restrict Access with Firewall Rules (Minimum Recommendation)"})},{depth:4,url:"#option-2-enable-https",title:e.jsx(e.Fragment,{children:"Option 2: Enable HTTPS"})},{depth:4,url:"#option-3-require-client-authentication",title:e.jsx(e.Fragment,{children:"Option 3: Require Client Authentication"})},{depth:3,url:"#recommended-secure-configuration",title:e.jsx(e.Fragment,{children:"Recommended Secure Configuration"})},{depth:3,url:"#leave-adminserver-disabled-if-not-needed",title:e.jsx(e.Fragment,{children:"Leave AdminServer disabled If Not Needed"})},{depth:3,url:"#security-warning",title:e.jsx(e.Fragment,{children:"Security Warning"})},{depth:2,url:"#configuring-adminserver-for-ssltls",title:e.jsx(e.Fragment,{children:"Configuring AdminServer for SSL/TLS"})},{depth:3,url:"#restricting-tls-protocols-and-cipher-suites-for-adminserver",title:e.jsx(e.Fragment,{children:"Restricting TLS protocols and cipher suites for AdminServer"})}];function s(t){const n={a:"a",blockquote:"blockquote",code:"code",em:"em",h2:"h2",h3:"h3",h4:"h4",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"the-four-letter-words",children:"The Four Letter Words"}),`
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
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.5.0:"}),` The AdminServer is an embedded Jetty server that provides an HTTP interface to the four-letter word
commands. In ZooKeeper releases 3.5.0 through 3.9.x, the AdminServer was enabled by default. Starting with ZooKeeper
3.10.0, the default configuration disables the AdminServer. When enabled, the server listens on port 8080 by default,
and commands are issued by accessing the URL `,e.jsx(n.code,{children:"/commands/[command name]"}),", for example, ",e.jsx(n.code,{children:"http://localhost:8080/commands/stat"}),`.
Command responses are returned in JSON format.`]}),`
`,e.jsxs(n.p,{children:[`Unlike the original protocol, commands are not restricted to four-letter names, and commands can have multiple aliases;
for example, `,e.jsx(n.code,{children:"stmk"})," can also be referred to as ",e.jsx(n.code,{children:"set_trace_mask"}),`. To view a list of all available commands, access the
`,e.jsx(n.code,{children:"/commands"})," endpoint (for example, ",e.jsx(n.code,{children:"http://localhost:8080/commands"}),`). See the AdminServer configuration options for
information on changing the port and URL mappings.`]}),`
`,e.jsxs(n.p,{children:[`Beginning with ZooKeeper 3.10.0, the AdminServer is disabled by default and can be enabled by setting the
`,e.jsx(n.code,{children:"zookeeper.admin.enableServer"})," system property to ",e.jsx(n.code,{children:"true"}),`. When enabled without additional configuration, the AdminServer
listens on all network interfaces (`,e.jsx(n.code,{children:"0.0.0.0"}),`), uses unencrypted HTTP, and does not require client authentication.
Administrators are strongly encouraged to restrict network access and configure appropriate transport security and
authentication before exposing the AdminServer in production environments.`]}),`
`,e.jsxs(n.p,{children:[`Make sure that Jetty is available on the classpath, because the AdminServer will automatically remain disabled if Jetty
cannot be found. As of 3.10.0 that means `,e.jsx(n.code,{children:"jetty-server"}),", ",e.jsx(n.code,{children:"jetty-ee10-servlet"})," and ",e.jsx(n.code,{children:"jakarta.servlet-api"}),`; earlier
releases used `,e.jsx(n.code,{children:"jetty-servlet"})," and ",e.jsx(n.code,{children:"javax.servlet-api"}),` instead. This behavior can be useful when overriding
ZooKeeper's Jetty dependency.`]}),`
`,e.jsx(n.p,{children:`Note that the TCP four-letter word interface is still available for monitoring purposes if
the AdminServer is disabled.`}),`
`,e.jsx(n.h3,{id:"security-considerations",children:"Security Considerations"}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Important:"}),` The AdminServer is disabled by default. When enabled without additional configuration,
it listens on all network interfaces (0.0.0.0) on port 8080, uses unencrypted HTTP, and does not
require client authentication. As a result, most administrative commands are accessible to any client
that can reach the AdminServer. Administrators should restrict access appropriately and enable transport
security and authentication when deploying the AdminServer in production.`]}),`
`]}),`
`,e.jsx(n.h3,{id:"default-security-posture",children:"Default Security Posture"}),`
`,e.jsxs(n.p,{children:["The default AdminServer configuration is intended for ease of use in trusted environments, but it is ",e.jsx(n.strong,{children:`not secure for
exposure to untrusted networks`}),"."]}),`
`,e.jsx(n.p,{children:"Default settings include:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:e.jsx(n.code,{children:"admin.enableServer=false"})}),`
`,e.jsx(n.li,{children:e.jsx(n.code,{children:"admin.serverAddress=0.0.0.0"})}),`
`,e.jsx(n.li,{children:e.jsx(n.code,{children:"admin.serverPort=8080"})}),`
`,e.jsx(n.li,{children:e.jsx(n.code,{children:"admin.forceHttps=false"})}),`
`,e.jsx(n.li,{children:e.jsx(n.code,{children:"admin.needClientAuth=false"})}),`
`]}),`
`,e.jsx(n.p,{children:"With these defaults:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:"All traffic is transmitted in clear text over HTTP."}),`
`,e.jsx(n.li,{children:"Administrative data, including configuration and runtime details, can be viewed by anyone with network access."}),`
`,e.jsxs(n.li,{children:["Many commands (such as ",e.jsx(n.code,{children:"stat"}),", ",e.jsx(n.code,{children:"srvr"}),", ",e.jsx(n.code,{children:"conf"}),", and ",e.jsx(n.code,{children:"cons"}),") do not require authentication."]}),`
`,e.jsx(n.li,{children:"Sensitive operational information may be disclosed."}),`
`,e.jsx(n.li,{children:"In some environments, unrestricted administrative access can increase the impact of vulnerabilities or misconfiguration."}),`
`]}),`
`,e.jsx(n.h3,{id:"recommended-deployment-practices",children:"Recommended Deployment Practices"}),`
`,e.jsx(n.p,{children:"Administrators should ensure that the AdminServer is accessible only to authorized users."}),`
`,e.jsx(n.h4,{id:"option-1-restrict-access-with-firewall-rules-minimum-recommendation",children:"Option 1: Restrict Access with Firewall Rules (Minimum Recommendation)"}),`
`,e.jsx(n.p,{children:"Limit access to the AdminServer port to trusted hosts or management networks only."}),`
`,e.jsx(n.p,{children:"Examples:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"Bind the server to localhost:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.serverAddress"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=127.0.0.1"})]})})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Use host-based firewall rules (such as ",e.jsx(n.code,{children:"iptables"}),", ",e.jsx(n.code,{children:"firewalld"}),", or cloud security groups) to allow access only from"]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"administrative systems."}),`
`]}),`
`]}),`
`,e.jsx(n.p,{children:"This is the minimum recommended protection when HTTPS and client authentication are not enabled."}),`
`,e.jsx(n.h4,{id:"option-2-enable-https",children:"Option 2: Enable HTTPS"}),`
`,e.jsx(n.p,{children:"To encrypt all communication, configure SSL/TLS and force HTTPS:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.forceHttps"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=true"})]})})})}),`
`,e.jsx(n.p,{children:`This prevents credentials and administrative data from being transmitted in clear text. ZooKeeper supports configuring
the AdminServer with TLS certificates and trust stores.`}),`
`,e.jsx(n.h4,{id:"option-3-require-client-authentication",children:"Option 3: Require Client Authentication"}),`
`,e.jsx(n.p,{children:"To restrict access to trusted clients using X.509 certificates:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.needClientAuth"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=true"})]})})})}),`
`,e.jsx(n.p,{children:"When enabled, only clients presenting valid certificates trusted by the server will be allowed to connect."}),`
`,e.jsx(n.h3,{id:"recommended-secure-configuration",children:"Recommended Secure Configuration"}),`
`,e.jsx(n.p,{children:"For production environments, the following configuration is strongly recommended:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.forceHttps"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=true"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.needClientAuth"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=true"})]}),`
`,e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.serverAddress"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=<management-network-ip>"})]})]})})}),`
`,e.jsx(n.p,{children:"In addition, restrict access to the AdminServer port using firewall rules."}),`
`,e.jsx(n.h3,{id:"leave-adminserver-disabled-if-not-needed",children:"Leave AdminServer disabled If Not Needed"}),`
`,e.jsx(n.p,{children:"If you do not use the AdminServer, disable it explicitly:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"admin.enableServer"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=false"})]})})})}),`
`,e.jsx(n.h3,{id:"security-warning",children:"Security Warning"}),`
`,e.jsx(n.p,{children:"Exposing the AdminServer to untrusted networks with the default configuration may allow unauthorized users to:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:"Retrieve server configuration and runtime information"}),`
`,e.jsx(n.li,{children:"Inspect connected clients and sessions"}),`
`,e.jsx(n.li,{children:"Reset statistics"}),`
`,e.jsx(n.li,{children:"Execute other administrative commands"}),`
`]}),`
`,e.jsxs(n.p,{children:["Always protect the AdminServer with ",e.jsx(n.strong,{children:"network-level controls"})," and, preferably, ",e.jsx(n.strong,{children:"HTTPS with client certificate authentication"}),"."]}),`
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
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer] - Successfully loaded private key from /data/software/cert/keystore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,213 [myid:] - INFO  [main:JettyAdminServer] - Successfully loaded certificate authority from /data/software/cert/truststore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"2019-08-03 15:44:55,403 [myid:] - INFO  [main:JettyAdminServer] - Started AdminServer on address 0.0.0.0, port 8080 and command URL /commands"})})]})})}),`
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
`]})]})}function c(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(s,{...t})}):s(t)}export{r as _markdown,c as default,a as extractedReferences,o as frontmatter,d as structuredData,l as toc};
