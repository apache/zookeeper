#! /bin/bash

if [ -z "$1" ]; then
    echo "usage: $0 <nodename>"
    exit 1
fi
set -x
mdir="."
cdir="${mdir}/ca"
jdir="${mdir}/java"

# Remove nodeX stuff from java dir
rm -rf ${jdir}/$1* > /dev/null 2>&1

# Generate the node certificate
openssl req -config ${mdir}/openssl-$1.cnf -newkey rsa:2048 -sha256 -nodes -out ${jdir}/$1.csr -keyout ${jdir}/$1key.pem -outform PEM

if [ $? -ne 0 ]; then
    echo "error creating keys for ${1}"
    exit 1
fi

# Verify it.
openssl req -text -noout -verify -in ${jdir}/$1.csr

if [ $? -ne 0 ]; then
    echo "Failed to verify cert for $1"
    exit 1
fi

# Sign the cert
openssl ca -config ${mdir}/openssl-ca.cnf -policy signing_policy -extensions signing_req -in ${jdir}/$1.csr -out ${jdir}/$1.pem

if [ $? -ne 0 ]; then
    echo "error siging the csr for ${1}"
    exit 1
fi

# Output pem cert to PCKS12 store along with the CA cert.
openssl pkcs12 -export -out ${jdir}/$1.p12 -inkey ${jdir}/$1key.pem -in ${jdir}/$1.pem -certfile ${mdir}/ca/cacert.pem -name $1

if [ $? -ne 0 ]; then
    echo "error combining private key and cert along with CA for ${1}"
    exit 1
fi

# Convert PCKS12 keystore into a JSK keystore
keytool -importkeystore -deststorepass CertPassword1 -destkeypass CertPassword1 -destkeystore ${jdir}/$1.ks -srckeystore ${jdir}/$1.p12 -srcstoretype PKCS12 -srcstorepass CertPassword1 -alias $1

if [ $? -ne 0 ]; then
    echo "error Creating the keystore for ${1}"
    exit 1
fi
