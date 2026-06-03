import{j as e}from"./chunk-EPOLDU6W-BACfhBcx.js";let r=`ZooKeeper uses ACLs to control access to its znodes (the
data nodes of a ZooKeeper data tree). The ACL implementation is
quite similar to UNIX file access permissions: it employs
permission bits to allow/disallow various operations against a
node and the scope to which the bits apply. Unlike standard UNIX
permissions, a ZooKeeper node is not limited by the three standard
scopes for user (owner of the file), group, and world
(other). ZooKeeper does not have a notion of an owner of a
znode. Instead, an ACL specifies sets of ids and permissions that
are associated with those ids.

Note also that an ACL pertains only to a specific znode. In
particular it does not apply to children. For example, if
*/app* is only readable by ip:172.16.16.1 and
*/app/status* is world readable, anyone will
be able to read */app/status*; ACLs are not
recursive.

ZooKeeper supports pluggable authentication schemes. Ids are
specified using the form *scheme:expression*,
where *scheme* is the authentication scheme
that the id corresponds to. The set of valid expressions are defined
by the scheme. For example, *ip:172.16.16.1* is
an id for a host with the address *172.16.16.1*
using the *ip* scheme, whereas *digest:bob:password*
is an id for the user with the name of *bob* using
the *digest* scheme.

When a client connects to ZooKeeper and authenticates
itself, ZooKeeper associates all the ids that correspond to a
client with the clients connection. These ids are checked against
the ACLs of znodes when a client tries to access a node. ACLs are
made up of pairs of *(scheme:expression,
perms)*. The format of
the *expression* is specific to the scheme. For
example, the pair *(ip:19.22.0.0/16, READ)*
gives the *READ* permission to any clients with
an IP address that starts with 19.22.

## ACL Permissions

ZooKeeper supports the following permissions:

* **CREATE**: you can create a child node
* **READ**: you can get data from a node and list its children.
* **WRITE**: you can set data for a node
* **DELETE**: you can delete a child node
* **ADMIN**: you can set permissions

The *CREATE*
and *DELETE* permissions have been broken out
of the *WRITE* permission for finer grained
access controls. The cases for *CREATE*
and *DELETE* are the following:

You want A to be able to do a set on a ZooKeeper node, but
not be able to *CREATE*
or *DELETE* children.

*CREATE*
without *DELETE*: clients create requests by
creating ZooKeeper nodes in a parent directory. You want all
clients to be able to add, but only request processor can
delete. (This is kind of like the APPEND permission for
files.)

Also, the *ADMIN* permission is there
since ZooKeeper doesn’t have a notion of file owner. In some
sense the *ADMIN* permission designates the
entity as the owner. ZooKeeper doesn’t support the LOOKUP
permission (execute permission bit on directories to allow you
to LOOKUP even though you can't list the directory). Everyone
implicitly has LOOKUP permission. This allows you to stat a
node, but nothing more. (The problem is, if you want to call
zoo\\_exists() on a node that doesn't exist, there is no
permission to check.)

*ADMIN* permission also has a special role in terms of ACLs:
in order to retrieve ACLs of a znode user has to have *READ* or *ADMIN*
permission, but without *ADMIN* permission, digest hash values will be
masked out.

As of versions **3.9.2 / 3.8.4 / 3.7.3** the exists() call will now verify ACLs
on nodes that exist and the client must have READ permission otherwise
'Insufficient permission' error will be raised.

### Builtin ACL Schemes

ZooKeeper has the following built in schemes:

* **world** has a
  single id, *anyone*, that represents
  anyone.
* **auth** is a special
  scheme which ignores any provided expression and instead uses the current user,
  credentials, and scheme. Any expression (whether *user* like with SASL
  authentication or *user:password* like with DIGEST authentication) provided is ignored
  by the ZooKeeper server when persisting the ACL. However, the expression must still be
  provided in the ACL because the ACL must match the form *scheme:expression:perms*.
  This scheme is provided as a convenience as it is a common use-case for
  a user to create a znode and then restrict access to that znode to only that user.
  If there is no authenticated user, setting an ACL with the auth scheme will fail.
* **digest** uses
  a *username:password* string to generate
  MD5 hash which is then used as an ACL ID
  identity. Authentication is done by sending
  the *username:password* in clear text. When
  used in the ACL the expression will be
  the *username:base64*
  encoded *SHA1*
  password *digest*.
* **ip** uses the
  client host IP as an ACL ID identity. The ACL expression is of
  the form *addr/bits* where the most
  significant *bits*
  of *addr* are matched against the most
  significant *bits* of the client host
  IP.
* **x509** uses the client
  X500 Principal as an ACL ID identity. The ACL expression is the exact
  X500 Principal name of a client. When using the secure port, clients
  are automatically authenticated and their auth info for the x509 scheme
  is set.

### ZooKeeper C client API

The following constants are provided by the ZooKeeper C
library:

* *const* *int* ZOO\\_PERM\\_READ; //can read node’s value and list its children
* *const* *int* ZOO\\_PERM\\_WRITE;// can set the node’s value
* *const* *int* ZOO\\_PERM\\_CREATE; //can create children
* *const* *int* ZOO\\_PERM\\_DELETE;// can delete children
* *const* *int* ZOO\\_PERM\\_ADMIN; //can execute set\\_acl()
* *const* *int* ZOO\\_PERM\\_ALL;// all of the above flags OR’d together

The following are the standard ACL IDs:

* *struct* Id ZOO\\_ANYONE\\_ID\\_UNSAFE; //(‘world’,’anyone’)
* *struct* Id ZOO\\_AUTH\\_IDS;// (‘auth’,’’)

ZOO\\_AUTH\\_IDS empty identity string should be interpreted as “the identity of the creator”.

ZooKeeper client comes with three standard ACLs:

* *struct* ACL\\_vector ZOO\\_OPEN\\_ACL\\_UNSAFE; //(ZOO\\_PERM\\_ALL,ZOO\\_ANYONE\\_ID\\_UNSAFE)
* *struct* ACL\\_vector ZOO\\_READ\\_ACL\\_UNSAFE;// (ZOO\\_PERM\\_READ, ZOO\\_ANYONE\\_ID\\_UNSAFE)
* *struct* ACL\\_vector ZOO\\_CREATOR\\_ALL\\_ACL; //(ZOO\\_PERM\\_ALL,ZOO\\_AUTH\\_IDS)

The ZOO*OPEN\\_ACL\\_UNSAFE is completely open free for all
ACL: any application can execute any operation on the node and
can create, list and delete its children. The
ZOO\\_READ\\_ACL\\_UNSAFE is read-only access for any
application. CREATE\\_ALL\\_ACL grants all permissions to the
creator of the node. The creator must have been authenticated by
the server (for example, using “\\_digest*”
scheme) before it can create nodes with this ACL.

The following ZooKeeper operations deal with ACLs:

* *int* *zoo\\_add\\_auth*
  (zhandle*t \\*zh,\\_const* *char\\_\\_
  scheme,*const* *char**
  cert, *int* certLen, void*completion\\_t
  completion, \\_const* *void*
  \\*data);

  The application uses the zoo\\_add\\_auth function to
  authenticate itself to the server. The function can be called
  multiple times if the application wants to authenticate using
  different schemes and/or identities.

* *int* *zoo\\_create*
  (zhandle*t \\*zh, \\_const* *char*
  \\*path, *const* *char*
  \\*value,*int*
  valuelen, *const* *struct*
  ACL*vector \\*acl, \\_int*
  flags,*char*
  \\*realpath, *int*
  max\\_realpath\\_len);

  zoo\\_create(...) operation creates a new node. The acl
  parameter is a list of ACLs associated with the node. The parent
  node must have the CREATE permission bit set.

* *int* *zoo\\_get\\_acl*
  (zhandle*t \\*zh, \\_const* *char*
  \\*path,*struct* ACL*vector
  \\*acl, \\_struct* Stat \\*stat);

  This operation returns a node’s ACL info. The node must have READ or ADMIN
  permission set. Without ADMIN permission, the digest hash values will be masked out.

* *int* *zoo\\_set\\_acl*
  (zhandle*t \\*zh, \\_const* *char*
  \\*path, *int*
  version,*const* *struct*
  ACL\\_vector \\*acl);

  This function replaces node’s ACL list with a new one. The
  node must have the ADMIN permission set.

Here is a sample code that makes use of the above APIs to
authenticate itself using the “*foo*” scheme
and create an ephemeral node “/xyz” with create-only
permissions.

<Callout type="info">
  This is a very simple example which is intended to show how to interact with
  ZooKeeper ACLs specifically. See
  *.../trunk/zookeeper-client/zookeeper-client-c/src/cli.c* for an example of a
  C client implementation
</Callout>

\`\`\`c
#include <string.h>
#include <errno.h>

#include "zookeeper.h"

static zhandle_t *zh;

/**
 * In this example this method gets the cert for your
 *   environment -- you must provide
 */
char *foo_get_cert_once(char* id) { return 0; }

/** Watcher function -- empty for this example, not something you should
 * do in real code */
void watcher(zhandle_t *zzh, int type, int state, const char *path,
         void *watcherCtx) {}

int main(int argc, char argv) {
  char buffer[512];
  char p[2048];
  char *cert=0;
  char appId[64];

  strcpy(appId, "example.foo_test");
  cert = foo_get_cert_once(appId);
  if(cert!=0) {
    fprintf(stderr,
        "Certificate for appid [%s] is [%s]\\n",appId,cert);
    strncpy(p,cert, sizeof(p)-1);
    free(cert);
  } else {
    fprintf(stderr, "Certificate for appid [%s] not found\\n",appId);
    strcpy(p, "dummy");
  }

  zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);

  zh = zookeeper_init("localhost:3181", watcher, 10000, 0, 0, 0);
  if (!zh) {
    return errno;
  }
  if(zoo_add_auth(zh,"foo",p,strlen(p),0,0)!=ZOK)
    return 2;

  struct ACL CREATE_ONLY_ACL[] = {{ZOO_PERM_CREATE, ZOO_AUTH_IDS}};
  struct ACL_vector CREATE_ONLY = {1, CREATE_ONLY_ACL};
  int rc = zoo_create(zh,"/xyz","value", 5, &CREATE_ONLY, ZOO_EPHEMERAL,
                  buffer, sizeof(buffer)-1);

  /** this operation will fail with a ZNOAUTH error */
  int buflen= sizeof(buffer);
  struct Stat stat;
  rc = zoo_get(zh, "/xyz", 0, buffer, &buflen, &stat);
  if (rc) {
    fprintf(stderr, "Error %d for %s\\n", rc, __LINE__);
  }

  zookeeper_close(zh);
  return 0;
}
\`\`\`
`,l={title:"Access Control using ACLs",description:"Explains ZooKeeper's ACL-based access control for znodes, including permission types, built-in ACL schemes (world, auth, digest, host, IP), and the C client API."},o=[],c={contents:[{heading:void 0,content:`ZooKeeper uses ACLs to control access to its znodes (the
data nodes of a ZooKeeper data tree). The ACL implementation is
quite similar to UNIX file access permissions: it employs
permission bits to allow/disallow various operations against a
node and the scope to which the bits apply. Unlike standard UNIX
permissions, a ZooKeeper node is not limited by the three standard
scopes for user (owner of the file), group, and world
(other). ZooKeeper does not have a notion of an owner of a
znode. Instead, an ACL specifies sets of ids and permissions that
are associated with those ids.`},{heading:void 0,content:`Note also that an ACL pertains only to a specific znode. In
particular it does not apply to children. For example, if
/app is only readable by ip:172.16.16.1 and
/app/status is world readable, anyone will
be able to read /app/status; ACLs are not
recursive.`},{heading:void 0,content:`ZooKeeper supports pluggable authentication schemes. Ids are
specified using the form scheme:expression,
where scheme is the authentication scheme
that the id corresponds to. The set of valid expressions are defined
by the scheme. For example, ip:172.16.16.1 is
an id for a host with the address 172.16.16.1
using the ip scheme, whereas digest:bob:password
is an id for the user with the name of bob using
the digest scheme.`},{heading:void 0,content:`When a client connects to ZooKeeper and authenticates
itself, ZooKeeper associates all the ids that correspond to a
client with the clients connection. These ids are checked against
the ACLs of znodes when a client tries to access a node. ACLs are
made up of pairs of (scheme:expression,
perms). The format of
the expression is specific to the scheme. For
example, the pair (ip:19.22.0.0/16, READ)
gives the READ permission to any clients with
an IP address that starts with 19.22.`},{heading:"acl-permissions",content:"ZooKeeper supports the following permissions:"},{heading:"acl-permissions",content:"CREATE: you can create a child node"},{heading:"acl-permissions",content:"READ: you can get data from a node and list its children."},{heading:"acl-permissions",content:"WRITE: you can set data for a node"},{heading:"acl-permissions",content:"DELETE: you can delete a child node"},{heading:"acl-permissions",content:"ADMIN: you can set permissions"},{heading:"acl-permissions",content:`The CREATE
and DELETE permissions have been broken out
of the WRITE permission for finer grained
access controls. The cases for CREATE
and DELETE are the following:`},{heading:"acl-permissions",content:`You want A to be able to do a set on a ZooKeeper node, but
not be able to CREATE
or DELETE children.`},{heading:"acl-permissions",content:`CREATE
without DELETE: clients create requests by
creating ZooKeeper nodes in a parent directory. You want all
clients to be able to add, but only request processor can
delete. (This is kind of like the APPEND permission for
files.)`},{heading:"acl-permissions",content:`Also, the ADMIN permission is there
since ZooKeeper doesn’t have a notion of file owner. In some
sense the ADMIN permission designates the
entity as the owner. ZooKeeper doesn’t support the LOOKUP
permission (execute permission bit on directories to allow you
to LOOKUP even though you can't list the directory). Everyone
implicitly has LOOKUP permission. This allows you to stat a
node, but nothing more. (The problem is, if you want to call
zoo_exists() on a node that doesn't exist, there is no
permission to check.)`},{heading:"acl-permissions",content:`ADMIN permission also has a special role in terms of ACLs:
in order to retrieve ACLs of a znode user has to have READ or ADMIN
permission, but without ADMIN permission, digest hash values will be
masked out.`},{heading:"acl-permissions",content:`As of versions 3.9.2 / 3.8.4 / 3.7.3 the exists() call will now verify ACLs
on nodes that exist and the client must have READ permission otherwise
'Insufficient permission' error will be raised.`},{heading:"builtin-acl-schemes",content:"ZooKeeper has the following built in schemes:"},{heading:"builtin-acl-schemes",content:`world has a
single id, anyone, that represents
anyone.`},{heading:"builtin-acl-schemes",content:`auth is a special
scheme which ignores any provided expression and instead uses the current user,
credentials, and scheme. Any expression (whether user like with SASL
authentication or user:password like with DIGEST authentication) provided is ignored
by the ZooKeeper server when persisting the ACL. However, the expression must still be
provided in the ACL because the ACL must match the form scheme:expression:perms.
This scheme is provided as a convenience as it is a common use-case for
a user to create a znode and then restrict access to that znode to only that user.
If there is no authenticated user, setting an ACL with the auth scheme will fail.`},{heading:"builtin-acl-schemes",content:`digest uses
a username:password string to generate
MD5 hash which is then used as an ACL ID
identity. Authentication is done by sending
the username:password in clear text. When
used in the ACL the expression will be
the username:base64
encoded SHA1
password digest.`},{heading:"builtin-acl-schemes",content:`ip uses the
client host IP as an ACL ID identity. The ACL expression is of
the form addr/bits where the most
significant bits
of addr are matched against the most
significant bits of the client host
IP.`},{heading:"builtin-acl-schemes",content:`x509 uses the client
X500 Principal as an ACL ID identity. The ACL expression is the exact
X500 Principal name of a client. When using the secure port, clients
are automatically authenticated and their auth info for the x509 scheme
is set.`},{heading:"zookeeper-c-client-api",content:`The following constants are provided by the ZooKeeper C
library:`},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_READ; //can read node’s value and list its children"},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_WRITE;// can set the node’s value"},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_CREATE; //can create children"},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_DELETE;// can delete children"},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_ADMIN; //can execute set_acl()"},{heading:"zookeeper-c-client-api",content:"const int ZOO_PERM_ALL;// all of the above flags OR’d together"},{heading:"zookeeper-c-client-api",content:"The following are the standard ACL IDs:"},{heading:"zookeeper-c-client-api",content:"struct Id ZOO_ANYONE_ID_UNSAFE; //(‘world’,’anyone’)"},{heading:"zookeeper-c-client-api",content:"struct Id ZOO_AUTH_IDS;// (‘auth’,’’)"},{heading:"zookeeper-c-client-api",content:"ZOO_AUTH_IDS empty identity string should be interpreted as “the identity of the creator”."},{heading:"zookeeper-c-client-api",content:"ZooKeeper client comes with three standard ACLs:"},{heading:"zookeeper-c-client-api",content:"struct ACL_vector ZOO_OPEN_ACL_UNSAFE; //(ZOO_PERM_ALL,ZOO_ANYONE_ID_UNSAFE)"},{heading:"zookeeper-c-client-api",content:"struct ACL_vector ZOO_READ_ACL_UNSAFE;// (ZOO_PERM_READ, ZOO_ANYONE_ID_UNSAFE)"},{heading:"zookeeper-c-client-api",content:"struct ACL_vector ZOO_CREATOR_ALL_ACL; //(ZOO_PERM_ALL,ZOO_AUTH_IDS)"},{heading:"zookeeper-c-client-api",content:`The ZOOOPEN_ACL_UNSAFE is completely open free for all
ACL: any application can execute any operation on the node and
can create, list and delete its children. The
ZOO_READ_ACL_UNSAFE is read-only access for any
application. CREATE_ALL_ACL grants all permissions to the
creator of the node. The creator must have been authenticated by
the server (for example, using “_digest”
scheme) before it can create nodes with this ACL.`},{heading:"zookeeper-c-client-api",content:"The following ZooKeeper operations deal with ACLs:"},{heading:"zookeeper-c-client-api",content:`int zoo_add_auth
(zhandlet *zh,_const char__
scheme,const char
cert, int certLen, voidcompletion_t
completion, _const void
*data);`},{heading:"zookeeper-c-client-api",content:`The application uses the zoo_add_auth function to
authenticate itself to the server. The function can be called
multiple times if the application wants to authenticate using
different schemes and/or identities.`},{heading:"zookeeper-c-client-api",content:`int zoo_create
(zhandlet *zh, _const char
*path, const char
*value,int
valuelen, const struct
ACLvector *acl, _int
flags,char
*realpath, int
max_realpath_len);`},{heading:"zookeeper-c-client-api",content:`zoo_create(...) operation creates a new node. The acl
parameter is a list of ACLs associated with the node. The parent
node must have the CREATE permission bit set.`},{heading:"zookeeper-c-client-api",content:`int zoo_get_acl
(zhandlet *zh, _const char
*path,struct ACLvector
*acl, _struct Stat *stat);`},{heading:"zookeeper-c-client-api",content:`This operation returns a node’s ACL info. The node must have READ or ADMIN
permission set. Without ADMIN permission, the digest hash values will be masked out.`},{heading:"zookeeper-c-client-api",content:`int zoo_set_acl
(zhandlet *zh, _const char
*path, int
version,const struct
ACL_vector *acl);`},{heading:"zookeeper-c-client-api",content:`This function replaces node’s ACL list with a new one. The
node must have the ADMIN permission set.`},{heading:"zookeeper-c-client-api",content:`Here is a sample code that makes use of the above APIs to
authenticate itself using the “foo” scheme
and create an ephemeral node “/xyz” with create-only
permissions.`},{heading:"zookeeper-c-client-api",content:"type: info"},{heading:"zookeeper-c-client-api",content:`This is a very simple example which is intended to show how to interact with
ZooKeeper ACLs specifically. See
.../trunk/zookeeper-client/zookeeper-client-c/src/cli.c for an example of a
C client implementation`}],headings:[{id:"acl-permissions",content:"ACL Permissions"},{id:"builtin-acl-schemes",content:"Builtin ACL Schemes"},{id:"zookeeper-c-client-api",content:"ZooKeeper C client API"}]};const d=[{depth:2,url:"#acl-permissions",title:e.jsx(e.Fragment,{children:"ACL Permissions"})},{depth:3,url:"#builtin-acl-schemes",title:e.jsx(e.Fragment,{children:"Builtin ACL Schemes"})},{depth:3,url:"#zookeeper-c-client-api",title:e.jsx(e.Fragment,{children:"ZooKeeper C client API"})}];function t(i){const s={code:"code",em:"em",h2:"h2",h3:"h3",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...i.components},{Callout:n}=s;return n||h("Callout"),e.jsxs(e.Fragment,{children:[e.jsx(s.p,{children:`ZooKeeper uses ACLs to control access to its znodes (the
data nodes of a ZooKeeper data tree). The ACL implementation is
quite similar to UNIX file access permissions: it employs
permission bits to allow/disallow various operations against a
node and the scope to which the bits apply. Unlike standard UNIX
permissions, a ZooKeeper node is not limited by the three standard
scopes for user (owner of the file), group, and world
(other). ZooKeeper does not have a notion of an owner of a
znode. Instead, an ACL specifies sets of ids and permissions that
are associated with those ids.`}),`
`,e.jsxs(s.p,{children:[`Note also that an ACL pertains only to a specific znode. In
particular it does not apply to children. For example, if
`,e.jsx(s.em,{children:"/app"}),` is only readable by ip:172.16.16.1 and
`,e.jsx(s.em,{children:"/app/status"}),` is world readable, anyone will
be able to read `,e.jsx(s.em,{children:"/app/status"}),`; ACLs are not
recursive.`]}),`
`,e.jsxs(s.p,{children:[`ZooKeeper supports pluggable authentication schemes. Ids are
specified using the form `,e.jsx(s.em,{children:"scheme:expression"}),`,
where `,e.jsx(s.em,{children:"scheme"}),` is the authentication scheme
that the id corresponds to. The set of valid expressions are defined
by the scheme. For example, `,e.jsx(s.em,{children:"ip:172.16.16.1"}),` is
an id for a host with the address `,e.jsx(s.em,{children:"172.16.16.1"}),`
using the `,e.jsx(s.em,{children:"ip"})," scheme, whereas ",e.jsx(s.em,{children:"digest:bob:password"}),`
is an id for the user with the name of `,e.jsx(s.em,{children:"bob"}),` using
the `,e.jsx(s.em,{children:"digest"})," scheme."]}),`
`,e.jsxs(s.p,{children:[`When a client connects to ZooKeeper and authenticates
itself, ZooKeeper associates all the ids that correspond to a
client with the clients connection. These ids are checked against
the ACLs of znodes when a client tries to access a node. ACLs are
made up of pairs of `,e.jsx(s.em,{children:`(scheme:expression,
perms)`}),`. The format of
the `,e.jsx(s.em,{children:"expression"}),` is specific to the scheme. For
example, the pair `,e.jsx(s.em,{children:"(ip:19.22.0.0/16, READ)"}),`
gives the `,e.jsx(s.em,{children:"READ"}),` permission to any clients with
an IP address that starts with 19.22.`]}),`
`,e.jsx(s.h2,{id:"acl-permissions",children:"ACL Permissions"}),`
`,e.jsx(s.p,{children:"ZooKeeper supports the following permissions:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"CREATE"}),": you can create a child node"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"READ"}),": you can get data from a node and list its children."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"WRITE"}),": you can set data for a node"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"DELETE"}),": you can delete a child node"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"ADMIN"}),": you can set permissions"]}),`
`]}),`
`,e.jsxs(s.p,{children:["The ",e.jsx(s.em,{children:"CREATE"}),`
and `,e.jsx(s.em,{children:"DELETE"}),` permissions have been broken out
of the `,e.jsx(s.em,{children:"WRITE"}),` permission for finer grained
access controls. The cases for `,e.jsx(s.em,{children:"CREATE"}),`
and `,e.jsx(s.em,{children:"DELETE"})," are the following:"]}),`
`,e.jsxs(s.p,{children:[`You want A to be able to do a set on a ZooKeeper node, but
not be able to `,e.jsx(s.em,{children:"CREATE"}),`
or `,e.jsx(s.em,{children:"DELETE"})," children."]}),`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"CREATE"}),`
without `,e.jsx(s.em,{children:"DELETE"}),`: clients create requests by
creating ZooKeeper nodes in a parent directory. You want all
clients to be able to add, but only request processor can
delete. (This is kind of like the APPEND permission for
files.)`]}),`
`,e.jsxs(s.p,{children:["Also, the ",e.jsx(s.em,{children:"ADMIN"}),` permission is there
since ZooKeeper doesn’t have a notion of file owner. In some
sense the `,e.jsx(s.em,{children:"ADMIN"}),` permission designates the
entity as the owner. ZooKeeper doesn’t support the LOOKUP
permission (execute permission bit on directories to allow you
to LOOKUP even though you can't list the directory). Everyone
implicitly has LOOKUP permission. This allows you to stat a
node, but nothing more. (The problem is, if you want to call
zoo_exists() on a node that doesn't exist, there is no
permission to check.)`]}),`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"ADMIN"}),` permission also has a special role in terms of ACLs:
in order to retrieve ACLs of a znode user has to have `,e.jsx(s.em,{children:"READ"})," or ",e.jsx(s.em,{children:"ADMIN"}),`
permission, but without `,e.jsx(s.em,{children:"ADMIN"}),` permission, digest hash values will be
masked out.`]}),`
`,e.jsxs(s.p,{children:["As of versions ",e.jsx(s.strong,{children:"3.9.2 / 3.8.4 / 3.7.3"}),` the exists() call will now verify ACLs
on nodes that exist and the client must have READ permission otherwise
'Insufficient permission' error will be raised.`]}),`
`,e.jsx(s.h3,{id:"builtin-acl-schemes",children:"Builtin ACL Schemes"}),`
`,e.jsx(s.p,{children:"ZooKeeper has the following built in schemes:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"world"}),` has a
single id, `,e.jsx(s.em,{children:"anyone"}),`, that represents
anyone.`]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"auth"}),` is a special
scheme which ignores any provided expression and instead uses the current user,
credentials, and scheme. Any expression (whether `,e.jsx(s.em,{children:"user"}),` like with SASL
authentication or `,e.jsx(s.em,{children:"user:password"}),` like with DIGEST authentication) provided is ignored
by the ZooKeeper server when persisting the ACL. However, the expression must still be
provided in the ACL because the ACL must match the form `,e.jsx(s.em,{children:"scheme:expression:perms"}),`.
This scheme is provided as a convenience as it is a common use-case for
a user to create a znode and then restrict access to that znode to only that user.
If there is no authenticated user, setting an ACL with the auth scheme will fail.`]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"digest"}),` uses
a `,e.jsx(s.em,{children:"username:password"}),` string to generate
MD5 hash which is then used as an ACL ID
identity. Authentication is done by sending
the `,e.jsx(s.em,{children:"username:password"}),` in clear text. When
used in the ACL the expression will be
the `,e.jsx(s.em,{children:"username:base64"}),`
encoded `,e.jsx(s.em,{children:"SHA1"}),`
password `,e.jsx(s.em,{children:"digest"}),"."]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"ip"}),` uses the
client host IP as an ACL ID identity. The ACL expression is of
the form `,e.jsx(s.em,{children:"addr/bits"}),` where the most
significant `,e.jsx(s.em,{children:"bits"}),`
of `,e.jsx(s.em,{children:"addr"}),` are matched against the most
significant `,e.jsx(s.em,{children:"bits"}),` of the client host
IP.`]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.strong,{children:"x509"}),` uses the client
X500 Principal as an ACL ID identity. The ACL expression is the exact
X500 Principal name of a client. When using the secure port, clients
are automatically authenticated and their auth info for the x509 scheme
is set.`]}),`
`]}),`
`,e.jsx(s.h3,{id:"zookeeper-c-client-api",children:"ZooKeeper C client API"}),`
`,e.jsx(s.p,{children:`The following constants are provided by the ZooKeeper C
library:`}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_READ; //can read node’s value and list its children"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_WRITE;// can set the node’s value"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_CREATE; //can create children"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_DELETE;// can delete children"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_ADMIN; //can execute set_acl()"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"int"})," ZOO_PERM_ALL;// all of the above flags OR’d together"]}),`
`]}),`
`,e.jsx(s.p,{children:"The following are the standard ACL IDs:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"struct"})," Id ZOO_ANYONE_ID_UNSAFE; //(‘world’,’anyone’)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"struct"})," Id ZOO_AUTH_IDS;// (‘auth’,’’)"]}),`
`]}),`
`,e.jsx(s.p,{children:"ZOO_AUTH_IDS empty identity string should be interpreted as “the identity of the creator”."}),`
`,e.jsx(s.p,{children:"ZooKeeper client comes with three standard ACLs:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"struct"})," ACL_vector ZOO_OPEN_ACL_UNSAFE; //(ZOO_PERM_ALL,ZOO_ANYONE_ID_UNSAFE)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"struct"})," ACL_vector ZOO_READ_ACL_UNSAFE;// (ZOO_PERM_READ, ZOO_ANYONE_ID_UNSAFE)"]}),`
`,e.jsxs(s.li,{children:[e.jsx(s.em,{children:"struct"})," ACL_vector ZOO_CREATOR_ALL_ACL; //(ZOO_PERM_ALL,ZOO_AUTH_IDS)"]}),`
`]}),`
`,e.jsxs(s.p,{children:["The ZOO",e.jsx(s.em,{children:`OPEN_ACL_UNSAFE is completely open free for all
ACL: any application can execute any operation on the node and
can create, list and delete its children. The
ZOO_READ_ACL_UNSAFE is read-only access for any
application. CREATE_ALL_ACL grants all permissions to the
creator of the node. The creator must have been authenticated by
the server (for example, using “_digest`}),`”
scheme) before it can create nodes with this ACL.`]}),`
`,e.jsx(s.p,{children:"The following ZooKeeper operations deal with ACLs:"}),`
`,e.jsxs(s.ul,{children:[`
`,e.jsxs(s.li,{children:[`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"int"})," ",e.jsx(s.em,{children:"zoo_add_auth"}),`
(zhandle`,e.jsx(s.em,{children:"t *zh,_const"})," ",e.jsxs(s.em,{children:[`char__
scheme,`,e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"char"})]}),`
cert, `,e.jsx(s.em,{children:"int"})," certLen, void",e.jsx(s.em,{children:`completion_t
completion, _const`})," ",e.jsx(s.em,{children:"void"}),`
*data);`]}),`
`,e.jsx(s.p,{children:`The application uses the zoo_add_auth function to
authenticate itself to the server. The function can be called
multiple times if the application wants to authenticate using
different schemes and/or identities.`}),`
`]}),`
`,e.jsxs(s.li,{children:[`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"int"})," ",e.jsx(s.em,{children:"zoo_create"}),`
(zhandle`,e.jsx(s.em,{children:"t *zh, _const"})," ",e.jsx(s.em,{children:"char"}),`
*path, `,e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"char"}),`
*value,`,e.jsx(s.em,{children:"int"}),`
valuelen, `,e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"struct"}),`
ACL`,e.jsx(s.em,{children:"vector *acl, _int"}),`
flags,`,e.jsx(s.em,{children:"char"}),`
*realpath, `,e.jsx(s.em,{children:"int"}),`
max_realpath_len);`]}),`
`,e.jsx(s.p,{children:`zoo_create(...) operation creates a new node. The acl
parameter is a list of ACLs associated with the node. The parent
node must have the CREATE permission bit set.`}),`
`]}),`
`,e.jsxs(s.li,{children:[`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"int"})," ",e.jsx(s.em,{children:"zoo_get_acl"}),`
(zhandle`,e.jsx(s.em,{children:"t *zh, _const"})," ",e.jsx(s.em,{children:"char"}),`
*path,`,e.jsx(s.em,{children:"struct"})," ACL",e.jsx(s.em,{children:`vector
*acl, _struct`})," Stat *stat);"]}),`
`,e.jsx(s.p,{children:`This operation returns a node’s ACL info. The node must have READ or ADMIN
permission set. Without ADMIN permission, the digest hash values will be masked out.`}),`
`]}),`
`,e.jsxs(s.li,{children:[`
`,e.jsxs(s.p,{children:[e.jsx(s.em,{children:"int"})," ",e.jsx(s.em,{children:"zoo_set_acl"}),`
(zhandle`,e.jsx(s.em,{children:"t *zh, _const"})," ",e.jsx(s.em,{children:"char"}),`
*path, `,e.jsx(s.em,{children:"int"}),`
version,`,e.jsx(s.em,{children:"const"})," ",e.jsx(s.em,{children:"struct"}),`
ACL_vector *acl);`]}),`
`,e.jsx(s.p,{children:`This function replaces node’s ACL list with a new one. The
node must have the ADMIN permission set.`}),`
`]}),`
`]}),`
`,e.jsxs(s.p,{children:[`Here is a sample code that makes use of the above APIs to
authenticate itself using the “`,e.jsx(s.em,{children:"foo"}),`” scheme
and create an ephemeral node “/xyz” with create-only
permissions.`]}),`
`,e.jsx(n,{type:"info",children:e.jsxs(s.p,{children:[`This is a very simple example which is intended to show how to interact with
ZooKeeper ACLs specifically. See
`,e.jsx(s.em,{children:".../trunk/zookeeper-client/zookeeper-client-c/src/cli.c"}),` for an example of a
C client implementation`]})}),`
`,e.jsx(e.Fragment,{children:e.jsx(s.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M16.5921 9.1962s-.354-3.298-3.627-3.39c-3.2741-.09-4.9552 2.474-4.9552 6.14 0 3.6651 1.858 6.5972 5.0451 6.5972 3.184 0 3.5381-3.665 3.5381-3.665l6.1041.365s.36 3.31-2.196 5.836c-2.552 2.5241-5.6901 2.9371-7.8762 2.9201-2.19-.017-5.2261.034-8.1602-2.97-2.938-3.0101-3.436-5.9302-3.436-8.8002 0-2.8701.556-6.6702 4.047-9.5502C7.444.72 9.849 0 12.254 0c10.0422 0 10.7172 9.2602 10.7172 9.2602z" fill="currentColor" /></svg>',children:e.jsxs(s.code,{children:[e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" <string.h>"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" <errno.h>"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:' "zookeeper.h"'})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"static"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" zhandle_t"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zh;"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"/**"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" * In this example this method gets the cert for your"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" *   environment -- you must provide"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" */"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"char"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"foo_get_cert_once"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"char*"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" id"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") { "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"return"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"; }"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"/** Watcher function -- empty for this example, not something you should"})}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:" * do in real code */"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"void"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" watcher"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"zhandle_t"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"zzh"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" type"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" state"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"const"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" char"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"path"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"         void"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"watcherCtx"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {}"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" main"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" argc"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"char"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" argv"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  char"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" buffer"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"["}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"512"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"];"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  char"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" p"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"["}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"2048"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"];"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  char"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"cert"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  char"}),e.jsx(s.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" appId"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"["}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"64"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"];"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  strcpy"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(appId, "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"example.foo_test"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  cert "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" foo_get_cert_once"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(appId);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  if"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(cert"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!="}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:") {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    fprintf"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(stderr,"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'        "Certificate for appid ['}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"%s"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"] is ["}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"%s"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"]"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\n"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:",appId,cert);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    strncpy"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(p,cert, "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"sizeof"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(p)"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"-"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    free"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(cert);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  } "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    fprintf"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(stderr, "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Certificate for appid ['}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"%s"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:"] not found"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\n"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:",appId);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    strcpy"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(p, "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"dummy"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  }"})}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  zoo_set_debug_level"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZOO_LOG_LEVEL_DEBUG);"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  zh "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" zookeeper_init"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"localhost:3181"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", watcher, "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"10000"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  if"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ("}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zh) {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    return"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" errno;"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  }"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  if"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"zoo_add_auth"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zh,"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"foo"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:",p,"}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"strlen"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(p),"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:")"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!="}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"ZOK)"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    return"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 2"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  struct"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ACL CREATE_ONLY_ACL"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"[]"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" ="}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {{ZOO_PERM_CREATE, ZOO_AUTH_IDS}};"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  struct"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ACL_vector CREATE_ONLY "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" {"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", CREATE_ONLY_ACL};"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  int"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" rc "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" zoo_create"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zh,"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"/xyz"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:","}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"value"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"5"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"&"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"CREATE_ONLY, ZOO_EPHEMERAL,"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"                  buffer, "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"sizeof"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(buffer)"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"-"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"1"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"  /** this operation will fail with a ZNOAUTH error */"})}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  int"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" buflen"}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" sizeof"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(buffer);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  struct"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" Stat stat;"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  rc "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" zoo_get"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zh, "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"/xyz"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", buffer, "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"&"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"buflen, "}),e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"&"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"stat);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  if"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (rc) {"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    fprintf"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(stderr, "}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Error '}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"%d"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" for "}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"%s\\n"}),e.jsx(s.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"'}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", rc, "}),e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"__LINE__"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"  }"})}),`
`,e.jsx(s.span,{className:"line"}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"  zookeeper_close"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zh);"})]}),`
`,e.jsxs(s.span,{className:"line",children:[e.jsx(s.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"  return"}),e.jsx(s.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsx(s.span,{className:"line",children:e.jsx(s.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})})]})}function p(i={}){const{wrapper:s}=i.components||{};return s?e.jsx(s,{...i,children:e.jsx(t,{...i})}):t(i)}function h(i,s){throw new Error("Expected component `"+i+"` to be defined: you likely forgot to import, pass, or provide it.")}export{r as _markdown,p as default,o as extractedReferences,l as frontmatter,c as structuredData,d as toc};
