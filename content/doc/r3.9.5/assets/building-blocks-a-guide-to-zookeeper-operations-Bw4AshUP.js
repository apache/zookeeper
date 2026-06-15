import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let l=`This section surveys all the operations a developer can perform
against a ZooKeeper server. It is lower level information than the earlier
concepts chapters in this manual, but higher level than the ZooKeeper API
Reference.

## Handling Errors

Both the Java and C client bindings may report errors. The Java client binding does so by throwing KeeperException, calling code() on the exception will return the specific error code. The C client binding returns an error code as defined in the enum ZOO\\_ERRORS. API callbacks indicate result code for both language bindings. See the API documentation (javadoc for Java, doxygen for C) for full details on the possible errors and their meaning.

## Connecting to ZooKeeper

Before we begin, you will have to set up a running Zookeeper server so that we can start developing the client. For C client bindings, we will be using the multithreaded library(zookeeper*mt) with a simple example written in C. To establish a connection with Zookeeper server, we make use of C API - \\_zookeeper\\_init* with the following signature:

int zookeeper\\_init(const char \\*host, watcher\\_fn fn, int recv\\_timeout, const clientid\\_t \\*clientid, void \\*context, int flags);

* \\**host* :
  Connection string to zookeeper server in the format of host:port. If there are multiple servers, use comma as separator after specifying the host:port pairs. Eg: "127.0.0.1:2181,127.0.0.1:3001,127.0.0.1:3002"
* *fn* :
  Watcher function to process events when a notification is triggered.
* *recv\\_timeout* :
  Session expiration time in milliseconds.
* \\**clientid* :
  We can specify 0 for a new session. If a session has already establish previously, we could provide that client ID and it would reconnect to that previous session.
* \\**context* :
  Context object that can be associated with the zkhandle\\_t handler. If it is not used, we can set it to 0.
* *flags* :
  In an initiation, we can leave it for 0.

We will demonstrate client that outputs "Connected to Zookeeper" after successful connection or an error message otherwise. Let's call the following code *zkClient.cc*:

\`\`\`c
#include <stdio.h>
#include <zookeeper/zookeeper.h>
#include <errno.h>
using namespace std;

// Keeping track of the connection state
static int connected = 0;
static int expired   = 0;

// *zkHandler handles the connection with Zookeeper
static zhandle_t *zkHandler;

// watcher function would process events
void watcher(zhandle_t *zkH, int type, int state, const char *path, void *watcherCtx)
{
    if (type == ZOO_SESSION_EVENT) {

        // state refers to states of zookeeper connection.
        // To keep it simple, we would demonstrate these 3: ZOO_EXPIRED_SESSION_STATE, ZOO_CONNECTED_STATE, ZOO_NOTCONNECTED_STATE
        // If you are using ACL, you should be aware of an authentication failure state - ZOO_AUTH_FAILED_STATE
        if (state == ZOO_CONNECTED_STATE) {
            connected = 1;
        } else if (state == ZOO_NOTCONNECTED_STATE ) {
            connected = 0;
        } else if (state == ZOO_EXPIRED_SESSION_STATE) {
            expired = 1;
            connected = 0;
            zookeeper_close(zkH);
        }
    }
}

int main(){
    zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);

    // zookeeper_init returns the handler upon a successful connection, null otherwise
    zkHandler = zookeeper_init("localhost:2181", watcher, 10000, 0, 0, 0);

    if (!zkHandler) {
        return errno;
    }else{
        printf("Connection established with Zookeeper. \\n");
    }

    // Close Zookeeper connection
    zookeeper_close(zkHandler);

    return 0;
}
\`\`\`

Compile the code with the multithreaded library mentioned before.

\`> g++ -Iinclude/ zkClient.cpp -lzookeeper_mt -o Client\`

Run the client.

\`> ./Client\`

From the output, you should see "Connected to Zookeeper" along with Zookeeper's DEBUG messages if the connection is successful.
`,h={title:"Building Blocks: A Guide to ZooKeeper Operations",description:"A practical reference for all operations a developer can perform against a ZooKeeper server, covering error handling, connecting, and the full synchronous and asynchronous API."},r=[],a={contents:[{heading:void 0,content:`This section surveys all the operations a developer can perform
against a ZooKeeper server. It is lower level information than the earlier
concepts chapters in this manual, but higher level than the ZooKeeper API
Reference.`},{heading:"handling-errors",content:"Both the Java and C client bindings may report errors. The Java client binding does so by throwing KeeperException, calling code() on the exception will return the specific error code. The C client binding returns an error code as defined in the enum ZOO_ERRORS. API callbacks indicate result code for both language bindings. See the API documentation (javadoc for Java, doxygen for C) for full details on the possible errors and their meaning."},{heading:"connecting-to-zookeeper",content:"Before we begin, you will have to set up a running Zookeeper server so that we can start developing the client. For C client bindings, we will be using the multithreaded library(zookeepermt) with a simple example written in C. To establish a connection with Zookeeper server, we make use of C API - _zookeeper_init with the following signature:"},{heading:"connecting-to-zookeeper",content:"int zookeeper_init(const char *host, watcher_fn fn, int recv_timeout, const clientid_t *clientid, void *context, int flags);"},{heading:"connecting-to-zookeeper",content:`*host :
Connection string to zookeeper server in the format of host:port. If there are multiple servers, use comma as separator after specifying the host:port pairs. Eg: "127.0.0.1:2181,127.0.0.1:3001,127.0.0.1:3002"`},{heading:"connecting-to-zookeeper",content:`fn :
Watcher function to process events when a notification is triggered.`},{heading:"connecting-to-zookeeper",content:`recv_timeout :
Session expiration time in milliseconds.`},{heading:"connecting-to-zookeeper",content:`*clientid :
We can specify 0 for a new session. If a session has already establish previously, we could provide that client ID and it would reconnect to that previous session.`},{heading:"connecting-to-zookeeper",content:`*context :
Context object that can be associated with the zkhandle_t handler. If it is not used, we can set it to 0.`},{heading:"connecting-to-zookeeper",content:`flags :
In an initiation, we can leave it for 0.`},{heading:"connecting-to-zookeeper",content:`We will demonstrate client that outputs "Connected to Zookeeper" after successful connection or an error message otherwise. Let's call the following code zkClient.cc:`},{heading:"connecting-to-zookeeper",content:"Compile the code with the multithreaded library mentioned before."},{heading:"connecting-to-zookeeper",content:"> g++ -Iinclude/ zkClient.cpp -lzookeeper_mt -o Client"},{heading:"connecting-to-zookeeper",content:"Run the client."},{heading:"connecting-to-zookeeper",content:"> ./Client"},{heading:"connecting-to-zookeeper",content:`From the output, you should see "Connected to Zookeeper" along with Zookeeper's DEBUG messages if the connection is successful.`}],headings:[{id:"handling-errors",content:"Handling Errors"},{id:"connecting-to-zookeeper",content:"Connecting to ZooKeeper"}]};const o=[{depth:2,url:"#handling-errors",title:e.jsx(e.Fragment,{children:"Handling Errors"})},{depth:2,url:"#connecting-to-zookeeper",title:e.jsx(e.Fragment,{children:"Connecting to ZooKeeper"})}];function n(s){const i={code:"code",em:"em",h2:"h2",li:"li",p:"p",pre:"pre",span:"span",ul:"ul",...s.components};return e.jsxs(e.Fragment,{children:[e.jsx(i.p,{children:`This section surveys all the operations a developer can perform
against a ZooKeeper server. It is lower level information than the earlier
concepts chapters in this manual, but higher level than the ZooKeeper API
Reference.`}),`
`,e.jsx(i.h2,{id:"handling-errors",children:"Handling Errors"}),`
`,e.jsx(i.p,{children:"Both the Java and C client bindings may report errors. The Java client binding does so by throwing KeeperException, calling code() on the exception will return the specific error code. The C client binding returns an error code as defined in the enum ZOO_ERRORS. API callbacks indicate result code for both language bindings. See the API documentation (javadoc for Java, doxygen for C) for full details on the possible errors and their meaning."}),`
`,e.jsx(i.h2,{id:"connecting-to-zookeeper",children:"Connecting to ZooKeeper"}),`
`,e.jsxs(i.p,{children:["Before we begin, you will have to set up a running Zookeeper server so that we can start developing the client. For C client bindings, we will be using the multithreaded library(zookeeper",e.jsx(i.em,{children:"mt) with a simple example written in C. To establish a connection with Zookeeper server, we make use of C API - _zookeeper_init"})," with the following signature:"]}),`
`,e.jsx(i.p,{children:"int zookeeper_init(const char *host, watcher_fn fn, int recv_timeout, const clientid_t *clientid, void *context, int flags);"}),`
`,e.jsxs(i.ul,{children:[`
`,e.jsxs(i.li,{children:["*",e.jsx(i.em,{children:"host"}),` :
Connection string to zookeeper server in the format of host:port. If there are multiple servers, use comma as separator after specifying the host:port pairs. Eg: "127.0.0.1:2181,127.0.0.1:3001,127.0.0.1:3002"`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.em,{children:"fn"}),` :
Watcher function to process events when a notification is triggered.`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.em,{children:"recv_timeout"}),` :
Session expiration time in milliseconds.`]}),`
`,e.jsxs(i.li,{children:["*",e.jsx(i.em,{children:"clientid"}),` :
We can specify 0 for a new session. If a session has already establish previously, we could provide that client ID and it would reconnect to that previous session.`]}),`
`,e.jsxs(i.li,{children:["*",e.jsx(i.em,{children:"context"}),` :
Context object that can be associated with the zkhandle_t handler. If it is not used, we can set it to 0.`]}),`
`,e.jsxs(i.li,{children:[e.jsx(i.em,{children:"flags"}),` :
In an initiation, we can leave it for 0.`]}),`
`]}),`
`,e.jsxs(i.p,{children:[`We will demonstrate client that outputs "Connected to Zookeeper" after successful connection or an error message otherwise. Let's call the following code `,e.jsx(i.em,{children:"zkClient.cc"}),":"]}),`
`,e.jsx(e.Fragment,{children:e.jsx(i.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M16.5921 9.1962s-.354-3.298-3.627-3.39c-3.2741-.09-4.9552 2.474-4.9552 6.14 0 3.6651 1.858 6.5972 5.0451 6.5972 3.184 0 3.5381-3.665 3.5381-3.665l6.1041.365s.36 3.31-2.196 5.836c-2.552 2.5241-5.6901 2.9371-7.8762 2.9201-2.19-.017-5.2261.034-8.1602-2.97-2.938-3.0101-3.436-5.9302-3.436-8.8002 0-2.8701.556-6.6702 4.047-9.5502C7.444.72 9.849 0 12.254 0c10.0422 0 10.7172 9.2602 10.7172 9.2602z" fill="currentColor" /></svg>',children:e.jsxs(i.code,{children:[e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" <stdio.h>"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" <zookeeper/zookeeper.h>"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"#include"}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" <errno.h>"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"using namespace std;"})}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"// Keeping track of the connection state"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"static"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" int"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" connected "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"static"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" int"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" expired   "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"// *zkHandler handles the connection with Zookeeper"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"static"}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" zhandle_t"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zkHandler;"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"// watcher function would process events"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"void"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" watcher"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"zhandle_t"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"zkH"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" type"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:" state"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"const"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" char"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"path"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"void"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" *"}),e.jsx(i.span,{style:{"--shiki-light":"#E36209","--shiki-dark":"#FFAB70"},children:"watcherCtx"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:")"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"{"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    if"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (type "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ZOO_SESSION_EVENT) {"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"        // state refers to states of zookeeper connection."})}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"        // To keep it simple, we would demonstrate these 3: ZOO_EXPIRED_SESSION_STATE, ZOO_CONNECTED_STATE, ZOO_NOTCONNECTED_STATE"})}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"        // If you are using ACL, you should be aware of an authentication failure state - ZOO_AUTH_FAILED_STATE"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"        if"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (state "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ZOO_CONNECTED_STATE) {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            connected "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        } "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" if"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (state "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ZOO_NOTCONNECTED_STATE ) {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            connected "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        } "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:" if"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" (state "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"=="}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ZOO_EXPIRED_SESSION_STATE) {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            expired "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 1"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"            connected "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"            zookeeper_close"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zkH);"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"        }"})}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"})}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"int"}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" main"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(){"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    zoo_set_debug_level"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(ZOO_LOG_LEVEL_DEBUG);"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // zookeeper_init returns the handler upon a successful connection, null otherwise"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    zkHandler "}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"="}),e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:" zookeeper_init"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"localhost:2181"'}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", watcher, "}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"10000"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:", "}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    if"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" ("}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"!"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"zkHandler) {"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"        return"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:" errno;"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"}),e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"else"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"{"})]}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"        printf"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"("}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"Connection established with Zookeeper. '}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:"\\n"}),e.jsx(i.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:'"'}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:");"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"    }"})}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#6A737D","--shiki-dark":"#6A737D"},children:"    // Close Zookeeper connection"})}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"    zookeeper_close"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"(zkHandler);"})]}),`
`,e.jsx(i.span,{className:"line"}),`
`,e.jsxs(i.span,{className:"line",children:[e.jsx(i.span,{style:{"--shiki-light":"#D73A49","--shiki-dark":"#F97583"},children:"    return"}),e.jsx(i.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" 0"}),e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:";"})]}),`
`,e.jsx(i.span,{className:"line",children:e.jsx(i.span,{style:{"--shiki-light":"#24292E","--shiki-dark":"#E1E4E8"},children:"}"})})]})})}),`
`,e.jsx(i.p,{children:"Compile the code with the multithreaded library mentioned before."}),`
`,e.jsx(i.p,{children:e.jsx(i.code,{children:"> g++ -Iinclude/ zkClient.cpp -lzookeeper_mt -o Client"})}),`
`,e.jsx(i.p,{children:"Run the client."}),`
`,e.jsx(i.p,{children:e.jsx(i.code,{children:"> ./Client"})}),`
`,e.jsx(i.p,{children:`From the output, you should see "Connected to Zookeeper" along with Zookeeper's DEBUG messages if the connection is successful.`})]})}function c(s={}){const{wrapper:i}=s.components||{};return i?e.jsx(i,{...s,children:e.jsx(n,{...s})}):n(s)}export{l as _markdown,c as default,r as extractedReferences,h as frontmatter,a as structuredData,o as toc};
