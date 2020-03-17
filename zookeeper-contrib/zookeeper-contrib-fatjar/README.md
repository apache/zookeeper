ZooKeeper Fatjar
================

This package contains build to create a fat zookeeper jar. Fatjar can be used to run:
- zookeeper server
- zookeeper client
- distributed load generator for testing (generateLoad)
- container that will instantiate classes as directed by an instance manager (ic)
- system test (systest)
- jmh micro benchmarks (jmh)


Use following command to build fatjar
```
mvn clean install -P fatjar -DskipTests
```

To run the fatjar use:
```
java -jar zoookeeper-<version>-fatjar.jar
```
