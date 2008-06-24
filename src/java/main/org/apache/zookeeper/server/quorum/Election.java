package org.apache.zookeeper.server.quorum;


import org.apache.zookeeper.server.quorum.Vote;

interface Election {
    public Vote lookForLeader() throws InterruptedException;
}