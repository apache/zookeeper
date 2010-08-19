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
package org.apache.hedwig.server.common;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;

import com.google.protobuf.ByteString;
import org.apache.hedwig.conf.AbstractConfiguration;
import org.apache.hedwig.util.HedwigSocketAddress;

public class ServerConfiguration extends AbstractConfiguration {
    protected final static String REGION = "region";
    protected final static String MAX_MESSAGE_SIZE = "max_message_size";
    protected final static String READAHEAD_COUNT = "readahead_count";
    protected final static String READAHEAD_SIZE = "readahead_size";
    protected final static String CACHE_SIZE = "cache_size";
    protected final static String SCAN_BACKOFF_MSEC = "scan_backoff_ms";
    protected final static String SERVER_PORT = "server_port";
    protected final static String SSL_SERVER_PORT = "ssl_server_port";
    protected final static String ZK_PREFIX = "zk_prefix";
    protected final static String ZK_HOST = "zk_host";
    protected final static String ZK_TIMEOUT = "zk_timeout";
    protected final static String READAHEAD_ENABLED = "readhead_enabled";
    protected final static String STANDALONE = "standalone";
    protected final static String REGIONS = "regions";
    protected final static String CERT_NAME = "cert_name";
    protected final static String CERT_PATH = "cert_path";
    protected final static String PASSWORD = "password";
    protected final static String SSL_ENABLED = "ssl_enabled";
    protected final static String CONSUME_INTERVAL = "consume_interval";
    protected final static String RETENTION_SECS = "retention_secs";
    protected final static String INTER_REGION_SSL_ENABLED = "inter_region_ssl_enabled";
    protected final static String MESSAGES_CONSUMED_THREAD_RUN_INTERVAL = "messages_consumed_thread_run_interval";

    // these are the derived attributes
    protected ByteString myRegionByteString = null;
    protected HedwigSocketAddress myServerAddress = null;
    protected List<String> regionList = null;

    // Although this method is not currently used, currently maintaining it like
    // this so that we can support on-the-fly changes in configuration
    protected void refreshDerivedAttributes() {
        refreshMyRegionByteString();
        refreshMyServerAddress();
        refreshRegionList();
    }

    @Override
    public void loadConf(URL confURL) throws ConfigurationException {
        super.loadConf(confURL);
        refreshDerivedAttributes();
    }

    public int getMaximumMessageSize() {
        return conf.getInt(MAX_MESSAGE_SIZE, 1258291); /* 1.2M */
    }

    public String getMyRegion() {
        return conf.getString(REGION, "standalone");
    }

    protected void refreshMyRegionByteString() {
        myRegionByteString = ByteString.copyFromUtf8(getMyRegion());
    }

    protected void refreshMyServerAddress() {
        try {
            // Use the raw IP address as the hostname
            myServerAddress = new HedwigSocketAddress(InetAddress.getLocalHost().getHostAddress(), getServerPort(),
                    getSSLServerPort());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    // The expected format for the regions parameter is Hostname:Port:SSLPort
    // with spaces in between each of the regions.
    protected void refreshRegionList() {
        String regions = conf.getString(REGIONS, "");
        if (regions.isEmpty()) {
            regionList = new LinkedList<String>();
        } else {
            regionList = Arrays.asList(regions.split(" "));
        }
    }

    public ByteString getMyRegionByteString() {
        if (myRegionByteString == null) {
            refreshMyRegionByteString();
        }
        return myRegionByteString;
    }

    public int getReadAheadCount() {
        return conf.getInt(READAHEAD_COUNT, 10);
    }

    public long getReadAheadSizeBytes() {
        return conf.getLong(READAHEAD_SIZE, 4 * 1024 * 1024); // 4M
    }

    public long getMaximumCacheSize() {
        // 2G or half of the maximum amount of memory the JVM uses
        return conf.getLong(CACHE_SIZE, Math.min(2 * 1024L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 2));
    }

    // After a scan of a log fails, how long before we retry (in msec)
    public long getScanBackoffPeriodMs() {
        return conf.getLong(SCAN_BACKOFF_MSEC, 1000);
    }

    public int getServerPort() {
        return conf.getInt(SERVER_PORT, 4080);
    }

    public int getSSLServerPort() {
        return conf.getInt(SSL_SERVER_PORT, 9876);
    }

    public String getZkPrefix() {
        return conf.getString(ZK_PREFIX, "/hedwig");
    }

    public StringBuilder getZkRegionPrefix(StringBuilder sb) {
        return sb.append(getZkPrefix()).append("/").append(getMyRegion());
    }

    public StringBuilder getZkTopicsPrefix(StringBuilder sb) {
        return getZkRegionPrefix(sb).append("/topics");
    }

    public StringBuilder getZkTopicPath(StringBuilder sb, ByteString topic) {
        return getZkTopicsPrefix(sb).append("/").append(topic.toStringUtf8());
    }

    public StringBuilder getZkHostsPrefix(StringBuilder sb) {
        return getZkRegionPrefix(sb).append("/hosts");
    }

    public HedwigSocketAddress getServerAddr() {
        if (myServerAddress == null) {
            refreshMyServerAddress();
        }
        return myServerAddress;
    }

    public String getZkHost() {
        return conf.getString(ZK_HOST, "localhost");
    }

    public int getZkTimeout() {
        return conf.getInt(ZK_TIMEOUT, 2000);
    }

    public boolean getReadAheadEnabled() {
        return conf.getBoolean(READAHEAD_ENABLED, true);
    }

    public boolean isStandalone() {
        return conf.getBoolean(STANDALONE, false);
    }

    public List<String> getRegions() {
        if (regionList == null) {
            refreshRegionList();
        }
        return regionList;
    }

    // This is the name of the SSL certificate if available as a resource.
    public String getCertName() {
        return conf.getString(CERT_NAME, "");
    }

    // This is the path to the SSL certificate if it is available as a file.
    public String getCertPath() {
        return conf.getString(CERT_PATH, "");
    }

    // This method return the SSL certificate as an InputStream based on if it
    // is configured to be available as a resource or as a file. If nothing is
    // configured correctly, then a ConfigurationException will be thrown as
    // we do not know how to obtain the SSL certificate stream.
    public InputStream getCertStream() throws FileNotFoundException, ConfigurationException {
        String certName = getCertName();
        String certPath = getCertPath();
        if (certName != null && !certName.isEmpty()) {
            return getClass().getResourceAsStream(certName);
        } else if (certPath != null && !certPath.isEmpty()) {
            return new FileInputStream(certPath);
        } else
            throw new ConfigurationException("SSL Certificate configuration does not have resource name or path set!");
    }

    public String getPassword() {
        return conf.getString(PASSWORD, "");
    }

    public boolean isSSLEnabled() {
        return conf.getBoolean(SSL_ENABLED, false);
    }

    public int getConsumeInterval() {
        return conf.getInt(CONSUME_INTERVAL, 50);
    }

    public int getRetentionSecs() {
        return conf.getInt(RETENTION_SECS, 0);
    }

    public boolean isInterRegionSSLEnabled() {
        return conf.getBoolean(INTER_REGION_SSL_ENABLED, false);
    }

    // This parameter is used to determine how often we run the
    // SubscriptionManager's Messages Consumed timer task thread (in
    // milliseconds).
    public int getMessagesConsumedThreadRunInterval() {
        return conf.getInt(MESSAGES_CONSUMED_THREAD_RUN_INTERVAL, 60000);
    }

    /*
     * Is this a valid configuration that we can run with? This code might grow
     * over time.
     */
    public void validate() throws ConfigurationException {
        if (!getZkPrefix().startsWith("/")) {
            throw new ConfigurationException(ZK_PREFIX + " must start with a /");
        }
        // Validate that if Regions exist and inter-region communication is SSL
        // enabled, that the Regions correspond to valid HedwigSocketAddresses,
        // namely that SSL ports are present.
        if (isInterRegionSSLEnabled() && getRegions().size() > 0) {
            for (String hubString : getRegions()) {
                HedwigSocketAddress hub = new HedwigSocketAddress(hubString);
                if (hub.getSSLSocketAddress() == null)
                    throw new ConfigurationException("Region defined does not have required SSL port: " + hubString);
            }
        }

        // add other checks here
    }
}
