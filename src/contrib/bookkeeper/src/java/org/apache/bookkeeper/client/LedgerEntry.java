package org.apache.bookkeeper.client;

/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

import java.io.IOException;
import java.io.InputStream;

import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBufferInputStream;

/**
 * Ledger entry. Its a simple tuple containing the ledger id, the entry-id, and
 * the entry content.
 * 
 */

public class LedgerEntry {
  Logger LOG = Logger.getLogger(LedgerEntry.class);

  long ledgerId;
  long entryId;
  ChannelBufferInputStream entryDataStream;

  int nextReplicaIndexToReadFrom = 0;

  LedgerEntry(long lId, long eId) {
    this.ledgerId = lId;
    this.entryId = eId;
  }

  public long getLedgerId() {
    return ledgerId;
  }

  public long getEntryId() {
    return entryId;
  }

  public byte[] getEntry() {
    try {
      // In general, you can't rely on the available() method of an input
      // stream, but ChannelBufferInputStream is backed by a byte[] so it
      // accurately knows the # bytes available
      byte[] ret = new byte[entryDataStream.available()];
      entryDataStream.readFully(ret);
      return ret;
    } catch (IOException e) {
      // The channelbufferinput stream doesnt really throw the
      // ioexceptions, it just has to be in the signature because
      // InputStream says so. Hence this code, should never be reached.
      LOG.fatal("Unexpected IOException while reading from channel buffer", e);
      return new byte[0];
    }
  }

  public InputStream getEntryInputStream() {
    return entryDataStream;
  }
}
