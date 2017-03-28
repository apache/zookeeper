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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection rate limiter.
 *
 */
public interface RateLimiter {
    /**
     * Config file property for the {@code RateLimiter} implementation to use
     */
    String RATE_LIMITER_IMPL = "rateLimiterImpl";
    /**
     * A {@code RateLimiter} that does not do any rate limiting
     */
    RateLimiter NOOP_RATE_LIMITER = new RateLimiter() {
        @Override
        public boolean tryAquire() {
            return true;
        }
    };

    /**
     * Attempts to acquire a permit
     *
     * @return true if a permit was acquired, false otherwise
     */
    public boolean tryAquire();

    class Factory {
        private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.Factory.class);

        /**
         * Creates a {@code RateLimiter} from the passed in implementation class type
         *
         * @param rateLimiterImplClass
         *            the {@code RateLimiter} implementation to use. If set to
         *            null, then this defaults to {@code NOOP_RATE_LIMITER}.
         * @return the {@code RateLimiter}
         */
        public static RateLimiter create(String rateLimiterImplClass) {
            if (rateLimiterImplClass == null) {
                LOG.debug(
                        "No rateLimiterImplClass specified - defaulting to noop (rate not limited)");
                return NOOP_RATE_LIMITER;
            }
            try {
                LOG.debug("Initializing rate limiter with rateLimiterImpl={}",
                        rateLimiterImplClass);
                RateLimiter limiter = (RateLimiter) Class.forName(rateLimiterImplClass)
                        .newInstance();
                return limiter;
            } catch (Exception e) {
                LOG.error("Error instantiating RateLimiter", e);
                return NOOP_RATE_LIMITER;
            }
        }
    }
}
