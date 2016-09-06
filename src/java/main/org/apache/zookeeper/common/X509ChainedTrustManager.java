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
package org.apache.zookeeper.common;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class X509ChainedTrustManager extends X509ExtendedTrustManager {
    private static final Logger LOG = LoggerFactory.getLogger(
            X509ChainedTrustManager.class);

    public class TrustManagerWithPredicate {
        final X509ExtendedTrustManager trustManager;
        final Boolean predicate;
        public TrustManagerWithPredicate(
                final X509ExtendedTrustManager trustManager,
                final Boolean predicate) {
            this.trustManager = trustManager;
            this.predicate = predicate;
        }

        public X509ExtendedTrustManager getTrustManager() {
            return predicate ? trustManager : null;
        }
    }

    private final List<TrustManagerWithPredicate> trustManagerPredicateList;

    public X509ChainedTrustManager(
            final X509ExtendedTrustManager ... trustManagers) {
        final List<TrustManagerWithPredicate> trustManagerWithPredicateList =
                new ArrayList<>();
        for (final X509ExtendedTrustManager trustManager : trustManagers) {
            trustManagerWithPredicateList.add(
                    new TrustManagerWithPredicate(trustManager, true));
        }
        this.trustManagerPredicateList =
                Collections.unmodifiableList(trustManagerWithPredicateList);
    }

    public X509ChainedTrustManager(
            final TrustManagerWithPredicate ... trustManagerWithPredicates) {
        trustManagerPredicateList = Arrays.asList(trustManagerWithPredicates);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkClientTrusted(x509Certificates, s);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s) throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkServerTrusted(x509Certificates, s);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                        String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkClientTrusted(x509Certificates, s, socket);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                        String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final Socket socket)
            throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkServerTrusted(x509Certificates, s, socket);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                        String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkClientTrusted(x509Certificates, s, sslEngine);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                        String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates,
                                   final String s, final SSLEngine sslEngine)
            throws CertificateException {
        final List<String> expErrMsgList = new ArrayList<>();
        for (final TrustManagerWithPredicate trustManagerWithPredicate :
                trustManagerPredicateList) {
            if (trustManagerWithPredicate.getTrustManager() != null) {
                try {
                    trustManagerWithPredicate.getTrustManager()
                            .checkServerTrusted(x509Certificates, s, sslEngine);
                    return;  // means success, i.e no exception raised.
                } catch(CertificateException exp) {
                    // Ignore each exception except the one that is fatal!
                    if (exp instanceof  FatalCertificateException) {
                        final String errStr =
                                "Could not verify certificate chain: " +
                                        String.join(" ", expErrMsgList);
                        LOG.error("{}", errStr, exp);
                        throw new CertificateException(errStr, exp);
                    }
                    expErrMsgList.add(exp.getMessage());
                }
            }
        }
        final String errStr = "Could not verify certificate chain: " +
                String.join(" ", expErrMsgList);
        LOG.error(errStr);
        throw new CertificateException(errStr);
    }
}
