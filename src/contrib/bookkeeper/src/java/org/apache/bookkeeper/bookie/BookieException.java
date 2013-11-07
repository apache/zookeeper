package org.apache.bookkeeper.bookie;

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
 
 @SuppressWarnings("serial")
public abstract class BookieException extends Exception {

    private int code;
    public BookieException(int code){
        this.code = code;
    }
    
    public static BookieException create(int code){
        switch(code){
        case Code.UnauthorizedAccessException:
            return new BookieUnauthorizedAccessException();
        default:
            return new BookieIllegalOpException();
        }
    }
    
    public interface Code {
        int OK = 0;
        int UnauthorizedAccessException = -1;
        
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
        case Code.UnauthorizedAccessException:
            return "Error while reading ledger";
        default:
            return "Invalid operation";
        }
    }
    
    public static class BookieUnauthorizedAccessException extends BookieException {
        public BookieUnauthorizedAccessException(){
            super(Code.UnauthorizedAccessException);
        }   
    }
    
    public static class BookieIllegalOpException extends BookieException {
        public BookieIllegalOpException(){
            super(Code.UnauthorizedAccessException);
        }   
    }
}