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

package org.apache.zookeeper.server.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ServerCnxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IPAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(IPAuthenticationProvider.class);
    public static final String X_FORWARDED_FOR_HEADER_NAME = "X-Forwarded-For";

    public static final String USE_X_FORWARDED_FOR_KEY = "zookeeper.IPAuthenticationProvider.usexforwardedfor";
    private static final int IPV6_BYTE_LENGTH = 16; // IPv6 address is 128 bits = 16 bytes
    private static final int IPV6_SEGMENT_COUNT = 8; // IPv6 address has 8 segments
    private static final int IPV6_SEGMENT_BYTE_LENGTH = 2; // Each segment has up to two bytes
    private static final int IPV6_SEGMENT_HEX_LENGTH = 4; // Each segment has up to 4 hex digits

    private static final Pattern IPV6_PATTERN = Pattern.compile(":");
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\.");

    public String getScheme() {
        return "ip";
    }

    public KeeperException.Code handleAuthentication(ServerCnxn cnxn, byte[] authData) {
        String id = cnxn.getRemoteSocketAddress().getAddress().getHostAddress();
        cnxn.addAuthInfo(new Id(getScheme(), id));
        return KeeperException.Code.OK;
    }

    @Override
    public List<Id> handleAuthentication(HttpServletRequest request, byte[] authData) {
        final List<Id> ids = new ArrayList<>();

        final String ip = getClientIPAddress(request);
        ids.add(new Id(getScheme(), ip));

        return Collections.unmodifiableList(ids);
    }

    // This is a bit weird but we need to return the address and the number of
    // bytes (to distinguish between IPv4 and IPv6
    private byte[] addr2Bytes(String addr) {
        if (IPV6_PATTERN.matcher(addr).find()) {
            return v6addr2Bytes(addr);
        } else if (IPV4_PATTERN.matcher(addr).find()) {
            return v4addr2Bytes(addr);
        } else {
            LOG.warn("Input string does not resemble an IPv4 or IPv6 address: {}", addr);
            return null;
        }
    }

    private byte[] v4addr2Bytes(String addr) {
        String[] parts = addr.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v >= 0 && v <= 255) {
                    b[i] = (byte) v;
                } else {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return b;
    }

    /**
     * Validates an IPv6 address string and converts it into a byte array.
     *
     * @param ipv6Addr The IPv6 address string to validate.
     * @return A byte array representing the IPv6 address if valid, or null if the address
     * is invalid or cannot be parsed.
     */
    static byte[] v6addr2Bytes(String ipv6Addr) {
        try {
            return parseV6addr(ipv6Addr);
        } catch (IllegalArgumentException e) {
            LOG.warn("Fail to parse {} as IPv6 address: {}", ipv6Addr, e.getMessage());
            return null;
        }
    }

    static byte[] parseV6addr(String ipv6Addr) {
        // Split the address by "::" to handle zero compression, -1 to keep trailing empty strings
        String[] parts = ipv6Addr.split("::", -1);

        String[] segments1 = new String[0];
        String[] segments2 = new String[0];

        // Case 1: No "::" (full address)
        if (parts.length == 1) {
            segments1 = parts[0].split(":", -1);
            if (segments1.length != IPV6_SEGMENT_COUNT) {
                String reason = "wrong number of segments";
                throw new IllegalArgumentException(reason);
            }
        } else if (parts.length == 2) {
            // Case 2: "::" is present
            // Handle cases like "::1" or "1::"
            if (!parts[0].isEmpty()) {
                segments1 = parts[0].split(":", -1);
            }
            if (!parts[1].isEmpty()) {
                segments2 = parts[1].split(":", -1);
            }

            // Check if the total number of explicit segments exceeds 8
            if (segments1.length + segments2.length >= IPV6_SEGMENT_COUNT) {
                String reason = "too many segments";
                throw new IllegalArgumentException(reason);
            }
        } else {
            // Case 3: Invalid number of parts after splitting by "::" (should be 1 or 2)
            String reason = "too many '::'";
            throw new IllegalArgumentException(reason);
        }

        byte[] result = new byte[IPV6_BYTE_LENGTH];
        // Process segments before "::"
        parseV6Segment(result, 0, segments1);
        // Process segments after "::"
        parseV6Segment(result, IPV6_BYTE_LENGTH - segments2.length * IPV6_SEGMENT_BYTE_LENGTH, segments2);

        return result;
    }

    private static void parseV6Segment(byte[] addr, int i, String[] segments) {
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("empty segment");
            } else if (segment.length() > IPV6_SEGMENT_HEX_LENGTH) {
                throw new IllegalArgumentException("segment too long");
            }
            try {
                int value = Integer.parseInt(segment, 16);
                addr[i++] = (byte) ((value >> 8) & 0xFF);
                addr[i++] = (byte) (value & 0xFF);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid hexadecimal characters in segment: " + segment);
            }
        }
    }

    private void mask(byte[] b, int bits) {
        int start = bits / 8;
        int startMask = (1 << (8 - (bits % 8))) - 1;
        startMask = ~startMask;
        while (start < b.length) {
            b[start] &= startMask;
            startMask = 0;
            start++;
        }
    }

    public boolean matches(String id, String aclExpr) {
        LOG.trace("id: '{}' aclExpr:  {}", id, aclExpr);
        String[] parts = aclExpr.split("/", 2);
        byte[] aclAddr = addr2Bytes(parts[0]);
        if (aclAddr == null) {
            return false;
        }
        int bits = aclAddr.length * 8;
        if (parts.length == 2) {
            try {
                bits = Integer.parseInt(parts[1]);
                if (bits < 0 || bits > aclAddr.length * 8) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        mask(aclAddr, bits);
        byte[] remoteAddr = addr2Bytes(id);
        if (remoteAddr == null) {
            return false;
        }
        mask(remoteAddr, bits);
        // Check if id and acl expression are of different formats (ipv6 or iv4) return false
        if (remoteAddr.length != aclAddr.length) {
            return false;
        }
        for (int i = 0; i < remoteAddr.length; i++) {
            if (remoteAddr[i] != aclAddr[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean isAuthenticated() {
        return false;
    }

    public boolean isValid(String id) {
        String[] parts = id.split("/", 2);
        byte[] aclAddr = addr2Bytes(parts[0]);
        if (aclAddr == null) {
            return false;
        }
        if (parts.length == 2) {
            try {
                int bits = Integer.parseInt(parts[1]);
                if (bits < 0 || bits > aclAddr.length * 8) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the HTTP(s) client IP address
     * @param request HttpServletRequest
     * @return IP address
     */
    public static String getClientIPAddress(final HttpServletRequest request) {
        if (!Boolean.getBoolean(USE_X_FORWARDED_FOR_KEY)) {
            return request.getRemoteAddr();
        }

        // to handle the case that a HTTP(s) client connects via a proxy or load balancer
        final String xForwardedForHeader = request.getHeader(X_FORWARDED_FOR_HEADER_NAME);
        if (xForwardedForHeader == null) {
            return request.getRemoteAddr();
        }
        // the format of the field is: X-Forwarded-For: client, proxy1, proxy2 ...
        return new StringTokenizer(xForwardedForHeader, ",").nextToken().trim();
    }
}
