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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import org.apache.bookkeeper.client.BKException.BKDigestMatchException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;

/**
 * This class takes an entry, attaches a digest to it and packages it with relevant
 * data so that it can be shipped to the bookie. On the return side, it also
 * gets a packet, checks that the digest matches, and extracts the original entry
 * for the packet. Currently 2 types of digests are supported: MAC (based on SHA-1) and CRC32
 */

abstract class DigestManager {
    static final Logger logger = Logger.getLogger(DigestManager.class);

    long ledgerId;
    
    abstract int getMacCodeLength();
    
    void update(byte[] data){
        update(data, 0, data.length);
    }
    
    abstract void update(byte[] data, int offset, int length);
    abstract byte[] getValueAndReset();
    
    final int macCodeLength;

    public DigestManager(long ledgerId) {
        this.ledgerId = ledgerId;
        macCodeLength = getMacCodeLength();
    }
    
    static DigestManager instantiate(long ledgerId, byte[] passwd, DigestType digestType) throws GeneralSecurityException{
        switch(digestType){
        case MAC:
            return new MacDigestManager(ledgerId, passwd);
        case CRC32:
            return new CRC32DigestManager(ledgerId);
        default:
            throw new GeneralSecurityException("Unknown checksum type: " + digestType);
        }
    }

    ChannelBuffer computeDigestAndPackageForSending(long entryId, long lastAddConfirmed, byte[] data) {

        byte[] bufferArray = new byte[24+macCodeLength];
        ByteBuffer buffer = ByteBuffer.wrap(bufferArray);
        buffer.putLong(ledgerId);
        buffer.putLong(entryId);
        buffer.putLong(lastAddConfirmed);
        buffer.flip();

        update(buffer.array(), 0, 24);
        update(data);
        byte[] digest = getValueAndReset();

        buffer.limit(buffer.capacity());
        buffer.position(24);
        buffer.put(digest);
        buffer.flip();

        return ChannelBuffers.wrappedBuffer(ChannelBuffers.wrappedBuffer(buffer), ChannelBuffers.wrappedBuffer(data));
    }

    private void verifyDigest(ChannelBuffer dataReceived) throws BKDigestMatchException {
        verifyDigest(-1, dataReceived, true);
    }

    private void verifyDigest(long entryId, ChannelBuffer dataReceived) throws BKDigestMatchException {
        verifyDigest(entryId, dataReceived, false);
    }

    private void verifyDigest(long entryId, ChannelBuffer dataReceived, boolean skipEntryIdCheck)
            throws BKDigestMatchException {

        ByteBuffer dataReceivedBuffer = dataReceived.toByteBuffer();
        byte[] digest;

        update(dataReceivedBuffer.array(), dataReceivedBuffer.position(), 24);

        int offset = 24 + macCodeLength;
        update(dataReceivedBuffer.array(), dataReceivedBuffer.position() + offset, dataReceived.readableBytes() - offset);
        digest = getValueAndReset();

        for (int i = 0; i < digest.length; i++) {
            if (digest[i] != dataReceived.getByte(24 + i)) {
                logger.error("Mac mismatch for ledger-id: " + ledgerId + ", entry-id: " + entryId);
                throw new BKDigestMatchException();
            }
        }

        long actualLedgerId = dataReceived.readLong();
        long actualEntryId = dataReceived.readLong();

        if (actualLedgerId != ledgerId) {
            logger.error("Ledger-id mismatch in authenticated message, expected: " + ledgerId + " , actual: "
                    + actualLedgerId);
            throw new BKDigestMatchException();
        }

        if (!skipEntryIdCheck && actualEntryId != entryId) {
            logger.error("Entry-id mismatch in authenticated message, expected: " + entryId + " , actual: "
                    + actualEntryId);
            throw new BKDigestMatchException();
        }

    }

    ChannelBufferInputStream verifyDigestAndReturnData(long entryId, ChannelBuffer dataReceived)
            throws BKDigestMatchException {
        verifyDigest(entryId, dataReceived);
        dataReceived.readerIndex(24 + macCodeLength);
        return new ChannelBufferInputStream(dataReceived);
    }

    static class RecoveryData {
        long lastAddConfirmed;
        long entryId;

        public RecoveryData(long lastAddConfirmed, long entryId) {
            this.lastAddConfirmed = lastAddConfirmed;
            this.entryId = entryId;
        }

    }

    RecoveryData verifyDigestAndReturnLastConfirmed(ChannelBuffer dataReceived) throws BKDigestMatchException {
        verifyDigest(dataReceived);
        dataReceived.readerIndex(8);

        long entryId = dataReceived.readLong();
        long lastAddConfirmed = dataReceived.readLong();
        return new RecoveryData(lastAddConfirmed, entryId);

    }
}
