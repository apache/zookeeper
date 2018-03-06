/*
 * SyncRequestProcessor.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./SyncRequestProcessor.hh"

namespace efc {
namespace ezk {

sp<ELogger> SyncRequestProcessor::LOG = ELoggerManager::getLogger("SyncRequestProcessor");

int SyncRequestProcessor::snapCount = ZooKeeperServer::getSnapCount();

int SyncRequestProcessor::randRoll = 0;

} /* namespace ezk */
} /* namespace efc */
