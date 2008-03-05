package com.yahoo.zookeeper.server.quorum;


public class Vote {
    public Vote(long id, long zxid) {
        this.id = id;
        this.zxid = zxid;
    }

    public long id;
    
    public long zxid;
    
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Vote)) {
            return false;
        }
        Vote other = (Vote) o;
        return id == other.id && zxid == other.zxid;

    }

    @Override
    public int hashCode() {
        return (int) (id & zxid);
    }

}