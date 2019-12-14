#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# This script cleans up old transaction logs and snapshots
#

#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#


# determining the domain name in the certificates:
# - use the first commandline argument, if present
# - if not, then use the fully qualified domain name
# - if `hostname` command fails, fall back to zookeeper.apache.org
FQDN=`hostname -f`
FQDN=${1:-$FQDN}
FQDN=${FQDN:-"zookeeper.apache.org"}

# Generate the root key
openssl genrsa -out rootkey.pem 2048

#Generate the root Cert
openssl req -x509 -new -key rootkey.pem -out root.crt -config <(
cat <<-EOF
[ req ]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn

[ dn ]
C = US
ST = California
L = San Francisco
O = ZooKeeper
emailAddress = dev@$FQDN
CN = $FQDN
EOF
)

#Generate Client Key
openssl genrsa -out clientkey.pem 2048

#Generate Client Cert
openssl req -new -key clientkey.pem -out client.csr -config <(
cat <<-EOF
[ req ]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn

[ dn ]
C = US
ST = California
L = San Francisco
O = ZooKeeper
emailAddress = dev@$FQDN
CN = $FQDN
EOF
)
openssl x509 -req -in client.csr -CA root.crt -CAkey rootkey.pem -CAcreateserial -days 3650 -out client.crt

#Export in pkcs12 format
openssl pkcs12 -export -in client.crt -inkey clientkey.pem -out client.pkcs12 -password pass:password

# Import Keystore in JKS
keytool -importkeystore -srckeystore client.pkcs12 -destkeystore client.jks -srcstoretype pkcs12 -srcstorepass password -deststorepass password

############################################################

#Generate Server key
openssl genrsa -out serverkey.pem 2048

#Generate Server Cert
openssl req -new -key serverkey.pem -out server.csr -config <(
cat <<-EOF
[ req ]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn

[ dn ]
C = US
ST = California
L = San Francisco
O = ZooKeeper
emailAddress = dev@$FQDN
CN = $FQDN
EOF
)
openssl x509 -req -in server.csr -CA root.crt -CAkey rootkey.pem -CAcreateserial -days 3650 -out server.crt

#Export in pkcs12 format
openssl pkcs12 -export -in server.crt -inkey serverkey.pem -out server.pkcs12 -password pass:password

# Import Keystore in JKS
keytool -importkeystore -srckeystore server.pkcs12 -destkeystore server.jks -srcstoretype pkcs12 -srcstorepass password -deststorepass password


keytool -importcert -keystore server.jks -file root.crt -storepass password -noprompt

keytool -importcert -alias ca -file root.crt -keystore clienttrust.jks -storepass password -noprompt

keytool -importcert -alias clientcert -file client.crt -keystore clienttrust.jks -storepass password -noprompt

keytool -importcert -alias ca -file root.crt -keystore servertrust.jks -storepass password -noprompt
keytool -importcert -alias servercert -file server.crt -keystore servertrust.jks -storepass password -noprompt
