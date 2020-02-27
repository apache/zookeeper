package org.apache.zookeeper.server.quorum;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PingLaggingDetectorTest {
    @Test
    public void testLeaderPingLaggingDetector() {
        // detect lagging for 10ms
        PingLaggingDetector detector = new LeaderPingLaggingDetector(10);
        detector.start();

        // track message 1, which is marked sent at 1ms
        detector.trackMessage(1, ms2ns(1));
        // check if we're lagging, current time is 5ms
        assertFalse(detector.isLagging(ms2ns(5)));
        // check if we're lagging, current time is 12ms
        assertTrue(detector.isLagging(ms2ns(12)));


        // ack message 1
        detector.trackAck(1);

        // no message in track, so response false
        assertFalse(detector.isLagging(ms2ns(11)));

        detector.trackMessage(2, ms2ns(12));
        detector.trackMessage(3, ms2ns(13));
        detector.trackMessage(4, ms2ns(14));

        // after this, it will track for message 4
        detector.trackAck(2);

        assertFalse(detector.isLagging(ms2ns(23)));
        assertTrue(detector.isLagging(ms2ns(24)));
    }

    @Test
    public void testLearnerPingLaggingDetector() {
        // lagging threshold is 10 ms
        PingLaggingDetector detector = new LearnerPingLaggingDetector(10);
        detector.start();

        // track message 1, which is marked sent at 1ms
        detector.trackMessage(1, ms2ns(1));
        // check if we're lagging, current time is 5ms
        assertFalse(detector.isLagging(ms2ns(5)));
        // check if we're lagging, current time is 12ms
        assertTrue(detector.isLagging(ms2ns(12)));

        // ack message 1
        detector.trackAck(1);

        // no message in track, so response false
        assertFalse(detector.isLagging(ms2ns(11)));

        detector.trackMessage(2, ms2ns(12));
        detector.trackMessage(3, ms2ns(13));
        detector.trackMessage(4, ms2ns(14));

        // the LearnerPingLaggingDetector checks delay for every PING
        // after this, it will track for message 3
        detector.trackAck(2);

        // the learnerPingLaggingDetector reports lagging because it is checking
        // for message 3 sent at ms 13
        // the leaderPingLaggingDetector will not report lagging at this
        // point since it skips message 3 and would be checking for message 4
        assertTrue(detector.isLagging(ms2ns(23)));
        assertTrue(detector.isLagging(ms2ns(24)));
    }

    private long ms2ns(long ms) {
        return ms * 1000000;
    }
}
