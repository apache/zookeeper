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

#pragma once

#include <zookeeper/zookeeper.h>
#include <forward_list>
#include <mutex>
#include <unordered_map>
#include "zeus/client/ZookeeperClient.h"
#include "zeus/client/detail/InitialWatches.h"
#include "zeus/client/detail/SimpleSessionEventWatcher.h"

namespace facebook {
namespace zeus {
namespace client {
namespace detail {

/**
 * Implementation of IZookeeperClient which is a thin wrapper on top of the
 * open source C client.  It is unsupported for clients to construct an
 * instance of this class directly.  Please use one of the factories defined
 * in fbcode/zeus/client instead.
 */
class BasicZookeeperClient : public virtual IZookeeperClient,
                             public SimpleSessionEventWatcher {
 public:
  BasicZookeeperClient(
      const std::string& connectionString,
      std::chrono::milliseconds sessionTimeout,
      const SessionToken* token = nullptr,
      InitialWatches&& = InitialWatches());
  BasicZookeeperClient(
      const std::vector<folly::SocketAddress>& servers,
      const std::string& chroot,
      std::chrono::milliseconds sessionTimeout,
      const SessionToken* token = nullptr,
      InitialWatches&& = InitialWatches());
  virtual ~BasicZookeeperClient();

  virtual SessionState getState() const override;

  virtual int64_t getSessionID() const override;

  virtual SessionToken getSessionToken() const override;

  virtual std::chrono::milliseconds getSessionTimeout() const override;

  virtual folly::Optional<folly::SocketAddress> getConnectedHost()
      const override;

  virtual void setServers(const std::vector<folly::SocketAddress>&) override;

  virtual void close() override;

  virtual folly::Future<GetDataResult> getData(
      const std::string& path) override;
  virtual DataWithWatch getDataWithWatch(const std::string& path) override;

 protected:
  virtual folly::Future<Stat> setDataInternal(
      const std::string& path,
      folly::ByteRange data,
      int version) override;

  virtual folly::Future<CreateResult> createNodeInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl) override;

 public:
  virtual folly::Future<folly::Unit> deleteNode(
      const std::string& path,
      int version = -1) override;

  virtual folly::Future<GetChildrenResult> getChildren(
      const std::string& path) override;
  virtual ChildrenWithWatch getChildrenWithWatch(
      const std::string& path) override;

  virtual folly::Future<folly::Optional<Stat>> exists(
      const std::string& path) override;
  virtual StatWithWatch existsWithWatch(const std::string& path) override;

  virtual folly::Future<int64_t> getSubtreeSize(
      const std::string& path) override;

  virtual std::unique_ptr<IMultiOp> newMultiOp() const override;

  virtual folly::Future<std::vector<OpResponse>> multi(
      std::unique_ptr<IMultiOp>&&) override;

  virtual folly::Future<GetAclResult> getAcl(const std::string& path) override;

  virtual folly::Future<folly::Unit>
  setAcl(const std::string& path, const ACL& acl, int version = -1) override;

  virtual folly::Future<folly::Unit> addAuth(
      const std::string& scheme,
      const std::string& cert) override;

 private:
  class ContextBase {
   public:
    virtual ~ContextBase() = default;

    void setIterator(
        const std::list<std::unique_ptr<ContextBase>>::iterator& i) {
      i_ = i;
    }

    std::list<std::unique_ptr<ContextBase>>::iterator getIterator() const {
      return i_;
    }

  private:
    std::list<std::unique_ptr<ContextBase>>::iterator i_;
  };

  // Storage for context objects provided to async and watch callbacks in the
  // C client.  The purpose of this storage is to enable memory allocated for
  // such contexts to be freed when the client is destroyed, even if not
  // every callback was called by the C client.  The extract() method should
  // be called in callbacks from the C client to free memory and avoid too
  // much buildup for the happy case.
  class ContextStorage {
   public:
    template <class Context>
    Context* add(std::unique_ptr<Context>&& c) {
      std::unique_lock<std::mutex> g(lock_);
      auto* rawC = c.get();
      storage_.push_back(std::move(c));
      auto i = storage_.end();
      --i;
      g.unlock();
      rawC->setIterator(i);
      rawC->setStorage(this);
      return rawC;
    }

    // Remove context from this storage.
    template <class Context>
    void erase(Context* c) {
      std::unique_lock<std::mutex> g(lock_);
      storage_.erase(c->getIterator());
    }

    void closeAll() {
      std::unique_lock<std::mutex> g(lock_);
      for (auto& c : storage_) {
        c.reset();
      }
      storage_.clear();
    }

    // Interpret data as a Context, look up storage, erase the Context from that
    // storage, and return the context.
    template <class Context>
    static std::unique_ptr<Context> extract(const void* data) {
      auto* c = reinterpret_cast<Context*>(const_cast<void*>(data));
      auto* s = c->getStorage();
      auto i = c->getIterator();
      std::unique_lock<std::mutex> g(s->lock_);
      auto* p = dynamic_cast<Context*>(i->get());
      if (p) {
        std::unique_ptr<Context> rval(p);
        i->release();
        s->storage_.erase(i);
        rval->setStorage(nullptr);
        return rval;
      } else {
        throw std::logic_error("dynamic_cast failed");
      }
    }

   private:
    std::mutex lock_;
    std::list<std::unique_ptr<ContextBase>> storage_;
  };

  // Context object to be used for C client callbacks, which are all into static
  // methods.  Template instantiations specify T, the return type expected from
  // the async method using the callback context.
  template <class T>
  class Context : public ContextBase {
   public:
    ~Context() {
      if (!p_.isFulfilled()) {
        p_.setException(ZookeeperClosingException());
      }
    }

    folly::Promise<T>& getPromise() {
      return p_;
    }

    void setStorage(ContextStorage* storage) {
      storage_ = storage;
    }

    ContextStorage* getStorage() {
      return storage_;
    }

   private:
    folly::Promise<T> p_;
    ContextStorage* storage_;
  };

  class WatchContext : public ContextBase {
   public:
    WatchContext()
        : sessionEventWatcher_(std::make_shared<SimpleSessionEventWatcher>()) {}

    ~WatchContext() {
      if (!p_.isFulfilled()) {
        auto state = sessionEventWatcher_->getNextIndexAndCurrentState();
        p_.setValue(
            NodeEvent{state.first, "", WatchEventType::CLOSING, state.second});
      }
    }

    folly::Promise<NodeEvent>& getPromise() {
      return p_;
    }

    void setStorage(ContextStorage* storage) {
      storage_ = storage;
    }

    ContextStorage* getStorage() {
      return storage_;
    }

    std::shared_ptr<SimpleSessionEventWatcher> getSessionEventWatcher() const {
      return sessionEventWatcher_;
    }

   private:
    std::shared_ptr<SimpleSessionEventWatcher> sessionEventWatcher_;
    folly::Promise<NodeEvent> p_;
    ContextStorage* storage_;
  };

  class GetContext : public Context<GetDataResult> {
   public:
    std::string path;

    explicit GetContext(const std::string& inPath) : path(inPath) {}
  };

  class StatContext : public Context<Stat> {
   public:
    std::string path;

    explicit StatContext(const std::string& inPath) : path(inPath) {}
  };

  class OptionalStatContext : public Context<folly::Optional<Stat>> {
   public:
    std::string path;

    explicit OptionalStatContext(const std::string& inPath) : path(inPath) {}
  };

  class CreateContext : public Context<CreateResult> {
   public:
    std::string path;

    explicit CreateContext(const std::string& inPath) : path(inPath) {}
  };

  class VoidContext : public Context<folly::Unit> {
   public:
    std::string path;

    explicit VoidContext(const std::string& inPath) : path(inPath) {}
  };

  class GetChildrenContext : public Context<GetChildrenResult> {
   public:
    std::string path;

    explicit GetChildrenContext(const std::string& inPath) : path(inPath) {}
  };

  class GetAclContext : public Context<GetAclResult> {
   public:
    std::string path;

    explicit GetAclContext(const std::string& inPath) : path(inPath) {}
  };

  class AuthContext : public Context<folly::Unit> {};

  class SizeContext : public Context<int64_t> {
   public:
    std::string path;

    explicit SizeContext(const std::string& inPath) : path(inPath) {}
  };

  // RAII holder for C client's ACL format
  class CACL {
   public:
    explicit CACL(const ACL&);

    const ::ACL_vector* getCPtr() const {
      return aclVector_ ? aclVector_.get() : &ZOO_OPEN_ACL_UNSAFE;
    }

   private:
    std::unique_ptr<::ACL_vector> aclVector_;
    std::unique_ptr<::ACL[]> acls_;
  };

  enum class MultiOpType { CREATE, SETDATA, OTHER };

  // Implementation of IMultiOp that maps to the C client's multiop format.
  class MultiOp : public IMultiOp {
   public:
    MultiOp() = default;
    virtual ~MultiOp() = default;

    virtual IMultiOp& addDelete(const std::string& path, int version = -1)
        override;

    virtual IMultiOp& addCheck(const std::string& path, int version = -1)
        override;

    const std::vector<::zoo_op_t>& getOps() const {
      return ops_;
    }

    const std::list<std::string>& getPaths() const {
      return paths_;
    }

    const std::vector<MultiOpType> getOpTypes() const {
      return opTypes_;
    }

    std::vector<std::unique_ptr<char[]>>& getPathBufs() {
      return pathBufs_;
    }

    std::forward_list<::Stat>& getStats() {
      return stats_;
    }

   protected:
    virtual void addCreateInternal(
        const std::string& path,
        folly::ByteRange data,
        CreateMode,
        const ACL&) override;

    virtual void addSetDataInternal(
        const std::string& path,
        folly::ByteRange data,
        int version) override;

   private:
    // each zoo_*_op_init merely fills the contents of the ::zoo_op_t, so it's
    // ok for references to move
    std::vector<::zoo_op_t> ops_;
    // std::list to make sure references remain valid
    std::list<std::string> paths_;
    std::vector<MultiOpType> opTypes_;
    std::vector<std::unique_ptr<char[]>> pathBufs_;
    // CACL uses std::unique_ptr internally
    std::vector<CACL> cACLs_;
    // std::forward_list to make sure references remain valid
    std::forward_list<::Stat> stats_;
  };

  class MultiContext : public Context<std::vector<OpResponse>> {
   public:
    std::list<std::string> paths;
    std::vector<MultiOpType> opTypes;
    std::vector<std::unique_ptr<char[]>> pathBufs;
    std::forward_list<::Stat> stats;
    std::vector<::zoo_op_result_t> opResults;

    explicit MultiContext(MultiOp& multiOp)
        : paths(multiOp.getPaths()),
          opTypes(multiOp.getOpTypes()),
          pathBufs(std::move(multiOp.getPathBufs())),
          stats(std::move(multiOp.getStats())),
          opResults(paths.size()) {}
  };

  template <class T>
  static void sCallbackException(folly::Promise<T>& p, int rc);

  static Stat convertStat(const ::Stat& s);

  static SessionState convertStateType(int state);

  static NodeEvent convertWatchEventType(
      const char* path,
      int type,
      int state,
      size_t index = 0);

  static int convertCreateMode(const CreateMode&);

  static void sGetCallback(
      int rc,
      const char* value,
      int value_len,
      const ::Stat* stat,
      const void* data);

  static void sSessionWatchCallback(
      zhandle_t*,
      int type,
      int state,
      const char* path,
      void* watcherCtx);

  static void sWatchCallback(
      zhandle_t*,
      int type,
      int state,
      const char* path,
      void* watcherCtx);

  static void sStatCallback(int rc, const ::Stat* stat, const void* data);

  static void
  sOptionalStatCallback(int rc, const ::Stat* stat, const void* data);

  static void sCreateCallback(int rc, const char* value, const void* data);

  static void sVoidCallback(int rc, const void* data);

  static void sSizeCallback(int rc, const int64_t* size, const void* data);

  static void sAuthCallback(int rc, const void* data);

  static void sGetChildrenCallback(
      int rc,
      const ::String_vector* strings,
      const ::Stat* stat,
      const void* data);

  static void
  sGetAclCallback(int rc, ::ACL_vector* acl, ::Stat* stat, const void* data);

  static void sMultiCallback(int rc, const void* data);

  static OpResponse buildOpResponse(const ::zoo_op_result_t&, MultiOpType);

  static std::string buildConnectionString(
      const std::vector<folly::SocketAddress>&,
      const std::string& chroot = "");

  folly::SharedMutex zhLock_;
  mutable zhandle_t* zh_;

  std::mutex initialWatchesLock_;
  InitialWatches initialWatches_;

  ContextStorage contextStorage_;
};
}
}
}
}
