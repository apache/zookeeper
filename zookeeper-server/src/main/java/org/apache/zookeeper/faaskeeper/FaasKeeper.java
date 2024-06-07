package org.apache.zookeeper.faaskeeper;

import java.util.Map;

public class FaasKeeper {
    private Config cfg;
    private int port;
    private boolean heartbeat;
    private boolean verbose;
    private boolean debug;

    public FaasKeeper(Config cfg, int port, boolean heartbeat, boolean verbose, boolean debug) {
        this.cfg = cfg;
        this.port = port == -1 ? 8080 : port; // Assuming default port 8080 if -1 is passed
        this.heartbeat = heartbeat;
        this.verbose = verbose;
        this.debug = debug;
    }
}
