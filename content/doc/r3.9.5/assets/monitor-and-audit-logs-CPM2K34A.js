import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";const r="/doc/r3.9.5/assets/zkAuditLogs-JOCUL4nL.jpg";let h=`

## New Metrics System

The New Metrics System has been available since 3.6.0. It provides rich metrics covering
znodes, network, disk, quorum, leader election, clients, security, failures, watches/sessions,
request processors, and more.

### Metrics

All available metrics are defined in \`ServerMetrics.java\`.

### Configuring the Metrics Provider

Enable the Prometheus \`MetricsProvider\` by adding the following to \`zoo.cfg\`:

\`\`\`properties
metricsProvider.className=org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider
\`\`\`

The HTTP port for Prometheus metrics scraping can be configured with (default is \`7000\`):

\`\`\`properties
metricsProvider.httpPort=7000
\`\`\`

#### Enabling HTTPS for Prometheus Metrics

ZooKeeper supports SSL for the Prometheus metrics endpoint to provide secure data transmission.

Define the HTTPS port:

\`\`\`properties
metricsProvider.httpsPort=4443
\`\`\`

Configure the SSL key store (holds the server's private key and certificate):

\`\`\`properties
metricsProvider.ssl.keyStore.location=/path/to/keystore.jks
metricsProvider.ssl.keyStore.password=your_keystore_password
metricsProvider.ssl.keyStore.type=jks  # Default is JKS
\`\`\`

Configure the SSL trust store (used to verify client certificates):

\`\`\`properties
metricsProvider.ssl.trustStore.location=/path/to/truststore.jks
metricsProvider.ssl.trustStore.password=your_truststore_password
metricsProvider.ssl.trustStore.type=jks  # Default is JKS
\`\`\`

<Callout type="info">
  HTTP and HTTPS can be enabled simultaneously by defining both ports:

  \`\`\`properties
  metricsProvider.httpPort=7000
  metricsProvider.httpsPort=4443
  \`\`\`
</Callout>

#### Restricting TLS Protocols and Cipher Suites

You can restrict the TLS versions and cipher suites used by the Prometheus \`MetricsProvider\`. Add the following to \`zoo.cfg\`:

\`\`\`properties
metricsProvider.ssl.enabledProtocols=TLSv1.2,TLSv1.3
metricsProvider.ssl.ciphersuites=TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
\`\`\`

To verify, raise the log level of \`PrometheusMetricsProvider\` to \`DEBUG\` and confirm these entries appear in the logs:

\`\`\`
INFO  [main:o.a.z.m.p.PrometheusMetricsProvider] - Setting enabled protocols: 'TLSv1.2,TLSv1.3'
INFO  [main:o.a.z.m.p.PrometheusMetricsProvider] - Setting enabled cipherSuites: 'TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384'
\`\`\`

### Prometheus

[Prometheus](https://prometheus.io/) is the easiest way to ingest and record ZooKeeper metrics.

Install Prometheus from the official [download page](https://prometheus.io/download/).

Configure the scraper to target your ZooKeeper cluster endpoints:

\`\`\`bash
cat > /tmp/test-zk.yaml <<EOF
global:
  scrape_interval: 10s
scrape_configs:
  - job_name: test-zk
    static_configs:
    - targets: ['192.168.10.32:7000','192.168.10.33:7000','192.168.10.34:7000']
EOF
\`\`\`

Start Prometheus:

\`\`\`bash
nohup /tmp/prometheus \\
    --config.file /tmp/test-zk.yaml \\
    --web.listen-address ":9090" \\
    --storage.tsdb.path "/tmp/test-zk.data" >> /tmp/test-zk.log 2>&1 &
\`\`\`

Prometheus will now scrape ZooKeeper metrics every 10 seconds.

### Alerting with Prometheus

Read the [Prometheus alerting documentation](https://prometheus.io/docs/practices/alerting/)
for alerting principles, and use [Prometheus Alertmanager](https://www.prometheus.io/docs/alerting/latest/alertmanager/)
to receive alert notifications via email or webhook.

The following is a reference alerting rules file for common ZooKeeper metrics. Adjust
thresholds to match your environment.

Validate the rules file with:

\`\`\`bash
./promtool check rules rules/zk.yml
\`\`\`

\`rules/zk.yml\`:

\`\`\`yaml
groups:
  - name: zk-alert-example
    rules:
      - alert: ZooKeeper server is down
        expr: up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Instance {{ $labels.instance }} ZooKeeper server is down"
          description: "{{ $labels.instance }} of job {{$labels.job}} ZooKeeper server is down: [{{ $value }}]."

      - alert: create too many znodes
        expr: znode_count > 1000000
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} create too many znodes"
          description: "{{ $labels.instance }} of job {{$labels.job}} create too many znodes: [{{ $value }}]."

      - alert: create too many connections
        expr: num_alive_connections > 50 # suppose we use the default maxClientCnxns: 60
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} create too many connections"
          description: "{{ $labels.instance }} of job {{$labels.job}} create too many connections: [{{ $value }}]."

      - alert: znode total occupied memory is too big
        expr: approximate_data_size /1024 /1024 > 1 * 1024 # more than 1024 MB (1 GB)
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} znode total occupied memory is too big"
          description: "{{ $labels.instance }} of job {{$labels.job}} znode total occupied memory is too big: [{{ $value }}] MB."

      - alert: set too many watch
        expr: watch_count > 10000
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} set too many watch"
          description: "{{ $labels.instance }} of job {{$labels.job}} set too many watch: [{{ $value }}]."

      - alert: a leader election happens
        expr: increase(election_time_count[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} a leader election happens"
          description: "{{ $labels.instance }} of job {{$labels.job}} a leader election happens: [{{ $value }}]."

      - alert: open too many files
        expr: open_file_descriptor_count > 300
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} open too many files"
          description: "{{ $labels.instance }} of job {{$labels.job}} open too many files: [{{ $value }}]."

      - alert: fsync time is too long
        expr: rate(fsynctime_sum[1m]) > 100
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} fsync time is too long"
          description: "{{ $labels.instance }} of job {{$labels.job}} fsync time is too long: [{{ $value }}]."

      - alert: take snapshot time is too long
        expr: rate(snapshottime_sum[5m]) > 100
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} take snapshot time is too long"
          description: "{{ $labels.instance }} of job {{$labels.job}} take snapshot time is too long: [{{ $value }}]."

      - alert: avg latency is too high
        expr: avg_latency > 100
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Instance {{ $labels.instance }} avg latency is too high"
          description: "{{ $labels.instance }} of job {{$labels.job}} avg latency is too high: [{{ $value }}]."

      - alert: JvmMemoryFillingUp
        expr: jvm_memory_bytes_used / jvm_memory_bytes_max{area="heap"} > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "JVM memory filling up (instance {{ $labels.instance }})"
          description: "JVM memory is filling up (> 80%)\\n labels: {{ $labels }}  value = {{ $value }}\\n"
\`\`\`

### Grafana

Grafana has built-in Prometheus support. Add a Prometheus data source with the following settings:

\`\`\`
Name:   test-zk
Type:   Prometheus
Url:    http://localhost:9090
Access: proxy
\`\`\`

Download and import the default [ZooKeeper dashboard template](https://grafana.com/grafana/dashboards/10465)
and customize it to your needs. If you have improvements to share, send them to **[dev@zookeeper.apache.org](mailto:dev@zookeeper.apache.org)**.

### InfluxDB

InfluxDB is an open source time series database often used to store ZooKeeper metrics.
You can [download](https://portal.influxdata.com/downloads/) the open source version or
create a [free cloud account](https://cloud2.influxdata.com/signup). In either case,
configure the [Apache ZooKeeper Telegraf plugin](https://www.influxdata.com/integration/apache-zookeeper/)
to collect and store metrics from your ZooKeeper clusters. There is also an
[Apache ZooKeeper InfluxDB template](https://www.influxdata.com/influxdb-templates/zookeeper-monitor/)
that includes Telegraf configuration and a pre-built dashboard to get you started quickly.

## JMX

See the [JMX guide](/admin-ops/jmx) for details.

## Four Letter Words

See the [Four Letter Words section](/admin-ops/administrators-guide/commands) in the Administrator's Guide.

## Audit Logs

Apache ZooKeeper supports audit logging from version 3.6.0. By default audit logs are disabled.
To enable them, set \`audit.enable=true\` in \`conf/zoo.cfg\`. Audit logs are not written on every
ZooKeeper server — they are written only on the servers to which a client is connected, as
illustrated below.

<img alt="Audit Logs" src={__img0} placeholder="blur" />

The audit log captures detailed information for audited operations, written as \`key=value\` pairs:

| Key          | Value                                                                                                                                                                                       |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| \`session\`    | Client session ID.                                                                                                                                                                          |
| \`user\`       | Comma-separated list of users associated with the client session. See [Who is taken as user in audit logs?](#who-is-taken-as-user-in-audit-logs)                                            |
| \`ip\`         | Client IP address.                                                                                                                                                                          |
| \`operation\`  | The audited operation. Possible values: \`serverStart\`, \`serverStop\`, \`create\`, \`delete\`, \`setData\`, \`setAcl\`, \`multiOperation\`, \`reconfig\`, \`ephemeralZNodeDeleteOnSessionClose\`.           |
| \`znode\`      | Path of the znode.                                                                                                                                                                          |
| \`znode type\` | Type of the znode (only for \`create\` operations).                                                                                                                                           |
| \`acl\`        | String representation of the znode ACL, e.g. \`cdrwa\` (create, delete, read, write, admin). Only logged for \`setAcl\`.                                                                        |
| \`result\`     | Outcome of the operation: \`success\`, \`failure\`, or \`invoked\`. The \`invoked\` result is used for \`serverStop\` because the stop is logged before the server has confirmed it actually stopped. |

Sample audit logs for all operations, where the client connected from \`192.168.1.2\`, client
principal is \`zkcli@HADOOP.COM\`, and server principal is \`zookeeper/192.168.1.3@HADOOP.COM\`:

\`\`\`
user=zookeeper/192.168.1.3 operation=serverStart   result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/a    znode_type=persistent  result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/a    znode_type=persistent  result=failure
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/a    result=failure
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/a    result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setAcl    znode=/a    acl=world:anyone:cdrwa  result=failure
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setAcl    znode=/a    acl=world:anyone:cdrwa  result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/b    znode_type=persistent  result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/b    result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/b    result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=multiOperation    result=failure
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/a    result=failure
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/a    result=success
session=0x19344730001   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create   znode=/ephemral znode_type=ephemral result=success
session=0x19344730001   user=zookeeper/192.168.1.3   operation=ephemeralZNodeDeletionOnSessionCloseOrExpire  znode=/ephemral result=success
session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=reconfig  znode=/zookeeper/config result=success
user=zookeeper/192.168.1.3 operation=serverStop    result=invoked
\`\`\`

### Audit Log Configuration

Audit logging is performed using Logback. The following is the default logback configuration
block in \`conf/logback.xml\` (the entire block is commented out by default — uncomment it to
activate audit logging):

\`\`\`xml
<!--
  zk audit logging
-->
<!--property name="zookeeper.auditlog.file" value="zookeeper_audit.log" />
<property name="zookeeper.auditlog.threshold" value="INFO" />
<property name="audit.logger" value="INFO, RFAAUDIT" />

<appender name="RFAAUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
  <File>\${zookeeper.log.dir}/\${zookeeper.auditlog.file}</File>
  <encoder>
    <pattern>%d{ISO8601} %p %c{2}: %m%n</pattern>
  </encoder>
  <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
    <level>\${zookeeper.auditlog.threshold}</level>
  </filter>
  <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
    <maxIndex>10</maxIndex>
    <FileNamePattern>\${zookeeper.log.dir}/\${zookeeper.auditlog.file}.%i</FileNamePattern>
  </rollingPolicy>
  <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
    <MaxFileSize>10MB</MaxFileSize>
  </triggeringPolicy>
</appender>

<logger name="org.apache.zookeeper.audit.Slf4jAuditLogger" additivity="false" level="\${audit.logger}">
  <appender-ref ref="RFAAUDIT" />
</logger-->
\`\`\`

Modify this configuration to customize the audit log filename, number of backup files,
maximum file size, or to use a custom audit logger.

### Who is Taken as User in Audit Logs?

There are four built-in authentication providers:

* \`IPAuthenticationProvider\` — the authenticated IP address is used as the user.
* \`SASLAuthenticationProvider\` — the client principal is used as the user.
* \`X509AuthenticationProvider\` — the client certificate is used as the user.
* \`DigestAuthenticationProvider\` — the authenticated username is used as the user.

Custom authentication providers can override \`org.apache.zookeeper.server.auth.AuthenticationProvider.getUserName(String id)\`
to provide a user name. If a custom provider does not override this method, the value stored in
\`org.apache.zookeeper.data.Id.id\` is used as the user. Generally only the user name is stored in
this field, but it is up to the custom provider what they store there.

Not all ZooKeeper operations are initiated by clients — some are performed by the server itself.
For example, when a client session closes, any ephemeral znodes it owned are deleted by the server
directly. These are called system operations. For system operations, the user associated with the
ZooKeeper server principal is logged as the user. For example, if the server principal is
\`zookeeper/hadoop.hadoop.com@HADOOP.COM\`, it becomes the system user:

\`\`\`
user=zookeeper/hadoop.hadoop.com@HADOOP.COM operation=serverStart result=success
\`\`\`

If there is no user associated with the ZooKeeper server, the OS user who started the server
process is used. For example, if the server was started by \`root\`:

\`\`\`
user=root operation=serverStart result=success
\`\`\`

A single client can attach multiple authentication schemes to a session. In that case all
authenticated identities are taken as the user and presented as a comma-separated list. For
example, if a client is authenticated with principal \`zkcli@HADOOP.COM\` and IP \`127.0.0.1\`,
the create operation audit log will be:

\`\`\`
session=0x10c0bcb0000 user=zkcli@HADOOP.COM,127.0.0.1 ip=127.0.0.1 operation=create znode=/a result=success
\`\`\`
`,o={title:"Monitor & Audit Logs",description:"How to monitor ZooKeeper with Prometheus, Grafana, and InfluxDB metrics, and how to enable and configure audit logging to track operations performed on the cluster."},d=[{href:"https://prometheus.io/"},{href:"https://prometheus.io/download/"},{href:"https://prometheus.io/docs/practices/alerting/"},{href:"https://www.prometheus.io/docs/alerting/latest/alertmanager/"},{href:"https://grafana.com/grafana/dashboards/10465"},{href:"mailto:dev@zookeeper.apache.org"},{href:"https://portal.influxdata.com/downloads/"},{href:"https://cloud2.influxdata.com/signup"},{href:"https://www.influxdata.com/integration/apache-zookeeper/"},{href:"https://www.influxdata.com/influxdb-templates/zookeeper-monitor/"},{href:"/admin-ops/jmx"},{href:"/admin-ops/administrators-guide/commands"},{href:"#who-is-taken-as-user-in-audit-logs"}],c={contents:[{heading:"new-metrics-system",content:`The New Metrics System has been available since 3.6.0. It provides rich metrics covering
znodes, network, disk, quorum, leader election, clients, security, failures, watches/sessions,
request processors, and more.`},{heading:"metrics",content:"All available metrics are defined in ServerMetrics.java."},{heading:"configuring-the-metrics-provider",content:"Enable the Prometheus MetricsProvider by adding the following to zoo.cfg:"},{heading:"configuring-the-metrics-provider",content:"The HTTP port for Prometheus metrics scraping can be configured with (default is 7000):"},{heading:"enabling-https-for-prometheus-metrics",content:"ZooKeeper supports SSL for the Prometheus metrics endpoint to provide secure data transmission."},{heading:"enabling-https-for-prometheus-metrics",content:"Define the HTTPS port:"},{heading:"enabling-https-for-prometheus-metrics",content:"Configure the SSL key store (holds the server's private key and certificate):"},{heading:"enabling-https-for-prometheus-metrics",content:"Configure the SSL trust store (used to verify client certificates):"},{heading:"enabling-https-for-prometheus-metrics",content:"type: info"},{heading:"enabling-https-for-prometheus-metrics",content:"HTTP and HTTPS can be enabled simultaneously by defining both ports:"},{heading:"restricting-tls-protocols-and-cipher-suites",content:"You can restrict the TLS versions and cipher suites used by the Prometheus MetricsProvider. Add the following to zoo.cfg:"},{heading:"restricting-tls-protocols-and-cipher-suites",content:"To verify, raise the log level of PrometheusMetricsProvider to DEBUG and confirm these entries appear in the logs:"},{heading:"prometheus",content:"Prometheus is the easiest way to ingest and record ZooKeeper metrics."},{heading:"prometheus",content:"Install Prometheus from the official download page."},{heading:"prometheus",content:"Configure the scraper to target your ZooKeeper cluster endpoints:"},{heading:"prometheus",content:"Start Prometheus:"},{heading:"prometheus",content:"Prometheus will now scrape ZooKeeper metrics every 10 seconds."},{heading:"alerting-with-prometheus",content:`Read the Prometheus alerting documentation
for alerting principles, and use Prometheus Alertmanager
to receive alert notifications via email or webhook.`},{heading:"alerting-with-prometheus",content:`The following is a reference alerting rules file for common ZooKeeper metrics. Adjust
thresholds to match your environment.`},{heading:"alerting-with-prometheus",content:"Validate the rules file with:"},{heading:"alerting-with-prometheus",content:"rules/zk.yml:"},{heading:"grafana",content:"Grafana has built-in Prometheus support. Add a Prometheus data source with the following settings:"},{heading:"grafana",content:`Download and import the default ZooKeeper dashboard template
and customize it to your needs. If you have improvements to share, send them to dev@zookeeper.apache.org.`},{heading:"influxdb",content:`InfluxDB is an open source time series database often used to store ZooKeeper metrics.
You can download the open source version or
create a free cloud account. In either case,
configure the Apache ZooKeeper Telegraf plugin
to collect and store metrics from your ZooKeeper clusters. There is also an
Apache ZooKeeper InfluxDB template
that includes Telegraf configuration and a pre-built dashboard to get you started quickly.`},{heading:"jmx",content:"See the JMX guide for details."},{heading:"four-letter-words",content:"See the Four Letter Words section in the Administrator's Guide."},{heading:"audit-logs",content:`Apache ZooKeeper supports audit logging from version 3.6.0. By default audit logs are disabled.
To enable them, set audit.enable=true in conf/zoo.cfg. Audit logs are not written on every
ZooKeeper server — they are written only on the servers to which a client is connected, as
illustrated below.`},{heading:"audit-logs",content:"The audit log captures detailed information for audited operations, written as key=value pairs:"},{heading:"audit-logs",content:"Key"},{heading:"audit-logs",content:"Value"},{heading:"audit-logs",content:"session"},{heading:"audit-logs",content:"Client session ID."},{heading:"audit-logs",content:"user"},{heading:"audit-logs",content:"Comma-separated list of users associated with the client session. See Who is taken as user in audit logs?"},{heading:"audit-logs",content:"ip"},{heading:"audit-logs",content:"Client IP address."},{heading:"audit-logs",content:"operation"},{heading:"audit-logs",content:"The audited operation. Possible values: serverStart, serverStop, create, delete, setData, setAcl, multiOperation, reconfig, ephemeralZNodeDeleteOnSessionClose."},{heading:"audit-logs",content:"znode"},{heading:"audit-logs",content:"Path of the znode."},{heading:"audit-logs",content:"znode type"},{heading:"audit-logs",content:"Type of the znode (only for create operations)."},{heading:"audit-logs",content:"acl"},{heading:"audit-logs",content:"String representation of the znode ACL, e.g. cdrwa (create, delete, read, write, admin). Only logged for setAcl."},{heading:"audit-logs",content:"result"},{heading:"audit-logs",content:"Outcome of the operation: success, failure, or invoked. The invoked result is used for serverStop because the stop is logged before the server has confirmed it actually stopped."},{heading:"audit-logs",content:`Sample audit logs for all operations, where the client connected from 192.168.1.2, client
principal is zkcli@HADOOP.COM, and server principal is zookeeper/192.168.1.3@HADOOP.COM:`},{heading:"audit-log-configuration",content:`Audit logging is performed using Logback. The following is the default logback configuration
block in conf/logback.xml (the entire block is commented out by default — uncomment it to
activate audit logging):`},{heading:"audit-log-configuration",content:`Modify this configuration to customize the audit log filename, number of backup files,
maximum file size, or to use a custom audit logger.`},{heading:"who-is-taken-as-user-in-audit-logs",content:"There are four built-in authentication providers:"},{heading:"who-is-taken-as-user-in-audit-logs",content:"IPAuthenticationProvider — the authenticated IP address is used as the user."},{heading:"who-is-taken-as-user-in-audit-logs",content:"SASLAuthenticationProvider — the client principal is used as the user."},{heading:"who-is-taken-as-user-in-audit-logs",content:"X509AuthenticationProvider — the client certificate is used as the user."},{heading:"who-is-taken-as-user-in-audit-logs",content:"DigestAuthenticationProvider — the authenticated username is used as the user."},{heading:"who-is-taken-as-user-in-audit-logs",content:`Custom authentication providers can override org.apache.zookeeper.server.auth.AuthenticationProvider.getUserName(String id)
to provide a user name. If a custom provider does not override this method, the value stored in
org.apache.zookeeper.data.Id.id is used as the user. Generally only the user name is stored in
this field, but it is up to the custom provider what they store there.`},{heading:"who-is-taken-as-user-in-audit-logs",content:`Not all ZooKeeper operations are initiated by clients — some are performed by the server itself.
For example, when a client session closes, any ephemeral znodes it owned are deleted by the server
directly. These are called system operations. For system operations, the user associated with the
ZooKeeper server principal is logged as the user. For example, if the server principal is
zookeeper/hadoop.hadoop.com@HADOOP.COM, it becomes the system user:`},{heading:"who-is-taken-as-user-in-audit-logs",content:`If there is no user associated with the ZooKeeper server, the OS user who started the server
process is used. For example, if the server was started by root:`},{heading:"who-is-taken-as-user-in-audit-logs",content:`A single client can attach multiple authentication schemes to a session. In that case all
authenticated identities are taken as the user and presented as a comma-separated list. For
example, if a client is authenticated with principal zkcli@HADOOP.COM and IP 127.0.0.1,
the create operation audit log will be:`}],headings:[{id:"new-metrics-system",content:"New Metrics System"},{id:"metrics",content:"Metrics"},{id:"configuring-the-metrics-provider",content:"Configuring the Metrics Provider"},{id:"enabling-https-for-prometheus-metrics",content:"Enabling HTTPS for Prometheus Metrics"},{id:"restricting-tls-protocols-and-cipher-suites",content:"Restricting TLS Protocols and Cipher Suites"},{id:"prometheus",content:"Prometheus"},{id:"alerting-with-prometheus",content:"Alerting with Prometheus"},{id:"grafana",content:"Grafana"},{id:"influxdb",content:"InfluxDB"},{id:"jmx",content:"JMX"},{id:"four-letter-words",content:"Four Letter Words"},{id:"audit-logs",content:"Audit Logs"},{id:"audit-log-configuration",content:"Audit Log Configuration"},{id:"who-is-taken-as-user-in-audit-logs",content:"Who is Taken as User in Audit Logs?"}]};const p=[{depth:2,url:"#new-metrics-system",title:e.jsx(e.Fragment,{children:"New Metrics System"})},{depth:3,url:"#metrics",title:e.jsx(e.Fragment,{children:"Metrics"})},{depth:3,url:"#configuring-the-metrics-provider",title:e.jsx(e.Fragment,{children:"Configuring the Metrics Provider"})},{depth:4,url:"#enabling-https-for-prometheus-metrics",title:e.jsx(e.Fragment,{children:"Enabling HTTPS for Prometheus Metrics"})},{depth:4,url:"#restricting-tls-protocols-and-cipher-suites",title:e.jsx(e.Fragment,{children:"Restricting TLS Protocols and Cipher Suites"})},{depth:3,url:"#prometheus",title:e.jsx(e.Fragment,{children:"Prometheus"})},{depth:3,url:"#alerting-with-prometheus",title:e.jsx(e.Fragment,{children:"Alerting with Prometheus"})},{depth:3,url:"#grafana",title:e.jsx(e.Fragment,{children:"Grafana"})},{depth:3,url:"#influxdb",title:e.jsx(e.Fragment,{children:"InfluxDB"})},{depth:2,url:"#jmx",title:e.jsx(e.Fragment,{children:"JMX"})},{depth:2,url:"#four-letter-words",title:e.jsx(e.Fragment,{children:"Four Letter Words"})},{depth:2,url:"#audit-logs",title:e.jsx(e.Fragment,{children:"Audit Logs"})},{depth:3,url:"#audit-log-configuration",title:e.jsx(e.Fragment,{children:"Audit Log Configuration"})},{depth:3,url:"#who-is-taken-as-user-in-audit-logs",title:e.jsx(e.Fragment,{children:"Who is Taken as User in Audit Logs?"})}];function t(i){const s={a:"a",code:"code",h2:"h2",h3:"h3",h4:"h4",img:"img",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...i.components},{Callout:n}=s;return n||a("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(s.h2,{id:"new-metrics-system",children:"New Metrics System"}),`
`,e.jsx(s.p,{children:`The New Metrics System has been available since 3.6.0. It provides rich metrics covering
znodes, network, disk, quorum, leader election, clients, security, failures, watches/sessions,
request processors, and more.`}),`
`,e.jsx(s.h3,{id:"metrics",children:"Metrics"}),`
`,e.jsxs(s.p,{children:["All available metrics are defined in ",e.jsx(s.code,{children:"ServerMetrics.java"}),"."]}),`
`,e.jsx(s.h3,{id:"configuring-the-metrics-provider",children:"Configuring the Metrics Provider"}),`
`,e.jsxs(s.p,{children:["Enable the Prometheus ",e.jsx(s.code,{children:"MetricsProvider"})," by adding the following to ",e.jsx(s.code,{children:"zoo.cfg"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.className"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider"})]})})})}),`
`,e.jsxs(s.p,{children:["The HTTP port for Prometheus metrics scraping can be configured with (default is ",e.jsx(s.code,{children:"7000"}),"):"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.httpPort"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=7000"})]})})})}),`
`,e.jsx(s.h4,{id:"enabling-https-for-prometheus-metrics",children:"Enabling HTTPS for Prometheus Metrics"}),`
`,e.jsx(s.p,{children:"ZooKeeper supports SSL for the Prometheus metrics endpoint to provide secure data transmission."}),`
`,e.jsx(s.p,{children:"Define the HTTPS port:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.httpsPort"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=4443"})]})})})}),`
`,e.jsx(s.p,{children:"Configure the SSL key store (holds the server's private key and certificate):"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.keyStore.location"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=/path/to/keystore.jks"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.keyStore.password"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=your_keystore_password"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.keyStore.type"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=jks  "}),e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Default is JKS"})]})]})})}),`
`,e.jsx(s.p,{children:"Configure the SSL trust store (used to verify client certificates):"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.trustStore.location"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=/path/to/truststore.jks"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.trustStore.password"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=your_truststore_password"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.trustStore.type"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=jks  "}),e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"# Default is JKS"})]})]})})}),`
`,e.jsxs(n,{type:"info",children:[e.jsx(s.p,{children:"HTTP and HTTPS can be enabled simultaneously by defining both ports:"}),e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.httpPort"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=7000"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.httpsPort"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=4443"})]})]})})})]}),`
`,e.jsx(s.h4,{id:"restricting-tls-protocols-and-cipher-suites",children:"Restricting TLS Protocols and Cipher Suites"}),`
`,e.jsxs(s.p,{children:["You can restrict the TLS versions and cipher suites used by the Prometheus ",e.jsx(s.code,{children:"MetricsProvider"}),". Add the following to ",e.jsx(s.code,{children:"zoo.cfg"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.enabledProtocols"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=TLSv1.2,TLSv1.3"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"metricsProvider.ssl.ciphersuites"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"})]})]})})}),`
`,e.jsxs(s.p,{children:["To verify, raise the log level of ",e.jsx(s.code,{children:"PrometheusMetricsProvider"})," to ",e.jsx(s.code,{children:"DEBUG"})," and confirm these entries appear in the logs:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"INFO  [main:o.a.z.m.p.PrometheusMetricsProvider] - Setting enabled protocols: 'TLSv1.2,TLSv1.3'"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"INFO  [main:o.a.z.m.p.PrometheusMetricsProvider] - Setting enabled cipherSuites: 'TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384'"})})]})})}),`
`,e.jsx(s.h3,{id:"prometheus",children:"Prometheus"}),`
`,e.jsxs(s.p,{children:[e.jsx(s.a,{href:"https://prometheus.io/",children:"Prometheus"})," is the easiest way to ingest and record ZooKeeper metrics."]}),`
`,e.jsxs(s.p,{children:["Install Prometheus from the official ",e.jsx(s.a,{href:"https://prometheus.io/download/",children:"download page"}),"."]}),`
`,e.jsx(s.p,{children:"Configure the scraper to target your ZooKeeper cluster endpoints:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"cat"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" >"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /tmp/test-zk.yaml"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" <<"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"EOF"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"global:"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  scrape_interval: 10s"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"scrape_configs:"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"  - job_name: test-zk"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"    static_configs:"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"    - targets: ['192.168.10.32:7000','192.168.10.33:7000','192.168.10.34:7000']"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"EOF"})})]})})}),`
`,e.jsx(s.p,{children:"Start Prometheus:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"nohup"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /tmp/prometheus"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    --config.file"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /tmp/test-zk.yaml"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    --web.listen-address"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:' ":9090"'}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" \\"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"    --storage.tsdb.path"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:' "/tmp/test-zk.data"'}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" >>"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" /tmp/test-zk.log"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" 2>&1"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" &"})]})]})})}),`
`,e.jsx(s.p,{children:"Prometheus will now scrape ZooKeeper metrics every 10 seconds."}),`
`,e.jsx(s.h3,{id:"alerting-with-prometheus",children:"Alerting with Prometheus"}),`
`,e.jsxs(s.p,{children:["Read the ",e.jsx(s.a,{href:"https://prometheus.io/docs/practices/alerting/",children:"Prometheus alerting documentation"}),`
for alerting principles, and use `,e.jsx(s.a,{href:"https://www.prometheus.io/docs/alerting/latest/alertmanager/",children:"Prometheus Alertmanager"}),`
to receive alert notifications via email or webhook.`]}),`
`,e.jsx(s.p,{children:`The following is a reference alerting rules file for common ZooKeeper metrics. Adjust
thresholds to match your environment.`}),`
`,e.jsx(s.p,{children:"Validate the rules file with:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"./promtool"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" check"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" rules"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" rules/zk.yml"})]})})})}),`
`,e.jsxs(s.p,{children:[e.jsx(s.code,{children:"rules/zk.yml"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"groups"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"name"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"zk-alert-example"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"    rules"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"ZooKeeper server is down"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"up == 0"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"critical"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} ZooKeeper server is down"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} ZooKeeper server is down: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"create too many znodes"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"znode_count > 1000000"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} create too many znodes"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} create too many znodes: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"create too many connections"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"num_alive_connections > 50"}),e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" # suppose we use the default maxClientCnxns: 60"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} create too many connections"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} create too many connections: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"znode total occupied memory is too big"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"approximate_data_size /1024 /1024 > 1 * 1024"}),e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" # more than 1024 MB (1 GB)"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} znode total occupied memory is too big"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} znode total occupied memory is too big: [{{ $value }}] MB."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"set too many watch"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"watch_count > 10000"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} set too many watch"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} set too many watch: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"a leader election happens"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"increase(election_time_count[5m]) > 0"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} a leader election happens"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} a leader election happens: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"open too many files"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"open_file_descriptor_count > 300"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} open too many files"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} open too many files: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"fsync time is too long"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"rate(fsynctime_sum[1m]) > 100"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} fsync time is too long"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} fsync time is too long: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"take snapshot time is too long"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"rate(snapshottime_sum[5m]) > 100"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} take snapshot time is too long"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} take snapshot time is too long: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"avg latency is too high"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"avg_latency > 100"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"1m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Instance {{ $labels.instance }} avg latency is too high"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"{{ $labels.instance }} of job {{$labels.job}} avg latency is too high: [{{ $value }}]."'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"      - "}),e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"alert"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"JvmMemoryFillingUp"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        expr"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'jvm_memory_bytes_used / jvm_memory_bytes_max{area="heap"} > 0.8'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        for"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"5m"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        labels"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          severity"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"warning"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"        annotations"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:":"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          summary"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"JVM memory filling up (instance {{ $labels.instance }})"'})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#22863A","--shiki-dark":"#85E89D"},children:"          description"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:": "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"JVM memory is filling up (> 80%)'}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\n"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" labels: {{ $labels }}  value = {{ $value }}"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\n"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"'})]})]})})}),`
`,e.jsx(s.h3,{id:"grafana",children:"Grafana"}),`
`,e.jsx(s.p,{children:"Grafana has built-in Prometheus support. Add a Prometheus data source with the following settings:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"Name:   test-zk"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"Type:   Prometheus"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"Url:    http://localhost:9090"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"Access: proxy"})})]})})}),`
`,e.jsxs(s.p,{children:["Download and import the default ",e.jsx(s.a,{href:"https://grafana.com/grafana/dashboards/10465",children:"ZooKeeper dashboard template"}),`
and customize it to your needs. If you have improvements to share, send them to `,e.jsx(s.strong,{children:e.jsx(s.a,{href:"mailto:dev@zookeeper.apache.org",children:"dev@zookeeper.apache.org"})}),"."]}),`
`,e.jsx(s.h3,{id:"influxdb",children:"InfluxDB"}),`
`,e.jsxs(s.p,{children:[`InfluxDB is an open source time series database often used to store ZooKeeper metrics.
You can `,e.jsx(s.a,{href:"https://portal.influxdata.com/downloads/",children:"download"}),` the open source version or
create a `,e.jsx(s.a,{href:"https://cloud2.influxdata.com/signup",children:"free cloud account"}),`. In either case,
configure the `,e.jsx(s.a,{href:"https://www.influxdata.com/integration/apache-zookeeper/",children:"Apache ZooKeeper Telegraf plugin"}),`
to collect and store metrics from your ZooKeeper clusters. There is also an
`,e.jsx(s.a,{href:"https://www.influxdata.com/influxdb-templates/zookeeper-monitor/",children:"Apache ZooKeeper InfluxDB template"}),`
that includes Telegraf configuration and a pre-built dashboard to get you started quickly.`]}),`
`,e.jsx(s.h2,{id:"jmx",children:"JMX"}),`
`,e.jsxs(s.p,{children:["See the ",e.jsx(s.a,{href:"/admin-ops/jmx",children:"JMX guide"})," for details."]}),`
`,e.jsx(s.h2,{id:"four-letter-words",children:"Four Letter Words"}),`
`,e.jsxs(s.p,{children:["See the ",e.jsx(s.a,{href:"/admin-ops/administrators-guide/commands",children:"Four Letter Words section"})," in the Administrator's Guide."]}),`
`,e.jsx(s.h2,{id:"audit-logs",children:"Audit Logs"}),`
`,e.jsxs(s.p,{children:[`Apache ZooKeeper supports audit logging from version 3.6.0. By default audit logs are disabled.
To enable them, set `,e.jsx(s.code,{children:"audit.enable=true"})," in ",e.jsx(s.code,{children:"conf/zoo.cfg"}),`. Audit logs are not written on every
ZooKeeper server — they are written only on the servers to which a client is connected, as
illustrated below.`]}),`
`,e.jsx(s.p,{children:e.jsx(s.img,{alt:"Audit Logs",src:r,placeholder:"blur"})}),`
`,e.jsxs(s.p,{children:["The audit log captures detailed information for audited operations, written as ",e.jsx(s.code,{children:"key=value"})," pairs:"]}),`
`,e.jsxs(s.table,{children:[e.jsx(s.thead,{children:e.jsxs(s.tr,{children:[e.jsx(s.th,{children:"Key"}),e.jsx(s.th,{children:"Value"})]})}),e.jsxs(s.tbody,{children:[e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"session"})}),e.jsx(s.td,{children:"Client session ID."})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"user"})}),e.jsxs(s.td,{children:["Comma-separated list of users associated with the client session. See ",e.jsx(s.a,{href:"#who-is-taken-as-user-in-audit-logs",children:"Who is taken as user in audit logs?"})]})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"ip"})}),e.jsx(s.td,{children:"Client IP address."})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"operation"})}),e.jsxs(s.td,{children:["The audited operation. Possible values: ",e.jsx(s.code,{children:"serverStart"}),", ",e.jsx(s.code,{children:"serverStop"}),", ",e.jsx(s.code,{children:"create"}),", ",e.jsx(s.code,{children:"delete"}),", ",e.jsx(s.code,{children:"setData"}),", ",e.jsx(s.code,{children:"setAcl"}),", ",e.jsx(s.code,{children:"multiOperation"}),", ",e.jsx(s.code,{children:"reconfig"}),", ",e.jsx(s.code,{children:"ephemeralZNodeDeleteOnSessionClose"}),"."]})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"znode"})}),e.jsx(s.td,{children:"Path of the znode."})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"znode type"})}),e.jsxs(s.td,{children:["Type of the znode (only for ",e.jsx(s.code,{children:"create"})," operations)."]})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"acl"})}),e.jsxs(s.td,{children:["String representation of the znode ACL, e.g. ",e.jsx(s.code,{children:"cdrwa"})," (create, delete, read, write, admin). Only logged for ",e.jsx(s.code,{children:"setAcl"}),"."]})]}),e.jsxs(s.tr,{children:[e.jsx(s.td,{children:e.jsx(s.code,{children:"result"})}),e.jsxs(s.td,{children:["Outcome of the operation: ",e.jsx(s.code,{children:"success"}),", ",e.jsx(s.code,{children:"failure"}),", or ",e.jsx(s.code,{children:"invoked"}),". The ",e.jsx(s.code,{children:"invoked"})," result is used for ",e.jsx(s.code,{children:"serverStop"})," because the stop is logged before the server has confirmed it actually stopped."]})]})]})]}),`
`,e.jsxs(s.p,{children:["Sample audit logs for all operations, where the client connected from ",e.jsx(s.code,{children:"192.168.1.2"}),`, client
principal is `,e.jsx(s.code,{children:"zkcli@HADOOP.COM"}),", and server principal is ",e.jsx(s.code,{children:"zookeeper/192.168.1.3@HADOOP.COM"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"user=zookeeper/192.168.1.3 operation=serverStart   result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/a    znode_type=persistent  result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/a    znode_type=persistent  result=failure"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/a    result=failure"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/a    result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setAcl    znode=/a    acl=world:anyone:cdrwa  result=failure"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setAcl    znode=/a    acl=world:anyone:cdrwa  result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create    znode=/b    znode_type=persistent  result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=setData   znode=/b    result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/b    result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=multiOperation    result=failure"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/a    result=failure"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=delete    znode=/a    result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730001   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=create   znode=/ephemral znode_type=ephemral result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730001   user=zookeeper/192.168.1.3   operation=ephemeralZNodeDeletionOnSessionCloseOrExpire  znode=/ephemral result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x19344730000   user=192.168.1.2,zkcli@HADOOP.COM  ip=192.168.1.2    operation=reconfig  znode=/zookeeper/config result=success"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"user=zookeeper/192.168.1.3 operation=serverStop    result=invoked"})})]})})}),`
`,e.jsx(s.h3,{id:"audit-log-configuration",children:"Audit Log Configuration"}),`
`,e.jsxs(s.p,{children:[`Audit logging is performed using Logback. The following is the default logback configuration
block in `,e.jsx(s.code,{children:"conf/logback.xml"}),` (the entire block is commented out by default — uncomment it to
activate audit logging):`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"<!--"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  zk audit logging"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"-->"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'<!--property name="zookeeper.auditlog.file" value="zookeeper_audit.log" />'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'<property name="zookeeper.auditlog.threshold" value="INFO" />'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'<property name="audit.logger" value="INFO, RFAAUDIT" />'})}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'<appender name="RFAAUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  <File>${zookeeper.log.dir}/${zookeeper.auditlog.file}</File>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  <encoder>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    <pattern>%d{ISO8601} %p %c{2}: %m%n</pattern>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  </encoder>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'  <filter class="ch.qos.logback.classic.filter.ThresholdFilter">'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    <level>${zookeeper.auditlog.threshold}</level>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  </filter>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'  <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    <maxIndex>10</maxIndex>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    <FileNamePattern>${zookeeper.log.dir}/${zookeeper.auditlog.file}.%i</FileNamePattern>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  </rollingPolicy>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'  <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    <MaxFileSize>10MB</MaxFileSize>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  </triggeringPolicy>"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"</appender>"})}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'<logger name="org.apache.zookeeper.audit.Slf4jAuditLogger" additivity="false" level="${audit.logger}">'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:'  <appender-ref ref="RFAAUDIT" />'})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"</logger-->"})})]})})}),`
`,e.jsx(s.p,{children:`Modify this configuration to customize the audit log filename, number of backup files,
maximum file size, or to use a custom audit logger.`}),`
`,e.jsx(s.h3,{id:"who-is-taken-as-user-in-audit-logs",children:"Who is Taken as User in Audit Logs?"}),`
`,e.jsx(s.p,{children:"There are four built-in authentication providers:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.code,{children:"IPAuthenticationProvider"})," — the authenticated IP address is used as the user."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.code,{children:"SASLAuthenticationProvider"})," — the client principal is used as the user."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.code,{children:"X509AuthenticationProvider"})," — the client certificate is used as the user."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.code,{children:"DigestAuthenticationProvider"})," — the authenticated username is used as the user."]}),`
`]}),`
`,e.jsxs(s.p,{children:["Custom authentication providers can override ",e.jsx(s.code,{children:"org.apache.zookeeper.server.auth.AuthenticationProvider.getUserName(String id)"}),`
to provide a user name. If a custom provider does not override this method, the value stored in
`,e.jsx(s.code,{children:"org.apache.zookeeper.data.Id.id"}),` is used as the user. Generally only the user name is stored in
this field, but it is up to the custom provider what they store there.`]}),`
`,e.jsxs(s.p,{children:[`Not all ZooKeeper operations are initiated by clients — some are performed by the server itself.
For example, when a client session closes, any ephemeral znodes it owned are deleted by the server
directly. These are called system operations. For system operations, the user associated with the
ZooKeeper server principal is logged as the user. For example, if the server principal is
`,e.jsx(s.code,{children:"zookeeper/hadoop.hadoop.com@HADOOP.COM"}),", it becomes the system user:"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"user=zookeeper/hadoop.hadoop.com@HADOOP.COM operation=serverStart result=success"})})})})}),`
`,e.jsxs(s.p,{children:[`If there is no user associated with the ZooKeeper server, the OS user who started the server
process is used. For example, if the server was started by `,e.jsx(s.code,{children:"root"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"user=root operation=serverStart result=success"})})})})}),`
`,e.jsxs(s.p,{children:[`A single client can attach multiple authentication schemes to a session. In that case all
authenticated identities are taken as the user and presented as a comma-separated list. For
example, if a client is authenticated with principal `,e.jsx(s.code,{children:"zkcli@HADOOP.COM"})," and IP ",e.jsx(s.code,{children:"127.0.0.1"}),`,
the create operation audit log will be:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(s.code,{children:e.jsx(s.span,{className:"line",children:e.jsx(s.span,{children:"session=0x10c0bcb0000 user=zkcli@HADOOP.COM,127.0.0.1 ip=127.0.0.1 operation=create znode=/a result=success"})})})})})]})}function k(i={}){const{wrapper:s}=i.components||{};return s?e.jsx(s,{...i,children:e.jsx(t,{...i})}):t(i)}function a(i,s){throw new Error("Expected component `"+i+"` to be defined: you likely forgot to import, pass, or provide it.")}export{h as _markdown,k as default,d as extractedReferences,o as frontmatter,c as structuredData,p as toc};
