#!/usr/bin/env bash

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
O = Bookkeeper
emailAddress = dev@bookkeeper.apache.org
CN = bookkeeper.apache.org
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
O = Bookkeeper
emailAddress = dev@bookkeeper.apache.org
CN = bookkeeper.apache.org
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
O = Bookkeeper
emailAddress = dev@bookkeeper.apache.org
CN = bookkeeper.apache.org
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
