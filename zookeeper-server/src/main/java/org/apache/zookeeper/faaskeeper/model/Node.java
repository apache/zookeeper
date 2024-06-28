package org.apache.zookeeper.faaskeeper.model;

import java.util.List;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class Node {
    private String path;
    private byte[] data;
    private String dataB64;
    private List<String> children;
    private Version createdVersion;
    private Version modifiedVersion;

    public Node(String path) {
        this.path = path;
    }

    public String getPath() {
        return this.path;
    }

    public List<String> getChildren() throws Exception {
        if (this.children == null) {
            throw new IllegalStateException("Node has no children list");
        }
        return this.children;
    }

    public void setChildren(List<String> children) {
        this.children = children;
    }

    public boolean hasChildren() {
        return this.children != null;
    }

    public boolean hasData() {
        return this.data != null || this.dataB64 != null;
    }

    public byte[] getData() {
        if (this.data == null) {
            if (this.dataB64 == null) {
                throw new IllegalStateException("DataB64 is null");
            }
            return Base64.getDecoder().decode(this.dataB64);
        } else {
            return this.data;
        }
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getDataB64() {
        if (this.dataB64 == null) {
            throw new IllegalStateException("DataB64 is null");
        }
        return this.dataB64;
    }

    public void setDataB64(String data) {
        this.dataB64 = data;
    }

    public boolean hasCreated() {
        return this.createdVersion != null;
    }

    public Version getCreated() {
        if (this.createdVersion == null) {
            throw new IllegalStateException("Created version is null");
        }
        return this.createdVersion;
    }

    public void setCreated(Version val) {
        this.createdVersion = val;
    }

    public Version getModified() {
        if (this.modifiedVersion == null) {
            throw new IllegalStateException("Modified version is null");
        }
        return this.modifiedVersion;
    }

    public void setModified(Version val) {
        this.modifiedVersion = val;
    }

    public boolean hasModified() {
        return this.modifiedVersion != null;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> dataDict = new HashMap<>();
        if (this.data != null) {
            dataDict.put("data", new String(this.data));
        }

        Map<String, Object> versionDict = new HashMap<>();
        if (this.createdVersion != null) {
            versionDict.put("version", Map.of("created", this.createdVersion.serialize()));
        }
        if (this.modifiedVersion != null) {
            if (!versionDict.containsKey("version")) {
                versionDict.put("version", new HashMap<>());
            }
            ((Map<String, Object>) versionDict.get("version")).put("modified", this.modifiedVersion.serialize());
        }

        Map<String, Object> childrenDict = new HashMap<>();
        if (this.children != null) {
            childrenDict.put("children", this.children);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("path", this.path);
        result.putAll(dataDict);
        result.putAll(versionDict);
        result.putAll(childrenDict);
        return result;
    }
}