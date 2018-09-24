#!/bin/bash
openssl genrsa -out rootkey.pem 2048
openssl req -x509 -new -key rootkey.pem -out root.crt
openssl genrsa -out clientkey.pem 2048
openssl req -new -key clientkey.pem -out client.csr
openssl x509 -req -in client.csr -CA root.crt -CAkey rootkey.pem -CAcreateserial -days 3650 -out client.crt
openssl genrsa -out serverkey.pem 2048
openssl req -new -key serverkey.pem -out server.csr
openssl x509 -req -in server.csr -CA root.crt -CAkey rootkey.pem -CAcreateserial -days 3650 -out server.crt
openssl pkcs12 -export -in client.crt -inkey clientkey.pem -out client.pkcs12
openssl pkcs12 -export -in server.crt -inkey serverkey.pem -out server.pkcs12
keytool -importkeystore -srckeystore client.pkcs12 -destkeystore client.jks -srcstoretype pkcs12
keytool -importkeystore -srckeystore server.pkcs12 -destkeystore server.jks -srcstoretype pkcs12
keytool -importcert -keystore server.jks -file root.crt
keytool -importcert -alias ca -file root.crt -keystore clienttrust.jks
keytool -importcert -alias clientcert -file client.crt -keystore clienttrust.jks
keytool -importcert -alias ca -file root.crt -keystore servertrust.jks
keytool -importcert -alias servercert -file server.crt -keystore servertrust.jks
