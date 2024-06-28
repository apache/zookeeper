package org.apache.zookeeper.faaskeeper.model;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

public class Version {
    private SystemCounter system;
    private EpochCounter epoch;

    public Version(SystemCounter system, EpochCounter epoch) {
        if (system == null) {
            throw new IllegalArgumentException("SystemCounter cannot be null");
        }
        this.system = system;
        this.epoch = epoch;
    }

    public SystemCounter getSystem() {
        return this.system;
    }

    public void setSystem(SystemCounter system) {
        this.system = system;
    }

    public Optional<EpochCounter> getEpoch() {
        return Optional.ofNullable(this.epoch);
    }

    public void setEpoch(EpochCounter epoch) {
        this.epoch = epoch;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> sys = new HashMap<>();
        sys.put("system", this.system.serialize());
        if (this.epoch != null) {
            sys.put("epoch", this.epoch.serialize());
        }
        return sys;
    }
}