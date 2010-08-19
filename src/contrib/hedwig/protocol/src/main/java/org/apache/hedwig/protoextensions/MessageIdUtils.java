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
package org.apache.hedwig.protoextensions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.exceptions.PubSubException.UnexpectedConditionException;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protocol.PubSubProtocol.RegionSpecificSeqId;

public class MessageIdUtils {

    public static String msgIdToReadableString(MessageSeqId seqId) {
        StringBuilder sb = new StringBuilder();
        sb.append("local:");
        sb.append(seqId.getLocalComponent());

        String separator = ";";
        for (RegionSpecificSeqId regionId : seqId.getRemoteComponentsList()) {
            sb.append(separator);
            sb.append(regionId.getRegion().toStringUtf8());
            sb.append(':');
            sb.append(regionId.getSeqId());
        }
        return sb.toString();
    }

    public static Map<ByteString, RegionSpecificSeqId> inMapForm(MessageSeqId msi) {
        Map<ByteString, RegionSpecificSeqId> map = new HashMap<ByteString, RegionSpecificSeqId>();

        for (RegionSpecificSeqId lmsid : msi.getRemoteComponentsList()) {
            map.put(lmsid.getRegion(), lmsid);
        }

        return map;
    }

    public static boolean areEqual(MessageSeqId m1, MessageSeqId m2) {

        if (m1.getLocalComponent() != m2.getLocalComponent()) {
            return false;
        }

        if (m1.getRemoteComponentsCount() != m2.getRemoteComponentsCount()) {
            return false;
        }

        Map<ByteString, RegionSpecificSeqId> m2map = inMapForm(m2);

        for (RegionSpecificSeqId lmsid1 : m1.getRemoteComponentsList()) {
            RegionSpecificSeqId lmsid2 = m2map.get(lmsid1.getRegion());
            if (lmsid2 == null) {
                return false;
            }
            if (lmsid1.getSeqId() != lmsid2.getSeqId()) {
                return false;
            }
        }

        return true;

    }

    public static Message mergeLocalSeqId(Message.Builder messageBuilder, long localSeqId) {
        MessageSeqId.Builder msidBuilder = MessageSeqId.newBuilder(messageBuilder.getMsgId());
        msidBuilder.setLocalComponent(localSeqId);
        messageBuilder.setMsgId(msidBuilder);
        return messageBuilder.build();
    }

    public static Message mergeLocalSeqId(Message orginalMessage, long localSeqId) {
        return mergeLocalSeqId(Message.newBuilder(orginalMessage), localSeqId);
    }

    /**
     * Compares two seq numbers represented as lists of longs.
     * 
     * @param l1
     * @param l2
     * @return 1 if the l1 is greater, 0 if they are equal, -1 if l2 is greater
     * @throws UnexpectedConditionException
     *             If the lists are of unequal length
     */
    public static int compare(List<Long> l1, List<Long> l2) throws UnexpectedConditionException {
        if (l1.size() != l2.size()) {
            throw new UnexpectedConditionException("Seq-ids being compared have different sizes: " + l1.size()
                    + " and " + l2.size());
        }

        for (int i = 0; i < l1.size(); i++) {
            long v1 = l1.get(i);
            long v2 = l2.get(i);

            if (v1 == v2) {
                continue;
            }

            return v1 > v2 ? 1 : -1;
        }

        // All components equal
        return 0;
    }

    /**
     * Returns the element-wise vector maximum of the two vectors id1 and id2,
     * if we imagine them to be sparse representations of vectors.
     */
    public static void takeRegionMaximum(MessageSeqId.Builder newIdBuilder, MessageSeqId id1, MessageSeqId id2) {
        Map<ByteString, RegionSpecificSeqId> id2Map = MessageIdUtils.inMapForm(id2);

        for (RegionSpecificSeqId rrsid1 : id1.getRemoteComponentsList()) {
            ByteString region = rrsid1.getRegion();

            RegionSpecificSeqId rssid2 = id2Map.get(region);

            if (rssid2 == null) {
                newIdBuilder.addRemoteComponents(rrsid1);
                continue;
            }

            newIdBuilder.addRemoteComponents((rrsid1.getSeqId() > rssid2.getSeqId()) ? rrsid1 : rssid2);

            // remove from map
            id2Map.remove(region);
        }

        // now take the remaining components in the map and add them
        for (RegionSpecificSeqId rssid2 : id2Map.values()) {
            newIdBuilder.addRemoteComponents(rssid2);
        }

    }
}
