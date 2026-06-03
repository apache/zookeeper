import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";const n="/assets/2pc-C7D6TSfi.jpg";let i=`

## Atomic Broadcast

At the heart of ZooKeeper is an atomic messaging system that keeps all of the servers in sync.

### Guarantees, Properties, and Definitions

The specific guarantees provided by the ZooKeeper messaging system are:

* **Reliable delivery:** If a message \`m\` is delivered by one server, message \`m\` will eventually be delivered by all servers.
* **Total order:** If a message \`a\` is delivered before message \`b\` by one server, message \`a\` will be delivered before \`b\` by all servers.
* **Causal order:** If a message \`b\` is sent after a message \`a\` has been delivered by the sender of \`b\`, message \`a\` must be ordered before \`b\`. If a sender sends \`c\` after sending \`b\`, \`c\` must be ordered after \`b\`.

The ZooKeeper messaging system also needs to be efficient, reliable, and easy to
implement and maintain. We make heavy use of messaging, so we need the system to
be able to handle thousands of requests per second. Although we can require at
least k+1 correct servers to send new messages, we must be able to recover from
correlated failures such as power outages. When we implemented the system we had
little time and few engineering resources, so we needed a protocol that is
accessible to engineers and is easy to implement. We found that our protocol
satisfied all of these goals.

Our protocol assumes that we can construct point-to-point FIFO channels between
the servers. While similar services usually assume message delivery that can
lose or reorder messages, our assumption of FIFO channels is very practical
given that we use TCP for communication. Specifically we rely on the following property of TCP:

* **Ordered delivery:** Data is delivered in the same order it is sent and a message \`m\` is delivered only after all messages sent before \`m\` have been delivered. (The corollary is that if message \`m\` is lost, all messages after \`m\` will also be lost.)
* **No message after close:** Once a FIFO channel is closed, no messages will be received from it.

FLP proved that consensus cannot be achieved in asynchronous distributed systems
if failures are possible. To ensure that we achieve consensus in the presence of failures
we use timeouts. However, we rely on time for liveness, not for correctness. So,
if timeouts stop working (e.g., skewed clocks) the messaging system may
hang, but it will not violate its guarantees.

When describing the ZooKeeper messaging protocol we will talk of packets,
proposals, and messages:

* **Packet:** A sequence of bytes sent through a FIFO channel.
* **Proposal:** A unit of agreement. Proposals are agreed upon by exchanging packets with a quorum of ZooKeeper servers. Most proposals contain messages; however, the NEW\\_LEADER proposal is an example of a proposal that does not contain a message.
* **Message:** A sequence of bytes to be atomically broadcast to all ZooKeeper servers. A message is put into a proposal and agreed upon before it is delivered.

As stated above, ZooKeeper guarantees a total order of messages, and it also
guarantees a total order of proposals. ZooKeeper exposes the total ordering using
a ZooKeeper transaction id (*zxid*). All proposals will be stamped with a zxid when
proposed and exactly reflect the total ordering. Proposals are sent to all
ZooKeeper servers and committed when a quorum of them acknowledge the proposal.
If a proposal contains a message, the message will be delivered when the proposal
is committed. Acknowledgement means the server has recorded the proposal to persistent storage.
Our quorums have the requirement that any pair of quorums must have at least one server
in common. We ensure this by requiring that all quorums have size (*n/2+1*) where
n is the number of servers that make up a ZooKeeper service.

The zxid has two parts: the epoch and a counter. In our implementation the zxid
is a 64-bit number. We use the high order 32-bits for the epoch and the low order
32-bits for the counter. Because a zxid consists of two parts, it can be represented both as a
number and as a pair of integers, (*epoch, count*). The epoch number represents a
change in leadership. Each time a new leader comes into power it will have its
own epoch number. We have a simple algorithm to assign a unique zxid to a proposal:
the leader simply increments the zxid to obtain a unique zxid for each proposal.
*Leadership activation will ensure that only one leader uses a given epoch, so our
simple algorithm guarantees that every proposal will have a unique id.*

ZooKeeper messaging consists of two phases:

* **Leader activation:** In this phase a leader establishes the correct state of the system and gets ready to start making proposals.
* **Active messaging:** In this phase a leader accepts messages to propose and coordinates message delivery.

ZooKeeper is a holistic protocol. We do not focus on individual proposals, rather
we look at the stream of proposals as a whole. Our strict ordering allows us to do this
efficiently and greatly simplifies our protocol. Leadership activation embodies
this holistic concept. A leader becomes active only when a quorum of followers
(the leader counts as a follower as well — you can always vote for yourself) has synced
up with the leader: they have the same state. This state consists of all of the
proposals that the leader believes have been committed and the proposal to follow
the leader, the \`NEW_LEADER\` proposal. (Hopefully you are thinking:
*Does the set of proposals that the leader believes has been committed
include all the proposals that really have been committed?* The answer is *yes*.
Below, we make clear why.)

### Leader Activation

Leader activation includes leader election (\`FastLeaderElection\`).
ZooKeeper messaging doesn't care about the exact method of electing a leader as long as the following holds:

* The leader has seen the highest zxid of all the followers.
* A quorum of servers have committed to following the leader.

Of these two requirements, only the first — the highest zxid among the followers —
needs to hold for correct operation. The second requirement, a quorum of followers,
just needs to hold with high probability. We are going to recheck the second requirement,
so if a failure happens during or after the leader election and quorum is lost,
we will recover by abandoning leader activation and running another election.

After leader election a single server will be designated as a leader and start
waiting for followers to connect. The rest of the servers will try to connect to
the leader. The leader will sync up with the followers by sending any proposals they
are missing, or if a follower is missing too many proposals, it will send a full
snapshot of the state to the follower.

There is a corner case in which a follower that has proposals, \`U\`, not seen
by a leader arrives. Proposals are seen in order, so the proposals of \`U\` will have zxids
higher than zxids seen by the leader. The follower must have arrived after the
leader election, otherwise the follower would have been elected leader given that
it has seen a higher zxid. Since committed proposals must be seen by a quorum of
servers, and a quorum of servers that elected the leader did not see \`U\`, the proposals
of \`U\` have not been committed, so they can be discarded. When the follower connects
to the leader, the leader will tell the follower to discard \`U\`.

A new leader establishes a zxid to start using for new proposals by getting the
epoch, \`e\`, of the highest zxid it has seen and setting the next zxid to use to be
\`(e+1, 0)\`. After the leader syncs with a follower, it will propose a NEW\\_LEADER
proposal. Once the NEW\\_LEADER proposal has been committed, the leader will activate
and start receiving and issuing proposals.

The basic rules of operation during leader activation are:

* A follower will ACK the NEW\\_LEADER proposal after it has synced with the leader.
* A follower will only ACK a NEW\\_LEADER proposal with a given zxid from a single server.
* A new leader will COMMIT the NEW\\_LEADER proposal when a quorum of followers has ACKed it.
* A follower will commit any state it received from the leader when the NEW\\_LEADER proposal is COMMITted.
* A new leader will not accept new proposals until the NEW\\_LEADER proposal has been COMMITted.

If leader election terminates erroneously, we don't have a problem since the
NEW\\_LEADER proposal will not be committed because the leader will not have quorum.
When this happens, the leader and any remaining followers will timeout and go back
to leader election.

### Active Messaging

Leader Activation does all the heavy lifting. Once the leader is coronated it can
start blasting out proposals. As long as it remains the leader no other leader can
emerge since no other leader will be able to get a quorum of followers. If a new
leader does emerge, it means that the current leader has lost quorum, and the new
leader will clean up any mess left over during its activation.

ZooKeeper messaging operates similar to a classic two-phase commit.

<img alt="Two phase commit" src={__img0} placeholder="blur" />

All communication channels are FIFO, so everything is done in order. Specifically
the following operating constraints are observed:

* The leader sends proposals to all followers using the same order. Moreover, this order follows the order in which requests were received. Because we use FIFO channels this means that followers also receive proposals in order.
* Followers process messages in the order they are received. This means that messages will be ACKed in order and the leader will receive ACKs from followers in order, due to the FIFO channels. It also means that if message \`m\` has been written to non-volatile storage, all messages that were proposed before \`m\` have been written to non-volatile storage.
* The leader will issue a COMMIT to all followers as soon as a quorum of followers have ACKed a message. Since messages are ACKed in order, COMMITs will be sent by the leader and received by the followers in order.
* COMMITs are processed in order. Followers deliver a proposal message when that proposal is committed.

### Summary

Why does it work? Specifically, why does a set of proposals believed by a new leader
always contain any proposal that has actually been committed? First, all proposals have
a unique zxid, so unlike other protocols, we never have to worry about two different
values being proposed for the same zxid; followers (a leader is also a follower) see
and record proposals in order; proposals are committed in order; there is only one active
leader at a time since followers only follow a single leader at a time; a new leader has
seen all committed proposals from the previous epoch since it has seen the highest zxid
from a quorum of servers; any uncommitted proposals from a previous epoch seen by a new
leader will be committed by that leader before it becomes active.

### Comparisons

**Isn't this just Multi-Paxos?** No. Multi-Paxos requires some way of assuring that
there is only a single coordinator. We do not count on such assurances. Instead
we use leader activation to recover from leadership changes or old leaders
believing they are still active.

**Isn't this just Paxos? The active messaging phase looks just like phase 2 of Paxos.**
Actually, to us active messaging looks just like 2-phase commit without the need to
handle aborts. Active messaging is different from both in the sense that it has
cross-proposal ordering requirements. If we do not maintain strict FIFO ordering of
all packets, it all falls apart. Also, our leader activation phase is different from
both of them. In particular, our use of epochs allows us to skip blocks of uncommitted
proposals and to not worry about duplicate proposals for a given zxid.

## Consistency Guarantees

The [consistency](https://jepsen.io/consistency) guarantees of ZooKeeper lie between
sequential consistency and linearizability.

Write operations in ZooKeeper are *linearizable*. Each \`write\` will appear to take effect
atomically at some point between when the client issues the request and receives the
corresponding response. This means that the writes performed by all clients can be totally
ordered in a way that respects their real-time ordering. However, merely stating that writes
are linearizable is meaningless unless we also talk about reads.

Read operations in ZooKeeper are *not linearizable* since they can return stale data. A \`read\`
in ZooKeeper is not a quorum operation — a server will respond immediately to a client
performing a \`read\`. ZooKeeper prioritizes performance over consistency for reads. However,
reads are *sequentially consistent*, because \`read\` operations appear to take effect in some
sequential order that respects each client's operation order. A common workaround is to issue
a \`sync\` before a \`read\`. This too does **not** strictly guarantee up-to-date data because
\`sync\` is [not currently a quorum operation](https://issues.apache.org/jira/browse/ZOOKEEPER-1675).

To illustrate: consider a scenario where two servers simultaneously think they are the leader,
which could occur if the TCP connection timeout is smaller than \`syncLimit * tickTime\`. Note
that this is [unlikely](https://www.amazon.com/ZooKeeper-Distributed-Coordination-Flavio-Junqueira/dp/1449361307)
to occur in practice, but is worth keeping in mind. Under this scenario, the \`sync\` may be
served by the "leader" with stale data, allowing the following \`read\` to be stale as well.
The stronger guarantee of linearizability is provided if an actual quorum operation (e.g., a
\`write\`) is performed before a \`read\`.

Overall, the consistency guarantees of ZooKeeper are formally captured by the notion of
[ordered sequential consistency](http://webee.technion.ac.il/people/idish/ftp/OSC-IPL17.pdf)
(\`OSC(U)\`), which lies between sequential consistency and linearizability.

## Quorums

Atomic broadcast and leader election use the notion of quorums to guarantee a consistent
view of the system. By default, ZooKeeper uses majority quorums, which means that every
voting operation requires a majority to vote. One example is acknowledging a leader proposal:
the leader can only commit once it receives an acknowledgement from a quorum of servers.

If we extract the properties that we really need from our use of majorities, we have that we only
need to guarantee that groups of processes used to validate an operation by voting pairwise
intersect in at least one server. Using majorities guarantees such a property.
However, there are other ways of constructing quorums. For example, we can assign
weights to the votes of servers, and say that the votes of some servers are more important.
To obtain a quorum, we get enough votes so that the sum of weights of all votes is larger
than half of the total sum of all weights.

A different construction that uses weights and is useful in wide-area deployments is a
hierarchical one. With this construction, we split the servers into disjoint groups and
assign weights to processes. To form a quorum, we have to get enough servers from a majority
of groups G, such that for each group g in G, the sum of votes from g is larger than half of
the sum of weights in g. Interestingly, this construction enables smaller quorums. If we have
9 servers split into 3 groups with a weight of 1 each, we are able to form quorums of size 4.
Note that two subsets of processes each composed of a majority of servers from a majority of
groups necessarily have a non-empty intersection. It is reasonable to expect that a majority
of co-locations will have a majority of servers available with high probability.

ZooKeeper provides users with the ability to configure servers to use majority quorums,
weighted quorums, or a hierarchy of groups.

## Logging

ZooKeeper uses [slf4j](http://www.slf4j.org/index.html) as an abstraction layer for logging.
[Logback](https://logback.qos.ch/) has been chosen as the logging backend since ZooKeeper 3.8.0.
For better embedding support, it is planned in the future to leave the choice of logging
implementation to the end user. Therefore, always use the slf4j API for log statements in code,
but configure Logback for runtime logging behavior.
Note that slf4j has no FATAL level; former FATAL-level messages have been moved to ERROR.
For information on configuring Logback for ZooKeeper, see the
[Logging](/admin-ops/administrators-guide/administration#logging) section of the
[ZooKeeper Administrator's Guide](/admin-ops/administrators-guide).

### Developer Guidelines

Follow the [slf4j manual](http://www.slf4j.org/manual.html) when creating log statements in
code. Also read the [FAQ on logging performance](http://www.slf4j.org/faq.html#logging_performance).
Patch reviewers will look for the following:

#### Logging at the Right Level

There are several log levels in slf4j, in order of decreasing severity:

1. **ERROR** — error events that might still allow the application to continue running.
2. **WARN** — potentially harmful situations.
3. **INFO** — informational messages that highlight the progress of the application at a coarse-grained level.
4. **DEBUG** — fine-grained informational events most useful for debugging.
5. **TRACE** — finer-grained informational events than DEBUG.

ZooKeeper is typically run in production such that INFO and above are output to the log.

#### Use of Standard slf4j Idioms

**Static message logging:**

\`\`\`java
LOG.debug("process completed successfully!");
\`\`\`

For parameterized messages, use formatting anchors:

\`\`\`java
LOG.debug("got {} messages in {} minutes", new Object[]{count, time});
\`\`\`

**Naming:** Loggers should be named after the class in which they are used.

\`\`\`java
public class Foo {
    private static final Logger LOG = LoggerFactory.getLogger(Foo.class);

    public Foo() {
        LOG.info("constructing Foo");
    }
}
\`\`\`

**Exception handling:**

\`\`\`java
try {
    // code
} catch (XYZException e) {
    // do this
    LOG.error("Something bad happened", e);
    // don't do this (generally):
    // LOG.error(e);
    // the above hides the stack trace

    // continue, recover, or rethrow as appropriate
}
\`\`\`
`,r={title:"Internals",description:"This document covers the inner workings of ZooKeeper, including the atomic broadcast protocol, consistency guarantees, quorum design, and logging conventions."},l=[{href:"https://jepsen.io/consistency"},{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1675"},{href:"https://www.amazon.com/ZooKeeper-Distributed-Coordination-Flavio-Junqueira/dp/1449361307"},{href:"http://webee.technion.ac.il/people/idish/ftp/OSC-IPL17.pdf"},{href:"http://www.slf4j.org/index.html"},{href:"https://logback.qos.ch/"},{href:"/admin-ops/administrators-guide/administration#logging"},{href:"/admin-ops/administrators-guide"},{href:"http://www.slf4j.org/manual.html"},{href:"http://www.slf4j.org/faq.html#logging_performance"}],h={contents:[{heading:"atomic-broadcast",content:"At the heart of ZooKeeper is an atomic messaging system that keeps all of the servers in sync."},{heading:"guarantees-properties-and-definitions",content:"The specific guarantees provided by the ZooKeeper messaging system are:"},{heading:"guarantees-properties-and-definitions",content:"Reliable delivery: If a message m is delivered by one server, message m will eventually be delivered by all servers."},{heading:"guarantees-properties-and-definitions",content:"Total order: If a message a is delivered before message b by one server, message a will be delivered before b by all servers."},{heading:"guarantees-properties-and-definitions",content:"Causal order: If a message b is sent after a message a has been delivered by the sender of b, message a must be ordered before b. If a sender sends c after sending b, c must be ordered after b."},{heading:"guarantees-properties-and-definitions",content:`The ZooKeeper messaging system also needs to be efficient, reliable, and easy to
implement and maintain. We make heavy use of messaging, so we need the system to
be able to handle thousands of requests per second. Although we can require at
least k+1 correct servers to send new messages, we must be able to recover from
correlated failures such as power outages. When we implemented the system we had
little time and few engineering resources, so we needed a protocol that is
accessible to engineers and is easy to implement. We found that our protocol
satisfied all of these goals.`},{heading:"guarantees-properties-and-definitions",content:`Our protocol assumes that we can construct point-to-point FIFO channels between
the servers. While similar services usually assume message delivery that can
lose or reorder messages, our assumption of FIFO channels is very practical
given that we use TCP for communication. Specifically we rely on the following property of TCP:`},{heading:"guarantees-properties-and-definitions",content:"Ordered delivery: Data is delivered in the same order it is sent and a message m is delivered only after all messages sent before m have been delivered. (The corollary is that if message m is lost, all messages after m will also be lost.)"},{heading:"guarantees-properties-and-definitions",content:"No message after close: Once a FIFO channel is closed, no messages will be received from it."},{heading:"guarantees-properties-and-definitions",content:`FLP proved that consensus cannot be achieved in asynchronous distributed systems
if failures are possible. To ensure that we achieve consensus in the presence of failures
we use timeouts. However, we rely on time for liveness, not for correctness. So,
if timeouts stop working (e.g., skewed clocks) the messaging system may
hang, but it will not violate its guarantees.`},{heading:"guarantees-properties-and-definitions",content:`When describing the ZooKeeper messaging protocol we will talk of packets,
proposals, and messages:`},{heading:"guarantees-properties-and-definitions",content:"Packet: A sequence of bytes sent through a FIFO channel."},{heading:"guarantees-properties-and-definitions",content:"Proposal: A unit of agreement. Proposals are agreed upon by exchanging packets with a quorum of ZooKeeper servers. Most proposals contain messages; however, the NEW_LEADER proposal is an example of a proposal that does not contain a message."},{heading:"guarantees-properties-and-definitions",content:"Message: A sequence of bytes to be atomically broadcast to all ZooKeeper servers. A message is put into a proposal and agreed upon before it is delivered."},{heading:"guarantees-properties-and-definitions",content:`As stated above, ZooKeeper guarantees a total order of messages, and it also
guarantees a total order of proposals. ZooKeeper exposes the total ordering using
a ZooKeeper transaction id (zxid). All proposals will be stamped with a zxid when
proposed and exactly reflect the total ordering. Proposals are sent to all
ZooKeeper servers and committed when a quorum of them acknowledge the proposal.
If a proposal contains a message, the message will be delivered when the proposal
is committed. Acknowledgement means the server has recorded the proposal to persistent storage.
Our quorums have the requirement that any pair of quorums must have at least one server
in common. We ensure this by requiring that all quorums have size (n/2+1) where
n is the number of servers that make up a ZooKeeper service.`},{heading:"guarantees-properties-and-definitions",content:`The zxid has two parts: the epoch and a counter. In our implementation the zxid
is a 64-bit number. We use the high order 32-bits for the epoch and the low order
32-bits for the counter. Because a zxid consists of two parts, it can be represented both as a
number and as a pair of integers, (epoch, count). The epoch number represents a
change in leadership. Each time a new leader comes into power it will have its
own epoch number. We have a simple algorithm to assign a unique zxid to a proposal:
the leader simply increments the zxid to obtain a unique zxid for each proposal.
Leadership activation will ensure that only one leader uses a given epoch, so our
simple algorithm guarantees that every proposal will have a unique id.`},{heading:"guarantees-properties-and-definitions",content:"ZooKeeper messaging consists of two phases:"},{heading:"guarantees-properties-and-definitions",content:"Leader activation: In this phase a leader establishes the correct state of the system and gets ready to start making proposals."},{heading:"guarantees-properties-and-definitions",content:"Active messaging: In this phase a leader accepts messages to propose and coordinates message delivery."},{heading:"guarantees-properties-and-definitions",content:`ZooKeeper is a holistic protocol. We do not focus on individual proposals, rather
we look at the stream of proposals as a whole. Our strict ordering allows us to do this
efficiently and greatly simplifies our protocol. Leadership activation embodies
this holistic concept. A leader becomes active only when a quorum of followers
(the leader counts as a follower as well — you can always vote for yourself) has synced
up with the leader: they have the same state. This state consists of all of the
proposals that the leader believes have been committed and the proposal to follow
the leader, the NEW_LEADER proposal. (Hopefully you are thinking:
Does the set of proposals that the leader believes has been committed
include all the proposals that really have been committed? The answer is yes.
Below, we make clear why.)`},{heading:"leader-activation",content:`Leader activation includes leader election (FastLeaderElection).
ZooKeeper messaging doesn't care about the exact method of electing a leader as long as the following holds:`},{heading:"leader-activation",content:"The leader has seen the highest zxid of all the followers."},{heading:"leader-activation",content:"A quorum of servers have committed to following the leader."},{heading:"leader-activation",content:`Of these two requirements, only the first — the highest zxid among the followers —
needs to hold for correct operation. The second requirement, a quorum of followers,
just needs to hold with high probability. We are going to recheck the second requirement,
so if a failure happens during or after the leader election and quorum is lost,
we will recover by abandoning leader activation and running another election.`},{heading:"leader-activation",content:`After leader election a single server will be designated as a leader and start
waiting for followers to connect. The rest of the servers will try to connect to
the leader. The leader will sync up with the followers by sending any proposals they
are missing, or if a follower is missing too many proposals, it will send a full
snapshot of the state to the follower.`},{heading:"leader-activation",content:`There is a corner case in which a follower that has proposals, U, not seen
by a leader arrives. Proposals are seen in order, so the proposals of U will have zxids
higher than zxids seen by the leader. The follower must have arrived after the
leader election, otherwise the follower would have been elected leader given that
it has seen a higher zxid. Since committed proposals must be seen by a quorum of
servers, and a quorum of servers that elected the leader did not see U, the proposals
of U have not been committed, so they can be discarded. When the follower connects
to the leader, the leader will tell the follower to discard U.`},{heading:"leader-activation",content:`A new leader establishes a zxid to start using for new proposals by getting the
epoch, e, of the highest zxid it has seen and setting the next zxid to use to be
(e+1, 0). After the leader syncs with a follower, it will propose a NEW_LEADER
proposal. Once the NEW_LEADER proposal has been committed, the leader will activate
and start receiving and issuing proposals.`},{heading:"leader-activation",content:"The basic rules of operation during leader activation are:"},{heading:"leader-activation",content:"A follower will ACK the NEW_LEADER proposal after it has synced with the leader."},{heading:"leader-activation",content:"A follower will only ACK a NEW_LEADER proposal with a given zxid from a single server."},{heading:"leader-activation",content:"A new leader will COMMIT the NEW_LEADER proposal when a quorum of followers has ACKed it."},{heading:"leader-activation",content:"A follower will commit any state it received from the leader when the NEW_LEADER proposal is COMMITted."},{heading:"leader-activation",content:"A new leader will not accept new proposals until the NEW_LEADER proposal has been COMMITted."},{heading:"leader-activation",content:`If leader election terminates erroneously, we don't have a problem since the
NEW_LEADER proposal will not be committed because the leader will not have quorum.
When this happens, the leader and any remaining followers will timeout and go back
to leader election.`},{heading:"active-messaging",content:`Leader Activation does all the heavy lifting. Once the leader is coronated it can
start blasting out proposals. As long as it remains the leader no other leader can
emerge since no other leader will be able to get a quorum of followers. If a new
leader does emerge, it means that the current leader has lost quorum, and the new
leader will clean up any mess left over during its activation.`},{heading:"active-messaging",content:"ZooKeeper messaging operates similar to a classic two-phase commit."},{heading:"active-messaging",content:`All communication channels are FIFO, so everything is done in order. Specifically
the following operating constraints are observed:`},{heading:"active-messaging",content:"The leader sends proposals to all followers using the same order. Moreover, this order follows the order in which requests were received. Because we use FIFO channels this means that followers also receive proposals in order."},{heading:"active-messaging",content:"Followers process messages in the order they are received. This means that messages will be ACKed in order and the leader will receive ACKs from followers in order, due to the FIFO channels. It also means that if message m has been written to non-volatile storage, all messages that were proposed before m have been written to non-volatile storage."},{heading:"active-messaging",content:"The leader will issue a COMMIT to all followers as soon as a quorum of followers have ACKed a message. Since messages are ACKed in order, COMMITs will be sent by the leader and received by the followers in order."},{heading:"active-messaging",content:"COMMITs are processed in order. Followers deliver a proposal message when that proposal is committed."},{heading:"summary",content:`Why does it work? Specifically, why does a set of proposals believed by a new leader
always contain any proposal that has actually been committed? First, all proposals have
a unique zxid, so unlike other protocols, we never have to worry about two different
values being proposed for the same zxid; followers (a leader is also a follower) see
and record proposals in order; proposals are committed in order; there is only one active
leader at a time since followers only follow a single leader at a time; a new leader has
seen all committed proposals from the previous epoch since it has seen the highest zxid
from a quorum of servers; any uncommitted proposals from a previous epoch seen by a new
leader will be committed by that leader before it becomes active.`},{heading:"comparisons",content:`Isn't this just Multi-Paxos? No. Multi-Paxos requires some way of assuring that
there is only a single coordinator. We do not count on such assurances. Instead
we use leader activation to recover from leadership changes or old leaders
believing they are still active.`},{heading:"comparisons",content:`Isn't this just Paxos? The active messaging phase looks just like phase 2 of Paxos.
Actually, to us active messaging looks just like 2-phase commit without the need to
handle aborts. Active messaging is different from both in the sense that it has
cross-proposal ordering requirements. If we do not maintain strict FIFO ordering of
all packets, it all falls apart. Also, our leader activation phase is different from
both of them. In particular, our use of epochs allows us to skip blocks of uncommitted
proposals and to not worry about duplicate proposals for a given zxid.`},{heading:"consistency-guarantees",content:`The consistency guarantees of ZooKeeper lie between
sequential consistency and linearizability.`},{heading:"consistency-guarantees",content:`Write operations in ZooKeeper are linearizable. Each write will appear to take effect
atomically at some point between when the client issues the request and receives the
corresponding response. This means that the writes performed by all clients can be totally
ordered in a way that respects their real-time ordering. However, merely stating that writes
are linearizable is meaningless unless we also talk about reads.`},{heading:"consistency-guarantees",content:`Read operations in ZooKeeper are not linearizable since they can return stale data. A read
in ZooKeeper is not a quorum operation — a server will respond immediately to a client
performing a read. ZooKeeper prioritizes performance over consistency for reads. However,
reads are sequentially consistent, because read operations appear to take effect in some
sequential order that respects each client's operation order. A common workaround is to issue
a sync before a read. This too does not strictly guarantee up-to-date data because
sync is not currently a quorum operation.`},{heading:"consistency-guarantees",content:`To illustrate: consider a scenario where two servers simultaneously think they are the leader,
which could occur if the TCP connection timeout is smaller than syncLimit * tickTime. Note
that this is unlikely
to occur in practice, but is worth keeping in mind. Under this scenario, the sync may be
served by the "leader" with stale data, allowing the following read to be stale as well.
The stronger guarantee of linearizability is provided if an actual quorum operation (e.g., a
write) is performed before a read.`},{heading:"consistency-guarantees",content:`Overall, the consistency guarantees of ZooKeeper are formally captured by the notion of
ordered sequential consistency
(OSC(U)), which lies between sequential consistency and linearizability.`},{heading:"quorums",content:`Atomic broadcast and leader election use the notion of quorums to guarantee a consistent
view of the system. By default, ZooKeeper uses majority quorums, which means that every
voting operation requires a majority to vote. One example is acknowledging a leader proposal:
the leader can only commit once it receives an acknowledgement from a quorum of servers.`},{heading:"quorums",content:`If we extract the properties that we really need from our use of majorities, we have that we only
need to guarantee that groups of processes used to validate an operation by voting pairwise
intersect in at least one server. Using majorities guarantees such a property.
However, there are other ways of constructing quorums. For example, we can assign
weights to the votes of servers, and say that the votes of some servers are more important.
To obtain a quorum, we get enough votes so that the sum of weights of all votes is larger
than half of the total sum of all weights.`},{heading:"quorums",content:`A different construction that uses weights and is useful in wide-area deployments is a
hierarchical one. With this construction, we split the servers into disjoint groups and
assign weights to processes. To form a quorum, we have to get enough servers from a majority
of groups G, such that for each group g in G, the sum of votes from g is larger than half of
the sum of weights in g. Interestingly, this construction enables smaller quorums. If we have
9 servers split into 3 groups with a weight of 1 each, we are able to form quorums of size 4.
Note that two subsets of processes each composed of a majority of servers from a majority of
groups necessarily have a non-empty intersection. It is reasonable to expect that a majority
of co-locations will have a majority of servers available with high probability.`},{heading:"quorums",content:`ZooKeeper provides users with the ability to configure servers to use majority quorums,
weighted quorums, or a hierarchy of groups.`},{heading:"logging",content:`ZooKeeper uses slf4j as an abstraction layer for logging.
Logback has been chosen as the logging backend since ZooKeeper 3.8.0.
For better embedding support, it is planned in the future to leave the choice of logging
implementation to the end user. Therefore, always use the slf4j API for log statements in code,
but configure Logback for runtime logging behavior.
Note that slf4j has no FATAL level; former FATAL-level messages have been moved to ERROR.
For information on configuring Logback for ZooKeeper, see the
Logging section of the
ZooKeeper Administrator's Guide.`},{heading:"developer-guidelines",content:`Follow the slf4j manual when creating log statements in
code. Also read the FAQ on logging performance.
Patch reviewers will look for the following:`},{heading:"logging-at-the-right-level",content:"There are several log levels in slf4j, in order of decreasing severity:"},{heading:"logging-at-the-right-level",content:"ERROR — error events that might still allow the application to continue running."},{heading:"logging-at-the-right-level",content:"WARN — potentially harmful situations."},{heading:"logging-at-the-right-level",content:"INFO — informational messages that highlight the progress of the application at a coarse-grained level."},{heading:"logging-at-the-right-level",content:"DEBUG — fine-grained informational events most useful for debugging."},{heading:"logging-at-the-right-level",content:"TRACE — finer-grained informational events than DEBUG."},{heading:"logging-at-the-right-level",content:"ZooKeeper is typically run in production such that INFO and above are output to the log."},{heading:"use-of-standard-slf4j-idioms",content:"Static message logging:"},{heading:"use-of-standard-slf4j-idioms",content:"For parameterized messages, use formatting anchors:"},{heading:"use-of-standard-slf4j-idioms",content:"Naming: Loggers should be named after the class in which they are used."},{heading:"use-of-standard-slf4j-idioms",content:"Exception handling:"}],headings:[{id:"atomic-broadcast",content:"Atomic Broadcast"},{id:"guarantees-properties-and-definitions",content:"Guarantees, Properties, and Definitions"},{id:"leader-activation",content:"Leader Activation"},{id:"active-messaging",content:"Active Messaging"},{id:"summary",content:"Summary"},{id:"comparisons",content:"Comparisons"},{id:"consistency-guarantees",content:"Consistency Guarantees"},{id:"quorums",content:"Quorums"},{id:"logging",content:"Logging"},{id:"developer-guidelines",content:"Developer Guidelines"},{id:"logging-at-the-right-level",content:"Logging at the Right Level"},{id:"use-of-standard-slf4j-idioms",content:"Use of Standard slf4j Idioms"}]};const d=[{depth:2,url:"#atomic-broadcast",title:e.jsx(e.Fragment,{children:"Atomic Broadcast"})},{depth:3,url:"#guarantees-properties-and-definitions",title:e.jsx(e.Fragment,{children:"Guarantees, Properties, and Definitions"})},{depth:3,url:"#leader-activation",title:e.jsx(e.Fragment,{children:"Leader Activation"})},{depth:3,url:"#active-messaging",title:e.jsx(e.Fragment,{children:"Active Messaging"})},{depth:3,url:"#summary",title:e.jsx(e.Fragment,{children:"Summary"})},{depth:3,url:"#comparisons",title:e.jsx(e.Fragment,{children:"Comparisons"})},{depth:2,url:"#consistency-guarantees",title:e.jsx(e.Fragment,{children:"Consistency Guarantees"})},{depth:2,url:"#quorums",title:e.jsx(e.Fragment,{children:"Quorums"})},{depth:2,url:"#logging",title:e.jsx(e.Fragment,{children:"Logging"})},{depth:3,url:"#developer-guidelines",title:e.jsx(e.Fragment,{children:"Developer Guidelines"})},{depth:4,url:"#logging-at-the-right-level",title:e.jsx(e.Fragment,{children:"Logging at the Right Level"})},{depth:4,url:"#use-of-standard-slf4j-idioms",title:e.jsx(e.Fragment,{children:"Use of Standard slf4j Idioms"})}];function t(o){const s={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",h4:"h4",img:"img",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...o.components};return e.jsxs(e.Fragment,{children:[e.jsx(s.h2,{id:"atomic-broadcast",children:"Atomic Broadcast"}),`
`,e.jsx(s.p,{children:"At the heart of ZooKeeper is an atomic messaging system that keeps all of the servers in sync."}),`
`,e.jsx(s.h3,{id:"guarantees-properties-and-definitions",children:"Guarantees, Properties, and Definitions"}),`
`,e.jsx(s.p,{children:"The specific guarantees provided by the ZooKeeper messaging system are:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Reliable delivery:"})," If a message ",e.jsx(s.code,{children:"m"})," is delivered by one server, message ",e.jsx(s.code,{children:"m"})," will eventually be delivered by all servers."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Total order:"})," If a message ",e.jsx(s.code,{children:"a"})," is delivered before message ",e.jsx(s.code,{children:"b"})," by one server, message ",e.jsx(s.code,{children:"a"})," will be delivered before ",e.jsx(s.code,{children:"b"})," by all servers."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Causal order:"})," If a message ",e.jsx(s.code,{children:"b"})," is sent after a message ",e.jsx(s.code,{children:"a"})," has been delivered by the sender of ",e.jsx(s.code,{children:"b"}),", message ",e.jsx(s.code,{children:"a"})," must be ordered before ",e.jsx(s.code,{children:"b"}),". If a sender sends ",e.jsx(s.code,{children:"c"})," after sending ",e.jsx(s.code,{children:"b"}),", ",e.jsx(s.code,{children:"c"})," must be ordered after ",e.jsx(s.code,{children:"b"}),"."]}),`
`]}),`
`,e.jsx(s.p,{children:`The ZooKeeper messaging system also needs to be efficient, reliable, and easy to
implement and maintain. We make heavy use of messaging, so we need the system to
be able to handle thousands of requests per second. Although we can require at
least k+1 correct servers to send new messages, we must be able to recover from
correlated failures such as power outages. When we implemented the system we had
little time and few engineering resources, so we needed a protocol that is
accessible to engineers and is easy to implement. We found that our protocol
satisfied all of these goals.`}),`
`,e.jsx(s.p,{children:`Our protocol assumes that we can construct point-to-point FIFO channels between
the servers. While similar services usually assume message delivery that can
lose or reorder messages, our assumption of FIFO channels is very practical
given that we use TCP for communication. Specifically we rely on the following property of TCP:`}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Ordered delivery:"})," Data is delivered in the same order it is sent and a message ",e.jsx(s.code,{children:"m"})," is delivered only after all messages sent before ",e.jsx(s.code,{children:"m"})," have been delivered. (The corollary is that if message ",e.jsx(s.code,{children:"m"})," is lost, all messages after ",e.jsx(s.code,{children:"m"})," will also be lost.)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"No message after close:"})," Once a FIFO channel is closed, no messages will be received from it."]}),`
`]}),`
`,e.jsx(s.p,{children:`FLP proved that consensus cannot be achieved in asynchronous distributed systems
if failures are possible. To ensure that we achieve consensus in the presence of failures
we use timeouts. However, we rely on time for liveness, not for correctness. So,
if timeouts stop working (e.g., skewed clocks) the messaging system may
hang, but it will not violate its guarantees.`}),`
`,e.jsx(s.p,{children:`When describing the ZooKeeper messaging protocol we will talk of packets,
proposals, and messages:`}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Packet:"})," A sequence of bytes sent through a FIFO channel."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Proposal:"})," A unit of agreement. Proposals are agreed upon by exchanging packets with a quorum of ZooKeeper servers. Most proposals contain messages; however, the NEW_LEADER proposal is an example of a proposal that does not contain a message."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Message:"})," A sequence of bytes to be atomically broadcast to all ZooKeeper servers. A message is put into a proposal and agreed upon before it is delivered."]}),`
`]}),`
`,e.jsxs(s.p,{children:[`As stated above, ZooKeeper guarantees a total order of messages, and it also
guarantees a total order of proposals. ZooKeeper exposes the total ordering using
a ZooKeeper transaction id (`,e.jsx(s.em,{children:"zxid"}),`). All proposals will be stamped with a zxid when
proposed and exactly reflect the total ordering. Proposals are sent to all
ZooKeeper servers and committed when a quorum of them acknowledge the proposal.
If a proposal contains a message, the message will be delivered when the proposal
is committed. Acknowledgement means the server has recorded the proposal to persistent storage.
Our quorums have the requirement that any pair of quorums must have at least one server
in common. We ensure this by requiring that all quorums have size (`,e.jsx(s.em,{children:"n/2+1"}),`) where
n is the number of servers that make up a ZooKeeper service.`]}),`
`,e.jsxs(s.p,{children:[`The zxid has two parts: the epoch and a counter. In our implementation the zxid
is a 64-bit number. We use the high order 32-bits for the epoch and the low order
32-bits for the counter. Because a zxid consists of two parts, it can be represented both as a
number and as a pair of integers, (`,e.jsx(s.em,{children:"epoch, count"}),`). The epoch number represents a
change in leadership. Each time a new leader comes into power it will have its
own epoch number. We have a simple algorithm to assign a unique zxid to a proposal:
the leader simply increments the zxid to obtain a unique zxid for each proposal.
`,e.jsx(s.em,{children:`Leadership activation will ensure that only one leader uses a given epoch, so our
simple algorithm guarantees that every proposal will have a unique id.`})]}),`
`,e.jsx(s.p,{children:"ZooKeeper messaging consists of two phases:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Leader activation:"})," In this phase a leader establishes the correct state of the system and gets ready to start making proposals."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"Active messaging:"})," In this phase a leader accepts messages to propose and coordinates message delivery."]}),`
`]}),`
`,e.jsxs(s.p,{children:[`ZooKeeper is a holistic protocol. We do not focus on individual proposals, rather
we look at the stream of proposals as a whole. Our strict ordering allows us to do this
efficiently and greatly simplifies our protocol. Leadership activation embodies
this holistic concept. A leader becomes active only when a quorum of followers
(the leader counts as a follower as well — you can always vote for yourself) has synced
up with the leader: they have the same state. This state consists of all of the
proposals that the leader believes have been committed and the proposal to follow
the leader, the `,e.jsx(s.code,{children:"NEW_LEADER"}),` proposal. (Hopefully you are thinking:
`,e.jsx(s.em,{children:`Does the set of proposals that the leader believes has been committed
include all the proposals that really have been committed?`})," The answer is ",e.jsx(s.em,{children:"yes"}),`.
Below, we make clear why.)`]}),`
`,e.jsx(s.h3,{id:"leader-activation",children:"Leader Activation"}),`
`,e.jsxs(s.p,{children:["Leader activation includes leader election (",e.jsx(s.code,{children:"FastLeaderElection"}),`).
ZooKeeper messaging doesn't care about the exact method of electing a leader as long as the following holds:`]}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsx(s.li,{children:"The leader has seen the highest zxid of all the followers."}),`
`,e.jsx(s.li,{children:"A quorum of servers have committed to following the leader."}),`
`]}),`
`,e.jsx(s.p,{children:`Of these two requirements, only the first — the highest zxid among the followers —
needs to hold for correct operation. The second requirement, a quorum of followers,
just needs to hold with high probability. We are going to recheck the second requirement,
so if a failure happens during or after the leader election and quorum is lost,
we will recover by abandoning leader activation and running another election.`}),`
`,e.jsx(s.p,{children:`After leader election a single server will be designated as a leader and start
waiting for followers to connect. The rest of the servers will try to connect to
the leader. The leader will sync up with the followers by sending any proposals they
are missing, or if a follower is missing too many proposals, it will send a full
snapshot of the state to the follower.`}),`
`,e.jsxs(s.p,{children:["There is a corner case in which a follower that has proposals, ",e.jsx(s.code,{children:"U"}),`, not seen
by a leader arrives. Proposals are seen in order, so the proposals of `,e.jsx(s.code,{children:"U"}),` will have zxids
higher than zxids seen by the leader. The follower must have arrived after the
leader election, otherwise the follower would have been elected leader given that
it has seen a higher zxid. Since committed proposals must be seen by a quorum of
servers, and a quorum of servers that elected the leader did not see `,e.jsx(s.code,{children:"U"}),`, the proposals
of `,e.jsx(s.code,{children:"U"}),` have not been committed, so they can be discarded. When the follower connects
to the leader, the leader will tell the follower to discard `,e.jsx(s.code,{children:"U"}),"."]}),`
`,e.jsxs(s.p,{children:[`A new leader establishes a zxid to start using for new proposals by getting the
epoch, `,e.jsx(s.code,{children:"e"}),`, of the highest zxid it has seen and setting the next zxid to use to be
`,e.jsx(s.code,{children:"(e+1, 0)"}),`. After the leader syncs with a follower, it will propose a NEW_LEADER
proposal. Once the NEW_LEADER proposal has been committed, the leader will activate
and start receiving and issuing proposals.`]}),`
`,e.jsx(s.p,{children:"The basic rules of operation during leader activation are:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsx(s.li,{children:"A follower will ACK the NEW_LEADER proposal after it has synced with the leader."}),`
`,e.jsx(s.li,{children:"A follower will only ACK a NEW_LEADER proposal with a given zxid from a single server."}),`
`,e.jsx(s.li,{children:"A new leader will COMMIT the NEW_LEADER proposal when a quorum of followers has ACKed it."}),`
`,e.jsx(s.li,{children:"A follower will commit any state it received from the leader when the NEW_LEADER proposal is COMMITted."}),`
`,e.jsx(s.li,{children:"A new leader will not accept new proposals until the NEW_LEADER proposal has been COMMITted."}),`
`]}),`
`,e.jsx(s.p,{children:`If leader election terminates erroneously, we don't have a problem since the
NEW_LEADER proposal will not be committed because the leader will not have quorum.
When this happens, the leader and any remaining followers will timeout and go back
to leader election.`}),`
`,e.jsx(s.h3,{id:"active-messaging",children:"Active Messaging"}),`
`,e.jsx(s.p,{children:`Leader Activation does all the heavy lifting. Once the leader is coronated it can
start blasting out proposals. As long as it remains the leader no other leader can
emerge since no other leader will be able to get a quorum of followers. If a new
leader does emerge, it means that the current leader has lost quorum, and the new
leader will clean up any mess left over during its activation.`}),`
`,e.jsx(s.p,{children:"ZooKeeper messaging operates similar to a classic two-phase commit."}),`
`,e.jsx(s.p,{children:e.jsx(s.img,{alt:"Two phase commit",src:n,placeholder:"blur"})}),`
`,e.jsx(s.p,{children:`All communication channels are FIFO, so everything is done in order. Specifically
the following operating constraints are observed:`}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsx(s.li,{children:"The leader sends proposals to all followers using the same order. Moreover, this order follows the order in which requests were received. Because we use FIFO channels this means that followers also receive proposals in order."}),`
`,e.jsxs(s.li,{children:["Followers process messages in the order they are received. This means that messages will be ACKed in order and the leader will receive ACKs from followers in order, due to the FIFO channels. It also means that if message ",e.jsx(s.code,{children:"m"})," has been written to non-volatile storage, all messages that were proposed before ",e.jsx(s.code,{children:"m"})," have been written to non-volatile storage."]}),`
`,e.jsx(s.li,{children:"The leader will issue a COMMIT to all followers as soon as a quorum of followers have ACKed a message. Since messages are ACKed in order, COMMITs will be sent by the leader and received by the followers in order."}),`
`,e.jsx(s.li,{children:"COMMITs are processed in order. Followers deliver a proposal message when that proposal is committed."}),`
`]}),`
`,e.jsx(s.h3,{id:"summary",children:"Summary"}),`
`,e.jsx(s.p,{children:`Why does it work? Specifically, why does a set of proposals believed by a new leader
always contain any proposal that has actually been committed? First, all proposals have
a unique zxid, so unlike other protocols, we never have to worry about two different
values being proposed for the same zxid; followers (a leader is also a follower) see
and record proposals in order; proposals are committed in order; there is only one active
leader at a time since followers only follow a single leader at a time; a new leader has
seen all committed proposals from the previous epoch since it has seen the highest zxid
from a quorum of servers; any uncommitted proposals from a previous epoch seen by a new
leader will be committed by that leader before it becomes active.`}),`
`,e.jsx(s.h3,{id:"comparisons",children:"Comparisons"}),`
`,e.jsxs(s.p,{children:[e.jsx(s.strong,{children:"Isn't this just Multi-Paxos?"}),` No. Multi-Paxos requires some way of assuring that
there is only a single coordinator. We do not count on such assurances. Instead
we use leader activation to recover from leadership changes or old leaders
believing they are still active.`]}),`
`,e.jsxs(s.p,{children:[e.jsx(s.strong,{children:"Isn't this just Paxos? The active messaging phase looks just like phase 2 of Paxos."}),`
Actually, to us active messaging looks just like 2-phase commit without the need to
handle aborts. Active messaging is different from both in the sense that it has
cross-proposal ordering requirements. If we do not maintain strict FIFO ordering of
all packets, it all falls apart. Also, our leader activation phase is different from
both of them. In particular, our use of epochs allows us to skip blocks of uncommitted
proposals and to not worry about duplicate proposals for a given zxid.`]}),`
`,e.jsx(s.h2,{id:"consistency-guarantees",children:"Consistency Guarantees"}),`
`,e.jsxs(s.p,{children:["The ",e.jsx(s.a,{href:"https://jepsen.io/consistency",children:"consistency"}),` guarantees of ZooKeeper lie between
sequential consistency and linearizability.`]}),`
`,e.jsxs(s.p,{children:["Write operations in ZooKeeper are ",e.jsx(s.em,{children:"linearizable"}),". Each ",e.jsx(s.code,{children:"write"}),` will appear to take effect
atomically at some point between when the client issues the request and receives the
corresponding response. This means that the writes performed by all clients can be totally
ordered in a way that respects their real-time ordering. However, merely stating that writes
are linearizable is meaningless unless we also talk about reads.`]}),`
`,e.jsxs(s.p,{children:["Read operations in ZooKeeper are ",e.jsx(s.em,{children:"not linearizable"})," since they can return stale data. A ",e.jsx(s.code,{children:"read"}),`
in ZooKeeper is not a quorum operation — a server will respond immediately to a client
performing a `,e.jsx(s.code,{children:"read"}),`. ZooKeeper prioritizes performance over consistency for reads. However,
reads are `,e.jsx(s.em,{children:"sequentially consistent"}),", because ",e.jsx(s.code,{children:"read"}),` operations appear to take effect in some
sequential order that respects each client's operation order. A common workaround is to issue
a `,e.jsx(s.code,{children:"sync"})," before a ",e.jsx(s.code,{children:"read"}),". This too does ",e.jsx(s.strong,{children:"not"}),` strictly guarantee up-to-date data because
`,e.jsx(s.code,{children:"sync"})," is ",e.jsx(s.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1675",children:"not currently a quorum operation"}),"."]}),`
`,e.jsxs(s.p,{children:[`To illustrate: consider a scenario where two servers simultaneously think they are the leader,
which could occur if the TCP connection timeout is smaller than `,e.jsx(s.code,{children:"syncLimit * tickTime"}),`. Note
that this is `,e.jsx(s.a,{href:"https://www.amazon.com/ZooKeeper-Distributed-Coordination-Flavio-Junqueira/dp/1449361307",children:"unlikely"}),`
to occur in practice, but is worth keeping in mind. Under this scenario, the `,e.jsx(s.code,{children:"sync"}),` may be
served by the "leader" with stale data, allowing the following `,e.jsx(s.code,{children:"read"}),` to be stale as well.
The stronger guarantee of linearizability is provided if an actual quorum operation (e.g., a
`,e.jsx(s.code,{children:"write"}),") is performed before a ",e.jsx(s.code,{children:"read"}),"."]}),`
`,e.jsxs(s.p,{children:[`Overall, the consistency guarantees of ZooKeeper are formally captured by the notion of
`,e.jsx(s.a,{href:"http://webee.technion.ac.il/people/idish/ftp/OSC-IPL17.pdf",children:"ordered sequential consistency"}),`
(`,e.jsx(s.code,{children:"OSC(U)"}),"), which lies between sequential consistency and linearizability."]}),`
`,e.jsx(s.h2,{id:"quorums",children:"Quorums"}),`
`,e.jsx(s.p,{children:`Atomic broadcast and leader election use the notion of quorums to guarantee a consistent
view of the system. By default, ZooKeeper uses majority quorums, which means that every
voting operation requires a majority to vote. One example is acknowledging a leader proposal:
the leader can only commit once it receives an acknowledgement from a quorum of servers.`}),`
`,e.jsx(s.p,{children:`If we extract the properties that we really need from our use of majorities, we have that we only
need to guarantee that groups of processes used to validate an operation by voting pairwise
intersect in at least one server. Using majorities guarantees such a property.
However, there are other ways of constructing quorums. For example, we can assign
weights to the votes of servers, and say that the votes of some servers are more important.
To obtain a quorum, we get enough votes so that the sum of weights of all votes is larger
than half of the total sum of all weights.`}),`
`,e.jsx(s.p,{children:`A different construction that uses weights and is useful in wide-area deployments is a
hierarchical one. With this construction, we split the servers into disjoint groups and
assign weights to processes. To form a quorum, we have to get enough servers from a majority
of groups G, such that for each group g in G, the sum of votes from g is larger than half of
the sum of weights in g. Interestingly, this construction enables smaller quorums. If we have
9 servers split into 3 groups with a weight of 1 each, we are able to form quorums of size 4.
Note that two subsets of processes each composed of a majority of servers from a majority of
groups necessarily have a non-empty intersection. It is reasonable to expect that a majority
of co-locations will have a majority of servers available with high probability.`}),`
`,e.jsx(s.p,{children:`ZooKeeper provides users with the ability to configure servers to use majority quorums,
weighted quorums, or a hierarchy of groups.`}),`
`,e.jsx(s.h2,{id:"logging",children:"Logging"}),`
`,e.jsxs(s.p,{children:["ZooKeeper uses ",e.jsx(s.a,{href:"http://www.slf4j.org/index.html",children:"slf4j"}),` as an abstraction layer for logging.
`,e.jsx(s.a,{href:"https://logback.qos.ch/",children:"Logback"}),` has been chosen as the logging backend since ZooKeeper 3.8.0.
For better embedding support, it is planned in the future to leave the choice of logging
implementation to the end user. Therefore, always use the slf4j API for log statements in code,
but configure Logback for runtime logging behavior.
Note that slf4j has no FATAL level; former FATAL-level messages have been moved to ERROR.
For information on configuring Logback for ZooKeeper, see the
`,e.jsx(s.a,{href:"/admin-ops/administrators-guide/administration#logging",children:"Logging"}),` section of the
`,e.jsx(s.a,{href:"/admin-ops/administrators-guide",children:"ZooKeeper Administrator's Guide"}),"."]}),`
`,e.jsx(s.h3,{id:"developer-guidelines",children:"Developer Guidelines"}),`
`,e.jsxs(s.p,{children:["Follow the ",e.jsx(s.a,{href:"http://www.slf4j.org/manual.html",children:"slf4j manual"}),` when creating log statements in
code. Also read the `,e.jsx(s.a,{href:"http://www.slf4j.org/faq.html#logging_performance",children:"FAQ on logging performance"}),`.
Patch reviewers will look for the following:`]}),`
`,e.jsx(s.h4,{id:"logging-at-the-right-level",children:"Logging at the Right Level"}),`
`,e.jsx(s.p,{children:"There are several log levels in slf4j, in order of decreasing severity:"}),`
`,e.jsxs(s.ol,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"ERROR"})," — error events that might still allow the application to continue running."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"WARN"})," — potentially harmful situations."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"INFO"})," — informational messages that highlight the progress of the application at a coarse-grained level."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"DEBUG"})," — fine-grained informational events most useful for debugging."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"TRACE"})," — finer-grained informational events than DEBUG."]}),`
`]}),`
`,e.jsx(s.p,{children:"ZooKeeper is typically run in production such that INFO and above are output to the log."}),`
`,e.jsx(s.h4,{id:"use-of-standard-slf4j-idioms",children:"Use of Standard slf4j Idioms"}),`
`,e.jsx(s.p,{children:e.jsx(s.strong,{children:"Static message logging:"})}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"LOG."}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"debug"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"process completed successfully!"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]})})})}),`
`,e.jsx(s.p,{children:"For parameterized messages, use formatting anchors:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"LOG."}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"debug"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"got {} messages in {} minutes"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"new"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" Object"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[]{count, time});"})]})})})}),`
`,e.jsxs(s.p,{children:[e.jsx(s.strong,{children:"Naming:"})," Loggers should be named after the class in which they are used."]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"public"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" class"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" Foo"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    private"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" static"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" final"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" Logger LOG "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" LoggerFactory."}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getLogger"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(Foo.class);"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    public"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" Foo"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"() {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        LOG."}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"info"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"constructing Foo"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})}),`
`,e.jsx(s.p,{children:e.jsx(s.strong,{children:"Exception handling:"})}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"try"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // code"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"} "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"catch"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (XYZException "}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"e"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // do this"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    LOG."}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"error"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Something bad happened"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", e);"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // don't do this (generally):"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // LOG.error(e);"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // the above hides the stack trace"})}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // continue, recover, or rethrow as appropriate"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})})]})}function c(o={}){const{wrapper:s}=o.components||{};return s?e.jsx(s,{...o,children:e.jsx(t,{...o})}):t(o)}export{i as _markdown,c as default,l as extractedReferences,r as frontmatter,h as structuredData,d as toc};
