Original authors of zkfuse are Swee Lim & Bartlomiej M Niechwiej of Yahoo.
'
ZooKeeper FUSE (File System in Userspace)
=========================================

Pre-requisites
--------------
1. Linux system with 2.6.X kernel.
2. Fuse (Filesystem in Userspace) must be installed on the build node. 
3. Development build libraries:
  a. fuse
  b. log4cxx
  c. pthread

Build instructions
------------------
1. cd into this directory
2. autoreconf -if
3. ./configure
4. make
5. zkfuse binary is under the src directory

Testing Zkfuse
--------------
1. Depending on permission on /dev/fuse, you may need to sudo -u root.
   * If /dev/fuse has permissions 0600, then you have to run Zkfuse as root.
   * If /dev/fuse has permissions 0666, then you can run Zkfuse as any user.
2. Create or find a mount point that you have "rwx" permission. 
   * e.g. mkdir -p /tmp/zkfuse
3. Run Zkfuse as follows:
   zkfuse -z <hostspec> -m /tmp/zkfuse -d
   -z specifies ZooKeeper address(es) <host>:<port>
   -m specifies the mount point
   -d specifies the debug mode.
   For additional command line options, try "zkfuse -h".

FAQ
---
Q. How to fix "warning: macro `AM_PATH_CPPUNIT' not found in library"?
A. * install cppunit (src or pkg) on build machine

Q. Why can't Zkfuse cannot write to current directory?
A. * If Zkfuse is running as root on a NFS mounted file system, it will not
     have root permissions because root user is mapped to another user by
     NFS admin.
   * If you run Zkfuse as root, it is a good idea to run Zkfuse from a
     directory that you have write access to. This will allow core files
     to be saved.

Q. Why Zkfuse cannot mount?
A. * Check that the mount point exists and you have "rwx" permissions.
   * Check that previous mounts have been umounted. If Zkfuse does not 
     exit cleanly, its mount point may have to be umounted manually. 
     If you cannot umount manually, make sure that there no files is open 
     within the mount point.

Q. Why does Zkfuse complain about logging at startup?
A. * Zkfuse uses log4cxx for logging. It is looking for log4cxx.properties
     file to obtain its logging configuration.
   * There is an example log4cxx.properties file in the Zkfuse source 
     directory.

