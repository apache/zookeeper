import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let i=`## Maintenance

Little long term maintenance is required for a ZooKeeper
cluster however you must be aware of the following:

### Ongoing Data Directory Cleanup

The ZooKeeper [Data
Directory](/admin-ops/administrators-guide/data-file-management#the-data-directory) contains files which are a persistent copy
of the znodes stored by a particular serving ensemble. These
are the snapshot and transactional log files. As changes are
made to the znodes these changes are appended to a
transaction log. Occasionally, when a log grows large, a
snapshot of the current state of all znodes will be written
to the filesystem and a new transaction log file is created
for future transactions. During snapshotting, ZooKeeper may
continue appending incoming transactions to the old log file.
Therefore, some transactions which are newer than a snapshot
may be found in the last transaction log preceding the
snapshot.

A ZooKeeper server **will not remove
old snapshots and log files** when using the default
configuration (see autopurge below), this is the
responsibility of the operator. Every serving environment is
different and therefore the requirements of managing these
files may differ from install to install (backup for example).

The PurgeTxnLog utility implements a simple retention
policy that administrators can use. The [API docs](/apidocs/zookeeper-server/index.html) contains details on
calling conventions (arguments, etc...).

In the following example the last count snapshots and
their corresponding logs are retained and the others are
deleted. The value of \\<count> should typically be
greater than 3 (although not required, this provides 3 backups
in the unlikely event a recent log has become corrupted). This
can be run as a cron job on the ZooKeeper server machines to
clean up the logs daily.

\`\`\`bash
$ java -cp zookeeper.jar:lib/slf4j-api-1.7.30.jar:lib/logback-classic-1.2.10.jar:lib/logback-core-1.2.10.jar:conf org.apache.zookeeper.server.PurgeTxnLog <dataDir> <snapDir> -n <count>
\`\`\`

Automatic purging of the snapshots and corresponding
transaction logs was introduced in version 3.4.0 and can be
enabled via the following configuration parameters **autopurge.snapRetainCount** and **autopurge.purgeInterval**. For more on
this, see [Advanced Configuration](/admin-ops/administrators-guide/configuration-parameters#advanced-configuration).

### Debug Log Cleanup (logback)

See the section on [logging](#logging) in this document. It is
expected that you will setup a rolling file appender using the
in-built logback feature. The sample configuration file in the
release tar's \`conf/logback.xml\` provides an example of
this.

## Supervision

You will want to have a supervisory process that manages
each of your ZooKeeper server processes (JVM). The ZK server is
designed to be "fail fast" meaning that it will shut down
(process exit) if an error occurs that it cannot recover
from. As a ZooKeeper serving cluster is highly reliable, this
means that while the server may go down the cluster as a whole
is still active and serving requests. Additionally, as the
cluster is "self healing" the failed server once restarted will
automatically rejoin the ensemble w/o any manual
interaction.

Having a supervisory process such as [daemontools](http://cr.yp.to/daemontools.html) or
[SMF](http://en.wikipedia.org/wiki/Service_Management_Facility)
(other options for supervisory process are also available, it's
up to you which one you would like to use, these are just two
examples) managing your ZooKeeper server ensures that if the
process does exit abnormally it will automatically be restarted
and will quickly rejoin the cluster.

It is also recommended to configure the ZooKeeper server process to
terminate and dump its heap if an OutOfMemoryError\\*\\* occurs. This is achieved
by launching the JVM with the following arguments on Linux and Windows
respectively. The *zkServer.sh* and
\\*zkServer.cmd\\* scripts that ship with ZooKeeper set
these options.

\`\`\`
-XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p'

"-XX:+HeapDumpOnOutOfMemoryError" "-XX:OnOutOfMemoryError=cmd /c taskkill /pid %%%%p /t /f"
\`\`\`

## Monitoring

The ZooKeeper service can be monitored in one of three primary ways:

* the command port through the use of [4 letter words](/admin-ops/administrators-guide/commands#the-four-letter-words)
* with [JMX](/admin-ops/jmx)
* using the [\`zkServer.sh status\` command](/admin-ops/tools#zkserversh)

## Logging

ZooKeeper uses **[SLF4J](http://www.slf4j.org)**
version 1.7 as its logging infrastructure. By default ZooKeeper is shipped with
**[LOGBack](http://logback.qos.ch/)** as the logging backend, but you can use
any other supported logging framework of your choice.

The ZooKeeper default *logback.xml*
file resides in the *conf* directory. Logback requires that
*logback.xml* either be in the working directory
(the directory from which ZooKeeper is run) or be accessible from the classpath.

For more information about SLF4J, see
[its manual](http://www.slf4j.org/manual.html).

For more information about Logback, see
[Logback website](http://logback.qos.ch/).

## Troubleshooting

* *Server not coming up because of file corruption* :
  A server might not be able to read its database and fail to come up because of
  some file corruption in the transaction logs of the ZooKeeper server. You will
  see some IOException on loading ZooKeeper database. In such a case,
  make sure all the other servers in your ensemble are up and working. Use "stat"
  command on the command port to see if they are in good health. After you have verified that
  all the other servers of the ensemble are up, you can go ahead and clean the database
  of the corrupt server. Delete all the files in datadir/version-2 and datalogdir/version-2/.
  Restart the server.

## Metrics Providers

**New in 3.6.0:** The following options are used to configure metrics.

By default ZooKeeper server exposes useful metrics using the [AdminServer](/admin-ops/administrators-guide/commands#the-adminserver)
and [Four Letter Words](/admin-ops/administrators-guide/commands#the-four-letter-words) interface.

Since 3.6.0 you can configure a different Metrics Provider, that exports metrics
to your favourite system.

Since 3.6.0 ZooKeeper binary package bundles an integration with [Prometheus.io](https://prometheus.io)

* *metricsProvider.className* :
  Set to "org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider" to
  enable Prometheus.io exporter.
* *metricsProvider.httpHost* :
  **New in 3.8.0:** Prometheus.io exporter will start a Jetty server and listen this address, default is "0.0.0.0"
* *metricsProvider.httpPort* :
  Prometheus.io exporter will start a Jetty server and bind to this port, it defaults to 7000.
  Prometheus end point will be [http://hostname:httPort/metrics](http://hostname:httPort/metrics).
* *metricsProvider.exportJvmInfo* :
  If this property is set to **true** Prometheus.io will export useful metrics about the JVM.
  The default is true.
* *metricsProvider.numWorkerThreads* :
  **New in 3.7.1:**
  Number of worker threads for reporting Prometheus summary metrics.
  Default value is 1.
  If the number is less than 1, the main thread will be used.
* *metricsProvider.maxQueueSize* :
  **New in 3.7.1:**
  The max queue size for Prometheus summary metrics reporting task.
  Default value is 10000.
* *metricsProvider.workerShutdownTimeoutMs* :
  **New in 3.7.1:**
  The timeout in ms for Prometheus worker threads shutdown.
  Default value is 1000ms.
`,s={title:"Administration",description:"Operational guidance for running a ZooKeeper cluster, including provisioning, maintenance tasks, data directory cleanup, supervision, monitoring, logging, and troubleshooting."},a=[{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory"},{href:"/apidocs/zookeeper-server/index.html"},{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration"},{href:"#logging"},{href:"http://cr.yp.to/daemontools.html"},{href:"http://en.wikipedia.org/wiki/Service_Management_Facility"},{href:"/admin-ops/administrators-guide/commands#the-four-letter-words"},{href:"/admin-ops/jmx"},{href:"/admin-ops/tools#zkserversh"},{href:"http://www.slf4j.org"},{href:"http://logback.qos.ch/"},{href:"http://www.slf4j.org/manual.html"},{href:"http://logback.qos.ch/"},{href:"/admin-ops/administrators-guide/commands#the-adminserver"},{href:"/admin-ops/administrators-guide/commands#the-four-letter-words"},{href:"https://prometheus.io"},{href:"http://hostname:httPort/metrics"}],h={contents:[{heading:"maintenance",content:`Little long term maintenance is required for a ZooKeeper
cluster however you must be aware of the following:`},{heading:"ongoing-data-directory-cleanup",content:`The ZooKeeper Data
Directory contains files which are a persistent copy
of the znodes stored by a particular serving ensemble. These
are the snapshot and transactional log files. As changes are
made to the znodes these changes are appended to a
transaction log. Occasionally, when a log grows large, a
snapshot of the current state of all znodes will be written
to the filesystem and a new transaction log file is created
for future transactions. During snapshotting, ZooKeeper may
continue appending incoming transactions to the old log file.
Therefore, some transactions which are newer than a snapshot
may be found in the last transaction log preceding the
snapshot.`},{heading:"ongoing-data-directory-cleanup",content:`A ZooKeeper server will not remove
old snapshots and log files when using the default
configuration (see autopurge below), this is the
responsibility of the operator. Every serving environment is
different and therefore the requirements of managing these
files may differ from install to install (backup for example).`},{heading:"ongoing-data-directory-cleanup",content:`The PurgeTxnLog utility implements a simple retention
policy that administrators can use. The API docs contains details on
calling conventions (arguments, etc...).`},{heading:"ongoing-data-directory-cleanup",content:`In the following example the last count snapshots and
their corresponding logs are retained and the others are
deleted. The value of <count> should typically be
greater than 3 (although not required, this provides 3 backups
in the unlikely event a recent log has become corrupted). This
can be run as a cron job on the ZooKeeper server machines to
clean up the logs daily.`},{heading:"ongoing-data-directory-cleanup",content:`Automatic purging of the snapshots and corresponding
transaction logs was introduced in version 3.4.0 and can be
enabled via the following configuration parameters autopurge.snapRetainCount and autopurge.purgeInterval. For more on
this, see Advanced Configuration.`},{heading:"debug-log-cleanup-logback",content:`See the section on logging in this document. It is
expected that you will setup a rolling file appender using the
in-built logback feature. The sample configuration file in the
release tar's conf/logback.xml provides an example of
this.`},{heading:"supervision",content:`You will want to have a supervisory process that manages
each of your ZooKeeper server processes (JVM). The ZK server is
designed to be "fail fast" meaning that it will shut down
(process exit) if an error occurs that it cannot recover
from. As a ZooKeeper serving cluster is highly reliable, this
means that while the server may go down the cluster as a whole
is still active and serving requests. Additionally, as the
cluster is "self healing" the failed server once restarted will
automatically rejoin the ensemble w/o any manual
interaction.`},{heading:"supervision",content:`Having a supervisory process such as daemontools or
SMF
(other options for supervisory process are also available, it's
up to you which one you would like to use, these are just two
examples) managing your ZooKeeper server ensures that if the
process does exit abnormally it will automatically be restarted
and will quickly rejoin the cluster.`},{heading:"supervision",content:`It is also recommended to configure the ZooKeeper server process to
terminate and dump its heap if an OutOfMemoryError** occurs. This is achieved
by launching the JVM with the following arguments on Linux and Windows
respectively. The zkServer.sh and
*zkServer.cmd* scripts that ship with ZooKeeper set
these options.`},{heading:"monitoring",content:"The ZooKeeper service can be monitored in one of three primary ways:"},{heading:"monitoring",content:"the command port through the use of 4 letter words"},{heading:"monitoring",content:"with JMX"},{heading:"monitoring",content:"using the zkServer.sh status command"},{heading:"logging",content:`ZooKeeper uses SLF4J
version 1.7 as its logging infrastructure. By default ZooKeeper is shipped with
LOGBack as the logging backend, but you can use
any other supported logging framework of your choice.`},{heading:"logging",content:`The ZooKeeper default logback.xml
file resides in the conf directory. Logback requires that
logback.xml either be in the working directory
(the directory from which ZooKeeper is run) or be accessible from the classpath.`},{heading:"logging",content:`For more information about SLF4J, see
its manual.`},{heading:"logging",content:`For more information about Logback, see
Logback website.`},{heading:"troubleshooting",content:`Server not coming up because of file corruption :
A server might not be able to read its database and fail to come up because of
some file corruption in the transaction logs of the ZooKeeper server. You will
see some IOException on loading ZooKeeper database. In such a case,
make sure all the other servers in your ensemble are up and working. Use "stat"
command on the command port to see if they are in good health. After you have verified that
all the other servers of the ensemble are up, you can go ahead and clean the database
of the corrupt server. Delete all the files in datadir/version-2 and datalogdir/version-2/.
Restart the server.`},{heading:"metrics-providers",content:"New in 3.6.0: The following options are used to configure metrics."},{heading:"metrics-providers",content:`By default ZooKeeper server exposes useful metrics using the AdminServer
and Four Letter Words interface.`},{heading:"metrics-providers",content:`Since 3.6.0 you can configure a different Metrics Provider, that exports metrics
to your favourite system.`},{heading:"metrics-providers",content:"Since 3.6.0 ZooKeeper binary package bundles an integration with Prometheus.io"},{heading:"metrics-providers",content:`metricsProvider.className :
Set to "org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider" to
enable Prometheus.io exporter.`},{heading:"metrics-providers",content:`metricsProvider.httpHost :
New in 3.8.0: Prometheus.io exporter will start a Jetty server and listen this address, default is "0.0.0.0"`},{heading:"metrics-providers",content:`metricsProvider.httpPort :
Prometheus.io exporter will start a Jetty server and bind to this port, it defaults to 7000.
Prometheus end point will be http://hostname:httPort/metrics.`},{heading:"metrics-providers",content:`metricsProvider.exportJvmInfo :
If this property is set to true Prometheus.io will export useful metrics about the JVM.
The default is true.`},{heading:"metrics-providers",content:`metricsProvider.numWorkerThreads :
New in 3.7.1:
Number of worker threads for reporting Prometheus summary metrics.
Default value is 1.
If the number is less than 1, the main thread will be used.`},{heading:"metrics-providers",content:`metricsProvider.maxQueueSize :
New in 3.7.1:
The max queue size for Prometheus summary metrics reporting task.
Default value is 10000.`},{heading:"metrics-providers",content:`metricsProvider.workerShutdownTimeoutMs :
New in 3.7.1:
The timeout in ms for Prometheus worker threads shutdown.
Default value is 1000ms.`}],headings:[{id:"maintenance",content:"Maintenance"},{id:"ongoing-data-directory-cleanup",content:"Ongoing Data Directory Cleanup"},{id:"debug-log-cleanup-logback",content:"Debug Log Cleanup (logback)"},{id:"supervision",content:"Supervision"},{id:"monitoring",content:"Monitoring"},{id:"logging",content:"Logging"},{id:"troubleshooting",content:"Troubleshooting"},{id:"metrics-providers",content:"Metrics Providers"}]};const l=[{depth:2,url:"#maintenance",title:e.jsx(e.Fragment,{children:"Maintenance"})},{depth:3,url:"#ongoing-data-directory-cleanup",title:e.jsx(e.Fragment,{children:"Ongoing Data Directory Cleanup"})},{depth:3,url:"#debug-log-cleanup-logback",title:e.jsx(e.Fragment,{children:"Debug Log Cleanup (logback)"})},{depth:2,url:"#supervision",title:e.jsx(e.Fragment,{children:"Supervision"})},{depth:2,url:"#monitoring",title:e.jsx(e.Fragment,{children:"Monitoring"})},{depth:2,url:"#logging",title:e.jsx(e.Fragment,{children:"Logging"})},{depth:2,url:"#troubleshooting",title:e.jsx(e.Fragment,{children:"Troubleshooting"})},{depth:2,url:"#metrics-providers",title:e.jsx(e.Fragment,{children:"Metrics Providers"})}];function r(t){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(n.h2,{id:"maintenance",children:"Maintenance"}),`
`,e.jsx(n.p,{children:`Little long term maintenance is required for a ZooKeeper
cluster however you must be aware of the following:`}),`
`,e.jsx(n.h3,{id:"ongoing-data-directory-cleanup",children:"Ongoing Data Directory Cleanup"}),`
`,e.jsxs(n.p,{children:["The ZooKeeper ",e.jsx(n.a,{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory",children:`Data
Directory`}),` contains files which are a persistent copy
of the znodes stored by a particular serving ensemble. These
are the snapshot and transactional log files. As changes are
made to the znodes these changes are appended to a
transaction log. Occasionally, when a log grows large, a
snapshot of the current state of all znodes will be written
to the filesystem and a new transaction log file is created
for future transactions. During snapshotting, ZooKeeper may
continue appending incoming transactions to the old log file.
Therefore, some transactions which are newer than a snapshot
may be found in the last transaction log preceding the
snapshot.`]}),`
`,e.jsxs(n.p,{children:["A ZooKeeper server ",e.jsx(n.strong,{children:`will not remove
old snapshots and log files`}),` when using the default
configuration (see autopurge below), this is the
responsibility of the operator. Every serving environment is
different and therefore the requirements of managing these
files may differ from install to install (backup for example).`]}),`
`,e.jsxs(n.p,{children:[`The PurgeTxnLog utility implements a simple retention
policy that administrators can use. The `,e.jsx(n.a,{href:"/apidocs/zookeeper-server/index.html",children:"API docs"}),` contains details on
calling conventions (arguments, etc...).`]}),`
`,e.jsx(n.p,{children:`In the following example the last count snapshots and
their corresponding logs are retained and the others are
deleted. The value of <count> should typically be
greater than 3 (although not required, this provides 3 backups
in the unlikely event a recent log has become corrupted). This
can be run as a cron job on the ZooKeeper server machines to
clean up the logs daily.`}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsxs(n.span,{className:"line",children:[e.jsx(n.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" java"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -cp"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.jar:lib/slf4j-api-1.7.30.jar:lib/logback-classic-1.2.10.jar:lib/logback-core-1.2.10.jar:conf"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" org.apache.zookeeper.server.PurgeTxnLog"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"dataDi"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"r"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"snapDi"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"r"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(n.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -n"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(n.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"coun"}),e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"t"}),e.jsx(n.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"})]})})})}),`
`,e.jsxs(n.p,{children:[`Automatic purging of the snapshots and corresponding
transaction logs was introduced in version 3.4.0 and can be
enabled via the following configuration parameters `,e.jsx(n.strong,{children:"autopurge.snapRetainCount"})," and ",e.jsx(n.strong,{children:"autopurge.purgeInterval"}),`. For more on
this, see `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration",children:"Advanced Configuration"}),"."]}),`
`,e.jsx(n.h3,{id:"debug-log-cleanup-logback",children:"Debug Log Cleanup (logback)"}),`
`,e.jsxs(n.p,{children:["See the section on ",e.jsx(n.a,{href:"#logging",children:"logging"}),` in this document. It is
expected that you will setup a rolling file appender using the
in-built logback feature. The sample configuration file in the
release tar's `,e.jsx(n.code,{children:"conf/logback.xml"}),` provides an example of
this.`]}),`
`,e.jsx(n.h2,{id:"supervision",children:"Supervision"}),`
`,e.jsx(n.p,{children:`You will want to have a supervisory process that manages
each of your ZooKeeper server processes (JVM). The ZK server is
designed to be "fail fast" meaning that it will shut down
(process exit) if an error occurs that it cannot recover
from. As a ZooKeeper serving cluster is highly reliable, this
means that while the server may go down the cluster as a whole
is still active and serving requests. Additionally, as the
cluster is "self healing" the failed server once restarted will
automatically rejoin the ensemble w/o any manual
interaction.`}),`
`,e.jsxs(n.p,{children:["Having a supervisory process such as ",e.jsx(n.a,{href:"http://cr.yp.to/daemontools.html",children:"daemontools"}),` or
`,e.jsx(n.a,{href:"http://en.wikipedia.org/wiki/Service_Management_Facility",children:"SMF"}),`
(other options for supervisory process are also available, it's
up to you which one you would like to use, these are just two
examples) managing your ZooKeeper server ensures that if the
process does exit abnormally it will automatically be restarted
and will quickly rejoin the cluster.`]}),`
`,e.jsxs(n.p,{children:[`It is also recommended to configure the ZooKeeper server process to
terminate and dump its heap if an OutOfMemoryError** occurs. This is achieved
by launching the JVM with the following arguments on Linux and Windows
respectively. The `,e.jsx(n.em,{children:"zkServer.sh"}),` and
*zkServer.cmd* scripts that ship with ZooKeeper set
these options.`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"-XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p'"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'"-XX:+HeapDumpOnOutOfMemoryError" "-XX:OnOutOfMemoryError=cmd /c taskkill /pid %%%%p /t /f"'})})]})})}),`
`,e.jsx(n.h2,{id:"monitoring",children:"Monitoring"}),`
`,e.jsx(n.p,{children:"The ZooKeeper service can be monitored in one of three primary ways:"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:["the command port through the use of ",e.jsx(n.a,{href:"/admin-ops/administrators-guide/commands#the-four-letter-words",children:"4 letter words"})]}),`
`,e.jsxs(n.li,{children:["with ",e.jsx(n.a,{href:"/admin-ops/jmx",children:"JMX"})]}),`
`,e.jsxs(n.li,{children:["using the ",e.jsxs(n.a,{href:"/admin-ops/tools#zkserversh",children:[e.jsx(n.code,{children:"zkServer.sh status"})," command"]})]}),`
`]}),`
`,e.jsx(n.h2,{id:"logging",children:"Logging"}),`
`,e.jsxs(n.p,{children:["ZooKeeper uses ",e.jsx(n.strong,{children:e.jsx(n.a,{href:"http://www.slf4j.org",children:"SLF4J"})}),`
version 1.7 as its logging infrastructure. By default ZooKeeper is shipped with
`,e.jsx(n.strong,{children:e.jsx(n.a,{href:"http://logback.qos.ch/",children:"LOGBack"})}),` as the logging backend, but you can use
any other supported logging framework of your choice.`]}),`
`,e.jsxs(n.p,{children:["The ZooKeeper default ",e.jsx(n.em,{children:"logback.xml"}),`
file resides in the `,e.jsx(n.em,{children:"conf"}),` directory. Logback requires that
`,e.jsx(n.em,{children:"logback.xml"}),` either be in the working directory
(the directory from which ZooKeeper is run) or be accessible from the classpath.`]}),`
`,e.jsxs(n.p,{children:[`For more information about SLF4J, see
`,e.jsx(n.a,{href:"http://www.slf4j.org/manual.html",children:"its manual"}),"."]}),`
`,e.jsxs(n.p,{children:[`For more information about Logback, see
`,e.jsx(n.a,{href:"http://logback.qos.ch/",children:"Logback website"}),"."]}),`
`,e.jsx(n.h2,{id:"troubleshooting",children:"Troubleshooting"}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"Server not coming up because of file corruption"}),` :
A server might not be able to read its database and fail to come up because of
some file corruption in the transaction logs of the ZooKeeper server. You will
see some IOException on loading ZooKeeper database. In such a case,
make sure all the other servers in your ensemble are up and working. Use "stat"
command on the command port to see if they are in good health. After you have verified that
all the other servers of the ensemble are up, you can go ahead and clean the database
of the corrupt server. Delete all the files in datadir/version-2 and datalogdir/version-2/.
Restart the server.`]}),`
`]}),`
`,e.jsx(n.h2,{id:"metrics-providers",children:"Metrics Providers"}),`
`,e.jsxs(n.p,{children:[e.jsx(n.strong,{children:"New in 3.6.0:"})," The following options are used to configure metrics."]}),`
`,e.jsxs(n.p,{children:["By default ZooKeeper server exposes useful metrics using the ",e.jsx(n.a,{href:"/admin-ops/administrators-guide/commands#the-adminserver",children:"AdminServer"}),`
and `,e.jsx(n.a,{href:"/admin-ops/administrators-guide/commands#the-four-letter-words",children:"Four Letter Words"})," interface."]}),`
`,e.jsx(n.p,{children:`Since 3.6.0 you can configure a different Metrics Provider, that exports metrics
to your favourite system.`}),`
`,e.jsxs(n.p,{children:["Since 3.6.0 ZooKeeper binary package bundles an integration with ",e.jsx(n.a,{href:"https://prometheus.io",children:"Prometheus.io"})]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.className"}),` :
Set to "org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider" to
enable Prometheus.io exporter.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.httpHost"}),` :
`,e.jsx(n.strong,{children:"New in 3.8.0:"}),' Prometheus.io exporter will start a Jetty server and listen this address, default is "0.0.0.0"']}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.httpPort"}),` :
Prometheus.io exporter will start a Jetty server and bind to this port, it defaults to 7000.
Prometheus end point will be `,e.jsx(n.a,{href:"http://hostname:httPort/metrics",children:"http://hostname:httPort/metrics"}),"."]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.exportJvmInfo"}),` :
If this property is set to `,e.jsx(n.strong,{children:"true"}),` Prometheus.io will export useful metrics about the JVM.
The default is true.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.numWorkerThreads"}),` :
`,e.jsx(n.strong,{children:"New in 3.7.1:"}),`
Number of worker threads for reporting Prometheus summary metrics.
Default value is 1.
If the number is less than 1, the main thread will be used.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.maxQueueSize"}),` :
`,e.jsx(n.strong,{children:"New in 3.7.1:"}),`
The max queue size for Prometheus summary metrics reporting task.
Default value is 10000.`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.em,{children:"metricsProvider.workerShutdownTimeoutMs"}),` :
`,e.jsx(n.strong,{children:"New in 3.7.1:"}),`
The timeout in ms for Prometheus worker threads shutdown.
Default value is 1000ms.`]}),`
`]})]})}function c(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(r,{...t})}):r(t)}export{i as _markdown,c as default,a as extractedReferences,s as frontmatter,h as structuredData,l as toc};
