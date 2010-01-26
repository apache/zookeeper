package org.apache.bookkeeper.client;

/*
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
import java.util.zip.CRC32;

class CRC32DigestManager extends DigestManager {
    CRC32 crc = new CRC32();
    
    public CRC32DigestManager(long ledgerId) {
        super(ledgerId);
    }

    @Override
    int getMacCodeLength() {
        return 8;
    }
    
    @Override
    byte[] getValueAndReset() {
        byte[] value = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(value);
        buf.putLong(crc.getValue());
        crc.reset();
        return value;
    }
    
    @Override
    void update(byte[] data, int offset, int length) {
        crc.update(data, offset, length);
    }
}
