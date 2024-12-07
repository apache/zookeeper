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

package org.apache.zookeeper.server.persistence;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Range;

/**
 * A range of Zxids with an optional high zxid.
 */
public class ZxidRange {
  public static final ZxidRange INVALID = new ZxidRange(-1L, Optional.of(-1L), true);

  private long low;
  private Optional<Long> high;

  private ZxidRange(long low, Optional<Long> high, boolean allowNeg) {
    Preconditions.checkNotNull(high);
    Preconditions.checkArgument(allowNeg || low >= 0, "Zxid must be 0 or greater");
    Preconditions.checkArgument(allowNeg || !high.isPresent() || high.get() >= 0,
        "Zxid must be 0 or greater");
    Preconditions.checkArgument(!high.isPresent() || low <= high.get(),
        "Invalid zxid range, low must not be greater than high");

    this.low = low;
    this.high = high;
  }

  public ZxidRange(long low, long high) {
    this(low, Optional.of(high), false);
  }

  public ZxidRange(long low) {
    this(low, Optional.<Long>absent(), false);
  }

  public ZxidRange(Range<Long> range) {
    this(range.lowerEndpoint(), range.upperEndpoint());
  }

  public static ZxidRange parse(String value) {
    String[] zxidParts = value.split("-");

    if (zxidParts.length == 1 && !value.endsWith("-")) {
      try {
        return new ZxidRange(Long.parseLong(zxidParts[0], 16));
      } catch (NumberFormatException e) { }
    } else if (zxidParts.length == 2) {
      try {
        return new ZxidRange(Long.parseLong(zxidParts[0], 16), Long.parseLong(zxidParts[1], 16));
      } catch (NumberFormatException e) { }
    }

    return ZxidRange.INVALID;
  }

  public Range<Long> toRange() {
    return Range.closed(low, high.isPresent() ? high.get() : Long.MAX_VALUE);
  }

  public long getLow() {
    return low;
  }

  public long getHigh() {
    Preconditions.checkState(high.isPresent());
    return high.get();
  }

  public boolean isHighPresent() {
    return high.isPresent();
  }
}