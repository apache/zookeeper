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


----------
Examples Using CURL

First review the spec SPEC.txt in this directory.

#get the root node data
curl http://localhost:9998/znodes/v1/

#get children of the root node
curl http://localhost:9998/znodes/v1/?view=children

#get "/cluster1/leader" as xml (default is json)
curl -H'Accept: application/xml' http://localhost:9998/znodes/v1/cluster1/leader

#get the data as text
curl -w "\n%{http_code}\n" "http://localhost:9998/znodes/v1/cluster1/leader?dataformat=utf8"

#set a node (data.txt contains the ascii text you want to set on the node)
curl -T data.txt -w "\n%{http_code}\n" "http://localhost:9998/znodes/v1/cluster1/leader?dataformat=utf8"

#create a node
curl -d "data1" -H'Content-Type: application/octet-stream' -w "\n%{http_code}\n" "http://localhost:9998/znodes/v1/?op=create&name=cluster2&dataformat=utf8"
curl -d "data2" -H'Content-Type: application/octet-stream' -w "\n%{http_code}\n" "http://localhost:9998/znodes/v1/cluster2?op=create&name=leader&dataformat=utf8"
