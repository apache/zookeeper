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

package org.apache.zookeeper;

import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

public class SSLCertCfg {
    private final CertType certType;
    private final MessageDigest certFingerPrint;
    public enum CertType {
        NONE, SELF, CA;
    }

    public SSLCertCfg() {
        certType = CertType.NONE;
        certFingerPrint = null;
    }

    public SSLCertCfg(final CertType certType,
                      final MessageDigest certFingerPrint) {
        this.certType = certType;
        this.certFingerPrint = certFingerPrint;
    }

    public boolean isSelfSigned() {
        return certType == CertType.SELF;
    }

    public boolean isCASigned() {
        return certType == CertType.CA;
    }

    public MessageDigest getCertFingerPrint() {
        return certFingerPrint;
    }

    public static SSLCertCfg parseCertCfgStr(final String certCfgStr)
            throws QuorumPeerConfig.ConfigException {
        SSLCertCfg.CertType certType = SSLCertCfg.CertType.NONE;
        int fpIndex = Integer.MAX_VALUE;
        final String[] parts = certCfgStr.split(":");
        final Map<String, Integer> propKvMap =
                getKeyAndIndexMap(certCfgStr);
        if (propKvMap.containsKey("cert") &&
                propKvMap.containsKey("cacert")) {
            final String errStr = "Server string has both self signed " +
                    "cert and ca cert: " + certCfgStr;
            throw new QuorumPeerConfig.ConfigException(errStr);
        } else if (propKvMap.containsKey("cert")) {
            certType = SSLCertCfg.CertType.SELF;
            fpIndex = propKvMap.get("cert") + 1;
            if (parts.length < fpIndex) {
                final String errStr = "No fingerprint provided for self " +
                        "signed, server cfg string: " + certCfgStr;
                throw new QuorumPeerConfig.ConfigException(errStr);
            }
        } else if (propKvMap.containsKey("cacert")) {
            certType = SSLCertCfg.CertType.SELF;
            fpIndex = propKvMap.get("cacert") + 1;
        }

        if (fpIndex != Integer.MAX_VALUE &&
                parts.length > fpIndex) {
            return new SSLCertCfg(certType,
                    getMessageDigest(parts[fpIndex]));
        }

        return new SSLCertCfg();
    }

    private static Map<String, Integer> getKeyAndIndexMap(
            final String cfgStr) {
        final Map<String, Integer> propKvMap = new HashMap<>();
        final String[] parts = cfgStr.split(":");
        for (int i = 0; i < parts.length; i++) {
            propKvMap.put(parts[i].trim().toLowerCase(), i);
        }

        return propKvMap;
    }

    /**
     * Given a fingerprint get the supported message digest object for that.
     * @param fp fingerprint.
     * @return MessageDigest, null on error
     */
    private static MessageDigest getMessageDigest(final String fp)
            throws QuorumPeerConfig.ConfigException {
        // Check for supported algos for the given fingerprint if cannot
        // validate throw exception.
        MessageDigest md = X509Util.getSupportedMessageDigestForFp(fp);
        if (md == null) {
            final String errStr = "Algo in fingerprint: " + fp +
                    " not supported, bailing out";
            throw new QuorumPeerConfig.ConfigException(errStr);
        }

        return md;
    }

    public static String getDigestToCertFp(final MessageDigest md) {
        return md.getAlgorithm().toLowerCase() + "-" +
                printHexBinary(md.digest()).toLowerCase();
    }
}
