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


import java.lang.Exception;

/**
 * Implements BookKeeper exceptions. 
 * 
 */

@SuppressWarnings("serial")
public abstract class BKException extends Exception {

    private int code;
    public BKException(int code){
        this.code = code;
    }
    
    public static BKException create(int code){
        switch(code){
        case Code.ReadException:
            return new BKReadException();
        case Code.QuorumException:
            return new BKQuorumException();
        case Code.NoBookieAvailableException:
            return new BKBookieException();
        case Code.DigestNotInitializedException:
            return new BKDigestNotInitializedException();
        case Code.DigestMatchException:
            return new BKDigestMatchException();
        case Code.NotEnoughBookiesException:
            return new BKNotEnoughBookiesException();
        case Code.NoSuchLedgerExistsException:
            return new BKNoSuchLedgerExistsException();
        case Code.BookieHandleNotAvailableException:
            return new BKBookieHandleNotAvailableException();
        default:
            return new BKIllegalOpException();
        }
    }
    
    public interface Code {
        int OK = 0;
        int ReadException = -1;
        int QuorumException = -2;
        int NoBookieAvailableException = -3;
        int DigestNotInitializedException = -4;
        int DigestMatchException = -5;
        int NotEnoughBookiesException = -6;
        int NoSuchLedgerExistsException = -7;
        int BookieHandleNotAvailableException = -8;
        
        int IllegalOpException = -100;
    }
    
    public void setCode(int code){
        this.code = code;
    }
    
    public int getCode(){
        return this.code;
    }
    
    public String getMessage(int code){
        switch(code){
        case Code.OK:
            return "No problem";
        case Code.ReadException:
            return "Error while reading ledger";
        case Code.QuorumException:
            return "Invalid quorum size on ensemble size";
        case Code.NoBookieAvailableException:
            return "Invalid quorum size on ensemble size";
        case Code.DigestNotInitializedException:
            return "Digest engine not initialized";
        case Code.DigestMatchException:
            return "Entry digest does not match";
        case Code.NotEnoughBookiesException:
            return "Not enough non-faulty bookies available";
        case Code.NoSuchLedgerExistsException:
            return "No such ledger exists";
        case Code.BookieHandleNotAvailableException:
            return "Bookie handle is not available";
        default:
            return "Invalid operation";
        }
    }
    
    public static class BKReadException extends BKException {
        public BKReadException(){
            super(Code.ReadException);
        }   
    }
    
    public static class BKQuorumException extends BKException {
        public BKQuorumException(){
            super(Code.QuorumException);
        }   
    }
     
    public static class BKBookieException extends BKException {
        public BKBookieException(){
            super(Code.NoBookieAvailableException);
        }   
    }
    
    public static class BKDigestNotInitializedException extends BKException {
        public BKDigestNotInitializedException(){
            super(Code.DigestNotInitializedException);
        }   
    }
    
    public static class BKDigestMatchException extends BKException {
        public BKDigestMatchException(){
            super(Code.DigestMatchException);
        }   
    }
    
    public static class BKIllegalOpException extends BKException {
        public BKIllegalOpException(){
            super(Code.IllegalOpException);
        }   
    }
    
    public static class BKNotEnoughBookiesException extends BKException {
        public BKNotEnoughBookiesException(){
            super(Code.NotEnoughBookiesException);
        }
    }

    public static class BKNoSuchLedgerExistsException extends BKException {
        public BKNoSuchLedgerExistsException(){
            super(Code.NoSuchLedgerExistsException);
        }   
    }
    
    public static class BKBookieHandleNotAvailableException extends BKException {
        public BKBookieHandleNotAvailableException(){
            super(Code.BookieHandleNotAvailableException);
        }   
    }
}
    
