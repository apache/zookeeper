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

public interface BKDefs { 
    /**
     * String used to construct znode paths. They are used in BookKeeper
     *  and LedgerManagementProcessor.
     */
    static public final String prefix = "/ledgers/L";
    static public final String ensemble = "/ensemble"; 
    static public final String quorumSize = "/quorum";
    static public final String close = "/close";
    static public final String quorumMode = "/mode";
    
    /**
     * Status ok
     */
    public final int EOK = 0;
    
    /**
     * Insufficient bookies
     */
    public final int EIB = -1;
 
    /**
     * No such a ledger
     */
    public final int ENL = -2;
    
    /**
     * Error while recovering ledger
     */
    public final int ERL = -3;
    
    /**
     * Error while reading from zookeeper or writing to zookeeper
     */
    public final int EZK = -4;

    /**
     * IO error, typically when trying to connect to a bookie
     */
    public final int EIO = -5;
    
    /**
     * Exceeded number of retries
     */
    public final int ENR = -6;
}
