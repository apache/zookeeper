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
package org.apache.hedwig.server.persistence;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.sql.rowset.serial.SerialBlob;

import org.apache.log4j.Logger;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.exceptions.PubSubException.UnexpectedConditionException;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageSeqId;
import org.apache.hedwig.protoextensions.MessageIdUtils;
import org.apache.hedwig.server.persistence.ScanCallback.ReasonForFinish;
import org.apache.hedwig.util.Callback;
import org.apache.hedwig.util.FileUtils;

public class LocalDBPersistenceManager implements PersistenceManagerWithRangeScan {
    static Logger logger = Logger.getLogger(LocalDBPersistenceManager.class);

    static String connectionURL;

    static {
        try {
            File tempDir = FileUtils.createTempDirectory("derby", null);

            // Since derby needs to create it, I will have to delete it first
            if (!tempDir.delete()) {
                throw new IOException("Could not delete dir: " + tempDir.getAbsolutePath());
            }
            connectionURL = "jdbc:derby:" + tempDir.getAbsolutePath() + ";create=true";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static final ThreadLocal<Connection> threadLocalConnection = new ThreadLocal<Connection>() {
        @Override
        protected Connection initialValue() {
            try {
                return DriverManager.getConnection(connectionURL);
            } catch (SQLException e) {
                logger.error("Could not connect to derby", e);
                return null;
            }
        }
    };
    static final String ID_FIELD_NAME = "id";
    static final String MSG_FIELD_NAME = "msg";
    static final String driver = "org.apache.derby.jdbc.EmbeddedDriver";

    static final int SCAN_CHUNK = 1000;

    /**
     * Having trouble restarting the database multiple times from within the
     * same jvm. Hence to facilitate units tests, we are just going to have a
     * version number that we will append to every table name. This version
     * number will be incremented in lieu of shutting down the database and
     * restarting it, so that we get different table names, and it behaves like
     * a brand new database
     */
    private int version = 0;

    ConcurrentMap<ByteString, MessageSeqId> currTopicSeqIds = new ConcurrentHashMap<ByteString, MessageSeqId>();

    static LocalDBPersistenceManager instance = new LocalDBPersistenceManager();

    public static LocalDBPersistenceManager instance() {
        return instance;
    }

    private LocalDBPersistenceManager() {

        try {
            Class.forName(driver).newInstance();
            logger.info("Derby Driver loaded");
        } catch (java.lang.ClassNotFoundException e) {
            logger.error("Derby driver not found", e);
        } catch (InstantiationException e) {
            logger.error("Could not instantiate derby driver", e);
        } catch (IllegalAccessException e) {
            logger.error("Could not instantiate derby driver", e);
        }
    }

    /**
     * Ensures that at least the default seq-id exists in the map for the given
     * topic. Checks for race conditions (.e.g, another thread inserts the
     * default id before us), and returns the latest seq-id value in the map
     * 
     * @param topic
     * @return
     */
    private MessageSeqId ensureSeqIdExistsForTopic(ByteString topic) {
        MessageSeqId presentSeqIdInMap = currTopicSeqIds.get(topic);

        if (presentSeqIdInMap != null) {
            return presentSeqIdInMap;
        }

        presentSeqIdInMap = MessageSeqId.newBuilder().setLocalComponent(0).build();
        MessageSeqId oldSeqIdInMap = currTopicSeqIds.putIfAbsent(topic, presentSeqIdInMap);

        if (oldSeqIdInMap != null) {
            return oldSeqIdInMap;
        }
        return presentSeqIdInMap;

    }

    /**
     * Adjust the current seq id of the topic based on the message we are about
     * to publish. The local component of the current seq-id is always
     * incremented by 1. For the other components, there are two cases:
     * 
     * 1. If the message to be published doesn't have a seq-id (locally
     * published messages), the other components are left as is.
     * 
     * 2. If the message to be published has a seq-id, we take the max of the
     * current one we have, and that in the message to be published.
     * 
     * @param topic
     * @param messageToPublish
     * @return The value of the local seq-id obtained after incrementing the
     *         local component. This value should be used as an id while
     *         persisting to Derby
     * @throws UnexpectedConditionException
     */
    private long adjustTopicSeqIdForPublish(ByteString topic, Message messageToPublish)
            throws UnexpectedConditionException {
        long retValue = 0;
        MessageSeqId oldId;
        MessageSeqId.Builder newIdBuilder = MessageSeqId.newBuilder();

        do {
            oldId = ensureSeqIdExistsForTopic(topic);

            // Increment our own component by 1
            retValue = oldId.getLocalComponent() + 1;
            newIdBuilder.setLocalComponent(retValue);

            if (messageToPublish.hasMsgId()) {
                // take a region-wise max
                MessageIdUtils.takeRegionMaximum(newIdBuilder, messageToPublish.getMsgId(), oldId);

            } else {
                newIdBuilder.addAllRemoteComponents(oldId.getRemoteComponentsList());
            }
        } while (!currTopicSeqIds.replace(topic, oldId, newIdBuilder.build()));

        return retValue;

    }

    public long getSeqIdAfterSkipping(ByteString topic, long seqId, int skipAmount) {
        return seqId + skipAmount;
    }

    public void persistMessage(PersistRequest request) {

        Connection conn = threadLocalConnection.get();

        Callback<Long> callback = request.getCallback();
        Object ctx = request.getCtx();
        ByteString topic = request.getTopic();
        Message message = request.getMessage();

        if (conn == null) {
            callback.operationFailed(ctx, new ServiceDownException("Not connected to derby"));
            return;
        }

        long seqId;

        try {
            seqId = adjustTopicSeqIdForPublish(topic, message);
        } catch (UnexpectedConditionException e) {
            callback.operationFailed(ctx, e);
            return;
        }
        PreparedStatement stmt;

        boolean triedCreatingTable = false;
        while (true) {
            try {
                message.getBody();
                stmt = conn.prepareStatement("INSERT INTO " + getTableNameForTopic(topic) + " VALUES(?,?)");
                stmt.setLong(1, seqId);
                stmt.setBlob(2, new SerialBlob(message.toByteArray()));

                int rowCount = stmt.executeUpdate();
                stmt.close();
                if (rowCount != 1) {
                    logger.error("Unexpected number of affected rows from derby");
                    callback.operationFailed(ctx, new ServiceDownException("Unexpected response from derby"));
                    return;
                }
                break;
            } catch (SQLException sqle) {
                String theError = (sqle).getSQLState();
                if (theError.equals("42X05") && !triedCreatingTable) {
                    createTable(conn, topic);
                    triedCreatingTable = true;
                    continue;
                }

                logger.error("Error while executing derby insert", sqle);
                callback.operationFailed(ctx, new ServiceDownException(sqle));
                return;
            }
        }
        callback.operationFinished(ctx, seqId);
    }

    /*
     * This method does not throw an exception because another thread might
     * sneak in and create the table before us
     */
    private void createTable(Connection conn, ByteString topic) {

        try {
            Statement stmt = conn.createStatement();
            String tableName = getTableNameForTopic(topic);
            stmt.execute("CREATE TABLE " + tableName + " (" + ID_FIELD_NAME + " BIGINT NOT NULL CONSTRAINT ID_PK_"
                    + tableName + " PRIMARY KEY," + MSG_FIELD_NAME + " BLOB(2M) NOT NULL)");
        } catch (SQLException e) {
            logger.debug("Could not create table", e);
        }
    }

    public MessageSeqId getCurrentSeqIdForTopic(ByteString topic) {
        return ensureSeqIdExistsForTopic(topic);
    }

    public void scanSingleMessage(ScanRequest request) {
        scanMessagesInternal(request.getTopic(), request.getStartSeqId(), 1, Long.MAX_VALUE, request.getCallback(),
                request.getCtx(), 1);
        return;
    }

    public void scanMessages(RangeScanRequest request) {
        scanMessagesInternal(request.getTopic(), request.getStartSeqId(), request.getMessageLimit(), request
                .getSizeLimit(), request.getCallback(), request.getCtx(), SCAN_CHUNK);
        return;
    }

    private String getTableNameForTopic(ByteString topic) {
        return (topic.toStringUtf8() + "_" + version);
    }

    private void scanMessagesInternal(ByteString topic, long startSeqId, int messageLimit, long sizeLimit,
            ScanCallback callback, Object ctx, int scanChunk) {

        Connection conn = threadLocalConnection.get();

        if (conn == null) {
            callback.scanFailed(ctx, new ServiceDownException("Not connected to derby"));
            return;
        }

        long currentSeqId;
        currentSeqId = startSeqId;

        PreparedStatement stmt;
        try {
            try {
                stmt = conn.prepareStatement("SELECT * FROM " + getTableNameForTopic(topic) + " WHERE " + ID_FIELD_NAME
                        + " >= ?  AND " + ID_FIELD_NAME + " <= ?");

            } catch (SQLException sqle) {
                String theError = (sqle).getSQLState();
                if (theError.equals("42X05")) {
                    // No table, scan is over
                    callback.scanFinished(ctx, ReasonForFinish.NO_MORE_MESSAGES);
                    return;
                } else {
                    throw sqle;
                }
            }

            int numMessages = 0;
            long totalSize = 0;

            while (true) {

                stmt.setLong(1, currentSeqId);
                stmt.setLong(2, currentSeqId + scanChunk);

                if (!stmt.execute()) {
                    String errorMsg = "Select query did not return a result set";
                    logger.error(errorMsg);
                    stmt.close();
                    callback.scanFailed(ctx, new ServiceDownException(errorMsg));
                    return;
                }

                ResultSet resultSet = stmt.getResultSet();

                if (!resultSet.next()) {
                    stmt.close();
                    callback.scanFinished(ctx, ReasonForFinish.NO_MORE_MESSAGES);
                    return;
                }

                do {

                    long localSeqId = resultSet.getLong(1);

                    Message.Builder messageBuilder = Message.newBuilder().mergeFrom(resultSet.getBinaryStream(2));

                    // Merge in the local seq-id since that is not stored with
                    // the message
                    Message message = MessageIdUtils.mergeLocalSeqId(messageBuilder, localSeqId);

                    callback.messageScanned(ctx, message);
                    numMessages++;
                    totalSize += message.getBody().size();

                    if (numMessages > messageLimit) {
                        stmt.close();
                        callback.scanFinished(ctx, ReasonForFinish.NUM_MESSAGES_LIMIT_EXCEEDED);
                        return;
                    } else if (totalSize > sizeLimit) {
                        stmt.close();
                        callback.scanFinished(ctx, ReasonForFinish.SIZE_LIMIT_EXCEEDED);
                        return;
                    }

                } while (resultSet.next());

                currentSeqId += SCAN_CHUNK;
            }
        } catch (SQLException e) {
            logger.error("SQL Exception", e);
            callback.scanFailed(ctx, new ServiceDownException(e));
            return;
        } catch (IOException e) {
            logger.error("Message stored in derby is not parseable", e);
            callback.scanFailed(ctx, new ServiceDownException(e));
            return;
        }

    }

    public void deliveredUntil(ByteString topic, Long seqId) {
        // noop
    }

    public void consumedUntil(ByteString topic, Long seqId) {
        Connection conn = threadLocalConnection.get();
        if (conn == null) {
            logger.error("Not connected to derby");
            return;
        }
        PreparedStatement stmt;
        try {
            stmt = conn.prepareStatement("DELETE FROM " + getTableNameForTopic(topic) + " WHERE " + ID_FIELD_NAME
                    + " <= ?");
            stmt.setLong(1, seqId);
            int rowCount = stmt.executeUpdate();
            logger.debug("Deleted " + rowCount + " records for topic: " + topic.toStringUtf8() + ", seqId: " + seqId);
            stmt.close();
        } catch (SQLException sqle) {
            String theError = (sqle).getSQLState();
            if (theError.equals("42X05")) {
                logger.warn("Table for topic (" + topic + ") does not exist so no consumed messages to delete!");
            } else
                logger.error("Error while executing derby delete for consumed messages", sqle);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (driver.equals("org.apache.derby.jdbc.EmbeddedDriver")) {
            boolean gotSQLExc = false;
            // This is weird: on normal shutdown, it throws an exception
            try {
                DriverManager.getConnection("jdbc:derby:;shutdown=true").close();
            } catch (SQLException se) {
                if (se.getSQLState().equals("XJ015")) {
                    gotSQLExc = true;
                }
            }
            if (!gotSQLExc) {
                logger.error("Database did not shut down normally");
            } else {
                logger.info("Database shut down normally");
            }
        }
        super.finalize();
    }

    public void reset() {
        // just move the namespace over to the next one
        version++;
        currTopicSeqIds.clear();
    }
}
