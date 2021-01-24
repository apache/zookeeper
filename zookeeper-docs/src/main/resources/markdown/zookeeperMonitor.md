<!--
Copyright 2002-2021 The Apache Software Foundation

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

# ZooKeeper Monitor Guide

* [New Metrics System](#Metrics-System)
    * [Metrics](#Metrics)
    * [Prometheus](#Prometheus)
    * [Grafana](#Grafana)
    * [InfluxDB](#influxdb)

* [JMX](#JMX)

* [Four letter words](#four-letter-words)

<a name="Metrics-System"></a>

## New Metrics System
The feature:`New Metrics System` has been available since 3.6.0 which provides the abundant metrics
to help users monitor the ZooKeeper on the topic: znode, network, disk, quorum, leader election,
client, security, failures, watch/session, requestProcessor, and so forth.

<a name="Metrics"></a>

### Metrics
All the metrics are included in the `ServerMetrics.java`.

<a name="Prometheus"></a>

### Prometheus
- Running a [Prometheus](https://prometheus.io/) monitoring service is the easiest way to ingest and record ZooKeeper's metrics.
- Pre-requisites:
  - enable the `Prometheus MetricsProvider` by setting `metricsProvider.className=org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider` in the zoo.cfg.
  - the Port is also configurable by setting `metricsProvider.httpPort`（the default value:7000）
- Install Prometheus:
  Go to the official website download [page](https://prometheus.io/download/), download the latest release.
  
- Set Prometheus's scraper to target the ZooKeeper cluster endpoints:

    ```bash
    cat > /tmp/test-zk.yaml <<EOF
    global:
      scrape_interval: 10s
    scrape_configs:
      - job_name: test-zk
        static_configs:
        - targets: ['192.168.10.32:7000','192.168.10.33:7000','192.168.10.34:7000']
    EOF
    cat /tmp/test-zk.yaml
    ```

- Set up the Prometheus handler:

    ```bash
    nohup /tmp/prometheus \
        --config.file /tmp/test-zk.yaml \
        --web.listen-address ":9090" \
        --storage.tsdb.path "/tmp/test-zk.data" >> /tmp/test-zk.log  2>&1 &
    ```

- Now Prometheus will scrape zk metrics every 10 seconds.

<a name="Grafana"></a>

### Grafana
- Grafana has built-in Prometheus support; just add a Prometheus data source:

    ```bash
    Name:   test-zk
    Type:   Prometheus
    Url:    http://localhost:9090
    Access: proxy
    ```
- Then download and import the default ZooKeeper dashboard [template](https://grafana.com/dashboards/10465) and customize.
- Users can ask for Grafana dashboard account if having any good improvements by writing a email to **dev@zookeeper.apache.org**.

<a name="influxdb"></a>

### InfluxDB

InfluxDB is an open source time series data that is often used to store metrics
from Zookeeper. You can [download](https://portal.influxdata.com/downloads/) the
open source version or create a [free](https://cloud2.influxdata.com/signup)
account on InfluxDB Cloud. In either case, configure the [Apache Zookeeper
Telegraf plugin](https://www.influxdata.com/integration/apache-zookeeper/) to
start collecting and storing metrics from your Zookeeper clusters into your
InfluxDB instance. There is also an [Apache Zookeeper InfluxDB
template](https://www.influxdata.com/influxdb-templates/zookeeper-monitor/) that
includes the Telegraf configurations and a dashboard to get you set up right
away.

<a name="JMX"></a>
## JMX
More details can be found in [here](http://zookeeper.apache.org/doc/current/zookeeperJMX.html)

<a name="four-letter-words"></a>
## Four letter words
More details can be found in [here](http://zookeeper.apache.org/doc/current/zookeeperAdmin.html#sc_zkCommands)
