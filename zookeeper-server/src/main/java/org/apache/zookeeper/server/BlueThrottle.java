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

package org.apache.zookeeper.server;

import java.util.Random;

import org.apache.zookeeper.common.Time;

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

    public BlueThrottle() {
        // Disable throttling by default (maxTokens = 0)
        this.maxTokens = 0;
        this.fillTime  = 1;
        this.fillCount = 1;
        this.tokens = maxTokens;
        this.lastTime = Time.currentElapsedTime();

        // Disable BLUE throttling by default (freezeTime = -1)
        this.freezeTime = -1;
        this.lastFreeze = Time.currentElapsedTime();
        this.dropIncrease = 0.02;
        this.dropDecrease = 0.002;
        this.decreasePoint = 0;
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

    public double getDropChance() {
        return drop;
    }

    public int getDeficit() {
        return maxTokens - tokens;
    }

    public synchronized boolean checkLimit(int need) {
        // A maxTokens setting of zero disables throttling
        if (maxTokens == 0)
            return true;

        long now = Time.currentElapsedTime();
        long diff = now - lastTime;

        if (diff > fillTime) {
            int refill = (int)(diff * fillCount / fillTime);
            tokens = Math.min(tokens + refill, maxTokens);
            lastTime = now;
        }

        // A freeze time of -1 disables BLUE randomized throttling
        if(freezeTime != -1) {
            if(!checkBlue(now)) {
                return false;
            }
        }

        if (tokens < need) {
            return false;
        }

        tokens -= need;
        return true;
    }

    public boolean checkBlue(long now) {
        int length = maxTokens - tokens;
        int limit = maxTokens;
        long diff = now - lastFreeze;
        long threshold = Math.round(maxTokens * decreasePoint);

        if (diff > freezeTime) {
            if((length == limit) && (drop < 1)) {
                drop += dropIncrease;
            }
            else if ((length <= threshold) && (drop >= dropDecrease)) {
                drop -= dropDecrease;
            }
            lastFreeze = now;
        }

        if (rng.nextDouble() <= drop) {
            return false;
        }
        return true;
    }
};
