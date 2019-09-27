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

package org.apache.zookeeper.server;

import java.util.Random;
import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a token-bucket based rate limiting mechanism with optional
 * probabilistic dropping inspired by the BLUE queue management algorithm [1].
 *
 * The throttle provides the {@link #checkLimit(int)} method which provides
 * a binary yes/no decision.
 *
 * The core token bucket algorithm starts with an initial set of tokens based
 * on the <code>maxTokens</code> setting. Tokens are dispensed each
 * {@link #checkLimit(int)} call, which fails if there are not enough tokens to
 * satisfy a given request.
 *
 * The token bucket refills over time, providing <code>fillCount</code> tokens
 * every <code>fillTime</code> milliseconds, capping at <code>maxTokens</code>.
 *
 * This design allows the throttle to allow short bursts to pass, while still
 * capping the total number of requests per time interval.
 *
 * One issue with a pure token bucket approach for something like request or
 * connection throttling is that the wall clock arrival time of requests affects
 * the probability of a request being allowed to pass or not. Under constant
 * load this can lead to request starvation for requests that constantly arrive
 * later than the majority.
 *
 * In an attempt to combat this, this throttle can also provide probabilistic
 * dropping. This is enabled anytime <code>freezeTime</code> is set to a value
 * other than <code>-1</code>.
 *
 * The probabilistic algorithm starts with an initial drop probability of 0, and
 * adjusts this probability roughly every <code>freezeTime</code> milliseconds.
 * The first request after <code>freezeTime</code>, the algorithm checks the
 * token bucket. If the token bucket is empty, the drop probability is increased
 * by <code>dropIncrease</code> up to a maximum of <code>1</code>. Otherwise, if
 * the bucket has a token deficit less than <code>decreasePoint * maxTokens</code>,
 * the probability is decreased by <code>dropDecrease</code>.
 *
 * Given a call to {@link #checkLimit(int)}, requests are first dropped randomly
 * based on the current drop probability, and only surviving requests are then
 * checked against the token bucket.
 *
 * When under constant load, the probabilistic algorithm will adapt to a drop
 * frequency that should keep requests within the token limit. When load drops,
 * the drop probability will decrease, eventually returning to zero if possible.
 *
 * [1] "BLUE: A New Class of Active Queue Management Algorithms"
 **/

public class BlueThrottle {
    private static final Logger LOG = LoggerFactory.getLogger(BlueThrottle.class);

    private int maxTokens;
    private int fillTime;
    private int fillCount;
    private int tokens;
    private long lastTime;

    private int freezeTime;
    private long lastFreeze;
    private double dropIncrease;
    private double dropDecrease;
    private double decreasePoint;
    private double drop;

    Random rng;

    public static final String CONNECTION_THROTTLE_TOKENS = "zookeeper.connection_throttle_tokens";
    private static final int DEFAULT_CONNECTION_THROTTLE_TOKENS;

    public static final String CONNECTION_THROTTLE_FILL_TIME = "zookeeper.connection_throttle_fill_time";
    private static final int DEFAULT_CONNECTION_THROTTLE_FILL_TIME;

    public static final String CONNECTION_THROTTLE_FILL_COUNT = "zookeeper.connection_throttle_fill_count";
    private static final int DEFAULT_CONNECTION_THROTTLE_FILL_COUNT;

    public static final String CONNECTION_THROTTLE_FREEZE_TIME = "zookeeper.connection_throttle_freeze_time";
    private static final int DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME;

    public static final String CONNECTION_THROTTLE_DROP_INCREASE = "zookeeper.connection_throttle_drop_increase";
    private static final double DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE;

    public static final String CONNECTION_THROTTLE_DROP_DECREASE = "zookeeper.connection_throttle_drop_decrease";
    private static final double DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE;

    public static final String CONNECTION_THROTTLE_DECREASE_RATIO = "zookeeper.connection_throttle_decrease_ratio";
    private static final double DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO;

    public static final String WEIGHED_CONNECTION_THROTTLE = "zookeeper.connection_throttle_weight_enabled";
    private static boolean connectionWeightEnabled;

    public static final String GLOBAL_SESSION_WEIGHT = "zookeeper.connection_throttle_global_session_weight";
    private static final int DEFAULT_GLOBAL_SESSION_WEIGHT;

    public static final String LOCAL_SESSION_WEIGHT = "zookeeper.connection_throttle_local_session_weight";
    private static final int DEFAULT_LOCAL_SESSION_WEIGHT;

    public static final String RENEW_SESSION_WEIGHT = "zookeeper.connection_throttle_renew_session_weight";
    private static final int DEFAULT_RENEW_SESSION_WEIGHT;

    // for unit tests only
    protected  static void setConnectionWeightEnabled(boolean enabled) {
        connectionWeightEnabled = enabled;
        logWeighedThrottlingSetting();
    }

    private static void logWeighedThrottlingSetting() {
        if (connectionWeightEnabled) {
            LOG.info("Weighed connection throttling is enabled. "
                    + "But it will only be effective if connection throttling is enabled");
            LOG.info(
                    "The weights for different session types are: global {} renew {} local {}",
                    DEFAULT_GLOBAL_SESSION_WEIGHT,
                    DEFAULT_RENEW_SESSION_WEIGHT,
                    DEFAULT_LOCAL_SESSION_WEIGHT
            );
        } else {
            LOG.info("Weighed connection throttling is disabled");
        }
    }

    static {
        int tokens = Integer.getInteger(CONNECTION_THROTTLE_TOKENS, 0);
        int fillCount = Integer.getInteger(CONNECTION_THROTTLE_FILL_COUNT, 1);

        connectionWeightEnabled = Boolean.getBoolean(WEIGHED_CONNECTION_THROTTLE);

        // if not specified, the weights for a global session, a local session, and a renew session
        // are 3, 1, 2 respectively. The weight for a global session is 3 because in our connection benchmarking,
        // the throughput of global sessions is about one third of that of local sessions. Renewing a session
        // requires is more expensive than establishing a local session and cheaper than creating a global session so
        // its default weight is set to 2.
        int globalWeight = Integer.getInteger(GLOBAL_SESSION_WEIGHT, 3);
        int localWeight = Integer.getInteger(LOCAL_SESSION_WEIGHT, 1);
        int renewWeight = Integer.getInteger(RENEW_SESSION_WEIGHT, 2);

        if (globalWeight <= 0) {
            LOG.warn("Invalid global session weight {}. It should be larger than 0", globalWeight);
            DEFAULT_GLOBAL_SESSION_WEIGHT = 3;
        } else if (globalWeight < localWeight) {
            LOG.warn(
                "The global session weight {} is less than the local session weight {}. Use the local session weight.",
                globalWeight,
                localWeight);
            DEFAULT_GLOBAL_SESSION_WEIGHT = localWeight;
        } else {
            DEFAULT_GLOBAL_SESSION_WEIGHT = globalWeight;
        }

        if (localWeight <= 0) {
            LOG.warn("Invalid local session weight {}. It should be larger than 0", localWeight);
            DEFAULT_LOCAL_SESSION_WEIGHT = 1;
        } else {
            DEFAULT_LOCAL_SESSION_WEIGHT = localWeight;
        }

        if (renewWeight <= 0) {
            LOG.warn("Invalid renew session weight {}. It should be larger than 0", renewWeight);
            DEFAULT_RENEW_SESSION_WEIGHT = 2;
        } else if (renewWeight < localWeight) {
            LOG.warn(
                "The renew session weight {} is less than the local session weight {}. Use the local session weight.",
                renewWeight,
                localWeight);
            DEFAULT_RENEW_SESSION_WEIGHT = localWeight;
        } else {
            DEFAULT_RENEW_SESSION_WEIGHT = renewWeight;
        }

        // This is based on the assumption that tokens set in config are for global sessions
        DEFAULT_CONNECTION_THROTTLE_TOKENS = connectionWeightEnabled
                ? DEFAULT_GLOBAL_SESSION_WEIGHT * tokens : tokens;
        DEFAULT_CONNECTION_THROTTLE_FILL_TIME = Integer.getInteger(CONNECTION_THROTTLE_FILL_TIME, 1);
        DEFAULT_CONNECTION_THROTTLE_FILL_COUNT = connectionWeightEnabled
                ? DEFAULT_GLOBAL_SESSION_WEIGHT * fillCount : fillCount;
        DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME = Integer.getInteger(CONNECTION_THROTTLE_FREEZE_TIME, -1);
        DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE = getDoubleProp(CONNECTION_THROTTLE_DROP_INCREASE, 0.02);
        DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE = getDoubleProp(CONNECTION_THROTTLE_DROP_DECREASE, 0.002);
        DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO = getDoubleProp(CONNECTION_THROTTLE_DECREASE_RATIO, 0);

        logWeighedThrottlingSetting();
    }

    /* Varation of Integer.getInteger for real number properties */
    private static double getDoubleProp(String name, double def) {
        String val = System.getProperty(name);
        if (val != null) {
            return Double.parseDouble(val);
        } else {
            return def;
        }
    }

    public BlueThrottle() {
        // Disable throttling by default (maxTokens = 0)
        this.maxTokens = DEFAULT_CONNECTION_THROTTLE_TOKENS;
        this.fillTime = DEFAULT_CONNECTION_THROTTLE_FILL_TIME;
        this.fillCount = DEFAULT_CONNECTION_THROTTLE_FILL_COUNT;
        this.tokens = maxTokens;
        this.lastTime = Time.currentElapsedTime();

        // Disable BLUE throttling by default (freezeTime = -1)
        this.freezeTime = DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME;
        this.lastFreeze = Time.currentElapsedTime();
        this.dropIncrease = DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE;
        this.dropDecrease = DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE;
        this.decreasePoint = DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO;
        this.drop = 0;

        this.rng = new Random();
    }

    public synchronized void setMaxTokens(int max) {
        int deficit = maxTokens - tokens;
        maxTokens = max;
        tokens = max - deficit;
    }

    public synchronized void setFillTime(int time) {
        fillTime = time;
    }

    public synchronized void setFillCount(int count) {
        fillCount = count;
    }

    public synchronized void setFreezeTime(int time) {
        freezeTime = time;
    }

    public synchronized void setDropIncrease(double increase) {
        dropIncrease = increase;
    }

    public synchronized void setDropDecrease(double decrease) {
        dropDecrease = decrease;
    }

    public synchronized void setDecreasePoint(double ratio) {
        decreasePoint = ratio;
    }

    public synchronized int getMaxTokens() {
        return maxTokens;
    }

    public synchronized int getFillTime() {
        return fillTime;
    }

    public synchronized int getFillCount() {
        return fillCount;
    }

    public synchronized int getFreezeTime() {
        return freezeTime;
    }

    public synchronized double getDropIncrease() {
        return dropIncrease;
    }

    public synchronized double getDropDecrease() {
        return dropDecrease;
    }

    public synchronized double getDecreasePoint() {
        return decreasePoint;
    }

    public synchronized double getDropChance() {
        return drop;
    }

    public synchronized int getDeficit() {
        return maxTokens - tokens;
    }

    public int getRequiredTokensForGlobal() {
        return BlueThrottle.DEFAULT_GLOBAL_SESSION_WEIGHT;
    }

    public int getRequiredTokensForLocal() {
        return BlueThrottle.DEFAULT_LOCAL_SESSION_WEIGHT;
    }

    public int getRequiredTokensForRenew() {
        return BlueThrottle.DEFAULT_RENEW_SESSION_WEIGHT;
    }

    public boolean isConnectionWeightEnabled() {
        return BlueThrottle.connectionWeightEnabled;
    }

    public synchronized boolean checkLimit(int need) {
        // A maxTokens setting of zero disables throttling
        if (maxTokens == 0) {
            return true;
        }

        long now = Time.currentElapsedTime();
        long diff = now - lastTime;

        if (diff > fillTime) {
            int refill = (int) (diff * fillCount / fillTime);
            tokens = Math.min(tokens + refill, maxTokens);
            lastTime = now;
        }

        // A freeze time of -1 disables BLUE randomized throttling
        if (freezeTime != -1) {
            if (!checkBlue(now)) {
                return false;
            }
        }

        if (tokens < need) {
            return false;
        }

        tokens -= need;
        return true;
    }

    public synchronized boolean checkBlue(long now) {
        int length = maxTokens - tokens;
        int limit = maxTokens;
        long diff = now - lastFreeze;
        long threshold = Math.round(maxTokens * decreasePoint);

        if (diff > freezeTime) {
            if ((length == limit) && (drop < 1)) {
                drop = Math.min(drop + dropIncrease, 1);
            } else if ((length <= threshold) && (drop > 0)) {
                drop = Math.max(drop - dropDecrease, 0);
            }
            lastFreeze = now;
        }

        return !(rng.nextDouble() < drop);
    }

}
