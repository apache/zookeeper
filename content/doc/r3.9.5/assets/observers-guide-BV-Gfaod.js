import{j as e}from"./chunk-6CSD65Y2-BRnPx4iH.js";let o=`Although ZooKeeper performs very well by having clients connect directly
to voting members of the ensemble, this architecture makes it hard to
scale out to huge numbers of clients. The problem is that as we add more
voting members, write performance drops. This is because a write operation
requires the agreement of at least half the nodes in an ensemble, so the
cost of a vote grows significantly as more voters are added.

We have introduced a new type of ZooKeeper node called an *Observer* which
helps address this problem and further improves ZooKeeper's scalability. Observers
are non-voting members of an ensemble which only hear the results of votes, not the
agreement protocol that leads up to them. Other than this simple distinction,
Observers function exactly the same as Followers — clients may connect to
them and send read and write requests to them. Observers forward these
requests to the Leader like Followers do, but they then simply wait to
hear the result of the vote. Because of this, we can increase the number
of Observers as much as we like without harming vote performance.

Observers have other advantages. Because they do not vote, they are not a
critical part of the ZooKeeper ensemble — they can fail or be
disconnected from the cluster without harming the availability of the
ZooKeeper service. The benefit to the user is that Observers may connect
over less reliable network links than Followers. In fact, Observers may be
used to talk to a ZooKeeper server from another data center. Clients of
an Observer will see fast reads, as all reads are served locally, and
writes result in minimal network traffic since the number of messages
required without the vote protocol is smaller.

## How to Use Observers

Setting up a ZooKeeper ensemble that uses Observers requires just two
changes to your config files.

First, in the config file of every node that is to be an Observer, add:

\`\`\`
peerType=observer
\`\`\`

This tells ZooKeeper that the server is to be an Observer.

Second, in every server config file, append \`:observer\` to the server
definition line of each Observer. For example:

\`\`\`
server.1=localhost:2181:3181:observer
\`\`\`

This tells every other server that \`server.1\` is an Observer and that they
should not expect it to vote. This is all the configuration needed to add
an Observer to your ZooKeeper cluster. You can then connect to it as you
would an ordinary Follower:

\`\`\`bash
$ bin/zkCli.sh -server localhost:2181
\`\`\`

where \`localhost:2181\` is the hostname and port of the Observer as
specified in every config file. You should see a command line prompt
through which you can issue commands like \`ls\` to query the ZooKeeper service.

## How to Use Observer Masters

Observers function simply as non-voting members of the ensemble, sharing
the Learner interface with Followers and holding only a slightly different
internal pipeline. Both maintain connections along the quorum port with the
Leader by which they learn of all new proposals on the ensemble.

By default, Observers connect to the Leader along its quorum port to learn of
new proposals. There are benefits to allowing Observers to connect to
Followers instead as a means of plugging into the commit stream. This shifts
the burden of supporting Observers off the Leader, allowing it to focus on
coordinating the commit of writes. The result is better performance when the
Leader is under high load — particularly high network load such as after a
leader election when many Learners need to sync. It also reduces the total
number of network connections maintained on the Leader when there are many
Observers. Activating this feature allows the overall number of Observers to
scale into the hundreds, and improves Observer availability since a large
number of Observers finish syncing and start serving client traffic faster.

This feature is activated by adding the following entry to the server config
file. It instructs Observers to connect to peers (Leaders and Followers) on
the specified port, and instructs Followers to create an ObserverMaster thread
to listen and serve on that port:

\`\`\`
observerMasterPort=2191
\`\`\`

## Example Use Cases

Wherever you wish to scale the number of clients of your ZooKeeper ensemble,
or where you wish to insulate the critical part of an ensemble from the load
of dealing with client requests, Observers are a good architectural choice.
Two example use cases are:

* **Datacenter bridge:** Forming a ZooKeeper ensemble between two datacenters is
  problematic because high variance in latency between datacenters can lead to
  false-positive failure detection and partitioning. However, if the ensemble runs
  entirely in one datacenter and the second datacenter runs only Observers,
  partitions are not problematic — the ensemble remains connected. Clients of the
  Observers may still see and issue proposals.
* **Message bus integration:** Some use cases call for ZooKeeper as a component of
  a persistent reliable message bus. Observers provide a natural integration point:
  a plug-in mechanism can attach the stream of proposals an Observer sees to a
  publish-subscribe system, without loading the core ensemble.
`,a={title:"Observers",description:"How ZooKeeper Observers enable scaling to large numbers of clients without hurting write performance, by using non-voting ensemble members that can be added freely without affecting quorum."},i=[],l={contents:[{heading:void 0,content:`Although ZooKeeper performs very well by having clients connect directly
to voting members of the ensemble, this architecture makes it hard to
scale out to huge numbers of clients. The problem is that as we add more
voting members, write performance drops. This is because a write operation
requires the agreement of at least half the nodes in an ensemble, so the
cost of a vote grows significantly as more voters are added.`},{heading:void 0,content:`We have introduced a new type of ZooKeeper node called an Observer which
helps address this problem and further improves ZooKeeper's scalability. Observers
are non-voting members of an ensemble which only hear the results of votes, not the
agreement protocol that leads up to them. Other than this simple distinction,
Observers function exactly the same as Followers — clients may connect to
them and send read and write requests to them. Observers forward these
requests to the Leader like Followers do, but they then simply wait to
hear the result of the vote. Because of this, we can increase the number
of Observers as much as we like without harming vote performance.`},{heading:void 0,content:`Observers have other advantages. Because they do not vote, they are not a
critical part of the ZooKeeper ensemble — they can fail or be
disconnected from the cluster without harming the availability of the
ZooKeeper service. The benefit to the user is that Observers may connect
over less reliable network links than Followers. In fact, Observers may be
used to talk to a ZooKeeper server from another data center. Clients of
an Observer will see fast reads, as all reads are served locally, and
writes result in minimal network traffic since the number of messages
required without the vote protocol is smaller.`},{heading:"how-to-use-observers",content:`Setting up a ZooKeeper ensemble that uses Observers requires just two
changes to your config files.`},{heading:"how-to-use-observers",content:"First, in the config file of every node that is to be an Observer, add:"},{heading:"how-to-use-observers",content:"This tells ZooKeeper that the server is to be an Observer."},{heading:"how-to-use-observers",content:`Second, in every server config file, append :observer to the server
definition line of each Observer. For example:`},{heading:"how-to-use-observers",content:`This tells every other server that server.1 is an Observer and that they
should not expect it to vote. This is all the configuration needed to add
an Observer to your ZooKeeper cluster. You can then connect to it as you
would an ordinary Follower:`},{heading:"how-to-use-observers",content:`where localhost:2181 is the hostname and port of the Observer as
specified in every config file. You should see a command line prompt
through which you can issue commands like ls to query the ZooKeeper service.`},{heading:"how-to-use-observer-masters",content:`Observers function simply as non-voting members of the ensemble, sharing
the Learner interface with Followers and holding only a slightly different
internal pipeline. Both maintain connections along the quorum port with the
Leader by which they learn of all new proposals on the ensemble.`},{heading:"how-to-use-observer-masters",content:`By default, Observers connect to the Leader along its quorum port to learn of
new proposals. There are benefits to allowing Observers to connect to
Followers instead as a means of plugging into the commit stream. This shifts
the burden of supporting Observers off the Leader, allowing it to focus on
coordinating the commit of writes. The result is better performance when the
Leader is under high load — particularly high network load such as after a
leader election when many Learners need to sync. It also reduces the total
number of network connections maintained on the Leader when there are many
Observers. Activating this feature allows the overall number of Observers to
scale into the hundreds, and improves Observer availability since a large
number of Observers finish syncing and start serving client traffic faster.`},{heading:"how-to-use-observer-masters",content:`This feature is activated by adding the following entry to the server config
file. It instructs Observers to connect to peers (Leaders and Followers) on
the specified port, and instructs Followers to create an ObserverMaster thread
to listen and serve on that port:`},{heading:"example-use-cases",content:`Wherever you wish to scale the number of clients of your ZooKeeper ensemble,
or where you wish to insulate the critical part of an ensemble from the load
of dealing with client requests, Observers are a good architectural choice.
Two example use cases are:`},{heading:"example-use-cases",content:`Datacenter bridge: Forming a ZooKeeper ensemble between two datacenters is
problematic because high variance in latency between datacenters can lead to
false-positive failure detection and partitioning. However, if the ensemble runs
entirely in one datacenter and the second datacenter runs only Observers,
partitions are not problematic — the ensemble remains connected. Clients of the
Observers may still see and issue proposals.`},{heading:"example-use-cases",content:`Message bus integration: Some use cases call for ZooKeeper as a component of
a persistent reliable message bus. Observers provide a natural integration point:
a plug-in mechanism can attach the stream of proposals an Observer sees to a
publish-subscribe system, without loading the core ensemble.`}],headings:[{id:"how-to-use-observers",content:"How to Use Observers"},{id:"how-to-use-observer-masters",content:"How to Use Observer Masters"},{id:"example-use-cases",content:"Example Use Cases"}]};const h=[{depth:2,url:"#how-to-use-observers",title:e.jsx(e.Fragment,{children:"How to Use Observers"})},{depth:2,url:"#how-to-use-observer-masters",title:e.jsx(e.Fragment,{children:"How to Use Observer Masters"})},{depth:2,url:"#example-use-cases",title:e.jsx(e.Fragment,{children:"Example Use Cases"})}];function s(n){const t={code:"code",em:"em",h2:"h2",li:"li",p:"p",pre:"pre",span:"span",strong:"strong",ul:"ul",...n.components};return e.jsxs(e.Fragment,{children:[e.jsx(t.p,{children:`Although ZooKeeper performs very well by having clients connect directly
to voting members of the ensemble, this architecture makes it hard to
scale out to huge numbers of clients. The problem is that as we add more
voting members, write performance drops. This is because a write operation
requires the agreement of at least half the nodes in an ensemble, so the
cost of a vote grows significantly as more voters are added.`}),`
`,e.jsxs(t.p,{children:["We have introduced a new type of ZooKeeper node called an ",e.jsx(t.em,{children:"Observer"}),` which
helps address this problem and further improves ZooKeeper's scalability. Observers
are non-voting members of an ensemble which only hear the results of votes, not the
agreement protocol that leads up to them. Other than this simple distinction,
Observers function exactly the same as Followers — clients may connect to
them and send read and write requests to them. Observers forward these
requests to the Leader like Followers do, but they then simply wait to
hear the result of the vote. Because of this, we can increase the number
of Observers as much as we like without harming vote performance.`]}),`
`,e.jsx(t.p,{children:`Observers have other advantages. Because they do not vote, they are not a
critical part of the ZooKeeper ensemble — they can fail or be
disconnected from the cluster without harming the availability of the
ZooKeeper service. The benefit to the user is that Observers may connect
over less reliable network links than Followers. In fact, Observers may be
used to talk to a ZooKeeper server from another data center. Clients of
an Observer will see fast reads, as all reads are served locally, and
writes result in minimal network traffic since the number of messages
required without the vote protocol is smaller.`}),`
`,e.jsx(t.h2,{id:"how-to-use-observers",children:"How to Use Observers"}),`
`,e.jsx(t.p,{children:`Setting up a ZooKeeper ensemble that uses Observers requires just two
changes to your config files.`}),`
`,e.jsx(t.p,{children:"First, in the config file of every node that is to be an Observer, add:"}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsx(t.span,{className:"line",children:e.jsx(t.span,{children:"peerType=observer"})})})})}),`
`,e.jsx(t.p,{children:"This tells ZooKeeper that the server is to be an Observer."}),`
`,e.jsxs(t.p,{children:["Second, in every server config file, append ",e.jsx(t.code,{children:":observer"}),` to the server
definition line of each Observer. For example:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsx(t.span,{className:"line",children:e.jsx(t.span,{children:"server.1=localhost:2181:3181:observer"})})})})}),`
`,e.jsxs(t.p,{children:["This tells every other server that ",e.jsx(t.code,{children:"server.1"}),` is an Observer and that they
should not expect it to vote. This is all the configuration needed to add
an Observer to your ZooKeeper cluster. You can then connect to it as you
would an ordinary Follower:`]}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="m 4,4 a 1,1 0 0 0 -0.7070312,0.2929687 1,1 0 0 0 0,1.4140625 L 8.5859375,11 3.2929688,16.292969 a 1,1 0 0 0 0,1.414062 1,1 0 0 0 1.4140624,0 l 5.9999998,-6 a 1.0001,1.0001 0 0 0 0,-1.414062 L 4.7070312,4.2929687 A 1,1 0 0 0 4,4 Z m 8,14 a 1,1 0 0 0 -1,1 1,1 0 0 0 1,1 h 8 a 1,1 0 0 0 1,-1 1,1 0 0 0 -1,-1 z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsxs(t.span,{className:"line",children:[e.jsx(t.span,{style:{"--shiki-light":"#6F42C1","--shiki-dark":"#B392F0"},children:"$"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" bin/zkCli.sh"}),e.jsx(t.span,{style:{"--shiki-light":"#005CC5","--shiki-dark":"#79B8FF"},children:" -server"}),e.jsx(t.span,{style:{"--shiki-light":"#032F62","--shiki-dark":"#9ECBFF"},children:" localhost:2181"})]})})})}),`
`,e.jsxs(t.p,{children:["where ",e.jsx(t.code,{children:"localhost:2181"}),` is the hostname and port of the Observer as
specified in every config file. You should see a command line prompt
through which you can issue commands like `,e.jsx(t.code,{children:"ls"})," to query the ZooKeeper service."]}),`
`,e.jsx(t.h2,{id:"how-to-use-observer-masters",children:"How to Use Observer Masters"}),`
`,e.jsx(t.p,{children:`Observers function simply as non-voting members of the ensemble, sharing
the Learner interface with Followers and holding only a slightly different
internal pipeline. Both maintain connections along the quorum port with the
Leader by which they learn of all new proposals on the ensemble.`}),`
`,e.jsx(t.p,{children:`By default, Observers connect to the Leader along its quorum port to learn of
new proposals. There are benefits to allowing Observers to connect to
Followers instead as a means of plugging into the commit stream. This shifts
the burden of supporting Observers off the Leader, allowing it to focus on
coordinating the commit of writes. The result is better performance when the
Leader is under high load — particularly high network load such as after a
leader election when many Learners need to sync. It also reduces the total
number of network connections maintained on the Leader when there are many
Observers. Activating this feature allows the overall number of Observers to
scale into the hundreds, and improves Observer availability since a large
number of Observers finish syncing and start serving client traffic faster.`}),`
`,e.jsx(t.p,{children:`This feature is activated by adding the following entry to the server config
file. It instructs Observers to connect to peers (Leaders and Followers) on
the specified port, and instructs Followers to create an ObserverMaster thread
to listen and serve on that port:`}),`
`,e.jsx(e.Fragment,{children:e.jsx(t.pre,{className:"shiki shiki-themes github-light github-dark",style:{"--shiki-light":"#24292e","--shiki-dark":"#e1e4e8","--shiki-light-bg":"#fff","--shiki-dark-bg":"#24292e"},tabIndex:"0",icon:'<svg viewBox="0 0 24 24"><path d="M 6,1 C 4.354992,1 3,2.354992 3,4 v 16 c 0,1.645008 1.354992,3 3,3 h 12 c 1.645008,0 3,-1.354992 3,-3 V 8 7 A 1.0001,1.0001 0 0 0 20.707031,6.2929687 l -5,-5 A 1.0001,1.0001 0 0 0 15,1 h -1 z m 0,2 h 7 v 3 c 0,1.645008 1.354992,3 3,3 h 3 v 11 c 0,0.564129 -0.435871,1 -1,1 H 6 C 5.4358712,21 5,20.564129 5,20 V 4 C 5,3.4358712 5.4358712,3 6,3 Z M 15,3.4140625 18.585937,7 H 16 C 15.435871,7 15,6.5641288 15,6 Z" fill="currentColor" /></svg>',children:e.jsx(t.code,{children:e.jsx(t.span,{className:"line",children:e.jsx(t.span,{children:"observerMasterPort=2191"})})})})}),`
`,e.jsx(t.h2,{id:"example-use-cases",children:"Example Use Cases"}),`
`,e.jsx(t.p,{children:`Wherever you wish to scale the number of clients of your ZooKeeper ensemble,
or where you wish to insulate the critical part of an ensemble from the load
of dealing with client requests, Observers are a good architectural choice.
Two example use cases are:`}),`
`,e.jsxs(t.ul,{children:[`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Datacenter bridge:"}),` Forming a ZooKeeper ensemble between two datacenters is
problematic because high variance in latency between datacenters can lead to
false-positive failure detection and partitioning. However, if the ensemble runs
entirely in one datacenter and the second datacenter runs only Observers,
partitions are not problematic — the ensemble remains connected. Clients of the
Observers may still see and issue proposals.`]}),`
`,e.jsxs(t.li,{children:[e.jsx(t.strong,{children:"Message bus integration:"}),` Some use cases call for ZooKeeper as a component of
a persistent reliable message bus. Observers provide a natural integration point:
a plug-in mechanism can attach the stream of proposals an Observer sees to a
publish-subscribe system, without loading the core ensemble.`]}),`
`]})]})}function c(n={}){const{wrapper:t}=n.components||{};return t?e.jsx(t,{...n,children:e.jsx(s,{...n})}):s(n)}export{o as _markdown,c as default,i as extractedReferences,a as frontmatter,l as structuredData,h as toc};
