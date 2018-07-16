---
layout: page
title: Releases
---
# Apache ZooKeeper&trade; Releases

The Apache ZooKeeper system for distributed coordination is a high-performance service for building distributed applications.

* [Download](#download)
* [Release Notes](#releasenotes)
* [News](#news)

## Download {#download}

Active releases may be downloaded from Apache mirrors: [Download](https://www.apache.org/dyn/closer.cgi/zookeeper/)

On the mirror, all recent releases are available.

Older releases are available in the [archive](https://archive.apache.org/dist/zookeeper/).

You can verify the integrity of a downloaded release using the PGP signatures and hashes (MD5 or SHA1) hosted at the main [Apache distro site](https://apache.org/dist/zookeeper/).  For additional information, refer to the Apache documentation for [verifying the integrity of Apache project releases](https://www.apache.org/dyn/closer.cgi#verify).

## Release Notes {#releasenotes}

Release notes for Apache Zookeeper releases are available in Jira: [Browse release notes](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel)

## News {#news}

### 15 July, 2018: release 3.4.13 available

This is a bugfix release. It fixes 17 issues, including issues such as ZOOKEEPER-2959 that could cause data loss when observer is used, and ZOOKEEPER-2184 that prevents ZooKeeper Java clients working in dynamic IP (container / cloud) environment. See [ZooKeeper 3.4.13 Release Notes](https://zookeeper.apache.org/doc/r3.4.13/releasenotes.html) for details.

### 17 May, 2018: release 3.5.4-beta available

3.5.4-beta is the second beta in the planned 3.5 release line leading up to a stable 3.5 release. It comprises 113 bug fixes and improvements.

Release 3.5.3 added a new feature ZOOKEEPER-2169 "Enable creation of nodes with TTLs". There was a major oversight when TTL nodes were implemented. The session ID generator for each server is seeded with the configured Server ID in the high byte. TTL Nodes were using the highest bit to denote a TTL node when used in the ephemeral owner. This meant that Server IDs > 127 that created ephemeral nodes would have those nodes always considered TTL nodes (with the TTL being essentially a random number). ZOOKEEPER-2901 fixes the issue. By default TTL is disabled and must now be enabled in zoo.cfg. When TTL Nodes are enabled, the max Server ID changes from 255 to 254. See the documentation for TTL in the administrator guide (or the referenced JIRAs) for more details.

### 1 May, 2018: release 3.4.12 available

This release fixes 22 issues, including issues that affect incorrect handling of the dataDir and the dataLogDir.  See [ZooKeeper 3.4.12 Release Notes](https://zookeeper.apache.org/doc/r3.4.12/releasenotes.html) for details.

### 9 November, 2017: release 3.4.11 available

This release fixes 53 issues, it includes support for Java 9 and other critical bug fixes.  See [ZooKeeper 3.4.11 Release Notes](https://zookeeper.apache.org/doc/r3.4.11/releasenotes.html) for details.

**WARNING**: [ZOOKEEPER-2960](https://issues.apache.org/jira/browse/ZOOKEEPER-2960) was recently identified as a regression in 3.4.11 affecting the specification of separate dataDir and dataLogDir configuration parameters (vs the default which is a single directory for both). It will be addressed in 3.4.12.

### 17 April, 2017: release 3.5.3-beta available

3.5.3-beta is the first beta in the planned 3.5 release line leading up to a stable 3.5 release. It comprises 76 bug fixes and improvements. This release includes important security fix around dynamic reconfigure API, improvements on test infrastructure, and new features such as TTL node.

### 30 March, 2017: release 3.4.10 available

This release fixes 43 issues, including security feature QuorumPeer mutual authentication via SASL and other critical bugs. See [ZooKeeper 3.4.10 Release Notes](https://zookeeper.apache.org/doc/r3.4.10/releasenotes.html) for details.

### 03 September, 2016: release 3.4.9 available

This release fixes many critical bugs and improvements. See [ZooKeeper 3.4.9 Release Notes](https://zookeeper.apache.org/doc/r3.4.9/releasenotes.html) for details.

### 20 July, 2016: release 3.5.2-alpha available

This is an alpha quality release that contains many bug fixes and improvements.

### 20 February, 2016: release 3.4.8 available

This release fixes 9 issues, most notably a deadlock when shutting down ZooKeeper. See [ZooKeeper 3.4.8 Release Notes](https://zookeeper.apache.org/doc/r3.4.8/releasenotes.html) for details.

### 31 August, 2015: release 3.5.1-alpha available

This is an alpha quality release that contains many bug fixes and improvements. It also introduces a few new features, including container znodes and SSL support for client-server communication.

See the [ZooKeeper 3.5.1-alpha Release Notes](https://zookeeper.apache.org/doc/r3.5.1-alpha/releasenotes.html) for details.

### 6 August, 2014: release 3.5.0-alpha available

This release is alpha quality and contains many improvements, new features, bug fixes and optimizations.

See the [ZooKeeper 3.5.0-alpha Release Notes](https://zookeeper.apache.org/doc/r3.5.0-alpha/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 10 March, 2014: release 3.4.6 available

The release fixes a critical bug that could prevent a server from joining an established ensemble. See
[ZooKeeper 3.4.6 Release Notes](https://zookeeper.apache.org/doc/r3.4.6/releasenotes.html) for details.

### 18 November, 2012: release 3.4.5 available

The release fixes a critical bug that could cause client connection issues. See
[ZooKeeper 3.4.5 Release Notes](https://zookeeper.apache.org/doc/r3.4.5/releasenotes.html) for details.

### 23 September, 2012: release 3.4.4 available

The release fixes a critical bug that could cause data inconsistency. See
[ZooKeeper 3.4.4 Release Notes](https://zookeeper.apache.org/doc/r3.4.4/releasenotes.html) for details.


### 02 August, 2012: release 3.3.6 available

The release fixes a critical bug that could cause data loss. See
[ZooKeeper 3.3.6 Release Notes](https://zookeeper.apache.org/doc/r3.3.6/releasenotes.html) for details.

### 20 March, 2012: release 3.3.5 available

The release fixes a critical bug that could cause data corruption. See
[ZooKeeper 3.3.5 Release Notes](https://zookeeper.apache.org/doc/r3.3.5/releasenotes.html) for details.

### 13 Feb, 2012: release 3.4.3 available

This release fixes  critical bugs in 3.4.2. See
[ZooKeeper 3.4.3 Release Notes](https://zookeeper.apache.org/doc/r3.4.3/releasenotes.html) for details.

We are now upgrading this release to a beta release given that we have had quite a few bug fixes to 3.4 branch and 3.4 releases have been out for some time now.

### 29 Dec, 2011: release 3.4.2 available

This release fixes a critical bug in 3.4.1. See
[ZooKeeper 3.4.2 Release Notes](https://zookeeper.apache.org/doc/r3.4.2/releasenotes.html) for details.

Please note that this is still an alpha release and we do not recommend this for production. Please use the stable release line 3.3.* for production use. 


### 16 Dec, 2011: release 3.4.1 available

This release fixes a critical bug with data loss in 3.4.0. See
[ZooKeeper 3.4.1 Release Notes](https://zookeeper.apache.org/doc/r3.4.1/releasenotes.html) for details.
In case you are already using 3.4.0 release please upgrade ASAP.

Please note that this is an alpha release and not ready for production as of now.

### 26 Nov, 2011: release 3.3.4 available

The release fixes a number of critical bugs that could cause data corruption. See
[ZooKeeper 3.3.4 Release Notes](https://zookeeper.apache.org/doc/r3.3.4/releasenotes.html) for details.

### 22 Nov, 2011: release 3.4.0 available

Due to data loss issues, this release has been removed from the downloads page. Release 3.4.1 is now available. 

### 27 Feb, 2011: release 3.3.3 available

The release fixes two critical bugs that could cause data corruption. It also addresses 12 other issues. See
[ZooKeeper 3.3.3 Release Notes](https://zookeeper.apache.org/doc/r3.3.3/releasenotes.html) for details.

### 11 Nov, 2010: release 3.3.2 available

This release contains a number of critical bug fixes.

See the [ZooKeeper 3.3.2 Release Notes](https://zookeeper.apache.org/doc/r3.3.2/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 17 May, 2010: release 3.3.1 available

This release contains a number of critical bug fixes.

See the [ZooKeeper 3.3.1 Release Notes](https://zookeeper.apache.org/doc/r3.3.1/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 25 March, 2010: release 3.3.0 available

This release contains many improvements, new features, bug fixes and optimizations.

See the [ZooKeeper 3.3.0 Release Notes](https://zookeeper.apache.org/doc/r3.3.0/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 14 December, 2009: release 3.2.2 available

This release contains a number of critical bug fixes.

See the [ZooKeeper 3.2.2 Release Notes](https://zookeeper.apache.org/doc/r3.2.2/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 14 December, 2009: release 3.1.2 available

This release contains a number of critical bug fixes.

See the [ZooKeeper 3.1.2 Release Notes](https://zookeeper.apache.org/doc/r3.1.2/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 4 September, 2009: release 3.2.1 available

This release contains a number of critical bug fixes.

See the [ZooKeeper 3.2.1 Release Notes](https://zookeeper.apache.org/doc/r3.2.1/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 8 July, 2009: release 3.2.0 available

This release contains many improvements, new features, bug fixes and optimizations.

See the [ZooKeeper 3.2.0 Release Notes](https://zookeeper.apache.org/doc/r3.2.0/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 27 March, 2009: release 3.1.1 available

This release contains a small number of bug fixes.

See the [ZooKeeper 3.1.1 Release Notes](https://zookeeper.apache.org/doc/r3.1.1/releasenotes.html) for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 13 February, 2009: release 3.1.0 available

This release contains many improvements, new features, bug fixes and optimizations.

See the ZooKeeper 3.1.0 Release Notes for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 4 December, 2008: release 3.0.1 available

This release contains many improvements, new features, bug fixes and optimizations.

See the ZooKeeper 3.0.1 Release Notes for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.

### 27 October, 2008: release 3.0.0 available

This release contains many improvements, new features, bug fixes and optimizations.

See the ZooKeeper 3.0.0 Release Notes for details. Alternatively, you can look at the [Jira](https://issues.apache.org/jira/browse/ZOOKEEPER?report=com.atlassian.jira.plugin.system.project:changelog-panel) issue log for all releases.


