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

package org.apache.zookeeper.client;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

public class FourLetterWordMain {
    protected static final Logger LOG = Logger.getLogger(FourLetterWordMain.class);
    
    /**
     * Send the 4letterword
     * @param host the destination host
     * @param port the destination port
     * @param cmd the 4letterword
     * @return server response
     * @throws java.io.IOException
     */
    public static String send4LetterWord(String host, int port, String cmd)
            throws IOException
    {
        LOG.info("connecting to " + host + " " + port);
        Socket sock = new Socket(host, port);
        BufferedReader reader = null;
        try {
            OutputStream outstream = sock.getOutputStream();
            outstream.write(cmd.getBytes());
            outstream.flush();
            // this replicates NC - close the output stream before reading
            sock.shutdownOutput();

            reader =
                    new BufferedReader(
                            new InputStreamReader(sock.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
            return sb.toString();
        } finally {
            sock.close();
            if (reader != null) {
                reader.close();
            }
        }
    }
    
    public static void main(String[] args)
            throws IOException
    {
        if (args.length != 3) {
            System.out.println("Usage: FourLetterWordMain <host> <port> <cmd>");
        } else {
            System.out.println(send4LetterWord(args[0], Integer.parseInt(args[1]), args[2]));
        }
    }
}
