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
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.ArrayList;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookieHandle;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.log4j.Logger;




/**
 * Ledger handle on the client side. Contains ledger metadata
 * used to access it.
 * 
 */

public class LedgerHandle {
    Logger LOG = Logger.getLogger(LedgerHandle.class);
    
    public enum QMode {VERIFIABLE, GENERIC, FREEFORM};
    
    
    private long ledger;
    private volatile long last;
    private volatile long lastAddConfirmed = 0;
    private ArrayList<BookieHandle> bookies;
    private ArrayList<InetSocketAddress> bookieAddrList;
    private BookKeeper bk;

    private int qSize;
    private QMode qMode = QMode.VERIFIABLE;

    private int threshold;
    private String digestAlg = "SHA1";
    
    private byte[] passwdHash;
    private byte[] passwd;
    
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();
        this.passwd = passwd;
        genPasswdHash(passwd);

        this.qSize = (bookies.size() + 1)/2;
    }
    
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            int qSize, 
            QMode mode,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();

        this.qSize = qSize;
        this.qMode = mode;
        this.passwd = passwd;
        genPasswdHash(passwd);
    }
        
        
    LedgerHandle(BookKeeper bk, 
            long ledger, 
            long last,
            int qSize,
            byte[] passwd) throws InterruptedException {
        this.bk = bk;
        this.ledger = ledger;
        this.last = last;
        this.bookies = new ArrayList<BookieHandle>();

        this.qSize = qSize;
        this.passwd = passwd;
        genPasswdHash(passwd);
    }
    
    private void setBookies(ArrayList<InetSocketAddress> bookies)
    throws InterruptedException {
        for(InetSocketAddress a : bookies){
            LOG.debug("Opening bookieHandle: " + a);
            try{
                BookieHandle bh = new BookieHandle(this, a);
                this.bookies.add(bh);
            } catch(ConnectException e){
                LOG.error(e + "(bookie: " + a + ")");
                
                InetSocketAddress addr = null;
                addr = bk.getNewBookie(bookies);
                
                if(addr != null){
                    bookies.add(addr);
                }
            } catch(IOException e) {
                LOG.error(e);
            }
        }
    }
    
    
    /**
     * Create bookie handle and add it to the list
     * 
     * @param addr	socket address
     */
    void addBookie(InetSocketAddress addr)
    throws IOException {
        BookieHandle bh = new BookieHandle(this, addr);
        this.bookies.add(bh);
        
        if(bookies.size() > qSize) setThreshold();
    }
    
    private void setThreshold(){
        switch(qMode){
        case GENERIC:
            threshold = bookies.size() - qSize/2;
            break;
        case VERIFIABLE:
            threshold = bookies.size() - qSize + 1;
            break;
        default:
            threshold = bookies.size();
        }
        
    }
    
    public int getThreshold(){
        return threshold;
    }
    
    /**
     * Replace bookie in the case of a failure 
     */
    
    void replaceBookie(int index) 
    throws BKException {
        InetSocketAddress addr = null;
        try{
            addr = bk.getNewBookie(bookieAddrList);
        } catch(InterruptedException e){
            LOG.error(e);
        }
        
        if(addr == null){
            throw BKException.create(Code.NoBookieAvailableException);
        } else {           
            try{
                BookieHandle bh = new BookieHandle(this, addr);
                
                /*
                 * TODO: Read from current bookies, and write to this one
                 */
                
                /*
                 * If successful in writing to new bookie, add it to the set
                 */
                this.bookies.set(index, bh);
            } catch(ConnectException e){
                bk.blackListBookie(addr);
                LOG.error(e);
            } catch(IOException e) {
                bk.blackListBookie(addr);
                LOG.error(e);
            }
        }
    }
    
    /**
     * This method is used when BK cannot find a bookie
     * to replace the current faulty one. In such cases,
     * we simply remove the bookie.
     * 
     * @param index
     */
    void removeBookie(int index){
        bookies.remove(index);
    }
    
    void close(){
        ledger = -1;
        last = -1;
        for(BookieHandle bh : bookies){
            bh.halt();
        }
    }
    
    
    /**
     * Returns the ledger identifier
     * @return long
     */
    public long getId(){
        return ledger;
    }
    
    /**
     * Returns the last entry identifier submitted
     * @return long
     */
    public long getLast(){
        return last;   
    }
    
    /**
     * Returns the last entry identifier submitted and increments it.
     * @return long
     */
    long incLast(){
        return last++;
    }
    
    /**
     * Returns the last entry identifier submitted and increments it.
     * @return long
     */
    long setLast(long last){
        this.last = last;
        return this.last;
    }
    
    /**
     * Sets the value of the last add confirmed. This is used
     * when adding new entries, since we use this value as a hint
     * to recover from failures of the client.
     */
    void setAddConfirmed(long entryId){
        if(entryId > lastAddConfirmed)
            lastAddConfirmed = entryId;
    }
    
    long getAddConfirmed(){
        return lastAddConfirmed;
    }
    
    /**
     * Returns the list of bookies
     * @return ArrayList<BookieHandle>
     */
    ArrayList<BookieHandle> getBookies(){
        return bookies;
    }
    
    /**
     * Return the quorum size. By default, the size of a quorum is (n+1)/2, 
     * where n is the size of the set of bookies.
     * @return int
     */
    int getQuorumSize(){
        return qSize;   
    }
    
    /**
     * Returns the quorum mode for this ledger: Verifiable or Generic
     */
    QMode getQMode(){
        return qMode;   
    }
    
    /**
     * Sets message digest algorithm.
     */
    
    void setDigestAlg(String alg){
        this.digestAlg = alg;
    }
    
    /**
     * Get message digest algorithm.
     */
    
    String getDigestAlg(){
        return digestAlg;
    }
    
    /**
     * Generates and stores password hash.
     * 
     * @param passwd
     */
    
    private void genPasswdHash(byte[] passwd){
        try{
            MessageDigest digest = MessageDigest.getInstance("MD5");
        
            digest.update(passwd);
            this.passwdHash = digest.digest();
        } catch(NoSuchAlgorithmException e){
            this.passwd = passwd;
            LOG.error("Storing password as plain text because secure hash implementation does not exist");
        }
    }
    
    
    /**
     * Returns password in plain text
     */
    byte[] getPasswd(){
    	return passwd;
    }
    
    
    /**
     * Returns password hash
     * 
     * @return byte[]
     */
    byte[] getPasswdHash(){
       return passwdHash; 
    }
   
}
