/*
 * NIOServerCnxnFactory.cpp
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#include "./NIOServerCnxnFactory.hh"

namespace efc {
namespace ezk {

sp<ELogger> NIOServerCnxnFactory::LOG = ELoggerManager::getLogger("NIOServerCnxnFactory");

void NIOServerCnxnFactory::addCnxn(sp<NIOServerCnxn> cnxn) {
	SYNCBLOCK(&cnxnsLock) {
		cnxns.add(dynamic_pointer_cast<ServerCnxn>(cnxn));
		SYNCBLOCK(&ipMapLock){
			EInetAddress* addr = cnxn->sock->socket()->getInetAddress();
			sp<EHashSet<sp<NIOServerCnxn> > > s = ipMap.get(addr->getAddress());
			if (s == null) {
				// in general we will see 1 connection from each
				// host, setting the initial cap to 2 allows us
				// to minimize mem usage in the common case
				// of 1 entry --  we need to set the initial cap
				// to 2 to avoid rehash when the first entry is added
				s = new EHashSet<sp<NIOServerCnxn> >(2);
				s->add(cnxn);
				ipMap.put(addr->getAddress(),s);
			} else {
				s->add(cnxn);
			}
		}}
	}}
}

void NIOServerCnxnFactory::removeCnxn(NIOServerCnxn* cnxn) {
	SYNCBLOCK(&cnxnsLock) {
		// Remove the related session from the sessionMap.
		llong sessionId = cnxn->getSessionId();
		if (sessionId != 0) {
			sessionMap.remove(sessionId);
		}

		// if this is not in cnxns then it's already closed
		if (!cnxns.remove(cnxn)) {
			return;
		}

		SYNCBLOCK(&ipMapLock){
			sp<EHashSet<sp<NIOServerCnxn> > > s =
					ipMap.get(cnxn->getSocketAddress()->getAddress());
			s->remove(cnxn);
		}}
	}}
}

sp<NIOServerCnxn> NIOServerCnxnFactory::createConnection(sp<ESocketChannel> sock,
		sp<ESelectionKey> sk) THROWS(EIOException) {
	return new NIOServerCnxn(zkServer, sock, sk, this);
}

void NIOServerCnxnFactory::run() {
	while (!ss->socket()->isClosed()) {
		try {
			selector->select(1000);
			ESet<sp<ESelectionKey> >* selected;
			SYNCHRONIZED(this) {
				selected = selector->selectedKeys();
			}}
			/* @see:
			ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
					selected);
			Collections.shuffle(selectedList);
			for (SelectionKey k : selectedList) {
			*/
			EArrayList<sp<ESelectionKey> > selectedList;
			auto it = selected->iterator();
			while (it->hasNext()) {
				sp<ESelectionKey> k = it->next();
				selectedList.add(k);
			}
			ECollections::shuffle(&selectedList);
			auto iter = selectedList.iterator();
			while (iter->hasNext()) {
				sp<ESelectionKey> k = iter->next();
				if ((k->readyOps() & ESelectionKey::OP_ACCEPT) != 0) {
					sp<ESocketChannel> sc = (dynamic_pointer_cast<EServerSocketChannel>(k
							->channel()))->accept();
					EInetAddress* ia = sc->socket()->getInetAddress();
					int cnxncount = getClientCnxnCount(ia);
					if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
						LOG->warn("Too many connections from " + ia->toString()
								 + " - max is " + maxClientCnxns );
						sc->close();
					} else {
						LOG->info("Accepted socket connection from "
								 + sc->socket()->getRemoteSocketAddress()->toString());
						sc->configureBlocking(false);
						sp<ESelectionKey> sk = sc->register_(selector.get(),
								ESelectionKey::OP_READ);
						sp<NIOServerCnxn> cnxn = createConnection(sc, sk);
						delete sk->attach(new wp<NIOServerCnxn>(cnxn));
						addCnxn(cnxn);
					}
				} else if ((k->readyOps() & (ESelectionKey::OP_READ | ESelectionKey::OP_WRITE)) != 0) {
                    wp<NIOServerCnxn>* p = dynamic_cast<wp<NIOServerCnxn>*>(k->attachment());
                    ES_ASSERT(p);
					sp<NIOServerCnxn> c = p->lock();
					c->doIO(k);
				} else {
					if (LOG->isDebugEnabled()) {
						LOG->debug(EString("Unexpected ops in select ")
								  + k->readyOps());
					}
				}
			}
			selected->clear();
		} catch (ERuntimeException& e) {
			LOG->warn("Ignoring unexpected runtime exception", e);
		} catch (EException& e) {
			LOG->warn("Ignoring exception", e);
		}
	}
	closeAll();
	LOG->info("NIOServerCnxn factory exited run method");
}

void NIOServerCnxnFactory::closeAll() {
	selector->wakeup();
	EHashSet<sp<ServerCnxn> > cnxns;
	SYNCBLOCK(&this->cnxnsLock) {
		cnxns = this->cnxns;
	}}
	// got to clear all the connections that we have in the selector
	auto iter = cnxns.iterator();
	while (iter->hasNext()) {
		auto cnxn = dynamic_pointer_cast<NIOServerCnxn>(iter->next());
		try {
			// don't hold this.cnxns lock as deadlock may occur
			cnxn->close();
		} catch (EException& e) {
			LOG->warn("Ignoring exception closing cnxn sessionid 0x"
					 + ELLong::toHexString(cnxn->sessionId), e);
		}
	}
}

void NIOServerCnxnFactory::shutdown() {
	try {
		ss->close();
		closeAll();
		thread->interrupt();
		thread->join();
	} catch (EInterruptedException& e) {
		LOG->warn("Ignoring interrupted exception during shutdown", e);
	} catch (EException& e) {
		LOG->warn("Ignoring unexpected exception during shutdown", e);
	}
	try {
		selector->close();
	} catch (EIOException& e) {
		LOG->warn("Selector closing", e);
	}
	if (zkServer != null) {
		zkServer->shutdown();
	}
}

void NIOServerCnxnFactory::closeSession(llong sessionId) {
	SYNCHRONIZED(this) {
		selector->wakeup();
		closeSessionWithoutWakeup(sessionId);
	}}
}

} /* namespace ezk */
} /* namespace efc */
