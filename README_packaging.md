# README file for Packaging Notes

The ZooKeeper project publishes releases as tarballs.  For ZooKeeper packages
specific to your OS (such as rpm and deb), consider using Apache Bigtop:

http://bigtop.apache.org/


## Requirements

- ant (recommended version 1.9.4 or later for concurrent JUnit test execution) or maven to build the java code
- gcc, cppunit and python-setuptools are required to build C and python bindings.

On RHEL machine:

```
yum install cppunit
yum install python-setuptools
```

On Ubuntu:

```
apt-get install cppunit
apt-get install python-setuptools
```


## Package build command (using maven)

Commands to clean everything and build the tarball package without executing the tests: `mvn clean install -DskipTests`


`zookeeper-assembly/target/apache-zookeeper-<version>-bin.tar.gz` tarball file structure layout:

- `/bin`    - User executables
- `/conf`   - Configuration files
- `/lib`    - ZooKeeper JAR files and all the required java library dependencies
- `/docs`   - Documents
  
Beside the binary tarball, you can find the whole original source project packaged into: 
`zookeeper-assembly/target/apache-zookeeper-<version>.tar.gz`


### Building the C client (using maven)

To also build the C client, you need to activate the `full-build` profile:
 
```
mvn clean -Pfull-build
mvn install -Pfull-build -DskipTests
```

Optional parameters you might consider when using maven:
-  `-Pfull-build`         -   activates the full-build profile, causing the C client to be built
-  `-DskipTests`          -   this parameter will skip both java and C++ unit test execution during the build
-  `-Pc-test-coverage`    -   activates the test coverage calculation during the execution of C client tests

Please note: if you don't provide the `-Pfull-build` parameter, then the C client will not be built, the C client tests
will not be executed and the previous C client builds will no be cleaned up (e.g. with simply using `mvn clean`).

The compiled C client can be found here: 
- `zookeeper-client/zookeeper-client-c/target/c/bin`                 - User executable
- `zookeeper-client/zookeeper-client-c/target/c/lib`                 - Native libraries
- `zookeeper-client/zookeeper-client-c/target/c/include/zookeeper`   - Native library headers

The same folders gets archived to the `zookeeper-assembly/target/apache-zookeeper-<version>-lib.tar.gz` file, assuming you activated the `full-build` maven profile.

## Package build command (using ant)

**Command to build tarball package:** `ant tar`

`zookeeper-<version>.tar.gz` tarball file structure layout:

- `/bin`              - User executable
- `/sbin`             - System executable
- `/libexec`          - Configuration boot trap script
- `/lib`              - Library dependencies
- `/docs`             - Documents
- `/share/zookeeper`  - Project files


**Command to build tarball package with native components:** `ant package-native tar`

`zookeeper-<version>-lib.tar.gz` tarball file structure layout:

- `/bin`                 - User executable
- `/lib`                 - Native libraries
- `/include/zookeeper`   - Native library headers

