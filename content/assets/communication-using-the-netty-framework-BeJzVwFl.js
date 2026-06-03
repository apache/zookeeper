import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let c=`[Netty](http://netty.io)
is an NIO based client/server communication framework, it
simplifies (over NIO being used directly) many of the
complexities of network level communication for java
applications. Additionally the Netty framework has built
in support for encryption (SSL) and authentication
(certificates). These are optional features and can be
turned on or off individually.

In versions 3.5+, a ZooKeeper server can use Netty
instead of NIO (default option) by setting the environment
variable **zookeeper.serverCnxnFactory**
to **org.apache.zookeeper.server.NettyServerCnxnFactory**;
for the client, set **zookeeper.clientCnxnSocket**
to **org.apache.zookeeper.ClientCnxnSocketNetty**.

## Quorum TLS

*New in 3.5.5*

Based on the Netty Framework ZooKeeper ensembles can be set up
to use TLS encryption in their communication channels. This section
describes how to set up encryption on the quorum communication.

Please note that Quorum TLS encapsulates securing both leader election
and quorum communication protocols.

<Steps>
  <Step>
    Create SSL keystore JKS to store local credentials. One keystore should be created for each ZK
    instance. In this example we generate a self-signed certificate and store it together with the
    private key in \`keystore.jks\`. This is suitable for testing purposes, but you probably need an
    official certificate to sign your keys in a production environment. Please note that the alias
    (\`-alias\`) and the distinguished name (\`-dname\`) must match the hostname of the machine that is
    associated with, otherwise hostname verification won't work.

    \`\`\`
    keytool -genkeypair -alias $(hostname -f) -keyalg RSA -keysize 2048 -dname "cn=$(hostname -f)" -keypass password -keystore keystore.jks -storepass password
    \`\`\`
  </Step>

  <Step>
    Extract the signed public key (certificate) from keystore. *This step might only be necessary
    for self-signed certificates.*

    \`\`\`
    keytool -exportcert -alias $(hostname -f) -keystore keystore.jks -file $(hostname -f).cer -rfc
    \`\`\`
  </Step>

  <Step>
    Create SSL truststore JKS containing certificates of all ZooKeeper instances. The same
    truststore (storing all accepted certs) should be shared on participants of the ensemble. You
    need to use different aliases to store multiple certificates in the same truststore. Name of
    the aliases doesn't matter.

    \`\`\`
    keytool -importcert -alias [host1..3] -file [host1..3].cer -keystore truststore.jks -storepass password
    \`\`\`
  </Step>

  <Step>
    Use \`NettyServerCnxnFactory\` as serverCnxnFactory, because SSL is not supported by NIO. Add
    the following configuration settings to your \`zoo.cfg\` config file:

    \`\`\`
    sslQuorum=true
    serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory
    ssl.quorum.keyStore.location=/path/to/keystore.jks
    ssl.quorum.keyStore.password=password
    ssl.quorum.trustStore.location=/path/to/truststore.jks
    ssl.quorum.trustStore.password=password
    \`\`\`
  </Step>

  <Step>
    Verify in the logs that your ensemble is running on TLS:

    \`\`\`
    INFO  [main:QuorumPeer@1789] - Using TLS encrypted quorum communication
    INFO  [main:QuorumPeer@1797] - Port unification disabled
    ...
    INFO  [QuorumPeerListener:QuorumCnxManager$Listener@877] - Creating TLS-only quorum server socket
    \`\`\`
  </Step>
</Steps>

## Upgrading existing non-TLS cluster with no downtime

*New in 3.5.5*

Here are the steps needed to upgrade an already running ZooKeeper ensemble
to TLS without downtime by taking advantage of port unification functionality.

<Steps>
  <Step>
    Create the necessary keystores and truststores for all ZK participants as described in the
    previous section.
  </Step>

  <Step>
    Add the following config settings and restart the first node. Note that TLS is not yet
    enabled, but we turn on port unification.

    \`\`\`
    sslQuorum=false
    portUnification=true
    serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory
    ssl.quorum.keyStore.location=/path/to/keystore.jks
    ssl.quorum.keyStore.password=password
    ssl.quorum.trustStore.location=/path/to/truststore.jks
    ssl.quorum.trustStore.password=password
    \`\`\`
  </Step>

  <Step>
    Repeat step 2 on the remaining nodes. Verify that you see the following entries in the logs,
    and double-check after each node restart that the quorum becomes healthy again.

    \`\`\`
    INFO  [main:QuorumPeer@1791] - Using insecure (non-TLS) quorum communication
    INFO  [main:QuorumPeer@1797] - Port unification enabled
    ...
    INFO  [QuorumPeerListener:QuorumCnxManager$Listener@874] - Creating TLS-enabled quorum server socket
    \`\`\`
  </Step>

  <Step>
    Enable Quorum TLS on each node and do a rolling restart:

    \`\`\`
    sslQuorum=true
    portUnification=true
    \`\`\`
  </Step>

  <Step>
    Once you verify that your entire ensemble is running on TLS, disable port unification and do
    another rolling restart:

    \`\`\`
    sslQuorum=true
    portUnification=false
    \`\`\`
  </Step>
</Steps>
`,l={title:"Communication using the Netty framework",description:"Explains how to configure ZooKeeper to use Netty instead of NIO for client/server communication, including Quorum TLS setup and zero-downtime migration from non-TLS clusters."},h=[{href:"http://netty.io"}],d={contents:[{heading:void 0,content:`Netty
is an NIO based client/server communication framework, it
simplifies (over NIO being used directly) many of the
complexities of network level communication for java
applications. Additionally the Netty framework has built
in support for encryption (SSL) and authentication
(certificates). These are optional features and can be
turned on or off individually.`},{heading:void 0,content:`In versions 3.5+, a ZooKeeper server can use Netty
instead of NIO (default option) by setting the environment
variable zookeeper.serverCnxnFactory
to org.apache.zookeeper.server.NettyServerCnxnFactory;
for the client, set zookeeper.clientCnxnSocket
to org.apache.zookeeper.ClientCnxnSocketNetty.`},{heading:"quorum-tls",content:"New in 3.5.5"},{heading:"quorum-tls",content:`Based on the Netty Framework ZooKeeper ensembles can be set up
to use TLS encryption in their communication channels. This section
describes how to set up encryption on the quorum communication.`},{heading:"quorum-tls",content:`Please note that Quorum TLS encapsulates securing both leader election
and quorum communication protocols.`},{heading:"quorum-tls",content:`Create SSL keystore JKS to store local credentials. One keystore should be created for each ZK
instance. In this example we generate a self-signed certificate and store it together with the
private key in keystore.jks. This is suitable for testing purposes, but you probably need an
official certificate to sign your keys in a production environment. Please note that the alias
(-alias) and the distinguished name (-dname) must match the hostname of the machine that is
associated with, otherwise hostname verification won't work.`},{heading:"quorum-tls",content:`Extract the signed public key (certificate) from keystore. This step might only be necessary
for self-signed certificates.`},{heading:"quorum-tls",content:`Create SSL truststore JKS containing certificates of all ZooKeeper instances. The same
truststore (storing all accepted certs) should be shared on participants of the ensemble. You
need to use different aliases to store multiple certificates in the same truststore. Name of
the aliases doesn't matter.`},{heading:"quorum-tls",content:`Use NettyServerCnxnFactory as serverCnxnFactory, because SSL is not supported by NIO. Add
the following configuration settings to your zoo.cfg config file:`},{heading:"quorum-tls",content:"Verify in the logs that your ensemble is running on TLS:"},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:"New in 3.5.5"},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:`Here are the steps needed to upgrade an already running ZooKeeper ensemble
to TLS without downtime by taking advantage of port unification functionality.`},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:`Create the necessary keystores and truststores for all ZK participants as described in the
previous section.`},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:`Add the following config settings and restart the first node. Note that TLS is not yet
enabled, but we turn on port unification.`},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:`Repeat step 2 on the remaining nodes. Verify that you see the following entries in the logs,
and double-check after each node restart that the quorum becomes healthy again.`},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:"Enable Quorum TLS on each node and do a rolling restart:"},{heading:"upgrading-existing-non-tls-cluster-with-no-downtime",content:`Once you verify that your entire ensemble is running on TLS, disable port unification and do
another rolling restart:`}],headings:[{id:"quorum-tls",content:"Quorum TLS"},{id:"upgrading-existing-non-tls-cluster-with-no-downtime",content:"Upgrading existing non-TLS cluster with no downtime"}]};const u=[{depth:2,url:"#quorum-tls",title:e.jsx(e.Fragment,{children:"Quorum TLS"})},{depth:2,url:"#upgrading-existing-non-tls-cluster-with-no-downtime",title:e.jsx(e.Fragment,{children:"Upgrading existing non-TLS cluster with no downtime"})}];function r(s){const n={a:"a",code:"code",em:"em",h2:"h2",p:"p",pre:"pre",span:"span",strong:"strong",...s.components},{Step:t,Steps:i}=n;return t||o("Step"),i||o("Steps"),e.jsxs(e.Fragment,{children:[e.jsxs(n.p,{children:[e.jsx(n.a,{href:"http://netty.io",children:"Netty"}),`
is an NIO based client/server communication framework, it
simplifies (over NIO being used directly) many of the
complexities of network level communication for java
applications. Additionally the Netty framework has built
in support for encryption (SSL) and authentication
(certificates). These are optional features and can be
turned on or off individually.`]}),`
`,e.jsxs(n.p,{children:[`In versions 3.5+, a ZooKeeper server can use Netty
instead of NIO (default option) by setting the environment
variable `,e.jsx(n.strong,{children:"zookeeper.serverCnxnFactory"}),`
to `,e.jsx(n.strong,{children:"org.apache.zookeeper.server.NettyServerCnxnFactory"}),`;
for the client, set `,e.jsx(n.strong,{children:"zookeeper.clientCnxnSocket"}),`
to `,e.jsx(n.strong,{children:"org.apache.zookeeper.ClientCnxnSocketNetty"}),"."]}),`
`,e.jsx(n.h2,{id:"quorum-tls",children:"Quorum TLS"}),`
`,e.jsx(n.p,{children:e.jsx(n.em,{children:"New in 3.5.5"})}),`
`,e.jsx(n.p,{children:`Based on the Netty Framework ZooKeeper ensembles can be set up
to use TLS encryption in their communication channels. This section
describes how to set up encryption on the quorum communication.`}),`
`,e.jsx(n.p,{children:`Please note that Quorum TLS encapsulates securing both leader election
and quorum communication protocols.`}),`
`,e.jsxs(i,{children:[e.jsxs(t,{children:[e.jsxs(n.p,{children:[`Create SSL keystore JKS to store local credentials. One keystore should be created for each ZK
instance. In this example we generate a self-signed certificate and store it together with the
private key in `,e.jsx(n.code,{children:"keystore.jks"}),`. This is suitable for testing purposes, but you probably need an
official certificate to sign your keys in a production environment. Please note that the alias
(`,e.jsx(n.code,{children:"-alias"}),") and the distinguished name (",e.jsx(n.code,{children:"-dname"}),`) must match the hostname of the machine that is
associated with, otherwise hostname verification won't work.`]}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:'keytool -genkeypair -alias $(hostname -f) -keyalg RSA -keysize 2048 -dname "cn=$(hostname -f)" -keypass password -keystore keystore.jks -storepass password'})})})})})]}),e.jsxs(t,{children:[e.jsxs(n.p,{children:["Extract the signed public key (certificate) from keystore. ",e.jsx(n.em,{children:`This step might only be necessary
for self-signed certificates.`})]}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"keytool -exportcert -alias $(hostname -f) -keystore keystore.jks -file $(hostname -f).cer -rfc"})})})})})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Create SSL truststore JKS containing certificates of all ZooKeeper instances. The same
truststore (storing all accepted certs) should be shared on participants of the ensemble. You
need to use different aliases to store multiple certificates in the same truststore. Name of
the aliases doesn't matter.`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(n.code,{children:e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"keytool -importcert -alias [host1..3] -file [host1..3].cer -keystore truststore.jks -storepass password"})})})})})]}),e.jsxs(t,{children:[e.jsxs(n.p,{children:["Use ",e.jsx(n.code,{children:"NettyServerCnxnFactory"}),` as serverCnxnFactory, because SSL is not supported by NIO. Add
the following configuration settings to your `,e.jsx(n.code,{children:"zoo.cfg"})," config file:"]}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sslQuorum=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.location=/path/to/keystore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.password=password"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.location=/path/to/truststore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.password=password"})})]})})})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:"Verify in the logs that your ensemble is running on TLS:"}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [main:QuorumPeer@1789] - Using TLS encrypted quorum communication"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [main:QuorumPeer@1797] - Port unification disabled"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"..."})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [QuorumPeerListener:QuorumCnxManager$Listener@877] - Creating TLS-only quorum server socket"})})]})})})]})]}),`
`,e.jsx(n.h2,{id:"upgrading-existing-non-tls-cluster-with-no-downtime",children:"Upgrading existing non-TLS cluster with no downtime"}),`
`,e.jsx(n.p,{children:e.jsx(n.em,{children:"New in 3.5.5"})}),`
`,e.jsx(n.p,{children:`Here are the steps needed to upgrade an already running ZooKeeper ensemble
to TLS without downtime by taking advantage of port unification functionality.`}),`
`,e.jsxs(i,{children:[e.jsx(t,{children:e.jsx(n.p,{children:`Create the necessary keystores and truststores for all ZK participants as described in the
previous section.`})}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Add the following config settings and restart the first node. Note that TLS is not yet
enabled, but we turn on port unification.`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sslQuorum=false"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"portUnification=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.location=/path/to/keystore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.keyStore.password=password"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.location=/path/to/truststore.jks"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"ssl.quorum.trustStore.password=password"})})]})})})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Repeat step 2 on the remaining nodes. Verify that you see the following entries in the logs,
and double-check after each node restart that the quorum becomes healthy again.`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [main:QuorumPeer@1791] - Using insecure (non-TLS) quorum communication"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [main:QuorumPeer@1797] - Port unification enabled"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"..."})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"INFO  [QuorumPeerListener:QuorumCnxManager$Listener@874] - Creating TLS-enabled quorum server socket"})})]})})})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:"Enable Quorum TLS on each node and do a rolling restart:"}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sslQuorum=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"portUnification=true"})})]})})})]}),e.jsxs(t,{children:[e.jsx(n.p,{children:`Once you verify that your entire ensemble is running on TLS, disable port unification and do
another rolling restart:`}),e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"sslQuorum=true"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{children:"portUnification=false"})})]})})})]})]})]})}function p(s={}){const{wrapper:n}=s.components||{};return n?e.jsx(n,{...s,children:e.jsx(r,{...s})}):r(s)}function o(s,n){throw new Error("Expected component `"+s+"` to be defined: you likely forgot to import, pass, or provide it.")}export{c as _markdown,p as default,h as extractedReferences,l as frontmatter,d as structuredData,u as toc};
