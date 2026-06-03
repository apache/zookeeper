import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let s=`Apache ZooKeeper has extensive support for JMX, allowing you
to view and manage a ZooKeeper serving ensemble.

This document assumes that you have basic knowledge of
JMX. See the [Sun JMX Technology](http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/) page to get started with JMX.

See the [JMX Management Guide](http://java.sun.com/javase/6/docs/technotes/guides/management/agent.html) for details on setting up local and
remote management of VM instances. By default the included
*zkServer.sh* supports only local management —
review the linked document to enable support for remote management
(beyond the scope of this document).

## Starting ZooKeeper with JMX Enabled

The class \`org.apache.zookeeper.server.quorum.QuorumPeerMain\`
will start a JMX manageable ZooKeeper server. This class
registers the proper MBeans during initialization to support JMX
monitoring and management of the instance. See *bin/zkServer.sh* for one
example of starting ZooKeeper using QuorumPeerMain.

## Running a JMX Console

There are a number of JMX consoles available which can connect
to the running server. For this example we will use Sun's *jconsole*.

The Java JDK ships with a simple JMX console
named [jconsole](http://java.sun.com/developer/technicalArticles/J2SE/jconsole.html)
which can be used to connect to ZooKeeper and inspect a running
server. Once you've started ZooKeeper using QuorumPeerMain,
start *jconsole*, which typically resides in *JDK\\_HOME/bin/jconsole*.

When the "new connection" window is displayed, either connect
to the local process (if jconsole was started on the same host as the server) or
use the remote process connection.

By default the "overview" tab for the VM is displayed. Select
the "MBeans" tab.

You should now see *org.apache.ZooKeeperService*
on the left hand side. Expand this item and depending on how you've
started the server you will be able to monitor and manage various
service related features.

### Logback MBeans *(new in 3.8.0)*

Logback is the default logging backend of ZooKeeper since version 3.8.0.
It can be configured to register JMX MBeans by adding \`<jmxConfigurator />\` to *logback.xml*. More
information can be found on Logback's [website](https://logback.qos.ch/manual/jmxConfig.html).

### Log4j MBeans *(deprecated)*

ZooKeeper will register log4j MBeans if log4j1 is configured as the logging backend of SLF4J.
In the same section along the left hand side you will see
"log4j". Expand that to manage log4j through JMX. Of particular
interest is the ability to dynamically change the logging levels
used by editing the appender and root thresholds. Log4j MBean
registration can be disabled by passing
*-Dzookeeper.jmx.log4j.disable=true* to the JVM
when starting ZooKeeper. In addition, we can specify the name of
the MBean with the *-Dzookeeper.jmx.log4j.mbean=log4j:hierarchy=default*
option, in case we need to upgrade an integrated system
using the old MBean name (\`log4j:hierarchy = default\`).

## ZooKeeper MBean Reference

### Replicated Ensemble MBeans

This table details JMX for a server participating in a
replicated ZooKeeper ensemble (i.e. not standalone). This is the
typical case for a production environment.

| MBean                | MBean Object Name        | Description                                                                                                                                                                                                                                     |
| -------------------- | ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Quorum               | \`ReplicatedServer_id<#>\` | Represents the Quorum, or Ensemble — parent of all cluster members. Note that the object name includes the "myid" of the server (name suffix) that your JMX agent has connected to.                                                             |
| LocalPeer/RemotePeer | \`replica.<#>\`            | Represents a local or remote peer (i.e. server participating in the ensemble). Note that the object name includes the "myid" of the server (name suffix).                                                                                       |
| LeaderElection       | \`LeaderElection\`         | Represents a ZooKeeper cluster leader election which is in progress. Provides information about the election, such as when it started.                                                                                                          |
| Leader               | \`Leader\`                 | Indicates that the parent replica is the leader and provides attributes/operations for that server. Note that Leader is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node.   |
| Follower             | \`Follower\`               | Indicates that the parent replica is a follower and provides attributes/operations for that server. Note that Follower is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node. |
| DataTree             | \`InMemoryDataTree\`       | Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count). InMemoryDataTrees are children of ZooKeeperServer nodes.                     |
| ServerCnxn           | \`<session_id>\`           | Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form.                                                                         |

### Standalone Server MBeans

This table details JMX for a standalone server. Standalone is typically
only used in development situations.

| MBean           | MBean Object Name          | Description                                                                                                                                                             |
| --------------- | -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ZooKeeperServer | \`StandaloneServer_port<#>\` | Statistics on the running server, also operations to reset these attributes. Note that the object name includes the client port of the server (name suffix).            |
| DataTree        | \`InMemoryDataTree\`         | Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count).      |
| ServerCnxn      | \`<session_id>\`             | Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form. |
`,r={title:"JMX",description:"How to enable and use JMX monitoring and management for ZooKeeper ensembles, including starting with JMX, connecting via jconsole, and a full MBean reference for replicated and standalone servers."},i=[{href:"http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/"},{href:"http://java.sun.com/javase/6/docs/technotes/guides/management/agent.html"},{href:"http://java.sun.com/developer/technicalArticles/J2SE/jconsole.html"},{href:"https://logback.qos.ch/manual/jmxConfig.html"}],c={contents:[{heading:void 0,content:`Apache ZooKeeper has extensive support for JMX, allowing you
to view and manage a ZooKeeper serving ensemble.`},{heading:void 0,content:`This document assumes that you have basic knowledge of
JMX. See the Sun JMX Technology page to get started with JMX.`},{heading:void 0,content:`See the JMX Management Guide for details on setting up local and
remote management of VM instances. By default the included
zkServer.sh supports only local management —
review the linked document to enable support for remote management
(beyond the scope of this document).`},{heading:"starting-zookeeper-with-jmx-enabled",content:`The class org.apache.zookeeper.server.quorum.QuorumPeerMain
will start a JMX manageable ZooKeeper server. This class
registers the proper MBeans during initialization to support JMX
monitoring and management of the instance. See bin/zkServer.sh for one
example of starting ZooKeeper using QuorumPeerMain.`},{heading:"running-a-jmx-console",content:`There are a number of JMX consoles available which can connect
to the running server. For this example we will use Sun's jconsole.`},{heading:"running-a-jmx-console",content:`The Java JDK ships with a simple JMX console
named jconsole
which can be used to connect to ZooKeeper and inspect a running
server. Once you've started ZooKeeper using QuorumPeerMain,
start jconsole, which typically resides in JDK_HOME/bin/jconsole.`},{heading:"running-a-jmx-console",content:`When the "new connection" window is displayed, either connect
to the local process (if jconsole was started on the same host as the server) or
use the remote process connection.`},{heading:"running-a-jmx-console",content:`By default the "overview" tab for the VM is displayed. Select
the "MBeans" tab.`},{heading:"running-a-jmx-console",content:`You should now see org.apache.ZooKeeperService
on the left hand side. Expand this item and depending on how you've
started the server you will be able to monitor and manage various
service related features.`},{heading:"logback-mbeans-new-in-380",content:`Logback is the default logging backend of ZooKeeper since version 3.8.0.
It can be configured to register JMX MBeans by adding <jmxConfigurator /> to logback.xml. More
information can be found on Logback's website.`},{heading:"log4j-mbeans-deprecated",content:`ZooKeeper will register log4j MBeans if log4j1 is configured as the logging backend of SLF4J.
In the same section along the left hand side you will see
"log4j". Expand that to manage log4j through JMX. Of particular
interest is the ability to dynamically change the logging levels
used by editing the appender and root thresholds. Log4j MBean
registration can be disabled by passing
-Dzookeeper.jmx.log4j.disable=true to the JVM
when starting ZooKeeper. In addition, we can specify the name of
the MBean with the -Dzookeeper.jmx.log4j.mbean=log4j:hierarchy=default
option, in case we need to upgrade an integrated system
using the old MBean name (log4j:hierarchy = default).`},{heading:"replicated-ensemble-mbeans",content:`This table details JMX for a server participating in a
replicated ZooKeeper ensemble (i.e. not standalone). This is the
typical case for a production environment.`},{heading:"replicated-ensemble-mbeans",content:"MBean"},{heading:"replicated-ensemble-mbeans",content:"MBean Object Name"},{heading:"replicated-ensemble-mbeans",content:"Description"},{heading:"replicated-ensemble-mbeans",content:"Quorum"},{heading:"replicated-ensemble-mbeans",content:"ReplicatedServer_id<#>"},{heading:"replicated-ensemble-mbeans",content:'Represents the Quorum, or Ensemble — parent of all cluster members. Note that the object name includes the "myid" of the server (name suffix) that your JMX agent has connected to.'},{heading:"replicated-ensemble-mbeans",content:"LocalPeer/RemotePeer"},{heading:"replicated-ensemble-mbeans",content:"replica.<#>"},{heading:"replicated-ensemble-mbeans",content:'Represents a local or remote peer (i.e. server participating in the ensemble). Note that the object name includes the "myid" of the server (name suffix).'},{heading:"replicated-ensemble-mbeans",content:"LeaderElection"},{heading:"replicated-ensemble-mbeans",content:"LeaderElection"},{heading:"replicated-ensemble-mbeans",content:"Represents a ZooKeeper cluster leader election which is in progress. Provides information about the election, such as when it started."},{heading:"replicated-ensemble-mbeans",content:"Leader"},{heading:"replicated-ensemble-mbeans",content:"Leader"},{heading:"replicated-ensemble-mbeans",content:"Indicates that the parent replica is the leader and provides attributes/operations for that server. Note that Leader is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node."},{heading:"replicated-ensemble-mbeans",content:"Follower"},{heading:"replicated-ensemble-mbeans",content:"Follower"},{heading:"replicated-ensemble-mbeans",content:"Indicates that the parent replica is a follower and provides attributes/operations for that server. Note that Follower is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node."},{heading:"replicated-ensemble-mbeans",content:"DataTree"},{heading:"replicated-ensemble-mbeans",content:"InMemoryDataTree"},{heading:"replicated-ensemble-mbeans",content:"Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count). InMemoryDataTrees are children of ZooKeeperServer nodes."},{heading:"replicated-ensemble-mbeans",content:"ServerCnxn"},{heading:"replicated-ensemble-mbeans",content:"<session_id>"},{heading:"replicated-ensemble-mbeans",content:"Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form."},{heading:"standalone-server-mbeans",content:`This table details JMX for a standalone server. Standalone is typically
only used in development situations.`},{heading:"standalone-server-mbeans",content:"MBean"},{heading:"standalone-server-mbeans",content:"MBean Object Name"},{heading:"standalone-server-mbeans",content:"Description"},{heading:"standalone-server-mbeans",content:"ZooKeeperServer"},{heading:"standalone-server-mbeans",content:"StandaloneServer_port<#>"},{heading:"standalone-server-mbeans",content:"Statistics on the running server, also operations to reset these attributes. Note that the object name includes the client port of the server (name suffix)."},{heading:"standalone-server-mbeans",content:"DataTree"},{heading:"standalone-server-mbeans",content:"InMemoryDataTree"},{heading:"standalone-server-mbeans",content:"Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count)."},{heading:"standalone-server-mbeans",content:"ServerCnxn"},{heading:"standalone-server-mbeans",content:"<session_id>"},{heading:"standalone-server-mbeans",content:"Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form."}],headings:[{id:"starting-zookeeper-with-jmx-enabled",content:"Starting ZooKeeper with JMX Enabled"},{id:"running-a-jmx-console",content:"Running a JMX Console"},{id:"logback-mbeans-new-in-380",content:"Logback MBeans (new in 3.8.0)"},{id:"log4j-mbeans-deprecated",content:"Log4j MBeans (deprecated)"},{id:"zookeeper-mbean-reference",content:"ZooKeeper MBean Reference"},{id:"replicated-ensemble-mbeans",content:"Replicated Ensemble MBeans"},{id:"standalone-server-mbeans",content:"Standalone Server MBeans"}]};const l=[{depth:2,url:"#starting-zookeeper-with-jmx-enabled",title:e.jsx(e.Fragment,{children:"Starting ZooKeeper with JMX Enabled"})},{depth:2,url:"#running-a-jmx-console",title:e.jsx(e.Fragment,{children:"Running a JMX Console"})},{depth:3,url:"#logback-mbeans-new-in-380",title:e.jsxs(e.Fragment,{children:["Logback MBeans ",e.jsx("em",{children:"(new in 3.8.0)"})]})},{depth:3,url:"#log4j-mbeans-deprecated",title:e.jsxs(e.Fragment,{children:["Log4j MBeans ",e.jsx("em",{children:"(deprecated)"})]})},{depth:2,url:"#zookeeper-mbean-reference",title:e.jsx(e.Fragment,{children:"ZooKeeper MBean Reference"})},{depth:3,url:"#replicated-ensemble-mbeans",title:e.jsx(e.Fragment,{children:"Replicated Ensemble MBeans"})},{depth:3,url:"#standalone-server-mbeans",title:e.jsx(e.Fragment,{children:"Standalone Server MBeans"})}];function o(t){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",p:"p",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`Apache ZooKeeper has extensive support for JMX, allowing you
to view and manage a ZooKeeper serving ensemble.`}),`
`,e.jsxs(n.p,{children:[`This document assumes that you have basic knowledge of
JMX. See the `,e.jsx(n.a,{href:"http://java.sun.com/javase/technologies/core/mntr-mgmt/javamanagement/",children:"Sun JMX Technology"})," page to get started with JMX."]}),`
`,e.jsxs(n.p,{children:["See the ",e.jsx(n.a,{href:"http://java.sun.com/javase/6/docs/technotes/guides/management/agent.html",children:"JMX Management Guide"}),` for details on setting up local and
remote management of VM instances. By default the included
`,e.jsx(n.em,{children:"zkServer.sh"}),` supports only local management —
review the linked document to enable support for remote management
(beyond the scope of this document).`]}),`
`,e.jsx(n.h2,{id:"starting-zookeeper-with-jmx-enabled",children:"Starting ZooKeeper with JMX Enabled"}),`
`,e.jsxs(n.p,{children:["The class ",e.jsx(n.code,{children:"org.apache.zookeeper.server.quorum.QuorumPeerMain"}),`
will start a JMX manageable ZooKeeper server. This class
registers the proper MBeans during initialization to support JMX
monitoring and management of the instance. See `,e.jsx(n.em,{children:"bin/zkServer.sh"}),` for one
example of starting ZooKeeper using QuorumPeerMain.`]}),`
`,e.jsx(n.h2,{id:"running-a-jmx-console",children:"Running a JMX Console"}),`
`,e.jsxs(n.p,{children:[`There are a number of JMX consoles available which can connect
to the running server. For this example we will use Sun's `,e.jsx(n.em,{children:"jconsole"}),"."]}),`
`,e.jsxs(n.p,{children:[`The Java JDK ships with a simple JMX console
named `,e.jsx(n.a,{href:"http://java.sun.com/developer/technicalArticles/J2SE/jconsole.html",children:"jconsole"}),`
which can be used to connect to ZooKeeper and inspect a running
server. Once you've started ZooKeeper using QuorumPeerMain,
start `,e.jsx(n.em,{children:"jconsole"}),", which typically resides in ",e.jsx(n.em,{children:"JDK_HOME/bin/jconsole"}),"."]}),`
`,e.jsx(n.p,{children:`When the "new connection" window is displayed, either connect
to the local process (if jconsole was started on the same host as the server) or
use the remote process connection.`}),`
`,e.jsx(n.p,{children:`By default the "overview" tab for the VM is displayed. Select
the "MBeans" tab.`}),`
`,e.jsxs(n.p,{children:["You should now see ",e.jsx(n.em,{children:"org.apache.ZooKeeperService"}),`
on the left hand side. Expand this item and depending on how you've
started the server you will be able to monitor and manage various
service related features.`]}),`
`,e.jsxs(n.h3,{id:"logback-mbeans-new-in-380",children:["Logback MBeans ",e.jsx(n.em,{children:"(new in 3.8.0)"})]}),`
`,e.jsxs(n.p,{children:[`Logback is the default logging backend of ZooKeeper since version 3.8.0.
It can be configured to register JMX MBeans by adding `,e.jsx(n.code,{children:"<jmxConfigurator />"})," to ",e.jsx(n.em,{children:"logback.xml"}),`. More
information can be found on Logback's `,e.jsx(n.a,{href:"https://logback.qos.ch/manual/jmxConfig.html",children:"website"}),"."]}),`
`,e.jsxs(n.h3,{id:"log4j-mbeans-deprecated",children:["Log4j MBeans ",e.jsx(n.em,{children:"(deprecated)"})]}),`
`,e.jsxs(n.p,{children:[`ZooKeeper will register log4j MBeans if log4j1 is configured as the logging backend of SLF4J.
In the same section along the left hand side you will see
"log4j". Expand that to manage log4j through JMX. Of particular
interest is the ability to dynamically change the logging levels
used by editing the appender and root thresholds. Log4j MBean
registration can be disabled by passing
`,e.jsx(n.em,{children:"-Dzookeeper.jmx.log4j.disable=true"}),` to the JVM
when starting ZooKeeper. In addition, we can specify the name of
the MBean with the `,e.jsx(n.em,{children:"-Dzookeeper.jmx.log4j.mbean=log4j:hierarchy=default"}),`
option, in case we need to upgrade an integrated system
using the old MBean name (`,e.jsx(n.code,{children:"log4j:hierarchy = default"}),")."]}),`
`,e.jsx(n.h2,{id:"zookeeper-mbean-reference",children:"ZooKeeper MBean Reference"}),`
`,e.jsx(n.h3,{id:"replicated-ensemble-mbeans",children:"Replicated Ensemble MBeans"}),`
`,e.jsx(n.p,{children:`This table details JMX for a server participating in a
replicated ZooKeeper ensemble (i.e. not standalone). This is the
typical case for a production environment.`}),`
`,e.jsxs(n.table,{children:[e.jsx(n.thead,{children:e.jsxs(n.tr,{children:[e.jsx(n.th,{children:"MBean"}),e.jsx(n.th,{children:"MBean Object Name"}),e.jsx(n.th,{children:"Description"})]})}),e.jsxs(n.tbody,{children:[e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Quorum"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"ReplicatedServer_id<#>"})}),e.jsx(n.td,{children:'Represents the Quorum, or Ensemble — parent of all cluster members. Note that the object name includes the "myid" of the server (name suffix) that your JMX agent has connected to.'})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"LocalPeer/RemotePeer"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"replica.<#>"})}),e.jsx(n.td,{children:'Represents a local or remote peer (i.e. server participating in the ensemble). Note that the object name includes the "myid" of the server (name suffix).'})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"LeaderElection"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"LeaderElection"})}),e.jsx(n.td,{children:"Represents a ZooKeeper cluster leader election which is in progress. Provides information about the election, such as when it started."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Leader"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"Leader"})}),e.jsx(n.td,{children:"Indicates that the parent replica is the leader and provides attributes/operations for that server. Note that Leader is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"Follower"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"Follower"})}),e.jsx(n.td,{children:"Indicates that the parent replica is a follower and provides attributes/operations for that server. Note that Follower is a subclass of ZooKeeperServer, so it provides all of the information normally associated with a ZooKeeperServer node."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"DataTree"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"InMemoryDataTree"})}),e.jsx(n.td,{children:"Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count). InMemoryDataTrees are children of ZooKeeperServer nodes."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"ServerCnxn"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"<session_id>"})}),e.jsx(n.td,{children:"Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form."})]})]})]}),`
`,e.jsx(n.h3,{id:"standalone-server-mbeans",children:"Standalone Server MBeans"}),`
`,e.jsx(n.p,{children:`This table details JMX for a standalone server. Standalone is typically
only used in development situations.`}),`
`,e.jsxs(n.table,{children:[e.jsx(n.thead,{children:e.jsxs(n.tr,{children:[e.jsx(n.th,{children:"MBean"}),e.jsx(n.th,{children:"MBean Object Name"}),e.jsx(n.th,{children:"Description"})]})}),e.jsxs(n.tbody,{children:[e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"ZooKeeperServer"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"StandaloneServer_port<#>"})}),e.jsx(n.td,{children:"Statistics on the running server, also operations to reset these attributes. Note that the object name includes the client port of the server (name suffix)."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"DataTree"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"InMemoryDataTree"})}),e.jsx(n.td,{children:"Statistics on the in-memory znode database, also operations to access finer (and more computationally intensive) statistics on the data (such as ephemeral count)."})]}),e.jsxs(n.tr,{children:[e.jsx(n.td,{children:"ServerCnxn"}),e.jsx(n.td,{children:e.jsx(n.code,{children:"<session_id>"})}),e.jsx(n.td,{children:"Statistics on each client connection, also operations on those connections (such as termination). Note the object name is the session id of the connection in hex form."})]})]})]})]})}function d(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(o,{...t})}):o(t)}export{s as _markdown,d as default,i as extractedReferences,r as frontmatter,c as structuredData,l as toc};
