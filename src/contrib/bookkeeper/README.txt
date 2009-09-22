BookKeeper README

1- Overview
BookKeeper is a highly available logging service. As many critical services rely upon write-ahead logs to provide persistence along with high performance, an alternative to make such a service highly available despite the failures of individual servers it to offload write-ahead logs to an external service. 

This is exactly what BookKeeper provides. With BookKeeper, a service (or application) writes to a set of servers dedicated to storing such logs. An example of such an application is the Namenode of the Hadoop Distributed File System. 

The main components of BookKeeper are:
* Client: Applications interact with BookKeeper through the interface of of a BookKeeper client;
* Ledger: A ledger is our equivalent to a log file. Clients read entries from and write entries to ledgers;  
* Bookie: Bookies are BookKeeper servers and they store the content of ledgers. Typically there are multiple bookies implementing a ledger.

2- How to compile
Run "ant" from "trunk/contrib/bookkeeper". This will generate the bookkeeper jar in "trunk/build/contrib/bookkeeper".

3- Setting up

A typical BookKeeper configuration includes a set of bookies and a ZooKeeper ensemble, where the ZooKeeper instance stores metadata for BookKeeper. As an example of such metadata, BookKeeper clients learn about available bookies by consulting a ZooKeeper service. 

To set up BookKeeper, follow these steps:
* Once bookies and ZooKeeper servers are running, create two znodes: "/ledgers" and "/ledgers/available". 
* To run a bookie, run the java class "org.apache.bookkeeper.proto.BookieServer". It takes 3 parameters: a port, one directory path for transaction logs, and one directory path for indexes and data. Here is an example: java -cp .:bookkeeper.jar:../ZooKeeper/zookeeper-<version>.jar:/usr/local/apache-log4j-1.2.15/log4j-1.2.15.jar -Dlog4j.configuration=log4j.properties org.apache.bookkeeper.proto.BookieServer 3181 /disk1/bk/ /disk2/bk/
* For each bookie b, if <host> is the host name of b and <port> is the bookie port, then create a znode "/ledgers/available/<host>:<port>".
* It is ready to run! 

For test purposes, there is a class named "org.apache.bookkeeper.util.LocalBookkeeper" which runs a custom number on BookKeeper servers, along with a ZooKeeper server, on a single node. A typical invocation would be: 
java -cp:<classpath> org.apache.bookkeeper.util.LocalBookKeeper <number-of-bookies>

4- Developing applications

BookKeeper is written in Java. When implementing an application that uses BookKeeper, follow these steps:

a. Instantiate a BookKeeper object. The single parameter to the BookKeeper constructor is a list of ZooKeeper servers;
b. Once we have a BookKeeper object, we can create a ledger with createLedger. The default call to createLedger takes a single parameter, which is supposed to be for password authentication, but currently it has no effect. A call to createLedger returns a ledger handle (type LedgerHandle);
c. Once we have a ledger, we can write to the ledger by calling either addEntry or asyncAddEntry. The first call is synchronous, whereas the second call is asynchronous, and both write byte arrays as entries. To use the asynchronous version, the application has to implement the AddCallback interface;
d. Ideally, once the application finishes writing to the ledger, it should close it by calling close on the ledger handle. If it doesn't then BookKeeper will try to recover the ledger when a client tries to open it. By closing the ledger properly, we avoid this recovery step, which is recommended but not mandatory;
e. Before reading from a ledger, a client has to open it by calling openLedger on a BookKeeper object, and readEntries or asycnReadEntries to read entries. Both read calls take as input two entry numbers, n1 and n2, and return all entries from n1 through n2.   

Here is a simple example of a method that creates a BookKeeper object, creates a ledger, writes an entry to the ledger, and closes it:

BookKeeper bk;
LedgerHandle lh;

public void allInOne(String servers) throws KeeperException, IOException, InterruptedException{
        bk = new BookKeeper(servers);
        try{
          lh = bk.createLedger(new byte[] {'a', 'b'});
          bk.addEntry(lh, new byte[]{'a', 'b'});
          bk.close(lh);
        } catch (BKException e) {
            e.printStackTrace();
        }
    }

5- Selecting quorum mode and number of bookies (advanced)

There are two methods to store ledgers with BookKeeper:

a. Self-verifying: Each entry includes a digest that is used to guarantee that upon a read, the value read is the same as the one written. This mode requires n > 2t bookies, and quorums of size t + 1. By default, a call to createLedger uses this method and 3 servers;
b. Generic: Entries do not include a digest, and it requires more replicas: n > 3t and quorums of size 2t + 1. 

The quorum mode and number of bookies can be selected through the createLedger method.
