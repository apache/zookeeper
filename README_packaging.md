# README file for Packaging Notes

The ZooKeeper project publishes releases as tarballs.  For ZooKeeper packages
specific to your OS (such as rpm and deb), consider using Apache Bigtop:

http://bigtop.apache.org/


## Requirements

- you need Maven to build the Java code
- gcc, cppunit, openssl and python-setuptools are required to build C and Python bindings (only needed when using `-Pfull-build`).  Cyrus SASL is optional, but recommended for a maximally functional client.

On RHEL machine:

```
yum install cppunit
yum install python-setuptools
yum install openssl openssl-devel
yum install cyrus-sasl-md5 cyrus-sasl-gssapi cyrus-sasl-devel
```

On Ubuntu (in case of 16.4+):

```
apt-get install libcppunit-dev
apt-get install python-setuptools python2.7-dev
apt-get install openssl libssl-dev
apt-get install libsasl2-modules-gssapi-mit libsasl2-modules libsasl2-dev
```


## Package build command (using Maven)

Commands to clean everything and build the tarball package without executing the tests: `mvn clean install -DskipTests`


`zookeeper-assembly/target/apache-zookeeper-<version>-bin.tar.gz` tarball file structure layout:

- `/bin`    - User executables
- `/conf`   - Configuration files
- `/lib`    - ZooKeeper JAR files and all the required Java library dependencies
- `/docs`   - Documents
  
Beside the binary tarball, you can find the whole original source project packaged into: 
`zookeeper-assembly/target/apache-zookeeper-<version>.tar.gz`


### Building the C client (using Maven)

To build the C client, you need to activate the `full-build` profile:
 
```
mvn clean -Pfull-build
mvn install -Pfull-build -DskipTests
```

Optional parameters you might consider when using Maven:
-  `-Pfull-build`         -   activates the full-build profile, causing the C client to be built
-  `-DskipTests`          -   skips executing both Java, and C++, unit tests during the build
-  `-Pc-test-coverage`    -   activates the test-coverage calculation during the execution of C client tests
-  `-Dc-client-openssl`   -   specifies SSL support and the openssl library location. Default value: `yes`, results in 
                              the auto-detection of the openssl library. If the openssl library is not found, 
                              then a warning will be shown, and the C client will be compiled without SSL support.
                              Use `-Dc-client-openssl=no` to explicitly disable SSL support in C client. Or use 
                              `-Dc-client-openssl=/path/to/openssl/` to specify the openssl library location.
-  `-Dc-client-sasl`      -   specifies SASL support and Cyrus SASL 1.x library location.  Works similarly to the
                              `c-client-openssl` flag above (`yes`, `no`, or path).

Please note: If you don't provide the `-Pfull-build` parameter, then the C client will not be built, the C client tests
will not be executed, and the previous C client builds will not be deleted.

The compiled C client can be found here: 
- `zookeeper-client/zookeeper-client-c/target/c/bin`                 - User executable
- `zookeeper-client/zookeeper-client-c/target/c/lib`                 - Native libraries
- `zookeeper-client/zookeeper-client-c/target/c/include/zookeeper`   - Native library headers

The same folders get archived to the `zookeeper-assembly/target/apache-zookeeper-<version>-lib.tar.gz` file, assuming 
you activated the `full-build` Maven profile.
