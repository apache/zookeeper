package org.apache.zookeeper.faaskeeper;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID; // Added UUID import

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FaasKeeperClient {
    // TODO: Move this reqId to the queue once implemented
    private int reqIdTempRemoveLater = 0;
    private FaasKeeperConfig cfg;
    private int port;
    private boolean heartbeat = true;
    private String sessionId;
    private ProviderClient providerClient;
    private static Map<String, Class<? extends ProviderClient>> providers = new HashMap<>();
    private static final Logger LOG;
    static {
        LOG = LoggerFactory.getLogger(FaasKeeperClient.class);
        providers.put(CloudProvider.serialize(CloudProvider.AWS), AwsClient.class);
    }

    public FaasKeeperClient(FaasKeeperConfig cfg, int port, boolean heartbeat) {
        try {
            this.cfg = cfg;
            this.port = port == -1 ? 8080 : port; // Assuming default port 8080 if -1 is passed
            this.heartbeat = heartbeat;
            Class<? extends ProviderClient> providerClass = providers.get(CloudProvider.serialize(this.cfg.getCloudProvider()));
            this.providerClient = providerClass.getDeclaredConstructor(FaasKeeperConfig.class).newInstance(this.cfg);
        } catch (Exception e) {
            LOG.error("Error in initializing provider client", e);
            throw new RuntimeException("Error in initializing provider client", e);
        }
    }

    public String start() {
        LOG.info("Starting FK connection");
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        // TODO: Add queues implementation + add source_addr IP calculation
        this.providerClient.registerSession(sessionId, "", this.heartbeat);
        LOG.info("Connection successful. sessionID = " + sessionId);
        return sessionId;
    }

    public void stop() {
        // TODO: stop threads and clear queues
        // TODO: deregister session
        LOG.info("Closing FK connection");
    }

    public static FaasKeeperClient buildClient(String configFilePath, int port, boolean heartbeat) throws Exception {
        try {
            FaasKeeperConfig cfg = FaasKeeperConfig.buildFromConfigJson(configFilePath);
            return new FaasKeeperClient(cfg, port, heartbeat);
        } catch (Exception e) {
            LOG.error("Error in creating client", e);
            throw e;
        }
    }

    // flags represents createmode in its bit representation
    public String create(String path, byte[] value, int flags) throws Exception {
        String requestId = sessionId + "-" + String.valueOf(reqIdTempRemoveLater);
        reqIdTempRemoveLater = reqIdTempRemoveLater + 1;
        CreateNode requestOp = new CreateNode(sessionId, path, value, flags);
        providerClient.sendRequest(requestId, requestOp.generateRequest());
        return path;
    }
}

