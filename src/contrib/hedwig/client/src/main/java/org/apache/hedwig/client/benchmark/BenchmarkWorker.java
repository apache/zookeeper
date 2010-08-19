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
package org.apache.hedwig.client.benchmark;

public class BenchmarkWorker {
    int numTopics;
    int numMessages;
    int numRegions;
    int startTopicLabel;
    int partitionIndex;
    int numPartitions;

    public BenchmarkWorker(int numTopics, int numMessages, int numRegions,
            int startTopicLabel, int partitionIndex, int numPartitions) {
        this.numTopics = numTopics;
        this.numMessages = numMessages;
        this.numRegions = numRegions;
        this.startTopicLabel = startTopicLabel;
        this.partitionIndex = partitionIndex;
        this.numPartitions = numPartitions;

        if (numMessages % (numTopics * numRegions) != 0) {
            throw new RuntimeException("Number of messages not equally divisible among regions and topics");
        }

        if (numTopics % numPartitions != 0) {
            throw new RuntimeException("Number of topics not equally divisible among partitions");
        }

    }
}
