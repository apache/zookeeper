==========================================
zktreeutil - Zookeeper Tree Data Utility
Author: Anirban Roy
Organization: Yahoo Inc.
==========================================

zktreeutil program is intended to manage and manipulate zk-tree data quickly, effi-
ciently and with ease. The utility operates on free-form ZK-tree and hence can be used
for any cluster managed by Zookeeper. Here are the basic functionalities -

EXPORT: The whole/partial ZK-tree is exported into a XML file. This helps in
capturing a current snapshot of the data for backup/analysis. For a subtree
export, one need to specify the path to the ZK-subtree with proper option.

IMPORT: The ZK-tree can be imported from XML into ZK cluster. This helps in priming
the new ZK cluster with static configuration. The import can be non-intrusive by
making only the additions in the existing data. The import of subtree is also
possible by optionally providing the path to the ZK-subtree.

DIFF: Creates a diff between live ZK data vs data saved in XML file. Diff can ignore
some ZK-tree branches (possibly dynamic data) on reading the optional ignore flag
from XML file. Diffing on a ZK-subtree achieved by providing path to ZK-subtree with
diff command.

UPDATE: Make the incremental changes into the live ZK-tree from saved XML, essentia-
lly after running the diff.

DUMP: Dumps the ZK-tree on the standard output device reading either from live ZK
server or XML file. Like export, ZK-subtree can be dumped with optionaly
providing the path to the ZK-subtree, and till a certain depth of the (sub)tree.

The exported ZK data into XML file can be shortened by only keeping the static ZK
nodes which are required to prime a cluster. The dynamic zk nodes (created on-the-
fly) can be ignored by setting a 'ignore' attribute at the root node of the dynamic
subtree (see tests/zk_sample.xml), possibly deleting all inner ZK nodes under that.
Once ignored, the whole subtree is ignored during DIFF, UPDATE and WRITE.

Pre-requisites
--------------
1. Linux system with 2.6.X kernel.
2. Zookeeper C client library (locally built at ../../c/.libs) >= 3.X.X
3. Development build libraries (rpm packages):
  a. boost-devel >= 1.32.0
  b. libxml2-devel >= 2.7.3
  c. log4cxx0100-devel >= 0.10.0

Build instructions
------------------
1. cd into this directory
2. autoreconf -if
3. ./configure
4. make
5. 'zktreeutil' binary created under src directory

Limitations
-----------
Current version works with text data only, binary data will be supported in future
versions.

Testing  and usage of zktreeutil
--------------------------------
1.  Run Zookeeper server locally on port 2181
2.  export LD_LIBRARY_PATH=../../c/.libs/:/usr/local/lib/
3.  ./src/zktreeutil --help # show help
4.  ./src/zktreeutil --zookeeper=localhost:2181 --import --xmlfile=tests/zk_sample.xml 2>/dev/null                 # import sample ZK tree
5.  ./src/zktreeutil --zookeeper=localhost:2181 --dump --path=/myapp/version-1.0 2>/dev/null                         # dump Zk subtree 
5.  ./src/zktreeutil --zookeeper=localhost:2181 --dump --depth=3 2>/dev/null                                                 # dump Zk tree till certain depth
6.  ./src/zktreeutil --xmlfile=zk_sample.xml -D 2>/dev/null                                                                     # dump the xml data
7.  Change zk_sample.xml with adding/deleting/chaging some nodes 
8.  ./src/zktreeutil -z localhost:2181 -F -x zk_sample.xml -p /myapp/version-1.0/configuration 2>/dev/null          # take a diff of changes
9.  ./src/zktreeutil -z localhost:2181 -E 2>/dev/null > zk_sample2.xml                                                         # export the mofied ZK tree
10. ./src/zktreeutil -z localhost:2181 -U -x zk_sample.xml -p /myapp/version-1.0/distributions 2>/dev/null        # update with incr. changes
11. ./src/zktreeutil --zookeeper=localhost:2181 --import --force --xmlfile=zk_sample2.xml 2>/dev/null             # re-prime the ZK tree

