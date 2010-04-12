For the latest information about ZooKeeper, please visit our website at:

   http://hadoop.apache.org/zookeeper/

and our wiki, at:

   http://wiki.apache.org/hadoop/ZooKeeper

Full documentation for this release can also be found in docs/index.html

---------------------------
Packaging/release artifacts

The release artifact contains the following jar files:

zookeeper-<version>.jar         - legacy jar file which contains all classes
                                  and source files. Prior to version 3.3.0 this
                                  was the only jar file available. It has the 
                                  benefit of having the source included (for
                                  debugging purposes) however is also larger as
                                  a result

zookeeper-<version>-bin.jar     - contains only class (*.class) files
zookeeper-<version>-sources.jar - contains only src (*.java) files
zookeeper-<version>-javadoc.jar - contains only javadoc files

The bin/src/javadoc jars were added specifically to support Maven/Ivy which have 
the ability to pull these down automatically as part of your build process. 
The content of the legacy jar and the bin+sources jar are the same.

As of version 3.3.0 bin/sources/javadoc jars are deployed to the Apache Maven 
repository: http://people.apache.org/repo/m2-ibiblio-rsync-repository/
