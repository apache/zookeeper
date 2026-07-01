import{w as s}from"./chunk-6CSD65Y2-DdXLjHPL.js";import{j as e}from"./jsx-runtime-u17CrQMm.js";import{M as t}from"./mdx-components-DzdEM0Pp.js";import"./index-BES0CLPa.js";import"./index-ByRO6HJY.js";import"./index-CUfaML0k.js";e.jsx(e.Fragment,{children:"ZooKeeper Security"}),e.jsx(e.Fragment,{children:"Security model"}),e.jsx(e.Fragment,{children:"Security is opt-in"}),e.jsx(e.Fragment,{children:"In scope for security reports"}),e.jsx(e.Fragment,{children:"Out of scope for security reports"}),e.jsx(e.Fragment,{children:"Vulnerability reports"}),e.jsx(e.Fragment,{children:"CVE-2026-24308"}),e.jsx(e.Fragment,{children:"CVE-2026-24281"}),e.jsx(e.Fragment,{children:"CVE-2025-58457"}),e.jsx(e.Fragment,{children:"CVE-2024-51504"}),e.jsx(e.Fragment,{children:"CVE-2024-23944"}),e.jsx(e.Fragment,{children:"CVE-2023-44981"}),e.jsx(e.Fragment,{children:"CVE-2019-0201"}),e.jsx(e.Fragment,{children:"CVE-2018-8012"}),e.jsx(e.Fragment,{children:"CVE-2017-5637"}),e.jsx(e.Fragment,{children:"CVE-2016-5017"});function i(n){const r={a:"a",code:"code",h1:"h1",h2:"h2",h3:"h3",hr:"hr",li:"li",p:"p",strong:"strong",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsx(r.h1,{id:"zookeeper-security",children:"ZooKeeper Security"}),`
`,e.jsxs(r.p,{children:["The Apache Software Foundation takes security issues very seriously. Due to the infrastructure nature of the Apache ZooKeeper project specifically, we haven't had many reports over time, but it doesn't mean that we haven't had concerns over some bugs and vulnerabilities. If you have any concern or believe you have uncovered a vulnerability, we suggest that you get in touch via the e-mail address ",e.jsx(r.a,{href:"mailto:security@zookeeper.apache.org?Subject=%5BSECURITY%5D%20My%20security%20issue",children:"security@zookeeper.apache.org"}),". In the message, try to provide a description of the issue and ideally a way of reproducing it. Note that this security address should be used only for undisclosed vulnerabilities. Dealing with known issues should be handled regularly via jira and the mailing lists. ",e.jsx(r.strong,{children:"Please report any security problems to the project security address before disclosing it publicly."})]}),`
`,e.jsxs(r.p,{children:["The ASF Security team maintains a page with a description of how vulnerabilities are handled, check their ",e.jsx(r.a,{href:"https://security.apache.org/report/",children:"Web page"})," for more information."]}),`
`,e.jsx(r.h2,{id:"security-model",children:"Security model"}),`
`,e.jsxs(r.p,{children:["ZooKeeper is a coordination service intended for use inside a trusted network, not exposed directly to the Internet. The ",e.jsx(r.a,{href:"https://zookeeper.apache.org/doc/current/admin-ops/administrators-guide/",children:"Admin Guide"}),' states it plainly: "A ZooKeeper ensemble is expected to operate in a trusted computing environment. It is thus recommended deploying ZooKeeper behind a firewall." A fresh ensemble ships with no transport encryption, no peer authentication, and world-readable/writable znodes. Hardening is a shared responsibility between the ZooKeeper project and the operator.']}),`
`,e.jsx(r.h3,{id:"security-is-opt-in",children:"Security is opt-in"}),`
`,e.jsx(r.p,{children:"The following controls exist but must be explicitly enabled:"}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"Transport encryption"})," — TLS for client-server and quorum traffic, with optional hostname verification. Disabled by default."]}),`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"Authentication"})," — digest (username/password), SASL/Kerberos, or x509/mTLS for clients; SASL or TLS for quorum peers. None are enabled by default."]}),`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"Access control"})," — per-znode ACLs using the ",e.jsx(r.code,{children:"world"}),", ",e.jsx(r.code,{children:"auth"}),", ",e.jsx(r.code,{children:"digest"}),", ",e.jsx(r.code,{children:"sasl"}),", ",e.jsx(r.code,{children:"ip"}),", or ",e.jsx(r.code,{children:"x509"})," schemes. ACLs are not applied automatically; unless the application sets a restrictive ACL, nodes are commonly created with the wide-open ",e.jsx(r.code,{children:"OPEN_ACL_UNSAFE"})," (",e.jsx(r.code,{children:"world:anyone"})," with all permissions)."]}),`
`]}),`
`,e.jsx(r.p,{children:"Known limitations of these controls, by design:"}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsxs(r.li,{children:["ACLs are ",e.jsx(r.strong,{children:"not recursive"}),". An ACL on a parent znode does not protect its children; each znode's ACL must be set independently."]}),`
`,e.jsxs(r.li,{children:["The ",e.jsx(r.code,{children:"ip"})," ACL scheme matches source IP address and is ",e.jsx(r.strong,{children:"spoofable"}),". Operators must ensure network-level controls prevent IP address forgery on any path that relies on this scheme."]}),`
`,e.jsxs(r.li,{children:["The AdminServer's default client IP detection honors the ",e.jsx(r.code,{children:"X-Forwarded-For"})," header, which can be forged by an attacker to bypass IP-based access controls (see CVE-2024-51504). Operators exposing the AdminServer should configure a trusted proxy or restrict access at the network layer."]}),`
`,e.jsxs(r.li,{children:["The ",e.jsx(r.code,{children:"digest"})," scheme transmits the password during authentication and stores an unsalted SHA-1 hash. Use SASL/Kerberos or mTLS where credential confidentiality is required."]}),`
`]}),`
`,e.jsx(r.h3,{id:"in-scope-for-security-reports",children:"In scope for security reports"}),`
`,e.jsxs(r.p,{children:["Reports sent to ",e.jsx(r.a,{href:"mailto:security@zookeeper.apache.org?Subject=%5BSECURITY%5D%20My%20security%20issue",children:"security@zookeeper.apache.org"})," are appropriate for:"]}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsxs(r.li,{children:["Vulnerabilities in the ",e.jsx(r.strong,{children:"core ZooKeeper server, Java client library, and AdminServer"})," published to ",e.jsx(r.a,{href:"https://central.sonatype.com/search?q=g:org.apache.zookeeper",children:"Maven Central"})," under ",e.jsx(r.code,{children:"org.apache.zookeeper"}),"."]}),`
`,e.jsxs(r.li,{children:["Bypass of an ",e.jsx(r.strong,{children:"enabled and correctly configured"})," security control (e.g., authentication bypass when SASL is on, ACL enforcement bugs)."]}),`
`,e.jsxs(r.li,{children:["Behaviors that contradict documented guarantees in the current ",e.jsx(r.a,{href:"https://zookeeper.apache.org/doc/current/",children:"ZooKeeper documentation"}),"."]}),`
`]}),`
`,e.jsx(r.h3,{id:"out-of-scope-for-security-reports",children:"Out of scope for security reports"}),`
`,e.jsxs(r.p,{children:["The following are ",e.jsx(r.strong,{children:"not"})," in scope for private disclosure:"]}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsxs(r.li,{children:[e.jsxs(r.strong,{children:[e.jsx(r.code,{children:"zookeeper-contrib"})," and ",e.jsx(r.code,{children:"zookeeper-recipes"})]})," — these are community contributions not published to Maven Central and carry no security support commitment."]}),`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"The C CLI example shells"})," (",e.jsx(r.code,{children:"cli_st"}),", ",e.jsx(r.code,{children:"cli_mt"}),") — these are sample code illustrating C client usage, not production tools (see CVE-2016-5017)."]}),`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"Missing hardening on a default installation"})," — the default configuration is intentionally minimal. Absence of encryption or authentication on a fresh ensemble is expected behavior, not a vulnerability."]}),`
`,e.jsxs(r.li,{children:[e.jsx(r.strong,{children:"Third-party dependency CVEs"})," — if a dependency bundled in a ZooKeeper distribution carries a known CVE but poses no direct exploit path through ZooKeeper, this is not a private vulnerability. Please open a public ",e.jsx(r.a,{href:"https://issues.apache.org/jira/projects/ZOOKEEPER",children:"JIRA"})," ticket or post to the ",e.jsx(r.a,{href:"https://zookeeper.apache.org/mailinglists.html",children:"dev mailing list"})," instead. See the ",e.jsx(r.a,{href:"https://security.apache.org/report/",children:"ASF reporting guide"})," for guidance on choosing the right channel."]}),`
`]}),`
`,e.jsx(r.h2,{id:"vulnerability-reports",children:"Vulnerability reports"}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2026-24308",children:"CVE-2026-24308"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2026-24281",children:"CVE-2026-24281"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2025-58457",children:"CVE-2025-58457"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2024-51504",children:"CVE-2024-51504"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2024-23944",children:"CVE-2024-23944"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2023-44981",children:"CVE-2023-44981"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2019-0201",children:"CVE-2019-0201"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2018-8012",children:"CVE-2018-8012"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2017-5637",children:"CVE-2017-5637"})}),`
`,e.jsx(r.li,{children:e.jsx(r.a,{href:"#cve-2016-5017",children:"CVE-2016-5017"})}),`
`]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2026-24308",children:"CVE-2026-24308"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Sensitive information disclosure in client configuration handling"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," important"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper (org.apache.zookeeper:zookeeper) 3.9.0 through 3.9.4"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper (org.apache.zookeeper:zookeeper) 3.8.0 through 3.8.5"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsx(r.p,{children:"Improper handling of configuration values in ZKConfig in Apache ZooKeeper 3.8.5 and 3.9.4 on all platforms allows an attacker to expose sensitive information stored in client configuration in the client's logfile. Configuration values are exposed at INFO level logging rendering potential production systems affected by the issue. Users are recommended to upgrade to version 3.8.6 or 3.9.5 which fixes this issue."}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," Youlong Chen (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2026-24308",children:"https://www.cve.org/CVERecord?id=CVE-2026-24308"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2026-24281",children:"CVE-2026-24281"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Reverse-DNS fallback enables hostname verification bypass in ZooKeeper ZKTrustManager"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," important"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper (org.apache.zookeeper:zookeeper) 3.9.0 through 3.9.4"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper (org.apache.zookeeper:zookeeper) 3.8.0 through 3.8.5"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsx(r.p,{children:"Hostname verification in Apache ZooKeeper ZKTrustManager falls back to reverse DNS (PTR) when IP SAN validation fails, allowing attackers who control or spoof PTR records to impersonate ZooKeeper servers or clients with a valid certificate for the PTR name. It's important to note that attacker must present a certificate which is trusted by ZKTrustManager which makes the attack vector harder to exploit. Users are recommended to upgrade to version 3.8.6 or 3.9.5, which fixes this issue by introducing a new configuration option to disable reverse DNS lookup in client and quorum protocols."}),`
`,e.jsxs(r.p,{children:["This issue is being tracked as ",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4986",children:"ZOOKEEPER-4986"}),"."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," Nikita Markevich (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2026-24281",children:"https://www.cve.org/CVERecord?id=CVE-2026-24281"})," · ",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-4986",children:"ZOOKEEPER-4986"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2025-58457",children:"CVE-2025-58457"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Insufficient Permission Check in AdminServer Snapshot/Restore Commands"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," moderate"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper (org.apache.zookeeper:zookeeper) 3.9.0 before 3.9.4"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsxs(r.p,{children:["Improper permission check in ZooKeeper AdminServer lets authorized clients to run ",e.jsx(r.code,{children:"snapshot"})," and ",e.jsx(r.code,{children:"restore"})," command with insufficient permissions."]}),`
`,e.jsx(r.p,{children:"This issue affects Apache ZooKeeper: from 3.9.0 before 3.9.4. Users are recommended to upgrade to version 3.9.4, which fixes the issue."}),`
`,e.jsxs(r.p,{children:["The issue can be mitigated by disabling both commands (via ",e.jsx(r.code,{children:"admin.snapshot.enabled"})," and ",e.jsx(r.code,{children:"admin.restore.enabled"}),"), disabling the whole AdminServer interface (via ",e.jsx(r.code,{children:"admin.enableServer"}),"), or ensuring that the root ACL does not provide open permissions. (Note that ZooKeeper ACLs are not recursive, so this does not impact operations on child nodes besides notifications from recursive watches.)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," Damien Diederen (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2025-58457",children:"https://www.cve.org/CVERecord?id=CVE-2025-58457"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2024-51504",children:"CVE-2024-51504"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Authentication bypass with IP-based authentication in Admin Server"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," important"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.9.0 before 3.9.3"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsxs(r.p,{children:["When using IPAuthenticationProvider in ZooKeeper Admin Server there is a possibility of Authentication Bypass by Spoofing — this only impacts IP based authentication implemented in ZooKeeper Admin Server. Default configuration of client's IP address detection in IPAuthenticationProvider, which uses HTTP request headers, is weak and allows an attacker to bypass authentication via spoofing client's IP address in request headers. Default configuration honors ",e.jsx(r.code,{children:"X-Forwarded-For"})," HTTP header to read client's IP address. ",e.jsx(r.code,{children:"X-Forwarded-For"})," request header is mainly used by proxy servers to identify the client and can be easily spoofed by an attacker pretending that the request comes from a different IP address. Admin Server commands, such as snapshot and restore arbitrarily can be executed on successful exploitation which could potentially lead to information leakage or service availability issues. Users are recommended to upgrade to version 3.9.3, which fixes this issue."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," 4ra1n (reporter), Y4tacker (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2024-51504",children:"https://www.cve.org/CVERecord?id=CVE-2024-51504"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2024-23944",children:"CVE-2024-23944"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Information disclosure in persistent watcher handling"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," critical"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.9.0 through 3.9.1"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.8.0 through 3.8.3"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.6.0 through 3.7.2"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsxs(r.p,{children:["Information disclosure in persistent watchers handling in Apache ZooKeeper due to missing ACL check. It allows an attacker to monitor child znodes by attaching a persistent watcher (",e.jsx(r.code,{children:"addWatch"})," command) to a parent which the attacker has already access to. ZooKeeper server doesn't do ACL check when the persistent watcher is triggered and as a consequence, the full path of znodes that a watch event gets triggered upon is exposed to the owner of the watcher. It's important to note that only the path is exposed by this vulnerability, not the data of znode, but since znode path can contain sensitive information like user name or login ID, this issue is potentially critical."]}),`
`,e.jsx(r.p,{children:"Users are recommended to upgrade to version 3.9.2 or 3.8.4 which fixes the issue."}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," 周吉安(寒泉) (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2024-23944",children:"https://www.cve.org/CVERecord?id=CVE-2024-23944"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2023-44981",children:"CVE-2023-44981"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Authorization bypass in SASL Quorum Peer Authentication"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," critical"]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Affected versions:"})}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.9.0"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.8.0 through 3.8.2"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper 3.7.0 through 3.7.1"}),`
`,e.jsx(r.li,{children:"Apache ZooKeeper before 3.7.0"}),`
`]}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Description:"})}),`
`,e.jsxs(r.p,{children:["Authorization Bypass Through User-Controlled Key vulnerability in Apache ZooKeeper. If SASL Quorum Peer authentication is enabled in ZooKeeper (",e.jsx(r.code,{children:"quorum.auth.enableSasl=true"}),"), the authorization is done by verifying that the instance part in SASL authentication ID is listed in ",e.jsx(r.code,{children:"zoo.cfg"})," server list. The instance part in SASL auth ID is optional and if it's missing, like ",e.jsx(r.code,{children:"eve@EXAMPLE.COM"}),", the authorization check will be skipped. As a result an arbitrary endpoint could join the cluster and begin propagating counterfeit changes to the leader, essentially giving it complete read-write access to the data tree. Quorum Peer authentication is not enabled by default."]}),`
`,e.jsx(r.p,{children:"Users are recommended to upgrade to version 3.9.1, 3.8.3, or 3.7.2, which fixes the issue."}),`
`,e.jsx(r.p,{children:"Alternately ensure the ensemble election/quorum communication is protected by a firewall as this will mitigate the issue."}),`
`,e.jsx(r.p,{children:"See the documentation for more details on correct cluster administration."}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," Damien Diederen (reporter)"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://www.cve.org/CVERecord?id=CVE-2023-44981",children:"https://www.cve.org/CVERecord?id=CVE-2023-44981"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2019-0201",children:"CVE-2019-0201"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Information disclosure vulnerability in Apache ZooKeeper"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," Critical"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Vendor:"})," The Apache Software Foundation"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Versions Affected:"})," ZooKeeper prior to 3.4.14; ZooKeeper 3.5.0-alpha through 3.5.4-beta. The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Description:"})," ZooKeeper's ",e.jsx(r.code,{children:"getACL()"})," command doesn't check any permission when retrieves the ACLs of the requested node and returns all information contained in the ACL Id field as plaintext string. DigestAuthenticationProvider overloads the Id field with the hash value that is used for user authentication. As a consequence, if Digest Authentication is in use, the unsalted hash value will be disclosed by ",e.jsx(r.code,{children:"getACL()"})," request for unauthenticated or unprivileged users."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Mitigation:"})," Use an authentication method other than Digest (e.g. Kerberos) or upgrade to 3.4.14 or later (3.5.5 or later if on the 3.5 branch)."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," This issue was identified by Harrison Neal, PatchAdvisor, Inc."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1392",children:"https://issues.apache.org/jira/browse/ZOOKEEPER-1392"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2018-8012",children:"CVE-2018-8012"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Apache ZooKeeper Quorum Peer mutual authentication"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," Critical"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Vendor:"})," The Apache Software Foundation"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Versions Affected:"})," ZooKeeper prior to 3.4.10; ZooKeeper 3.5.0-alpha through 3.5.3-beta. The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Description:"})," No authentication/authorization is enforced when a server attempts to join a quorum. As a result an arbitrary end point could join the cluster and begin propagating counterfeit changes to the leader."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Mitigation:"})," Upgrade to 3.4.10 or later (3.5.4-beta or later if on the 3.5 branch) and enable Quorum Peer mutual authentication."]}),`
`,e.jsx(r.p,{children:"Alternately ensure the ensemble election/quorum communication is protected by a firewall as this will mitigate the issue."}),`
`,e.jsx(r.p,{children:"See the documentation for more details on correct cluster administration."}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," This issue was identified by Földi Tamás and Eugene Koontz."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-1045",children:"ZOOKEEPER-1045"})," · ",e.jsx(r.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/Server-Server+mutual+authentication",children:"Server-Server mutual authentication"})," · ",e.jsx(r.a,{href:"https://zookeeper.apache.org/doc/current/admin-ops/administrators-guide/",children:"ZooKeeper Admin Guide"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2017-5637",children:"CVE-2017-5637"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"DOS attack on wchp/wchc four letter words (4lw)"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," moderate"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Vendor:"})," The Apache Software Foundation"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Versions Affected:"})," ZooKeeper 3.4.0 to 3.4.9; ZooKeeper 3.5.0 to 3.5.2. The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected. Note: The 3.5 branch was still beta at this time."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Description:"}),' Two four letter word commands "wchp/wchc" are CPU intensive and could cause spike of CPU utilization on ZooKeeper server if abused, which leads to the server unable to serve legitimate client requests. There is no known compromise which takes advantage of this vulnerability.']}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Mitigation:"})," This affects ZooKeeper ensembles whose client port is publicly accessible, so it is recommended to protect ZooKeeper ensemble with firewall. Documentation has also been updated to clarify on this point. In addition, a patch (",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2693",children:"ZOOKEEPER-2693"}),') is provided to disable "wchp/wchc" commands by default.']}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsx(r.li,{children:"ZooKeeper 3.4.x users should upgrade to 3.4.10 or apply the patch."}),`
`,e.jsx(r.li,{children:"ZooKeeper 3.5.x users should upgrade to 3.5.3 or apply the patch."}),`
`]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"References:"})," ",e.jsx(r.a,{href:"https://issues.apache.org/jira/browse/ZOOKEEPER-2693",children:"ZOOKEEPER-2693"})]}),`
`,e.jsx(r.hr,{}),`
`,e.jsx(r.h3,{id:"cve-2016-5017",children:"CVE-2016-5017"}),`
`,e.jsx(r.p,{children:e.jsx(r.strong,{children:"Buffer overflow vulnerability in ZooKeeper C cli shell"})}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Severity:"})," moderate"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Vendor:"})," The Apache Software Foundation"]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Versions Affected:"})," ZooKeeper 3.4.0 to 3.4.8; ZooKeeper 3.5.0 to 3.5.2. The unsupported ZooKeeper 1.x through 3.3.x versions may be also affected. Note: The 3.5 branch was still alpha at this time."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Description:"}),' The ZooKeeper C client shells "cli_st" and "cli_mt" have a buffer overflow vulnerability associated with parsing of the input command when using the ',e.jsx(r.code,{children:"cmd:<cmd>"})," batch mode syntax. If the command string exceeds 1024 characters a buffer overflow will occur. There is no known compromise which takes advantage of this vulnerability, and if security is enabled the attacker would be limited by client level security constraints. The C cli shell is intended as a sample/example of how to use the C client interface, not as a production tool — the documentation has also been clarified on this point."]}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Mitigation:"})," It is important to use the fully featured/supported Java cli shell rather than the C cli shell independent of version."]}),`
`,e.jsxs(r.ul,{children:[`
`,e.jsxs(r.li,{children:["ZooKeeper 3.4.x users should upgrade to 3.4.9 or apply this ",e.jsx(r.a,{href:"https://gitbox.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=27ecf981a15554dc8e64a28630af7a5c9e2bdf4f",children:"patch"}),"."]}),`
`,e.jsxs(r.li,{children:["ZooKeeper 3.5.x users should upgrade to 3.5.3 when released or apply this ",e.jsx(r.a,{href:"https://gitbox.apache.org/repos/asf?p=zookeeper.git;a=commitdiff;h=f09154d6648eeb4ec5e1ac8a2bacbd2f8c87c14a",children:"patch"}),"."]}),`
`]}),`
`,e.jsx(r.p,{children:"The patch solves the problem reported here, but it does not make the client ready for production use. The community has no plan to make this client production ready at this time, and strongly recommends that users move to the Java cli and use the C cli for illustration purposes only."}),`
`,e.jsxs(r.p,{children:[e.jsx(r.strong,{children:"Credit:"})," This issue was discovered by Lyon Yang (@l0Op3r)."]})]})}function o(n={}){const{wrapper:r}=n.components||{};return r?e.jsx(r,{...n,children:e.jsx(i,{...n})}):i(n)}function c(){return e.jsx(t,{Content:o,className:"mt-12"})}function x({}){return[{title:"Security - Apache ZooKeeper"},{name:"description",content:"How to report security vulnerabilities and review known CVEs for Apache ZooKeeper."}]}const j=s(function(){return e.jsx(c,{})});export{j as default,x as meta};
