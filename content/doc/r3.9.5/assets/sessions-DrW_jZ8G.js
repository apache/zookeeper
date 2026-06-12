import{j as e}from"./chunk-EPOLDU6W-CDTnuKF9.js";const i="/doc/r3.9.5/assets/state_dia-Db2ssn4g.jpg";let a=`

A ZooKeeper client establishes a session with the ZooKeeper
service by creating a handle to the service using a language
binding. Once created, the handle starts off in the CONNECTING state
and the client library tries to connect to one of the servers that
make up the ZooKeeper service at which point it switches to the
CONNECTED state. During normal operation the client handle will be in one of these
two states. If an unrecoverable error occurs, such as session
expiration or authentication failure, or if the application explicitly
closes the handle, the handle will move to the CLOSED state.
The following figure shows the possible state transitions of a
ZooKeeper client:

<img alt="State transitions" src={__img0} placeholder="blur" />

To create a client session the application code must provide
a connection string containing a comma separated list of host:port pairs,
each corresponding to a ZooKeeper server (e.g. "127.0.0.1:4545" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"). The ZooKeeper
client library will pick an arbitrary server and try to connect to
it. If this connection fails, or if the client becomes
disconnected from the server for any reason, the client will
automatically try the next server in the list, until a connection
is (re-)established.

**Added in 3.2.0**: An
optional "chroot" suffix may also be appended to the connection
string. This will run the client commands while interpreting all
paths relative to this root (similar to the unix chroot
command). If used the example would look like:
"127.0.0.1:4545/app/a" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a" where the
client would be rooted at "/app/a" and all paths would be relative
to this root - ie getting/setting/etc... "/foo/bar" would result
in operations being run on "/app/a/foo/bar" (from the server
perspective). This feature is particularly useful in multi-tenant
environments where each user of a particular ZooKeeper service
could be rooted differently. This makes re-use much simpler as
each user can code his/her application as if it were rooted at
"/", while actual location (say /app/a) could be determined at
deployment time.

When a client gets a handle to the ZooKeeper service,
ZooKeeper creates a ZooKeeper session, represented as a 64-bit
number, that it assigns to the client. If the client connects to a
different ZooKeeper server, it will send the session id as a part
of the connection handshake. As a security measure, the server
creates a password for the session id that any ZooKeeper server
can validate.The password is sent to the client with the session
id when the client establishes the session. The client sends this
password with the session id whenever it reestablishes the session
with a new server.

One of the parameters to the ZooKeeper client library call
to create a ZooKeeper session is the session timeout in
milliseconds. The client sends a requested timeout, the server
responds with the timeout that it can give the client. The current
implementation requires that the timeout be a minimum of 2 times
the tickTime (as set in the server configuration) and a maximum of
20 times the tickTime. The ZooKeeper client API allows access to
the negotiated timeout.

When a client (session) becomes partitioned from the ZK
serving cluster it will begin searching the list of servers that
were specified during session creation. Eventually, when
connectivity between the client and at least one of the servers is
re-established, the session will either again transition to the
"connected" state (if reconnected within the session timeout
value) or it will transition to the "expired" state (if
reconnected after the session timeout). It is not advisable to
create a new session object (a new ZooKeeper.class or zookeeper
handle in the c binding) for disconnection. The ZK client library
will handle reconnect for you. In particular we have heuristics
built into the client library to handle things like "herd effect",
etc... Only create a new session when you are notified of session
expiration (mandatory).

Session expiration is managed by the ZooKeeper cluster
itself, not by the client. When the ZK client establishes a
session with the cluster it provides a "timeout" value detailed
above. This value is used by the cluster to determine when the
client's session expires. Expirations happens when the cluster
does not hear from the client within the specified session timeout
period (i.e. no heartbeat). At session expiration the cluster will
delete any/all ephemeral nodes owned by that session and
immediately notify any/all connected clients of the change (anyone
watching those znodes). At this point the client of the expired
session is still disconnected from the cluster, it will not be
notified of the session expiration until/unless it is able to
re-establish a connection to the cluster. The client will stay in
disconnected state until the TCP connection is re-established with
the cluster, at which point the watcher of the expired session
will receive the "session expired" notification.

Example state transitions for an expired session as seen by
the expired session's watcher:

1. 'connected' : session is established and client
   is communicating with cluster (client/server communication is
   operating properly)
2. .... client is partitioned from the
   cluster
3. 'disconnected' : client has lost connectivity
   with the cluster
4. .... time elapses, after 'timeout' period the
   cluster expires the session, nothing is seen by client as it is
   disconnected from cluster
5. .... time elapses, the client regains network
   level connectivity with the cluster
6. 'expired' : eventually the client reconnects to
   the cluster, it is then notified of the
   expiration

Another parameter to the ZooKeeper session establishment
call is the default watcher. Watchers are notified when any state
change occurs in the client. For example if the client loses
connectivity to the server the client will be notified, or if the
client's session expires, etc... This watcher should consider the
initial state to be disconnected (i.e. before any state changes
events are sent to the watcher by the client lib). In the case of
a new connection, the first event sent to the watcher is typically
the session connection event.

The session is kept alive by requests sent by the client. If
the session is idle for a period of time that would timeout the
session, the client will send a PING request to keep the session
alive. This PING request not only allows the ZooKeeper server to
know that the client is still active, but it also allows the
client to verify that its connection to the ZooKeeper server is
still active. The timing of the PING is conservative enough to
ensure reasonable time to detect a dead connection and reconnect
to a new server.

Once a connection to the server is successfully established
(connected) there are basically two cases where the client lib generates
connectionloss (the result code in c binding, exception in Java — see
the API documentation for binding specific details) when either a synchronous or
asynchronous operation is performed and one of the following holds:

1. The application calls an operation on a session that is no
   longer alive/valid
2. The ZooKeeper client disconnects from a server when there
   are pending operations to that server, i.e., there is a pending asynchronous call.

**Added in 3.2.0 — SessionMovedException**. There is an internal
exception that is generally not seen by clients called the SessionMovedException.
This exception occurs because a request was received on a connection for a session
which has been reestablished on a different server. The normal cause of this error is
a client that sends a request to a server, but the network packet gets delayed, so
the client times out and connects to a new server. When the delayed packet arrives at
the first server, the old server detects that the session has moved, and closes the
client connection. Clients normally do not see this error since they do not read
from those old connections. (Old connections are usually closed.) One situation in which this
condition can be seen is when two clients try to reestablish the same connection using
a saved session id and password. One of the clients will reestablish the connection
and the second client will be disconnected (causing the pair to attempt to re-establish
its connection/session indefinitely).

**Updating the list of servers**. We allow a client to
update the connection string by providing a new comma separated list of host:port pairs,
each corresponding to a ZooKeeper server. The function invokes a probabilistic load-balancing
algorithm which may cause the client to disconnect from its current host with the goal
to achieve expected uniform number of connections per server in the new list.
In case the current host to which the client is connected is not in the new list
this call will always cause the connection to be dropped. Otherwise, the decision
is based on whether the number of servers has increased or decreased and by how much.

For example, if the previous connection string contained 3 hosts and now the list contains
these 3 hosts and 2 more hosts, 40% of clients connected to each of the 3 hosts will
move to one of the new hosts in order to balance the load. The algorithm will cause the client
to drop its connection to the current host to which it is connected with probability 0.4 and in this
case cause the client to connect to one of the 2 new hosts, chosen at random.

Another example — suppose we have 5 hosts and now update the list to remove 2 of the hosts,
the clients connected to the 3 remaining hosts will stay connected, whereas all clients connected
to the 2 removed hosts will need to move to one of the 3 hosts, chosen at random. If the connection
is dropped, the client moves to a special mode where he chooses a new server to connect to using the
probabilistic algorithm, and not just round robin.

In the first example, each client decides to disconnect with probability 0.4 but once the decision is
made, it will try to connect to a random new server and only if it cannot connect to any of the new
servers will it try to connect to the old ones. After finding a server, or trying all servers in the
new list and failing to connect, the client moves back to the normal mode of operation where it picks
an arbitrary server from the connectString and attempts to connect to it. If that fails, it will continue
trying different random servers in round robin. (see above the algorithm used to initially choose a server)

**Local session**. Added in 3.5.0, mainly implemented by [ZOOKEEPER-1147](https://issues.apache.org/jira/browse/ZOOKEEPER-1147).

* Background: The creation and closing of sessions are costly in ZooKeeper because they need quorum confirmations,
  they become the bottleneck of a ZooKeeper ensemble when it needs to handle thousands of client connections.
  So after 3.5.0, we introduce a new type of session: local session which doesn't have a full functionality of a normal(global) session, this feature
  will be available by turning on *localSessionsEnabled*.

when *localSessionsUpgradingEnabled* is disable:

* Local sessions cannot create ephemeral nodes

* Once a local session is lost, users cannot re-establish it using the session-id/password, the session and its watches are gone for good.
  Note: Losing the tcp connection does not necessarily imply that the session is lost. If the connection can be reestablished with the same zk server
  before the session timeout then the client can continue (it simply cannot move to another server).

* When a local session connects, the session info is only maintained on the zookeeper server that it is connected to. The leader is not aware of the creation of such a session and
  there is no state written to disk.

* The pings, expiration and other session state maintenance are handled by the server which current session is connected to.

when *localSessionsUpgradingEnabled* is enable:

* A local session can be upgraded to the global session automatically.

* When a new session is created it is saved locally in a wrapped *LocalSessionTracker*. It can subsequently be upgraded
  to a global session as required (e.g. create ephemeral nodes). If an upgrade is requested the session is removed from local
  collections while keeping the same session ID.

* Currently, Only the operation: *create ephemeral node* needs a session upgrade from local to global.
  The reason is that the creation of ephemeral node depends heavily on a global session. If local session can create ephemeral
  node without upgrading to global session, it will cause the data inconsistency between different nodes.
  The leader also needs to know about the lifespan of a session in order to clean up ephemeral nodes on close/expiry.
  This requires a global session as the local session is tied to its particular server.

* A session can be both a local and global session during upgrade, but the operation of upgrade cannot be called concurrently by two thread.

* *ZooKeeperServer*(Standalone) uses *SessionTrackerImpl*; *LeaderZookeeper* uses *LeaderSessionTracker* which holds
  *SessionTrackerImpl*(global) and *LocalSessionTracker*(if enable); *FollowerZooKeeperServer* and *ObserverZooKeeperServer*
  use *LearnerSessionTracker* which holds *LocalSessionTracker*.
  The UML Graph of Classes about session:

  \`\`\`
  +----------------+     +--------------------+       +---------------------+
  |                | --> |                    | ----> | LocalSessionTracker |
  | SessionTracker |     | SessionTrackerImpl |       +---------------------+
  |                |     |                    |                              +-----------------------+
  |                |     |                    |  +-------------------------> | LeaderSessionTracker  |
  +----------------+     +--------------------+  |                           +-----------------------+
            |                                   |
            |                                   |
            |                                   |
            |           +---------------------------+
            +---------> |                           |
                        | UpgradeableSessionTracker |
                        |                           |
                        |                           | ------------------------+
                        +---------------------------+                         |
                                                                              |
                                                                              |
                                                                              v
                                                                            +-----------------------+
                                                                            | LearnerSessionTracker |
                                                                            +-----------------------+
  \`\`\`

## Q\\&A \\[!toc]

* *What's the reason for having the config option to disable local session upgrade?*
  * In a large deployment which wants to handle a very large number of clients, we know that clients connecting via the observers
    which is supposed to be local session only. So this is more like a safeguard against someone accidentally creates lots of ephemeral nodes and global sessions.

* *When is the session created?*
  * In the current implementation, it will try to create a local session when processing *ConnectRequest* and when
    *createSession* request reaches *FinalRequestProcessor*.

* *What happens if the create for session is sent at server A and the client disconnects to some other server B
  which ends up sending it again and then disconnects and connects back to server A?*
  * When a client reconnects to B, its sessionId won’t exist in B’s local session tracker. So B will send validation packet.
    If CreateSession issued by A is committed before validation packet arrive the client will be able to connect.
    Otherwise, the client will get session expired because the quorum hasn’t know about this session yet.
    If the client also tries to connect back to A again, the session is already removed from local session tracker.
    So A will need to send a validation packet to the leader. The outcome should be the same as B depending on the timing of the request.
`,r={title:"Sessions",description:"Covers ZooKeeper client sessions: connection states, session timeouts, expiration, password-based recovery, and how clients connect to a ZooKeeper ensemble."},c=[{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1147"}],l={contents:[{heading:void 0,content:`A ZooKeeper client establishes a session with the ZooKeeper
service by creating a handle to the service using a language
binding. Once created, the handle starts off in the CONNECTING state
and the client library tries to connect to one of the servers that
make up the ZooKeeper service at which point it switches to the
CONNECTED state. During normal operation the client handle will be in one of these
two states. If an unrecoverable error occurs, such as session
expiration or authentication failure, or if the application explicitly
closes the handle, the handle will move to the CLOSED state.
The following figure shows the possible state transitions of a
ZooKeeper client:`},{heading:void 0,content:`To create a client session the application code must provide
a connection string containing a comma separated list of host:port pairs,
each corresponding to a ZooKeeper server (e.g. "127.0.0.1:4545" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"). The ZooKeeper
client library will pick an arbitrary server and try to connect to
it. If this connection fails, or if the client becomes
disconnected from the server for any reason, the client will
automatically try the next server in the list, until a connection
is (re-)established.`},{heading:void 0,content:`Added in 3.2.0: An
optional "chroot" suffix may also be appended to the connection
string. This will run the client commands while interpreting all
paths relative to this root (similar to the unix chroot
command). If used the example would look like:
"127.0.0.1:4545/app/a" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a" where the
client would be rooted at "/app/a" and all paths would be relative
to this root - ie getting/setting/etc... "/foo/bar" would result
in operations being run on "/app/a/foo/bar" (from the server
perspective). This feature is particularly useful in multi-tenant
environments where each user of a particular ZooKeeper service
could be rooted differently. This makes re-use much simpler as
each user can code his/her application as if it were rooted at
"/", while actual location (say /app/a) could be determined at
deployment time.`},{heading:void 0,content:`When a client gets a handle to the ZooKeeper service,
ZooKeeper creates a ZooKeeper session, represented as a 64-bit
number, that it assigns to the client. If the client connects to a
different ZooKeeper server, it will send the session id as a part
of the connection handshake. As a security measure, the server
creates a password for the session id that any ZooKeeper server
can validate.The password is sent to the client with the session
id when the client establishes the session. The client sends this
password with the session id whenever it reestablishes the session
with a new server.`},{heading:void 0,content:`One of the parameters to the ZooKeeper client library call
to create a ZooKeeper session is the session timeout in
milliseconds. The client sends a requested timeout, the server
responds with the timeout that it can give the client. The current
implementation requires that the timeout be a minimum of 2 times
the tickTime (as set in the server configuration) and a maximum of
20 times the tickTime. The ZooKeeper client API allows access to
the negotiated timeout.`},{heading:void 0,content:`When a client (session) becomes partitioned from the ZK
serving cluster it will begin searching the list of servers that
were specified during session creation. Eventually, when
connectivity between the client and at least one of the servers is
re-established, the session will either again transition to the
"connected" state (if reconnected within the session timeout
value) or it will transition to the "expired" state (if
reconnected after the session timeout). It is not advisable to
create a new session object (a new ZooKeeper.class or zookeeper
handle in the c binding) for disconnection. The ZK client library
will handle reconnect for you. In particular we have heuristics
built into the client library to handle things like "herd effect",
etc... Only create a new session when you are notified of session
expiration (mandatory).`},{heading:void 0,content:`Session expiration is managed by the ZooKeeper cluster
itself, not by the client. When the ZK client establishes a
session with the cluster it provides a "timeout" value detailed
above. This value is used by the cluster to determine when the
client's session expires. Expirations happens when the cluster
does not hear from the client within the specified session timeout
period (i.e. no heartbeat). At session expiration the cluster will
delete any/all ephemeral nodes owned by that session and
immediately notify any/all connected clients of the change (anyone
watching those znodes). At this point the client of the expired
session is still disconnected from the cluster, it will not be
notified of the session expiration until/unless it is able to
re-establish a connection to the cluster. The client will stay in
disconnected state until the TCP connection is re-established with
the cluster, at which point the watcher of the expired session
will receive the "session expired" notification.`},{heading:void 0,content:`Example state transitions for an expired session as seen by
the expired session's watcher:`},{heading:void 0,content:`'connected' : session is established and client
is communicating with cluster (client/server communication is
operating properly)`},{heading:void 0,content:`.... client is partitioned from the
cluster`},{heading:void 0,content:`'disconnected' : client has lost connectivity
with the cluster`},{heading:void 0,content:`.... time elapses, after 'timeout' period the
cluster expires the session, nothing is seen by client as it is
disconnected from cluster`},{heading:void 0,content:`.... time elapses, the client regains network
level connectivity with the cluster`},{heading:void 0,content:`'expired' : eventually the client reconnects to
the cluster, it is then notified of the
expiration`},{heading:void 0,content:`Another parameter to the ZooKeeper session establishment
call is the default watcher. Watchers are notified when any state
change occurs in the client. For example if the client loses
connectivity to the server the client will be notified, or if the
client's session expires, etc... This watcher should consider the
initial state to be disconnected (i.e. before any state changes
events are sent to the watcher by the client lib). In the case of
a new connection, the first event sent to the watcher is typically
the session connection event.`},{heading:void 0,content:`The session is kept alive by requests sent by the client. If
the session is idle for a period of time that would timeout the
session, the client will send a PING request to keep the session
alive. This PING request not only allows the ZooKeeper server to
know that the client is still active, but it also allows the
client to verify that its connection to the ZooKeeper server is
still active. The timing of the PING is conservative enough to
ensure reasonable time to detect a dead connection and reconnect
to a new server.`},{heading:void 0,content:`Once a connection to the server is successfully established
(connected) there are basically two cases where the client lib generates
connectionloss (the result code in c binding, exception in Java — see
the API documentation for binding specific details) when either a synchronous or
asynchronous operation is performed and one of the following holds:`},{heading:void 0,content:`The application calls an operation on a session that is no
longer alive/valid`},{heading:void 0,content:`The ZooKeeper client disconnects from a server when there
are pending operations to that server, i.e., there is a pending asynchronous call.`},{heading:void 0,content:`Added in 3.2.0 — SessionMovedException. There is an internal
exception that is generally not seen by clients called the SessionMovedException.
This exception occurs because a request was received on a connection for a session
which has been reestablished on a different server. The normal cause of this error is
a client that sends a request to a server, but the network packet gets delayed, so
the client times out and connects to a new server. When the delayed packet arrives at
the first server, the old server detects that the session has moved, and closes the
client connection. Clients normally do not see this error since they do not read
from those old connections. (Old connections are usually closed.) One situation in which this
condition can be seen is when two clients try to reestablish the same connection using
a saved session id and password. One of the clients will reestablish the connection
and the second client will be disconnected (causing the pair to attempt to re-establish
its connection/session indefinitely).`},{heading:void 0,content:`Updating the list of servers. We allow a client to
update the connection string by providing a new comma separated list of host:port pairs,
each corresponding to a ZooKeeper server. The function invokes a probabilistic load-balancing
algorithm which may cause the client to disconnect from its current host with the goal
to achieve expected uniform number of connections per server in the new list.
In case the current host to which the client is connected is not in the new list
this call will always cause the connection to be dropped. Otherwise, the decision
is based on whether the number of servers has increased or decreased and by how much.`},{heading:void 0,content:`For example, if the previous connection string contained 3 hosts and now the list contains
these 3 hosts and 2 more hosts, 40% of clients connected to each of the 3 hosts will
move to one of the new hosts in order to balance the load. The algorithm will cause the client
to drop its connection to the current host to which it is connected with probability 0.4 and in this
case cause the client to connect to one of the 2 new hosts, chosen at random.`},{heading:void 0,content:`Another example — suppose we have 5 hosts and now update the list to remove 2 of the hosts,
the clients connected to the 3 remaining hosts will stay connected, whereas all clients connected
to the 2 removed hosts will need to move to one of the 3 hosts, chosen at random. If the connection
is dropped, the client moves to a special mode where he chooses a new server to connect to using the
probabilistic algorithm, and not just round robin.`},{heading:void 0,content:`In the first example, each client decides to disconnect with probability 0.4 but once the decision is
made, it will try to connect to a random new server and only if it cannot connect to any of the new
servers will it try to connect to the old ones. After finding a server, or trying all servers in the
new list and failing to connect, the client moves back to the normal mode of operation where it picks
an arbitrary server from the connectString and attempts to connect to it. If that fails, it will continue
trying different random servers in round robin. (see above the algorithm used to initially choose a server)`},{heading:void 0,content:"Local session. Added in 3.5.0, mainly implemented by ZOOKEEPER-1147."},{heading:void 0,content:`Background: The creation and closing of sessions are costly in ZooKeeper because they need quorum confirmations,
they become the bottleneck of a ZooKeeper ensemble when it needs to handle thousands of client connections.
So after 3.5.0, we introduce a new type of session: local session which doesn't have a full functionality of a normal(global) session, this feature
will be available by turning on localSessionsEnabled.`},{heading:void 0,content:"when localSessionsUpgradingEnabled is disable:"},{heading:void 0,content:"Local sessions cannot create ephemeral nodes"},{heading:void 0,content:`Once a local session is lost, users cannot re-establish it using the session-id/password, the session and its watches are gone for good.
Note: Losing the tcp connection does not necessarily imply that the session is lost. If the connection can be reestablished with the same zk server
before the session timeout then the client can continue (it simply cannot move to another server).`},{heading:void 0,content:`When a local session connects, the session info is only maintained on the zookeeper server that it is connected to. The leader is not aware of the creation of such a session and
there is no state written to disk.`},{heading:void 0,content:"The pings, expiration and other session state maintenance are handled by the server which current session is connected to."},{heading:void 0,content:"when localSessionsUpgradingEnabled is enable:"},{heading:void 0,content:"A local session can be upgraded to the global session automatically."},{heading:void 0,content:`When a new session is created it is saved locally in a wrapped LocalSessionTracker. It can subsequently be upgraded
to a global session as required (e.g. create ephemeral nodes). If an upgrade is requested the session is removed from local
collections while keeping the same session ID.`},{heading:void 0,content:`Currently, Only the operation: create ephemeral node needs a session upgrade from local to global.
The reason is that the creation of ephemeral node depends heavily on a global session. If local session can create ephemeral
node without upgrading to global session, it will cause the data inconsistency between different nodes.
The leader also needs to know about the lifespan of a session in order to clean up ephemeral nodes on close/expiry.
This requires a global session as the local session is tied to its particular server.`},{heading:void 0,content:"A session can be both a local and global session during upgrade, but the operation of upgrade cannot be called concurrently by two thread."},{heading:void 0,content:`ZooKeeperServer(Standalone) uses SessionTrackerImpl; LeaderZookeeper uses LeaderSessionTracker which holds
SessionTrackerImpl(global) and LocalSessionTracker(if enable); FollowerZooKeeperServer and ObserverZooKeeperServer
use LearnerSessionTracker which holds LocalSessionTracker.
The UML Graph of Classes about session:`},{heading:"qa-toc",content:"What's the reason for having the config option to disable local session upgrade?"},{heading:"qa-toc",content:`In a large deployment which wants to handle a very large number of clients, we know that clients connecting via the observers
which is supposed to be local session only. So this is more like a safeguard against someone accidentally creates lots of ephemeral nodes and global sessions.`},{heading:"qa-toc",content:"When is the session created?"},{heading:"qa-toc",content:`In the current implementation, it will try to create a local session when processing ConnectRequest and when
createSession request reaches FinalRequestProcessor.`},{heading:"qa-toc",content:`What happens if the create for session is sent at server A and the client disconnects to some other server B
which ends up sending it again and then disconnects and connects back to server A?`},{heading:"qa-toc",content:`When a client reconnects to B, its sessionId won’t exist in B’s local session tracker. So B will send validation packet.
If CreateSession issued by A is committed before validation packet arrive the client will be able to connect.
Otherwise, the client will get session expired because the quorum hasn’t know about this session yet.
If the client also tries to connect back to A again, the session is already removed from local session tracker.
So A will need to send a validation packet to the leader. The outcome should be the same as B depending on the timing of the request.`}],headings:[{id:"qa-toc",content:"Q&A [!toc]"}]};const h=[];function s(t){const n={a:"a",code:"code",em:"em",h2:"h2",img:"img",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`A ZooKeeper client establishes a session with the ZooKeeper
service by creating a handle to the service using a language
binding. Once created, the handle starts off in the CONNECTING state
and the client library tries to connect to one of the servers that
make up the ZooKeeper service at which point it switches to the
CONNECTED state. During normal operation the client handle will be in one of these
two states. If an unrecoverable error occurs, such as session
expiration or authentication failure, or if the application explicitly
closes the handle, the handle will move to the CLOSED state.
The following figure shows the possible state transitions of a
ZooKeeper client:`}),`
`,e.jsx(n.p,{children:e.jsx(n.img,{alt:"State transitions",src:i,placeholder:"blur"})}),`
`,e.jsx(n.p,{children:`To create a client session the application code must provide
a connection string containing a comma separated list of host:port pairs,
each corresponding to a ZooKeeper server (e.g. "127.0.0.1:4545" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"). The ZooKeeper
client library will pick an arbitrary server and try to connect to
it. If this connection fails, or if the client becomes
disconnected from the server for any reason, the client will
automatically try the next server in the list, until a connection
is (re-)established.`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Added in 3.2.0"}),`: An
optional "chroot" suffix may also be appended to the connection
string. This will run the client commands while interpreting all
paths relative to this root (similar to the unix chroot
command). If used the example would look like:
"127.0.0.1:4545/app/a" or
"127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a" where the
client would be rooted at "/app/a" and all paths would be relative
to this root - ie getting/setting/etc... "/foo/bar" would result
in operations being run on "/app/a/foo/bar" (from the server
perspective). This feature is particularly useful in multi-tenant
environments where each user of a particular ZooKeeper service
could be rooted differently. This makes re-use much simpler as
each user can code his/her application as if it were rooted at
"/", while actual location (say /app/a) could be determined at
deployment time.`]}),`
`,e.jsx(n.p,{children:`When a client gets a handle to the ZooKeeper service,
ZooKeeper creates a ZooKeeper session, represented as a 64-bit
number, that it assigns to the client. If the client connects to a
different ZooKeeper server, it will send the session id as a part
of the connection handshake. As a security measure, the server
creates a password for the session id that any ZooKeeper server
can validate.The password is sent to the client with the session
id when the client establishes the session. The client sends this
password with the session id whenever it reestablishes the session
with a new server.`}),`
`,e.jsx(n.p,{children:`One of the parameters to the ZooKeeper client library call
to create a ZooKeeper session is the session timeout in
milliseconds. The client sends a requested timeout, the server
responds with the timeout that it can give the client. The current
implementation requires that the timeout be a minimum of 2 times
the tickTime (as set in the server configuration) and a maximum of
20 times the tickTime. The ZooKeeper client API allows access to
the negotiated timeout.`}),`
`,e.jsx(n.p,{children:`When a client (session) becomes partitioned from the ZK
serving cluster it will begin searching the list of servers that
were specified during session creation. Eventually, when
connectivity between the client and at least one of the servers is
re-established, the session will either again transition to the
"connected" state (if reconnected within the session timeout
value) or it will transition to the "expired" state (if
reconnected after the session timeout). It is not advisable to
create a new session object (a new ZooKeeper.class or zookeeper
handle in the c binding) for disconnection. The ZK client library
will handle reconnect for you. In particular we have heuristics
built into the client library to handle things like "herd effect",
etc... Only create a new session when you are notified of session
expiration (mandatory).`}),`
`,e.jsx(n.p,{children:`Session expiration is managed by the ZooKeeper cluster
itself, not by the client. When the ZK client establishes a
session with the cluster it provides a "timeout" value detailed
above. This value is used by the cluster to determine when the
client's session expires. Expirations happens when the cluster
does not hear from the client within the specified session timeout
period (i.e. no heartbeat). At session expiration the cluster will
delete any/all ephemeral nodes owned by that session and
immediately notify any/all connected clients of the change (anyone
watching those znodes). At this point the client of the expired
session is still disconnected from the cluster, it will not be
notified of the session expiration until/unless it is able to
re-establish a connection to the cluster. The client will stay in
disconnected state until the TCP connection is re-established with
the cluster, at which point the watcher of the expired session
will receive the "session expired" notification.`}),`
`,e.jsx(n.p,{children:`Example state transitions for an expired session as seen by
the expired session's watcher:`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:`'connected' : session is established and client
is communicating with cluster (client/server communication is
operating properly)`}),`
`,e.jsx(n.li,{children:`.... client is partitioned from the
cluster`}),`
`,e.jsx(n.li,{children:`'disconnected' : client has lost connectivity
with the cluster`}),`
`,e.jsx(n.li,{children:`.... time elapses, after 'timeout' period the
cluster expires the session, nothing is seen by client as it is
disconnected from cluster`}),`
`,e.jsx(n.li,{children:`.... time elapses, the client regains network
level connectivity with the cluster`}),`
`,e.jsx(n.li,{children:`'expired' : eventually the client reconnects to
the cluster, it is then notified of the
expiration`}),`
`]}),`
`,e.jsx(n.p,{children:`Another parameter to the ZooKeeper session establishment
call is the default watcher. Watchers are notified when any state
change occurs in the client. For example if the client loses
connectivity to the server the client will be notified, or if the
client's session expires, etc... This watcher should consider the
initial state to be disconnected (i.e. before any state changes
events are sent to the watcher by the client lib). In the case of
a new connection, the first event sent to the watcher is typically
the session connection event.`}),`
`,e.jsx(n.p,{children:`The session is kept alive by requests sent by the client. If
the session is idle for a period of time that would timeout the
session, the client will send a PING request to keep the session
alive. This PING request not only allows the ZooKeeper server to
know that the client is still active, but it also allows the
client to verify that its connection to the ZooKeeper server is
still active. The timing of the PING is conservative enough to
ensure reasonable time to detect a dead connection and reconnect
to a new server.`}),`
`,e.jsx(n.p,{children:`Once a connection to the server is successfully established
(connected) there are basically two cases where the client lib generates
connectionloss (the result code in c binding, exception in Java — see
the API documentation for binding specific details) when either a synchronous or
asynchronous operation is performed and one of the following holds:`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:`The application calls an operation on a session that is no
longer alive/valid`}),`
`,e.jsx(n.li,{children:`The ZooKeeper client disconnects from a server when there
are pending operations to that server, i.e., there is a pending asynchronous call.`}),`
`]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Added in 3.2.0 — SessionMovedException"}),`. There is an internal
exception that is generally not seen by clients called the SessionMovedException.
This exception occurs because a request was received on a connection for a session
which has been reestablished on a different server. The normal cause of this error is
a client that sends a request to a server, but the network packet gets delayed, so
the client times out and connects to a new server. When the delayed packet arrives at
the first server, the old server detects that the session has moved, and closes the
client connection. Clients normally do not see this error since they do not read
from those old connections. (Old connections are usually closed.) One situation in which this
condition can be seen is when two clients try to reestablish the same connection using
a saved session id and password. One of the clients will reestablish the connection
and the second client will be disconnected (causing the pair to attempt to re-establish
its connection/session indefinitely).`]}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Updating the list of servers"}),`. We allow a client to
update the connection string by providing a new comma separated list of host:port pairs,
each corresponding to a ZooKeeper server. The function invokes a probabilistic load-balancing
algorithm which may cause the client to disconnect from its current host with the goal
to achieve expected uniform number of connections per server in the new list.
In case the current host to which the client is connected is not in the new list
this call will always cause the connection to be dropped. Otherwise, the decision
is based on whether the number of servers has increased or decreased and by how much.`]}),`
`,e.jsx(n.p,{children:`For example, if the previous connection string contained 3 hosts and now the list contains
these 3 hosts and 2 more hosts, 40% of clients connected to each of the 3 hosts will
move to one of the new hosts in order to balance the load. The algorithm will cause the client
to drop its connection to the current host to which it is connected with probability 0.4 and in this
case cause the client to connect to one of the 2 new hosts, chosen at random.`}),`
`,e.jsx(n.p,{children:`Another example — suppose we have 5 hosts and now update the list to remove 2 of the hosts,
the clients connected to the 3 remaining hosts will stay connected, whereas all clients connected
to the 2 removed hosts will need to move to one of the 3 hosts, chosen at random. If the connection
is dropped, the client moves to a special mode where he chooses a new server to connect to using the
probabilistic algorithm, and not just round robin.`}),`
`,e.jsx(n.p,{children:`In the first example, each client decides to disconnect with probability 0.4 but once the decision is
made, it will try to connect to a random new server and only if it cannot connect to any of the new
servers will it try to connect to the old ones. After finding a server, or trying all servers in the
new list and failing to connect, the client moves back to the normal mode of operation where it picks
an arbitrary server from the connectString and attempts to connect to it. If that fails, it will continue
trying different random servers in round robin. (see above the algorithm used to initially choose a server)`}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Local session"}),". Added in 3.5.0, mainly implemented by ",e.jsx(n.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1147",children:"ZOOKEEPER-1147"}),"."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`Background: The creation and closing of sessions are costly in ZooKeeper because they need quorum confirmations,
they become the bottleneck of a ZooKeeper ensemble when it needs to handle thousands of client connections.
So after 3.5.0, we introduce a new type of session: local session which doesn't have a full functionality of a normal(global) session, this feature
will be available by turning on `,e.jsx(n.em,{children:"localSessionsEnabled"}),"."]}),`
`]}),`
`,e.jsxs(n.p,{children:["when ",e.jsx(n.em,{children:"localSessionsUpgradingEnabled"})," is disable:"]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"Local sessions cannot create ephemeral nodes"}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:`Once a local session is lost, users cannot re-establish it using the session-id/password, the session and its watches are gone for good.
Note: Losing the tcp connection does not necessarily imply that the session is lost. If the connection can be reestablished with the same zk server
before the session timeout then the client can continue (it simply cannot move to another server).`}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:`When a local session connects, the session info is only maintained on the zookeeper server that it is connected to. The leader is not aware of the creation of such a session and
there is no state written to disk.`}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"The pings, expiration and other session state maintenance are handled by the server which current session is connected to."}),`
`]}),`
`]}),`
`,e.jsxs(n.p,{children:["when ",e.jsx(n.em,{children:"localSessionsUpgradingEnabled"})," is enable:"]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"A local session can be upgraded to the global session automatically."}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["When a new session is created it is saved locally in a wrapped ",e.jsx(n.em,{children:"LocalSessionTracker"}),`. It can subsequently be upgraded
to a global session as required (e.g. create ephemeral nodes). If an upgrade is requested the session is removed from local
collections while keeping the same session ID.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Currently, Only the operation: ",e.jsx(n.em,{children:"create ephemeral node"}),` needs a session upgrade from local to global.
The reason is that the creation of ephemeral node depends heavily on a global session. If local session can create ephemeral
node without upgrading to global session, it will cause the data inconsistency between different nodes.
The leader also needs to know about the lifespan of a session in order to clean up ephemeral nodes on close/expiry.
This requires a global session as the local session is tied to its particular server.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"A session can be both a local and global session during upgrade, but the operation of upgrade cannot be called concurrently by two thread."}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"ZooKeeperServer"}),"(Standalone) uses ",e.jsx(n.em,{children:"SessionTrackerImpl"}),"; ",e.jsx(n.em,{children:"LeaderZookeeper"})," uses ",e.jsx(n.em,{children:"LeaderSessionTracker"}),` which holds
`,e.jsx(n.em,{children:"SessionTrackerImpl"}),"(global) and ",e.jsx(n.em,{children:"LocalSessionTracker"}),"(if enable); ",e.jsx(n.em,{children:"FollowerZooKeeperServer"})," and ",e.jsx(n.em,{children:"ObserverZooKeeperServer"}),`
use `,e.jsx(n.em,{children:"LearnerSessionTracker"})," which holds ",e.jsx(n.em,{children:"LocalSessionTracker"}),`.
The UML Graph of Classes about session:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"+----------------+     +--------------------+       +---------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"|                | --> |                    | ----> | LocalSessionTracker |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"| SessionTracker |     | SessionTrackerImpl |       +---------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"|                |     |                    |                              +-----------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"|                |     |                    |  +-------------------------> | LeaderSessionTracker  |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"+----------------+     +--------------------+  |                           +-----------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"          |                                   |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"          |                                   |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"          |                                   |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"          |           +---------------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"          +---------> |                           |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                      | UpgradeableSessionTracker |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                      |                           |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                      |                           | ------------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                      +---------------------------+                         |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                            |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                            |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                            v"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                          +-----------------------+"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                          | LearnerSessionTracker |"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"                                                                          +-----------------------+"})})]})})}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"qa-toc",children:"Q&A"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:e.jsx(n.em,{children:"What's the reason for having the config option to disable local session upgrade?"})}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`In a large deployment which wants to handle a very large number of clients, we know that clients connecting via the observers
which is supposed to be local session only. So this is more like a safeguard against someone accidentally creates lots of ephemeral nodes and global sessions.`}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:e.jsx(n.em,{children:"When is the session created?"})}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:["In the current implementation, it will try to create a local session when processing ",e.jsx(n.em,{children:"ConnectRequest"}),` and when
`,e.jsx(n.em,{children:"createSession"})," request reaches ",e.jsx(n.em,{children:"FinalRequestProcessor"}),"."]}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:e.jsx(n.em,{children:`What happens if the create for session is sent at server A and the client disconnects to some other server B
which ends up sending it again and then disconnects and connects back to server A?`})}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`When a client reconnects to B, its sessionId won’t exist in B’s local session tracker. So B will send validation packet.
If CreateSession issued by A is committed before validation packet arrive the client will be able to connect.
Otherwise, the client will get session expired because the quorum hasn’t know about this session yet.
If the client also tries to connect back to A again, the session is already removed from local session tracker.
So A will need to send a validation packet to the leader. The outcome should be the same as B depending on the timing of the request.`}),`
`]}),`
`]}),`
`]})]})}function d(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(s,{...t})}):s(t)}export{a as _markdown,d as default,c as extractedReferences,r as frontmatter,l as structuredData,h as toc};
