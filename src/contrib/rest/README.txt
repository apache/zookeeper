ZooKeeper REST implementation using Jersey JAX-RS.

This is an implementation of version 1 of the ZooKeeper REST spec.

Note: This interface is currently experimental, may change at any time,
etc... In general you should be using the Java/C client bindings to access
the ZooKeeper server.

See SPEC.txt for details on the REST binding.

-----------
Quickstart:

1) start a zookeeper server on localhost port 2181

2) run "ant runrestserver"

3) use a REST client to access the data (see below for more details)

  curl http://localhost:9998/znodes/v1/

or use the provided src/python scripts 

  zk_dump_tree.py


----------
Tests:

1) the full testsuite can be run via "ant test" target
