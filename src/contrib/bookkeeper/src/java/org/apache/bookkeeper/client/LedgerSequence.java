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


import java.util.Enumeration;
import java.util.List;
import java.util.Arrays;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;

/**
 * Sequence of entries of a ledger. Used to return a sequence of entries
 * upon an asynchornous read call.
 *
 */


public class LedgerSequence 
implements Enumeration<LedgerEntry> {
    Logger LOG = Logger.getLogger(LedgerSequence.class);
    
    int index = 0;
    List<LedgerEntry> seq;
    
    LedgerSequence(LedgerEntry[] seq){
        this.seq = Arrays.asList(seq);
        LOG.debug("Sequence provided: " + this.seq.size());
    }
    
    public boolean hasMoreElements(){
        if(index < seq.size())
            return true;
        else
            return false;
    }
    
    public LedgerEntry nextElement() throws NoSuchElementException{
        LOG.debug("Next element of sequence: " + seq.size() + ", " + index);
        return seq.get(index++);
    }
    
    public int size(){
        return seq.size();
    }
}
