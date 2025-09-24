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

# ZooKeeper Security

The Apache Software Foundation takes security issues very seriously. Due to the infrastructure
nature of the Apache ZooKeeper project specifically, we haven't had many reports over time, but
it doesn't mean that we haven't had concerns over some bugs and vulnerabilities. If you have any
concern or believe you have uncovered a vulnerability, we suggest that you get in touch via the 
e-mail address <a href="mailto:security@zookeeper.apache.org?Subject=[SECURITY] My security issue"
target="_top">security@zookeeper.apache.org</a>. In the message, try to provide a description of
the issue and ideally a way of reproducing it. Note that this security address should be used
only for undisclosed vulnerabilities. Dealing with known issues should be handled regularly 
via jira and the mailing lists. **Please report any security problems to the project security
address before disclosing it publicly.**  

The ASF Security team maintains a page with a description of how vulnerabilities are handled, check
their <a href="https://www.apache.org/security/">Web page</a> for more information.

## Vulnerability reports

* [CVE-2025-58457: Insufficient Permission Check in AdminServer Snapshot/Restore Commands](#CVE-2025-58457)
* [CVE-2024-51504: Authentication bypass with IP-based authentication in Admin Server](#CVE-2024-51504)
* [CVE-2024-23944: Information disclosure in persistent watcher handling](#CVE-2024-23944)
* [CVE-2023-44981: Authorization bypass in SASL Quorum Peer Authentication](#CVE-2023-44981)
* [CVE-2019-0201: Information disclosure vulnerability in Apache ZooKeeper](#CVE-2019-0201)
* [CVE-2018-8012: Apache ZooKeeper Quorum Peer mutual authentication](#CVE-2018-8012)
* [CVE-2017-5637: DOS attack on wchp/wchc four letter words (4lw)](#CVE-2017-5637)
* [CVE-2016-5017: Buffer overflow vulnerability in ZooKeeper C cli shell](#CVE-2016-5017)


<a name="CVE-2025-58457"></a>
### CVE-2025-58457: Insufficient Permission Check in AdminServer Snapshot/Restore Commands

Severity: moderate

Affected versions:

- Apache ZooKeeper (`org.apache.zookeeper:zookeeper`) 3.9.0 before 3.9.4

Description:

Improper permission check in ZooKeeper AdminServer lets authorized clients to run `snapshot` and `restore` command with insufficient permissions.

This issue affects Apache ZooKeeper: from 3.9.0 before 3.9.4.

Users are recommended to upgrade to version 3.9.4, which fixes the issue.

The issue can be mitigated by disabling both commands (via `admin.snapshot.enabled` and `admin.restore.enabled`), disabling the whole AdminServer interface (via `admin.enableServer`), or ensuring that the root ACL does not provide open permissions. (Note that ZooKeeper ACLs are not recursive, so this does not impact operations on child nodes besides notifications from recursive watches.)

Credit:

Damien Diederen <ddiederen@apache.org> (reporter)

References:

[https://zookeeper.apache.org/](https://zookeeper.apache.org/)
[https://www.cve.org/CVERecord?id=CVE-2025-58457](https://www.cve.org/CVERecord?id=CVE-2025-58457)


<a name="CVE-2024-51504"></a>
### CVE-2024-51504: Authentication bypass with IP-based authentication in Admin Server

Severity: important

Affected versions:

- Apache ZooKeeper 3.9.0 before 3.9.3

Description:

When using IPAuthenticationProvider in ZooKeeper Admin Server there is
a possibility of Authentication Bypass by Spoofing -- this only impacts
IP based authentication implemented in ZooKeeper Admin Server. Default
configuration of client's IP address detection
in IPAuthenticationProvider, which uses HTTP request headers, is
weak and allows an attacker to bypass authentication via spoofing
client's IP address in request headers. Default configuration honors X-
Forwarded-For HTTP header to read client's IP address. X-Forwarded-For
request header is mainly used by proxy servers to identify the client
and can be easily spoofed by an attacker pretending that the request
comes from a different IP address. Admin Server commands, such as
snapshot and restore arbitrarily can be executed on successful
exploitation which could potentially lead to information leakage or
service availability issues. Users are recommended to upgrade to
version 3.9.3, which fixes this issue.

Credit:

4ra1n (reporter)
Y4tacker (reporter)

References:

[https://zookeeper.apache.org/](https://zookeeper.apache.org/)
[https://www.cve.org/CVERecord?id=CVE-2024-51504](https://www.cve.org/CVERecord?id=CVE-2024-51504)


<a name="CVE-2024-23944"></a>
### CVE-2024-23944: Information disclosure in persistent watcher handling

Severity: critical

Affected versions:

- Apache ZooKeeper 3.9.0 through 3.9.1
- Apache ZooKeeper 3.8.0 through 3.8.3
- Apache ZooKeeper 3.6.0 through 3.7.2

Description:

Information disclosure in persistent watchers handling in Apache ZooKeeper due to missing ACL check. It allows an attacker to monitor child znodes by attaching a persistent watcher (addWatch command) to a parent which the attacker has already access to. ZooKeeper server doesn't do ACL check when the persistent watcher is triggered and as a consequence, the full path of znodes that a watch event gets triggered upon is exposed to the owner of the watcher. It's important to note that only the path is exposed by this vulnerability, not the data of znode, but since znode path can contain sensitive information like user name or login ID, this issue is potentially critical.

Users are recommended to upgrade to version 3.9.2, 3.8.4 which fixes the issue.

Credit:

周吉安(寒泉) <zhoujian.zja@alibaba-inc.com> (reporter)

References:

[https://zookeeper.apache.org/](https://zookeeper.apache.org/)
[https://www.cve.org/CVERecord?id=CVE-2024-23944](https://www.cve.org/CVERecord?id=CVE-2024-23944)



<a name="CVE-2023-44981"></a>
### CVE-2023-44981: Authorization bypass in SASL Quorum Peer Authentication

Severity: critical

Affected versions:

- Apache ZooKeeper 3.9.0
- Apache ZooKeeper 3.8.0 through 3.8.2
- Apache ZooKeeper 3.7.0 through 3.7.1
- Apache ZooKeeper before 3.7.0

Description:

Authorization Bypass Through User-Controlled Key vulnerability in Apache ZooKeeper. If SASL Quorum Peer authentication is enabled in ZooKeeper (quorum.auth.enableSasl=true), the authorization is done by verifying that the instance part in SASL authentication ID is listed in zoo.cfg server list. The instance part in SASL auth ID is optional and if it's missing, like 'eve@EXAMPLE.COM', the authorization check will be skipped. As a result an arbitrary endpoint could join the cluster and begin propagating counterfeit changes to the leader, essentially giving it complete read-write access to the data tree. Quorum Peer authentication is not enabled by default.

Users are recommended to upgrade to version 3.9.1, 3.8.3, 3.7.2, which fixes the issue.

Alternately ensure the ensemble election/quorum communication is protected by a firewall as this will mitigate the issue.

See the documentation for more details on correct cluster administration.

Credit:

Damien Diederen <ddiederen@apache.org> (reporter)

References:

[https://zookeeper.apache.org/](https://zookeeper.apache.org/)

[https://www.cve.org/CVERecord?id=CVE-2023-44981](https://www.cve.org/CVERecord?id=CVE-2023-44981)


<a name="CVE-2019-0201"></a>
### CVE-2019-0201: Information disclosure vulnerability in Apache ZooKeeper

Severity: Critical
 
Vendor: 
The Apache Software Foundation
 
Versions Affected: 
ZooKeeper prior to 3.4.14
ZooKeeper 3.5.0-alpha through 3.5.4-beta. 
The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected.
 
Description: 
ZooKeeper’s getACL() command doesn’t check any permission when retrieves the ACLs of the requested node 
and returns all information contained in the ACL Id field as plaintext string. DigestAuthenticationProvider 
overloads the Id field with the hash value that is used for user authentication. As a consequence, 
if Digest Authentication is in use, the unsalted hash value will be disclosed by getACL() request 
for unauthenticated or unprivileged users.
 
Mitigation: 
Use an authentication method other than Digest (e.g. Kerberos) or upgrade to 3.4.14 or later (3.5.5 
or later if on the 3.5 branch).
 
Credit: 
This issue was identified by Harrison Neal <harrison@patchadvisor.com> PatchAdvisor, Inc.
 
References: 
https://issues.apache.org/jira/browse/ZOOKEEPER-1392


<a name="CVE-2018-8012"></a>
### CVE-2018-8012: Apache ZooKeeper Quorum Peer mutual authentication

Severity: Critical

Vendor:
The Apache Software Foundation

Versions Affected:
ZooKeeper prior to 3.4.10
ZooKeeper 3.5.0-alpha through 3.5.3-beta
The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected

Description:
No authentication/authorization is enforced when a server attempts to join a quorum. As a result an
arbitrary end point could join the cluster and begin propagating counterfeit changes to the leader.

Mitigation:
Upgrade to 3.4.10 or later (3.5.4-beta or later if on the 3.5 branch) and enable Quorum Peer mutual
authentication.

Alternately ensure the ensemble election/quorum communication is protected by a firewall as this
will mitigate the issue.

See the documentation for more details on correct cluster administration.

Credit:
This issue was identified by Földi Tamás and Eugene Koontz

References:
https://issues.apache.org/jira/browse/ZOOKEEPER-1045
https://cwiki.apache.org/confluence/display/ZOOKEEPER/Server-Server+mutual+authentication
https://zookeeper.apache.org/doc/current/zookeeperAdmin.html


<a name="CVE-2017-5637"></a>
### CVE-2017-5637: DOS attack on wchp/wchc four letter words (4lw) {#CVE-2017-5637}

Severity: moderate

Vendor:
The Apache Software Foundation

Versions Affected:
ZooKeeper 3.4.0 to 3.4.9
ZooKeeper 3.5.0 to 3.5.2
The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected

Note: The 3.5 branch is still beta at this time.

Description:
Two four letter word commands “wchp/wchc” are CPU intensive and could cause spike of CPU utilization
on ZooKeeper server if abused,
which leads to the server unable to serve legitimate client requests. There is no known compromise
which takes advantage of this vulnerability.

Mitigation:
This affects ZooKeeper ensembles whose client port is publicly accessible, so it is recommended to
protect ZooKeeper ensemble with firewall.
Documentation has also been updated to clarify on this point. In addition, a patch (ZOOKEEPER-2693)
is provided to disable "wchp/wchc” commands
by default.
- ZooKeeper 3.4.x users should upgrade to 3.4.10 or apply the patch.
- ZooKeeper 3.5.x users should upgrade to 3.5.3 or apply the patch.

References
[1] https://issues.apache.org/jira/browse/ZOOKEEPER-2693


<a name="CVE-2016-5017"></a>
### CVE-2016-5017: Buffer overflow vulnerability in ZooKeeper C cli shell {#CVE-2016-5017}

Severity: moderate

Vendor:
The Apache Software Foundation

Versions Affected:
ZooKeeper 3.4.0 to 3.4.8
ZooKeeper 3.5.0 to 3.5.2
The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected

Note: The 3.5 branch is still alpha at this time.

Description:
The ZooKeeper C client shells "cli_st" and "cli_mt" have a buffer
overflow vulnerability associated with parsing of the input command
when using the "cmd:<cmd>" batch mode syntax. If the command string
exceeds 1024 characters a buffer overflow will occur. There is no
known compromise which takes advantage of this vulnerability, and if
security is enabled the attacker would be limited by client level
security constraints. The C cli shell is intended as a sample/example
of how to use the C client interface, not as a production tool - the
documentation has also been clarified on this point.

Mitigation:
It is important to use the fully featured/supported Java cli shell rather
than the C cli shell independent of version.

- ZooKeeper 3.4.x users should upgrade to 3.4.9 or apply this
[patch](https://gitbox.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=27ecf981a15554dc8e64a28630af7a5c9e2bdf4f)

- ZooKeeper 3.5.x users should upgrade to 3.5.3 when released or apply
this [patch](https://gitbox.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=f09154d6648eeb4ec5e1ac8a2bacbd2f8c87c14a)

The patch solves the problem reported here, but it does not make the
client ready for production use. The community has no plan to make
this client production ready at this time, and strongly recommends that
users move to the Java cli and use the C cli for illustration purposes only.


Credit:
This issue was discovered by Lyon Yang (@l0Op3r)

References:
[Apache ZooKeeper Security Page](https://zookeeper.apache.org/security.html)

