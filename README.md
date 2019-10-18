[![Gitter chat](https://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/apache/apache-zookeeper)
[![Build Status](https://travis-ci.org/apache/zookeeper.svg?branch=master)](https://travis-ci.org/apache/zookeeper)
[![Maven Central](https://img.shields.io/maven-central/v/org.apache.zookeeper/zookeeper)](https://zookeeper.apache.org/releases.html)
[![License](https://img.shields.io/github/license/apache/zookeeper)](https://github.com/apache/zookeeper/blob/master/LICENSE.txt)

# Apache ZooKeeper
![alt text](https://zookeeper.apache.org/images/zookeeper_small.gif "ZooKeeper")

ZooKeeper is a centralized service for maintaining configuration information, naming, providing distributed synchronization, and providing group services.

For the latest information about Apache ZooKeeper, please visit our [website](http://zookeeper.apache.org/)
and our [wiki](https://cwiki.apache.org/confluence/display/ZOOKEEPER)


## Packaging/Release Artifacts

Either downloaded from https://zookeeper.apache.org/releases.html or
found in zookeeper-assembly/target directory after building the project with maven.

    apache-zookeeper-[version].tar.gz

        Contains all the source files which can be built by running:
        mvn clean install

        To generate an aggregated apidocs for zookeeper-server and zookeeper-jute:
        mvn javadoc:aggregate
        (generated files will be at target/site/apidocs)

    apache-zookeeper-[version]-bin.tar.gz

        Contains all the jar files required to run ZooKeeper
        Full documentation can also be found in the docs folder

As of version 3.5.5, the parent, zookeeper and zookeeper-jute artifacts
are deployed to the central repository after the release
is voted on and approved by the Apache ZooKeeper PMC.

Maven central repository location can be found [here](https://repo1.maven.org/maven2/org/apache/zookeeper/zookeeper/).

## Docker Image
ZooKeeper docker [official image](https://hub.docker.com/_/zookeeper) is published to Docker Hub and maintained by the Docker Community.

## Contributing
We always welcome new contributors to the project! See [How to Contribute](https://cwiki.apache.org/confluence/display/ZOOKEEPER/HowToContribute) for details on how to submit patch through pull request and our contribution workflow.


