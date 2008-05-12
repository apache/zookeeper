package com.yahoo.zookeeper.server.quorum;


import com.yahoo.zookeeper.server.quorum.Vote;

interface Election {
    public Vote lookForLeader() throws InterruptedException;
}