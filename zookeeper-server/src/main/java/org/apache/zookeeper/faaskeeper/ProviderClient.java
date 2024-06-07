package org.apache.zookeeper.faaskeeper;

// import java.util.List;
// import java.util.Optional;
// import java.util.AbstractMap.SimpleEntry;

public abstract class ProviderClient {

    protected FaasKeeperConfig config;

    public ProviderClient(FaasKeeperConfig cfg) {
        this.config = cfg;
    }

    // public abstract SimpleEntry<Node, Optional<Watch>> getData(String path, WatchCallbackType watch, SimpleEntry<String, Integer> listenAddress);

    // public abstract SimpleEntry<Optional<Node>, Optional<Watch>> exists(String path);

    // public abstract SimpleEntry<List<Node>, Optional<Watch>> getChildren(String path, boolean includeData);

    public abstract void registerSession(String sessionId, String sourceAddr, boolean heartbeat);

    // public abstract Watch registerWatch(Node node, WatchType watchType, WatchCallbackType watch, SimpleEntry<String, Integer> listenAddress);

    // public Object executeRequest(DirectOperation op, SimpleEntry<String, Integer> listenAddress) {
    //     if (op instanceof GetData) {
    //         return getData(op.getPath(), op.getWatch(), listenAddress);
    //     } else if (op instanceof ExistsNode) {
    //         return exists(op.getPath());
    //     } else if (op instanceof GetChildren) {
    //         return getChildren(op.getPath(), op.isIncludeData());
    //     } else if (op instanceof RegisterSession) {
    //         registerSession(op.getSessionId(), op.getSourceAddr(), op.isHeartbeat());
    //         return null;
    //     } else {
    //         throw new UnsupportedOperationException("Operation not supported");
    //     }
    // }
}
