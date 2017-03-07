# Zookeeper Dynamic Quorum SSL

Provides SSL for Leader Election and ZAB i.e ports 3888 and 2888.

Goal of this patch is to build on top of SSL changes for [branch-3.4](https://github.com/geek101/zookeeper/blob/branch-3.4/README_SSL.md) and in 
the spirit of branch-3.5 provide support for dynamic reconfiguration, i.e do 
not violate safety and liveliness even when SSL is enabled.

### TODO

* CA based cert verification currently has no support for changing CA in a 
fault-tolerant way. Alternative to changing CA is to revoke the quorum peer 
to be removed via CRL(s) hence this needs more discussion/debate perhaps. 

* Needs test framework and/or test cases to verify new functionality, this is 
probably a significant amount of work gating this patch among other things.

### Self Signed Certs How To

The idea here is to propagate the new member(s) certificate fingerprint(s) 
via the secure channel to the quorum via the reconfig() API.

Each quorum peer will have its self-signed cert finger print (typically like 
a SHA-256 digest) embedded into its server string.

```
server.1=125.23.63.23:2780:2783:participant:cert:SHA-256-XXXX;2791
```

We also have support for both plain and secure port for ZookeeperServer hence 
the config has been extended to reflect that as follows.

```
server.1=125.23.63.23:2780:2783:participant:cert:SHA-256-XXXX;plain:2791;
secure:2891
```

A reconfiguration operation would work by submitting the new server 
config (or the new quorum list) to reconfig() API.

This has been tested to work with the current state of the patch.

### Some details

* [X509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/common/X509Util.java) 
becomes first class citizen and [QuorumX509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/util/QuorumX509Util.java) and [ServerX509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/util/ServerX509Util.java)
extend it.
* [ZKConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/common/ZKConfig.java) 
becomes an abstract class and [QuorumSslConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/QuorumPeerConfig.java) and 
[ZookeeperServerConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/ZookeeperServerConfig.java) implement it.
* [QuorumServer](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/QuorumPeer.java#L278) gets the parsing code for the extra cert information and gets 
help from new [SSLCertCfg](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/SSLCertCfg.java) class. Dynamic config generation is handled 
transparently due to this.
* [ZKDynamicX509TrustManager](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/util/ZKDynamicX509TrustManager.java) handles the dynamic verification of certs and 
gets help from QuorumPeer's new API, 
[getQuorumServerFingerPrintByElectionAddress()](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/QuorumPeer.java#L1642) and 
[getQuorumServerFingerPrintByCert()](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/QuorumPeer.java#L1661)
* Support for a Quorum peer to also be authenticated as a [ZK client](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/util/ServerX509Util.java#L62) (this 
will be removed if it breaks security and or is not needed)


### How to Run

**Depends on Java 1.7+**

##### Building


```
git checkout branch-3.5-ssl-review5
ant jar
```

Args to enable SSL:
```
-Dquorum.ssl.enabled="true"
-Dquorum.ssl.keyStore.location="<Private key and signed cert, key store file>"
-Dquorum.ssl.keyStore.password="<Password for the above>"
-Dquorum.ssl.trustStore.location="<Root CA cert, key store file>"
-Dquorum.ssl.trustStore.password="<Password for the above>"
-Dquorum.ssl.digest.algos="<example: SHA-256, SHA-384 etc>"
```

Example run command:
```
java -Dquorum.ssl.enabled="true" -Dquorum.ssl.keyStore.location="node1.ks" -Dquorum.ssl.keyStore.password="CertPassword1" -Dquorum.ssl.trustStore.location="truststore.ks" -Dquorum.ssl.trustStore.password="StorePass" -Dquorum.ssl.digest.algos="SHA-256" -cp zookeeper.jar:lib/* org.apache.zookeeper.server.quorum.QuorumPeerMain zoo1.cfg
```

##### Note

Keystore password must be the same as password used to store the private key of the node.
Keystore cannot have more than 1 key.
To help with debug please add -Djavax.net.debug=ssl:handshake.

#### Cert generation and test helpers

##### Generating Root CA and certs for Zookeeper nodes
Use the scripts and config files in *resources/* directory.

###### Step 1
To generate root CA:

```
$ cd resources/x509ca
$ resources/x509ca$ ../init.sh
```

> use defaults and enter yes to load root self-signed cert to truststore
> truststore is *resources/x509ca/java/truststore.ks* 

###### Step 2

Now generate certs for every node, ex:

```
$ resources/x509ca$ ../gencert.sh node1
```

> you will be prompted for private key password, enter: CertPassword1
> note: you can enter any password but remember to change the script to support that if you do so.
> keystore is *resouces/x509ca/node1.ks*
> Repeat Step 2 for as many nodes as you want.
> These will be used by step 3.

###### Step 3

Running a three node zookeeper cluster

Create three loopback interfaces 127.0.1.1, 127.0.1.2, 127.0.1.3 and run *start_quorum.sh* in *config/multi/*
```
$ sudo ifconfig lo:1 127.0.1.1 netmask 255.255.255.0
$ cd conf/multi/
$ ./start_quorum.sh
```

> Verify the logs in */tmp/zookeeper/multi/node<id>.log* and SSL debug data in
> *conf/multi/node<id>.out*
> If logs look good then you could use zkCli.sh to test the cluster.

```
bin/zkCli.sh -server 127.0.1.1:2181
```

##### Unit test

Currently unit test expects keystore files to be available via absolute path.
Edit *src/java/test/org/apache/zookeeper/server/quorum/QuorumSocketFactoryTest.java* and point **PATH** to *resources/* directory.

Also generate certs and keys in *resources/x509ca2* for the negative test to pass.