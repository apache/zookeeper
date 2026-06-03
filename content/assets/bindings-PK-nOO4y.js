import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let a=`The ZooKeeper client libraries come in two languages: Java and C.
The following sections describe these.

## Java Binding

There are two packages that make up the ZooKeeper Java binding:
**org.apache.zookeeper** and **org.apache.zookeeper.data**. The rest of the
packages that make up ZooKeeper are used internally or are part of the
server implementation. The **org.apache.zookeeper.data** package is made up of
generated classes that are used simply as containers.

The main class used by a ZooKeeper Java client is the **ZooKeeper** class. Its two constructors differ only
by an optional session id and password. ZooKeeper supports session
recovery across instances of a process. A Java program may save its
session id and password to stable storage, restart, and recover the
session that was used by the earlier instance of the program.

When a ZooKeeper object is created, two threads are created as
well: an IO thread and an event thread. All IO happens on the IO thread
(using Java NIO). All event callbacks happen on the event thread.
Session maintenance such as reconnecting to ZooKeeper servers and
maintaining heartbeat is done on the IO thread. Responses for
synchronous methods are also processed in the IO thread. All responses
to asynchronous methods and watch events are processed on the event
thread. There are a few things to notice that result from this
design:

* All completions for asynchronous calls and watcher callbacks
  will be made in order, one at a time. The caller can do any
  processing they wish, but no other callbacks will be processed
  during that time.
* Callbacks do not block the processing of the IO thread or the
  processing of the synchronous calls.
* Synchronous calls may not return in the correct order. For
  example, assume a client does the following processing: issues an
  asynchronous read of node **/a** with
  *watch* set to true, and then in the completion
  callback of the read it does a synchronous read of **/a**. (Maybe not good practice, but not illegal
  either, and it makes for a simple example.)
  Note that if there is a change to **/a** between the asynchronous read and the
  synchronous read, the client library will receive the watch event
  saying **/a** changed before the
  response for the synchronous read, but because of the completion
  callback blocking the event queue, the synchronous read will
  return with the new value of **/a**
  before the watch event is processed.

Finally, the rules associated with shutdown are straightforward:
once a ZooKeeper object is closed or receives a fatal event
(SESSION\\_EXPIRED and AUTH\\_FAILED), the ZooKeeper object becomes invalid.
On a close, the two threads shut down and any further access on zookeeper
handle is undefined behavior and should be avoided.

### Client Configuration Parameters

The following list contains configuration properties for the Java client. You can set any
of these properties using Java system properties. For server properties, please check the
[Server configuration section of the Admin Guide](/admin-ops/administrators-guide/configuration-parameters#advanced-configuration).
The ZooKeeper Wiki also has useful pages about
[ZooKeeper SSL support](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide),
and [SASL authentication for ZooKeeper](https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL).

* *zookeeper.sasl.client* :
  Set the value to **false** to disable
  SASL authentication. Default is **true**.

* *zookeeper.sasl.clientconfig* :
  Specifies the context key in the JAAS login file. Default is "Client".

* *zookeeper.server.principal* :
  Specifies the server principal to be used by the client for authentication, while connecting to the zookeeper
  server, when Kerberos authentication is enabled. If this configuration is provided, then
  the ZooKeeper client will NOT USE any of the following parameters to determine the server principal:

  \`\`\`properties
  zookeeper.sasl.client.username, zookeeper.sasl.client.canonicalize.hostname, zookeeper.server.realm
  Note: this config parameter is working only for ZooKeeper 3.5.7+, 3.6.0+
  \`\`\`

* *zookeeper.sasl.client.username* :
  Traditionally, a principal is divided into three parts: the primary, the instance, and the realm.
  The format of a typical Kerberos V5 principal is primary/instance\\@REALM.
  zookeeper.sasl.client.username specifies the primary part of the server principal. Default
  is "zookeeper". Instance part is derived from the server IP. Finally server's principal is
  username/IP\\@realm, where username is the value of zookeeper.sasl.client.username, IP is
  the server IP, and realm is the value of zookeeper.server.realm.

* *zookeeper.sasl.client.canonicalize.hostname* :
  Expecting the zookeeper.server.principal parameter is not provided, the ZooKeeper client will try to
  determine the 'instance' (host) part of the ZooKeeper server principal. First it takes the hostname provided
  as the ZooKeeper server connection string. Then it tries to 'canonicalize' the address by getting
  the fully qualified domain name belonging to the address. You can disable this 'canonicalization'
  by setting: zookeeper.sasl.client.canonicalize.hostname=false

* *zookeeper.sasl.client.allowReverseDnsLookup* :
  **New in 3.9.5:**
  Controls whether reverse DNS lookup is enabled when constructing the server principal for the SASL client.
  Default: false

* *zookeeper.server.realm* :
  Realm part of the server principal. By default it is the client principal realm.

* *zookeeper.disableAutoWatchReset* :
  This switch controls whether automatic watch resetting is enabled. Clients automatically
  reset watches during session reconnect by default, this option allows the client to turn off
  this behavior by setting zookeeper.disableAutoWatchReset to **true**.

* *zookeeper.client.secure* :
  **New in 3.5.5:**
  If you want to connect to the server secure client port, you need to set this property to
  **true**
  on the client. This will connect to server using SSL with specified credentials. Note that
  it requires the Netty client.

* *zookeeper.clientCnxnSocket* :
  Specifies which ClientCnxnSocket to be used. Possible values are
  **org.apache.zookeeper.ClientCnxnSocketNIO**
  and
  **org.apache.zookeeper.ClientCnxnSocketNetty**
  . Default is
  **org.apache.zookeeper.ClientCnxnSocketNIO**
  . If you want to connect to server's secure client port, you need to set this property to
  **org.apache.zookeeper.ClientCnxnSocketNetty**
  on client.

* *zookeeper.ssl.keyStore.location and zookeeper.ssl.keyStore.password* :
  **New in 3.5.5:**
  Specifies the file path to a JKS containing the local credentials to be used for SSL connections,
  and the password to unlock the file.

* *zookeeper.ssl.keyStore.passwordPath* :
  **New in 3.8.0:**
  Specifies the file path which contains the keystore password

* *zookeeper.ssl.trustStore.location and zookeeper.ssl.trustStore.password* :
  **New in 3.5.5:**
  Specifies the file path to a JKS containing the remote credentials to be used for SSL connections,
  and the password to unlock the file.

* *zookeeper.ssl.trustStore.passwordPath* :
  **New in 3.8.0:**
  Specifies the file path which contains the truststore password

* *zookeeper.ssl.keyStore.type* and *zookeeper.ssl.trustStore.type*:
  **New in 3.5.5:**
  Specifies the file format of keys/trust store files used to establish TLS connection to the ZooKeeper server.
  Values: JKS, PEM, PKCS12 or null (detect by filename). Default: null.
  **New in 3.6.3, 3.7.0:**
  The format BCFKS was added.

* *jute.maxbuffer* :
  In the client side, it specifies the maximum size of the incoming data from the server. The default is 0xfffff(1048575) bytes,
  or just under 1M. This is really a sanity check. The ZooKeeper server is designed to store and send
  data on the order of kilobytes. If incoming data length is more than this value, an IOException
  is raised. This value of client side should keep same with the server side(Setting **System.setProperty("jute.maxbuffer", "xxxx")** in the client side will work),
  otherwise problems will arise.

* *zookeeper.kinit* :
  Specifies path to kinit binary. Default is "/usr/bin/kinit".

* *zookeeper.shuffleDnsResponse* :
  **New in 3.10.0:**
  Specifies whether ZooKeeper client should randomly pick an IP address from the DNS lookup query result when resolving
  server addresses or not. This is a feature flag in order to keep the old behavior of the default DNS resolver in
  \`StaticHostProvider\`, because we disabled it by default. The reason behind that is shuffling the response of DNS queries
  shadows JVM network property \`java.net.preferIPv6Addresses\` (default: false). This property controls whether JVM's built-in
  resolver should prioritize v4 (false value) or v6 (true value) addresses when putting together the list of IP addresses
  in the result. In other words the above Java system property was completely ineffective in the case of ZooKeeper server host
  resolution protocol and this must have been fixed. In a dual stack environment one might want to prefer one stack over
  the other which, in case of Java processes, should be controlled by JVM network properties and ZooKeeper must honor
  these settings. Since the old behavior has been with us since day zero, we introduced this feature flag in case you
  need it. Such case could be when you have a buggy DNS server which responds IP addresses always in the same order and
  you want to randomize that.
  Default: false

* *zookeeper.hostProvider.dnsSrvRefreshIntervalSeconds* :
  **New in 3.10.0:**
  Specifies the refresh interval in seconds for DNS SRV record lookups when using DnsSrvHostProvider.
  A value of 0 disables periodic refresh.
  Default: 60 seconds

## C Binding

The C binding has a single-threaded and multi-threaded library.
The multi-threaded library is easiest to use and is most similar to the
Java API. This library will create an IO thread and an event dispatch
thread for handling connection maintenance and callbacks. The
single-threaded library allows ZooKeeper to be used in event driven
applications by exposing the event loop used in the multi-threaded
library.

The package includes two shared libraries: zookeeper*st and
zookeeper\\_mt. The former only provides the asynchronous APIs and
callbacks for integrating into the application's event loop. The only
reason this library exists is to support the platforms were a
\\_pthread* library is not available or is unstable
(i.e. FreeBSD 4.x). In all other cases, application developers should
link with zookeeper\\_mt, as it includes support for both Sync and Async
API.

### Installation

If you're building the client from a check-out from the Apache
repository, follow the steps outlined below. If you're building from a
project source package downloaded from apache, skip to step **3**.

1. Run \`mvn compile\` in zookeeper-jute directory (*.../trunk/zookeeper-jute*).
   This will create a directory named "generated" under
   *.../trunk/zookeeper-client/zookeeper-client-c*.
2. Change directory to the\\*.../trunk/zookeeper-client/zookeeper-client-c\\*
   and run \`autoreconf -if\` to bootstrap **autoconf**, **automake** and **libtool**. Make sure you have **autoconf version 2.59** or greater installed.
   Skip to step**4**.
3. If you are building from a project source package,
   unzip/untar the source tarball and cd to the\\*
   zookeeper-x.x.x/zookeeper-client/zookeeper-client-c\\* directory.
4. Run \`./configure <your-options>\` to
   generate the makefile. Here are some of options the **configure** utility supports that can be
   useful in this step:

* \`--enable-debug\`
  Enables optimization and enables debug info compiler
  options. (Disabled by default.)
* \`--without-syncapi\`
  Disables Sync API support; zookeeper\\_mt library won't be
  built. (Enabled by default.)
* \`--disable-static\`
  Do not build static libraries. (Enabled by
  default.)
* \`--disable-shared\`
  Do not build shared libraries. (Enabled by
  default.)
  <Callout type="info">
    See INSTALL for general information about running **configure**. 1. Run
    \`make\` or \`make install\` to build the libraries and install them. 1. To
    generate doxygen documentation for the ZooKeeper API, run \`make
      doxygen-doc\`. All documentation will be placed in a new subfolder named
    docs. By default, this command only generates HTML. For information on other
    document formats, run \`./configure --help\`
  </Callout>

### Building Your Own C Client

In order to be able to use the ZooKeeper C API in your application
you have to remember to

1. Include ZooKeeper header: \`#include <zookeeper/zookeeper.h>\`
2. If you are building a multithreaded client, compile with
   \`-DTHREADED\` compiler flag to enable the multi-threaded version of
   the library, and then link against the
   *zookeeper\\_mt* library. If you are building a
   single-threaded client, do not compile with \`-DTHREADED\`, and be
   sure to link against the\\_zookeeper\\_st\\_library.

<Callout type="info">
  See *.../trunk/zookeeper-client/zookeeper-client-c/src/cli.c* for an example
  of a C client implementation
</Callout>
`,l={title:"Bindings",description:"Documents the Java and C client library bindings for ZooKeeper, including the ZooKeeper class, callbacks, POSIX-style C API, multithreaded and single-threaded libraries."},c=[{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide"},{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL"}],h={contents:[{heading:void 0,content:`The ZooKeeper client libraries come in two languages: Java and C.
The following sections describe these.`},{heading:"java-binding",content:`There are two packages that make up the ZooKeeper Java binding:
org.apache.zookeeper and org.apache.zookeeper.data. The rest of the
packages that make up ZooKeeper are used internally or are part of the
server implementation. The org.apache.zookeeper.data package is made up of
generated classes that are used simply as containers.`},{heading:"java-binding",content:`The main class used by a ZooKeeper Java client is the ZooKeeper class. Its two constructors differ only
by an optional session id and password. ZooKeeper supports session
recovery across instances of a process. A Java program may save its
session id and password to stable storage, restart, and recover the
session that was used by the earlier instance of the program.`},{heading:"java-binding",content:`When a ZooKeeper object is created, two threads are created as
well: an IO thread and an event thread. All IO happens on the IO thread
(using Java NIO). All event callbacks happen on the event thread.
Session maintenance such as reconnecting to ZooKeeper servers and
maintaining heartbeat is done on the IO thread. Responses for
synchronous methods are also processed in the IO thread. All responses
to asynchronous methods and watch events are processed on the event
thread. There are a few things to notice that result from this
design:`},{heading:"java-binding",content:`All completions for asynchronous calls and watcher callbacks
will be made in order, one at a time. The caller can do any
processing they wish, but no other callbacks will be processed
during that time.`},{heading:"java-binding",content:`Callbacks do not block the processing of the IO thread or the
processing of the synchronous calls.`},{heading:"java-binding",content:`Synchronous calls may not return in the correct order. For
example, assume a client does the following processing: issues an
asynchronous read of node /a with
watch set to true, and then in the completion
callback of the read it does a synchronous read of /a. (Maybe not good practice, but not illegal
either, and it makes for a simple example.)
Note that if there is a change to /a between the asynchronous read and the
synchronous read, the client library will receive the watch event
saying /a changed before the
response for the synchronous read, but because of the completion
callback blocking the event queue, the synchronous read will
return with the new value of /a
before the watch event is processed.`},{heading:"java-binding",content:`Finally, the rules associated with shutdown are straightforward:
once a ZooKeeper object is closed or receives a fatal event
(SESSION_EXPIRED and AUTH_FAILED), the ZooKeeper object becomes invalid.
On a close, the two threads shut down and any further access on zookeeper
handle is undefined behavior and should be avoided.`},{heading:"client-configuration-parameters",content:`The following list contains configuration properties for the Java client. You can set any
of these properties using Java system properties. For server properties, please check the
Server configuration section of the Admin Guide.
The ZooKeeper Wiki also has useful pages about
ZooKeeper SSL support,
and SASL authentication for ZooKeeper.`},{heading:"client-configuration-parameters",content:`zookeeper.sasl.client :
Set the value to false to disable
SASL authentication. Default is true.`},{heading:"client-configuration-parameters",content:`zookeeper.sasl.clientconfig :
Specifies the context key in the JAAS login file. Default is "Client".`},{heading:"client-configuration-parameters",content:`zookeeper.server.principal :
Specifies the server principal to be used by the client for authentication, while connecting to the zookeeper
server, when Kerberos authentication is enabled. If this configuration is provided, then
the ZooKeeper client will NOT USE any of the following parameters to determine the server principal:`},{heading:"client-configuration-parameters",content:`zookeeper.sasl.client.username :
Traditionally, a principal is divided into three parts: the primary, the instance, and the realm.
The format of a typical Kerberos V5 principal is primary/instance@REALM.
zookeeper.sasl.client.username specifies the primary part of the server principal. Default
is "zookeeper". Instance part is derived from the server IP. Finally server's principal is
username/IP@realm, where username is the value of zookeeper.sasl.client.username, IP is
the server IP, and realm is the value of zookeeper.server.realm.`},{heading:"client-configuration-parameters",content:`zookeeper.sasl.client.canonicalize.hostname :
Expecting the zookeeper.server.principal parameter is not provided, the ZooKeeper client will try to
determine the 'instance' (host) part of the ZooKeeper server principal. First it takes the hostname provided
as the ZooKeeper server connection string. Then it tries to 'canonicalize' the address by getting
the fully qualified domain name belonging to the address. You can disable this 'canonicalization'
by setting: zookeeper.sasl.client.canonicalize.hostname=false`},{heading:"client-configuration-parameters",content:`zookeeper.sasl.client.allowReverseDnsLookup :
New in 3.9.5:
Controls whether reverse DNS lookup is enabled when constructing the server principal for the SASL client.
Default: false`},{heading:"client-configuration-parameters",content:`zookeeper.server.realm :
Realm part of the server principal. By default it is the client principal realm.`},{heading:"client-configuration-parameters",content:`zookeeper.disableAutoWatchReset :
This switch controls whether automatic watch resetting is enabled. Clients automatically
reset watches during session reconnect by default, this option allows the client to turn off
this behavior by setting zookeeper.disableAutoWatchReset to true.`},{heading:"client-configuration-parameters",content:`zookeeper.client.secure :
New in 3.5.5:
If you want to connect to the server secure client port, you need to set this property to
true
on the client. This will connect to server using SSL with specified credentials. Note that
it requires the Netty client.`},{heading:"client-configuration-parameters",content:`zookeeper.clientCnxnSocket :
Specifies which ClientCnxnSocket to be used. Possible values are
org.apache.zookeeper.ClientCnxnSocketNIO
and
org.apache.zookeeper.ClientCnxnSocketNetty
. Default is
org.apache.zookeeper.ClientCnxnSocketNIO
. If you want to connect to server's secure client port, you need to set this property to
org.apache.zookeeper.ClientCnxnSocketNetty
on client.`},{heading:"client-configuration-parameters",content:`zookeeper.ssl.keyStore.location and zookeeper.ssl.keyStore.password :
New in 3.5.5:
Specifies the file path to a JKS containing the local credentials to be used for SSL connections,
and the password to unlock the file.`},{heading:"client-configuration-parameters",content:`zookeeper.ssl.keyStore.passwordPath :
New in 3.8.0:
Specifies the file path which contains the keystore password`},{heading:"client-configuration-parameters",content:`zookeeper.ssl.trustStore.location and zookeeper.ssl.trustStore.password :
New in 3.5.5:
Specifies the file path to a JKS containing the remote credentials to be used for SSL connections,
and the password to unlock the file.`},{heading:"client-configuration-parameters",content:`zookeeper.ssl.trustStore.passwordPath :
New in 3.8.0:
Specifies the file path which contains the truststore password`},{heading:"client-configuration-parameters",content:`zookeeper.ssl.keyStore.type and zookeeper.ssl.trustStore.type:
New in 3.5.5:
Specifies the file format of keys/trust store files used to establish TLS connection to the ZooKeeper server.
Values: JKS, PEM, PKCS12 or null (detect by filename). Default: null.
New in 3.6.3, 3.7.0:
The format BCFKS was added.`},{heading:"client-configuration-parameters",content:`jute.maxbuffer :
In the client side, it specifies the maximum size of the incoming data from the server. The default is 0xfffff(1048575) bytes,
or just under 1M. This is really a sanity check. The ZooKeeper server is designed to store and send
data on the order of kilobytes. If incoming data length is more than this value, an IOException
is raised. This value of client side should keep same with the server side(Setting System.setProperty("jute.maxbuffer", "xxxx") in the client side will work),
otherwise problems will arise.`},{heading:"client-configuration-parameters",content:`zookeeper.kinit :
Specifies path to kinit binary. Default is "/usr/bin/kinit".`},{heading:"client-configuration-parameters",content:`zookeeper.shuffleDnsResponse :
New in 3.10.0:
Specifies whether ZooKeeper client should randomly pick an IP address from the DNS lookup query result when resolving
server addresses or not. This is a feature flag in order to keep the old behavior of the default DNS resolver in
StaticHostProvider, because we disabled it by default. The reason behind that is shuffling the response of DNS queries
shadows JVM network property java.net.preferIPv6Addresses (default: false). This property controls whether JVM's built-in
resolver should prioritize v4 (false value) or v6 (true value) addresses when putting together the list of IP addresses
in the result. In other words the above Java system property was completely ineffective in the case of ZooKeeper server host
resolution protocol and this must have been fixed. In a dual stack environment one might want to prefer one stack over
the other which, in case of Java processes, should be controlled by JVM network properties and ZooKeeper must honor
these settings. Since the old behavior has been with us since day zero, we introduced this feature flag in case you
need it. Such case could be when you have a buggy DNS server which responds IP addresses always in the same order and
you want to randomize that.
Default: false`},{heading:"client-configuration-parameters",content:`zookeeper.hostProvider.dnsSrvRefreshIntervalSeconds :
New in 3.10.0:
Specifies the refresh interval in seconds for DNS SRV record lookups when using DnsSrvHostProvider.
A value of 0 disables periodic refresh.
Default: 60 seconds`},{heading:"c-binding",content:`The C binding has a single-threaded and multi-threaded library.
The multi-threaded library is easiest to use and is most similar to the
Java API. This library will create an IO thread and an event dispatch
thread for handling connection maintenance and callbacks. The
single-threaded library allows ZooKeeper to be used in event driven
applications by exposing the event loop used in the multi-threaded
library.`},{heading:"c-binding",content:`The package includes two shared libraries: zookeeperst and
zookeeper_mt. The former only provides the asynchronous APIs and
callbacks for integrating into the application's event loop. The only
reason this library exists is to support the platforms were a
_pthread library is not available or is unstable
(i.e. FreeBSD 4.x). In all other cases, application developers should
link with zookeeper_mt, as it includes support for both Sync and Async
API.`},{heading:"installation",content:`If you're building the client from a check-out from the Apache
repository, follow the steps outlined below. If you're building from a
project source package downloaded from apache, skip to step 3.`},{heading:"installation",content:`Run mvn compile in zookeeper-jute directory (.../trunk/zookeeper-jute).
This will create a directory named "generated" under
.../trunk/zookeeper-client/zookeeper-client-c.`},{heading:"installation",content:`Change directory to the*.../trunk/zookeeper-client/zookeeper-client-c*
and run autoreconf -if to bootstrap autoconf, automake and libtool. Make sure you have autoconf version 2.59 or greater installed.
Skip to step4.`},{heading:"installation",content:`If you are building from a project source package,
unzip/untar the source tarball and cd to the*
zookeeper-x.x.x/zookeeper-client/zookeeper-client-c* directory.`},{heading:"installation",content:`Run ./configure <your-options> to
generate the makefile. Here are some of options the configure utility supports that can be
useful in this step:`},{heading:"installation",content:`--enable-debug
Enables optimization and enables debug info compiler
options. (Disabled by default.)`},{heading:"installation",content:`--without-syncapi
Disables Sync API support; zookeeper_mt library won't be
built. (Enabled by default.)`},{heading:"installation",content:`--disable-static
Do not build static libraries. (Enabled by
default.)`},{heading:"installation",content:`--disable-shared
Do not build shared libraries. (Enabled by
default.)`},{heading:"installation",content:"type: info"},{heading:"installation",content:`See INSTALL for general information about running configure. 1. Run
make or make install to build the libraries and install them. 1. To
generate doxygen documentation for the ZooKeeper API, run make
  doxygen-doc. All documentation will be placed in a new subfolder named
docs. By default, this command only generates HTML. For information on other
document formats, run ./configure --help`},{heading:"building-your-own-c-client",content:`In order to be able to use the ZooKeeper C API in your application
you have to remember to`},{heading:"building-your-own-c-client",content:"Include ZooKeeper header: #include <zookeeper/zookeeper.h>"},{heading:"building-your-own-c-client",content:`If you are building a multithreaded client, compile with
-DTHREADED compiler flag to enable the multi-threaded version of
the library, and then link against the
zookeeper_mt library. If you are building a
single-threaded client, do not compile with -DTHREADED, and be
sure to link against the_zookeeper_st_library.`},{heading:"building-your-own-c-client",content:"type: info"},{heading:"building-your-own-c-client",content:`See .../trunk/zookeeper-client/zookeeper-client-c/src/cli.c for an example
of a C client implementation`}],headings:[{id:"java-binding",content:"Java Binding"},{id:"client-configuration-parameters",content:"Client Configuration Parameters"},{id:"c-binding",content:"C Binding"},{id:"installation",content:"Installation"},{id:"building-your-own-c-client",content:"Building Your Own C Client"}]};const d=[{depth:2,url:"#java-binding",title:e.jsx(e.Fragment,{children:"Java Binding"})},{depth:3,url:"#client-configuration-parameters",title:e.jsx(e.Fragment,{children:"Client Configuration Parameters"})},{depth:2,url:"#c-binding",title:e.jsx(e.Fragment,{children:"C Binding"})},{depth:3,url:"#installation",title:e.jsx(e.Fragment,{children:"Installation"})},{depth:3,url:"#building-your-own-c-client",title:e.jsx(e.Fragment,{children:"Building Your Own C Client"})}];function r(t){const n={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",li:"li",ol:"ol",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...t.components},{Callout:o}=n;return o||i("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(n.p,{children:`The ZooKeeper client libraries come in two languages: Java and C.
The following sections describe these.`}),`
`,e.jsx(n.h2,{id:"java-binding",children:"Java Binding"}),`
`,e.jsxs(n.p,{children:[`There are two packages that make up the ZooKeeper Java binding:
`,e.jsx(n.strong,{children:"org.apache.zookeeper"})," and ",e.jsx(n.strong,{children:"org.apache.zookeeper.data"}),`. The rest of the
packages that make up ZooKeeper are used internally or are part of the
server implementation. The `,e.jsx(n.strong,{children:"org.apache.zookeeper.data"}),` package is made up of
generated classes that are used simply as containers.`]}),`
`,e.jsxs(n.p,{children:["The main class used by a ZooKeeper Java client is the ",e.jsx(n.strong,{children:"ZooKeeper"}),` class. Its two constructors differ only
by an optional session id and password. ZooKeeper supports session
recovery across instances of a process. A Java program may save its
session id and password to stable storage, restart, and recover the
session that was used by the earlier instance of the program.`]}),`
`,e.jsx(n.p,{children:`When a ZooKeeper object is created, two threads are created as
well: an IO thread and an event thread. All IO happens on the IO thread
(using Java NIO). All event callbacks happen on the event thread.
Session maintenance such as reconnecting to ZooKeeper servers and
maintaining heartbeat is done on the IO thread. Responses for
synchronous methods are also processed in the IO thread. All responses
to asynchronous methods and watch events are processed on the event
thread. There are a few things to notice that result from this
design:`}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsx(n.li,{children:`All completions for asynchronous calls and watcher callbacks
will be made in order, one at a time. The caller can do any
processing they wish, but no other callbacks will be processed
during that time.`}),`
`,e.jsx(n.li,{children:`Callbacks do not block the processing of the IO thread or the
processing of the synchronous calls.`}),`
`,e.jsxs(n.li,{children:[`Synchronous calls may not return in the correct order. For
example, assume a client does the following processing: issues an
asynchronous read of node `,e.jsx(n.strong,{children:"/a"}),` with
`,e.jsx(n.em,{children:"watch"}),` set to true, and then in the completion
callback of the read it does a synchronous read of `,e.jsx(n.strong,{children:"/a"}),`. (Maybe not good practice, but not illegal
either, and it makes for a simple example.)
Note that if there is a change to `,e.jsx(n.strong,{children:"/a"}),` between the asynchronous read and the
synchronous read, the client library will receive the watch event
saying `,e.jsx(n.strong,{children:"/a"}),` changed before the
response for the synchronous read, but because of the completion
callback blocking the event queue, the synchronous read will
return with the new value of `,e.jsx(n.strong,{children:"/a"}),`
before the watch event is processed.`]}),`
`]}),`
`,e.jsx(n.p,{children:`Finally, the rules associated with shutdown are straightforward:
once a ZooKeeper object is closed or receives a fatal event
(SESSION_EXPIRED and AUTH_FAILED), the ZooKeeper object becomes invalid.
On a close, the two threads shut down and any further access on zookeeper
handle is undefined behavior and should be avoided.`}),`
`,e.jsx(n.h3,{id:"client-configuration-parameters",children:"Client Configuration Parameters"}),`
`,e.jsxs(n.p,{children:[`The following list contains configuration properties for the Java client. You can set any
of these properties using Java system properties. For server properties, please check the
`,e.jsx(n.a,{href:"/admin-ops/administrators-guide/configuration-parameters#advanced-configuration",children:"Server configuration section of the Admin Guide"}),`.
The ZooKeeper Wiki also has useful pages about
`,e.jsx(n.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+SSL+User+Guide",children:"ZooKeeper SSL support"}),`,
and `,e.jsx(n.a,{href:"https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZooKeeper+and+SASL",children:"SASL authentication for ZooKeeper"}),"."]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.sasl.client"}),` :
Set the value to `,e.jsx(n.strong,{children:"false"}),` to disable
SASL authentication. Default is `,e.jsx(n.strong,{children:"true"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.sasl.clientconfig"}),` :
Specifies the context key in the JAAS login file. Default is "Client".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.server.principal"}),` :
Specifies the server principal to be used by the client for authentication, while connecting to the zookeeper
server, when Kerberos authentication is enabled. If this configuration is provided, then
the ZooKeeper client will NOT USE any of the following parameters to determine the server principal:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(n.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(n.code,{children:[e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zookeeper.sasl.client.username, zookeeper.sasl.client.canonicalize.hostname, zookeeper.server.realm"})}),`
`,e.jsx(n.span,{className:"line",children:e.jsx(n.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"Note: this config parameter is working only for ZooKeeper 3.5.7+, 3.6.0+"})})]})})}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.sasl.client.username"}),` :
Traditionally, a principal is divided into three parts: the primary, the instance, and the realm.
The format of a typical Kerberos V5 principal is primary/instance@REALM.
zookeeper.sasl.client.username specifies the primary part of the server principal. Default
is "zookeeper". Instance part is derived from the server IP. Finally server's principal is
username/IP@realm, where username is the value of zookeeper.sasl.client.username, IP is
the server IP, and realm is the value of zookeeper.server.realm.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.sasl.client.canonicalize.hostname"}),` :
Expecting the zookeeper.server.principal parameter is not provided, the ZooKeeper client will try to
determine the 'instance' (host) part of the ZooKeeper server principal. First it takes the hostname provided
as the ZooKeeper server connection string. Then it tries to 'canonicalize' the address by getting
the fully qualified domain name belonging to the address. You can disable this 'canonicalization'
by setting: zookeeper.sasl.client.canonicalize.hostname=false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.sasl.client.allowReverseDnsLookup"}),` :
`,e.jsx(n.strong,{children:"New in 3.9.5:"}),`
Controls whether reverse DNS lookup is enabled when constructing the server principal for the SASL client.
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.server.realm"}),` :
Realm part of the server principal. By default it is the client principal realm.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.disableAutoWatchReset"}),` :
This switch controls whether automatic watch resetting is enabled. Clients automatically
reset watches during session reconnect by default, this option allows the client to turn off
this behavior by setting zookeeper.disableAutoWatchReset to `,e.jsx(n.strong,{children:"true"}),"."]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.client.secure"}),` :
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
If you want to connect to the server secure client port, you need to set this property to
`,e.jsx(n.strong,{children:"true"}),`
on the client. This will connect to server using SSL with specified credentials. Note that
it requires the Netty client.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.clientCnxnSocket"}),` :
Specifies which ClientCnxnSocket to be used. Possible values are
`,e.jsx(n.strong,{children:"org.apache.zookeeper.ClientCnxnSocketNIO"}),`
and
`,e.jsx(n.strong,{children:"org.apache.zookeeper.ClientCnxnSocketNetty"}),`
. Default is
`,e.jsx(n.strong,{children:"org.apache.zookeeper.ClientCnxnSocketNIO"}),`
. If you want to connect to server's secure client port, you need to set this property to
`,e.jsx(n.strong,{children:"org.apache.zookeeper.ClientCnxnSocketNetty"}),`
on client.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ssl.keyStore.location and zookeeper.ssl.keyStore.password"}),` :
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file path to a JKS containing the local credentials to be used for SSL connections,
and the password to unlock the file.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ssl.keyStore.passwordPath"}),` :
`,e.jsx(n.strong,{children:"New in 3.8.0:"}),`
Specifies the file path which contains the keystore password`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ssl.trustStore.location and zookeeper.ssl.trustStore.password"}),` :
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file path to a JKS containing the remote credentials to be used for SSL connections,
and the password to unlock the file.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ssl.trustStore.passwordPath"}),` :
`,e.jsx(n.strong,{children:"New in 3.8.0:"}),`
Specifies the file path which contains the truststore password`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.ssl.keyStore.type"})," and ",e.jsx(n.em,{children:"zookeeper.ssl.trustStore.type"}),`:
`,e.jsx(n.strong,{children:"New in 3.5.5:"}),`
Specifies the file format of keys/trust store files used to establish TLS connection to the ZooKeeper server.
Values: JKS, PEM, PKCS12 or null (detect by filename). Default: null.
`,e.jsx(n.strong,{children:"New in 3.6.3, 3.7.0:"}),`
The format BCFKS was added.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"jute.maxbuffer"}),` :
In the client side, it specifies the maximum size of the incoming data from the server. The default is 0xfffff(1048575) bytes,
or just under 1M. This is really a sanity check. The ZooKeeper server is designed to store and send
data on the order of kilobytes. If incoming data length is more than this value, an IOException
is raised. This value of client side should keep same with the server side(Setting `,e.jsx(n.strong,{children:'System.setProperty("jute.maxbuffer", "xxxx")'}),` in the client side will work),
otherwise problems will arise.`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.kinit"}),` :
Specifies path to kinit binary. Default is "/usr/bin/kinit".`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.shuffleDnsResponse"}),` :
`,e.jsx(n.strong,{children:"New in 3.10.0:"}),`
Specifies whether ZooKeeper client should randomly pick an IP address from the DNS lookup query result when resolving
server addresses or not. This is a feature flag in order to keep the old behavior of the default DNS resolver in
`,e.jsx(n.code,{children:"StaticHostProvider"}),`, because we disabled it by default. The reason behind that is shuffling the response of DNS queries
shadows JVM network property `,e.jsx(n.code,{children:"java.net.preferIPv6Addresses"}),` (default: false). This property controls whether JVM's built-in
resolver should prioritize v4 (false value) or v6 (true value) addresses when putting together the list of IP addresses
in the result. In other words the above Java system property was completely ineffective in the case of ZooKeeper server host
resolution protocol and this must have been fixed. In a dual stack environment one might want to prefer one stack over
the other which, in case of Java processes, should be controlled by JVM network properties and ZooKeeper must honor
these settings. Since the old behavior has been with us since day zero, we introduced this feature flag in case you
need it. Such case could be when you have a buggy DNS server which responds IP addresses always in the same order and
you want to randomize that.
Default: false`]}),`
`]}),`
`,e.jsxs(n.li,{children:[`
`,e.jsxs(n.p,{children:[e.jsx(n.em,{children:"zookeeper.hostProvider.dnsSrvRefreshIntervalSeconds"}),` :
`,e.jsx(n.strong,{children:"New in 3.10.0:"}),`
Specifies the refresh interval in seconds for DNS SRV record lookups when using DnsSrvHostProvider.
A value of 0 disables periodic refresh.
Default: 60 seconds`]}),`
`]}),`
`]}),`
`,e.jsx(n.h2,{id:"c-binding",children:"C Binding"}),`
`,e.jsx(n.p,{children:`The C binding has a single-threaded and multi-threaded library.
The multi-threaded library is easiest to use and is most similar to the
Java API. This library will create an IO thread and an event dispatch
thread for handling connection maintenance and callbacks. The
single-threaded library allows ZooKeeper to be used in event driven
applications by exposing the event loop used in the multi-threaded
library.`}),`
`,e.jsxs(n.p,{children:["The package includes two shared libraries: zookeeper",e.jsx(n.em,{children:`st and
zookeeper_mt. The former only provides the asynchronous APIs and
callbacks for integrating into the application's event loop. The only
reason this library exists is to support the platforms were a
_pthread`}),` library is not available or is unstable
(i.e. FreeBSD 4.x). In all other cases, application developers should
link with zookeeper_mt, as it includes support for both Sync and Async
API.`]}),`
`,e.jsx(n.h3,{id:"installation",children:"Installation"}),`
`,e.jsxs(n.p,{children:[`If you're building the client from a check-out from the Apache
repository, follow the steps outlined below. If you're building from a
project source package downloaded from apache, skip to step `,e.jsx(n.strong,{children:"3"}),"."]}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:["Run ",e.jsx(n.code,{children:"mvn compile"})," in zookeeper-jute directory (",e.jsx(n.em,{children:".../trunk/zookeeper-jute"}),`).
This will create a directory named "generated" under
`,e.jsx(n.em,{children:".../trunk/zookeeper-client/zookeeper-client-c"}),"."]}),`
`,e.jsxs(n.li,{children:[`Change directory to the*.../trunk/zookeeper-client/zookeeper-client-c*
and run `,e.jsx(n.code,{children:"autoreconf -if"})," to bootstrap ",e.jsx(n.strong,{children:"autoconf"}),", ",e.jsx(n.strong,{children:"automake"})," and ",e.jsx(n.strong,{children:"libtool"}),". Make sure you have ",e.jsx(n.strong,{children:"autoconf version 2.59"}),` or greater installed.
Skip to step`,e.jsx(n.strong,{children:"4"}),"."]}),`
`,e.jsx(n.li,{children:`If you are building from a project source package,
unzip/untar the source tarball and cd to the*
zookeeper-x.x.x/zookeeper-client/zookeeper-client-c* directory.`}),`
`,e.jsxs(n.li,{children:["Run ",e.jsx(n.code,{children:"./configure <your-options>"}),` to
generate the makefile. Here are some of options the `,e.jsx(n.strong,{children:"configure"}),` utility supports that can be
useful in this step:`]}),`
`]}),`
`,e.jsxs(n.ul,{children:[`
`,e.jsxs(n.li,{children:[e.jsx(n.code,{children:"--enable-debug"}),`
Enables optimization and enables debug info compiler
options. (Disabled by default.)`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.code,{children:"--without-syncapi"}),`
Disables Sync API support; zookeeper_mt library won't be
built. (Enabled by default.)`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.code,{children:"--disable-static"}),`
Do not build static libraries. (Enabled by
default.)`]}),`
`,e.jsxs(n.li,{children:[e.jsx(n.code,{children:"--disable-shared"}),`
Do not build shared libraries. (Enabled by
default.)`,`
`,e.jsx(o,{type:"info",children:e.jsxs(n.p,{children:["See INSTALL for general information about running ",e.jsx(n.strong,{children:"configure"}),`. 1. Run
`,e.jsx(n.code,{children:"make"})," or ",e.jsx(n.code,{children:"make install"}),` to build the libraries and install them. 1. To
generate doxygen documentation for the ZooKeeper API, run `,e.jsx(n.code,{children:"make   doxygen-doc"}),`. All documentation will be placed in a new subfolder named
docs. By default, this command only generates HTML. For information on other
document formats, run `,e.jsx(n.code,{children:"./configure --help"})]})}),`
`]}),`
`]}),`
`,e.jsx(n.h3,{id:"building-your-own-c-client",children:"Building Your Own C Client"}),`
`,e.jsx(n.p,{children:`In order to be able to use the ZooKeeper C API in your application
you have to remember to`}),`
`,e.jsxs(n.ol,{children:[`
`,e.jsxs(n.li,{children:["Include ZooKeeper header: ",e.jsx(n.code,{children:"#include <zookeeper/zookeeper.h>"})]}),`
`,e.jsxs(n.li,{children:[`If you are building a multithreaded client, compile with
`,e.jsx(n.code,{children:"-DTHREADED"}),` compiler flag to enable the multi-threaded version of
the library, and then link against the
`,e.jsx(n.em,{children:"zookeeper_mt"}),` library. If you are building a
single-threaded client, do not compile with `,e.jsx(n.code,{children:"-DTHREADED"}),`, and be
sure to link against the_zookeeper_st_library.`]}),`
`]}),`
`,e.jsx(o,{type:"info",children:e.jsxs(n.p,{children:["See ",e.jsx(n.em,{children:".../trunk/zookeeper-client/zookeeper-client-c/src/cli.c"}),` for an example
of a C client implementation`]})})]})}function p(t={}){const{wrapper:n}=t.components||{};return n?e.jsx(n,{...t,children:e.jsx(r,{...t})}):r(t)}function i(t,n){throw new Error("Expected component `"+t+"` to be defined: you likely forgot to import, pass, or provide it.")}export{a as _markdown,p as default,c as extractedReferences,l as frontmatter,h as structuredData,d as toc};
