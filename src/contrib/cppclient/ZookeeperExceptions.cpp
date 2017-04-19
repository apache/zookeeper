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

#include <zookeeper/zookeeper.h>
#include "ZookeeperClient.h"

namespace facebook {
namespace zeus {
namespace client {

ZookeeperMultiOpException::ZookeeperMultiOpException(
    int rc,
    std::vector<folly::Try<OpResponse>>&& responses)
    : ZookeeperClientException("multi op failed"),
      rc_(rc),
      responses_(std::make_shared<std::vector<folly::Try<OpResponse>>>(
          std::move(responses))) {}

std::string ZookeeperException::getReturnCodeDescription() const {
  return ::zerror(getReturnCode());
}

ZookeeperUnexpectedException::ZookeeperUnexpectedException(int rc)
    : ZookeeperException(folly::to<std::string>(
          "unexpected return code ",
          rc,
          ": ",
          ::zerror(rc))),
      rc_(rc) {}

int ZookeeperUnexpectedException::getReturnCode() const {
  return rc_;
}

int ZookeeperSystemErrorException::getReturnCode() const {
  return ZSYSTEMERROR;
}

int ZookeeperRuntimeInconsistencyException::getReturnCode() const {
  return ZRUNTIMEINCONSISTENCY;
}

int ZookeeperDataInconsistencyException::getReturnCode() const {
  return ZDATAINCONSISTENCY;
}

int ZookeeperConnectionLossException::getReturnCode() const {
  return ZCONNECTIONLOSS;
}

int ZookeeperMarshallingException::getReturnCode() const {
  return ZMARSHALLINGERROR;
}

int ZookeeperUnimplementedException::getReturnCode() const {
  return ZUNIMPLEMENTED;
}

int ZookeeperTimeoutException::getReturnCode() const {
  return ZOPERATIONTIMEOUT;
}

int ZookeeperBadArgumentsException::getReturnCode() const {
  return ZBADARGUMENTS;
}

int ZookeeperInvalidStateException::getReturnCode() const {
  return ZINVALIDSTATE;
}

int ZookeeperNoNodeException::getReturnCode() const {
  return ZNONODE;
}

int ZookeeperNoAuthException::getReturnCode() const {
  return ZNOAUTH;
}

int ZookeeperBadVersionException::getReturnCode() const {
  return ZBADVERSION;
}

int ZookeeperNoChildrenForEphemeralsException::getReturnCode() const {
  return ZNOCHILDRENFOREPHEMERALS;
}

int ZookeeperNodeExistsException::getReturnCode() const {
  return ZNODEEXISTS;
}

int ZookeeperNotEmptyException::getReturnCode() const {
  return ZNOTEMPTY;
}

int ZookeeperSessionExpiredException::getReturnCode() const {
  return ZSESSIONEXPIRED;
}

int ZookeeperInvalidCallbackException::getReturnCode() const {
  return ZINVALIDCALLBACK;
}

int ZookeeperInvalidACLException::getReturnCode() const {
  return ZINVALIDACL;
}

int ZookeeperAuthFailedException::getReturnCode() const {
  return ZAUTHFAILED;
}

int ZookeeperClosingException::getReturnCode() const {
  return ZCLOSING;
}

int ZookeeperNothingException::getReturnCode() const {
  return ZNOTHING;
}

int ZookeeperSessionMovedException::getReturnCode() const {
  return ZSESSIONMOVED;
}

int ZookeeperMultiOpException::getReturnCode() const {
  return rc_;
}
}
}
}
