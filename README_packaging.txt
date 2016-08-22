README file for Packaging Notes

Requirement
-----------

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

Command to build Debian package: ant deb
Command to build RPM Package: ant rpm

rpm and deb packages are generated and placed in:

build/zookeeper*.[rpm|deb]
build/contrib/**.[rpm|deb]

Default package file structure layout

  /usr/bin                           - User executable
  /usr/sbin                          - System executable
  /usr/libexec                       - Configuration boot trap script
  /usr/lib                           - Native libraries
  /usr/share/doc/zookeeper           - Documents
  /usr/share/zookeeper               - Project files
  /usr/share/zookeeper/template/conf - Configuration template files
  /etc/zookeeper                     - Configuration files
  /etc/init.d/zookeeper              - OS startup script

Source file structure layout
---------------------

src/packages/update-zookeeper-env.sh 
  - setup environment variables and symlink $PREFIX/etc/zookeeper to 
    /etc/zookeeper.
  - This script is designed to run in post installation, and pre-remove 
    phase of ZooKeeper package.
  - Run update-zookeeper-env.sh -h to get a list of supported parameters.

src/packages/template
  - Standard configuration template

src/packages/deb 
  Meta data for creating Debian package

src/packages/deb/init.d
  Daemon start/stop script for Debian flavor of Linux

src/packages/rpm 
  Meta data for creating RPM package

src/packages/rpm/init.d
  Daemon start/stop script for Redhat flavor of Linux
