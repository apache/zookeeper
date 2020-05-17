<!--
Copyright 2002-2019 The Apache Software Foundation

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

# ZooKeeper Client Bindings

- Clients with some active in the official repository within the latest six months are marked with `active:Y`

- If your client wants to be listed here. Please Submit a pull request or write an email to **dev@zookeeper.apache.org**.
After some evaluations, your binding will be included.


## Java
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[native java client](https://github.com/apache/zookeeper/blob/master/zookeeper-server/src/main/java/org/apache/zookeeper/ZooKeeper.java)|Apache ZooKeeper|Y|Y|All the native java client apis are included in the `ZooKeeper.java` and its subclasses:`ZooKeeperAdmin.java`
|[curator](https://github.com/apache/curator)|Apache Curator|Y|Y|Apache Curator includes a high-level API framework and utilities to make using Apache ZooKeeper much easier and more reliable.
|[zkclient](https://github.com/sgroschupf/zkclient)|[sgroschupf](https://github.com/sgroschupf)|Y||a zookeeper client, that makes life a little easier.


## C
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[native C client](https://github.com/apache/zookeeper/tree/master/zookeeper-client/zookeeper-client-c)|Apache ZooKeeper|Y|Y|All the native C client apis are included in the `zookeeper.c`


## C++
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[zookeeper-cpp](https://github.com/tgockel/zookeeper-cpp)|[Travis Gockel](https://github.com/tgockel)||Y|A ZooKeeper client for C++.


## C#(.NET)
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[zookeeper-client](https://github.com/RabbitTeam/zookeeper-client)|[RabbitTeam](https://github.com/RabbitTeam)|||Provides the basic zk operation, but also additionally encapsulates the commonly used functions to make it easier for .NET developers to use zookeeper better.


## Python
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[kazoo](https://github.com/python-zk/kazoo)|Kazoo Team|Y|Y|Kazoo is a high-level Python library that makes it easier to use Apache Zookeeper
|[zookeeper-contrib-zkpython](https://github.com/apache/zookeeper/tree/master/zookeeper-contrib/zookeeper-contrib-zkpython)|Apache Zookeeper|Y|Y|Early version of ZooKeeper bindings for Python


## Go
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[go-zookeeper](https://github.com/samuel/go-zookeeper)|[Samuel Stauffer](https://github.com/samuel)|Y|Y|Native ZooKeeper client for Go


## Scala
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[scala-zookeeper-client](https://github.com/foursquare/scala-zookeeper-client)|foursquare|||A ZooKeeper client library in Scala.
|[util-zk](https://github.com/twitter/util/tree/develop/util-zk)|twitter|||


## Node.js
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[node-zookeeper](https://github.com/yfinkelstein/node-zookeeper)|[Yuri Finkelstein](https://github.com/yfinkelstein)|Y|Y|node.js client for Apache Zookeeper
|[node-zookeeper-client](https://github.com/alexguan/node-zookeeper-client)|[Alex Guan](https://github.com/alexguan)|Y|Y|A pure Javascript ZooKeeper client for Node.js


## Erlang
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[erlzk](https://github.com/huaban/erlzk)|[huaban](https://github.com/huaban)|Y|Y|A Pure Erlang ZooKeeper Client (no C dependency)
|[ezk](https://github.com/campanja/ezk)|Marco Grebe|||Erlang-Bindings for Zookeeper


## Haskell
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[haskell-zookeeper-client](https://github.com/motus/haskell-zookeeper-client)|[Sergiy Matusevych](https://github.com/motus)|||Apache ZooKeeper client for Haskell (GHC)
|[hzk](https://github.com/dgvncsz0f/hzk)|[Diego Souza](https://github.com/dgvncsz0f)|||Zookeeper bindings for haskell


## Ruby
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[ZK](https://github.com/zk-ruby/zk)|Jonathan Simms|Y||A High-Level wrapper for Apache's Zookeeper


## PHP
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[php-zookeeper](https://github.com/php-zookeeper/php-zookeeper)|[php-zookeeper](https://github.com/php-zookeeper)|Y|Y|A PHP extension for interfacing with Apache ZooKeeper


## Lua
|  name |maintainer |recommended|active|description
|:-:|:-:|:-:|:-:|:-:
|[zklua](https://github.com/forhappy/zklua)|[Fu Haiping](https://github.com/forhappy)|Y||Lua binding of apache zookeeper


## HTTP/restful api
- Currently, the HTTP/restful api has not been supported yet.
- There was a contributed [restful binding](https://github.com/apache/zookeeper/tree/trunk/src/contrib/rest) in the trunk which now is not in a well-maintained state.


#### References
[1] https://cwiki.apache.org/confluence/display/ZOOKEEPER/ZKClientBindings
