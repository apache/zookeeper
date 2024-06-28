package org.apache.zookeeper.faaskeeper.model;


import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.Optional;
import java.util.ArrayList;
import java.math.BigInteger;

public class EpochCounter {    
    private Map<String, Object> providerData;
    private Set<String> version;
    
    /**
     * The epoch counter is a set of non-duplicated integer values.
     * Each one corresponds to a watch invocation.
     */
    
    public EpochCounter(Map<String, Object> providerData, Set<String> version) {
        this.providerData = providerData;
        this.version = version;
    }
    
    public static EpochCounter fromProviderSchema(Map<String, Object> providerData) {
        return new EpochCounter(providerData, null);
    }
    
    public static EpochCounter fromRawData(Set<String> counterData) {
        return new EpochCounter(null, counterData);
    }
    
    public Map<String, Object> getProviderData() {
        return this.providerData;
    }
    
    public Set<String> getVersion() {
        if (this.version == null) {
            assert this.providerData != null;
            // TODO: Convert provData to set
            // this.version = new HashSet<>(Arrays.asList(_typeDeserializer.deserialize(this.providerData)));
        }
        return this.version;
    }
    
    // JSON cannot accept a set
    public List<String> serialize() {
        if (this.version == null) {
            assert this.providerData != null;
            // TODO: Convert provData to set
            // this.version = new HashSet<>(Arrays.asList(_typeDeserializer.deserialize(this.providerData)));
        }        
        return new ArrayList<>(this.version);
    }
}
