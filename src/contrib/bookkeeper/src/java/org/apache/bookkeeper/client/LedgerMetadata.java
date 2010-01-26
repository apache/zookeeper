package org.apache.bookkeeper.client;

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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.bookkeeper.util.StringUtils;
import org.apache.log4j.Logger;

/**
 * This class encapsulates all the ledger metadata that is persistently stored
 * in zookeeper. It provides parsing and serialization methods of such metadata.
 * 
 */
class LedgerMetadata {
    static final Logger LOG = Logger.getLogger(LedgerMetadata.class);

    private static final String closed = "CLOSED";
    private static final String lSplitter = "\n";
    private static final String tSplitter = "\t";

    // can't use -1 for NOTCLOSED because that is reserved for a closed, empty
    // ledger
    public static final int NOTCLOSED = -101;
    int ensembleSize;
    int quorumSize;
    long close;
    private SortedMap<Long, ArrayList<InetSocketAddress>> ensembles = new TreeMap<Long, ArrayList<InetSocketAddress>>();
    ArrayList<InetSocketAddress> currentEnsemble;

    public LedgerMetadata(int ensembleSize, int quorumSize) {
        this.ensembleSize = ensembleSize;
        this.quorumSize = quorumSize;
        this.close = NOTCLOSED;
    };

    private LedgerMetadata() {
        this(0, 0);
    }

    boolean isClosed() {
        return close != NOTCLOSED;
    }

    void close(long entryId) {
        close = entryId;
    }

    void addEnsemble(long startEntryId, ArrayList<InetSocketAddress> ensemble) {
        assert ensembles.isEmpty() || startEntryId >= ensembles.lastKey();

        ensembles.put(startEntryId, ensemble);
        currentEnsemble = ensemble;
    }

    ArrayList<InetSocketAddress> getEnsemble(long entryId) {
        // the head map cannot be empty, since we insert an ensemble for
        // entry-id 0, right when we start
        return ensembles.get(ensembles.headMap(entryId + 1).lastKey());
    }

    /**
     * the entry id > the given entry-id at which the next ensemble change takes
     * place ( -1 if no further ensemble changes)
     * 
     * @param entryId
     * @return
     */
    long getNextEnsembleChange(long entryId) {
        SortedMap<Long, ArrayList<InetSocketAddress>> tailMap = ensembles.tailMap(entryId + 1);

        if (tailMap.isEmpty()) {
            return -1;
        } else {
            return tailMap.firstKey();
        }
    }

    /**
     * Generates a byte array based on a LedgerConfig object received.
     * 
     * @param config
     *            LedgerConfig object
     * @return byte[]
     */
    public byte[] serialize() {
        StringBuilder s = new StringBuilder();
        s.append(quorumSize).append(lSplitter).append(ensembleSize);

        for (Map.Entry<Long, ArrayList<InetSocketAddress>> entry : ensembles.entrySet()) {
            s.append(lSplitter).append(entry.getKey());
            for (InetSocketAddress addr : entry.getValue()) {
                s.append(tSplitter);
                StringUtils.addrToString(s, addr);
            }
        }

        if (close != NOTCLOSED) {
            s.append(lSplitter).append(close).append(tSplitter).append(closed);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Serialized config: " + s.toString());
        }

        return s.toString().getBytes();
    }

    /**
     * Parses a given byte array and transforms into a LedgerConfig object
     * 
     * @param array
     *            byte array to parse
     * @return LedgerConfig
     * @throws IOException
     *             if the given byte[] cannot be parsed
     */

    static LedgerMetadata parseConfig(byte[] bytes) throws IOException {

        LedgerMetadata lc = new LedgerMetadata();
        String config = new String(bytes);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Parsing Config: " + config);
        }

        String lines[] = config.split(lSplitter);

        if (lines.length < 2) {
            throw new IOException("Quorum size or ensemble size absent from config: " + config);
        }

        try {
            lc.quorumSize = new Integer(lines[0]);
            lc.ensembleSize = new Integer(lines[1]);

            for (int i = 2; i < lines.length; i++) {
                String parts[] = lines[i].split(tSplitter);

                if (parts[1].equals(closed)) {
                    lc.close = new Long(parts[0]);
                    break;
                }

                ArrayList<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>();
                for (int j = 1; j < parts.length; j++) {
                    addrs.add(StringUtils.parseAddr(parts[j]));
                }
                lc.addEnsemble(new Long(parts[0]), addrs);
            }
        } catch (NumberFormatException e) {
            throw new IOException(e);
        }
        return lc;
    }

}
