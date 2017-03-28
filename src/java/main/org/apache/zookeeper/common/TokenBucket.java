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
package org.apache.zookeeper.common;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

/**
 * Simple rate limiter based on a token bucket.
 *
 * Tokens are added at a constant rate R per second to a bucket with maximum
 * capacity B.
 * Each connection request takes one token out of the bucket.
 * This allows for a fixed rate of connections R with bursts up to B.
 * i.e. with R=1 and B=10, a client is limited to 1 connection per second,
 * with bursts up to 10 connections in a second.
 *
 * R can be configured with the property {@code tokenBucket.maxClientCnxnRate}
 * B can be configured with the property {@code tokenBucket.maxClientCnxnBurst}
 *
 * https://en.wikipedia.org/wiki/Token_bucket
 */
public class TokenBucket implements RateLimiter {
    /**
     * Config file property for the maximum average rate of client connections per second
     */
    public static final String MAX_CLIENT_CNXN_RATE = "tokenBucket.maxClientCnxnRate";

    /**
     * Config file property for the maximum burst size of client connections per second
     */
    public static final String MAX_CLIENT_CNXN_BURST = "tokenBucket.maxClientCnxnBurst";

    // VisibleForTesting
    long refreshPeriodMillis = TimeUnit.SECONDS.toMillis(1L);
    // VisibleForTesting
    volatile long nextRefillTime;
    private AtomicLong tokens;
    private long capacity;
    private long tokensPerPeriod;

    public TokenBucket() {
        Properties properties = QuorumPeerConfig.getConfig().getProperties();
        String rateStr = System.getProperty(MAX_CLIENT_CNXN_RATE);
        if (rateStr == null) {
            rateStr = properties.getProperty(MAX_CLIENT_CNXN_RATE, "1");
        }
        long rate = Long.parseLong(rateStr);

        String burstStr = System.getProperty(MAX_CLIENT_CNXN_BURST);
        if (burstStr == null) {
            burstStr = properties.getProperty(MAX_CLIENT_CNXN_BURST, "10");
        }
        long burst = Long.parseLong(burstStr);

        init(burst, rate, burst);
    }

    // VisibleForTesting
    public TokenBucket(long capacity, long tokensPerSecond, long initialTokens) {
        init(capacity, tokensPerSecond, initialTokens);
    }

    private void init(long capacity, long tokensPerSecond, long initialTokens) {
        this.tokens = new AtomicLong(initialTokens);
        this.capacity = Math.max(capacity, tokensPerSecond);
        this.tokensPerPeriod = tokensPerSecond;
        this.nextRefillTime = Time.currentElapsedTime() + refreshPeriodMillis;
    }

    @Override
    public boolean tryAquire() {
        refill();
        long current = tokens.get();
        while (current > 0) {
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
            current = tokens.get();
        }
        return false;
    }

    private void refill() {
        long currentTimeMillis = Time.currentElapsedTime();
        if (currentTimeMillis >= nextRefillTime) {
            synchronized (this) {
                if (currentTimeMillis >= nextRefillTime) {
                    long elapsedMillis = currentTimeMillis - nextRefillTime + refreshPeriodMillis;
                    long elapsedPeriods = elapsedMillis / refreshPeriodMillis;
                    long newTokens = elapsedPeriods * tokensPerPeriod;
                    tokens.set(Math.min(capacity, tokens.get() + newTokens));
                    nextRefillTime = currentTimeMillis + refreshPeriodMillis - (elapsedMillis % refreshPeriodMillis);
                }
            }
        }
    }

    // VisibleForTesting
    protected long getTokenCount() {
        return tokens.get();
    }

    @Override
    public String toString() {
        return "TokenBucket [tokens=" + tokens + ", capacity=" + capacity + ", tokensPerPeriod=" + tokensPerPeriod
                + "]";
    }
}
