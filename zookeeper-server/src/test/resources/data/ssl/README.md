SSL test data
===================

Create keystore with certificate
```
keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -dname "cn=localhost" -keypass testpass -keystore keystore.jks -storepass testpass -ext SAN=DNS:localhost,IP:127.0.0.1
```

Export certificate to file
```
keytool -exportcert -alias test -keystore keystore.jks -file test.cer -rfc
```

Create truststore
```
keytool -importcert -alias test -file test.cer -keystore truststore.jks -storepass testpass
```

testKeyStore.jks
---
Testing keystore, password is "testpass".

testTrustStore.jks
---
Testing truststore, password is "testpass".
