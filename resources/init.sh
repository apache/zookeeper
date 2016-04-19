#! /bin/bash

mdir="."
cdir="${mdir}/ca"
jdir="${mdir}/java"

rm -rf ${mdir}/java/* > /dev/null 2>&1
rm -rf ${cdir}/* > /dev/null 2>&1
rm -rf ${mdir}/newcerts/* > /dev/null 2>&1

rm ${mdir}/serial* > /dev/null 2>&1
rm ${mdir}/index* > /dev/null 2>&1

echo "01" > ${mdir}/serial
touch ${mdir}/index.txt

mkdir -p ${mdir}/ca > /dev/null 2>&1
mkdir -p ${mdir}/certs > /dev/null 2>&1
mkdir -p ${mdir}/crl > /dev/null 2>&1
mkdir -p ${mdir}/newcerts > /dev/null 2>&1
mkdir -p ${jdir} > /dev/null 2>&1

# Create CA
openssl req -x509 -config ${mdir}/openssl-ca.cnf -newkey rsa:2048 -sha256 -nodes -out ${cdir}/cacert.pem -keyout ${cdir}/cakey.pem -outform PEM

if [ $? -ne 0 ]; then
    echo "Creation of CA cert failed"
    exit 1
fi

# Import CA to truststore
keytool -import -file ${cdir}/cacert.pem -alias ca -keystore ${jdir}/truststore.ks -storepass StorePass

if [ $? -ne 0 ]; then
    echo "Import of CA to truststore failed"
    exit 1
fi

openssl x509 -in ${cdir}/cacert.pem -text -noout
