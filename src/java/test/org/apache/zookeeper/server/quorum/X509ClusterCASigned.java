/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.quorum;


import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.operator.OperatorCreationException;

public class X509ClusterCASigned extends X509ClusterBase {

    public X509ClusterCASigned(final String clusterName,
                               final Path basePath,
                               final int clusterSize) {
        super(clusterName, basePath, clusterSize);
    }

    @Override
    protected void initCerts() {
        try {
            initCertSafe("good", true);
            initCertSafe("bad", false);
        } catch (Exception exp) {
            final String errStr = "Could not create certs";
            LOG.error("{}", errStr, exp);
            throw new RuntimeException(errStr, exp);
        }
    }

    private void initCertSafe(final String prefix, final boolean isGood)
            throws NoSuchAlgorithmException,
            CertificateException, OperatorCreationException, IOException,
            KeyStoreException {
        final KeyPair caKeyPair = createRSAKeyPair();
        final X509Certificate caCert = buildRootCert(caKeyPair);

        for (int i = 0; i < clusterSize; i++) {
            final KeyPair nodeKeyPair = createRSAKeyPair();
            final X509Certificate nodeCert = buildEndEntityCert(
                    prefix+"_"+NODE_PREFIX + (i+1), caCert,
                    caKeyPair.getPrivate(), nodeKeyPair);
            final Pair<Path, String> p = buildKeyStore(prefix, i+1, nodeKeyPair,
                    nodeCert);
            if (isGood) {
                keyStoreList.add(p.getLeft());
                keyStorePasswordList.add(p.getRight());
            } else {
                badKeyStoreList.add(p.getLeft());
                badKeyStorePasswordList.add(p.getRight());
            }
        }

        final Pair<Path, String> p = buildTrustStore(prefix, caKeyPair, caCert);
        if (isGood) {
            trustStore = p.getLeft();
            trustStorePassword = p.getRight();
        } else {
            badTrustStore = p.getLeft();
            badTrustStorePassword = p.getRight();
        }
    }
}
