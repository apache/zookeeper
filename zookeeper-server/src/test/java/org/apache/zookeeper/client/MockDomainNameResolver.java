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

package org.apache.zookeeper.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * This mock resolver class returns the predefined resolving/reverse lookup
 * results. By default it uses a default "test.foo.bar" domain with two
 * IP addresses.
 */
public class MockDomainNameResolver implements DomainNameResolver {

  public static final String DOMAIN1 = "test.foo.bar";
  public static final byte[] BYTE_ADDR_1 = new byte[]{10, 1, 1, 1};
  public static final byte[] BYTE_ADDR_2 = new byte[]{10, 1, 1, 2};
  public static final String FQDN_1 = "host01.test";
  public static final String FQDN_2 = "host02.test";

  /** Internal mapping of domain names and IP addresses. */
  private Map<String, InetAddress[]> addrs = new TreeMap<>();
  /** Internal mapping from IP addresses to fqdns. */
  private Map<InetAddress, String> ptrMap = new HashMap<>();

  public MockDomainNameResolver() {
    try {
      InetAddress nn1Address = InetAddress.getByAddress(BYTE_ADDR_1);
      InetAddress nn2Address = InetAddress.getByAddress(BYTE_ADDR_2);
      addrs.put(DOMAIN1, new InetAddress[]{nn1Address, nn2Address});
      ptrMap.put(nn1Address, FQDN_1);
      ptrMap.put(nn2Address, FQDN_2);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public InetAddress[] getAllByDomainName(String domainName)
          throws UnknownHostException {
    if (!addrs.containsKey(domainName)) {
      throw new UnknownHostException(domainName + " is not resolvable");
    }
    return addrs.get(domainName);
  }

  @Override
  public String getHostnameByIP(InetAddress address) {
    return ptrMap.containsKey(address) ? ptrMap.get(address) : null;
  }

  @Override
  public String[] getAllResolvedHostnameByDomainName(
          String domainName, boolean useFQDN) throws UnknownHostException {
    InetAddress[] addresses = getAllByDomainName(domainName);
    String[] hosts = new String[addresses.length];
    if (useFQDN) {
      for (int i = 0; i < hosts.length; i++) {
        hosts[i] = this.ptrMap.get(addresses[i]);
      }
    } else {
      for (int i = 0; i < hosts.length; i++) {
        hosts[i] = addresses[i].getHostAddress();
      }
    }

    return hosts;
  }

  public void setAddressMap(Map<String, InetAddress[]> addresses) {
    this.addrs = addresses;
  }
}
