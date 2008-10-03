package org.apache.zookeeper.server.quorum;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.Request;

public class FollowerSyncRequest extends Request {
	FollowerHandler fh;
	public FollowerSyncRequest(FollowerHandler fh, long sessionId, int xid, int type,
			ByteBuffer bb, List<Id> authInfo) {
		super(null, sessionId, xid, type, bb, authInfo);
		this.fh = fh;
	}
}
