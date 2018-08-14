                     Zookeeper C lock client library 


INSTALLATION

If you're building the client from a source checkout you need to
follow the steps outlined below. If you're building from a release
tar downloaded from Apache please skip to step 2.

1) make sure that you compile the main zookeeper c client library.
 
2) change directory to zookeeper-recipes/zookeeper-recipes-lock/src/main/c
    and do a "autoreconf -if" to bootstrap
   autoconf, automake and libtool. Please make sure you have autoconf
   version 2.59 or greater installed.
3) do a "./configure [OPTIONS]" to generate the makefile. See INSTALL
   for general information about running configure.

4) do a "make" or "make install" to build the libraries and install them. 
   Alternatively, you can also build and run a unit test suite (and
   you probably should).  Please make sure you have cppunit-1.10.x or
   higher installed before you execute step 4.  Once ./configure has
   finished, do a "make run-check". It will build the libraries, build
   the tests and run them.
5) to generate doxygen documentation do a "make doxygen-doc". All
   documentations will be placed to a new subfolder named docs. By
   default only HTML documentation is generated.  For information on
   other document formats please use "./configure --help"
