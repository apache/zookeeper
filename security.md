---
layout: page
---
# ZooKeeper Security

The Apache Software Foundation takes security issues very seriously. Due to the infrastructure nature of the Apache ZooKeeper project specifically, we haven't had many reports over time, but it doesn't mean that we haven't had concerns over some bugs and vulnerabilities. If you have any concern or believe you have uncovered a vulnerability, we suggest that you get in touch via the e-mail address <a href="mailto:security@zookeeper.apache.org?Subject=[SECURITY] My security issue" target="_top">security@zookeeper.apache.org</a>. In the message, try to provide a description of the issue and ideally a way of reproducing it. Note that this security address should be used only for undisclosed vulnerabilities. Dealing with known issues should be handled regularly via jira and the mailing lists. **Please report any security problems to the project security address before disclosing it publicly.**  

The ASF Security team maintains a page with a description of how vulnerabilities are handled, check their <a href="https://www.apache.org/security/">Web page</a> for more information.

## Vulnerability reports

* [CVE-2016-5017: Buffer overflow vulnerability in ZooKeeper C cli shell](#CVE-2016-5017)
* [CVE-2017-5637: DOS attack on wchp/wchc four letter words (4lw)](#CVE-2017-5637)


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

- ZooKeeper 3.4.x users should upgrade to 3.4.9 or apply this [patch](https://git-wip-us.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=27ecf981a15554dc8e64a28630af7a5c9e2bdf4f)

- ZooKeeper 3.5.x users should upgrade to 3.5.3 when released or apply
this [patch](https://git-wip-us.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=f09154d6648eeb4ec5e1ac8a2bacbd2f8c87c14a)

The patch solves the problem reported here, but it does not make the
client ready for production use. The community has no plan to make
this client production ready at this time, and strongly recommends that
users move to the Java cli and use the C cli for illustration purposes only.


Credit:
This issue was discovered by Lyon Yang (@l0Op3r)

References:
[Apache ZooKeeper Security Page](https://zookeeper.apache.org/security.html)


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
Two four letter word commands “wchp/wchc” are CPU intensive and could cause spike of CPU utilization on ZooKeeper server if abused,
which leads to the server unable to serve legitimate client requests. There is no known compromise which takes advantage of this vulnerability.

Mitigation:
This affects ZooKeeper ensembles whose client port is publicly accessible, so it is recommended to protect ZooKeeper ensemble with firewall.
Documentation has also been updated to clarify on this point. In addition, a patch (ZOOKEEPER-2693) is provided to disable "wchp/wchc” commands
by default.
- ZooKeeper 3.4.x users should upgrade to 3.4.10 or apply the patch.
- ZooKeeper 3.5.x users should upgrade to 3.5.3 or apply the patch.

References
[1] https://issues.apache.org/jira/browse/ZOOKEEPER-2693


