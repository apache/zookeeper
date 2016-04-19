Helpers to generate a root CA and generate a cert for each node and
create keystore for Zookeeper to consume.

Step 1:
Go to x509ca and run ../init.sh, this will generate root self-signed CA.
They will reside in ca/

Step 2:
remain in x509ca and run ../gencerts.sh node1

Look in QuorumSocketFactoryTest.java to see how they are accessed.

*NOTE* All of this might go away once I finish enhancing the UT to generate
certs at runtime. This does help with starting real ZK cluster however.

