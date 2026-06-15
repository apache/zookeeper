import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let a=`## Hierarchical Quorums

This document gives an example of how to use hierarchical quorums. The basic idea is
very simple. First, we split servers into groups, and add a line for each group listing
the servers that form this group. Next we have to assign a weight to each server.

The following example shows how to configure a system with three groups of three servers
each, and we assign a weight of 1 to each server:

\`\`\`
group.1=1:2:3
group.2=4:5:6
group.3=7:8:9

weight.1=1
weight.2=1
weight.3=1
weight.4=1
weight.5=1
weight.6=1
weight.7=1
weight.8=1
weight.9=1
\`\`\`

When running the system, we are able to form a quorum once we have a majority of votes from
a majority of non-zero-weight groups. Groups that have zero weight are discarded and not
considered when forming quorums. Looking at the example, we are able to form a quorum once
we have votes from at least two servers from each of two different groups.

## Oracle Quorum

Oracle Quorum increases the availability of a cluster of 2 ZooKeeper instances with a failure detector known as the Oracle.
The Oracle is designed to grant permission to the instance which is the only remaining instance
in a 2-instance configuration when the other instance is identified as faulty by the failure detector.

### The Implementation of the Oracle

Every instance accesses a file which contains either \`0\` or \`1\` to indicate whether that instance is authorized by the Oracle.
This design can be changed since failure detector algorithms vary. You can override the \`askOracle()\` method in *QuorumOracleMaj* to adapt a preferred way of reading the Oracle's decision.

### Deployment Contexts

The Oracle is designed to increase the availability of a cluster of **2** ZooKeeper instances; thus, the size of the voting member is **2**.
In other words, the Oracle solves the consensus problem of a possible faulty instance in a two-instance ensemble.

When the size of the voting members exceeds 2, the expected way to make the Oracle work correctly is to reconfigure the cluster size when a faulty machine is identified.
For example, with a configuration of 5 instances, when a faulty machine breaks the connection with the Leader, a *reconfig* client request is expected to re-form the cluster as 4 instances.
Once the size of the voting member equals 2, the configuration falls into the problem domain which the Oracle is designed to address.

### Configuring the Oracle in \`zoo.cfg\`

Regardless of the cluster size, \`oraclePath\` must be configured at initialization time, like other static parameters.
The following shows the correct way to specify and enable the Oracle:

\`\`\`
oraclePath=/to/some/file
\`\`\`

\`QuorumOracleMaj\` reads the result of a failure detector written to a text file — the oracle file.
Suppose you have the result of the failure detector written to \`/some/path/result.txt\`; the correct configuration is:

\`\`\`
oraclePath=/some/path/result.txt
\`\`\`

The oracle file should contain \`1\` to authorize the instance, or \`0\` to deny it. An example file can be created with:

\`\`\`bash
echo 1 > /some/path/result.txt
\`\`\`

Any equivalent file is suitable for the current implementation of \`QuorumOracleMaj\`.
The number of oracle files should equal the number of ZooKeeper instances configured to enable the Oracle.
Each ZooKeeper instance should have its own oracle file — files must not be shared, otherwise the issues described in the Safety section will arise.

#### Example \`zoo.cfg\`

\`\`\`
dataDir=/data
dataLogDir=/datalog
tickTime=2000
initLimit=5
syncLimit=2
autopurge.snapRetainCount=3
autopurge.purgeInterval=0
maxClientCnxns=60
standaloneEnabled=true
admin.enableServer=true
oraclePath=/chassis/mastership
server.1=0.0.0.0:2888:3888;2181
server.2=hw1:2888:3888;2181
\`\`\`

### Behavior After Enabling the Oracle

*QuorumPeerConfig* will create an instance of *QuorumOracleMaj* instead of the default \`QuorumMaj\` when it reads an \`oraclePath\` in \`zoo.cfg\`.
\`QuorumOracleMaj\` inherits from \`QuorumMaj\` and differs from its superclass by overriding \`containsQuorum()\`.
\`QuorumOracleMaj\` executes its version of \`containsQuorum\` when the Leader loses all of its followers and fails to maintain the quorum.
In all other cases, *QuorumOracleMaj* behaves identically to *QuorumMaj*.

### Considerations When Using the Oracle

We consider an asynchronous distributed system which consists of **2** ZooKeeper instances and an Oracle.

#### Liveness Issue

When the Oracle satisfies the following property introduced by [^1]:

> **Strong Completeness:** There is a time after which every process that crashes is permanently suspected by every correct process.

Liveness of the system is ensured by the Oracle.
However, when the Oracle fails to maintain this property, loss of liveness is expected. For example:

Suppose we have a Leader and a Follower running in the broadcasting state. The system will lose its liveness when:

1. The Leader fails, but the Oracle does not detect the faulty Leader — the Oracle will not authorize the Follower to become a new Leader.
2. A Follower fails, but the Oracle does not detect the faulty Follower — the Oracle will authorize the Leader to move the system forward.

#### Safety Issue

**Loss of Progress**

Progress can be lost when multiple failures occur in the system at different times. For example:

Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:

* **T1** \`zxid(0x1_1)\`: L-Ben fails, and F-John takes over under authorization from the Oracle.
* **T2** \`zxid(0x2_1)\`: F-John becomes a new Leader (L-John) and starts a new epoch.
* **T3** \`zxid(0x2_A)\`: L-John fails.
* **T4** \`zxid(0x2_A)\`: Ben recovers and starts leader election.
* **T5** \`zxid(0x3_1)\`: Ben becomes the new Leader (L-Ben) under authorization from the Oracle.

In this case, the system loses its progress after L-Ben initially failed.

However, loss of progress can be prevented by making the Oracle capable of referring to the latest zxid.
When the Oracle can refer to the latest zxid, at T5 \`zxid(0x2_A)\`, Ben will not complete leader election because the Oracle would not authorize him while John is still known to be ahead.
This trades liveness for safety.

**Split Brain Issue**

We consider the Oracle satisfies the following property introduced by [^1]:

> **Accuracy:** There is a time after which some correct process is never suspected by any process.

The decisions the Oracle gives out must be mutually exclusive.

Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:

* At any time, the Oracle will not authorize both Ben and John simultaneously, even if each failure detector suspects the other.
* Equivalently: for any two Oracle files, their values must not both be \`1\` at the same time.

Split brain is expected when the Oracle fails to maintain this property during leader election, which can happen at:

1. System start.
2. A failed instance recovering from failure.

### Examples of Failure Detector Implementations

A failure detector's role is to authorize the querying ZooKeeper instance whether it has the right to move the system forward without waiting for the faulty instance.

#### Hardware-based Implementation

Suppose two dedicated hardware nodes, \`hw1\` and \`hw2\`, host ZooKeeper instances \`zk1\` and \`zk2\` respectively, forming a cluster.
A hardware device attached to both nodes can determine whether each is powered on.
When \`hw1\` is not powered on, \`zk1\` is undoubtedly faulty.
The hardware device then updates the oracle file on \`hw2\` to \`1\`, authorizing \`zk2\` to move the system forward.

#### Software-based Implementation

Suppose two dedicated hardware nodes, \`hw1\` and \`hw2\`, host ZooKeeper instances \`zk1\` and \`zk2\` respectively.
Two services, \`o1\` on \`hw1\` and \`o2\` on \`hw2\`, detect whether the other node is alive (for example, by pinging).
When \`o1\` cannot ping \`hw2\`, it identifies \`hw2\` as faulty and updates the oracle file of \`zk1\` to \`1\`, authorizing \`zk1\` to move forward.

#### Using a USB Device as the Oracle

In macOS 10.15.7 (19H2), external storage devices are mounted under \`/Volumes\`.
A USB device containing the required oracle file can serve as the Oracle.
When the device is connected, the oracle authorizes the leader to move the system forward.

The following 6 steps demonstrate how this works:

1. Insert a USB device named \`Oracle\`. The path \`/Volumes/Oracle\` will be accessible.

2. Create a file containing \`1\` under \`/Volumes/Oracle\` named \`mastership\`:

   \`\`\`bash
   echo 1 > mastership
   \`\`\`

   The path \`/Volumes/Oracle/mastership\` is now accessible to ZooKeeper instances.

3. Create a \`zoo.cfg\` like the following:

   \`\`\`
   dataDir=/data
   dataLogDir=/datalog
   tickTime=2000
   initLimit=5
   syncLimit=2
   autopurge.snapRetainCount=3
   autopurge.purgeInterval=0
   maxClientCnxns=60
   standaloneEnabled=true
   admin.enableServer=true
   oraclePath=/Volumes/Oracle/mastership
   server.1=0.0.0.0:2888:3888;2181
   server.2=hw1:2888:3888;2181
   \`\`\`

   > **Note:** Split brain will not occur here because there is only a single USB device. \`mastership\` must not be shared by multiple instances — only one ZooKeeper instance should be configured with Oracle. See the [Safety Issue](#safety-issue) section for details.

4. Start the cluster. It should form a quorum normally.

5. Terminate the instance that is either not attached to a USB device or whose \`mastership\` file contains \`0\`. Two scenarios are expected:
   * A leader failure occurs, and the remaining instance completes leader election on its own via the Oracle.
   * The quorum is maintained via the Oracle.

6. Remove the USB device. \`/Volumes/Oracle/mastership\` becomes unavailable. According to the current implementation, whenever the Leader queries the oracle and the file is missing, the oracle throws an exception and returns \`false\`. Repeating step 5 will result in either the system being unable to recover from a leader failure, or the leader losing the quorum and service being interrupted.

With these steps, you can observe and practice how the Oracle works with a two-instance system.

### Reference

[^1]: Tushar Deepak Chandra and Sam Toueg. 1991. Unreliable failure detectors for asynchronous systems (preliminary version). In *Proceedings of the tenth annual ACM symposium on Principles of distributed computing* (*PODC '91*). Association for Computing Machinery, New York, NY, USA, 325–340. [DOI:10.1145/112600.112627](https://doi.org/10.1145/112600.112627)
`,r={title:"Quorums",description:"How to configure ZooKeeper quorums: hierarchical quorums using server groups and weights, and Oracle Quorum for increasing availability in two-instance clusters."},o=[{href:"#safety-issue"},{href:"https://doi.org/10.1145/112600.112627"}],h={contents:[{heading:"hierarchical-quorums",content:`This document gives an example of how to use hierarchical quorums. The basic idea is
very simple. First, we split servers into groups, and add a line for each group listing
the servers that form this group. Next we have to assign a weight to each server.`},{heading:"hierarchical-quorums",content:`The following example shows how to configure a system with three groups of three servers
each, and we assign a weight of 1 to each server:`},{heading:"hierarchical-quorums",content:`When running the system, we are able to form a quorum once we have a majority of votes from
a majority of non-zero-weight groups. Groups that have zero weight are discarded and not
considered when forming quorums. Looking at the example, we are able to form a quorum once
we have votes from at least two servers from each of two different groups.`},{heading:"oracle-quorum",content:`Oracle Quorum increases the availability of a cluster of 2 ZooKeeper instances with a failure detector known as the Oracle.
The Oracle is designed to grant permission to the instance which is the only remaining instance
in a 2-instance configuration when the other instance is identified as faulty by the failure detector.`},{heading:"the-implementation-of-the-oracle",content:`Every instance accesses a file which contains either 0 or 1 to indicate whether that instance is authorized by the Oracle.
This design can be changed since failure detector algorithms vary. You can override the askOracle() method in QuorumOracleMaj to adapt a preferred way of reading the Oracle's decision.`},{heading:"deployment-contexts",content:`The Oracle is designed to increase the availability of a cluster of 2 ZooKeeper instances; thus, the size of the voting member is 2.
In other words, the Oracle solves the consensus problem of a possible faulty instance in a two-instance ensemble.`},{heading:"deployment-contexts",content:`When the size of the voting members exceeds 2, the expected way to make the Oracle work correctly is to reconfigure the cluster size when a faulty machine is identified.
For example, with a configuration of 5 instances, when a faulty machine breaks the connection with the Leader, a reconfig client request is expected to re-form the cluster as 4 instances.
Once the size of the voting member equals 2, the configuration falls into the problem domain which the Oracle is designed to address.`},{heading:"configuring-the-oracle-in-zoocfg",content:`Regardless of the cluster size, oraclePath must be configured at initialization time, like other static parameters.
The following shows the correct way to specify and enable the Oracle:`},{heading:"configuring-the-oracle-in-zoocfg",content:`QuorumOracleMaj reads the result of a failure detector written to a text file — the oracle file.
Suppose you have the result of the failure detector written to /some/path/result.txt; the correct configuration is:`},{heading:"configuring-the-oracle-in-zoocfg",content:"The oracle file should contain 1 to authorize the instance, or 0 to deny it. An example file can be created with:"},{heading:"configuring-the-oracle-in-zoocfg",content:`Any equivalent file is suitable for the current implementation of QuorumOracleMaj.
The number of oracle files should equal the number of ZooKeeper instances configured to enable the Oracle.
Each ZooKeeper instance should have its own oracle file — files must not be shared, otherwise the issues described in the Safety section will arise.`},{heading:"behavior-after-enabling-the-oracle",content:`QuorumPeerConfig will create an instance of QuorumOracleMaj instead of the default QuorumMaj when it reads an oraclePath in zoo.cfg.
QuorumOracleMaj inherits from QuorumMaj and differs from its superclass by overriding containsQuorum().
QuorumOracleMaj executes its version of containsQuorum when the Leader loses all of its followers and fails to maintain the quorum.
In all other cases, QuorumOracleMaj behaves identically to QuorumMaj.`},{heading:"considerations-when-using-the-oracle",content:"We consider an asynchronous distributed system which consists of 2 ZooKeeper instances and an Oracle."},{heading:"liveness-issue",content:"When the Oracle satisfies the following property introduced by :"},{heading:"liveness-issue",content:"Strong Completeness: There is a time after which every process that crashes is permanently suspected by every correct process."},{heading:"liveness-issue",content:`Liveness of the system is ensured by the Oracle.
However, when the Oracle fails to maintain this property, loss of liveness is expected. For example:`},{heading:"liveness-issue",content:"Suppose we have a Leader and a Follower running in the broadcasting state. The system will lose its liveness when:"},{heading:"liveness-issue",content:"The Leader fails, but the Oracle does not detect the faulty Leader — the Oracle will not authorize the Follower to become a new Leader."},{heading:"liveness-issue",content:"A Follower fails, but the Oracle does not detect the faulty Follower — the Oracle will authorize the Leader to move the system forward."},{heading:"safety-issue",content:"Loss of Progress"},{heading:"safety-issue",content:"Progress can be lost when multiple failures occur in the system at different times. For example:"},{heading:"safety-issue",content:"Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:"},{heading:"safety-issue",content:"T1 zxid(0x1_1): L-Ben fails, and F-John takes over under authorization from the Oracle."},{heading:"safety-issue",content:"T2 zxid(0x2_1): F-John becomes a new Leader (L-John) and starts a new epoch."},{heading:"safety-issue",content:"T3 zxid(0x2_A): L-John fails."},{heading:"safety-issue",content:"T4 zxid(0x2_A): Ben recovers and starts leader election."},{heading:"safety-issue",content:"T5 zxid(0x3_1): Ben becomes the new Leader (L-Ben) under authorization from the Oracle."},{heading:"safety-issue",content:"In this case, the system loses its progress after L-Ben initially failed."},{heading:"safety-issue",content:`However, loss of progress can be prevented by making the Oracle capable of referring to the latest zxid.
When the Oracle can refer to the latest zxid, at T5 zxid(0x2_A), Ben will not complete leader election because the Oracle would not authorize him while John is still known to be ahead.
This trades liveness for safety.`},{heading:"safety-issue",content:"Split Brain Issue"},{heading:"safety-issue",content:"We consider the Oracle satisfies the following property introduced by :"},{heading:"safety-issue",content:"Accuracy: There is a time after which some correct process is never suspected by any process."},{heading:"safety-issue",content:"The decisions the Oracle gives out must be mutually exclusive."},{heading:"safety-issue",content:"Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:"},{heading:"safety-issue",content:"At any time, the Oracle will not authorize both Ben and John simultaneously, even if each failure detector suspects the other."},{heading:"safety-issue",content:"Equivalently: for any two Oracle files, their values must not both be 1 at the same time."},{heading:"safety-issue",content:"Split brain is expected when the Oracle fails to maintain this property during leader election, which can happen at:"},{heading:"safety-issue",content:"System start."},{heading:"safety-issue",content:"A failed instance recovering from failure."},{heading:"examples-of-failure-detector-implementations",content:"A failure detector's role is to authorize the querying ZooKeeper instance whether it has the right to move the system forward without waiting for the faulty instance."},{heading:"hardware-based-implementation",content:`Suppose two dedicated hardware nodes, hw1 and hw2, host ZooKeeper instances zk1 and zk2 respectively, forming a cluster.
A hardware device attached to both nodes can determine whether each is powered on.
When hw1 is not powered on, zk1 is undoubtedly faulty.
The hardware device then updates the oracle file on hw2 to 1, authorizing zk2 to move the system forward.`},{heading:"software-based-implementation",content:`Suppose two dedicated hardware nodes, hw1 and hw2, host ZooKeeper instances zk1 and zk2 respectively.
Two services, o1 on hw1 and o2 on hw2, detect whether the other node is alive (for example, by pinging).
When o1 cannot ping hw2, it identifies hw2 as faulty and updates the oracle file of zk1 to 1, authorizing zk1 to move forward.`},{heading:"using-a-usb-device-as-the-oracle",content:`In macOS 10.15.7 (19H2), external storage devices are mounted under /Volumes.
A USB device containing the required oracle file can serve as the Oracle.
When the device is connected, the oracle authorizes the leader to move the system forward.`},{heading:"using-a-usb-device-as-the-oracle",content:"The following 6 steps demonstrate how this works:"},{heading:"using-a-usb-device-as-the-oracle",content:"Insert a USB device named Oracle. The path /Volumes/Oracle will be accessible."},{heading:"using-a-usb-device-as-the-oracle",content:"Create a file containing 1 under /Volumes/Oracle named mastership:"},{heading:"using-a-usb-device-as-the-oracle",content:"The path /Volumes/Oracle/mastership is now accessible to ZooKeeper instances."},{heading:"using-a-usb-device-as-the-oracle",content:"Create a zoo.cfg like the following:"},{heading:"using-a-usb-device-as-the-oracle",content:"Note: Split brain will not occur here because there is only a single USB device. mastership must not be shared by multiple instances — only one ZooKeeper instance should be configured with Oracle. See the Safety Issue section for details."},{heading:"using-a-usb-device-as-the-oracle",content:"Start the cluster. It should form a quorum normally."},{heading:"using-a-usb-device-as-the-oracle",content:"Terminate the instance that is either not attached to a USB device or whose mastership file contains 0. Two scenarios are expected:"},{heading:"using-a-usb-device-as-the-oracle",content:"A leader failure occurs, and the remaining instance completes leader election on its own via the Oracle."},{heading:"using-a-usb-device-as-the-oracle",content:"The quorum is maintained via the Oracle."},{heading:"using-a-usb-device-as-the-oracle",content:"Remove the USB device. /Volumes/Oracle/mastership becomes unavailable. According to the current implementation, whenever the Leader queries the oracle and the file is missing, the oracle throws an exception and returns false. Repeating step 5 will result in either the system being unable to recover from a leader failure, or the leader losing the quorum and service being interrupted."},{heading:"using-a-usb-device-as-the-oracle",content:"With these steps, you can observe and practice how the Oracle works with a two-instance system."},{heading:"reference",content:"Tushar Deepak Chandra and Sam Toueg. 1991. Unreliable failure detectors for asynchronous systems (preliminary version). In Proceedings of the tenth annual ACM symposium on Principles of distributed computing (PODC '91). Association for Computing Machinery, New York, NY, USA, 325–340. DOI:10.1145/112600.112627"}],headings:[{id:"hierarchical-quorums",content:"Hierarchical Quorums"},{id:"oracle-quorum",content:"Oracle Quorum"},{id:"the-implementation-of-the-oracle",content:"The Implementation of the Oracle"},{id:"deployment-contexts",content:"Deployment Contexts"},{id:"configuring-the-oracle-in-zoocfg",content:"Configuring the Oracle in zoo.cfg"},{id:"example-zoocfg",content:"Example zoo.cfg"},{id:"behavior-after-enabling-the-oracle",content:"Behavior After Enabling the Oracle"},{id:"considerations-when-using-the-oracle",content:"Considerations When Using the Oracle"},{id:"liveness-issue",content:"Liveness Issue"},{id:"safety-issue",content:"Safety Issue"},{id:"examples-of-failure-detector-implementations",content:"Examples of Failure Detector Implementations"},{id:"hardware-based-implementation",content:"Hardware-based Implementation"},{id:"software-based-implementation",content:"Software-based Implementation"},{id:"using-a-usb-device-as-the-oracle",content:"Using a USB Device as the Oracle"},{id:"reference",content:"Reference"}]};const l=[{depth:2,url:"#hierarchical-quorums",title:e.jsx(e.Fragment,{children:"Hierarchical Quorums"})},{depth:2,url:"#oracle-quorum",title:e.jsx(e.Fragment,{children:"Oracle Quorum"})},{depth:3,url:"#the-implementation-of-the-oracle",title:e.jsx(e.Fragment,{children:"The Implementation of the Oracle"})},{depth:3,url:"#deployment-contexts",title:e.jsx(e.Fragment,{children:"Deployment Contexts"})},{depth:3,url:"#configuring-the-oracle-in-zoocfg",title:e.jsxs(e.Fragment,{children:["Configuring the Oracle in ",e.jsx("code",{children:"zoo.cfg"})]})},{depth:4,url:"#example-zoocfg",title:e.jsxs(e.Fragment,{children:["Example ",e.jsx("code",{children:"zoo.cfg"})]})},{depth:3,url:"#behavior-after-enabling-the-oracle",title:e.jsx(e.Fragment,{children:"Behavior After Enabling the Oracle"})},{depth:3,url:"#considerations-when-using-the-oracle",title:e.jsx(e.Fragment,{children:"Considerations When Using the Oracle"})},{depth:4,url:"#liveness-issue",title:e.jsx(e.Fragment,{children:"Liveness Issue"})},{depth:4,url:"#safety-issue",title:e.jsx(e.Fragment,{children:"Safety Issue"})},{depth:3,url:"#examples-of-failure-detector-implementations",title:e.jsx(e.Fragment,{children:"Examples of Failure Detector Implementations"})},{depth:4,url:"#hardware-based-implementation",title:e.jsx(e.Fragment,{children:"Hardware-based Implementation"})},{depth:4,url:"#software-based-implementation",title:e.jsx(e.Fragment,{children:"Software-based Implementation"})},{depth:4,url:"#using-a-usb-device-as-the-oracle",title:e.jsx(e.Fragment,{children:"Using a USB Device as the Oracle"})},{depth:3,url:"#reference",title:e.jsx(e.Fragment,{children:"Reference"})},{depth:2,url:"#footnote-label",title:e.jsx(e.Fragment,{children:"Footnotes"})}];function s(t){const n={a:"a",blockquote:"blockquote",code:"code",em:"em",h2:"h2",h3:"h3",h4:"h4",li:"li",ol:"ol",p:"p",pre:"pre",section:"section",span:"span",strong:"strong",sup:"sup",ul:"ul",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"hierarchical-quorums",children:"Hierarchical Quorums"}),`
`,e.jsx(n.p,{children:`This document gives an example of how to use hierarchical quorums. The basic idea is
very simple. First, we split servers into groups, and add a line for each group listing
the servers that form this group. Next we have to assign a weight to each server.`}),`
`,e.jsx(n.p,{children:`The following example shows how to configure a system with three groups of three servers
each, and we assign a weight of 1 to each server:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"group.1=1:2:3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"group.2=4:5:6"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"group.3=7:8:9"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.1=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.2=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.3=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.4=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.5=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.6=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.7=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.8=1"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"weight.9=1"})})]})})}),`
`,e.jsx(n.p,{children:`When running the system, we are able to form a quorum once we have a majority of votes from
a majority of non-zero-weight groups. Groups that have zero weight are discarded and not
considered when forming quorums. Looking at the example, we are able to form a quorum once
we have votes from at least two servers from each of two different groups.`}),`
`,e.jsx(n.h2,{id:"oracle-quorum",children:"Oracle Quorum"}),`
`,e.jsx(n.p,{children:`Oracle Quorum increases the availability of a cluster of 2 ZooKeeper instances with a failure detector known as the Oracle.
The Oracle is designed to grant permission to the instance which is the only remaining instance
in a 2-instance configuration when the other instance is identified as faulty by the failure detector.`}),`
`,e.jsx(n.h3,{id:"the-implementation-of-the-oracle",children:"The Implementation of the Oracle"}),`
`,e.jsxs(n.p,{children:["Every instance accesses a file which contains either ",e.jsx(n.code,{children:"0"})," or ",e.jsx(n.code,{children:"1"}),` to indicate whether that instance is authorized by the Oracle.
This design can be changed since failure detector algorithms vary. You can override the `,e.jsx(n.code,{children:"askOracle()"})," method in ",e.jsx(n.em,{children:"QuorumOracleMaj"})," to adapt a preferred way of reading the Oracle's decision."]}),`
`,e.jsx(n.h3,{id:"deployment-contexts",children:"Deployment Contexts"}),`
`,e.jsxs(n.p,{children:["The Oracle is designed to increase the availability of a cluster of ",e.jsx(n.strong,{children:"2"})," ZooKeeper instances; thus, the size of the voting member is ",e.jsx(n.strong,{children:"2"}),`.
In other words, the Oracle solves the consensus problem of a possible faulty instance in a two-instance ensemble.`]}),`
`,e.jsxs(n.p,{children:[`When the size of the voting members exceeds 2, the expected way to make the Oracle work correctly is to reconfigure the cluster size when a faulty machine is identified.
For example, with a configuration of 5 instances, when a faulty machine breaks the connection with the Leader, a `,e.jsx(n.em,{children:"reconfig"}),` client request is expected to re-form the cluster as 4 instances.
Once the size of the voting member equals 2, the configuration falls into the problem domain which the Oracle is designed to address.`]}),`
`,e.jsxs(n.h3,{id:"configuring-the-oracle-in-zoocfg",children:["Configuring the Oracle in ",e.jsx(n.code,{children:"zoo.cfg"})]}),`
`,e.jsxs(n.p,{children:["Regardless of the cluster size, ",e.jsx(n.code,{children:"oraclePath"}),` must be configured at initialization time, like other static parameters.
The following shows the correct way to specify and enable the Oracle:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"oraclePath=/to/some/file"})})})})}),`
`,e.jsxs(n.p,{children:[e.jsx(n.code,{children:"QuorumOracleMaj"}),` reads the result of a failure detector written to a text file — the oracle file.
Suppose you have the result of the failure detector written to `,e.jsx(n.code,{children:"/some/path/result.txt"}),"; the correct configuration is:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"oraclePath=/some/path/result.txt"})})})})}),`
`,e.jsxs(n.p,{children:["The oracle file should contain ",e.jsx(n.code,{children:"1"})," to authorize the instance, or ",e.jsx(n.code,{children:"0"})," to deny it. An example file can be created with:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"echo"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" >"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /some/path/result.txt"})]})})})}),`
`,e.jsxs(n.p,{children:["Any equivalent file is suitable for the current implementation of ",e.jsx(n.code,{children:"QuorumOracleMaj"}),`.
The number of oracle files should equal the number of ZooKeeper instances configured to enable the Oracle.
Each ZooKeeper instance should have its own oracle file — files must not be shared, otherwise the issues described in the Safety section will arise.`]}),`
`,e.jsxs(n.h4,{id:"example-zoocfg",children:["Example ",e.jsx(n.code,{children:"zoo.cfg"})]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLogDir=/datalog"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"autopurge.snapRetainCount=3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"autopurge.purgeInterval=0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"maxClientCnxns=60"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"standaloneEnabled=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"admin.enableServer=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"oraclePath=/chassis/mastership"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=0.0.0.0:2888:3888;2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=hw1:2888:3888;2181"})})]})})}),`
`,e.jsx(n.h3,{id:"behavior-after-enabling-the-oracle",children:"Behavior After Enabling the Oracle"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"QuorumPeerConfig"})," will create an instance of ",e.jsx(n.em,{children:"QuorumOracleMaj"})," instead of the default ",e.jsx(n.code,{children:"QuorumMaj"})," when it reads an ",e.jsx(n.code,{children:"oraclePath"})," in ",e.jsx(n.code,{children:"zoo.cfg"}),`.
`,e.jsx(n.code,{children:"QuorumOracleMaj"})," inherits from ",e.jsx(n.code,{children:"QuorumMaj"})," and differs from its superclass by overriding ",e.jsx(n.code,{children:"containsQuorum()"}),`.
`,e.jsx(n.code,{children:"QuorumOracleMaj"})," executes its version of ",e.jsx(n.code,{children:"containsQuorum"}),` when the Leader loses all of its followers and fails to maintain the quorum.
In all other cases, `,e.jsx(n.em,{children:"QuorumOracleMaj"})," behaves identically to ",e.jsx(n.em,{children:"QuorumMaj"}),"."]}),`
`,e.jsx(n.h3,{id:"considerations-when-using-the-oracle",children:"Considerations When Using the Oracle"}),`
`,e.jsxs(n.p,{children:["We consider an asynchronous distributed system which consists of ",e.jsx(n.strong,{children:"2"})," ZooKeeper instances and an Oracle."]}),`
`,e.jsx(n.h4,{id:"liveness-issue",children:"Liveness Issue"}),`
`,e.jsxs(n.p,{children:["When the Oracle satisfies the following property introduced by ",e.jsx(n.sup,{children:e.jsx(n.a,{href:"#user-content-fn-1",id:"user-content-fnref-1","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),":"]}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Strong Completeness:"})," There is a time after which every process that crashes is permanently suspected by every correct process."]}),`
`]}),`
`,e.jsx(n.p,{children:`Liveness of the system is ensured by the Oracle.
However, when the Oracle fails to maintain this property, loss of liveness is expected. For example:`}),`
`,e.jsx(n.p,{children:"Suppose we have a Leader and a Follower running in the broadcasting state. The system will lose its liveness when:"}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:"The Leader fails, but the Oracle does not detect the faulty Leader — the Oracle will not authorize the Follower to become a new Leader."}),`
`,e.jsx(n.li,{children:"A Follower fails, but the Oracle does not detect the faulty Follower — the Oracle will authorize the Leader to move the system forward."}),`
`]}),`
`,e.jsx(n.h4,{id:"safety-issue",children:"Safety Issue"}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:"Loss of Progress"})}),`
`,e.jsx(n.p,{children:"Progress can be lost when multiple failures occur in the system at different times. For example:"}),`
`,e.jsx(n.p,{children:"Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"T1"})," ",e.jsx(n.code,{children:"zxid(0x1_1)"}),": L-Ben fails, and F-John takes over under authorization from the Oracle."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"T2"})," ",e.jsx(n.code,{children:"zxid(0x2_1)"}),": F-John becomes a new Leader (L-John) and starts a new epoch."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"T3"})," ",e.jsx(n.code,{children:"zxid(0x2_A)"}),": L-John fails."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"T4"})," ",e.jsx(n.code,{children:"zxid(0x2_A)"}),": Ben recovers and starts leader election."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.strong,{children:"T5"})," ",e.jsx(n.code,{children:"zxid(0x3_1)"}),": Ben becomes the new Leader (L-Ben) under authorization from the Oracle."]}),`
`]}),`
`,e.jsx(n.p,{children:"In this case, the system loses its progress after L-Ben initially failed."}),`
`,e.jsxs(n.p,{children:[`However, loss of progress can be prevented by making the Oracle capable of referring to the latest zxid.
When the Oracle can refer to the latest zxid, at T5 `,e.jsx(n.code,{children:"zxid(0x2_A)"}),`, Ben will not complete leader election because the Oracle would not authorize him while John is still known to be ahead.
This trades liveness for safety.`]}),`
`,e.jsx(n.p,{children:e.jsx(n.strong,{children:"Split Brain Issue"})}),`
`,e.jsxs(n.p,{children:["We consider the Oracle satisfies the following property introduced by ",e.jsx(n.sup,{children:e.jsx(n.a,{href:"#user-content-fn-1",id:"user-content-fnref-1-2","data-footnote-ref":!0,"aria-describedby":"footnote-label",children:"1"})}),":"]}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Accuracy:"})," There is a time after which some correct process is never suspected by any process."]}),`
`]}),`
`,e.jsx(n.p,{children:"The decisions the Oracle gives out must be mutually exclusive."}),`
`,e.jsx(n.p,{children:"Suppose we have a Leader (Ben) and a Follower (John) in the broadcasting state:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:"At any time, the Oracle will not authorize both Ben and John simultaneously, even if each failure detector suspects the other."}),`
`,e.jsxs(n.li,{children:["Equivalently: for any two Oracle files, their values must not both be ",e.jsx(n.code,{children:"1"})," at the same time."]}),`
`]}),`
`,e.jsx(n.p,{children:"Split brain is expected when the Oracle fails to maintain this property during leader election, which can happen at:"}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsx(n.li,{children:"System start."}),`
`,e.jsx(n.li,{children:"A failed instance recovering from failure."}),`
`]}),`
`,e.jsx(n.h3,{id:"examples-of-failure-detector-implementations",children:"Examples of Failure Detector Implementations"}),`
`,e.jsx(n.p,{children:"A failure detector's role is to authorize the querying ZooKeeper instance whether it has the right to move the system forward without waiting for the faulty instance."}),`
`,e.jsx(n.h4,{id:"hardware-based-implementation",children:"Hardware-based Implementation"}),`
`,e.jsxs(n.p,{children:["Suppose two dedicated hardware nodes, ",e.jsx(n.code,{children:"hw1"})," and ",e.jsx(n.code,{children:"hw2"}),", host ZooKeeper instances ",e.jsx(n.code,{children:"zk1"})," and ",e.jsx(n.code,{children:"zk2"}),` respectively, forming a cluster.
A hardware device attached to both nodes can determine whether each is powered on.
When `,e.jsx(n.code,{children:"hw1"})," is not powered on, ",e.jsx(n.code,{children:"zk1"}),` is undoubtedly faulty.
The hardware device then updates the oracle file on `,e.jsx(n.code,{children:"hw2"})," to ",e.jsx(n.code,{children:"1"}),", authorizing ",e.jsx(n.code,{children:"zk2"})," to move the system forward."]}),`
`,e.jsx(n.h4,{id:"software-based-implementation",children:"Software-based Implementation"}),`
`,e.jsxs(n.p,{children:["Suppose two dedicated hardware nodes, ",e.jsx(n.code,{children:"hw1"})," and ",e.jsx(n.code,{children:"hw2"}),", host ZooKeeper instances ",e.jsx(n.code,{children:"zk1"})," and ",e.jsx(n.code,{children:"zk2"}),` respectively.
Two services, `,e.jsx(n.code,{children:"o1"})," on ",e.jsx(n.code,{children:"hw1"})," and ",e.jsx(n.code,{children:"o2"})," on ",e.jsx(n.code,{children:"hw2"}),`, detect whether the other node is alive (for example, by pinging).
When `,e.jsx(n.code,{children:"o1"})," cannot ping ",e.jsx(n.code,{children:"hw2"}),", it identifies ",e.jsx(n.code,{children:"hw2"})," as faulty and updates the oracle file of ",e.jsx(n.code,{children:"zk1"})," to ",e.jsx(n.code,{children:"1"}),", authorizing ",e.jsx(n.code,{children:"zk1"})," to move forward."]}),`
`,e.jsx(n.h4,{id:"using-a-usb-device-as-the-oracle",children:"Using a USB Device as the Oracle"}),`
`,e.jsxs(n.p,{children:["In macOS 10.15.7 (19H2), external storage devices are mounted under ",e.jsx(n.code,{children:"/Volumes"}),`.
A USB device containing the required oracle file can serve as the Oracle.
When the device is connected, the oracle authorizes the leader to move the system forward.`]}),`
`,e.jsx(n.p,{children:"The following 6 steps demonstrate how this works:"}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Insert a USB device named ",e.jsx(n.code,{children:"Oracle"}),". The path ",e.jsx(n.code,{children:"/Volumes/Oracle"})," will be accessible."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Create a file containing ",e.jsx(n.code,{children:"1"})," under ",e.jsx(n.code,{children:"/Volumes/Oracle"})," named ",e.jsx(n.code,{children:"mastership"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"echo"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" >"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" mastership"})]})})})}),`
`,e.jsxs(n.p,{children:["The path ",e.jsx(n.code,{children:"/Volumes/Oracle/mastership"})," is now accessible to ZooKeeper instances."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Create a ",e.jsx(n.code,{children:"zoo.cfg"})," like the following:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataDir=/data"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"dataLogDir=/datalog"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"tickTime=2000"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"initLimit=5"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"syncLimit=2"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"autopurge.snapRetainCount=3"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"autopurge.purgeInterval=0"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"maxClientCnxns=60"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"standaloneEnabled=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"admin.enableServer=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"oraclePath=/Volumes/Oracle/mastership"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.1=0.0.0.0:2888:3888;2181"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"server.2=hw1:2888:3888;2181"})})]})})}),`
`,e.jsxs(n.blockquote,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"Note:"})," Split brain will not occur here because there is only a single USB device. ",e.jsx(n.code,{children:"mastership"})," must not be shared by multiple instances — only one ZooKeeper instance should be configured with Oracle. See the ",e.jsx(n.a,{href:"#safety-issue",children:"Safety Issue"})," section for details."]}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsx(n.p,{children:"Start the cluster. It should form a quorum normally."}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Terminate the instance that is either not attached to a USB device or whose ",e.jsx(n.code,{children:"mastership"})," file contains ",e.jsx(n.code,{children:"0"}),". Two scenarios are expected:"]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:"A leader failure occurs, and the remaining instance completes leader election on its own via the Oracle."}),`
`,e.jsx(n.li,{children:"The quorum is maintained via the Oracle."}),`
`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:["Remove the USB device. ",e.jsx(n.code,{children:"/Volumes/Oracle/mastership"})," becomes unavailable. According to the current implementation, whenever the Leader queries the oracle and the file is missing, the oracle throws an exception and returns ",e.jsx(n.code,{children:"false"}),". Repeating step 5 will result in either the system being unable to recover from a leader failure, or the leader losing the quorum and service being interrupted."]}),`
`]}),`
`]}),`
`,e.jsx(n.p,{children:"With these steps, you can observe and practice how the Oracle works with a two-instance system."}),`
`,e.jsx(n.h3,{id:"reference",children:"Reference"}),`
`,e.jsxs(n.section,{"data-footnotes":!0,className:"footnotes",children:[e.jsx(n.h2,{className:"sr-only",id:"footnote-label",children:"Footnotes"}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{id:"user-content-fn-1",children:[`
`,e.jsxs(n.p,{children:["Tushar Deepak Chandra and Sam Toueg. 1991. Unreliable failure detectors for asynchronous systems (preliminary version). In ",e.jsx(n.em,{children:"Proceedings of the tenth annual ACM symposium on Principles of distributed computing"})," (",e.jsx(n.em,{children:"PODC '91"}),"). Association for Computing Machinery, New York, NY, USA, 325–340. ",e.jsx(n.a,{href:"https://doi.org/10.1145/112600.112627",children:"DOI:10.1145/112600.112627"})," ",e.jsx(n.a,{href:"#user-content-fnref-1","data-footnote-backref":"","aria-label":"Back to reference 1",className:"data-footnote-backref",children:"↩"})," ",e.jsxs(n.a,{href:"#user-content-fnref-1-2","data-footnote-backref":"","aria-label":"Back to reference 1-2",className:"data-footnote-backref",children:["↩",e.jsx(n.sup,{children:"2"})]})]}),`
`]}),`
`]}),`
`]})]})}function c(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(s,{...t})}):s(t)}export{a as _markdown,c as default,o as extractedReferences,r as frontmatter,h as structuredData,l as toc};
