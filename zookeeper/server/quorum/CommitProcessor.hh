/*
 * CommitProcessor.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef CommitProcessor_HH_
#define CommitProcessor_HH_

#include "Efc.hh"
#include "ELog.hh"

#include "../RequestProcessor.hh"
#include "../ZooKeeperCriticalThread.hh"
#include "../ZooKeeperServerListener.hh"
#include "../../ZooDefs.hh"

namespace efc {
namespace ezk {

/**
 * This RequestProcessor matches the incoming committed requests with the
 * locally submitted requests. The trick is that locally submitted requests that
 * change the state of the system will come back as incoming committed requests,
 * so we need to match them up.
 */
class CommitProcessor : public ZooKeeperCriticalThread, virtual public RequestProcessor {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(CommitProcessor.class);

public:
    /**
     * Requests that we are holding until the commit comes in.
     */
    ELinkedList<sp<Request> > queuedRequests;// = new LinkedList<Request>();

    /**
     * Requests that have been committed.
     */
    ELinkedList<sp<Request> > committedRequests;// = new LinkedList<Request>();

    sp<RequestProcessor> nextProcessor;
    EArrayList<sp<Request> > toProcess;// = new ArrayList<Request>();

    /**
     * This flag indicates whether we need to wait for a response to come back from the
     * leader or we just let the sync operation flow through like a read. The flag will
     * be true if the CommitProcessor is in a Leader pipeline.
     */
    boolean matchSyncs;

    volatile boolean finished;// = false;

    CommitProcessor(sp<RequestProcessor> nextProcessor, EString id,
            boolean matchSyncs, ZooKeeperServerListener* listener);

    virtual void run();

    synchronized
    void commit(sp<Request> request);

    synchronized
    void processRequest(sp<Request> request);

    virtual void shutdown();
};

} /* namespace ezk */
} /* namespace efc */
#endif /* CommitProcessor_HH_ */
