package org.apache.bookkeeper.util;

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

import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.proto.BookieServer;

public class Main {

    static void usage() {
        System.err.println("USAGE: bookeeper client|bookie");
    }

    /**
     * @param args
     * @throws InterruptedException
     * @throws IOException
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1 || !(args[0].equals("client") || args[0].equals("bookie"))) {
            usage();
            return;
        }
        String newArgs[] = new String[args.length - 1];
        System.arraycopy(args, 1, newArgs, 0, newArgs.length);
        if (args[0].equals("bookie")) {
            BookieServer.main(newArgs);
        } else {
            BookieClient.main(newArgs);
        }
    }

}
