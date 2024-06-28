package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.Optional;
import java.util.ArrayList;
import java.math.BigInteger;


public class SystemCounter {
    private Map<String, Object> providerData;
    private List<BigInteger> version;
    private BigInteger sum;

    public SystemCounter(Map<String, Object> providerData, List<BigInteger> version) {
        this.providerData = providerData;
        this.version = version;
        this.sum = BigInteger.ZERO;
    }

    private BigInteger computeSum() {
        if (this.sum.equals(BigInteger.ZERO)) {            
            for (BigInteger num : this.version) {
                this.sum.add(num);
            }
        }
        return this.sum;
    }

    public static SystemCounter fromProviderSchema(Map<String, Object> providerData) {
        return new SystemCounter(providerData, null);
    }

    public static SystemCounter fromRawData(List<BigInteger> counterData) {
        return new SystemCounter(null, counterData);
    }

    public Map<String, Object> getVersion() {
        // TODO: calculate providerData from this.version list
        if (this.providerData == null) {
            throw new IllegalStateException("Provider data is null");
        }
        return this.providerData;
    }

    public BigInteger getSum() {
        return this.computeSum();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SystemCounter)) {
            return false;
        }
        SystemCounter other = (SystemCounter) obj;
        return this.computeSum() == other.computeSum();
    }

    public boolean compareTo(Object obj) {
        if (!(obj instanceof SystemCounter)) {
            return false;
        }
        SystemCounter other = (SystemCounter) obj;
        return this.computeSum().compareTo(other.computeSum()) < 0;
    }

    public List<BigInteger> serialize() {
        if (this.version == null) {
            // TODO: calculate this.version list from providerData
            throw new IllegalStateException("Version data is null");
        }
        return this.version;
    }
}