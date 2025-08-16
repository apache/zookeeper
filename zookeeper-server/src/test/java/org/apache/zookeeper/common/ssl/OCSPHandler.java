/*
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

package org.apache.zookeeper.common.ssl;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Map;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus;
import org.bouncycastle.asn1.ocsp.RevokedInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.jcajce.JcaCertificateID;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OCSPHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OCSPHandler.class);

    private final Ca ca;

    public OCSPHandler(Ca ca) {
        this.ca = ca;
    }

    @Override
    public void handle(com.sun.net.httpserver.HttpExchange httpExchange) throws IOException {
        byte[] responseBytes;
        try {
            String uri = httpExchange.getRequestURI().toString();
            LOG.info("OCSP request: {} {}", httpExchange.getRequestMethod(), uri);
            httpExchange.getRequestHeaders().entrySet().forEach((e) -> {
                LOG.info("OCSP request header: {} {}", e.getKey(), e.getValue());
            });
            InputStream request = httpExchange.getRequestBody();
            byte[] requestBytes = new byte[10000];
            int len = request.read(requestBytes);
            LOG.info("OCSP request size {}", len);

            if (len < 0) {
                String removedUriEncoding = URLDecoder.decode(uri.substring(1), "utf-8");
                LOG.info("OCSP request from URI no encoding {}", removedUriEncoding);
                requestBytes = Base64.getDecoder().decode(removedUriEncoding);
            }
            OCSPReq ocspRequest = new OCSPReq(requestBytes);
            Req[] requestList = ocspRequest.getRequestList();
            LOG.info("requestList {}", Arrays.toString(requestList));

            DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1);

            Map<CertificateID, RevokedInfo> revokedCerts = ca.ocspRevokedCerts.entrySet().stream().collect(Collectors.toMap(entry -> {
                try {
                    return new JcaCertificateID(digestCalculator, ca.cert, entry.getKey().getSerialNumber());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, Map.Entry::getValue));

            BasicOCSPRespBuilder responseBuilder = new JcaBasicOCSPRespBuilder(ca.key.getPublic(), digestCalculator);
            for (Req req : requestList) {
                CertificateID certId = req.getCertID();
                CertificateStatus certificateStatus = CertificateStatus.GOOD;
                RevokedInfo revokedInfo = revokedCerts.get(certId);
                if (revokedInfo != null) {
                    certificateStatus = new RevokedStatus(revokedInfo);
                }
                responseBuilder.addResponse(certId, certificateStatus, null);
            }

            X509CertificateHolder[] chain = new X509CertificateHolder[]{new JcaX509CertificateHolder(ca.cert)};
            ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA").setProvider("BC").build(ca.key.getPrivate());
            BasicOCSPResp ocspResponse = responseBuilder.build(signer, chain, Calendar.getInstance().getTime());
            LOG.info("response {}", ocspResponse);
            responseBytes = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, ocspResponse).getEncoded();
            LOG.error("OCSP server response OK");
        } catch (Throwable exception) {
            LOG.error("Internal OCSP server error", exception);
            responseBytes = new OCSPResp(new OCSPResponse(new OCSPResponseStatus(OCSPRespBuilder.INTERNAL_ERROR), null)).getEncoded();
        }

        Headers rh = httpExchange.getResponseHeaders();
        rh.set("Content-Type", "application/ocsp-response");
        httpExchange.sendResponseHeaders(200, responseBytes.length);

        OutputStream os = httpExchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

}
