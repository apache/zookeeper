<!--
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

# Apache ZooKeeper&trade; Releases

The Apache ZooKeeper system for distributed coordination is a high-performance service for building distributed applications.

* [Release strategy](#release-strategy)
* [Download](#download)
* [Verifying Hashes and Signatures](#verifying)
* [News](#news)

<a name="release-strategy"></a>
## Release strategy

The Apache ZooKeeper community supports two release branches at a time: **stable** and **current**. The **stable** 
version of ZooKeeper is 3.8.x and the **current** version is 3.9.x. Once a new minor version is released, the **stable** 
version is expected to be decommissioned soon and in approximately half a year will be announced as End-of-Life. During 
the half year grace period only security and critical fixes are expected to be released for the version. After EoL is 
announced no further patches are provided by the community. All ZooKeeper releases will remain accessible 
from [the official Apache Archives](https://archive.apache.org/dist/zookeeper/).

<a name="download"></a>
## Download

Apache ZooKeeper 3.9.4 is our current release, and 3.8.4 our latest stable release.

### Apache ZooKeeper 3.9.4

[Apache ZooKeeper 3.9.4](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4-bin.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4-bin.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4-bin.tar.gz.sha512))

[Apache ZooKeeper 3.9.4 Source Release](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.9.4/apache-zookeeper-3.9.4.tar.gz.sha512))

### Apache ZooKeeper 3.8.4 (latest stable release)

[Apache ZooKeeper 3.8.4](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4-bin.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4-bin.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4-bin.tar.gz.sha512))

[Apache ZooKeeper 3.8.4 Source Release](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.8.4/apache-zookeeper-3.8.4.tar.gz.sha512))

### Apache ZooKeeper 3.7.2 (3.7 is EoL since 2nd of February, 2024)

[Apache ZooKeeper 3.7.2](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2-bin.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2-bin.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2-bin.tar.gz.sha512))

[Apache ZooKeeper 3.7.2 Source Release](https://www.apache.org/dyn/closer.lua/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2.tar.gz)([asc](https://downloads.apache.org/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2.tar.gz.asc), [sha512](https://downloads.apache.org/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2.tar.gz.sha512))


---

Older releases are available [in the archive](https://archive.apache.org/dist/zookeeper/).

<a name="verifying"></a>
## Verifying Hashes and Signatures

You can verify the integrity of a downloaded release using release-signing [KEYS](https://downloads.apache.org/zookeeper/KEYS). For additional information, refer to the Apache documentation for [verifying the integrity of Apache project releases](https://www.apache.org/info/verification.html).

<a name="news"></a>
## News

### 29 Aug, 2025: release 3.9.4 available
This is a bugfix release for 3.9 branch.

See [ZooKeeper 3.9.4 Release Notes](https://zookeeper.apache.org/doc/r3.9.4/releasenotes.html) for details.

### 24 Oct, 2024: release 3.9.3 available
This is a bugfix release for 3.9 branch.

See [ZooKeeper 3.9.3 Release Notes](https://zookeeper.apache.org/doc/r3.9.3/releasenotes.html) for details.

### 12 Mar, 2024: release 3.9.2 available
This is a bugfix release for 3.9 branch.

See [ZooKeeper 3.9.2 Release Notes](https://zookeeper.apache.org/doc/r3.9.2/releasenotes.html) for details.

### 5 Mar, 2024: release 3.8.4 available
This is a bugfix release for 3.8 branch.

See [ZooKeeper 3.8.4 Release Notes](https://zookeeper.apache.org/doc/r3.8.4/releasenotes.html) for details.

### 2 February, 2024: Apache ZooKeeper 3.7 End-of-Life
The Apache ZooKeeper community would like to make the official
announcement of 3.7 release line End-of-Life. It will be effective on
2nd of February, 2024 00:01 AM (PDT). From that day forward the 3.7
version of Apache ZooKeeper won’t be supported by the community which
means we won't

- accept patches on the 3.7.x branch,
- run automated tests on any JDK version,
- create new releases from 3.7.x branch,
- resolve security issues, CVEs or critical bugs.

Latest released version of Apache ZooKeeper 3.7 (currently 3.7.2) will
be available on the download page for another year (until 2nd of
February, 2025), after that it will be accessible among other
historical versions from Apache Archives.

=== Upgrade ===

We recommend users of Apache ZooKeeper 3.7 to plan your production
upgrades according to the following supported upgrade path:

1) Upgrade to latest 3.8.x version
2) (Optional) Upgrade to latest 3.9.x version.

Please find known upgrade issues and workarounds on the following wiki
page: [Upgrade FAQ](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Upgrade+FAQ)

In addition to that the user@ mailing list is open 24/7 to help and
answer your questions as usual.

=== Compatibility ===

Our backward compatibility rules still apply and can be found here:
[Backward compatibility rules](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ReleaseManagement)

Following the recommended upgrade path with rolling upgrade process
ZooKeeper quorum will be available at all times as long as clients are
not starting to use new features.

### 9 Oct, 2023: release 3.9.1 available
This is a bugfix release for 3.9 branch.

See [ZooKeeper 3.9.1 Release Notes](https://zookeeper.apache.org/doc/r3.9.1/releasenotes.html) for details.

### 9 Oct, 2023: release 3.8.3 available
This is a bugfix release for 3.8 branch.

See [ZooKeeper 3.8.3 Release Notes](https://zookeeper.apache.org/doc/r3.8.3/releasenotes.html) for details.

### 9 Oct, 2023: release 3.7.2 available
This is a bugfix release for 3.7 branch.

See [ZooKeeper 3.7.2 Release Notes](https://zookeeper.apache.org/doc/r3.7.2/releasenotes.html) for details.

### 3 Aug, 2023: release 3.9.0 available
This is the first release for the 3.9 branch.\
It is a major release and it introduces a lot of new features, most notably:

- Admin server API for taking snapshot and stream out the data
- Communicate the Zxid that triggered a WatchEvent to fire
- TLS - dynamic loading for client trust/key store
- Add Netty-TcNative OpenSSL Support
- Adding SSL support to Zktreeutil
- Improve syncRequestProcessor performance
- Updates to all the third party dependencies to get rid of every known
  CVE.

ZooKeeper clients from 3.5.x onwards are fully compatible with 3.9.x servers.\
The upgrade from 3.7.x and 3.8.x can be executed as usual, no particular additional upgrade procedure is needed.\
ZooKeeper 3.9.x clients are compatible with 3.5.x, 3.6.x, 3.7.x and 3.8.x servers as long as you are not using new APIs not present these versions.

See [ZooKeeper 3.9.0 Release Notes](https://zookeeper.apache.org/doc/r3.9.0/releasenotes.html) for details.

Latest stable version of ZooKeeper is now 3.8.2.

### 18 Jul, 2023: release 3.8.2 available
This is a bugfix release for 3.8 branch.

See [ZooKeeper 3.8.2 Release Notes](https://zookeeper.apache.org/doc/r3.8.2/releasenotes.html) for details.

### 30 Jan, 2023: release 3.8.1 available
This is a bugfix release for 3.8 branch.

See [ZooKeeper 3.8.1 Release Notes](https://zookeeper.apache.org/doc/r3.8.1/releasenotes.html) for details.

### 30 December, 2022: release 3.6.4 available
This is the last bugfix release for 3.6 branch, as 3.6 is EoL since 30th December, 2022.\
It fixes 42 issues, including CVE fixes, log4j1 removal (using reload4j from now)\
and various other bug fixes (e.g. snapshotting, SASL and C client related fixes).

See [ZooKeeper 3.6.4 Release Notes](https://zookeeper.apache.org/doc/r3.6.4/releasenotes.html) for details.

### 30 December, 2022: Apache ZooKeeper 3.6 End-of-Life
The Apache ZooKeeper community would like to make the official announcement of
3.6 release line End-of-Life. It will be effective on 30th of December, 2022 00:01 AM
(PDT). From that day forward the 3.6 version of Apache ZooKeeper won’t be
supported by the community which means we won’t 

- accept patches on the 3.6.x branch,
- run automated tests on any JDK version,
- create new releases from 3.6.x branch,
- resolve security issues, CVEs or critical bugs.

Latest released version of Apache ZooKeeper 3.6 (currently 3.6.4) will be
available on the download page for another year (until 30th of December, 2023), after
that it will be accessible among other historical versions from Apache Archives.

=== Upgrade ===

We recommend users of Apache ZooKeeper 3.6 to plan your production upgrades
according to the following supported upgrade path:

1) Upgrade to latest 3.7.x version\
2) Upgrade to latest 3.8.x version\
3) (Optional) Upgrade to latest 3.9.x version.

Please find known upgrade issues and workarounds on the following wiki page:
[Upgrade FAQ](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Upgrade+FAQ)

In addition to that the user@ mailing list is open 24/7 to help and answer your
questions as usual.

=== Compatibility ===

Our backward compatibility rules still apply and can be found here:
[Backward compatibility rules](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ReleaseManagement)

Following the recommended upgrade path with rolling upgrade process ZooKeeper
quorum will be available at all times as long as clients are not starting to use
new features.

### 4 June, 2022: release 3.5.10 available
This is the last bugfix release for 3.5 branch, as 3.5 is EoL since 1st June, 2022.\
It fixes 44 issues, including CVE fixes, log4j1 removal (using reload4j from now)\
and various other bug fixes (thread leaks, data corruption, snapshotting and SASL related fixes).

See [ZooKeeper 3.5.10 Release Notes](https://zookeeper.apache.org/doc/r3.5.10/releasenotes.html) for details.

### 1 June, 2022: Apache ZooKeeper 3.5 End-of-Life
The Apache ZooKeeper community would like to make the official announcement of
3.5 release line End-of-Life. It will be effective on 1st of June, 2022 00:01 AM
(PDT). From that day forward the 3.5 version of Apache ZooKeeper won’t be
supported by the community which means we won’t 

- accept patches on the 3.5.x branch,
- run automated tests on any JDK version,
- create new releases from 3.5.x branch,
- resolve security issues, CVEs or critical bugs.

Latest released version of Apache ZooKeeper 3.5 (currently 3.5.9) will be
available on the download page for another year (until 1st of June, 2023), after
that it will be accessible among other historical versions from Apache Archives.

=== Upgrade ===

We recommend users of Apache ZooKeeper 3.5 to plan your production upgrades
according to the following supported upgrade path:

1) Upgrade to latest 3.5.x version\
2) Upgrade to latest 3.6.x version\
3) (Optional) Upgrade to latest 3.7.x version.

Please find known upgrade issues and workarounds on the following wiki page:
[Upgrade FAQ](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Upgrade+FAQ)

In addition to that the user@ mailing list is open 24/7 to help and answer your
questions as usual.

=== Compatibility ===

Our backward compatibility rules still apply and can be found here:
[Backward compatibility rules](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ReleaseManagement)

Following the recommended upgrade path with rolling upgrade process ZooKeeper
quorum will be available at all times as long as clients are not starting to use
new features.

### 12 May, 2022: release 3.7.1 available
This is a bugfix release for 3.7 branch.\
It fixes 64 issues, including multiple CVE fixes.

See [ZooKeeper 3.7.1 Release Notes](https://zookeeper.apache.org/doc/r3.7.1/releasenotes.html) for details.

### 7 March, 2022: release 3.8.0 available

This is the first release for the 3.8 branch.\
It is a major release and it introduces a lot of new features, most notably:

 * Migration of the logging framework from Apache Log4j1 to LogBack
 * Read Key/Trust store password from file (and other security related improvements)
 * Restored support for OSGI
 * Reduced the performance impact of Prometheus metrics
 * Official support for JDK17 (all tests are passing)
 * Updates to all the third party dependencies to get rid of every known CVE.

ZooKeeper clients from 3.5.x onwards are fully compatible with 3.8.x servers.\
The upgrade from 3.6.x and 3.7.x can be executed as usual, no particular additional upgrade procedure is needed.\
ZooKeeper 3.8.x clients are compatible with 3.5.x, 3.6.x and 3.7.x servers as long as you are not using new APIs not present these versions.

See [ZooKeeper 3.8.0 Release Notes](https://zookeeper.apache.org/doc/r3.8.0/releasenotes.html) for details.

### 13 April, 2021: release 3.6.3 available
This is a bugfix release for 3.6 branch.\
It fixes 52 issues, including multiple CVE fixes.

See [ZooKeeper 3.6.3 Release Notes](https://zookeeper.apache.org/doc/r3.6.3/releasenotes.html) for details.

### 27 March, 2021: release 3.7.0 available

This is the first release for the 3.7 branch.\
It introduces a number of new features, notably:

  * An API to start a ZooKeeper server from Java ([ZOOKEEPER-3874](https://issues.apache.org/jira/browse/ZOOKEEPER-3874));
  * Quota enforcement ([ZOOKEEPER-3301](https://issues.apache.org/jira/browse/ZOOKEEPER-3301));
  * Host name canonicalization in quorum SASL authentication ([ZOOKEEPER-4030](https://issues.apache.org/jira/browse/ZOOKEEPER-4030));
  * Support for BCFKS key/trust store format ([ZOOKEEPER-3950](https://issues.apache.org/jira/browse/ZOOKEEPER-3950));
  * A choice of mandatory authentication scheme(s) ([ZOOKEEPER-3561](https://issues.apache.org/jira/browse/ZOOKEEPER-3561));
  * A "whoami" API and CLI command ([ZOOKEEPER-3969](https://issues.apache.org/jira/browse/ZOOKEEPER-3969));
  * The possibility of disabling digest authentication ([ZOOKEEPER-3979](https://issues.apache.org/jira/browse/ZOOKEEPER-3979));
  * Multiple SASL "superUsers" ([ZOOKEEPER-3959](https://issues.apache.org/jira/browse/ZOOKEEPER-3959));
  * Fast-tracking of throttled requests ([ZOOKEEPER-3683](https://issues.apache.org/jira/browse/ZOOKEEPER-3683));
  * Additional security metrics ([ZOOKEEPER-3978](https://issues.apache.org/jira/browse/ZOOKEEPER-3978));
  * SASL support in the C and Perl clients ([ZOOKEEPER-1112](https://issues.apache.org/jira/browse/ZOOKEEPER-1112), ZOOKEEPER-3714);
  * A new zkSnapshotComparer.sh tool ([ZOOKEEPER-3427](https://issues.apache.org/jira/browse/ZOOKEEPER-3427));
  * Notes on how to benchmark ZooKeeper with the YCSB tool ([ZOOKEEPER-3264](https://issues.apache.org/jira/browse/ZOOKEEPER-3264)).

ZooKeeper clients from the 3.5 and 3.6 branches are fully compatible with 3.7 servers.\
The upgrade from 3.6.x to 3.7.0 can be executed as usual, no particular additional upgrade procedure is needed.\
ZooKeeper 3.7.0 clients are compatible with 3.5 and 3.6 servers as long as you are not using new APIs not present these versions.

See [ZooKeeper 3.7.0 Release Notes](https://zookeeper.apache.org/doc/r3.7.0/releasenotes.html) for details.

### 15 January, 2021: release 3.5.9 available
This is a bugfix release for 3.5 branch.\
It fixes 25 issues, including multiple CVE fixes.

See [ZooKeeper 3.5.9 Release Notes](https://zookeeper.apache.org/doc/r3.5.9/releasenotes.html) for details.

### 9 September, 2020: release 3.6.2 available
This is a bugfix release for 3.6 branch.\
It is a minor release and it fixes a few critical issues and brings a few dependencies upgrades.

See [ZooKeeper 3.6.2 Release Notes](https://zookeeper.apache.org/doc/r3.6.2/releasenotes.html) for details.

### 11 May, 2020: release 3.5.8 available
This is a bugfix release for 3.5 branch.\
It fixes 24 issues, including third party CVE fixes, several leader-election related fixes and a compatibility issue with applications built against earlier 3.5 client libraries (by restoring a few non public APIs).

See [ZooKeeper 3.5.8 Release Notes](https://zookeeper.apache.org/doc/r3.5.8/releasenotes.html) for details.

### 30 April, 2020: release 3.6.1 available
This is the second release for 3.6 branch.\
It is a bugfix release and it fixes a few compatibility issues with applications built for ZooKeeper 3.5.
The upgrade from 3.5.7 to 3.6.1 can be executed as usual, no particular additional upgrade procedure is needed.
ZooKeeper 3.6.1 clients are compatible with 3.5 servers as long as you are not using new APIs not present in 3.5.

See [ZooKeeper 3.6.1 Release Notes](https://zookeeper.apache.org/doc/r3.6.1/releasenotes.html) for details.

### 04 March, 2020: release 3.6.0 available
This is the first release for 3.6 branch.\
It comes with lots of new features and improvements around performance and security. It is also introducing new APIS on the client side.\
ZooKeeper clients from 3.4 and 3.5 branch are fully compatible with 3.6 servers.
The upgrade from 3.5.7 to 3.6.0 can be executed as usual, no particular additional upgrade procedure is needed.
ZooKeeper 3.6.0 clients are compatible with 3.5 servers as long as you are not using new APIs not present in 3.5.

See [ZooKeeper 3.6.0 Release Notes](https://zookeeper.apache.org/doc/r3.6.0/releasenotes.html) for details.

### 14 February, 2020: release 3.5.7 available
This is a bugfix release for 3.5 branch.\
It fixes 25 issues, including third party CVE fixes, potential data loss and potential split brain if some rare conditions exists.

See [ZooKeeper 3.5.7 Release Notes](https://zookeeper.apache.org/doc/r3.5.7/releasenotes.html) for details.

### 19 October, 2019: release 3.5.6 available
This is a bugfix release for 3.5 branch.\
It fixes 29 issues, including CVE fixes, hostname resolve issue and possible memory leak.

See [ZooKeeper 3.5.6 Release Notes](https://zookeeper.apache.org/doc/r3.5.6/releasenotes.html) for details.

### 20 May, 2019: release 3.5.5 available

First stable version of 3.5 branch. This release is considered to be the successor of 3.4 stable branch and recommended for production use.\
It contains 950 commits, resolves 744 issues, fixes 470 bugs and includes the following new features:

* Dynamic reconfiguration
* Local sessions
* New node types: Container, TTL
* SSL support for Atomic Broadcast Protocol
* Ability to remove watchers
* Multi-threaded commit processor
* Upgraded to Netty 4.1
* Maven build

Various performance and stability improvements.

Please also note:

* Minimum recommended JDK version is now 1.8
* Release artifacts have been changed considerably:
    * apache-zookeeper-X.Y.Z.tar.gz is standard source-only release,
    * apache-zookeeper-X.Y.Z-bin.tar.gz is the convenience tarball which contains the binaries

Thanks to the contributors for their tremendous efforts to make this release happen.

See [ZooKeeper 3.5.5 Release Notes](https://zookeeper.apache.org/doc/r3.5.5/releasenotes.html) for details.

### 2 April, 2019: release 3.4.14 available

This is a bugfix release. It fixes 8 issues, mostly build / unit tests issues, dependency updates flagged by OWASP, NPE and a name resolution problem. Among these it also supports experimental Maven build and Markdown based documentation generation. See [ZooKeeper 3.4.14 Release Notes](https://zookeeper.apache.org/doc/r3.4.14/releasenotes.html) for details.

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
