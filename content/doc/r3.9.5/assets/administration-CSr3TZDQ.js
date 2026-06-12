import{j as e}from"./chunk-EPOLDU6W-CDTnuKF9.js";let i=`## Maintenance

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
  Prometheus.io exporter will start a Jetty server and bind to this port.
  Prometheus end point will be \`http://hostname:httpPort/metrics\`.
  If omitted no HTTP port will be opened.
  Note: Either the HTTP or the HTTPS port has to be specified, or both.
* *metricsProvider.httpsPort* :
  **New in 3.10.0:** Prometheus.io exporter will start a Jetty server and bind to this port.
  Prometheus end point will be \`https://hostname:httpsPort/metrics\`.
  If omitted no HTTPS port will be opened.
  Note: Either the HTTP or the HTTPS port has to be specified, or both.
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
* *metricsProvider.ssl.keyStore.location* and *metricsProvider.ssl.keyStore.password* :
  **New in 3.10.0:**
  Specifies the file path to a Java keystore containing the local credentials to be
  used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.
* *metricsProvider.ssl.keyStore.type* :
  **New in 3.10.0:**
  Specifies the file format of the PrometheusMetricsProvider keystore.
  Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.
* *metricsProvider.ssl.trustStore.location* and *metricsProvider.ssl.trustStore.password* :
  **New in 3.10.0:**
  Specifies the file path to a Java truststore containing the remote credentials to be
  used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.
* *metricsProvider.ssl.trustStore.type* :
  **New in 3.10.0:**
  Specifies the file format of the PrometheusMetricsProvider truststore.
  Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.
* *metricsProvider.ssl.need.client.auth* :
  **New in 3.10.0:**
  When set to true, PrometheusMetricsProvider will "require" client authentication
  for SSL connections from clients. Default: true.
* *metricsProvider.ssl.want.client.auth* :
  **New in 3.10.0:**
  When set to true, PrometheusMetricsProvider will "request" client authentication
  for SSL connections from clients. Default: true.
* *metricsProvider.ssl.ciphersuites* :
  **New in 3.10.0:**
  The enabled cipher suites to be used in TLS negotiation for PrometheusMetricsProvider.
  Default value is the Jetty default.
* *metricsProvider.ssl.enabledProtocols* :
  **New in 3.10.0:**
  The enabled protocols to be used in TLS negotiation for PrometheusMetricsProvider.
  Default value is the Jetty default.
`,s={title:"Administration",description:"Operational guidance for running a ZooKeeper cluster, including provisioning, maintenance tasks, data directory cleanup, supervision, monitoring, logging, and troubleshooting."},a=[{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory"},{href:"/apidocs/zookeeper-server/index.html"},{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration"},{href:"#logging"},{href:"http://cr.yp.to/daemontools.html"},{href:"http://en.wikipedia.org/wiki/Service_Management_Facility"},{href:"/admin-ops/administrators-guide/commands#the-four-letter-words"},{href:"/admin-ops/jmx"},{href:"/admin-ops/tools#zkserversh"},{href:"http://www.slf4j.org"},{href:"http://logback.qos.ch/"},{href:"http://www.slf4j.org/manual.html"},{href:"http://logback.qos.ch/"},{href:"/admin-ops/administrators-guide/commands#the-adminserver"},{href:"/admin-ops/administrators-guide/commands#the-four-letter-words"},{href:"https://prometheus.io"}],l={contents:[{heading:"maintenance",content:`Little long term maintenance is required for a ZooKeeper
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
Prometheus.io exporter will start a Jetty server and bind to this port.
Prometheus end point will be http://hostname:httpPort/metrics.
If omitted no HTTP port will be opened.
Note: Either the HTTP or the HTTPS port has to be specified, or both.`},{heading:"metrics-providers",content:`metricsProvider.httpsPort :
New in 3.10.0: Prometheus.io exporter will start a Jetty server and bind to this port.
Prometheus end point will be https://hostname:httpsPort/metrics.
If omitted no HTTPS port will be opened.
Note: Either the HTTP or the HTTPS port has to be specified, or both.`},{heading:"metrics-providers",content:`metricsProvider.exportJvmInfo :
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
Default value is 1000ms.`},{heading:"metrics-providers",content:`metricsProvider.ssl.keyStore.location and metricsProvider.ssl.keyStore.password :
New in 3.10.0:
Specifies the file path to a Java keystore containing the local credentials to be
used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.`},{heading:"metrics-providers",content:`metricsProvider.ssl.keyStore.type :
New in 3.10.0:
Specifies the file format of the PrometheusMetricsProvider keystore.
Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.`},{heading:"metrics-providers",content:`metricsProvider.ssl.trustStore.location and metricsProvider.ssl.trustStore.password :
New in 3.10.0:
Specifies the file path to a Java truststore containing the remote credentials to be
used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.`},{heading:"metrics-providers",content:`metricsProvider.ssl.trustStore.type :
New in 3.10.0:
Specifies the file format of the PrometheusMetricsProvider truststore.
Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.`},{heading:"metrics-providers",content:`metricsProvider.ssl.need.client.auth :
New in 3.10.0:
When set to true, PrometheusMetricsProvider will "require" client authentication
for SSL connections from clients. Default: true.`},{heading:"metrics-providers",content:`metricsProvider.ssl.want.client.auth :
New in 3.10.0:
When set to true, PrometheusMetricsProvider will "request" client authentication
for SSL connections from clients. Default: true.`},{heading:"metrics-providers",content:`metricsProvider.ssl.ciphersuites :
New in 3.10.0:
The enabled cipher suites to be used in TLS negotiation for PrometheusMetricsProvider.
Default value is the Jetty default.`},{heading:"metrics-providers",content:`metricsProvider.ssl.enabledProtocols :
New in 3.10.0:
The enabled protocols to be used in TLS negotiation for PrometheusMetricsProvider.
Default value is the Jetty default.`}],headings:[{id:"maintenance",content:"Maintenance"},{id:"ongoing-data-directory-cleanup",content:"Ongoing Data Directory Cleanup"},{id:"debug-log-cleanup-logback",content:"Debug Log Cleanup (logback)"},{id:"supervision",content:"Supervision"},{id:"monitoring",content:"Monitoring"},{id:"logging",content:"Logging"},{id:"troubleshooting",content:"Troubleshooting"},{id:"metrics-providers",content:"Metrics Providers"}]};const h=[{depth:2,url:"#maintenance",title:e.jsx(e.Fragment,{children:"Maintenance"})},{depth:3,url:"#ongoing-data-directory-cleanup",title:e.jsx(e.Fragment,{children:"Ongoing Data Directory Cleanup"})},{depth:3,url:"#debug-log-cleanup-logback",title:e.jsx(e.Fragment,{children:"Debug Log Cleanup (logback)"})},{depth:2,url:"#supervision",title:e.jsx(e.Fragment,{children:"Supervision"})},{depth:2,url:"#monitoring",title:e.jsx(e.Fragment,{children:"Monitoring"})},{depth:2,url:"#logging",title:e.jsx(e.Fragment,{children:"Logging"})},{depth:2,url:"#troubleshooting",title:e.jsx(e.Fragment,{children:"Troubleshooting"})},{depth:2,url:"#metrics-providers",title:e.jsx(e.Fragment,{children:"Metrics Providers"})}];function r(n){const t={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsx(t.h2,{id:"maintenance",children:"Maintenance"}),`
`,e.jsx(t.p,{children:`Little long term maintenance is required for a ZooKeeper
cluster however you must be aware of the following:`}),`
`,e.jsx(t.h3,{id:"ongoing-data-directory-cleanup",children:"Ongoing Data Directory Cleanup"}),`
`,e.jsxs(t.p,{children:["The ZooKeeper ",e.jsx(t.a,{href:"/admin-ops/administrators-guide/data-file-management#the-data-directory",children:`Data
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
`,e.jsxs(t.p,{children:["A ZooKeeper server ",e.jsx(t.strong,{children:`will not remove
old snapshots and log files`}),` when using the default
configuration (see autopurge below), this is the
responsibility of the operator. Every serving environment is
different and therefore the requirements of managing these
files may differ from install to install (backup for example).`]}),`
`,e.jsxs(t.p,{children:[`The PurgeTxnLog utility implements a simple retention
policy that administrators can use. The `,e.jsx(t.a,{href:"/apidocs/zookeeper-server/index.html",children:"API docs"}),` contains details on
calling conventions (arguments, etc...).`]}),`
`,e.jsx(t.p,{children:`In the following example the last count snapshots and
their corresponding logs are retained and the others are
deleted. The value of <count> should typically be
greater than 3 (although not required, this provides 3 backups
in the unlikely event a recent log has become corrupted). This
can be run as a cron job on the ZooKeeper server machines to
clean up the logs daily.`}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsxs(t.span,{className:"line",children:[e.jsx(t.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" java"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -cp"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" zookeeper.jar:lib/slf4j-api-1.7.30.jar:lib/logback-classic-1.2.10.jar:lib/logback-core-1.2.10.jar:conf"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" org.apache.zookeeper.server.PurgeTxnLog"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"dataDi"}),e.jsx(t.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"r"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"snapDi"}),e.jsx(t.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"r"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -n"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"coun"}),e.jsx(t.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"t"}),e.jsx(t.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:">"})]})})})}),`
`,e.jsxs(t.p,{children:[`Automatic purging of the snapshots and corresponding
transaction logs was introduced in version 3.4.0 and can be
enabled via the following configuration parameters `,e.jsx(t.strong,{children:"autopurge.snapRetainCount"})," and ",e.jsx(t.strong,{children:"autopurge.purgeInterval"}),`. For more on
this, see `,e.jsx(t.a,{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration",children:"Advanced Configuration"}),"."]}),`
`,e.jsx(t.h3,{id:"debug-log-cleanup-logback",children:"Debug Log Cleanup (logback)"}),`
`,e.jsxs(t.p,{children:["See the section on ",e.jsx(t.a,{href:"#logging",children:"logging"}),` in this document. It is
expected that you will setup a rolling file appender using the
in-built logback feature. The sample configuration file in the
release tar's `,e.jsx(t.code,{children:"conf/logback.xml"}),` provides an example of
this.`]}),`
`,e.jsx(t.h2,{id:"supervision",children:"Supervision"}),`
`,e.jsx(t.p,{children:`You will want to have a supervisory process that manages
each of your ZooKeeper server processes (JVM). The ZK server is
designed to be "fail fast" meaning that it will shut down
(process exit) if an error occurs that it cannot recover
from. As a ZooKeeper serving cluster is highly reliable, this
means that while the server may go down the cluster as a whole
is still active and serving requests. Additionally, as the
cluster is "self healing" the failed server once restarted will
automatically rejoin the ensemble w/o any manual
interaction.`}),`
`,e.jsxs(t.p,{children:["Having a supervisory process such as ",e.jsx(t.a,{href:"http://cr.yp.to/daemontools.html",children:"daemontools"}),` or
`,e.jsx(t.a,{href:"http://en.wikipedia.org/wiki/Service_Management_Facility",children:"SMF"}),`
(other options for supervisory process are also available, it's
up to you which one you would like to use, these are just two
examples) managing your ZooKeeper server ensures that if the
process does exit abnormally it will automatically be restarted
and will quickly rejoin the cluster.`]}),`
`,e.jsxs(t.p,{children:[`It is also recommended to configure the ZooKeeper server process to
terminate and dump its heap if an OutOfMemoryError** occurs. This is achieved
by launching the JVM with the following arguments on Linux and Windows
respectively. The `,e.jsx(t.em,{children:"zkServer.sh"}),` and
*zkServer.cmd* scripts that ship with ZooKeeper set
these options.`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(t.code,{children:[e.jsx(t.span,{className:"line",children:e.jsx(t.span,{children:"-XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p'"})}),`
`,e.jsx(t.span,{className:"line",children:e.jsx(t.span,{})}),`
`,e.jsx(t.span,{className:"line",children:e.jsx(t.span,{children:'"-XX:+HeapDumpOnOutOfMemoryError" "-XX:OnOutOfMemoryError=cmd /c taskkill /pid %%%%p /t /f"'})})]})})}),`
`,e.jsx(t.h2,{id:"monitoring",children:"Monitoring"}),`
`,e.jsx(t.p,{children:"The ZooKeeper service can be monitored in one of three primary ways:"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:["the command port through the use of ",e.jsx(t.a,{href:"/admin-ops/administrators-guide/commands#the-four-letter-words",children:"4 letter words"})]}),`
`,e.jsxs(t.li,{children:["with ",e.jsx(t.a,{href:"/admin-ops/jmx",children:"JMX"})]}),`
`,e.jsxs(t.li,{children:["using the ",e.jsxs(t.a,{href:"/admin-ops/tools#zkserversh",children:[e.jsx(t.code,{children:"zkServer.sh status"})," command"]})]}),`
`]}),`
`,e.jsx(t.h2,{id:"logging",children:"Logging"}),`
`,e.jsxs(t.p,{children:["ZooKeeper uses ",e.jsx(t.strong,{children:e.jsx(t.a,{href:"http://www.slf4j.org",children:"SLF4J"})}),`
version 1.7 as its logging infrastructure. By default ZooKeeper is shipped with
`,e.jsx(t.strong,{children:e.jsx(t.a,{href:"http://logback.qos.ch/",children:"LOGBack"})}),` as the logging backend, but you can use
any other supported logging framework of your choice.`]}),`
`,e.jsxs(t.p,{children:["The ZooKeeper default ",e.jsx(t.em,{children:"logback.xml"}),`
file resides in the `,e.jsx(t.em,{children:"conf"}),` directory. Logback requires that
`,e.jsx(t.em,{children:"logback.xml"}),` either be in the working directory
(the directory from which ZooKeeper is run) or be accessible from the classpath.`]}),`
`,e.jsxs(t.p,{children:[`For more information about SLF4J, see
`,e.jsx(t.a,{href:"http://www.slf4j.org/manual.html",children:"its manual"}),"."]}),`
`,e.jsxs(t.p,{children:[`For more information about Logback, see
`,e.jsx(t.a,{href:"http://logback.qos.ch/",children:"Logback website"}),"."]}),`
`,e.jsx(t.h2,{id:"troubleshooting",children:"Troubleshooting"}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"Server not coming up because of file corruption"}),` :
A server might not be able to read its database and fail to come up because of
some file corruption in the transaction logs of the ZooKeeper server. You will
see some IOException on loading ZooKeeper database. In such a case,
make sure all the other servers in your ensemble are up and working. Use "stat"
command on the command port to see if they are in good health. After you have verified that
all the other servers of the ensemble are up, you can go ahead and clean the database
of the corrupt server. Delete all the files in datadir/version-2 and datalogdir/version-2/.
Restart the server.`]}),`
`]}),`
`,e.jsx(t.h2,{id:"metrics-providers",children:"Metrics Providers"}),`
`,e.jsxs(t.p,{children:[e.jsx(t.strong,{children:"New in 3.6.0:"})," The following options are used to configure metrics."]}),`
`,e.jsxs(t.p,{children:["By default ZooKeeper server exposes useful metrics using the ",e.jsx(t.a,{href:"/admin-ops/administrators-guide/commands#the-adminserver",children:"AdminServer"}),`
and `,e.jsx(t.a,{href:"/admin-ops/administrators-guide/commands#the-four-letter-words",children:"Four Letter Words"})," interface."]}),`
`,e.jsx(t.p,{children:`Since 3.6.0 you can configure a different Metrics Provider, that exports metrics
to your favourite system.`}),`
`,e.jsxs(t.p,{children:["Since 3.6.0 ZooKeeper binary package bundles an integration with ",e.jsx(t.a,{href:"https://prometheus.io",children:"Prometheus.io"})]}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.className"}),` :
Set to "org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider" to
enable Prometheus.io exporter.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.httpHost"}),` :
`,e.jsx(t.strong,{children:"New in 3.8.0:"}),' Prometheus.io exporter will start a Jetty server and listen this address, default is "0.0.0.0"']}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.httpPort"}),` :
Prometheus.io exporter will start a Jetty server and bind to this port.
Prometheus end point will be `,e.jsx(t.code,{children:"http://hostname:httpPort/metrics"}),`.
If omitted no HTTP port will be opened.
Note: Either the HTTP or the HTTPS port has to be specified, or both.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.httpsPort"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),` Prometheus.io exporter will start a Jetty server and bind to this port.
Prometheus end point will be `,e.jsx(t.code,{children:"https://hostname:httpsPort/metrics"}),`.
If omitted no HTTPS port will be opened.
Note: Either the HTTP or the HTTPS port has to be specified, or both.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.exportJvmInfo"}),` :
If this property is set to `,e.jsx(t.strong,{children:"true"}),` Prometheus.io will export useful metrics about the JVM.
The default is true.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.numWorkerThreads"}),` :
`,e.jsx(t.strong,{children:"New in 3.7.1:"}),`
Number of worker threads for reporting Prometheus summary metrics.
Default value is 1.
If the number is less than 1, the main thread will be used.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.maxQueueSize"}),` :
`,e.jsx(t.strong,{children:"New in 3.7.1:"}),`
The max queue size for Prometheus summary metrics reporting task.
Default value is 10000.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.workerShutdownTimeoutMs"}),` :
`,e.jsx(t.strong,{children:"New in 3.7.1:"}),`
The timeout in ms for Prometheus worker threads shutdown.
Default value is 1000ms.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.keyStore.location"})," and ",e.jsx(t.em,{children:"metricsProvider.ssl.keyStore.password"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
Specifies the file path to a Java keystore containing the local credentials to be
used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.keyStore.type"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
Specifies the file format of the PrometheusMetricsProvider keystore.
Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.trustStore.location"})," and ",e.jsx(t.em,{children:"metricsProvider.ssl.trustStore.password"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
Specifies the file path to a Java truststore containing the remote credentials to be
used for PrometheusMetricsProvider TLS connections, and the password to unlock the file.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.trustStore.type"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
Specifies the file format of the PrometheusMetricsProvider truststore.
Values: JKS, PEM, PKCS12, BCFKS or null (detect by filename). Default: null.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.need.client.auth"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
When set to true, PrometheusMetricsProvider will "require" client authentication
for SSL connections from clients. Default: true.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.want.client.auth"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
When set to true, PrometheusMetricsProvider will "request" client authentication
for SSL connections from clients. Default: true.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.ciphersuites"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
The enabled cipher suites to be used in TLS negotiation for PrometheusMetricsProvider.
Default value is the Jetty default.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.em,{children:"metricsProvider.ssl.enabledProtocols"}),` :
`,e.jsx(t.strong,{children:"New in 3.10.0:"}),`
The enabled protocols to be used in TLS negotiation for PrometheusMetricsProvider.
Default value is the Jetty default.`]}),`
`]})]})}function c(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(r,{...n})}):r(n)}export{i as _markdown,c as default,a as extractedReferences,s as frontmatter,l as structuredData,h as toc};
