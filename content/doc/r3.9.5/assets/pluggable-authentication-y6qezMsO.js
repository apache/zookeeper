import{j as e}from"./chunk-EPOLDU6W-CDTnuKF9.js";let a=`ZooKeeper runs in a variety of different environments with
various different authentication schemes, so it has a completely
pluggable authentication framework. Even the builtin authentication
schemes use the pluggable authentication framework.

To understand how the authentication framework works, first you must
understand the two main authentication operations. The framework
first must authenticate the client. This is usually done as soon as
the client connects to a server and consists of validating information
sent from or gathered about a client and associating it with the connection.
The second operation handled by the framework is finding the entries in an
ACL that correspond to client. ACL entries are \`<idspec, permissions>\` pairs. The *idspec* may be
a simple string match against the authentication information associated
with the connection or it may be a expression that is evaluated against that
information. It is up to the implementation of the authentication plugin
to do the match. Here is the interface that an authentication plugin must
implement:

\`\`\`java
public interface AuthenticationProvider {
    String getScheme();
    KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte authData[]);
    boolean isValid(String id);
    boolean matches(String id, String aclExpr);
    boolean isAuthenticated();
}

\`\`\`

The first method *getScheme* returns the string
that identifies the plugin. Because we support multiple methods of authentication,
an authentication credential or an *idspec* will always be
prefixed with *scheme:*. The ZooKeeper server uses the scheme
returned by the authentication plugin to determine which ids the scheme
applies to.

*handleAuthentication* is called when a client
sends authentication information to be associated with a connection. The
client specifies the scheme to which the information corresponds. The
ZooKeeper server passes the information to the authentication plugin whose
*getScheme* matches the scheme passed by the client. The
implementor of *handleAuthentication* will usually return
an error if it determines that the information is bad, or it will associate information
with the connection using *cnxn.getAuthInfo().add(new Id(getScheme(), data))*.

The authentication plugin is involved in both setting and using ACLs. When an
ACL is set for a znode, the ZooKeeper server will pass the id part of the entry to
the *isValid(String id)* method. It is up to the plugin to verify
that the id has a correct form. For example, *ip:172.16.0.0/16*
is a valid id, but *ip:host.com* is not. If the new ACL includes
an "auth" entry, *isAuthenticated* is used to see if the
authentication information for this scheme that is associated with the connection
should be added to the ACL. Some schemes
should not be included in auth. For example, the IP address of the client is not
considered as an id that should be added to the ACL if auth is specified.

ZooKeeper invokes *matches(String id, String aclExpr)* when checking an ACL. It
needs to match authentication information of the client against the relevant ACL
entries. To find the entries which apply to the client, the ZooKeeper server will
find the scheme of each entry and if there is authentication information
from that client for that scheme, *matches(String id, String aclExpr)*
will be called with *id* set to the authentication information
that was previously added to the connection by *handleAuthentication* and
*aclExpr* set to the id of the ACL entry. The authentication plugin
uses its own logic and matching scheme to determine if *id* is included
in *aclExpr*.

There are two built in authentication plugins: *ip* and
*digest*. Additional plugins can adding using system properties. At
startup the ZooKeeper server will look for system properties that start with
"zookeeper.authProvider." and interpret the value of those properties as the class name
of an authentication plugin. These properties can be set using the
*-Dzookeeper.authProvider.X=com.f.MyAuth* or adding entries such as
the following in the server configuration file:

\`\`\`properties
authProvider.1=com.f.MyAuth
authProvider.2=com.f.MyAuth2

\`\`\`

Care should be taking to ensure that the suffix on the property is unique. If there are
duplicates such as *-Dzookeeper.authProvider.X=com.f.MyAuth -Dzookeeper.authProvider.X=com.f.MyAuth2*,
only one will be used. Also all servers must have the same plugins defined, otherwise clients using
the authentication schemes provided by the plugins will have problems connecting to some servers.

**Added in 3.6.0**: An alternate abstraction is available for pluggable
authentication. It provides additional arguments.

\`\`\`java
public abstract class ServerAuthenticationProvider implements AuthenticationProvider {
    public abstract KeeperException.Code handleAuthentication(ServerObjs serverObjs, byte authData[]);
    public abstract boolean matches(ServerObjs serverObjs, MatchValues matchValues);
}

\`\`\`

Instead of implementing AuthenticationProvider you extend ServerAuthenticationProvider. Your handleAuthentication()
and matches() methods will then receive the additional parameters (via ServerObjs and MatchValues).

* **ZooKeeperServer**
  The ZooKeeperServer instance
* **ServerCnxn**
  The current connection
* **path**
  The ZNode path being operated on (or null if not used)
* **perm**
  The operation value or 0
* **setAcls**
  When the setAcl() method is being operated on, the list of ACLs that are being set
`,h={title:"Pluggable Authentication",description:"Describes ZooKeeper's pluggable authentication framework, how authentication plugins authenticate clients and match ACL entries, and how to add a custom scheme."},r=[],o={contents:[{heading:void 0,content:`ZooKeeper runs in a variety of different environments with
various different authentication schemes, so it has a completely
pluggable authentication framework. Even the builtin authentication
schemes use the pluggable authentication framework.`},{heading:void 0,content:`To understand how the authentication framework works, first you must
understand the two main authentication operations. The framework
first must authenticate the client. This is usually done as soon as
the client connects to a server and consists of validating information
sent from or gathered about a client and associating it with the connection.
The second operation handled by the framework is finding the entries in an
ACL that correspond to client. ACL entries are <idspec, permissions> pairs. The idspec may be
a simple string match against the authentication information associated
with the connection or it may be a expression that is evaluated against that
information. It is up to the implementation of the authentication plugin
to do the match. Here is the interface that an authentication plugin must
implement:`},{heading:void 0,content:`The first method getScheme returns the string
that identifies the plugin. Because we support multiple methods of authentication,
an authentication credential or an idspec will always be
prefixed with scheme:. The ZooKeeper server uses the scheme
returned by the authentication plugin to determine which ids the scheme
applies to.`},{heading:void 0,content:`handleAuthentication is called when a client
sends authentication information to be associated with a connection. The
client specifies the scheme to which the information corresponds. The
ZooKeeper server passes the information to the authentication plugin whose
getScheme matches the scheme passed by the client. The
implementor of handleAuthentication will usually return
an error if it determines that the information is bad, or it will associate information
with the connection using cnxn.getAuthInfo().add(new Id(getScheme(), data)).`},{heading:void 0,content:`The authentication plugin is involved in both setting and using ACLs. When an
ACL is set for a znode, the ZooKeeper server will pass the id part of the entry to
the isValid(String id) method. It is up to the plugin to verify
that the id has a correct form. For example, ip:172.16.0.0/16
is a valid id, but ip:host.com is not. If the new ACL includes
an "auth" entry, isAuthenticated is used to see if the
authentication information for this scheme that is associated with the connection
should be added to the ACL. Some schemes
should not be included in auth. For example, the IP address of the client is not
considered as an id that should be added to the ACL if auth is specified.`},{heading:void 0,content:`ZooKeeper invokes matches(String id, String aclExpr) when checking an ACL. It
needs to match authentication information of the client against the relevant ACL
entries. To find the entries which apply to the client, the ZooKeeper server will
find the scheme of each entry and if there is authentication information
from that client for that scheme, matches(String id, String aclExpr)
will be called with id set to the authentication information
that was previously added to the connection by handleAuthentication and
aclExpr set to the id of the ACL entry. The authentication plugin
uses its own logic and matching scheme to determine if id is included
in aclExpr.`},{heading:void 0,content:`There are two built in authentication plugins: ip and
digest. Additional plugins can adding using system properties. At
startup the ZooKeeper server will look for system properties that start with
"zookeeper.authProvider." and interpret the value of those properties as the class name
of an authentication plugin. These properties can be set using the
-Dzookeeper.authProvider.X=com.f.MyAuth or adding entries such as
the following in the server configuration file:`},{heading:void 0,content:`Care should be taking to ensure that the suffix on the property is unique. If there are
duplicates such as -Dzookeeper.authProvider.X=com.f.MyAuth -Dzookeeper.authProvider.X=com.f.MyAuth2,
only one will be used. Also all servers must have the same plugins defined, otherwise clients using
the authentication schemes provided by the plugins will have problems connecting to some servers.`},{heading:void 0,content:`Added in 3.6.0: An alternate abstraction is available for pluggable
authentication. It provides additional arguments.`},{heading:void 0,content:`Instead of implementing AuthenticationProvider you extend ServerAuthenticationProvider. Your handleAuthentication()
and matches() methods will then receive the additional parameters (via ServerObjs and MatchValues).`},{heading:void 0,content:`ZooKeeperServer
The ZooKeeperServer instance`},{heading:void 0,content:`ServerCnxn
The current connection`},{heading:void 0,content:`path
The ZNode path being operated on (or null if not used)`},{heading:void 0,content:`perm
The operation value or 0`},{heading:void 0,content:`setAcls
When the setAcl() method is being operated on, the list of ACLs that are being set`}],headings:[]};const l=[];function n(t){const i={code:"code",em:"em",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...t.components};return e.jsxs(e.Fragment,{children:[e.jsx(i.p,{children:`ZooKeeper runs in a variety of different environments with
various different authentication schemes, so it has a completely
pluggable authentication framework. Even the builtin authentication
schemes use the pluggable authentication framework.`}),`
`,e.jsxs(i.p,{children:[`To understand how the authentication framework works, first you must
understand the two main authentication operations. The framework
first must authenticate the client. This is usually done as soon as
the client connects to a server and consists of validating information
sent from or gathered about a client and associating it with the connection.
The second operation handled by the framework is finding the entries in an
ACL that correspond to client. ACL entries are `,e.jsx(i.code,{children:"<idspec, permissions>"})," pairs. The ",e.jsx(i.em,{children:"idspec"}),` may be
a simple string match against the authentication information associated
with the connection or it may be a expression that is evaluated against that
information. It is up to the implementation of the authentication plugin
to do the match. Here is the interface that an authentication plugin must
implement:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(i.code,{children:[e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"public"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" interface"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" AuthenticationProvider"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    String "}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"getScheme"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"();"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    KeeperException.Code "}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"handleAuthentication"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ServerCnxn "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"cnxn"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" authData"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[]);"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    boolean"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" isValid"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(String "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"id"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    boolean"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" matches"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(String "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"id"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", String "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"aclExpr"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    boolean"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" isAuthenticated"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"();"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})}),`
`,e.jsxs(i.p,{children:["The first method ",e.jsx(i.em,{children:"getScheme"}),` returns the string
that identifies the plugin. Because we support multiple methods of authentication,
an authentication credential or an `,e.jsx(i.em,{children:"idspec"}),` will always be
prefixed with `,e.jsx(i.em,{children:"scheme:"}),`. The ZooKeeper server uses the scheme
returned by the authentication plugin to determine which ids the scheme
applies to.`]}),`
`,e.jsxs(i.p,{children:[e.jsx(i.em,{children:"handleAuthentication"}),` is called when a client
sends authentication information to be associated with a connection. The
client specifies the scheme to which the information corresponds. The
ZooKeeper server passes the information to the authentication plugin whose
`,e.jsx(i.em,{children:"getScheme"}),` matches the scheme passed by the client. The
implementor of `,e.jsx(i.em,{children:"handleAuthentication"}),` will usually return
an error if it determines that the information is bad, or it will associate information
with the connection using `,e.jsx(i.em,{children:"cnxn.getAuthInfo().add(new Id(getScheme(), data))"}),"."]}),`
`,e.jsxs(i.p,{children:[`The authentication plugin is involved in both setting and using ACLs. When an
ACL is set for a znode, the ZooKeeper server will pass the id part of the entry to
the `,e.jsx(i.em,{children:"isValid(String id)"}),` method. It is up to the plugin to verify
that the id has a correct form. For example, `,e.jsx(i.em,{children:"ip:172.16.0.0/16"}),`
is a valid id, but `,e.jsx(i.em,{children:"ip:host.com"}),` is not. If the new ACL includes
an "auth" entry, `,e.jsx(i.em,{children:"isAuthenticated"}),` is used to see if the
authentication information for this scheme that is associated with the connection
should be added to the ACL. Some schemes
should not be included in auth. For example, the IP address of the client is not
considered as an id that should be added to the ACL if auth is specified.`]}),`
`,e.jsxs(i.p,{children:["ZooKeeper invokes ",e.jsx(i.em,{children:"matches(String id, String aclExpr)"}),` when checking an ACL. It
needs to match authentication information of the client against the relevant ACL
entries. To find the entries which apply to the client, the ZooKeeper server will
find the scheme of each entry and if there is authentication information
from that client for that scheme, `,e.jsx(i.em,{children:"matches(String id, String aclExpr)"}),`
will be called with `,e.jsx(i.em,{children:"id"}),` set to the authentication information
that was previously added to the connection by `,e.jsx(i.em,{children:"handleAuthentication"}),` and
`,e.jsx(i.em,{children:"aclExpr"}),` set to the id of the ACL entry. The authentication plugin
uses its own logic and matching scheme to determine if `,e.jsx(i.em,{children:"id"}),` is included
in `,e.jsx(i.em,{children:"aclExpr"}),"."]}),`
`,e.jsxs(i.p,{children:["There are two built in authentication plugins: ",e.jsx(i.em,{children:"ip"}),` and
`,e.jsx(i.em,{children:"digest"}),`. Additional plugins can adding using system properties. At
startup the ZooKeeper server will look for system properties that start with
"zookeeper.authProvider." and interpret the value of those properties as the class name
of an authentication plugin. These properties can be set using the
`,e.jsx(i.em,{children:"-Dzookeeper.authProvider.X=com.f.MyAuth"}),` or adding entries such as
the following in the server configuration file:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(i.code,{children:[e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"authProvider.1"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=com.f.MyAuth"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"authProvider.2"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"=com.f.MyAuth2"})]})]})})}),`
`,e.jsxs(i.p,{children:[`Care should be taking to ensure that the suffix on the property is unique. If there are
duplicates such as `,e.jsx(i.em,{children:"-Dzookeeper.authProvider.X=com.f.MyAuth -Dzookeeper.authProvider.X=com.f.MyAuth2"}),`,
only one will be used. Also all servers must have the same plugins defined, otherwise clients using
the authentication schemes provided by the plugins will have problems connecting to some servers.`]}),`
`,e.jsxs(i.p,{children:[e.jsx(i.strong,{children:"Added in 3.6.0"}),`: An alternate abstraction is available for pluggable
authentication. It provides additional arguments.`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsxs(i.code,{children:[e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"public"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" abstract"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" class"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" ServerAuthenticationProvider"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" implements"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" AuthenticationProvider"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    public"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" abstract"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" KeeperException.Code "}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"handleAuthentication"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ServerObjs "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"serverObjs"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"byte"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" authData"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"[]);"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    public"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" abstract"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" boolean"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" matches"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ServerObjs "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"serverObjs"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", MatchValues "}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"matchValues"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})}),`
`,e.jsx(i.p,{children:`Instead of implementing AuthenticationProvider you extend ServerAuthenticationProvider. Your handleAuthentication()
and matches() methods will then receive the additional parameters (via ServerObjs and MatchValues).`}),`
`,e.jsxs(i.ul,{children:[`
`,e.jsxs(i.li,{children:[e.jsx(i.strong,{children:"ZooKeeperServer"}),`
The ZooKeeperServer instance`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.strong,{children:"ServerCnxn"}),`
The current connection`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.strong,{children:"path"}),`
The ZNode path being operated on (or null if not used)`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.strong,{children:"perm"}),`
The operation value or 0`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.strong,{children:"setAcls"}),`
When the setAcl() method is being operated on, the list of ACLs that are being set`]}),`
`]})]})}function d(t={}){const{wrapper:i}=t.components||{};return i?e.jsx(i,{...t,children:e.jsx(n,{...t})}):n(t)}export{a as _markdown,d as default,r as extractedReferences,h as frontmatter,o as structuredData,l as toc};
