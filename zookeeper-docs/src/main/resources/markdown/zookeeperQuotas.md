<!--
Copyright 2002-2004 The Apache Software Foundation

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

# ZooKeeper Quota's Guide

### A Guide to Deployment and Administration

* [Quotas](#zookeeper_quotas)
    * [Setting Quotas](#Setting+Quotas)
    * [Listing Quotas](#Listing+Quotas)
    * [Deleting Quotas](#Deleting+Quotas)

<a name="zookeeper_quotas"></a>

## Quotas

ZooKeeper has both namespace and bytes quotas. You can use the ZooKeeperMain class to setup quotas.
ZooKeeper prints _WARN_ messages if users exceed the quota assigned to them. The messages
are printed in the log of the ZooKeeper.

    $ bin/zkCli.sh -server host:port**

The above command gives you a command line option of using quotas.

<a name="Setting+Quotas"></a>

### Setting Quotas

You can use _setquota_ to set a quota on a ZooKeeper node. It has an option of setting quota with
`-n` (for namespace)
and `-b` (for bytes).

The ZooKeeper quota are stored in ZooKeeper itself in /zookeeper/quota. To disable other people from
changing the quota's set the ACL for /zookeeper/quota such that only admins are able to read and write to it.

<a name="Listing+Quotas"></a>

### Listing Quotas

You can use _listquota_ to list a quota on a ZooKeeper node.

<a name="Deleting+Quotas"></a>

### Deleting Quotas

You can use _delquota_ to delete quota on a ZooKeeper node.


