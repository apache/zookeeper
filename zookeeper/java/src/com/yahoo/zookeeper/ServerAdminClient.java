/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.server.ZooLog;

public class ServerAdminClient {
    private static final Logger LOG = Logger.getLogger(ServerAdminClient.class);

    private static long getMask(String mask) {
        long retv = 0;
        if (mask.equalsIgnoreCase("CLIENT_REQUEST_TRACE_MASK")) {
            retv = ZooLog.CLIENT_REQUEST_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("CLIENT_DATA_PACKET_TRACE_MASK")) {
            retv = ZooLog.CLIENT_DATA_PACKET_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("CLIENT_PING_TRACE_MASK")) {
            retv = ZooLog.CLIENT_PING_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SERVER_PACKET_TRACE_MASK")) {
            retv = ZooLog.SERVER_PACKET_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SESSION_TRACE_MASK")) {
            retv = ZooLog.SESSION_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("EVENT_DELIVERY_TRACE_MASK")) {
            retv = ZooLog.EVENT_DELIVERY_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SERVER_PING_TRACE_MASK")) {
            retv = ZooLog.SERVER_PING_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("WARNING_TRACE_MASK")) {
            retv = ZooLog.WARNING_TRACE_MASK;
        }
        return retv;
    }

    private static long getMasks(String masks) {
        long retv = 0;
        StringTokenizer st = new StringTokenizer(masks, "|");
        while (st.hasMoreTokens()) {
            String mask = st.nextToken().trim();
            retv = retv | getMask(mask);
        }
        return retv;
    }

    public static void ruok(String host, int port) {
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("ruok".getBytes()).getInt());
            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);

            byte[] resBytes = new byte[4];

            int rc = is.read(resBytes);
            String retv = new String(resBytes);
            System.out.println("rc=" + rc + " retv=" + retv);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public static void dump(String host, int port) {
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("dump".getBytes()).getInt());
            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);

            byte[] resBytes = new byte[1024];

            int rc = is.read(resBytes);
            String retv = new String(resBytes);
            System.out.println("rc=" + rc + " retv=" + retv);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public static void stat(String host, int port) {
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("stat".getBytes()).getInt());
            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);

            byte[] resBytes = new byte[1024];

            int rc = is.read(resBytes);
            String retv = new String(resBytes);
            System.out.println("rc=" + rc + " retv=" + retv);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public static void kill(String host, int port) {
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("kill".getBytes()).getInt());
            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);
            byte[] resBytes = new byte[4];

            int rc = is.read(resBytes);
            String retv = new String(resBytes);
            System.out.println("rc=" + rc + " retv=" + retv);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public static void setTraceMask(String host, int port, String traceMaskStr) {
        try {
            byte[] reqBytes = new byte[12];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            long traceMask = Long.parseLong(traceMaskStr, 8);
            req.putInt(ByteBuffer.wrap("stmk".getBytes()).getInt());
            req.putLong(traceMask);

            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);

            byte[] resBytes = new byte[8];

            int rc = is.read(resBytes);
            ByteBuffer res = ByteBuffer.wrap(resBytes);
            long retv = res.getLong();
            System.out.println("rc=" + rc + " retv=0"
                    + Long.toOctalString(retv) + " masks=0"
                    + Long.toOctalString(traceMask));
            assert (retv == traceMask);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public static void getTraceMask(String host, int port) {
        try {
            byte[] reqBytes = new byte[12];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("gtmk".getBytes()).getInt());

            Socket s = null;
            s = new Socket();
            s.setSoLinger(false, 10);
            s.setSoTimeout(20000);
            s.connect(new InetSocketAddress(host, port));

            InputStream is = s.getInputStream();
            OutputStream os = s.getOutputStream();

            os.write(reqBytes);

            byte[] resBytes = new byte[8];

            int rc = is.read(resBytes);
            ByteBuffer res = ByteBuffer.wrap(resBytes);
            long retv = res.getLong();
            System.out.println("rc=" + rc + " retv=0"
                    + Long.toOctalString(retv));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private static void usage() {
        System.out
                .println("usage: java [-cp CLASSPATH] com.yahoo.zookeeper.ServerAdminClient "
                        + "host port op (ruok|stat|dump|kill|gettracemask|settracemask) [arguments]");

    }

    public static void main(String[] args) {
        if (args.length < 3) {
            usage();
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String op = args[2];
        if (op.equalsIgnoreCase("gettracemask")) {
            getTraceMask(host, port);
        } else if (op.equalsIgnoreCase("settracemask")) {
            setTraceMask(host, port, args[3]);
        } else if (op.equalsIgnoreCase("ruok")) {
            ruok(host, port);
        } else if (op.equalsIgnoreCase("kill")) {
            kill(host, port);
        } else if (op.equalsIgnoreCase("stat")) {
            stat(host, port);
        } else if (op.equalsIgnoreCase("dump")) {
            dump(host, port);
        } else {
            System.out.println("Unrecognized op: " + op);
        }
    }
}
