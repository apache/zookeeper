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

# ZooKeeper Trace's Guide

### A Guide to Deployment and Administration

* [Trace](#zookeeper_trace)
    * [Trace Logger](#trace_logger)
    * [Setting Trace Mask](#setting_trace_mask)
    * [Trace Logger Server](#trace_logger_server)

<a name="zookeeper_trace"></a>

## Trace

ZooKeeper server writes traces which can be collected and post-processed by an external process.
The design is to avoid trace persistence on the same hardware Zookeeper server is running on since
trace volume is usually heavy. Server traces are exported by trace logger running inside zookeeper
server to trace logger server which serves as the external process.

<a name="trace_logger"></a>

### Trace Logger

Trace logger starts up by default with zookeeper server and tries to connect to trace logger server. 
There are five trace logger properties related to trace logger.

* *traceLoggerHost* : (Java system property: **zookeeper.traceLoggerHost**)
  The host of trace logger server. Default to local host.
* *traceLoggerPort* : (Java system property: **zookeeper.traceLoggerPort**)
  The port of trace logger server to connect. Default to port 2200.
* *traceLoggerWindowSize* : (Java system property: **zookeeper.traceLoggerWindowSize**)
  Number of traces for trace logger server to send acknowledgement. Default to 1000.
* *traceLoggerMaxOutstanding* : (Java system property: **zookeeper.traceLoggerMaxOutstanding**)
  Maximum number of traces for trace logger to buffer in memory. Default to 100,000.
* *disableTraceLogger* : (Java system property: **zookeeper.disableTraceLogger**)
  Stop trace logger when true and all traces are dropped. Default to false.

When trace logger is not able to connect with its server, all traces will be dropped silently.


<a name="setting_trace_mask"></a>

### Setting Trace Mask

Server traces are divided into different categories and trace mask can be used enable or 
disable certain type of traces. By default trace mask is set to zero which disables all traces.

* *traceMask* :
    (Java system property: **zookeeper.traceMask**)
    The property is an integer with each bit mapped to a specific category as defined below. 
    Default value is 0.
    * CLIENT_REQUEST_TRACE_MASK = 1
    * CLIENT_DATA_PACKET_TRACE_MASK = 1 << 1
    * CLIENT_PING_TRACE_MASK = 1 << 3
    * SERVER_PACKET_TRACE_MASK = 1 << 4
    * SESSION_TRACE_MASK = 1 << 5
    * EVENT_DELIVERY_TRACE_MASK = 1 << 6
    * SERVER_PING_TRACE_MASK = 1 << 7
    * WARNING_TRACE_MASK = 1 << 8
    * JMX_TRACE_MASK = 1 << 9
    * QUORUM_TRACE_MASK = 1 << 10

<a name="trace_logger_server"></a>

### Trace Logger Server

Trace logger server is an external process which is responsible for collecting and post-processing 
traces. It can be started with the script from zookeeper root directory which writes traces 
received into log file. Default log file is defined inside conf/log4j.properties.

    $ bin/zkTraceServer.sh
    
Compared with default implementation of file persistence, trace logger server can be extended to
write traces into structured storage which supports analytics like filtering and grouping. 
