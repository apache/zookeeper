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

package com.yahoo.zookeeper.server;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.server.quorum.QuorumPacket;

/**
 * This class encapsulates and centralizes the logging for the zookeeper server.
 * Log messages go to System.out or System.err depending on the severity of the
 * message.
 * <p>
 * If the methods that do not take an explicit location are used, the location
 * will be derived by creating a stack trace and then looking one frame up the
 * stack trace. (It's a hack, but it works rather well.)
 */
public class ZooLog {
    private static final Logger LOG = Logger.getLogger(ZooLog.class);

    static FileChannel tos = null;

    private static String requestTraceFile =
        System.getProperty("requestTraceFile");

    static boolean loggedTraceError = false;

    static boolean traceInitialiazed = false;

    final static public long CLIENT_REQUEST_TRACE_MASK = 1 << 1;

    final static public long CLIENT_DATA_PACKET_TRACE_MASK = 1 << 2;

    final static public long CLIENT_PING_TRACE_MASK = 1 << 3;

    final static public long SERVER_PACKET_TRACE_MASK = 1 << 4;

    final static public long SESSION_TRACE_MASK = 1 << 5;

    final static public long EVENT_DELIVERY_TRACE_MASK = 1 << 6;

    final static public long SERVER_PING_TRACE_MASK = 1 << 7;

    final static public long WARNING_TRACE_MASK = 1 << 8;

    final static public long JMX_TRACE_MASK = 1 << 9;

    static long binaryTraceMask = CLIENT_REQUEST_TRACE_MASK
            | SERVER_PACKET_TRACE_MASK | SESSION_TRACE_MASK
            | WARNING_TRACE_MASK;

    static FileChannel textTos = null;

    static long textTosCreationTime = 0;

    static boolean loggedTextTraceError = false;

    static boolean textTraceInitialiazed = false;

    public static long textTraceMask = CLIENT_REQUEST_TRACE_MASK
            | SERVER_PACKET_TRACE_MASK | SESSION_TRACE_MASK
            | WARNING_TRACE_MASK;

    public static void setTextTraceLevel(long mask) {
        textTraceMask = mask;
        logTextTraceMessage("Set text trace mask to "
                + Long.toBinaryString(mask), textTraceMask);
    }

    static private String stackTrace2Location(StackTraceElement ste) {
        String location = ste.getFileName() + "@" + ste.getLineNumber();
        return location;
    }

    public static long getTextTraceLevel() {
        return textTraceMask;
    }

    static private void write(FileChannel os, String msg) throws IOException {
        os.write(ByteBuffer.wrap(msg.getBytes()));
    }

    private static final SimpleDateFormat DATELOGFMT =
        new SimpleDateFormat("MM/dd/yy HH:mm:ss,SSS");
    
    static private void writeText(FileChannel os, char rp, Request request,
            String header, String location) throws IOException {
        StringBuffer sb = new StringBuffer();
        long time = System.currentTimeMillis();
        sb.append(DATELOGFMT.format(new Date(time)));
        sb.append(" ").append(location).append(" ");
        sb.append(header).append(":").append(rp);
        sb.append(request.toString());
        write(os, sb.toString());
        write(textTos, "\n");
    }

    static private void writeText(FileChannel os, String message,
            String location) throws IOException {
        StringBuffer sb = new StringBuffer();
        long time = System.currentTimeMillis();
        sb.append(DATELOGFMT.format(new Date(time)));
        sb.append(" ").append(location).append(" ");
        sb.append(message);
        write(os, sb.toString());
        write(textTos, "\n");
    }

    static private long ROLLOVER_TIME = 24 * 3600 * 1000;

    synchronized private static void checkTextTraceFile() {
        long time = System.currentTimeMillis();

        if ((time - textTosCreationTime) > ROLLOVER_TIME) {
            textTraceInitialiazed = false;
            if (textTos != null) {
                try {
                    textTos.close();
                } catch (IOException e) {

                }
                textTos = null;
            }
        }
        if (!textTraceInitialiazed) {
            textTraceInitialiazed = true;
            Calendar d = new GregorianCalendar();
            long year = d.get(Calendar.YEAR);
            long month = d.get(Calendar.MONTH) + 1;
            long day = d.get(Calendar.DAY_OF_MONTH);

            if (requestTraceFile == null) {
                return;
            }
           String currentTextFile = requestTraceFile +  "." + year + "." + month + "." + day;

            try {
                textTos = new FileOutputStream(currentTextFile + ".txt", true)
                        .getChannel();
                textTosCreationTime = time;
                write(textTos, "\n");
            } catch (IOException e) {
                LOG.error("FIXMSG",e);
                return;
            }
            LOG.warn("*********** Traced requests text saved to "
                    + currentTextFile + ".txt");
        }
    }

    private static void checkTraceFile() {
        if (!traceInitialiazed) {
            traceInitialiazed = true;
            String requestTraceFile = System.getProperty("requestTraceFile");
            if (requestTraceFile == null) {
                return;
            }
            try {
                tos = new FileOutputStream(requestTraceFile, true).getChannel();
            } catch (IOException e) {
                LOG.error("FIXMSG",e);
                return;
            }
            LOG.warn("*********** Traced requests saved to "
                    + requestTraceFile);
        }
    }

    final static private boolean doLog(long traceMask) {
        return requestTraceFile != null && (textTraceMask & traceMask) != 0;
    }

    public static void logTextTraceMessage(String text, long traceMask) {
        if (!doLog(traceMask)) {
            return;
        }
        synchronized (ZooLog.class) {
            checkTextTraceFile();
            if (textTos != null && !loggedTextTraceError
                    && ((textTraceMask & traceMask) != 0)) {
                try {
                    RuntimeException re = new RuntimeException();
                    StackTraceElement ste = re.getStackTrace()[1];
                    String location = ZooLog.stackTrace2Location(ste);
                    writeText(textTos, text, location);
                } catch (IOException e1) {
                    LOG.error("FIXMSG", e1);
                    loggedTextTraceError = true;
                }
            }
        }
    }

    static public void logQuorumPacket(char direction, QuorumPacket qp,
            long traceMask) {
        return;

        // if (!doLog(traceMask)) {
        //    return;
        //}
        //logTextTraceMessage(direction + " "
        //        + FollowerHandler.packetToString(qp), traceMask);
    }

    static public void logRequest(char rp, Request request, String header,
            long traceMask) {
        if (!doLog(traceMask)) {
            return;
        }
        RuntimeException re = new RuntimeException();
        StackTraceElement ste = re.getStackTrace()[1];
        String location = ZooLog.stackTrace2Location(ste);
        logRequestText(rp, request, header, traceMask, location);
    }

    static synchronized private void logRequestText(char rp, Request request,
            String header, long traceMask, String location) {
        if (!doLog(traceMask)) {
            return;
        }
        checkTextTraceFile();
        if (textTos != null && !loggedTextTraceError
                && ((traceMask & textTraceMask) != 0)) {
            try {
                writeText(textTos, rp, request, header, location);
            } catch (IOException e1) {
                LOG.error("FIXMSG", e1);
                loggedTextTraceError = true;
            }
        }
    }

    /*
    public void logRequestBinary(char rp, Request request, long traceMask) {
        if (!doLog(traceMask)) {
            return;
        }
        synchronized (ZooLog.class) {
            checkTraceFile();
            if (tos != null && !loggedTraceError
                    && ((traceMask & binaryTraceMask) != 0)) {
                ByteBuffer bb = ByteBuffer.allocate(41);
                bb.put((byte) rp);
                bb.putLong(System.currentTimeMillis());
                bb.putLong(request.sessionId);
                bb.putInt(request.cxid);
                bb.putLong(request.hdr == null ? -2 : request.hdr.getZxid());
                bb.putInt(request.hdr == null ? -2 : request.hdr.getType());
                bb.putInt(request.type);
                if (request.request != null) {
                    bb.putInt(request.request.remaining());
                } else {
                    bb.putInt(0);
                }
                bb.flip();
                try {
                    if (request.request == null) {
                        tos.write(bb);
                    } else {
                        tos.write(new ByteBuffer[] { bb,
                                request.request.duplicate() });
                    }
                } catch (IOException e) {
                    LOG.error("FIXMSG", e);
                    loggedTraceError = true;
                }
            }
        }
    }
    */
    /*
    static private void formatLine(PrintStream ps, String mess, String location) {
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.LONG);
        StringBuffer entry = new StringBuffer(dateFormat.format(new Date())
                + " [" + location + "]["
                + Long.toHexString(Thread.currentThread().getId()) + "]: ");
        while (entry.length() < 45) {
            entry.append(' ');
        }
        entry.append(mess);
        ps.println(entry);
    }

    public static void logError(String mess) {
        RuntimeException re = new RuntimeException();
        StackTraceElement ste = re.getStackTrace()[1];
        String location = ZooLog.stackTrace2Location(ste);
        LOG.error(mess, location);
        ZooLog.logTextTraceMessage(mess + "location: " + location,
                textTraceMask);
    }

    public static void logError(String mess, String location) {
        formatLine(System.err, mess, location);
        System.err.flush();
    }

    public static void logWarn(String mess) {
        RuntimeException re = new RuntimeException();
        StackTraceElement ste = re.getStackTrace()[1];
        String location = ZooLog.stackTrace2Location(ste);
        LOG.warn(mess, location);
        ZooLog.logTextTraceMessage(mess + " location: " + location,
                WARNING_TRACE_MASK);
    }

    public static void logWarn(String mess, String location) {
        formatLine(System.out, mess, location);
        System.out.flush();
    }

    private static void logException(Throwable e, String mess, String location) {
        StringWriter sw = new StringWriter();
        sw.append(mess);
        sw.append(": ");
        e.printStackTrace(new PrintWriter(sw));
        if (location == null) {
            RuntimeException re = new RuntimeException();
            StackTraceElement ste = re.getStackTrace()[1];
            location = stackTrace2Location(ste);
        }
        logError(sw.toString(), location);
    }

    public static void logException(Throwable e, String mess) {
        RuntimeException re = new RuntimeException();
        StackTraceElement ste = re.getStackTrace()[1];
        logException(e, mess, stackTrace2Location(ste));
    }

    public static void logException(Throwable e) {
        RuntimeException re = new RuntimeException();
        StackTraceElement ste = re.getStackTrace()[1];
        logException(e, "", stackTrace2Location(ste));
    }

*/

}
