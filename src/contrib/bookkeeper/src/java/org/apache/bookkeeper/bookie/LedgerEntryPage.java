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

package org.apache.bookkeeper.bookie;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This is a page in the LedgerCache. It holds the locations
 * (entrylogfile, offset) for entry ids.
 */
public class LedgerEntryPage {
    public static final int PAGE_SIZE = 8192;
    public static final int ENTRIES_PER_PAGES = PAGE_SIZE/8;
    private long ledger = -1;
    private long firstEntry = -1;
    private ByteBuffer page = ByteBuffer.allocateDirect(PAGE_SIZE);
    private boolean clean = true;
    private boolean pinned = false;
    private int useCount;
    private int version;
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getLedger());
        sb.append('@');
        sb.append(getFirstEntry());
        sb.append(clean ? " clean " : " dirty ");
        sb.append(useCount);
        return sb.toString();
    }
    synchronized public void usePage() {
        useCount++;
    }
    synchronized public void pin() {
        pinned = true;
    }
    synchronized public void unpin() {
        pinned = false;
    }
    synchronized public boolean isPinned() {
        return pinned;
    }
    synchronized public void releasePage() {
        useCount--;
        if (useCount < 0) {
            throw new IllegalStateException("Use count has gone below 0");
        }
    }
    synchronized private void checkPage() {
        if (useCount <= 0) {
            throw new IllegalStateException("Page not marked in use");
        }
    }
    @Override
    public boolean equals(Object other) {
        LedgerEntryPage otherLEP = (LedgerEntryPage) other;
        return otherLEP.getLedger() == getLedger() && otherLEP.getFirstEntry() == getFirstEntry();
    }
    @Override
    public int hashCode() {
        return (int)getLedger() ^ (int)(getFirstEntry());
    }
    void setClean(int versionOfCleaning) {
        this.clean = (versionOfCleaning == version);
    }
    boolean isClean() {
        return clean;
    }
    public void setOffset(long offset, int position) {
        checkPage();
        version++;
        this.clean = false;
        page.putLong(position, offset);
    }
    public long getOffset(int position) {
        checkPage();
        return page.getLong(position);
    }
    static final byte zeroPage[] = new byte[64*1024];
    public void zeroPage() {
        checkPage();
        page.clear();
        page.put(zeroPage, 0, page.remaining());
        clean = true;
    }
    public void readPage(FileInfo fi) throws IOException {
        checkPage();
        page.clear();
        while(page.remaining() != 0) {
            if (fi.read(page, getFirstEntry()*8) <= 0) {
                throw new IOException("Short page read of ledger " + getLedger() + " tried to get " + page.capacity() + " from position " + getFirstEntry()*8 + " still need " + page.remaining());
            }
        }
        clean = true;
    }
    public ByteBuffer getPageToWrite() {
        checkPage();
        page.clear();
        return page;
    }
    void setLedger(long ledger) {
        this.ledger = ledger;
    }
    long getLedger() {
        return ledger;
    }
    int getVersion() {
        return version;
    }
    void setFirstEntry(long firstEntry) {
        if (firstEntry % ENTRIES_PER_PAGES != 0) {
            throw new IllegalArgumentException(firstEntry + " is not a multiple of " + ENTRIES_PER_PAGES);
        }
        this.firstEntry = firstEntry;
    }
    long getFirstEntry() {
        return firstEntry;
    }
    public boolean inUse() {
        return useCount > 0;
    }
    public long getLastEntry() {
        for(int i = ENTRIES_PER_PAGES - 1; i >= 0; i--) {
            if (getOffset(i*8) > 0) {
                return i + firstEntry;
            }
        }
        return 0;
    }
}
