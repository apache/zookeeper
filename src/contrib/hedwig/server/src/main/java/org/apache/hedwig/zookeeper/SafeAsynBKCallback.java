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
package org.apache.hedwig.zookeeper;

import java.util.Enumeration;

import org.apache.bookkeeper.client.AsyncCallback;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;


public class SafeAsynBKCallback extends SafeAsyncCallback{

    public static abstract class OpenCallback implements AsyncCallback.OpenCallback {
        @Override
        public void openComplete(int rc, LedgerHandle ledgerHandle, Object ctx) {
            try{
                safeOpenComplete(rc, ledgerHandle, ctx);
            }catch(Throwable t){
                invokeUncaughtExceptionHandler(t);
            }
        }
        
        public abstract void safeOpenComplete(int rc, LedgerHandle ledgerHandle, Object ctx);

    }
    
    public static abstract class CloseCallback implements AsyncCallback.CloseCallback {
        @Override
        public void closeComplete(int rc, LedgerHandle ledgerHandle, Object ctx){
            try{
                safeCloseComplete(rc, ledgerHandle, ctx);
            }catch(Throwable t){
                invokeUncaughtExceptionHandler(t);
            }
        }
        
        public abstract void safeCloseComplete(int rc, LedgerHandle ledgerHandle, Object ctx) ;
    }
    
    public static abstract class ReadCallback implements AsyncCallback.ReadCallback {
        
        @Override
        public void readComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx) {
            try{
                safeReadComplete(rc, lh, seq, ctx);
            }catch(Throwable t){
                invokeUncaughtExceptionHandler(t);
            }
            
        }
        
        public abstract void safeReadComplete(int rc, LedgerHandle lh, Enumeration<LedgerEntry> seq, Object ctx);
    }
    
    public static abstract class CreateCallback implements AsyncCallback.CreateCallback {
        
        @Override
        public void createComplete(int rc, LedgerHandle lh, Object ctx) {
            try{
                safeCreateComplete(rc, lh, ctx);
            }catch(Throwable t){
                invokeUncaughtExceptionHandler(t);
            }
            
        }
        
        public abstract void safeCreateComplete(int rc, LedgerHandle lh, Object ctx);
        
        
    }
    
    public static abstract class AddCallback implements AsyncCallback.AddCallback {
        
        @Override
        public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
            try{
                safeAddComplete(rc, lh, entryId, ctx);
            }catch(Throwable t){
                invokeUncaughtExceptionHandler(t);
            }
        }
        
        public abstract void safeAddComplete(int rc, LedgerHandle lh, long entryId, Object ctx);
        
    }
    
}

