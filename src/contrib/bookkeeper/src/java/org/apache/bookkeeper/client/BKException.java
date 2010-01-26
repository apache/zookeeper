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
 * Class the enumerates all the possible error conditions
 * 
 */

@SuppressWarnings("serial")
public abstract class BKException extends Exception {

    private int code;

    BKException(int code) {
        this.code = code;
    }

    /**
     * Create an exception from an error code
     * @param code return error code
     * @return correponding exception
     */
    public static BKException create(int code) {
        switch (code) {
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
        case Code.ZKException:
            return new ZKException();
        case Code.LedgerRecoveryException:
            return new BKLedgerRecoveryException();
        case Code.LedgerClosedException:
            return new BKLedgerClosedException();
        case Code.WriteException:
            return new BKWriteException();
        case Code.NoSuchEntryException:
            return new BKNoSuchEntryException();
        default:
            return new BKIllegalOpException();
        }
    }

    /**
     * List of return codes
     *
     */
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
        int ZKException = -9;
        int LedgerRecoveryException = -10;
        int LedgerClosedException = -11;
        int WriteException = -12;
        int NoSuchEntryException = -13;

        int IllegalOpException = -100;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public static String getMessage(int code) {
        switch (code) {
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
        case Code.ZKException:
            return "Error while using ZooKeeper";
        case Code.LedgerRecoveryException:
            return "Error while recovering ledger";
        case Code.LedgerClosedException:
            return "Attempt to write to a closed ledger";
        case Code.WriteException:
            return "Write failed on bookie";
        case Code.NoSuchEntryException:
            return "No such entry";
        default:
            return "Invalid operation";
        }
    }

    public static class BKReadException extends BKException {
        public BKReadException() {
            super(Code.ReadException);
        }
    }

    public static class BKNoSuchEntryException extends BKException {
        public BKNoSuchEntryException() {
            super(Code.NoSuchEntryException);
        }
    }

    public static class BKQuorumException extends BKException {
        public BKQuorumException() {
            super(Code.QuorumException);
        }
    }

    public static class BKBookieException extends BKException {
        public BKBookieException() {
            super(Code.NoBookieAvailableException);
        }
    }

    public static class BKDigestNotInitializedException extends BKException {
        public BKDigestNotInitializedException() {
            super(Code.DigestNotInitializedException);
        }
    }

    public static class BKDigestMatchException extends BKException {
        public BKDigestMatchException() {
            super(Code.DigestMatchException);
        }
    }

    public static class BKIllegalOpException extends BKException {
        public BKIllegalOpException() {
            super(Code.IllegalOpException);
        }
    }

    public static class BKNotEnoughBookiesException extends BKException {
        public BKNotEnoughBookiesException() {
            super(Code.NotEnoughBookiesException);
        }
    }

    public static class BKWriteException extends BKException {
        public BKWriteException() {
            super(Code.WriteException);
        }
    }

    public static class BKNoSuchLedgerExistsException extends BKException {
        public BKNoSuchLedgerExistsException() {
            super(Code.NoSuchLedgerExistsException);
        }
    }

    public static class BKBookieHandleNotAvailableException extends BKException {
        public BKBookieHandleNotAvailableException() {
            super(Code.BookieHandleNotAvailableException);
        }
    }

    public static class ZKException extends BKException {
        public ZKException() {
            super(Code.ZKException);
        }
    }

    public static class BKLedgerRecoveryException extends BKException {
        public BKLedgerRecoveryException() {
            super(Code.LedgerRecoveryException);
        }
    }

    public static class BKLedgerClosedException extends BKException {
        public BKLedgerClosedException() {
            super(Code.LedgerClosedException);
        }
    }
}
