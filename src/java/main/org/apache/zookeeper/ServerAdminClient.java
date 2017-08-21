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

package org.apache.zookeeper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.StringTokenizer;

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.server.ZooTrace;

@InterfaceAudience.Public
public class ServerAdminClient {
    private static final Logger LOG = LoggerFactory.getLogger(ServerAdminClient.class);

    private static long getMask(String mask) {
        long retv = 0;
        if (mask.equalsIgnoreCase("CLIENT_REQUEST_TRACE_MASK")) {
            retv = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("CLIENT_DATA_PACKET_TRACE_MASK")) {
            retv = ZooTrace.CLIENT_DATA_PACKET_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("CLIENT_PING_TRACE_MASK")) {
            retv = ZooTrace.CLIENT_PING_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SERVER_PACKET_TRACE_MASK")) {
            retv = ZooTrace.SERVER_PACKET_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SESSION_TRACE_MASK")) {
            retv = ZooTrace.SESSION_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("EVENT_DELIVERY_TRACE_MASK")) {
            retv = ZooTrace.EVENT_DELIVERY_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("SERVER_PING_TRACE_MASK")) {
            retv = ZooTrace.SERVER_PING_TRACE_MASK;
        } else if (mask.equalsIgnoreCase("WARNING_TRACE_MASK")) {
            retv = ZooTrace.WARNING_TRACE_MASK;
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
        Socket s = null;
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("ruok".getBytes()).getInt());
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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    public static void dump(String host, int port) {
        Socket s = null;
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("dump".getBytes()).getInt());
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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    public static void stat(String host, int port) {
        Socket s = null;
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("stat".getBytes()).getInt());
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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    public static void kill(String host, int port) {
        Socket s = null;
        try {
            byte[] reqBytes = new byte[4];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("kill".getBytes()).getInt());
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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    public static void setTraceMask(String host, int port, String traceMaskStr) {
        Socket s = null;
        try {
            byte[] reqBytes = new byte[12];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            long traceMask = Long.parseLong(traceMaskStr, 8);
            req.putInt(ByteBuffer.wrap("stmk".getBytes()).getInt());
            req.putLong(traceMask);

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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    public static void getTraceMask(String host, int port) {
        Socket s = null;
        try {
            byte[] reqBytes = new byte[12];
            ByteBuffer req = ByteBuffer.wrap(reqBytes);
            req.putInt(ByteBuffer.wrap("gtmk".getBytes()).getInt());

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
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
            }
        }
    }

    private static void usage() {
        System.out
                .println("usage: java [-cp CLASSPATH] org.apache.zookeeper.ServerAdminClient "
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
