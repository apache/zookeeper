#!/bin/bash -x

exec java -Xmx512m -Dtest.junit.threads=1 -Dbuild.test.dir=build/test/tmp -Dlog4j.configuration=file:conf/log4j.properties -Dtest.data.dir=build/test/data -Dzookeeper.DigestAuthenticationProvider.superDigest=super:D/InIHSb7yEEbrWz8b9l71RjZJU= -classpath build/test/classes:build/test/lib/accessors-smart-1.2.jar:build/test/lib/ant-1.10.5.jar:build/test/lib/ant-junit-1.10.5.jar:build/test/lib/ant-junit4-1.10.5.jar:build/test/lib/ant-launcher-1.10.5.jar:build/test/lib/antlr-2.7.7.jar:build/test/lib/antlr4-runtime-4.5.3.jar:build/test/lib/asm-5.0.4.jar:build/test/lib/audience-annotations-0.5.0.jar:build/test/lib/bcpkix-jdk15on-1.60.jar:build/test/lib/bcprov-jdk15on-1.60.jar:build/test/lib/checkstyle-7.1.2.jar:build/test/lib/commons-beanutils-1.9.3.jar:build/test/lib/commons-cli-1.2.jar:build/test/lib/commons-collections-3.2.2.jar:build/test/lib/commons-io-2.6.jar:build/test/lib/commons-logging-1.2.jar:build/test/lib/guava-19.0.jar:build/test/lib/hamcrest-all-1.3.jar:build/test/lib/hamcrest-core-1.3.jar:build/test/lib/jackson-annotations-2.9.0.jar:build/test/lib/jackson-core-2.9.7.jar:build/test/lib/jackson-databind-2.9.7.jar:build/test/lib/javax.servlet-api-3.1.0.jar:build/test/lib/jcip-annotations-1.0-1.jar:build/test/lib/jetty-http-9.4.14.v20181114.jar:build/test/lib/jetty-io-9.4.14.v20181114.jar:build/test/lib/jetty-security-9.4.14.v20181114.jar:build/test/lib/jetty-server-9.4.14.v20181114.jar:build/test/lib/jetty-servlet-9.4.14.v20181114.jar:build/test/lib/jetty-util-9.4.14.v20181114.jar:build/test/lib/jline-2.11.jar:build/test/lib/json-smart-2.3.jar:build/test/lib/junit-4.12.jar:build/test/lib/kerb-admin-1.1.0.jar:build/test/lib/kerb-client-1.1.0.jar:build/test/lib/kerb-common-1.1.0.jar:build/test/lib/kerb-core-1.1.0.jar:build/test/lib/kerb-crypto-1.1.0.jar:build/test/lib/kerb-identity-1.1.0.jar:build/test/lib/kerb-server-1.1.0.jar:build/test/lib/kerb-simplekdc-1.1.0.jar:build/test/lib/kerb-util-1.1.0.jar:build/test/lib/kerby-asn1-1.1.0.jar:build/test/lib/kerby-config-1.1.0.jar:build/test/lib/kerby-pkix-1.1.0.jar:build/test/lib/kerby-util-1.1.0.jar:build/test/lib/kerby-xdr-1.1.0.jar:build/test/lib/log4j-1.2.17.jar:build/test/lib/mockito-core-2.23.4.jar:build/test/lib/byte-buddy-agent-1.9.3.jar:build/test/lib/byte-buddy-1.9.3.jar:build/test/lib/objenesis-2.6.jar:build/test/lib/netty-3.10.6.Final.jar:build/test/lib/nimbus-jose-jwt-4.41.2.jar:build/test/lib/slf4j-api-1.7.25.jar:build/test/lib/slf4j-log4j12-1.7.25.jar:build/test/lib/token-provider-1.1.0.jar:build/classes org.apache.tools.ant.taskdefs.optional.junit.JUnitTestRunner "$@" skipNonTests=false filtertrace=true haltOnError=false haltOnFailure=false showoutput=true outputtoformatters=true logfailedtests=true threadid=0 logtestlistenerevents=true formatter=com.undefined.testing.OneLinerFormatter
