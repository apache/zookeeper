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

package org.apache.zookeeper.common;

/**
 * Some X509 certificates to test against.
 * <p>
 * Note:  some of these certificates have Japanese Kanji in the "subjectAlt"
 * field (UTF8).  Not sure how realistic that is since international characters
 * in DNS names usually get translated into ASCII using "xn--" style DNS
 * entries.  "xn--i8s592g.co.jp" is what FireFox actually uses when trying to
 * find &#x82b1;&#x5b50;.co.jp.  So would the CN in the certificate contain
 * "xn--i8s592g.co.jp" in ASCII, or "&#x82b1;&#x5b50;.co.jp" in UTF8?  (Both?)
 * </p>
 *
 * @since 11-Dec-2006
 */
public class CertificatesToPlayWith {

    /**
     * CN=foo.com
     */
    public static final byte[] X509_FOO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIERjCCAy6gAwIBAgIJAIz+EYMBU6aQMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzE0MVoXDTI4MTEwNTE1MzE0MVowgaQx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs\n"
        + "aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC\n"
        + "ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B\n"
        + "lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy\n"
        + "zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY\n"
        + "07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8\n"
        + "BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV\n"
        + "JTxpTKqym93whYk93l3ocEe55c0CAwEAAaN7MHkwCQYDVR0TBAIwADAsBglghkgB\n"
        + "hvhCAQ0EHxYdT3BlblNTTCBHZW5lcmF0ZWQgQ2VydGlmaWNhdGUwHQYDVR0OBBYE\n"
        + "FJ8Ud78/OrbKOIJCSBYs2tDLXofYMB8GA1UdIwQYMBaAFHua2o+QmU5S0qzbswNS\n"
        + "yoemDT4NMA0GCSqGSIb3DQEBBQUAA4IBAQC3jRmEya6sQCkmieULcvx8zz1euCk9\n"
        + "fSez7BEtki8+dmfMXe3K7sH0lI8f4jJR0rbSCjpmCQLYmzC3NxBKeJOW0RcjNBpO\n"
        + "c2JlGO9auXv2GDP4IYiXElLJ6VSqc8WvDikv0JmCCWm0Zga+bZbR/EWN5DeEtFdF\n"
        + "815CLpJZNcYwiYwGy/CVQ7w2TnXlG+mraZOz+owr+cL6J/ZesbdEWfjoS1+cUEhE\n"
        + "HwlNrAu8jlZ2UqSgskSWlhYdMTAP9CPHiUv9N7FcT58Itv/I4fKREINQYjDpvQcx\n"
        + "SaTYb9dr5sB4WLNglk7zxDtM80H518VvihTcP7FHL+Gn6g4j5fkI98+S\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=&#x82b1;&#x5b50;.co.jp
     */
    public static final byte[] X509_HANAKO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIESzCCAzOgAwIBAgIJAIz+EYMBU6aTMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1NDIxNVoXDTI4MTEwNTE1NDIxNVowgakx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEVMBMGA1UEAwwM6Iqx5a2QLmNvLmpwMSUwIwYJKoZIhvcNAQkB\n"
        + "FhZqdWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n"
        + "MIIBCgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjU\n"
        + "g4pNjYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQc\n"
        + "wHf0ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t\n"
        + "7iu1JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAn\n"
        + "AxK6q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArD\n"
        + "qUYxqJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwG\n"
        + "CWCGSAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNV\n"
        + "HQ4EFgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLS\n"
        + "rNuzA1LKh6YNPg0wDQYJKoZIhvcNAQEFBQADggEBALJ27i3okV/KvlDp6KMID3gd\n"
        + "ITl68PyItzzx+SquF8gahMh016NX73z/oVZoVUNdftla8wPUB1GwIkAnGkhQ9LHK\n"
        + "spBdbRiCj0gMmLCsX8SrjFvr7cYb2cK6J/fJe92l1tg/7Y4o7V/s4JBe/cy9U9w8\n"
        + "a0ctuDmEBCgC784JMDtT67klRfr/2LlqWhlOEq7pUFxRLbhpquaAHSOjmIcWnVpw\n"
        + "9BsO7qe46hidgn39hKh1WjKK2VcL/3YRsC4wUi0PBtFW6ScMCuMhgIRXSPU55Rae\n"
        + "UIlOdPjjr1SUNWGId1rD7W16Scpwnknn310FNxFMHVI0GTGFkNdkilNCFJcIoRA=\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=foo.com, subjectAlt=bar.com
     */
    public static final byte[] X509_FOO_BAR = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEXDCCA0SgAwIBAgIJAIz+EYMBU6aRMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzYyOVoXDTI4MTEwNTE1MzYyOVowgaQx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs\n"
        + "aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC\n"
        + "ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B\n"
        + "lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy\n"
        + "zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY\n"
        + "07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8\n"
        + "BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV\n"
        + "JTxpTKqym93whYk93l3ocEe55c0CAwEAAaOBkDCBjTAJBgNVHRMEAjAAMCwGCWCG\n"
        + "SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E\n"
        + "FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz\n"
        + "A1LKh6YNPg0wEgYDVR0RBAswCYIHYmFyLmNvbTANBgkqhkiG9w0BAQUFAAOCAQEA\n"
        + "dQyprNZBmVnvuVWjV42sey/PTfkYShJwy1j0/jcFZR/ypZUovpiHGDO1DgL3Y3IP\n"
        + "zVQ26uhUsSw6G0gGRiaBDe/0LUclXZoJzXX1qpS55OadxW73brziS0sxRgGrZE/d\n"
        + "3g5kkio6IED47OP6wYnlmZ7EKP9cqjWwlnvHnnUcZ2SscoLNYs9rN9ccp8tuq2by\n"
        + "88OyhKwGjJfhOudqfTNZcDzRHx4Fzm7UsVaycVw4uDmhEHJrAsmMPpj/+XRK9/42\n"
        + "2xq+8bc6HojdtbCyug/fvBZvZqQXSmU8m8IVcMmWMz0ZQO8ee3QkBHMZfCy7P/kr\n"
        + "VbWx/uETImUu+NZg22ewEw==\n" + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=foo.com, subjectAlt=bar.com, subjectAlt=&#x82b1;&#x5b50;.co.jp
     * (hanako.co.jp in kanji)
     */
    public static final byte[] X509_FOO_BAR_HANAKO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEajCCA1KgAwIBAgIJAIz+EYMBU6aSMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzgxM1oXDTI4MTEwNTE1MzgxM1owgaQx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs\n"
        + "aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC\n"
        + "ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B\n"
        + "lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy\n"
        + "zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY\n"
        + "07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8\n"
        + "BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV\n"
        + "JTxpTKqym93whYk93l3ocEe55c0CAwEAAaOBnjCBmzAJBgNVHRMEAjAAMCwGCWCG\n"
        + "SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E\n"
        + "FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz\n"
        + "A1LKh6YNPg0wIAYDVR0RBBkwF4IHYmFyLmNvbYIM6Iqx5a2QLmNvLmpwMA0GCSqG\n"
        + "SIb3DQEBBQUAA4IBAQBeZs7ZIYyKtdnVxVvdLgwySEPOE4pBSXii7XYv0Q9QUvG/\n"
        + "++gFGQh89HhABzA1mVUjH5dJTQqSLFvRfqTHqLpxSxSWqMHnvRM4cPBkIRp/XlMK\n"
        + "PlXadYtJLPTgpbgvulA1ickC9EwlNYWnowZ4uxnfsMghW4HskBqaV+PnQ8Zvy3L0\n"
        + "12c7Cg4mKKS5pb1HdRuiD2opZ+Hc77gRQLvtWNS8jQvd/iTbh6fuvTKfAOFoXw22\n"
        + "sWIKHYrmhCIRshUNohGXv50m2o+1w9oWmQ6Dkq7lCjfXfUB4wIbggJjpyEtbNqBt\n"
        + "j4MC2x5rfsLKKqToKmNE7pFEgqwe8//Aar1b+Qj+\n" + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=*.foo.com
     */
    public static final byte[] X509_WILD_FOO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIESDCCAzCgAwIBAgIJAIz+EYMBU6aUMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTU1NVoXDTI4MTEwNTE2MTU1NVowgaYx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczESMBAGA1UEAxQJKi5mb28uY29tMSUwIwYJKoZIhvcNAQkBFhZq\n"
        + "dWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n"
        + "CgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pN\n"
        + "jYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0\n"
        + "ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1\n"
        + "JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6\n"
        + "q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYx\n"
        + "qJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwGCWCG\n"
        + "SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E\n"
        + "FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz\n"
        + "A1LKh6YNPg0wDQYJKoZIhvcNAQEFBQADggEBAH0ipG6J561UKUfgkeW7GvYwW98B\n"
        + "N1ZooWX+JEEZK7+Pf/96d3Ij0rw9ACfN4bpfnCq0VUNZVSYB+GthQ2zYuz7tf/UY\n"
        + "A6nxVgR/IjG69BmsBl92uFO7JTNtHztuiPqBn59pt+vNx4yPvno7zmxsfI7jv0ww\n"
        + "yfs+0FNm7FwdsC1k47GBSOaGw38kuIVWqXSAbL4EX9GkryGGOKGNh0qvAENCdRSB\n"
        + "G9Z6tyMbmfRY+dLSh3a9JwoEcBUso6EWYBakLbq4nG/nvYdYvG9ehrnLVwZFL82e\n"
        + "l3Q/RK95bnA6cuRClGusLad0e6bjkBzx/VQ3VarDEpAkTLUGVAa0CLXtnyc=\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=*.co.jp
     */
    public static final byte[] X509_WILD_CO_JP = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIERjCCAy6gAwIBAgIJAIz+EYMBU6aVMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTYzMFoXDTI4MTEwNTE2MTYzMFowgaQx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEQMA4GA1UEAxQHKi5jby5qcDElMCMGCSqGSIb3DQEJARYWanVs\n"
        + "aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC\n"
        + "ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B\n"
        + "lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy\n"
        + "zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY\n"
        + "07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8\n"
        + "BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV\n"
        + "JTxpTKqym93whYk93l3ocEe55c0CAwEAAaN7MHkwCQYDVR0TBAIwADAsBglghkgB\n"
        + "hvhCAQ0EHxYdT3BlblNTTCBHZW5lcmF0ZWQgQ2VydGlmaWNhdGUwHQYDVR0OBBYE\n"
        + "FJ8Ud78/OrbKOIJCSBYs2tDLXofYMB8GA1UdIwQYMBaAFHua2o+QmU5S0qzbswNS\n"
        + "yoemDT4NMA0GCSqGSIb3DQEBBQUAA4IBAQA0sWglVlMx2zNGvUqFC73XtREwii53\n"
        + "CfMM6mtf2+f3k/d8KXhLNySrg8RRlN11zgmpPaLtbdTLrmG4UdAHHYr8O4y2BBmE\n"
        + "1cxNfGxxechgF8HX10QV4dkyzp6Z1cfwvCeMrT5G/V1pejago0ayXx+GPLbWlNeZ\n"
        + "S+Kl0m3p+QplXujtwG5fYcIpaGpiYraBLx3Tadih39QN65CnAh/zRDhLCUzKyt9l\n"
        + "UGPLEUDzRHMPHLnSqT1n5UU5UDRytbjJPXzF+l/+WZIsanefWLsxnkgAuZe/oMMF\n"
        + "EJMryEzOjg4Tfuc5qM0EXoPcQ/JlheaxZ40p2IyHqbsWV4MRYuFH4bkM\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * CN=*.foo.com, subjectAlt=*.bar.com, subjectAlt=*.&#x82b1;&#x5b50;.co.jp
     * (*.hanako.co.jp in kanji)
     */
    public static final byte[] X509_WILD_FOO_BAR_HANAKO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEcDCCA1igAwIBAgIJAIz+EYMBU6aWMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTczMVoXDTI4MTEwNTE2MTczMVowgaYx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczESMBAGA1UEAxQJKi5mb28uY29tMSUwIwYJKoZIhvcNAQkBFhZq\n"
        + "dWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n"
        + "CgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pN\n"
        + "jYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0\n"
        + "ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1\n"
        + "JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6\n"
        + "q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYx\n"
        + "qJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo4GiMIGfMAkGA1UdEwQCMAAwLAYJ\n"
        + "YIZIAYb4QgENBB8WHU9wZW5TU0wgR2VuZXJhdGVkIENlcnRpZmljYXRlMB0GA1Ud\n"
        + "DgQWBBSfFHe/Pzq2yjiCQkgWLNrQy16H2DAfBgNVHSMEGDAWgBR7mtqPkJlOUtKs\n"
        + "27MDUsqHpg0+DTAkBgNVHREEHTAbggkqLmJhci5jb22CDiou6Iqx5a2QLmNvLmpw\n"
        + "MA0GCSqGSIb3DQEBBQUAA4IBAQBobWC+D5/lx6YhX64CwZ26XLjxaE0S415ajbBq\n"
        + "DK7lz+Rg7zOE3GsTAMi+ldUYnhyz0wDiXB8UwKXl0SDToB2Z4GOgqQjAqoMmrP0u\n"
        + "WB6Y6dpkfd1qDRUzI120zPYgSdsXjHW9q2H77iV238hqIU7qCvEz+lfqqWEY504z\n"
        + "hYNlknbUnR525ItosEVwXFBJTkZ3Yw8gg02c19yi8TAh5Li3Ad8XQmmSJMWBV4XK\n"
        + "qFr0AIZKBlg6NZZFf/0dP9zcKhzSriW27bY0XfzA6GSiRDXrDjgXq6baRT6YwgIg\n"
        + "pgJsDbJtZfHnV1nd3M6zOtQPm1TIQpNmMMMd/DPrGcUQerD3\n" + "-----END CERTIFICATE-----\n")
        .getBytes();

    /**
     * CN=foo.com, CN=bar.com, CN=&#x82b1;&#x5b50;.co.jp
     */
    public static final byte[] X509_THREE_CNS_FOO_BAR_HANAKO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEbzCCA1egAwIBAgIJAIz+EYMBU6aXMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTk0NVoXDTI4MTEwNTE2MTk0NVowgc0x\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczEQMA4GA1UEAwwHZm9vLmNvbTEQMA4GA1UEAwwHYmFyLmNvbTEV\n"
        + "MBMGA1UEAwwM6Iqx5a2QLmNvLmpwMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyGOv\n"
        + "loI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pNjYGViGjg7zhf\n"
        + "bjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0ZHLN6sD9m2uV\n"
        + "Sp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1JVjTuE0pcBva\n"
        + "h2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6q/wGqcZ3zvFB\n"
        + "TcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYxqJUlPGlMqrKb\n"
        + "3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwGCWCGSAGG+EIBDQQf\n"
        + "Fh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4EFgQUnxR3vz86\n"
        + "tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuzA1LKh6YNPg0w\n"
        + "DQYJKoZIhvcNAQEFBQADggEBAGuZb8ai1NO2j4v3y9TLZvd5s0vh5/TE7n7RX+8U\n"
        + "y37OL5k7x9nt0mM1TyAKxlCcY+9h6frue8MemZIILSIvMrtzccqNz0V1WKgA+Orf\n"
        + "uUrabmn+CxHF5gpy6g1Qs2IjVYWA5f7FROn/J+Ad8gJYc1azOWCLQqSyfpNRLSvY\n"
        + "EriQFEV63XvkJ8JrG62b+2OT2lqT4OO07gSPetppdlSa8NBSKP6Aro9RIX1ZjUZQ\n"
        + "SpQFCfo02NO0uNRDPUdJx2huycdNb+AXHaO7eXevDLJ+QnqImIzxWiY6zLOdzjjI\n"
        + "VBMkLHmnP7SjGSQ3XA4ByrQOxfOUTyLyE7NuemhHppuQPxE=\n" + "-----END CERTIFICATE-----\n")
        .getBytes();

    /**
     * subjectAlt=foo.com
     */
    public static final byte[] X509_NO_CNS_FOO = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIESjCCAzKgAwIBAgIJAIz+EYMBU6aYMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE\n"
        + "ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU\n"
        + "FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp\n"
        + "ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MjYxMFoXDTI4MTEwNTE2MjYxMFowgZIx\n"
        + "CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0\n"
        + "IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl\n"
        + "cnRpZmljYXRlczElMCMGCSqGSIb3DQEJARYWanVsaXVzZGF2aWVzQGdtYWlsLmNv\n"
        + "bTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMhjr5aCPoyp0R1iroWA\n"
        + "fnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2BlYho4O84X244QrZTRl8kQbYt\n"
        + "xnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRyzerA/ZtrlUqf+lKo0uWcocxe\n"
        + "Rc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY07hNKXAb2odnVqgzcYiDkLV8\n"
        + "ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8BqnGd87xQU3FVZI4tbtkB+Kz\n"
        + "jD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiVJTxpTKqym93whYk93l3ocEe5\n"
        + "5c0CAwEAAaOBkDCBjTAJBgNVHRMEAjAAMCwGCWCGSAGG+EIBDQQfFh1PcGVuU1NM\n"
        + "IEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4EFgQUnxR3vz86tso4gkJIFiza\n"
        + "0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuzA1LKh6YNPg0wEgYDVR0RBAsw\n"
        + "CYIHZm9vLmNvbTANBgkqhkiG9w0BAQUFAAOCAQEAjl78oMjzFdsMy6F1sGg/IkO8\n"
        + "tF5yUgPgFYrs41yzAca7IQu6G9qtFDJz/7ehh/9HoG+oqCCIHPuIOmS7Sd0wnkyJ\n"
        + "Y7Y04jVXIb3a6f6AgBkEFP1nOT0z6kjT7vkA5LJ2y3MiDcXuRNMSta5PYVnrX8aZ\n"
        + "yiqVUNi40peuZ2R8mAUSBvWgD7z2qWhF8YgDb7wWaFjg53I36vWKn90ZEti3wNCw\n"
        + "qAVqixM+J0qJmQStgAc53i2aTMvAQu3A3snvH/PHTBo+5UL72n9S1kZyNCsVf1Qo\n"
        + "n8jKTiRriEM+fMFlcgQP284EBFzYHyCXFb9O/hMjK2+6mY9euMB1U1aFFzM/Bg==\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * Intermediate CA for all of these.
     */
    public static final byte[] X509_INTERMEDIATE_CA = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEnDCCA4SgAwIBAgIJAJTNwZ6yNa5cMA0GCSqGSIb3DQEBBQUAMIGGMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxFjAUBgNVBAoTDXd3dy5jdWNiYy5jb20xFDAS\n"
        + "BgNVBAsUC2NvbW1vbnNfc3NsMRUwEwYDVQQDFAxkZW1vX3Jvb3RfY2ExJTAjBgkq\n"
        + "hkiG9w0BCQEWFmp1bGl1c2Rhdmllc0BnbWFpbC5jb20wHhcNMDYxMTA1MjE0OTMx\n"
        + "WhcNMDcxMTA1MjE0OTMxWjCBojELMAkGA1UEBhMCQ0ExCzAJBgNVBAgTAkJDMRIw\n"
        + "EAYDVQQHEwlWYW5jb3V2ZXIxFjAUBgNVBAoTDXd3dy5jdWNiYy5jb20xFDASBgNV\n"
        + "BAsUC2NvbW1vbnNfc3NsMR0wGwYDVQQDFBRkZW1vX2ludGVybWVkaWF0ZV9jYTEl\n"
        + "MCMGCSqGSIb3DQEJARYWanVsaXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZI\n"
        + "hvcNAQEBBQADggEPADCCAQoCggEBAL0S4y3vUO0EM6lwqOEfK8fvrUprIbsikXaG\n"
        + "XzejcZ+T3l2Dc7t8WtBfRf78i4JypMqJQSijrUicj3H6mOMIReKaXm6ls4hA5d8w\n"
        + "Lhmgiqsz/kW+gA8SeWGWRN683BD/RbQmzOls6ynBvap9jZlthXWBrSIlPCQoBLXY\n"
        + "KVaxGzbL4ezaq+XFMKMQSm2uKwVmHHQNbfmZlPsuendBVomb/ked53Ab9IH6dwwN\n"
        + "qJH9WIrvIzIVEXWlpvQ5MCqozM7u1akU+G8cazr8theGPCaYkzoXnigWua4OjdpV\n"
        + "9z5ZDknhfBzG1AjapdG07FIirwWWgIyZXqZSD96ikmLtwT29qnsCAwEAAaOB7jCB\n"
        + "6zAdBgNVHQ4EFgQUe5raj5CZTlLSrNuzA1LKh6YNPg0wgbsGA1UdIwSBszCBsIAU\n"
        + "rN8eFIvMiRFXXgDqKumS0/W2AhOhgYykgYkwgYYxCzAJBgNVBAYTAkNBMQswCQYD\n"
        + "VQQIEwJCQzEWMBQGA1UEChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9u\n"
        + "c19zc2wxFTATBgNVBAMUDGRlbW9fcm9vdF9jYTElMCMGCSqGSIb3DQEJARYWanVs\n"
        + "aXVzZGF2aWVzQGdtYWlsLmNvbYIJAJTNwZ6yNa5bMAwGA1UdEwQFMAMBAf8wDQYJ\n"
        + "KoZIhvcNAQEFBQADggEBAIB4KMZvHD20pdKajFtMBpL7X4W4soq6EeTtjml3NYa9\n"
        + "Qc52bsQEGNccKY9afYSBIndaQvFdtmz6HdoN+B8TjYShw2KhyjtKimGLpWYoi1YF\n"
        + "e4aHdmA/Gp5xk8pZzR18FmooxC9RqBux+NAM2iTFSLgDtGIIj4sg2rbn6Bb6ZlQT\n"
        + "1rg6VucXCA1629lNfMeNcu7CBNmUKIdaxHR/YJQallE0KfGRiOIWPrPj/VNk0YA6\n"
        + "XFg0ocjqXJ2/N0N9rWVshMUaXgOh7m4D/5zga5/nuxDU+PoToA6mQ4bV6eCYqZbh\n"
        + "aa1kQYtR9B4ZiG6pB82qVc2dCqStOH2FAEWos2gAVkQ=\n" + "-----END CERTIFICATE-----\n")
        .getBytes();

    /**
     * Root CA for all of these.
     */
    public static final byte[] X509_ROOT_CA = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIEgDCCA2igAwIBAgIJAJTNwZ6yNa5bMA0GCSqGSIb3DQEBBQUAMIGGMQswCQYD\n"
        + "VQQGEwJDQTELMAkGA1UECBMCQkMxFjAUBgNVBAoTDXd3dy5jdWNiYy5jb20xFDAS\n"
        + "BgNVBAsUC2NvbW1vbnNfc3NsMRUwEwYDVQQDFAxkZW1vX3Jvb3RfY2ExJTAjBgkq\n"
        + "hkiG9w0BCQEWFmp1bGl1c2Rhdmllc0BnbWFpbC5jb20wHhcNMDYxMTA1MjEzNjQz\n"
        + "WhcNMjYxMTA1MjEzNjQzWjCBhjELMAkGA1UEBhMCQ0ExCzAJBgNVBAgTAkJDMRYw\n"
        + "FAYDVQQKEw13d3cuY3VjYmMuY29tMRQwEgYDVQQLFAtjb21tb25zX3NzbDEVMBMG\n"
        + "A1UEAxQMZGVtb19yb290X2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZpZXNA\n"
        + "Z21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAv+OnocmJ\n"
        + "79UeO2hlCwK+Cle5uZWnU6uwJl+08z5cvebb5tT64WL9+psDbfgUH/Gm9JsuxKTg\n"
        + "w1tZO/4duIgnaLNSx4HoqaTjwigd/hR3TsoGEPXTCkz1ikgTCOEDvl+iMid6aOrd\n"
        + "mViE8HhscxKZ+h5FE7oHZyuT6gFoiaIXhFq+xK2w4ZwDz9L+paiwqywyUJJMnh9U\n"
        + "jKorY+nua81N0oxpIhHPspCanDU4neMzCzYOZyLR/LqV5xORvHcFY84GWMz5hI25\n"
        + "JbgaWJsYKuCAvNsnQwVoqKPGa7x1fn7x6oGsXJaCVt8weUwIj2xwg1lxMhrNaisH\n"
        + "EvKpEAEnGGwWKQIDAQABo4HuMIHrMB0GA1UdDgQWBBSs3x4Ui8yJEVdeAOoq6ZLT\n"
        + "9bYCEzCBuwYDVR0jBIGzMIGwgBSs3x4Ui8yJEVdeAOoq6ZLT9bYCE6GBjKSBiTCB\n"
        + "hjELMAkGA1UEBhMCQ0ExCzAJBgNVBAgTAkJDMRYwFAYDVQQKEw13d3cuY3VjYmMu\n"
        + "Y29tMRQwEgYDVQQLFAtjb21tb25zX3NzbDEVMBMGA1UEAxQMZGVtb19yb290X2Nh\n"
        + "MSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZpZXNAZ21haWwuY29tggkAlM3BnrI1\n"
        + "rlswDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAlPl3/8h1LttR1svC\n"
        + "S8RXbHpAWIT2BEDhGHUNjSmgDQNkE/itf/FCEXh0tlU4bYdtBSOHzflbnzOyIPId\n"
        + "VZeSWs33V38xDFy6KoVg1gT8JxkLmE5S1vWkpsHIlpw/U6r7KD0Kx9FYx5AiXjw0\n"
        + "lzz/zlVNuO2U09KIDwDPVG1mBzQiMiSWj1U1pM4KxINkWQwDy/fvu/I983s8lW5z\n"
        + "hf2WuFNzQN3fcMK5dpBE9NVIu27oYuGYh2sak34v+7T700W2ooBB71qFXtm9P5rl\n"
        + "Yp9RCEsg3KEEPNTtCBs8fROeXvLDrP0cmBIqwGYDuRNCxFDTOdjv6YGdA8nLOjaH\n" + "2dDk0g==\n"
        + "-----END CERTIFICATE-----\n").getBytes();

    /**
     * Below is the private key for all the server certificates above (but
     * not the intermediate CA or the root CA).  All of those server certs
     * came from the same private key.
     */
    public static final String RSA_PUBLIC_MODULUS =
        "00c863af96823e8ca9d11d62ae85807e713204c1985a80a2747f7ac863c5"
            + "8d82e8c1ecf9698298d4838a4d8d81958868e0ef385f6e3842b653465f24"
            + "41b62dc671a1e204820fe67c82367f80cbcb52586a39bf965cf0141cc077"
            + "f46472cdeac0fd9b6b954a9ffa52a8d2e59ca1cc5e45cefbd4a37c70f1f7"
            + "9c7674ad5d07c78640672e94e31c4e6dee2bb52558d3b84d29701bda8767"
            + "56a83371888390b57c8a5bc49a8356316ae9f1406a913729121621098a77"
            + "713920270312baabfc06a9c677cef1414dc5559238b5bb6407e2b38c3f73"
            + "cfc4020c901f0e3647474dca350e66c4e817c31c0ac3a94631a895253c69"
            + "4caab29bddf085893dde5de87047b9e5cd";

    public static final String RSA_PUBLIC_EXPONENT = "65537";

    public static final String RSA_PRIVATE_EXPONENT =
        "577abd3295553d0efd4d38c13b62a6d03fa7b7e40cce4f1d5071877d96c6"
            + "7a39a63f0f7ab21a89db8acae45587b3ef251309a70f74dc1ac02bde68f3"
            + "8ed658e54e685ed370a18c054449512ea66a2252ed36e82b565b5159ec83"
            + "f23df40ae189550a183865b25fd77789e960f0d8cedcd72f32d7a66edb4b"
            + "a0a2baf3fbeb6c7d75f56ef0af9a7cff1c8c7f297d72eae7982164e50a89"
            + "d450698cf598d39343201094241d2d180a95882a7111e58f4a5bdbc5c125"
            + "a967dd6ed9ec614c5853e88e4c71e8b682a7cf89cb1d82b6fe78cc865084"
            + "c8c5dfbb50c939df2b839c977b0245bfa3615e0592b527b1013d5b675ecb"
            + "44e6b355c1df581f50997175166eef39";

    public static final String RSA_PRIME1 =
        "00fe759c4f0ce8b763880215e82767e7a937297668f4e4b1e119c6b22a3c"
            + "a2c7b06c547d88d0aa45f645d7d3aeadaf7f8bc594deae0978529592977c"
            + "b1ff890f05033a9e9e15551cad9fbf9c41d12139ccd99c1c3ac7b2197eff"
            + "350d236bb900c1440953b64956e0a058ef824a2e16894af175177c77dbe1" + "fef7d8b532608d2513";

    public static final String RSA_PRIME2 =
        "00c99a45878737a4cf73f9896680b75487f1b669b7686a6ba07103856f31"
            + "db668c2c440c44cdd116f708f631c37a9adf119f5b5cb58ffe3dc62e20af"
            + "af72693d936dc6bb3c5194996468389c1f094079b81522e94572b4ad7d39"
            + "529178e9b8ebaeb1f0fdd83b8731c5223f1dea125341d1d64917f6b1a6ae" + "c18d320510d79f859f";

    public static final String RSA_EXPONENT1 =
        "029febf0d4cd41b7011c2465b4a259bd6118486464c247236f44a169d61e"
            + "47b9062508f674508d5031003ceabc57e714e600d71b2c75d5443db2da52"
            + "6bb45a374f0537c5a1aab3150764ce93cf386c84346a6bd01f6732e42075"
            + "c7a0e9e78a9e73b934e7d871d0f75673820089e129a1604438edcbbeb4e2" + "106467da112ce389";

    public static final String RSA_EXPONENT2 =
        "00827e76650c946afcd170038d32e1f8386ab00d6be78d830efe382e45d4"
            + "7ad4bd04e6231ee22e66740efbf52838134932c9f8c460cdccdec58a1424"
            + "4427859192fd6ab6c58b74e97941b0eaf577f2a11713af5e5952af3ae124"
            + "9a9a892e98410dfa2628d9af668a43b5302fb7d496c9b2fec69f595292b6" + "e997f079b0f6314eb7";

    public static final String RSA_COEFFICIENT =
        "00e6b62add350f1a2a8968903ff76c31cf703b0d7326c4a620aef01225b7"
            + "1640b3f2ec375208c5f7299863f6005b7799b6e529bb1133c8435bf5fdb5"
            + "a786f6cd8a19ee7094a384e6557c600a38845a0960ddbfd1df18d0af5740"
            + "001853788f1b5ccbf9affb4c52c9d2efdb8aab0183d86735b32737fb4e79" + "2b8a9c7d91c7d175ae";

    /**
     * subjectAlt=IP Address:127.0.0.1, email:oleg@ural.ru, DNS:localhost.localdomain
     */
    public static final byte[] X509_MULTIPLE_SUBJECT_ALT = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIDcTCCAtqgAwIBAgIBATANBgkqhkiG9w0BAQUFADBAMQswCQYDVQQGEwJDSDEL\n"
        + "MAkGA1UECBMCWkgxDzANBgNVBAcTBlp1cmljaDETMBEGA1UEAxMKTXkgVGVzdCBD\n"
        + "QTAeFw0wODEwMzExMTU3NDVaFw0wOTEwMzExMTU3NDVaMGkxCzAJBgNVBAYTAkNI\n"
        + "MRAwDgYDVQQIEwdVbmtub3duMRAwDgYDVQQHEwdVbmtub3duMRAwDgYDVQQKEwdV\n"
        + "bmtub3duMRAwDgYDVQQLEwdVbmtub3duMRIwEAYDVQQDEwlsb2NhbGhvc3QwggG4\n"
        + "MIIBLAYHKoZIzjgEATCCAR8CgYEA/X9TgR11EilS30qcLuzk5/YRt1I870QAwx4/\n"
        + "gLZRJmlFXUAiUftZPY1Y+r/F9bow9subVWzXgTuAHTRv8mZgt2uZUKWkn5/oBHsQ\n"
        + "IsJPu6nX/rfGG/g7V+fGqKYVDwT7g/bTxR7DAjVUE1oWkTL2dfOuK2HXKu/yIgMZ\n"
        + "ndFIAccCFQCXYFCPFSMLzLKSuYKi64QL8Fgc9QKBgQD34aCF1ps93su8q1w2uFe5\n"
        + "eZSvu/o66oL5V0wLPQeCZ1FZV4661FlP5nEHEIGAtEkWcSPoTCgWE7fPCTKMyKbh\n"
        + "PBZ6i1R8jSjgo64eK7OmdZFuo38L+iE1YvH7YnoBJDvMpPG+qFGQiaiD3+Fa5Z8G\n"
        + "kotmXoB7VSVkAUw7/s9JKgOBhQACgYEA6ogAb/YLM1Rz9AoXKW4LA70VtFf7Mqqp\n"
        + "divdu9f72WQc1vMKo1YMf3dQadkMfBYRvAAa1IXDnoiFCHhXnVRkWkoUBJyNebLB\n"
        + "N92CZc0RVFZiMFgQMEh8UldnvAIi4cBk0/YuN3BGl4MzmquVIGrFovdWGqeaveOu\n"
        + "Xcu4lKGJNiqjODA2MDQGA1UdEQQtMCuHBH8AAAGBDG9sZWdAdXJhbC5ydYIVbG9j\n"
        + "YWxob3N0LmxvY2FsZG9tYWluMA0GCSqGSIb3DQEBBQUAA4GBAIgEwIoCSRkU3O7K\n"
        + "USYaOYyfJB9hsvs6YpClvYXiQ/5kPGARP60pM62v4wC7wI9shEizokIAxY2+O3cC\n"
        + "vwuJhNYaa2FJMELIwRN3XES8X8R6JHWbPaRjaAAPhczuEd8SZYy8yiVLmJTgw0gH\n"
        + "BSW775NHlkjsscFVgXkNf0PobqJ9\n" + "-----END CERTIFICATE-----").getBytes();

    /**
     * subject CN=repository.infonotary.com (Multiple AVA in RDN).
     */
    public static final byte[] X509_MULTIPLE_VALUE_AVA = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIFxzCCBK+gAwIBAgIIRO/2+/XA7z4wDQYJKoZIhvcNAQEFBQAwgZwxgZkwCQYD\n"
        + "VQQGDAJCRzAVBgNVBAoMDkluZm9Ob3RhcnkgUExDMBcGCgmSJomT8ixkARkWCWRv\n"
        + "bWFpbi1jYTAtBgNVBAMMJmktTm90YXJ5IFRydXN0UGF0aCBWYWxpZGF0ZWQgRG9t\n"
        + "YWluIENBMC0GA1UECwwmaS1Ob3RhcnkgVHJ1c3RQYXRoIFZhbGlkYXRlZCBEb21h\n"
        + "aW4gQ0EwHhcNMTIwNjE4MDg1MzIyWhcNMTMwNjE4MDg1MzIyWjCBxjGBwzAJBgNV\n"
        + "BAYTAkJHMBUGA1UEChMOSW5mb05vdGFyeSBQTEMwFwYDVQQLExBGaWxlcyBSZXBv\n"
        + "c2l0b3J5MBcGCgmSJomT8ixkARkWCWRvbWFpbi1jYTAgBgNVBAMTGXJlcG9zaXRv\n"
        + "cnkuaW5mb25vdGFyeS5jb20wIwYJKoZIhvcNAQkBFhZzdXBwb3J0QGluZm9ub3Rh\n"
        + "cnkuY29tMCYGCSqGSIb3DQEJAhMZcmVwb3NpdG9yeS5pbmZvbm90YXJ5LmNvbTCC\n"
        + "ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALKWjGpgsuz103xVEW/GSg5I\n"
        + "tBoLbXPxockabOTHnOh0VO2sImycyhBH78nMj+VMexn4y+kdCOuJqAA5LApxyhTA\n"
        + "KgKlRN7TfoC90IYHjB1dqLMIseg4YM7Oe0e4Z2nL50bHoqXg7OUHaILUQn7ufpYp\n"
        + "+VCWxyI43KvaR4+HnST3x47wqeArg/rULGV1a16X+46cxq2eoMAcDfostXHaemvz\n"
        + "vg/Wd5xcWfPbF/oY1/sBXH+AK+peVBMen82+3GtAWtNWbyPE3bT4RG+WgKUyfLZ1\n"
        + "7A67rX9DkUEVMPQpa50MpLnrRveiM9w6R3mrMHMHbNnwID0Tqfds5zzOi/7cLD0C\n"
        + "AwEAAaOCAd8wggHbMA4GA1UdDwEB/wQEAwIDuDATBgNVHSUEDDAKBggrBgEFBQcD\n"
        + "ATBEBggrBgEFBQcBAQQ4MDYwNAYIKwYBBQUHMAGGKGh0dHA6Ly9vY3NwLmluZm9u\n"
        + "b3RhcnkuY29tL3Jlc3BvbmRlci5jZ2kwgZAGA1UdIASBiDCBhTCBggYMKwYBBAGB\n"
        + "rQABAgMBMHIwOAYIKwYBBQUHAgEWLGh0dHA6Ly9yZXBvc2l0b3J5LmluZm9ub3Rh\n"
        + "cnkuY29tL2RvbWFpbi5odG1sMDYGCCsGAQUFBwICMCoaKGktTm90YXJ5IFZhbGlk\n"
        + "YXRlZCBEb21haW4gQ2VydGlmaWNhdGUgQ1AwgYkGA1UdHwSBgTB/MDWgL6Athito\n"
        + "dHRwOi8vY3JsLmluZm9ub3RhcnkuY29tL2NybC9kb21haW4tY2EuY3JsgQIBVjBG\n"
        + "oECgPoY8bGRhcDovL2xkYXAuaW5mb25vdGFyeS5jb20vZGM9ZG9tYWluLWNhLGRj\n"
        + "PWluZm9ub3RhcnksZGM9Y29tgQIBVjAPBgNVHRMBAf8EBTADAQEAMB0GA1UdDgQW\n"
        + "BBTImKJZrgV/8n7mHrA0U5EeGsBvbzAfBgNVHSMEGDAWgBTbkorEK+bPdVPpvyVI\n"
        + "PTxGFnuOoDANBgkqhkiG9w0BAQUFAAOCAQEAhsMbqsqvkbfVaKZ+wDY9rX3EtuDS\n"
        + "isdAo4AjmWgTtj/aBGiEiXcIGP312x+0JF+mEEQ75ZOKN+WsM8eLB0F4aqylklk7\n"
        + "6yRYauRXp8dfbXrT3ozxekt0cpSMqbzze456krI12nL+C00V2Iwq96k5J/yZboNW\n"
        + "Q+ibCaEAHNiL4tGVHSHm6znkWvIuUTbDgDEsm5RdafO27suz5H6zMnV+VE6onN1J\n"
        + "I1mQmUs44cg2HZAqnFBpDyJQhNYy8M7yGVaRkbfuVaMqiPa+xDPR5v7NFB3kxRq2\n"
        + "Za2Snopi52eUxDEhJ0MNqFi3Jfj/ZSmJ+XHra5lU4R8lijCAq8SVLZCmIQ==\n"
        + "-----END CERTIFICATE-----").getBytes();

    public static final byte[] S_GOOGLE_COM = ("-----BEGIN CERTIFICATE-----\n"
        + "MIICpzCCAY+gAwIBAgIBATANBgkqhkiG9w0BAQUFADAXMRUwEwYDVQQDDAwqLmdv\n"
        + "b2dsZS5jb20wHhcNMTcwMTEzMjI0OTAzWhcNMTgwMTEzMjI0OTAzWjAXMRUwEwYD\n"
        + "VQQDDAwqLmdvb2dsZS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB\n"
        + "AQDHuzznuHdJ5PH344xCyGYnUnIRhyLGBKN3WDLLrXWtr/5Sf3Q1qkiMiJ4BINsh\n"
        + "3Xy0z7VvHmMFlntgHXtkofBUPvTihxsVIypRkCZb5hpsWLotR10AW2JpVl/oxLP2\n"
        + "227/36X1zKh33fjImLJl9KzGWHLsbCBleQQJOn7YRsNR/QBZO0XGGkN/R2rRfLF3\n"
        + "rseRfI5gJjZkO0WDxocnf/iieOe0XNR0NAZaY1aozzPmZ/pRrOKYB8OFH7F73WOC\n"
        + "lPIUGai/byJ9SpbXdLUcMlGhml/4XzcnV/WVRD2P/mlY+xEFG3UEy3ufhNnKFJul\n"
        + "yjZrOaKbagamqtOyktzkjnerAgMBAAEwDQYJKoZIhvcNAQEFBQADggEBADaMcwVs\n"
        + "w5kbnoDJzMBJ01H16T4u8k78i/ybwz7u7krgkU0tABXCRj7S/4Dt3jqQ/rV6evj4\n"
        + "gIJ/2kZUp/PHKkV7CxWI48XBTAQUu9LEpxj0Hut3AtNMD9y/J6cFn2978tWsHFHI\n"
        + "mYgvclKUDE4WFMvuxfQVuX3RcGQ5i8khEMczY/KVhZYDcLU1PU0GTTJqqrQm59Z4\n"
        + "T4UyI3OPBR7Nb/kaU1fcgQ083uxRXcNYRMMZnU6c2oFnR+c6pO6aGoXo0C6rgC4R\n"
        + "pOj4hPvHCfZO2xg6HAdQ7UPALLX8pu5KGot7GRc8yiJ/Q1nBEuiPKKu0MIwQoFgP\n"
        + "WUux/APTsgLR7Vc=\n" + "-----END CERTIFICATE-----").getBytes();

    public static final byte[] IP_1_1_1_1 = ("-----BEGIN CERTIFICATE-----\n"
        + "MIICwjCCAaqgAwIBAgIBATANBgkqhkiG9w0BAQUFADAaMRgwFgYDVQQDEw9kdW1t\n"
        + "eS12YWx1ZS5jb20wHhcNMTcwMTEzMjI1MTQ2WhcNMTgwMTEzMjI1MTQ2WjAaMRgw\n"
        + "FgYDVQQDEw9kdW1teS12YWx1ZS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw\n"
        + "ggEKAoIBAQDfrapp3jHLp1RlElzpR/4sF9AcTYwMF1N+adkHRoVtmTlJV2lTIAjn\n"
        + "QLauy0Kkzv8uxmbID3uROgrFNDQ5RxTTCe+kW/vE6Pyzr5Z5ayjSTKeycTE7mAC4\n"
        + "6ntoCeEWiD593zlfqVo5PuRSp9Kusd+kexNVjC/BETDPa3yXctcH1ouW9GyGItgQ\n"
        + "u4GhCE8cipKMuTltgfK+Gh/5e9lFG9/F2fD+wHUVBULLR3JOQoqwgk2zAwKDwLuS\n"
        + "sEd1CBi35+W3apCKN0SEdTKIAxc/R+O/1j2hpOl9yXCCYyveGwJdFXVZtDcx+9/H\n"
        + "7NXhOdmw/mTXC5fOQGKciEo2SXt8Wp89AgMBAAGjEzARMA8GA1UdEQQIMAaHBAEB\n"
        + "AQEwDQYJKoZIhvcNAQEFBQADggEBAEAO6CE8twpcfdjk9oMjI5nX9GdC5Wt6+ujd\n"
        + "tLj0SbXvMKzCLLkveT0xTEzXfyEo8KW2qYYvPP1h83BIxsbR/J3Swt35UQVofv+4\n"
        + "JgO0FIdgB+iLEcjUh5+60xslylqWE+9bSWm4f06OXuv78tq5NYPZKku/3i4tqLRp\n"
        + "gH2rTtjX7Q4olSS7GdAgfiA2AnDZAbMtxtsnTt/QFpYQqhlkqHVDwgkGP7C8aMBD\n"
        + "RH0UIQCPxUkhwhtNmVyHO42r6oHXselZoVU6XRHuhogrGxPf/pzDUvrKBiJhsZQQ\n"
        + "oEu+pZCwkFLiNwUoq1G2oDpkkdBWB0JcBXB2Txa536ezFFWZYc0=\n" + "-----END CERTIFICATE-----")
        .getBytes();

    public static final byte[] EMAIL_ALT_SUBJECT_NAME = ("-----BEGIN CERTIFICATE-----\n"
        + "MIIDpTCCAo2gAwIBAgIJANqkMEtlkelbMA0GCSqGSIb3DQEBCwUAMHAxCzAJBgNV\n"
        + "BAYTAlVTMQswCQYDVQQIDAJWQTERMA8GA1UEBwwIU29tZUNpdHkxEjAQBgNVBAoM\n"
        + "CU15Q29tcGFueTETMBEGA1UECwwKTXlEaXZpc2lvbjEYMBYGA1UEAwwPd3d3LmNv\n"
        + "bXBhbnkuY29tMB4XDTE4MDIxNTA3MjkzMFoXDTIwMDIxNTA3MjkzMFowcDELMAkG\n"
        + "A1UEBhMCVVMxCzAJBgNVBAgMAlZBMREwDwYDVQQHDAhTb21lQ2l0eTESMBAGA1UE\n"
        + "CgwJTXlDb21wYW55MRMwEQYDVQQLDApNeURpdmlzaW9uMRgwFgYDVQQDDA93d3cu\n"
        + "Y29tcGFueS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC4v6Oq\n"
        + "Ua0goRVn1cmT7MOpJhXFm3A70bTpvJIRpEjtGIz99hb34/9r5AYyf1VhKyWmBq24\n"
        + "XNcOJ59XOlyjjbm2Tl811ufTOdcNbPadoVBmMt4039OSUFpVb4wAw2XPWLTCG2h1\n"
        + "HNj9GuFHmwcDsg5EiIRrhDGQm2LLLAGoe5PdReoMZCeeWzNWvKTCV14pyRzwQhJL\n"
        + "F1OmzLYzovbPfB8LZVhQgDbLsh034FScivf2oKDB+NEzAEagNpnrFR0MFLWGYsu1\n"
        + "nWD5RiZi78HFGiibmhH7QrEPfGlo2eofuUga6naoBUROqkmMCIL8n1HZ/Ur0oGny\n"
        + "vQCj1AyrfOhuVC53AgMBAAGjQjBAMAsGA1UdDwQEAwIEMDATBgNVHSUEDDAKBggr\n"
        + "BgEFBQcDATAcBgNVHREEFTATgRFlbWFpbEBleGFtcGxlLmNvbTANBgkqhkiG9w0B\n"
        + "AQsFAAOCAQEAZ0IsqRrsEmJ6Fa9Yo6PQtrKJrejN2TTDddVgyLQdokzWh/25JFad\n"
        + "NCMYPH5KjTUyKf96hJDlDayjbKk1PMMhSZMU5OG9NOuGMH/dQttruG1ojse7KIKg\n"
        + "yHDQrfq5Exxgfa7CMHRKAoTCY7JZhSLyVbTMVhmGfuUDad/RA86ZisXycp0ZmS97\n"
        + "qDkAmzFL0sL0ZUWNNUh4ZUWvCUZwiuN08z70NjGqXMTDCf68p3SYxbII0xTfScgf\n"
        + "aQ/A/hD7IbGGTexeoTwpEj01DNvefbQV6//neo32/R5XD0D5jn3TCgZcMThA6H3a\n"
        + "VkEghVg+s7uMfL/UEebOBQWXQJ/uVoknMA==\n" + "-----END CERTIFICATE-----").getBytes();

}
