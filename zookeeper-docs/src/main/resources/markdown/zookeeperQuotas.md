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

Notice: What the `namespace` quota means is the count quota which limits the number of children
under the path(included itself).

    $ bin/zkCli.sh -server host:port**

The above command gives you a command line option of using quotas.

<a name="Setting+Quotas"></a>

### Setting Quotas

- You can use `setquota` to set a quota on a ZooKeeper node. It has an option of setting quota with
`-n` (for namespace/count) and `-b` (for bytes/data length).

- The ZooKeeper quota is stored in ZooKeeper itself in **/zookeeper/quota**. To disable other people from
changing the quotas, users can set the ACL for **/zookeeper/quota** ,so that only admins are able to read and write to it.

- If the quota doesn't exist in the specified path,create the quota, otherwise update the quota.

- The Scope of the quota users set is all the nodes under the path specified (included itself).

- In order to simplify the calculation of quota in the current directory/hierarchy structure, a complete tree path(from root to leaf node)
can be set only one quota. In the situation when setting a quota in a path which its parent or child node already has a quota. `setquota` will
reject and tell the specified parent or child path, users can adjust allocations of quotas(delete/move-up/move-down the quota)
according to specific circumstances.

- Combined with the Chroot, the quota will have a better isolation effectiveness between different applications.For example:

    ```bash
    # Chroot is:
    192.168.0.1:2181,192.168.0.2:2181,192.168.0.3:2181/apps/app1
    setquota -n 100000 /apps/app1
    ```

- Users cannot set the quota on the path under **/zookeeper/quota**

- The quota supports the soft and hard quota. The soft quota just logs the warning info when exceeding the quota, but the hard quota
also throws a `QuotaExceededException`. When setting soft and hard quota on the same path, the hard quota has the priority.

<a name="Listing+Quotas"></a>

### Listing Quotas

You can use _listquota_ to list a quota on a ZooKeeper node.

<a name="Deleting+Quotas"></a>

### Deleting Quotas

You can use _delquota_ to delete quota on a ZooKeeper node.


