README file for Packaging Notes

Requirement
-----------

ant (recommended version 1.9.4 or later for concurrent JUnit test execution)
gcc, cppunit and python-setuptools are required to build 
C and python bindings.

On RHEL machine:

yum install cppunit
yum install python-setuptools

On Ubuntu:

apt-get --install cppunit
apt-get --install python-setuptools

Package build command
---------------------

The ZooKeeper project publishes releases as tarballs.  For ZooKeeper packages
specific to your OS (such as rpm and deb), consider using Apache Bigtop:

http://bigtop.apache.org/

Command to build tarball package: ant tar

zookeeper-<version>.tar.gz tarball file structure layout

  /bin                               - User executable
  /sbin                              - System executable
  /libexec                           - Configuration boot trap script
  /lib                               - Library dependencies
  /docs                              - Documents
  /share/zookeeper                   - Project files

Command to build tarball package with native components: ant package-native tar

zookeeper-<version>-lib.tar.gz tarball file structure layout

  /bin                               - User executable
  /lib                               - Native libraries
  /include/zookeeper                 - Native library headers
