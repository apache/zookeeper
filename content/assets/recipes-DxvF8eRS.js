import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let a=`## A Guide to Creating Higher-level Constructs with ZooKeeper

In this article, you'll find guidelines for using
ZooKeeper to implement higher order functions. All of them are conventions
implemented at the client and do not require special support from
ZooKeeper. Hopefully the community will capture these conventions in client-side libraries
to ease their use and to encourage standardization.

One of the most interesting things about ZooKeeper is that even
though ZooKeeper uses *asynchronous* notifications, you
can use it to build *synchronous* consistency
primitives, such as queues and locks. As you will see, this is possible
because ZooKeeper imposes an overall order on updates, and has mechanisms
to expose this ordering.

Note that the recipes below attempt to employ best practices. In
particular, they avoid polling, timers or anything else that would result
in a "herd effect", causing bursts of traffic and limiting
scalability.

There are many useful functions that can be imagined that aren't
included here - revocable read-write priority locks, as just one example.
And some of the constructs mentioned here - locks, in particular -
illustrate certain points, even though you may find other constructs, such
as event handles or queues, a more practical means of performing the same
function. In general, the examples in this section are designed to
stimulate thought.

### Important Note About Error Handling

When implementing the recipes you must handle recoverable exceptions
(see the [FAQ](https://cwiki.apache.org/confluence/display/ZOOKEEPER/FAQ)). In
particular, several of the recipes employ sequential ephemeral
nodes. When creating a sequential ephemeral node there is an error case in
which the create() succeeds on the server but the server crashes before
returning the name of the node to the client. When the client reconnects its
session is still valid and, thus, the node is not removed. The implication is
that it is difficult for the client to know if its node was created or not. The
recipes below include measures to handle this.

### Out of the Box Applications: Name Service, Configuration, Group Membership

Name service and configuration are two of the primary applications
of ZooKeeper. These two functions are provided directly by the ZooKeeper
API.

Another function directly provided by ZooKeeper is *group
membership*. The group is represented by a node. Members of the
group create ephemeral nodes under the group node. Nodes of the members
that fail abnormally will be removed automatically when ZooKeeper detects
the failure.

### Barriers

Distributed systems use *barriers*
to block processing of a set of nodes until a condition is met
at which time all the nodes are allowed to proceed. Barriers are
implemented in ZooKeeper by designating a barrier node. The
barrier is in place if the barrier node exists. Here's the
pseudo code:

1. Client calls the ZooKeeper API's **exists()** function on the barrier node, with
   *watch* set to true.
2. If **exists()** returns false, the
   barrier is gone and the client proceeds
3. Else, if **exists()** returns true,
   the clients wait for a watch event from ZooKeeper for the barrier
   node.
4. When the watch event is triggered, the client reissues the
   **exists( )** call, again waiting until
   the barrier node is removed.

#### Double Barriers

Double barriers enable clients to synchronize the beginning and
the end of a computation. When enough processes have joined the barrier,
processes start their computation and leave the barrier once they have
finished. This recipe shows how to use a ZooKeeper node as a
barrier.

The pseudo code in this recipe represents the barrier node as
*b*. Every client process *p*
registers with the barrier node on entry and unregisters when it is
ready to leave. A node registers with the barrier node via the **Enter** procedure below, it waits until
*x* client process register before proceeding with
the computation. (The *x* here is up to you to
determine for your system.)

| **Enter**                                               | **Leave**                                                                       |
| ------------------------------------------------------- | ------------------------------------------------------------------------------- |
| 1. Create a name **n* = \\_b*+“/”+\\_p\\*\\*                | 1. **L = getChildren(b, false)**                                                |
| 2. Set watch: **exists(*b* + ‘‘/ready’’, true)**        | 2. if no children, exit                                                         |
| 3. Create child: **create(*n*, EPHEMERAL)**             | 3. if *p* is only process node in L, delete(n) and exit                         |
| 4. **L = getChildren(b, false)**                        | 4. if *p* is the lowest process node in L, wait on highest process node in L    |
| 5. if fewer children in L than*x*, wait for watch event | 5. else \\*\\*delete(*n*)\\*\\*if still exists and wait on lowest process node in L |
| 6. else **create(b + ‘‘/ready’’, REGULAR)**             | 6. goto 1                                                                       |

On entering, all processes watch on a ready node and
create an ephemeral node as a child of the barrier node. Each process
but the last enters the barrier and waits for the ready node to appear
at line 5. The process that creates the xth node, the last process, will
see x nodes in the list of children and create the ready node, waking up
the other processes. Note that waiting processes wake up only when it is
time to exit, so waiting is efficient.

On exit, you can't use a flag such as *ready*
because you are watching for process nodes to go away. By using
ephemeral nodes, processes that fail after the barrier has been entered
do not prevent correct processes from finishing. When processes are
ready to leave, they need to delete their process nodes and wait for all
other processes to do the same.

Processes exit when there are no process nodes left as children of
*b*. However, as an efficiency, you can use the
lowest process node as the ready flag. All other processes that are
ready to exit watch for the lowest existing process node to go away, and
the owner of the lowest process watches for any other process node
(picking the highest for simplicity) to go away. This means that only a
single process wakes up on each node deletion except for the last node,
which wakes up everyone when it is removed.

### Queues

Distributed queues are a common data structure. To implement a
distributed queue in ZooKeeper, first designate a znode to hold the queue,
the queue node. The distributed clients put something into the queue by
calling create() with a pathname ending in "queue-", with the
*sequence* and *ephemeral* flags in
the create() call set to true. Because the *sequence*
flag is set, the new pathname will have the form
*path-to-queue-node*/queue-X, where X is a monotonic increasing number. A
client that wants to be removed from the queue calls ZooKeeper's **getChildren( )** function, with
*watch* set to true on the queue node, and begins
processing nodes with the lowest number. The client does not need to issue
another **getChildren( )** until it exhausts
the list obtained from the first **getChildren(
)** call. If there are no children in the queue node, the
reader waits for a watch notification to check the queue again.

<Callout type="info">
  There now exists a Queue implementation in ZooKeeper recipes directory. This
  is distributed with the release -- zookeeper-recipes/zookeeper-recipes-queue
  directory of the release artifact.
</Callout>

#### Priority Queues

To implement a priority queue, you need only make two simple
changes to the generic [queue
recipe](#queues) . First, to add to a queue, the pathname ends with
"queue-YY" where YY is the priority of the element with lower numbers
representing higher priority (just like UNIX). Second, when removing
from the queue, a client uses an up-to-date children list meaning that
the client will invalidate previously obtained children lists if a watch
notification triggers for the queue node.

### Locks

Fully distributed locks that are globally synchronous, meaning at
any snapshot in time no two clients think they hold the same lock. These
can be implemented using ZooKeeper. As with priority queues, first define
a lock node.

<Callout type="info">
  There now exists a Lock implementation in ZooKeeper recipes directory. This is
  distributed with the release -- zookeeper-recipes/zookeeper-recipes-lock
  directory of the release artifact.
</Callout>

Clients wishing to obtain a lock do the following:

1. Call **create( )** with a pathname
   of "*locknode*/guid-lock-" and the *sequence* and
   *ephemeral* flags set. The *guid*
   is needed in case the create() result is missed. See the note below.
2. Call **getChildren( )** on the lock
   node *without* setting the watch flag (this is
   important to avoid the herd effect).
3. If the pathname created in step **1** has the lowest sequence number suffix, the
   client has the lock and the client exits the protocol.
4. The client calls **exists( )** with
   the watch flag set on the path in the lock directory with the next
   lowest sequence number.
5. if **exists( )** returns null, go
   to step **2**. Otherwise, wait for a
   notification for the pathname from the previous step before going to
   step **2**.

The unlock protocol is very simple: clients wishing to release a
lock simply delete the node they created in step 1.

Here are a few things to notice:

* The removal of a node will only cause one client to wake up
  since each node is watched by exactly one client. In this way, you
  avoid the herd effect.
* There is no polling or timeouts.
* Because of the way you implement locking, it is easy to see the
  amount of lock contention, break locks, debug locking problems,
  etc.

#### Recoverable Errors and the GUID

* If a recoverable error occurs calling **create()** the
  client should call **getChildren()** and check for a node
  containing the *guid* used in the path name.
  This handles the case (noted [above](#important-note-about-error-handling)) of
  the create() succeeding on the server but the server crashing before returning the name
  of the new node.

#### Shared Locks

You can implement shared locks by with a few changes to the lock
protocol:

| **Obtaining a read lock:**                                                                                                                                                                      | **Obtaining a write lock:**                                                                                                                                                           |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Call **create( )** to create a node with pathname "*guid-/read-*". This is the lock node use later in the protocol. Make sure to set both the *sequence* and *ephemeral* flags.              | 1. Call **create( )** to create a node with pathname "*guid-/write-*". This is the lock node spoken of later in the protocol. Make sure to set both *sequence* and *ephemeral* flags. |
| 2. Call **getChildren( )** on the lock node *without* setting the *watch* flag - this is important, as it avoids the herd effect.                                                               | 2. Call **getChildren( )** on the lock node *without* setting the *watch* flag - this is important, as it avoids the herd effect.                                                     |
| 3. If there are no children with a pathname starting with "*write-*" and having a lower sequence number than the node created in step **1**, the client has the lock and can exit the protocol. | 3. If there are no children with a lower sequence number than the node created in step **1**, the client has the lock and the client exits the protocol.                              |
| 4. Otherwise, call **exists( )**, with *watch* flag, set on the node in lock directory with pathname starting with "*write-*" having the next lowest sequence number.                           | 4. Call **exists( ),** with *watch* flag set, on the node with the pathname that has the next lowest sequence number.                                                                 |
| 5. If **exists( )** returns *false*, goto step **2**.                                                                                                                                           | 5. If **exists( )** returns *false*, goto step **2**. Otherwise, wait for a notification for the pathname from the previous step before going to step **2**.                          |
| 6. Otherwise, wait for a notification for the pathname from the previous step before going to step **2**                                                                                        |                                                                                                                                                                                       |

Notes:

* It might appear that this recipe creates a herd effect:
  when there is a large group of clients waiting for a read
  lock, and all getting notified more or less simultaneously
  when the "*write-*" node with the lowest
  sequence number is deleted. In fact. that's valid behavior:
  as all those waiting reader clients should be released since
  they have the lock. The herd effect refers to releasing a
  "herd" when in fact only a single or a small number of
  machines can proceed.
* See the [note for Locks](#recoverable-errors-and-the-guid) on how to use the guid in the node.

#### Revocable Shared Locks

With minor modifications to the Shared Lock protocol, you make
shared locks revocable by modifying the shared lock protocol:

In step **1**, of both obtain reader
and writer lock protocols, call **getData(
)** with *watch* set, immediately after the
call to **create( )**. If the client
subsequently receives notification for the node it created in step
**1**, it does another **getData( )** on that node, with
*watch* set and looks for the string "unlock", which
signals to the client that it must release the lock. This is because,
according to this shared lock protocol, you can request the client with
the lock give up the lock by calling **setData()** on the lock node, writing "unlock" to that node.

Note that this protocol requires the lock holder to consent to
releasing the lock. Such consent is important, especially if the lock
holder needs to do some processing before releasing the lock. Of course
you can always implement *Revocable Shared Locks with Freaking
Laser Beams* by stipulating in your protocol that the revoker
is allowed to delete the lock node if after some length of time the lock
isn't deleted by the lock holder.

### Two-phased Commit

A two-phase commit protocol is an algorithm that lets all clients in
a distributed system agree either to commit a transaction or abort.

In ZooKeeper, you can implement a two-phased commit by having a
coordinator create a transaction node, say "/app/Tx", and one child node
per participating site, say "/app/Tx/s\\_i". When coordinator creates the
child node, it leaves the content undefined. Once each site involved in
the transaction receives the transaction from the coordinator, the site
reads each child node and sets a watch. Each site then processes the query
and votes "commit" or "abort" by writing to its respective node. Once the
write completes, the other sites are notified, and as soon as all sites
have all votes, they can decide either "abort" or "commit". Note that a
node can decide "abort" earlier if some site votes for "abort".

An interesting aspect of this implementation is that the only role
of the coordinator is to decide upon the group of sites, to create the
ZooKeeper nodes, and to propagate the transaction to the corresponding
sites. In fact, even propagating the transaction can be done through
ZooKeeper by writing it in the transaction node.

There are two important drawbacks of the approach described above.
One is the message complexity, which is O(n²). The second is the
impossibility of detecting failures of sites through ephemeral nodes. To
detect the failure of a site using ephemeral nodes, it is necessary that
the site create the node.

To solve the first problem, you can have only the coordinator
notified of changes to the transaction nodes, and then notify the sites
once coordinator reaches a decision. Note that this approach is scalable,
but it is slower too, as it requires all communication to go through the
coordinator.

To address the second problem, you can have the coordinator
propagate the transaction to the sites, and have each site creating its
own ephemeral node.

### Leader Election

A simple way of doing leader election with ZooKeeper is to use the
**SEQUENCE|EPHEMERAL** flags when creating
znodes that represent "proposals" of clients. The idea is to have a znode,
say "/election", such that each znode creates a child znode "/election/guid-n\\_"
with both flags SEQUENCE|EPHEMERAL. With the sequence flag, ZooKeeper
automatically appends a sequence number that is greater than anyone
previously appended to a child of "/election". The process that created
the znode with the smallest appended sequence number is the leader.

That's not all, though. It is important to watch for failures of the
leader, so that a new client arises as the new leader in the case the
current leader fails. A trivial solution is to have all application
processes watching upon the current smallest znode, and checking if they
are the new leader when the smallest znode goes away (note that the
smallest znode will go away if the leader fails because the node is
ephemeral). But this causes a herd effect: upon a failure of the current
leader, all other processes receive a notification, and execute
getChildren on "/election" to obtain the current list of children of
"/election". If the number of clients is large, it causes a spike on the
number of operations that ZooKeeper servers have to process. To avoid the
herd effect, it is sufficient to watch for the next znode down on the
sequence of znodes. If a client receives a notification that the znode it
is watching is gone, then it becomes the new leader in the case that there
is no smaller znode. Note that this avoids the herd effect by not having
all clients watching the same znode.

Here's the pseudo code:

Let ELECTION be a path of choice of the application. To volunteer to
be a leader:

1. Create znode z with path "ELECTION/guid-n\\_" with both SEQUENCE and
   EPHEMERAL flags;
2. Let C be the children of "ELECTION", and i is the sequence
   number of z;
3. Watch for changes on "ELECTION/guid-n\\_j", where j is the largest
   sequence number such that j \\< i and n\\_j is a znode in C;

Upon receiving a notification of znode deletion:

1. Let C be the new set of children of ELECTION;
2. If z is the smallest node in C, then execute leader
   procedure;
3. Otherwise, watch for changes on "ELECTION/guid-n\\_j", where j is the
   largest sequence number such that j \\< i and n\\_j is a znode in C;

Notes:

* Note that the znode having no preceding znode on the list of
  children do not imply that the creator of this znode is aware that it is
  the current leader. Applications may consider creating a separate znode
  to acknowledge that the leader has executed the leader procedure.
* See the [note for Locks](#recoverable-errors-and-the-guid) on how to use the guid in the node.
`,h={title:"Recipes and Solutions",description:"Guidelines for using ZooKeeper to implement higher-order constructs such as barriers, queues, locks, and leader election."},c=[{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/FAQ"},{href:"#queues"},{href:"#important-note-about-error-handling"},{href:"#recoverable-errors-and-the-guid"},{href:"#recoverable-errors-and-the-guid"}],l={contents:[{heading:"a-guide-to-creating-higher-level-constructs-with-zookeeper",content:`In this article, you'll find guidelines for using
ZooKeeper to implement higher order functions. All of them are conventions
implemented at the client and do not require special support from
ZooKeeper. Hopefully the community will capture these conventions in client-side libraries
to ease their use and to encourage standardization.`},{heading:"a-guide-to-creating-higher-level-constructs-with-zookeeper",content:`One of the most interesting things about ZooKeeper is that even
though ZooKeeper uses asynchronous notifications, you
can use it to build synchronous consistency
primitives, such as queues and locks. As you will see, this is possible
because ZooKeeper imposes an overall order on updates, and has mechanisms
to expose this ordering.`},{heading:"a-guide-to-creating-higher-level-constructs-with-zookeeper",content:`Note that the recipes below attempt to employ best practices. In
particular, they avoid polling, timers or anything else that would result
in a "herd effect", causing bursts of traffic and limiting
scalability.`},{heading:"a-guide-to-creating-higher-level-constructs-with-zookeeper",content:`There are many useful functions that can be imagined that aren't
included here - revocable read-write priority locks, as just one example.
And some of the constructs mentioned here - locks, in particular -
illustrate certain points, even though you may find other constructs, such
as event handles or queues, a more practical means of performing the same
function. In general, the examples in this section are designed to
stimulate thought.`},{heading:"important-note-about-error-handling",content:`When implementing the recipes you must handle recoverable exceptions
(see the FAQ). In
particular, several of the recipes employ sequential ephemeral
nodes. When creating a sequential ephemeral node there is an error case in
which the create() succeeds on the server but the server crashes before
returning the name of the node to the client. When the client reconnects its
session is still valid and, thus, the node is not removed. The implication is
that it is difficult for the client to know if its node was created or not. The
recipes below include measures to handle this.`},{heading:"out-of-the-box-applications-name-service-configuration-group-membership",content:`Name service and configuration are two of the primary applications
of ZooKeeper. These two functions are provided directly by the ZooKeeper
API.`},{heading:"out-of-the-box-applications-name-service-configuration-group-membership",content:`Another function directly provided by ZooKeeper is group
membership. The group is represented by a node. Members of the
group create ephemeral nodes under the group node. Nodes of the members
that fail abnormally will be removed automatically when ZooKeeper detects
the failure.`},{heading:"barriers",content:`Distributed systems use barriers
to block processing of a set of nodes until a condition is met
at which time all the nodes are allowed to proceed. Barriers are
implemented in ZooKeeper by designating a barrier node. The
barrier is in place if the barrier node exists. Here's the
pseudo code:`},{heading:"barriers",content:`Client calls the ZooKeeper API's exists() function on the barrier node, with
watch set to true.`},{heading:"barriers",content:`If exists() returns false, the
barrier is gone and the client proceeds`},{heading:"barriers",content:`Else, if exists() returns true,
the clients wait for a watch event from ZooKeeper for the barrier
node.`},{heading:"barriers",content:`When the watch event is triggered, the client reissues the
exists( ) call, again waiting until
the barrier node is removed.`},{heading:"double-barriers",content:`Double barriers enable clients to synchronize the beginning and
the end of a computation. When enough processes have joined the barrier,
processes start their computation and leave the barrier once they have
finished. This recipe shows how to use a ZooKeeper node as a
barrier.`},{heading:"double-barriers",content:`The pseudo code in this recipe represents the barrier node as
b. Every client process p
registers with the barrier node on entry and unregisters when it is
ready to leave. A node registers with the barrier node via the Enter procedure below, it waits until
x client process register before proceeding with
the computation. (The x here is up to you to
determine for your system.)`},{heading:"double-barriers",content:"Enter"},{heading:"double-barriers",content:"Leave"},{heading:"double-barriers",content:"1. Create a name n = _b+“/”+_p**"},{heading:"double-barriers",content:"1. L = getChildren(b, false)"},{heading:"double-barriers",content:"2. Set watch: exists(b + ‘‘/ready’’, true)"},{heading:"double-barriers",content:"2. if no children, exit"},{heading:"double-barriers",content:"3. Create child: create(n, EPHEMERAL)"},{heading:"double-barriers",content:"3. if p is only process node in L, delete(n) and exit"},{heading:"double-barriers",content:"4. L = getChildren(b, false)"},{heading:"double-barriers",content:"4. if p is the lowest process node in L, wait on highest process node in L"},{heading:"double-barriers",content:"5. if fewer children in L thanx, wait for watch event"},{heading:"double-barriers",content:"5. else **delete(n)**if still exists and wait on lowest process node in L"},{heading:"double-barriers",content:"6. else create(b + ‘‘/ready’’, REGULAR)"},{heading:"double-barriers",content:"6. goto 1"},{heading:"double-barriers",content:`On entering, all processes watch on a ready node and
create an ephemeral node as a child of the barrier node. Each process
but the last enters the barrier and waits for the ready node to appear
at line 5. The process that creates the xth node, the last process, will
see x nodes in the list of children and create the ready node, waking up
the other processes. Note that waiting processes wake up only when it is
time to exit, so waiting is efficient.`},{heading:"double-barriers",content:`On exit, you can't use a flag such as ready
because you are watching for process nodes to go away. By using
ephemeral nodes, processes that fail after the barrier has been entered
do not prevent correct processes from finishing. When processes are
ready to leave, they need to delete their process nodes and wait for all
other processes to do the same.`},{heading:"double-barriers",content:`Processes exit when there are no process nodes left as children of
b. However, as an efficiency, you can use the
lowest process node as the ready flag. All other processes that are
ready to exit watch for the lowest existing process node to go away, and
the owner of the lowest process watches for any other process node
(picking the highest for simplicity) to go away. This means that only a
single process wakes up on each node deletion except for the last node,
which wakes up everyone when it is removed.`},{heading:"queues",content:`Distributed queues are a common data structure. To implement a
distributed queue in ZooKeeper, first designate a znode to hold the queue,
the queue node. The distributed clients put something into the queue by
calling create() with a pathname ending in "queue-", with the
sequence and ephemeral flags in
the create() call set to true. Because the sequence
flag is set, the new pathname will have the form
path-to-queue-node/queue-X, where X is a monotonic increasing number. A
client that wants to be removed from the queue calls ZooKeeper's getChildren( ) function, with
watch set to true on the queue node, and begins
processing nodes with the lowest number. The client does not need to issue
another getChildren( ) until it exhausts
the list obtained from the first getChildren(
) call. If there are no children in the queue node, the
reader waits for a watch notification to check the queue again.`},{heading:"queues",content:"type: info"},{heading:"queues",content:`There now exists a Queue implementation in ZooKeeper recipes directory. This
is distributed with the release -- zookeeper-recipes/zookeeper-recipes-queue
directory of the release artifact.`},{heading:"priority-queues",content:`To implement a priority queue, you need only make two simple
changes to the generic queue
recipe . First, to add to a queue, the pathname ends with
"queue-YY" where YY is the priority of the element with lower numbers
representing higher priority (just like UNIX). Second, when removing
from the queue, a client uses an up-to-date children list meaning that
the client will invalidate previously obtained children lists if a watch
notification triggers for the queue node.`},{heading:"locks",content:`Fully distributed locks that are globally synchronous, meaning at
any snapshot in time no two clients think they hold the same lock. These
can be implemented using ZooKeeper. As with priority queues, first define
a lock node.`},{heading:"locks",content:"type: info"},{heading:"locks",content:`There now exists a Lock implementation in ZooKeeper recipes directory. This is
distributed with the release -- zookeeper-recipes/zookeeper-recipes-lock
directory of the release artifact.`},{heading:"locks",content:"Clients wishing to obtain a lock do the following:"},{heading:"locks",content:`Call create( ) with a pathname
of "locknode/guid-lock-" and the sequence and
ephemeral flags set. The guid
is needed in case the create() result is missed. See the note below.`},{heading:"locks",content:`Call getChildren( ) on the lock
node without setting the watch flag (this is
important to avoid the herd effect).`},{heading:"locks",content:`If the pathname created in step 1 has the lowest sequence number suffix, the
client has the lock and the client exits the protocol.`},{heading:"locks",content:`The client calls exists( ) with
the watch flag set on the path in the lock directory with the next
lowest sequence number.`},{heading:"locks",content:`if exists( ) returns null, go
to step 2. Otherwise, wait for a
notification for the pathname from the previous step before going to
step 2.`},{heading:"locks",content:`The unlock protocol is very simple: clients wishing to release a
lock simply delete the node they created in step 1.`},{heading:"locks",content:"Here are a few things to notice:"},{heading:"locks",content:`The removal of a node will only cause one client to wake up
since each node is watched by exactly one client. In this way, you
avoid the herd effect.`},{heading:"locks",content:"There is no polling or timeouts."},{heading:"locks",content:`Because of the way you implement locking, it is easy to see the
amount of lock contention, break locks, debug locking problems,
etc.`},{heading:"recoverable-errors-and-the-guid",content:`If a recoverable error occurs calling create() the
client should call getChildren() and check for a node
containing the guid used in the path name.
This handles the case (noted above) of
the create() succeeding on the server but the server crashing before returning the name
of the new node.`},{heading:"shared-locks",content:`You can implement shared locks by with a few changes to the lock
protocol:`},{heading:"shared-locks",content:"Obtaining a read lock:"},{heading:"shared-locks",content:"Obtaining a write lock:"},{heading:"shared-locks",content:'1. Call create( ) to create a node with pathname "guid-/read-". This is the lock node use later in the protocol. Make sure to set both the sequence and ephemeral flags.'},{heading:"shared-locks",content:'1. Call create( ) to create a node with pathname "guid-/write-". This is the lock node spoken of later in the protocol. Make sure to set both sequence and ephemeral flags.'},{heading:"shared-locks",content:"2. Call getChildren( ) on the lock node without setting the watch flag - this is important, as it avoids the herd effect."},{heading:"shared-locks",content:"2. Call getChildren( ) on the lock node without setting the watch flag - this is important, as it avoids the herd effect."},{heading:"shared-locks",content:'3. If there are no children with a pathname starting with "write-" and having a lower sequence number than the node created in step 1, the client has the lock and can exit the protocol.'},{heading:"shared-locks",content:"3. If there are no children with a lower sequence number than the node created in step 1, the client has the lock and the client exits the protocol."},{heading:"shared-locks",content:'4. Otherwise, call exists( ), with watch flag, set on the node in lock directory with pathname starting with "write-" having the next lowest sequence number.'},{heading:"shared-locks",content:"4. Call exists( ), with watch flag set, on the node with the pathname that has the next lowest sequence number."},{heading:"shared-locks",content:"5. If exists( ) returns false, goto step 2."},{heading:"shared-locks",content:"5. If exists( ) returns false, goto step 2. Otherwise, wait for a notification for the pathname from the previous step before going to step 2."},{heading:"shared-locks",content:"6. Otherwise, wait for a notification for the pathname from the previous step before going to step 2"},{heading:"shared-locks",content:"Notes:"},{heading:"shared-locks",content:`It might appear that this recipe creates a herd effect:
when there is a large group of clients waiting for a read
lock, and all getting notified more or less simultaneously
when the "write-" node with the lowest
sequence number is deleted. In fact. that's valid behavior:
as all those waiting reader clients should be released since
they have the lock. The herd effect refers to releasing a
"herd" when in fact only a single or a small number of
machines can proceed.`},{heading:"shared-locks",content:"See the note for Locks on how to use the guid in the node."},{heading:"revocable-shared-locks",content:`With minor modifications to the Shared Lock protocol, you make
shared locks revocable by modifying the shared lock protocol:`},{heading:"revocable-shared-locks",content:`In step 1, of both obtain reader
and writer lock protocols, call getData(
) with watch set, immediately after the
call to create( ). If the client
subsequently receives notification for the node it created in step
1, it does another getData( ) on that node, with
watch set and looks for the string "unlock", which
signals to the client that it must release the lock. This is because,
according to this shared lock protocol, you can request the client with
the lock give up the lock by calling setData() on the lock node, writing "unlock" to that node.`},{heading:"revocable-shared-locks",content:`Note that this protocol requires the lock holder to consent to
releasing the lock. Such consent is important, especially if the lock
holder needs to do some processing before releasing the lock. Of course
you can always implement Revocable Shared Locks with Freaking
Laser Beams by stipulating in your protocol that the revoker
is allowed to delete the lock node if after some length of time the lock
isn't deleted by the lock holder.`},{heading:"two-phased-commit",content:`A two-phase commit protocol is an algorithm that lets all clients in
a distributed system agree either to commit a transaction or abort.`},{heading:"two-phased-commit",content:`In ZooKeeper, you can implement a two-phased commit by having a
coordinator create a transaction node, say "/app/Tx", and one child node
per participating site, say "/app/Tx/s_i". When coordinator creates the
child node, it leaves the content undefined. Once each site involved in
the transaction receives the transaction from the coordinator, the site
reads each child node and sets a watch. Each site then processes the query
and votes "commit" or "abort" by writing to its respective node. Once the
write completes, the other sites are notified, and as soon as all sites
have all votes, they can decide either "abort" or "commit". Note that a
node can decide "abort" earlier if some site votes for "abort".`},{heading:"two-phased-commit",content:`An interesting aspect of this implementation is that the only role
of the coordinator is to decide upon the group of sites, to create the
ZooKeeper nodes, and to propagate the transaction to the corresponding
sites. In fact, even propagating the transaction can be done through
ZooKeeper by writing it in the transaction node.`},{heading:"two-phased-commit",content:`There are two important drawbacks of the approach described above.
One is the message complexity, which is O(n²). The second is the
impossibility of detecting failures of sites through ephemeral nodes. To
detect the failure of a site using ephemeral nodes, it is necessary that
the site create the node.`},{heading:"two-phased-commit",content:`To solve the first problem, you can have only the coordinator
notified of changes to the transaction nodes, and then notify the sites
once coordinator reaches a decision. Note that this approach is scalable,
but it is slower too, as it requires all communication to go through the
coordinator.`},{heading:"two-phased-commit",content:`To address the second problem, you can have the coordinator
propagate the transaction to the sites, and have each site creating its
own ephemeral node.`},{heading:"leader-election",content:`A simple way of doing leader election with ZooKeeper is to use the
SEQUENCE|EPHEMERAL flags when creating
znodes that represent "proposals" of clients. The idea is to have a znode,
say "/election", such that each znode creates a child znode "/election/guid-n_"
with both flags SEQUENCE|EPHEMERAL. With the sequence flag, ZooKeeper
automatically appends a sequence number that is greater than anyone
previously appended to a child of "/election". The process that created
the znode with the smallest appended sequence number is the leader.`},{heading:"leader-election",content:`That's not all, though. It is important to watch for failures of the
leader, so that a new client arises as the new leader in the case the
current leader fails. A trivial solution is to have all application
processes watching upon the current smallest znode, and checking if they
are the new leader when the smallest znode goes away (note that the
smallest znode will go away if the leader fails because the node is
ephemeral). But this causes a herd effect: upon a failure of the current
leader, all other processes receive a notification, and execute
getChildren on "/election" to obtain the current list of children of
"/election". If the number of clients is large, it causes a spike on the
number of operations that ZooKeeper servers have to process. To avoid the
herd effect, it is sufficient to watch for the next znode down on the
sequence of znodes. If a client receives a notification that the znode it
is watching is gone, then it becomes the new leader in the case that there
is no smaller znode. Note that this avoids the herd effect by not having
all clients watching the same znode.`},{heading:"leader-election",content:"Here's the pseudo code:"},{heading:"leader-election",content:`Let ELECTION be a path of choice of the application. To volunteer to
be a leader:`},{heading:"leader-election",content:`Create znode z with path "ELECTION/guid-n_" with both SEQUENCE and
EPHEMERAL flags;`},{heading:"leader-election",content:`Let C be the children of "ELECTION", and i is the sequence
number of z;`},{heading:"leader-election",content:`Watch for changes on "ELECTION/guid-n_j", where j is the largest
sequence number such that j < i and n_j is a znode in C;`},{heading:"leader-election",content:"Upon receiving a notification of znode deletion:"},{heading:"leader-election",content:"Let C be the new set of children of ELECTION;"},{heading:"leader-election",content:`If z is the smallest node in C, then execute leader
procedure;`},{heading:"leader-election",content:`Otherwise, watch for changes on "ELECTION/guid-n_j", where j is the
largest sequence number such that j < i and n_j is a znode in C;`},{heading:"leader-election",content:"Notes:"},{heading:"leader-election",content:`Note that the znode having no preceding znode on the list of
children do not imply that the creator of this znode is aware that it is
the current leader. Applications may consider creating a separate znode
to acknowledge that the leader has executed the leader procedure.`},{heading:"leader-election",content:"See the note for Locks on how to use the guid in the node."}],headings:[{id:"a-guide-to-creating-higher-level-constructs-with-zookeeper",content:"A Guide to Creating Higher-level Constructs with ZooKeeper"},{id:"important-note-about-error-handling",content:"Important Note About Error Handling"},{id:"out-of-the-box-applications-name-service-configuration-group-membership",content:"Out of the Box Applications: Name Service, Configuration, Group Membership"},{id:"barriers",content:"Barriers"},{id:"double-barriers",content:"Double Barriers"},{id:"queues",content:"Queues"},{id:"priority-queues",content:"Priority Queues"},{id:"locks",content:"Locks"},{id:"recoverable-errors-and-the-guid",content:"Recoverable Errors and the GUID"},{id:"shared-locks",content:"Shared Locks"},{id:"revocable-shared-locks",content:"Revocable Shared Locks"},{id:"two-phased-commit",content:"Two-phased Commit"},{id:"leader-election",content:"Leader Election"}]};const d=[{depth:2,url:"#a-guide-to-creating-higher-level-constructs-with-zookeeper",title:e.jsx(e.Fragment,{children:"A Guide to Creating Higher-level Constructs with ZooKeeper"})},{depth:3,url:"#important-note-about-error-handling",title:e.jsx(e.Fragment,{children:"Important Note About Error Handling"})},{depth:3,url:"#out-of-the-box-applications-name-service-configuration-group-membership",title:e.jsx(e.Fragment,{children:"Out of the Box Applications: Name Service, Configuration, Group Membership"})},{depth:3,url:"#barriers",title:e.jsx(e.Fragment,{children:"Barriers"})},{depth:4,url:"#double-barriers",title:e.jsx(e.Fragment,{children:"Double Barriers"})},{depth:3,url:"#queues",title:e.jsx(e.Fragment,{children:"Queues"})},{depth:4,url:"#priority-queues",title:e.jsx(e.Fragment,{children:"Priority Queues"})},{depth:3,url:"#locks",title:e.jsx(e.Fragment,{children:"Locks"})},{depth:4,url:"#recoverable-errors-and-the-guid",title:e.jsx(e.Fragment,{children:"Recoverable Errors and the GUID"})},{depth:4,url:"#shared-locks",title:e.jsx(e.Fragment,{children:"Shared Locks"})},{depth:4,url:"#revocable-shared-locks",title:e.jsx(e.Fragment,{children:"Revocable Shared Locks"})},{depth:3,url:"#two-phased-commit",title:e.jsx(e.Fragment,{children:"Two-phased Commit"})},{depth:3,url:"#leader-election",title:e.jsx(e.Fragment,{children:"Leader Election"})}];function i(n){const t={a:"a",em:"em",h2:"h2",h3:"h3",h4:"h4",li:"li",ol:"ol",p:"p",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...n.components},{Callout:o}=t;return o||r("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(t.h2,{id:"a-guide-to-creating-higher-level-constructs-with-zookeeper",children:"A Guide to Creating Higher-level Constructs with ZooKeeper"}),`
`,e.jsx(t.p,{children:`In this article, you'll find guidelines for using
ZooKeeper to implement higher order functions. All of them are conventions
implemented at the client and do not require special support from
ZooKeeper. Hopefully the community will capture these conventions in client-side libraries
to ease their use and to encourage standardization.`}),`
`,e.jsxs(t.p,{children:[`One of the most interesting things about ZooKeeper is that even
though ZooKeeper uses `,e.jsx(t.em,{children:"asynchronous"}),` notifications, you
can use it to build `,e.jsx(t.em,{children:"synchronous"}),` consistency
primitives, such as queues and locks. As you will see, this is possible
because ZooKeeper imposes an overall order on updates, and has mechanisms
to expose this ordering.`]}),`
`,e.jsx(t.p,{children:`Note that the recipes below attempt to employ best practices. In
particular, they avoid polling, timers or anything else that would result
in a "herd effect", causing bursts of traffic and limiting
scalability.`}),`
`,e.jsx(t.p,{children:`There are many useful functions that can be imagined that aren't
included here - revocable read-write priority locks, as just one example.
And some of the constructs mentioned here - locks, in particular -
illustrate certain points, even though you may find other constructs, such
as event handles or queues, a more practical means of performing the same
function. In general, the examples in this section are designed to
stimulate thought.`}),`
`,e.jsx(t.h3,{id:"important-note-about-error-handling",children:"Important Note About Error Handling"}),`
`,e.jsxs(t.p,{children:[`When implementing the recipes you must handle recoverable exceptions
(see the `,e.jsx(t.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/FAQ",children:"FAQ"}),`). In
particular, several of the recipes employ sequential ephemeral
nodes. When creating a sequential ephemeral node there is an error case in
which the create() succeeds on the server but the server crashes before
returning the name of the node to the client. When the client reconnects its
session is still valid and, thus, the node is not removed. The implication is
that it is difficult for the client to know if its node was created or not. The
recipes below include measures to handle this.`]}),`
`,e.jsx(t.h3,{id:"out-of-the-box-applications-name-service-configuration-group-membership",children:"Out of the Box Applications: Name Service, Configuration, Group Membership"}),`
`,e.jsx(t.p,{children:`Name service and configuration are two of the primary applications
of ZooKeeper. These two functions are provided directly by the ZooKeeper
API.`}),`
`,e.jsxs(t.p,{children:["Another function directly provided by ZooKeeper is ",e.jsx(t.em,{children:`group
membership`}),`. The group is represented by a node. Members of the
group create ephemeral nodes under the group node. Nodes of the members
that fail abnormally will be removed automatically when ZooKeeper detects
the failure.`]}),`
`,e.jsx(t.h3,{id:"barriers",children:"Barriers"}),`
`,e.jsxs(t.p,{children:["Distributed systems use ",e.jsx(t.em,{children:"barriers"}),`
to block processing of a set of nodes until a condition is met
at which time all the nodes are allowed to proceed. Barriers are
implemented in ZooKeeper by designating a barrier node. The
barrier is in place if the barrier node exists. Here's the
pseudo code:`]}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsxs(t.li,{children:["Client calls the ZooKeeper API's ",e.jsx(t.strong,{children:"exists()"}),` function on the barrier node, with
`,e.jsx(t.em,{children:"watch"})," set to true."]}),`
`,e.jsxs(t.li,{children:["If ",e.jsx(t.strong,{children:"exists()"}),` returns false, the
barrier is gone and the client proceeds`]}),`
`,e.jsxs(t.li,{children:["Else, if ",e.jsx(t.strong,{children:"exists()"}),` returns true,
the clients wait for a watch event from ZooKeeper for the barrier
node.`]}),`
`,e.jsxs(t.li,{children:[`When the watch event is triggered, the client reissues the
`,e.jsx(t.strong,{children:"exists( )"}),` call, again waiting until
the barrier node is removed.`]}),`
`]}),`
`,e.jsx(t.h4,{id:"double-barriers",children:"Double Barriers"}),`
`,e.jsx(t.p,{children:`Double barriers enable clients to synchronize the beginning and
the end of a computation. When enough processes have joined the barrier,
processes start their computation and leave the barrier once they have
finished. This recipe shows how to use a ZooKeeper node as a
barrier.`}),`
`,e.jsxs(t.p,{children:[`The pseudo code in this recipe represents the barrier node as
`,e.jsx(t.em,{children:"b"}),". Every client process ",e.jsx(t.em,{children:"p"}),`
registers with the barrier node on entry and unregisters when it is
ready to leave. A node registers with the barrier node via the `,e.jsx(t.strong,{children:"Enter"}),` procedure below, it waits until
`,e.jsx(t.em,{children:"x"}),` client process register before proceeding with
the computation. (The `,e.jsx(t.em,{children:"x"}),` here is up to you to
determine for your system.)`]}),`
`,e.jsxs(t.table,{children:[e.jsx(t.thead,{children:e.jsxs(t.tr,{children:[e.jsx(t.th,{children:e.jsx(t.strong,{children:"Enter"})}),e.jsx(t.th,{children:e.jsx(t.strong,{children:"Leave"})})]})}),e.jsxs(t.tbody,{children:[e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["1. Create a name ",e.jsxs(t.em,{children:[e.jsx(t.em,{children:"n"})," = _b"]}),"+“/”+_p**"]}),e.jsxs(t.td,{children:["1. ",e.jsx(t.strong,{children:"L = getChildren(b, false)"})]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["2. Set watch: ",e.jsxs(t.strong,{children:["exists(",e.jsx(t.em,{children:"b"})," + ‘‘/ready’’, true)"]})]}),e.jsx(t.td,{children:"2. if no children, exit"})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["3. Create child: ",e.jsxs(t.strong,{children:["create(",e.jsx(t.em,{children:"n"}),", EPHEMERAL)"]})]}),e.jsxs(t.td,{children:["3. if ",e.jsx(t.em,{children:"p"})," is only process node in L, delete(n) and exit"]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["4. ",e.jsx(t.strong,{children:"L = getChildren(b, false)"})]}),e.jsxs(t.td,{children:["4. if ",e.jsx(t.em,{children:"p"})," is the lowest process node in L, wait on highest process node in L"]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["5. if fewer children in L than",e.jsx(t.em,{children:"x"}),", wait for watch event"]}),e.jsxs(t.td,{children:["5. else **delete(",e.jsx(t.em,{children:"n"}),")**if still exists and wait on lowest process node in L"]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["6. else ",e.jsx(t.strong,{children:"create(b + ‘‘/ready’’, REGULAR)"})]}),e.jsx(t.td,{children:"6. goto 1"})]})]})]}),`
`,e.jsx(t.p,{children:`On entering, all processes watch on a ready node and
create an ephemeral node as a child of the barrier node. Each process
but the last enters the barrier and waits for the ready node to appear
at line 5. The process that creates the xth node, the last process, will
see x nodes in the list of children and create the ready node, waking up
the other processes. Note that waiting processes wake up only when it is
time to exit, so waiting is efficient.`}),`
`,e.jsxs(t.p,{children:["On exit, you can't use a flag such as ",e.jsx(t.em,{children:"ready"}),`
because you are watching for process nodes to go away. By using
ephemeral nodes, processes that fail after the barrier has been entered
do not prevent correct processes from finishing. When processes are
ready to leave, they need to delete their process nodes and wait for all
other processes to do the same.`]}),`
`,e.jsxs(t.p,{children:[`Processes exit when there are no process nodes left as children of
`,e.jsx(t.em,{children:"b"}),`. However, as an efficiency, you can use the
lowest process node as the ready flag. All other processes that are
ready to exit watch for the lowest existing process node to go away, and
the owner of the lowest process watches for any other process node
(picking the highest for simplicity) to go away. This means that only a
single process wakes up on each node deletion except for the last node,
which wakes up everyone when it is removed.`]}),`
`,e.jsx(t.h3,{id:"queues",children:"Queues"}),`
`,e.jsxs(t.p,{children:[`Distributed queues are a common data structure. To implement a
distributed queue in ZooKeeper, first designate a znode to hold the queue,
the queue node. The distributed clients put something into the queue by
calling create() with a pathname ending in "queue-", with the
`,e.jsx(t.em,{children:"sequence"})," and ",e.jsx(t.em,{children:"ephemeral"}),` flags in
the create() call set to true. Because the `,e.jsx(t.em,{children:"sequence"}),`
flag is set, the new pathname will have the form
`,e.jsx(t.em,{children:"path-to-queue-node"}),`/queue-X, where X is a monotonic increasing number. A
client that wants to be removed from the queue calls ZooKeeper's `,e.jsx(t.strong,{children:"getChildren( )"}),` function, with
`,e.jsx(t.em,{children:"watch"}),` set to true on the queue node, and begins
processing nodes with the lowest number. The client does not need to issue
another `,e.jsx(t.strong,{children:"getChildren( )"}),` until it exhausts
the list obtained from the first `,e.jsx(t.strong,{children:`getChildren(
)`}),` call. If there are no children in the queue node, the
reader waits for a watch notification to check the queue again.`]}),`
`,e.jsx(o,{type:"info",children:e.jsx(t.p,{children:`There now exists a Queue implementation in ZooKeeper recipes directory. This
is distributed with the release -- zookeeper-recipes/zookeeper-recipes-queue
directory of the release artifact.`})}),`
`,e.jsx(t.h4,{id:"priority-queues",children:"Priority Queues"}),`
`,e.jsxs(t.p,{children:[`To implement a priority queue, you need only make two simple
changes to the generic `,e.jsx(t.a,{href:"#queues",children:`queue
recipe`}),` . First, to add to a queue, the pathname ends with
"queue-YY" where YY is the priority of the element with lower numbers
representing higher priority (just like UNIX). Second, when removing
from the queue, a client uses an up-to-date children list meaning that
the client will invalidate previously obtained children lists if a watch
notification triggers for the queue node.`]}),`
`,e.jsx(t.h3,{id:"locks",children:"Locks"}),`
`,e.jsx(t.p,{children:`Fully distributed locks that are globally synchronous, meaning at
any snapshot in time no two clients think they hold the same lock. These
can be implemented using ZooKeeper. As with priority queues, first define
a lock node.`}),`
`,e.jsx(o,{type:"info",children:e.jsx(t.p,{children:`There now exists a Lock implementation in ZooKeeper recipes directory. This is
distributed with the release -- zookeeper-recipes/zookeeper-recipes-lock
directory of the release artifact.`})}),`
`,e.jsx(t.p,{children:"Clients wishing to obtain a lock do the following:"}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsxs(t.li,{children:["Call ",e.jsx(t.strong,{children:"create( )"}),` with a pathname
of "`,e.jsx(t.em,{children:"locknode"}),'/guid-lock-" and the ',e.jsx(t.em,{children:"sequence"}),` and
`,e.jsx(t.em,{children:"ephemeral"})," flags set. The ",e.jsx(t.em,{children:"guid"}),`
is needed in case the create() result is missed. See the note below.`]}),`
`,e.jsxs(t.li,{children:["Call ",e.jsx(t.strong,{children:"getChildren( )"}),` on the lock
node `,e.jsx(t.em,{children:"without"}),` setting the watch flag (this is
important to avoid the herd effect).`]}),`
`,e.jsxs(t.li,{children:["If the pathname created in step ",e.jsx(t.strong,{children:"1"}),` has the lowest sequence number suffix, the
client has the lock and the client exits the protocol.`]}),`
`,e.jsxs(t.li,{children:["The client calls ",e.jsx(t.strong,{children:"exists( )"}),` with
the watch flag set on the path in the lock directory with the next
lowest sequence number.`]}),`
`,e.jsxs(t.li,{children:["if ",e.jsx(t.strong,{children:"exists( )"}),` returns null, go
to step `,e.jsx(t.strong,{children:"2"}),`. Otherwise, wait for a
notification for the pathname from the previous step before going to
step `,e.jsx(t.strong,{children:"2"}),"."]}),`
`]}),`
`,e.jsx(t.p,{children:`The unlock protocol is very simple: clients wishing to release a
lock simply delete the node they created in step 1.`}),`
`,e.jsx(t.p,{children:"Here are a few things to notice:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`The removal of a node will only cause one client to wake up
since each node is watched by exactly one client. In this way, you
avoid the herd effect.`}),`
`,e.jsx(t.li,{children:"There is no polling or timeouts."}),`
`,e.jsx(t.li,{children:`Because of the way you implement locking, it is easy to see the
amount of lock contention, break locks, debug locking problems,
etc.`}),`
`]}),`
`,e.jsx(t.h4,{id:"recoverable-errors-and-the-guid",children:"Recoverable Errors and the GUID"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["If a recoverable error occurs calling ",e.jsx(t.strong,{children:"create()"}),` the
client should call `,e.jsx(t.strong,{children:"getChildren()"}),` and check for a node
containing the `,e.jsx(t.em,{children:"guid"}),` used in the path name.
This handles the case (noted `,e.jsx(t.a,{href:"#important-note-about-error-handling",children:"above"}),`) of
the create() succeeding on the server but the server crashing before returning the name
of the new node.`]}),`
`]}),`
`,e.jsx(t.h4,{id:"shared-locks",children:"Shared Locks"}),`
`,e.jsx(t.p,{children:`You can implement shared locks by with a few changes to the lock
protocol:`}),`
`,e.jsxs(t.table,{children:[e.jsx(t.thead,{children:e.jsxs(t.tr,{children:[e.jsx(t.th,{children:e.jsx(t.strong,{children:"Obtaining a read lock:"})}),e.jsx(t.th,{children:e.jsx(t.strong,{children:"Obtaining a write lock:"})})]})}),e.jsxs(t.tbody,{children:[e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["1. Call ",e.jsx(t.strong,{children:"create( )"}),' to create a node with pathname "',e.jsx(t.em,{children:"guid-/read-"}),'". This is the lock node use later in the protocol. Make sure to set both the ',e.jsx(t.em,{children:"sequence"})," and ",e.jsx(t.em,{children:"ephemeral"})," flags."]}),e.jsxs(t.td,{children:["1. Call ",e.jsx(t.strong,{children:"create( )"}),' to create a node with pathname "',e.jsx(t.em,{children:"guid-/write-"}),'". This is the lock node spoken of later in the protocol. Make sure to set both ',e.jsx(t.em,{children:"sequence"})," and ",e.jsx(t.em,{children:"ephemeral"})," flags."]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["2. Call ",e.jsx(t.strong,{children:"getChildren( )"})," on the lock node ",e.jsx(t.em,{children:"without"})," setting the ",e.jsx(t.em,{children:"watch"})," flag - this is important, as it avoids the herd effect."]}),e.jsxs(t.td,{children:["2. Call ",e.jsx(t.strong,{children:"getChildren( )"})," on the lock node ",e.jsx(t.em,{children:"without"})," setting the ",e.jsx(t.em,{children:"watch"})," flag - this is important, as it avoids the herd effect."]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:['3. If there are no children with a pathname starting with "',e.jsx(t.em,{children:"write-"}),'" and having a lower sequence number than the node created in step ',e.jsx(t.strong,{children:"1"}),", the client has the lock and can exit the protocol."]}),e.jsxs(t.td,{children:["3. If there are no children with a lower sequence number than the node created in step ",e.jsx(t.strong,{children:"1"}),", the client has the lock and the client exits the protocol."]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["4. Otherwise, call ",e.jsx(t.strong,{children:"exists( )"}),", with ",e.jsx(t.em,{children:"watch"}),' flag, set on the node in lock directory with pathname starting with "',e.jsx(t.em,{children:"write-"}),'" having the next lowest sequence number.']}),e.jsxs(t.td,{children:["4. Call ",e.jsx(t.strong,{children:"exists( ),"})," with ",e.jsx(t.em,{children:"watch"})," flag set, on the node with the pathname that has the next lowest sequence number."]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["5. If ",e.jsx(t.strong,{children:"exists( )"})," returns ",e.jsx(t.em,{children:"false"}),", goto step ",e.jsx(t.strong,{children:"2"}),"."]}),e.jsxs(t.td,{children:["5. If ",e.jsx(t.strong,{children:"exists( )"})," returns ",e.jsx(t.em,{children:"false"}),", goto step ",e.jsx(t.strong,{children:"2"}),". Otherwise, wait for a notification for the pathname from the previous step before going to step ",e.jsx(t.strong,{children:"2"}),"."]})]}),e.jsxs(t.tr,{children:[e.jsxs(t.td,{children:["6. Otherwise, wait for a notification for the pathname from the previous step before going to step ",e.jsx(t.strong,{children:"2"})]}),e.jsx(t.td,{})]})]})]}),`
`,e.jsx(t.p,{children:"Notes:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[`It might appear that this recipe creates a herd effect:
when there is a large group of clients waiting for a read
lock, and all getting notified more or less simultaneously
when the "`,e.jsx(t.em,{children:"write-"}),`" node with the lowest
sequence number is deleted. In fact. that's valid behavior:
as all those waiting reader clients should be released since
they have the lock. The herd effect refers to releasing a
"herd" when in fact only a single or a small number of
machines can proceed.`]}),`
`,e.jsxs(t.li,{children:["See the ",e.jsx(t.a,{href:"#recoverable-errors-and-the-guid",children:"note for Locks"})," on how to use the guid in the node."]}),`
`]}),`
`,e.jsx(t.h4,{id:"revocable-shared-locks",children:"Revocable Shared Locks"}),`
`,e.jsx(t.p,{children:`With minor modifications to the Shared Lock protocol, you make
shared locks revocable by modifying the shared lock protocol:`}),`
`,e.jsxs(t.p,{children:["In step ",e.jsx(t.strong,{children:"1"}),`, of both obtain reader
and writer lock protocols, call `,e.jsx(t.strong,{children:`getData(
)`})," with ",e.jsx(t.em,{children:"watch"}),` set, immediately after the
call to `,e.jsx(t.strong,{children:"create( )"}),`. If the client
subsequently receives notification for the node it created in step
`,e.jsx(t.strong,{children:"1"}),", it does another ",e.jsx(t.strong,{children:"getData( )"}),` on that node, with
`,e.jsx(t.em,{children:"watch"}),` set and looks for the string "unlock", which
signals to the client that it must release the lock. This is because,
according to this shared lock protocol, you can request the client with
the lock give up the lock by calling `,e.jsx(t.strong,{children:"setData()"}),' on the lock node, writing "unlock" to that node.']}),`
`,e.jsxs(t.p,{children:[`Note that this protocol requires the lock holder to consent to
releasing the lock. Such consent is important, especially if the lock
holder needs to do some processing before releasing the lock. Of course
you can always implement `,e.jsx(t.em,{children:`Revocable Shared Locks with Freaking
Laser Beams`}),` by stipulating in your protocol that the revoker
is allowed to delete the lock node if after some length of time the lock
isn't deleted by the lock holder.`]}),`
`,e.jsx(t.h3,{id:"two-phased-commit",children:"Two-phased Commit"}),`
`,e.jsx(t.p,{children:`A two-phase commit protocol is an algorithm that lets all clients in
a distributed system agree either to commit a transaction or abort.`}),`
`,e.jsx(t.p,{children:`In ZooKeeper, you can implement a two-phased commit by having a
coordinator create a transaction node, say "/app/Tx", and one child node
per participating site, say "/app/Tx/s_i". When coordinator creates the
child node, it leaves the content undefined. Once each site involved in
the transaction receives the transaction from the coordinator, the site
reads each child node and sets a watch. Each site then processes the query
and votes "commit" or "abort" by writing to its respective node. Once the
write completes, the other sites are notified, and as soon as all sites
have all votes, they can decide either "abort" or "commit". Note that a
node can decide "abort" earlier if some site votes for "abort".`}),`
`,e.jsx(t.p,{children:`An interesting aspect of this implementation is that the only role
of the coordinator is to decide upon the group of sites, to create the
ZooKeeper nodes, and to propagate the transaction to the corresponding
sites. In fact, even propagating the transaction can be done through
ZooKeeper by writing it in the transaction node.`}),`
`,e.jsx(t.p,{children:`There are two important drawbacks of the approach described above.
One is the message complexity, which is O(n²). The second is the
impossibility of detecting failures of sites through ephemeral nodes. To
detect the failure of a site using ephemeral nodes, it is necessary that
the site create the node.`}),`
`,e.jsx(t.p,{children:`To solve the first problem, you can have only the coordinator
notified of changes to the transaction nodes, and then notify the sites
once coordinator reaches a decision. Note that this approach is scalable,
but it is slower too, as it requires all communication to go through the
coordinator.`}),`
`,e.jsx(t.p,{children:`To address the second problem, you can have the coordinator
propagate the transaction to the sites, and have each site creating its
own ephemeral node.`}),`
`,e.jsx(t.h3,{id:"leader-election",children:"Leader Election"}),`
`,e.jsxs(t.p,{children:[`A simple way of doing leader election with ZooKeeper is to use the
`,e.jsx(t.strong,{children:"SEQUENCE|EPHEMERAL"}),` flags when creating
znodes that represent "proposals" of clients. The idea is to have a znode,
say "/election", such that each znode creates a child znode "/election/guid-n_"
with both flags SEQUENCE|EPHEMERAL. With the sequence flag, ZooKeeper
automatically appends a sequence number that is greater than anyone
previously appended to a child of "/election". The process that created
the znode with the smallest appended sequence number is the leader.`]}),`
`,e.jsx(t.p,{children:`That's not all, though. It is important to watch for failures of the
leader, so that a new client arises as the new leader in the case the
current leader fails. A trivial solution is to have all application
processes watching upon the current smallest znode, and checking if they
are the new leader when the smallest znode goes away (note that the
smallest znode will go away if the leader fails because the node is
ephemeral). But this causes a herd effect: upon a failure of the current
leader, all other processes receive a notification, and execute
getChildren on "/election" to obtain the current list of children of
"/election". If the number of clients is large, it causes a spike on the
number of operations that ZooKeeper servers have to process. To avoid the
herd effect, it is sufficient to watch for the next znode down on the
sequence of znodes. If a client receives a notification that the znode it
is watching is gone, then it becomes the new leader in the case that there
is no smaller znode. Note that this avoids the herd effect by not having
all clients watching the same znode.`}),`
`,e.jsx(t.p,{children:"Here's the pseudo code:"}),`
`,e.jsx(t.p,{children:`Let ELECTION be a path of choice of the application. To volunteer to
be a leader:`}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsx(t.li,{children:`Create znode z with path "ELECTION/guid-n_" with both SEQUENCE and
EPHEMERAL flags;`}),`
`,e.jsx(t.li,{children:`Let C be the children of "ELECTION", and i is the sequence
number of z;`}),`
`,e.jsx(t.li,{children:`Watch for changes on "ELECTION/guid-n_j", where j is the largest
sequence number such that j < i and n_j is a znode in C;`}),`
`]}),`
`,e.jsx(t.p,{children:"Upon receiving a notification of znode deletion:"}),`
`,e.jsxs(t.ol,{children:[`
`,e.jsx(t.li,{children:"Let C be the new set of children of ELECTION;"}),`
`,e.jsx(t.li,{children:`If z is the smallest node in C, then execute leader
procedure;`}),`
`,e.jsx(t.li,{children:`Otherwise, watch for changes on "ELECTION/guid-n_j", where j is the
largest sequence number such that j < i and n_j is a znode in C;`}),`
`]}),`
`,e.jsx(t.p,{children:"Notes:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsx(t.li,{children:`Note that the znode having no preceding znode on the list of
children do not imply that the creator of this znode is aware that it is
the current leader. Applications may consider creating a separate znode
to acknowledge that the leader has executed the leader procedure.`}),`
`,e.jsxs(t.li,{children:["See the ",e.jsx(t.a,{href:"#recoverable-errors-and-the-guid",children:"note for Locks"})," on how to use the guid in the node."]}),`
`]})]})}function u(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(i,{...n})}):i(n)}function r(n,t){throw new Error("Expected component `"+n+"` to be defined: you likely forgot to import, pass, or provide it.")}export{a as _markdown,u as default,c as extractedReferences,h as frontmatter,l as structuredData,d as toc};
