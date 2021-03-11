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

package org.apache.zookeeper.log;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.xml.DOMConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches log4j configuration file for a change.
 * Reloads the configuration into memory whenever there is a change log4j configuration file.
 */
public class Log4jConfigWatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(Log4jConfigWatcher.class);
    private static final String DEFAULT_LOG4J_CONFIG = "log4j.properties";

    // If log4j configuration is already watched then skip starting watch again
    private static boolean isWatching = false;
    static final String LOG4J_CONFIGURATION_WATCH = "zookeeper.log4j.configuration.watch";
    static final String LOG4J_CONFIGURATION_WATCH_INTERVAL =
        "zookeeper.log4j.configuration.watch.interval";
    // Default 60 seconds
    private static final int LOG4J_CONFIGURATION_WATCH_DEFAULT = 60000;
    static final String LOG4J_CONFIGURATION = "zookeeper.log4j.configuration";

    /**
     * Watches log4j configuration file for a change if Log4jConfigWatcher feature is enabled.
     * Reloads the configuration into memory whenever there is a change in configuration file.
     * This method should be called only once per JVM. One thread
     * will be created to watch log4j configuration changes.
     */
    public static synchronized void watchLog4jConfiguration() {
        if (isWatching) {
            // Already watching, no need to initialize watcher again
            return;
        }

        // This feature is disabled by default
        boolean log4jConfigWatchEnable = Boolean.getBoolean(LOG4J_CONFIGURATION_WATCH);
        if (log4jConfigWatchEnable) {
            LOGGER.info("log4j config watcher is enabled.");
        } else {
            LOGGER.info("log4j config watcher is disabled.");
            return;
        }

        URL log4jUrl = getLog4jConfigUrl();
        if (log4jUrl == null) {
            LOGGER.warn("No log4j configuration file found to watch.");
            return;
        }

        String log4jConfigPath = log4jUrl.getPath();
        if (!new File(log4jConfigPath).exists()) {
            LOGGER.warn("Not watching for log4j config changes as file {} does not exist.",
                log4jConfigPath);
            return;
        }
        int log4jConfigWatchInterval = getLog4jConfigWatchInterval();
        if (log4jConfigPath.endsWith(".xml")) {
            DOMConfigurator.configureAndWatch(log4jUrl.getPath(), log4jConfigWatchInterval);
        } else {
            PropertyConfigurator.configureAndWatch(log4jUrl.getPath(), log4jConfigWatchInterval);
        }
        isWatching = true;
        LOGGER.info("Watching {} for changes with interval {} ms ", log4jConfigPath,
            log4jConfigWatchInterval);
    }

    private static int getLog4jConfigWatchInterval() {
        String intervalProp = getConfig(LOG4J_CONFIGURATION_WATCH_INTERVAL);
        if (intervalProp != null) {
            try {
                return Integer.parseInt(intervalProp);
            } catch (NumberFormatException e) {
                LOGGER.warn("Error while parsing interval property {} to integer."
                        + " Default interval {} will be used", intervalProp,
                    LOG4J_CONFIGURATION_WATCH_DEFAULT);
            }
        }
        return LOG4J_CONFIGURATION_WATCH_DEFAULT;
    }

    private static String getConfig(String configName) {
        String property = System.getProperty(configName);
        return property == null ? null : property.trim();
    }

    private static URL getLog4jConfigUrl() {
        String log4jConfiguration = getConfig(LOG4J_CONFIGURATION);
        URL log4jUrl = null;
        if (null != log4jConfiguration && !log4jConfiguration.isEmpty()) {
            // Load the configuration if its configured.
            try {
                return new URL(log4jConfiguration);
            } catch (final MalformedURLException e) {
                // Ignore it, this happens when only file name is specified
                // which should be searched in classpath
            }
            log4jUrl = getUrl(log4jConfiguration);
        } else {
            log4jUrl = getUrl(DEFAULT_LOG4J_CONFIG);
        }
        return log4jUrl;
    }

    private static URL getUrl(String log4jConfiguration) {
        try {
            Enumeration<URL> resources =
                Log4jConfigWatcher.class.getClassLoader().getResources(log4jConfiguration);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (url.getPath().contains(".jar")) {
                    // log4j configuration file in jar file can not be watched for modification
                    LOGGER.debug("Skipping log4j configuration {}", url.getPath());
                    continue;
                }
                return url;
            }
        } catch (IOException e) {
            LOGGER.warn("Error while searching resource {}, error={}", log4jConfiguration, e);
        }
        return null;
    }
}
