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

# ZooKeeper Observers

* [Observers: Scaling ZooKeeper Without Hurting Write Performance](#ch_Introduction)
* [How to use Observers](#sc_UsingObservers)
* [Example use cases](#ch_UseCases)

<a name="ch_Introduction"></a>

## Observers: Scaling ZooKeeper Without Hurting Write Performance

Although ZooKeeper performs very well by having clients connect directly
to voting members of the ensemble, this architecture makes it hard to
scale out to huge numbers of clients. The problem is that as we add more
voting members, the write performance drops. This is due to the fact that
a write operation requires the agreement of (in general) at least half the
nodes in an ensemble and therefore the cost of a vote can increase
significantly as more voters are added.

We have introduced a new type of ZooKeeper node called
an _Observer_ which helps address this problem and
further improves ZooKeeper's scalability. Observers are non-voting members
of an ensemble which only hear the results of votes, not the agreement
protocol that leads up to them. Other than this simple distinction,
Observers function exactly the same as Followers - clients may connect to
them and send read and write requests to them. Observers forward these
requests to the Leader like Followers do, but they then simply wait to
hear the result of the vote. Because of this, we can increase the number
of Observers as much as we like without harming the performance of votes.

Observers have other advantages. Because they do not vote, they are not a
critical part of the ZooKeeper ensemble. Therefore they can fail, or be
disconnected from the cluster, without harming the availability of the
ZooKeeper service. The benefit to the user is that Observers may connect
over less reliable network links than Followers. In fact, Observers may be
used to talk to a ZooKeeper server from another data center. Clients of
the Observer will see fast reads, as all reads are served locally, and
writes result in minimal network traffic as the number of messages
required in the absence of the vote protocol is smaller.

<a name="sc_UsingObservers"></a>

## How to use Observers

Setting up a ZooKeeper ensemble that uses Observers is very simple,
and requires just two changes to your config files. Firstly, in the config
file of every node that is to be an Observer, you must place this line:

    peerType=observer

This line tells ZooKeeper that the server is to be an Observer. Secondly,
in every server config file, you must add :observer to the server
definition line of each Observer. For example:

    server.1:localhost:2181:3181:observer

This tells every other server that server.1 is an Observer, and that they
should not expect it to vote. This is all the configuration you need to do
to add an Observer to your ZooKeeper cluster. Now you can connect to it as
though it were an ordinary Follower. Try it out, by running:

    $ bin/zkCli.sh -server localhost:2181

where localhost:2181 is the hostname and port number of the Observer as
specified in every config file. You should see a command line prompt
through which you can issue commands like _ls_ to query
the ZooKeeper service.

<a name="ch_ObserverMasters"></a>

## How to use Observer Masters

Observers function simple as non-voting members of the ensemble, sharing
the Learner interface with Followers and holding only a slightly different
internal pipeline. Both maintain connections along the quorum port with the
Leader by which they learn of all new proposals on the ensemble.

By default, Observers connect to the Leader of the quorum along its
quorum port and this is how they learn of all new proposals on the
ensemble. There are benefits to allowing Observers to connect to the
Followers instead as a means of plugging into the commit stream in place
of connecting to the Leader. It shifts the burden of supporting Observers
off the Leader and allow it to focus on coordinating the commit of writes.
This means better performance when the Leader is under high load,
particularly high network load such as can happen after a leader election
when many Learners need to sync. It reduces the total network connections
maintained on the Leader when there are a high number of observers.
Activating Followers to support Observers allow the overall number of
Observers to scale into the hundreds. On the other end, Observer
availability is improved since it will take shorter time for a high
number of Observers to finish syncing and start serving client traffic.

This feature can be activated by letting all members of the ensemble know
which port will be used by the Followers to listen for Observer
connections. The following entry, when added to the server config file,
will instruct Observers to connect to peers (Leaders and Followers) on
port 2191 and instruct Followers to create an ObserverMaster thread to
listen and serve on that port.

    observerMasterPort=2191
<a name="ch_UseCases"></a>

## Example use cases

Two example use cases for Observers are listed below. In fact, wherever
you wish to scale the number of clients of your ZooKeeper ensemble, or
where you wish to insulate the critical part of an ensemble from the load
of dealing with client requests, Observers are a good architectural
choice.

* As a datacenter bridge: Forming a ZK ensemble between two
  datacenters is a problematic endeavour as the high variance in latency
  between the datacenters could lead to false positive failure detection
  and partitioning. However if the ensemble runs entirely in one
  datacenter, and the second datacenter runs only Observers, partitions
  aren't problematic as the ensemble remains connected. Clients of the
  Observers may still see and issue proposals.
* As a link to a message bus: Some companies have expressed an
  interest in using ZK as a component of a persistent reliable message
  bus. Observers would give a natural integration point for this work: a
  plug-in mechanism could be used to attach the stream of proposals an
  Observer sees to a publish-subscribe system, again without loading the
  core ensemble.
