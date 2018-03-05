/*
 * SyncRequestProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef SyncRequestProcessor_HH_
#define SyncRequestProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "./ZooKeeperServer.hh"
#include "./RequestProcessor.hh"
#include "./ZooKeeperCriticalThread.hh"

namespace efc {
namespace ezk {

/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 *
 * SyncRequestProcessor is used in 3 different cases
 * 1. Leader - Sync request to disk and forward it to AckRequestProcessor which
 *             send ack back to itself.
 * 2. Follower - Sync request to disk and forward request to
 *             SendAckRequestProcessor which send the packets to leader.
 *             SendAckRequestProcessor is flushable which allow us to force
 *             push packets to leader.
 * 3. Observer - Sync committed request to disk (received as INFORM packet).
 *             It never send ack back to the leader, so the nextProcessor will
 *             be null. This change the semantic of txnlog on the observer
 *             since it only contains committed txns.
 */
class SyncRequestProcessor : public ZooKeeperCriticalThread, virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(SyncRequestProcessor.class);
    sp<ZooKeeperServer> zks;
    ELinkedBlockingQueue<Request> queuedRequests;// = new LinkedBlockingQueue<Request>();
    sp<RequestProcessor> nextProcessor;

    sp<EThread> snapInProcess;// = null;
    volatile boolean running;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    ELinkedList<sp<Request> > toFlush;// = new LinkedList<Request>();
    ERandom r;// = new Random(System.nanoTime());

    sp<Request> requestOfDeath;// = Request.requestOfDeath;

    /**
     * The number of log entries to log before starting a snapshot
     */
    static int snapCount;// = ZooKeeperServer::getSnapCount();
    
    /**
     * The number of log entries before rolling the log, number
     * is chosen randomly
     */
    static int randRoll;

    /**
     * Sets the value of randRoll. This method
     * is here to avoid a findbugs warning for
     * setting a static variable in an instance
     * method.
     *
     * @param roll
     */
    static void setRandRoll(int roll) {
        randRoll = roll;
    }

	void flush(ELinkedList<sp<Request> >& toFlush)
		THROWS2(EIOException, RequestProcessorException)
	{
		if (toFlush.isEmpty())
			return;

		zks->getZKDatabase()->commit();
		while (!toFlush.isEmpty()) {
			sp<Request> i = toFlush.remove();
			if (nextProcessor != null) {
				nextProcessor->processRequest(i);
			}
		}
		if (nextProcessor != null) {
			EFlushable* f = dynamic_cast<EFlushable*>(nextProcessor.get());
			if (f) {
				f->flush();
			}
		}
	}

public:
    virtual ~SyncRequestProcessor() {
    	//
    }

    SyncRequestProcessor(sp<ZooKeeperServer> zks,
            sp<RequestProcessor> nextProcessor) :
            	ZooKeeperCriticalThread(EString("SyncThread:") + zks->getServerId(), zks
                        ->getZooKeeperServerListener()),
                        r(ESystem::nanoTime()) {
        this->zks = zks;
        this->nextProcessor = nextProcessor;
        running = true;
        requestOfDeath = Request::requestOfDeath;
    }
    
    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    static void setSnapCount(int count) {
        snapCount = count;
        randRoll = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    static int getSnapCount() {
        return snapCount;
    }

    virtual void run() {
        try {
            int logCount = 0;

            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            setRandRoll(r.nextInt(snapCount/2));
            while (true) {
                sp<Request> si = null;
                if (toFlush.isEmpty()) {
                    si = queuedRequests.take();
                } else {
                    si = queuedRequests.poll();
                    if (si == null) {
                        flush(toFlush);
                        continue;
                    }
                }
                if (si == requestOfDeath) {
                    break;
                }
                if (si != null) {
                    // track the number of records written to the log
                    if (zks->getZKDatabase()->append(si)) {
                        logCount++;
                        if (logCount > (snapCount / 2 + randRoll)) {
                            setRandRoll(r.nextInt(snapCount/2));
                            // roll the log
                            zks->getZKDatabase()->rollLog();
                            // take a snapshot
                            if (snapInProcess != null && snapInProcess->isAlive()) {
                                LOG->warn("Too busy to snap, skipping");
                            } else {
                            	class SnapInProcess : public ZooKeeperThread {
                            	private:
                            		SyncRequestProcessor* self;
                            	public:
                            		SnapInProcess(SyncRequestProcessor* srp, EString name) : ZooKeeperThread(name), self(srp) {
                            		}
                            		virtual void run() {
										try {
											self->zks->takeSnapshot();
										} catch(EException& e) {
											LOG->warn("Unexpected exception", e);
										}
									}
                            	};
                                snapInProcess = new SnapInProcess(this, "Snapshot Thread");
                                EThread::setDaemon(snapInProcess, true); //!!!
                                snapInProcess->start();
                            }
                            logCount = 0;
                        }
                    } else if (toFlush.isEmpty()) {
                        // optimization for read heavy workloads
                        // iff this is a read, and there are no pending
                        // flushes (writes), then just pass this to the next
                        // processor
                        if (nextProcessor != null) {
                            nextProcessor->processRequest(si);
                            /*@see:
                            if (nextProcessor instanceof Flushable) {
                                ((Flushable)nextProcessor).flush();
                            }
                            */
                            EFlushable* f = dynamic_cast<EFlushable*>(nextProcessor.get());
                            if (f) {
                            	f->flush();
                            }
                        }
                        continue;
                    }
                    toFlush.add(si);
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (EThrowable& t) {
            handleException(this->getName(), t);
            running = false;
        }
        LOG->info("SyncRequestProcessor exited!");
    }

    virtual void shutdown() {
        LOG->info("Shutting down");
        queuedRequests.add(requestOfDeath);
        try {
            if(running){
                this->join();
            }
            if (!toFlush.isEmpty()) {
                flush(toFlush);
            }
        } catch(EInterruptedException& e) {
            LOG->warn("Interrupted while wating for " + this->toString() + " to finish");
        } catch (EIOException& e) {
            LOG->warn("Got IO exception during shutdown");
        } catch (RequestProcessorException& e) {
            LOG->warn("Got request processor exception during shutdown");
        }
        if (nextProcessor != null) {
            nextProcessor->shutdown();
        }
    }

    virtual void processRequest(sp<Request> request) {
        // request.addRQRec(">sync");
        queuedRequests.add(request);
    }

};

} /* namespace ezk */
} /* namespace efc */
#endif /* SyncRequestProcessor_HH_ */
