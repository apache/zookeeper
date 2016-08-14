# Zookeeper FLE & ZAB SSL

Provides SSL for Leader Election and ZAB i.e ports 3888 and 2888.

Goal of this patch is to build on top of SSL changes for [branch-3.4](https://github.com/geek101/zookeeper/blob/branch-3.4/README_SSL.md)

### Some details

* [X509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/common/X509Util.java) 
becomes first class citizen and [QuorumX509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/util/QuorumX509Util.java) and [ServerX509Util](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/util/ServerX509Util.java)
extend it.
* [ZKConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/common/ZKConfig.java) 
becomes an abstract class and [QuorumSslConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/quorum/QuorumPeerConfig.java) and 
[ZookeeperServerConfig](https://github.com/geek101/zookeeper/blob/branch-3.5-ssl-review5/src/java/main/org/apache/zookeeper/server/ZookeeperServerConfig.java) implement it.


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
```

Example run command:
```
java -Dquorum.ssl.enabled="true" -Dquorum.ssl.keyStore.location="node1.ks" 
-Dquorum.ssl.keyStore.password="CertPassword1" -Dquorum.ssl.trustStore.location="truststore.ks" -Dquorum.ssl.trustStore.password="StorePass" -cp zookeeper.jar:lib/* org.apache.zookeeper.server.quorum.QuorumPeerMain zoo1.cfg
```