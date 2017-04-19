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

#include "zeus/client/detail/BasicZookeeperClient.h"
#include <cstring>

namespace facebook {
namespace zeus {
namespace client {
namespace detail {

std::string BasicZookeeperClient::buildConnectionString(
    const std::vector<folly::SocketAddress>& servers,
    const std::string& chroot) {
  std::vector<std::string> hostPorts;
  for (const auto& server : servers) {
    hostPorts.push_back(folly::to<std::string>(
        server.getIPAddress().toFullyQualified(), ':', server.getPort()));
  }
  return folly::join(',', hostPorts) + chroot;
}

Stat BasicZookeeperClient::convertStat(const ::Stat& s) {
  return Stat{s.czxid,
              s.mzxid,
              std::chrono::system_clock::time_point() +
                  std::chrono::milliseconds(s.ctime),
              std::chrono::system_clock::time_point() +
                  std::chrono::milliseconds(s.mtime),
              s.version,
              s.cversion,
              s.aversion,
              s.ephemeralOwner,
              s.dataLength,
              s.numChildren,
              s.pzxid};
}

int BasicZookeeperClient::convertCreateMode(const CreateMode& m) {
  int flags = 0;
  if (m.isEphemeral) {
    flags |= ZOO_EPHEMERAL;
  }
  if (m.isSequential) {
    flags |= ZOO_SEQUENCE;
  }
  return flags;
}

SessionState BasicZookeeperClient::convertStateType(int state) {
  if (state == ZOO_EXPIRED_SESSION_STATE) {
    return SessionState::EXPIRED;
  } else if (state == ZOO_AUTH_FAILED_STATE) {
    return SessionState::AUTH_FAILED;
  } else if (state == ZOO_CONNECTING_STATE) {
    return SessionState::CONNECTING;
  } else if (state == ZOO_ASSOCIATING_STATE) {
    return SessionState::ASSOCIATING;
  } else if (state == ZOO_CONNECTED_STATE) {
    return SessionState::CONNECTED;
  } else if (state == 0 || state == ZOO_NOTCONNECTED_STATE) {
    return SessionState::DISCONNECTED;
  } else if (state == ZOO_TIMED_OUT_STATE) {
    return SessionState::TIMED_OUT;
  } else {
    throw std::runtime_error(
        folly::to<std::string>("unrecognized ZK state ", state));
  }
}

NodeEvent BasicZookeeperClient::convertWatchEventType(
    const char* path,
    int inType,
    int inState,
    size_t index) {
  WatchEventType type;
  if (inType == ZOO_CREATED_EVENT) {
    type = WatchEventType::CREATED;
  } else if (inType == ZOO_DELETED_EVENT) {
    type = WatchEventType::DELETED;
  } else if (inType == ZOO_CHANGED_EVENT) {
    type = WatchEventType::CHANGED;
  } else if (inType == ZOO_CHILD_EVENT) {
    type = WatchEventType::CHILD;
  } else if (inType == ZOO_SESSION_EVENT) {
    type = WatchEventType::SESSION;
  } else if (inType == ZOO_NOTWATCHING_EVENT) {
    type = WatchEventType::NOT_WATCHING;
  } else {
    throw std::runtime_error(
        folly::to<std::string>("unexpected watch event type ", inType));
  }

  return NodeEvent{index, path, type, convertStateType(inState)};
}

BasicZookeeperClient::BasicZookeeperClient(
    const std::string& connectionString,
    std::chrono::milliseconds sessionTimeout,
    const SessionToken* token,
    InitialWatches&& initialWatches)
    : initialWatches_(std::move(initialWatches)) {
  std::vector<const char*> dataWatchPaths;
  for (const auto& dataWatchPath : initialWatches_.getDataWatchPaths()) {
    dataWatchPaths.push_back(dataWatchPath.c_str());
  }
  std::vector<const char*> childWatchPaths;
  for (const auto& childWatchPath : initialWatches_.getChildWatchPaths()) {
    childWatchPaths.push_back(childWatchPath.c_str());
  }

  std::unique_ptr<::clientid_t> clientid;
  if (token) {
    clientid = std::make_unique<::clientid_t>();
    clientid->client_id = token->sessionId;
    memcpy(clientid->passwd, token->passwd.data(), 16);
  }

  folly::SharedMutex::WriteHolder g(zhLock_);
  zh_ = zookeeper_init_with_watches(
      connectionString.c_str(),
      sSessionWatchCallback,
      sessionTimeout.count(),
      clientid.get(),
      this,
      1,
      0,
      dataWatchPaths.size(),
      dataWatchPaths.empty() ? nullptr : dataWatchPaths.data(),
      childWatchPaths.size(),
      childWatchPaths.empty() ? nullptr : childWatchPaths.data(),
      initialWatches_.getLastZxid());
  if (zh_ == nullptr) {
    throw ZookeeperSystemErrorException();
  }
}

BasicZookeeperClient::BasicZookeeperClient(
    const std::vector<folly::SocketAddress>& servers,
    const std::string& chroot,
    std::chrono::milliseconds sessionTimeout,
    const SessionToken* token,
    InitialWatches&& iw)
    : BasicZookeeperClient(
          buildConnectionString(servers, chroot),
          sessionTimeout,
          token,
          std::move(iw)) {}

BasicZookeeperClient::~BasicZookeeperClient() {
  folly::SharedMutex::WriteHolder g(zhLock_);
  if (zh_ != nullptr) {
    int rc = zookeeper_close(zh_);
    if (rc != ZOK) {
      LOG(ERROR) << "error closing Zookeeper session, error code = " << rc;
    }
    contextStorage_.closeAll();
  }
}

int64_t BasicZookeeperClient::getSessionID() const {
  folly::SharedMutex::ReadHolder g(zhLock_);
  return zoo_client_id(zh_)->client_id;
}

SessionToken BasicZookeeperClient::getSessionToken() const {
  SessionToken rval;
  folly::SharedMutex::ReadHolder g(zhLock_);
  auto* id = zoo_client_id(zh_);
  rval.sessionId = id->client_id;
  std::memcpy(rval.passwd.data(), id->passwd, sizeof(char) * 16);
  return rval;
}

std::chrono::milliseconds BasicZookeeperClient::getSessionTimeout() const {
  folly::SharedMutex::ReadHolder g(zhLock_);
  return std::chrono::milliseconds(zoo_recv_timeout(zh_));
}

SessionState BasicZookeeperClient::getState() const {
  folly::SharedMutex::ReadHolder g(zhLock_);
  if (zh_ == nullptr) {
    return SessionState::DISCONNECTED;
  } else {
    auto rc = zoo_state(zh_);
    g.unlock();
    return convertStateType(rc);
  }
}

template <class T>
static T processSynchronousErrorCodes(int rc) {
  switch (rc) {
    case ZBADARGUMENTS:
      throw ZookeeperBadArgumentsException();
    case ZINVALIDSTATE:
      throw ZookeeperInvalidStateException();
    case ZMARSHALLINGERROR:
      throw ZookeeperMarshallingException();
    case ZSYSTEMERROR:
      throw ZookeeperSystemErrorException();
    default:
      throw ZookeeperUnexpectedException(rc);
  }
}

void BasicZookeeperClient::close() {
  folly::SharedMutex::WriteHolder g(zhLock_);
  if (zh_ != nullptr) {
    int rc = zookeeper_close(zh_);
    zh_ = nullptr;
    contextStorage_.closeAll();
    if (rc != ZOK) {
      processSynchronousErrorCodes<void>(rc);
    }
  }
}

void BasicZookeeperClient::sSessionWatchCallback(
    zhandle_t*,
    int type,
    int state,
    const char* path,
    void* watcherCtx) {
  auto* c =
      reinterpret_cast<BasicZookeeperClient*>(const_cast<void*>(watcherCtx));
  auto e = convertWatchEventType(path, type, state);
  std::unique_lock<std::mutex> g(c->initialWatchesLock_);
  c->initialWatches_.onWatchEvent(e.type, e.state, e.path);
  g.unlock();
  if (e.type == WatchEventType::SESSION) {
    c->onSessionEvent(e.state);
  }
}

template <class T>
void BasicZookeeperClient::sCallbackException(folly::Promise<T>& p, int rc) {
  switch (rc) {
    case ZNOAUTH:
      p.setException(ZookeeperNoAuthException());
      break;
    case ZCONNECTIONLOSS:
      p.setException(ZookeeperConnectionLossException());
      break;
    case ZOPERATIONTIMEOUT:
      p.setException(ZookeeperTimeoutException());
      break;
    case ZSESSIONEXPIRED:
      p.setException(ZookeeperSessionExpiredException());
      break;
    default:
      p.setException(ZookeeperUnexpectedException(rc));
      break;
  }
}

void BasicZookeeperClient::sGetCallback(
    int rc,
    const char* value,
    int value_len,
    const ::Stat* stat,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<GetContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue(
          GetDataResult{std::make_unique<folly::IOBuf>(
                            folly::IOBuf::COPY_BUFFER, value, value_len),
                        convertStat(*stat)});
      break;
    case ZNONODE:
      c->getPromise().setException(
          ZookeeperNoNodeException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sWatchCallback(
    zhandle_t*,
    int type,
    int state,
    const char* path,
    void* watcherCtx) {
  if (type == ZOO_SESSION_EVENT) {
    reinterpret_cast<WatchContext*>(watcherCtx)
        ->getSessionEventWatcher()
        ->onSessionEvent(convertStateType(state));
  } else {
    auto c = ContextStorage::extract<WatchContext>(watcherCtx);
    auto sessionEventWatcher = c->getSessionEventWatcher();

    NodeEvent e(convertWatchEventType(
        path, type, state, sessionEventWatcher->getNextIndex()));

    c->getPromise().setValue(std::move(e));
  }
}

void BasicZookeeperClient::sStatCallback(
    int rc,
    const ::Stat* stat,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<StatContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue(convertStat(*stat));
      break;
    case ZBADVERSION:
      c->getPromise().setException(
          ZookeeperBadVersionException(std::move(c->path)));
      break;
    case ZNONODE:
      c->getPromise().setException(
          ZookeeperNoNodeException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sOptionalStatCallback(
    int rc,
    const ::Stat* stat,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<OptionalStatContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue(convertStat(*stat));
      break;
    case ZNONODE:
      c->getPromise().setValue(folly::none);
      break;
    case ZBADVERSION:
      c->getPromise().setException(
          ZookeeperBadVersionException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sCreateCallback(
    int rc,
    const char* value,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<CreateContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue(CreateResult{value, folly::none});
      break;
    case ZNOCHILDRENFOREPHEMERALS:
      c->getPromise().setException(
          ZookeeperNoChildrenForEphemeralsException(std::move(c->path)));
      break;
    case ZNODEEXISTS:
      c->getPromise().setException(
          ZookeeperNodeExistsException(std::move(c->path)));
      break;
    case ZNONODE:
      // Parent does not exist
      c->getPromise().setException(
          ZookeeperNoNodeException(c->path.substr(0, c->path.rfind('/'))));
      break;
    case ZINVALIDACL:
      c->getPromise().setException(
          ZookeeperInvalidACLException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sVoidCallback(int rc, const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<VoidContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue();
      break;
    case ZBADVERSION:
      c->getPromise().setException(
          ZookeeperBadVersionException(std::move(c->path)));
      break;
    case ZNONODE:
      c->getPromise().setException(
          ZookeeperNoNodeException(std::move(c->path)));
      break;
    case ZNOTEMPTY:
      c->getPromise().setException(
          ZookeeperNotEmptyException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sSizeCallback(
    int rc,
    const int64_t* size,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<SizeContext>(size);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue(*size);
      break;
    case ZNONODE:
      c->getPromise().setException(
          ZookeeperNoNodeException(std::move(c->path)));
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sAuthCallback(int rc, const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<AuthContext>(data);
  if (!c) {
    return;
  }

  switch (rc) {
    case ZOK:
      c->getPromise().setValue();
      break;
    case ZAUTHFAILED:
      c->getPromise().setException(ZookeeperAuthFailedException());
      break;
    default:
      sCallbackException(c->getPromise(), rc);
      break;
  }
}

void BasicZookeeperClient::sGetChildrenCallback(
    int rc,
    const ::String_vector* strings,
    const ::Stat* stat,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<GetChildrenContext>(data);
  if (!c) {
    return;
  }

  if (rc == ZOK) {
    std::vector<std::string> children;
    for (int i = 0; i < strings->count; ++i) {
      children.emplace_back(strings->data[i]);
    }
    c->getPromise().setValue(
        GetChildrenResult{std::move(children), convertStat(*stat)});
  } else {
    switch (rc) {
      case ZNONODE:
        c->getPromise().setException(
            ZookeeperNoNodeException(std::move(c->path)));
        break;
      default:
        sCallbackException(c->getPromise(), rc);
        break;
    }
  }
}

void BasicZookeeperClient::sGetAclCallback(
    int rc,
    ::ACL_vector* inACL,
    ::Stat* stat,
    const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<GetAclContext>(data);
  if (!c) {
    return;
  }

  if (rc == ZOK) {
    ACL acl;
    for (int i = 0; i < inACL->count; ++i) {
      const auto& e = inACL->data[i];
      acl.push_back(ACLElement{e.perms, Id{e.id.scheme, e.id.id}});
    }
    c->getPromise().setValue(GetAclResult{convertStat(*stat), std::move(acl)});
  } else {
    switch (rc) {
      case ZNONODE:
        c->getPromise().setException(
            ZookeeperNoNodeException(std::move(c->path)));
        break;
      default:
        sCallbackException(c->getPromise(), rc);
        break;
    }
  }
}

OpResponse BasicZookeeperClient::buildOpResponse(
    const ::zoo_op_result_t& r,
    MultiOpType opType) {
  OpResponse out;
  if (r.value) {
    if (opType == MultiOpType::CREATE) {
      out.path = std::make_unique<std::string>(r.value);
    } else {
      out.data = std::make_unique<folly::IOBuf>(
          folly::IOBuf::COPY_BUFFER, r.value, r.valuelen);
    }
  }
  if (opType == MultiOpType::SETDATA) {
    out.stat = std::make_unique<Stat>(convertStat(*r.stat));
  }
  return out;
}

void BasicZookeeperClient::sMultiCallback(int rc, const void* data) {
  if (rc == ZCLOSING) {
    return;
  }

  auto c = ContextStorage::extract<MultiContext>(data);
  if (!c) {
    return;
  }

  if (rc == ZOK) {
    std::vector<OpResponse> rval;
    rval.reserve(c->paths.size());
    for (int i = 0; i < c->paths.size(); ++i) {
      rval.push_back(buildOpResponse(c->opResults.at(i), c->opTypes.at(i)));
    }
    c->getPromise().setValue(std::move(rval));
  } else {
    std::vector<folly::Try<OpResponse>> responses;
    int i = 0;
    auto itr = c->paths.begin();
    while (itr != c->paths.end()) {
      if (c->opResults.at(i).err == ZOK) {
        responses.emplace_back(
            buildOpResponse(c->opResults.at(i), c->opTypes.at(i)));
      } else {
        switch (c->opResults.at(i).err) {
          case ZNONODE:
            responses.emplace_back(
                ZookeeperNoNodeException(std::move(*itr)));
            break;
          case ZBADVERSION:
            responses.emplace_back(
                ZookeeperBadVersionException(std::move(*itr)));
            break;
          case ZNOTEMPTY:
            responses.emplace_back(
                ZookeeperNotEmptyException(std::move(*itr)));
            break;
          case ZINVALIDACL:
            responses.emplace_back(
                ZookeeperInvalidACLException(std::move(*itr)));
            break;
          case ZNOCHILDRENFOREPHEMERALS:
            responses.emplace_back(ZookeeperNoChildrenForEphemeralsException(
                std::move(*itr)));
            break;
          case ZNODEEXISTS:
            responses.emplace_back(
                ZookeeperNodeExistsException(std::move(*itr)));
            break;
          case ZNOAUTH:
            responses.emplace_back(ZookeeperNoAuthException());
            break;
          case ZCONNECTIONLOSS:
            responses.emplace_back(ZookeeperConnectionLossException());
            break;
          case ZOPERATIONTIMEOUT:
            responses.emplace_back(ZookeeperTimeoutException());
            break;
          default:
            responses.emplace_back(
                ZookeeperUnexpectedException(c->opResults.at(i).err));
            break;
        }
      }
      i++;
      itr++;
    }
    c->getPromise().setException(
        ZookeeperMultiOpException(rc, std::move(responses)));
  }
}

void BasicZookeeperClient::setServers(
    const std::vector<folly::SocketAddress>& servers) {
  auto connectString = buildConnectionString(servers);
  folly::SharedMutex::ReadHolder g(zhLock_);
  auto rc = zoo_set_servers(zh_, connectString.c_str());
  if (rc != ZOK) {
    processSynchronousErrorCodes<void>(rc);
  }
}

folly::Future<GetDataResult> BasicZookeeperClient::getData(
    const std::string& path) {
  auto* c = contextStorage_.add(std::make_unique<GetContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc =
      zoo_aget(zh_, path.c_str(), 0, sGetCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<GetDataResult>>(rc);
  }
}

DataWithWatch BasicZookeeperClient::getDataWithWatch(const std::string& path) {
  auto* gc = contextStorage_.add(std::make_unique<GetContext>(path));
  auto gf = gc->getPromise().getFuture();
  auto* wc = contextStorage_.add(std::make_unique<WatchContext>());
  auto wf = wc->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_awget(zh_, path.c_str(), sWatchCallback, wc, sGetCallback, gc);
  zhg.unlock();

  if (rc == ZOK) {
    return DataWithWatch{
        std::move(gf), wc->getSessionEventWatcher(), std::move(wf)};
  } else {
    contextStorage_.erase(gc);
    contextStorage_.erase(wc);
    return processSynchronousErrorCodes<DataWithWatch>(rc);
  }
}

folly::Future<Stat> BasicZookeeperClient::setDataInternal(
    const std::string& path,
    folly::ByteRange data,
    int version) {
  auto* c = contextStorage_.add(std::make_unique<StatContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aset(
      zh_,
      path.c_str(),
      reinterpret_cast<const char*>(data.data()),
      data.size(),
      version,
      sStatCallback,
      reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<Stat>>(rc);
  }
}

BasicZookeeperClient::CACL::CACL(const ACL& acl)
    : aclVector_(nullptr), acls_(nullptr) {
  if (!acl.empty()) {
    aclVector_ = std::make_unique<::ACL_vector>();
    aclVector_->count = acl.size();

    acls_.reset(new ::ACL[acl.size()]);
    for (int i = 0; i < acl.size(); ++i) {
      acls_[i].perms = acl[i].perms;

      // We only return a const* to acls_.
      acls_[i].id.scheme = const_cast<char*>(acl[i].id.scheme.c_str());
      acls_[i].id.id = const_cast<char*>(acl[i].id.id.c_str());
    }

    aclVector_->data = acls_.get();
  }
}

folly::Future<CreateResult> BasicZookeeperClient::createNodeInternal(
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  auto* c = contextStorage_.add(std::make_unique<CreateContext>(path));
  auto f = c->getPromise().getFuture();

  CACL cACL(acl);

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_acreate(
      zh_,
      path.c_str(),
      reinterpret_cast<const char*>(data.data()),
      data.size(),
      cACL.getCPtr(),
      convertCreateMode(createMode),
      sCreateCallback,
      reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<CreateResult>>(rc);
  }
}

folly::Future<folly::Unit> BasicZookeeperClient::deleteNode(
    const std::string& path,
    int version) {
  auto* c = contextStorage_.add(std::make_unique<VoidContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_adelete(
      zh_, path.c_str(), version, sVoidCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<folly::Unit>>(rc);
  }
}

folly::Future<GetChildrenResult> BasicZookeeperClient::getChildren(
    const std::string& path) {
  auto* c = contextStorage_.add(
      std::make_unique<GetChildrenContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aget_children2(
      zh_, path.c_str(), 0, sGetChildrenCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<GetChildrenResult>>(rc);
  }
}

ChildrenWithWatch BasicZookeeperClient::getChildrenWithWatch(
    const std::string& path) {
  auto* cc = contextStorage_.add(
      std::make_unique<GetChildrenContext>(path));
  auto cf = cc->getPromise().getFuture();
  auto* wc = contextStorage_.add(std::make_unique<WatchContext>());
  auto wf = wc->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_awget_children2(
      zh_,
      path.c_str(),
      sWatchCallback,
      reinterpret_cast<void*>(wc),
      sGetChildrenCallback,
      reinterpret_cast<void*>(cc));
  zhg.unlock();

  if (rc == ZOK) {
    return ChildrenWithWatch{
        std::move(cf), wc->getSessionEventWatcher(), std::move(wf)};
  } else {
    contextStorage_.erase(cc);
    contextStorage_.erase(wc);
    return processSynchronousErrorCodes<ChildrenWithWatch>(rc);
  }
}

folly::Future<folly::Optional<Stat>> BasicZookeeperClient::exists(
    const std::string& path) {
  auto* c = contextStorage_.add(
      std::make_unique<OptionalStatContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aexists(
      zh_, path.c_str(), 0, sOptionalStatCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<folly::Optional<Stat>>>(
        rc);
  }
}

StatWithWatch BasicZookeeperClient::existsWithWatch(const std::string& path) {
  auto* ec = contextStorage_.add(
      std::make_unique<OptionalStatContext>(path));
  auto ef = ec->getPromise().getFuture();
  auto* wc = contextStorage_.add(std::make_unique<WatchContext>());
  auto wf = wc->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_awexists(
      zh_,
      path.c_str(),
      sWatchCallback,
      reinterpret_cast<void*>(wc),
      sOptionalStatCallback,
      reinterpret_cast<void*>(ec));
  zhg.unlock();

  if (rc == ZOK) {
    return StatWithWatch{
        std::move(ef), wc->getSessionEventWatcher(), std::move(wf)};
  } else {
    contextStorage_.erase(ec);
    contextStorage_.erase(wc);
    return processSynchronousErrorCodes<StatWithWatch>(rc);
  }
}

folly::Future<int64_t> BasicZookeeperClient::getSubtreeSize(
    const std::string& path) {
  auto* c = contextStorage_.add(std::make_unique<SizeContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aget_subtree_size(
      zh_, path.c_str(), sSizeCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<int64_t>>(rc);
  }
}

folly::Future<GetAclResult> BasicZookeeperClient::getAcl(
    const std::string& path) {
  auto* c = contextStorage_.add(std::make_unique<GetAclContext>(path));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aget_acl(
      zh_, path.c_str(), sGetAclCallback, reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<GetAclResult>>(rc);
  }
}

folly::Future<folly::Unit> BasicZookeeperClient::setAcl(
    const std::string& path,
    const ACL& acl,
    int version) {
  auto* c = contextStorage_.add(std::make_unique<VoidContext>(path));
  auto f = c->getPromise().getFuture();

  CACL cACL(acl);

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_aset_acl(
      zh_,
      path.c_str(),
      version,
      const_cast<::ACL_vector*>(cACL.getCPtr()),
      sVoidCallback,
      reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<folly::Unit>>(rc);
  }
}

folly::Future<folly::Unit> BasicZookeeperClient::addAuth(
    const std::string& scheme,
    const std::string& cert) {
  auto* c = contextStorage_.add(std::make_unique<AuthContext>());
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_add_auth(
      zh_,
      scheme.c_str(),
      cert.c_str(),
      cert.length(),
      sAuthCallback,
      reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<folly::Unit>>(rc);
  }
}

void BasicZookeeperClient::MultiOp::addCreateInternal(
    const std::string& path,
    folly::ByteRange data,
    CreateMode createMode,
    const ACL& acl) {
  paths_.push_back(path);
  opTypes_.push_back(MultiOpType::CREATE);
  ops_.emplace_back();
  cACLs_.emplace_back(acl);

  auto pathBufSize = path.length() + 12;
  pathBufs_.emplace_back(new char[pathBufSize]);
  pathBufs_.back()[0] = '\0';

  zoo_create_op_init(
      &ops_.back(),
      paths_.back().c_str(),
      const_cast<char*>(reinterpret_cast<const char*>(data.data())),
      data.size(),
      cACLs_.back().getCPtr(),
      convertCreateMode(createMode),
      pathBufs_.back().get(),
      pathBufSize);
}

IMultiOp& BasicZookeeperClient::MultiOp::addDelete(
    const std::string& path,
    int version) {
  paths_.push_back(path);
  opTypes_.push_back(MultiOpType::OTHER);
  ops_.emplace_back();

  zoo_delete_op_init(&ops_.back(), paths_.back().c_str(), version);

  return *this;
}

void BasicZookeeperClient::MultiOp::addSetDataInternal(
    const std::string& path,
    folly::ByteRange data,
    int version) {
  paths_.push_back(path);
  opTypes_.push_back(MultiOpType::SETDATA);
  ops_.emplace_back();
  stats_.emplace_front();

  zoo_set_op_init(
      &ops_.back(),
      paths_.back().c_str(),
      const_cast<char*>(reinterpret_cast<const char*>(data.data())),
      data.size(),
      version,
      &stats_.front());
}

IMultiOp& BasicZookeeperClient::MultiOp::addCheck(
    const std::string& path,
    int version) {
  paths_.push_back(path);
  opTypes_.push_back(MultiOpType::OTHER);
  ops_.emplace_back();

  zoo_check_op_init(&ops_.back(), paths_.back().c_str(), version);

  return *this;
}

std::unique_ptr<IMultiOp> BasicZookeeperClient::newMultiOp() const {
  return std::make_unique<MultiOp>();
}

folly::Future<std::vector<OpResponse>> BasicZookeeperClient::multi(
    std::unique_ptr<IMultiOp>&& iMultiOp) {
  auto* multiOp = dynamic_cast<MultiOp*>(iMultiOp.get());
  auto* c = contextStorage_.add(std::make_unique<MultiContext>(*multiOp));
  auto f = c->getPromise().getFuture();

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  int rc = zoo_amulti(
      zh_,
      multiOp->getOps().size(),
      multiOp->getOps().data(),
      c->opResults.data(),
      sMultiCallback,
      reinterpret_cast<void*>(c));
  zhg.unlock();

  if (rc == ZOK) {
    return f;
  } else {
    contextStorage_.erase(c);
    return processSynchronousErrorCodes<folly::Future<std::vector<OpResponse>>>(
        rc);
  }
}

folly::Optional<folly::SocketAddress> BasicZookeeperClient::getConnectedHost()
    const {
  struct sockaddr_storage addr;
  socklen_t addrLen = sizeof(addr);

  folly::SharedMutex::ReadHolder zhg(zhLock_);
  if (zh_ == nullptr ||
      zookeeper_get_connected_host(zh_, (sockaddr*)&addr, &addrLen) ==
          nullptr) {
    return folly::none;
  }
  zhg.unlock();

  folly::SocketAddress rval;
  try {
    rval.setFromSockaddr((sockaddr*)&addr, addrLen);
    return rval;
  } catch (const std::invalid_argument&) {
    LOG(ERROR) << "couldn't parse returned sockaddr; returning folly::none";
    return folly::none;
  }
}
}
}
}
}
