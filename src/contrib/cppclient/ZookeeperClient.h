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

#include <folly/Function.h>
#include <folly/Optional.h>
#include <folly/SharedMutex.h>
#include <folly/SocketAddress.h>
#include <folly/futures/Future.h>
#include <folly/io/IOBuf.h>
#include <array>
#include <chrono>
#include <forward_list>
#include <string>
#include "zeus/client/ZookeeperExceptions.h"

namespace facebook {
namespace zeus {
namespace client {

/**
 * Parameters for node creation in Zookeeper.  Nodes can be created to be
 * ephemeral, which means that they will be automatically deleted when the
 * creator's session expires, or sequential, which means that the server will
 * append a unique, sequential index to the end of the node name on creation.
 * Nodes can also be created as both ephemeral and sequential, or neither.
 */
class CreateMode {
 public:
  explicit CreateMode(bool _isEphemeral = false, bool _isSequential = false)
      : isEphemeral(_isEphemeral), isSequential(_isSequential) {}

  bool isEphemeral : 1;
  bool isSequential : 1;

  inline static CreateMode ephemeral() {
    return CreateMode(true);
  }

  inline static CreateMode sequential() {
    return CreateMode(false, true);
  }

  inline static CreateMode ephemeralSequential() {
    return CreateMode(true, true);
  }
};

/**
 * Structure that can be used to reconnect to an ensemble and preserve the
 * session and its ephemeral nodes.
 */
struct SessionToken {
  int64_t sessionId;
  std::array<char, 16> passwd;
};

struct Id {
  std::string scheme;
  std::string id;
};

struct ACLElement {
  int32_t perms;
  Id id;
};

typedef std::vector<ACLElement> ACL;

/**
 * Metadata for a znode.  Several fields reference the idea of a zxid, which
 * is a monotonically increasing whole-database version ID maintained on the
 * server.
 */
struct Stat {
  /**
   * zxid in which the node was created
   */
  int64_t czxid;
  /**
   * zxid in which the node was last modified
   */
  int64_t mzxid;
  /**
   * timestamp of the node's creation
   */
  std::chrono::system_clock::time_point ctime;
  /**
   * timestamp of the node's last modification
   */
  std::chrono::system_clock::time_point mtime;
  /**
   * number of changes to the contents of the node since creation
   */
  int32_t version;
  /**
   * number of changes to the children the node since node creation
   */
  int32_t cversion;
  /**
   * number of changes to node ACL since node creation
   */
  int32_t aversion;
  /**
   * If this node is ephemeral, the session ID that created it.  If the node
   * is not ephemeral, this will be zero.
   */
  int64_t ephemeralOwner;
  /**
   * size of node contents
   */
  int32_t dataLength;
  /**
   * number of child nodes
   */
  int32_t numChildren;
  /**
   * zxid in which the node's children were last modified
   */
  int64_t pzxid;
};

/**
 * Possible states of a Zookeeper connection.
 */
enum class SessionState {
  /**
  * not yet connected; will transition to CONNECTING immediately after this
  */
  DISCONNECTED = 0,
  /**
   * currently attempting to connect to a server
   */
  CONNECTING = 1,
  ASSOCIATING = 2,
  /**
   * currently connected to a server in read-write mode
   */
  CONNECTED = 3,
  /**
   * currently connected to a server in read-only mode
   */
  READONLY = 5,
  /**
   * Session is expired.  The connection has been lost, and any ephemeral nodes
   * created by the session have been deleted.  Unless the client has
   * transparent reconnection, this is a terminal state.
   */
  EXPIRED = -112,
  /**
   * Auth credentials were rejected by the server.  This is a terminal state.
   */
  AUTH_FAILED = -113,
  /**
   * Client-side state indicating that time has expired for reconnecting.  An
   * EXPIRED event (which is issued by the server) will come later.
   */
  TIMED_OUT = 998,
};

enum class WatchEventType {
  /**
   * A node at the given path was created.
   */
  CREATED = 1,
  /**
   * A node at the given path was deleted.
   */
  DELETED = 2,
  /**
   * The contents of the node at the given path were changed.
   */
  CHANGED = 3,
  /**
   * The children of the node at the given path were changed.
   */
  CHILD = 4,
  /**
   * Change in session state; no node changes.
   */
  SESSION = -1,
  /**
   * An error occurred so that we are no longer able to watch the given node.
   */
  NOT_WATCHING = -2,
  /**
   * The connection is closing.
   */
  CLOSING,
};

/**
 * Event representing a change in session state.
 */
struct SessionEvent {
  size_t eventIndex;
  SessionState state;
};

/**
 * Event representing an operation done on some node (or NOT_WATCHING event
 * for a particular node).
 */
struct NodeEvent {
  size_t eventIndex;
  std::string path;
  WatchEventType type;
  SessionState state;
};

/**
 * Opaque identifier returned when adding a callback to an instance of
 * ISessionEventWatcher.  ISessionEventWatcher will return a
 * std::unique_ptr<IEventWatchCallbackIdentifier>, generally pointing to an
 * instance of some derived class.  This pointer should be handed back in
 * order to deregister the callback.  Destructors of derived classes should
 * not do anything to deregister the associated callback.
 */
class IEventWatchCallbackIdentifier {
 public:
  virtual ~IEventWatchCallbackIdentifier() = default;
};

/**
 * This class facilitates listening to Zookeeper session events.  All callbacks
 * should be implicitly deregistered in the destructor of derived classes.
 */
class ISessionEventWatcher {
 public:
  virtual ~ISessionEventWatcher() = default;

  /**
   * Registers a callback that will be called on session state changes.
   * The given callback will also be called with the most recent state
   * received from Zookeeper, if any.  The callback will no longer be called
   * after this watcher object is destroyed.  The return value is an opaque
   * identifier that can be used to deregister the callback prior to the
   * watcher's destruction by calling removeCallback().
   */
  virtual std::unique_ptr<IEventWatchCallbackIdentifier> addCallback(
      folly::Function<void(SessionEvent)>&&) = 0;

  /**
   * Deregister and return the associated callback.  There will no longer be
   * any calls made by this watcher to the associated callback once this method
   * returns.  This includes a guarantee that this method will not return until
   * any such calls that might have been in progress complete.
   */
  virtual folly::Function<void(SessionEvent)> removeCallback(
      std::unique_ptr<IEventWatchCallbackIdentifier>&&) = 0;

  /**
   * Deregister the associated callback without strict guarantees about there
   * being no remaining calls pending or in progress before returning.
   */
  virtual void removeCallbackSoon(
      std::unique_ptr<IEventWatchCallbackIdentifier>&&) = 0;

  /**
   * Returns a Future that will be completed when the session state
   * matches the one passed in.
   */
  // TOOD make it throw an exception when state can never be reached t14039600
  virtual folly::Future<SessionEvent> getEventForState(SessionState) = 0;
};

class IMultiOp {
 public:
  IMultiOp() : firstEphemeralCreatePath_(folly::none) {}
  virtual ~IMultiOp() = default;

  /**
   * Add a create node operation to this atomic transaction.
   */
  IMultiOp& addCreate(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode = CreateMode(),
      const ACL& acl = ACL()) {
    addCreateInternal(path, data, createMode, acl);
    if (createMode.isEphemeral && !firstEphemeralCreatePath_.hasValue()) {
      firstEphemeralCreatePath_ = path;
    }
    return *this;
  }

  /**
   * Take ownership of data and add a create node operation to this atomic
   * transaction.
   */
  IMultiOp& addCreate(
      const std::string& path,
      std::unique_ptr<folly::IOBuf>&& data,
      CreateMode createMode = CreateMode(),
      const ACL& acl = ACL()) {
    return addCreate(path, storeIOBuf(std::move(data)), createMode, acl);
  }

  /**
   * Take ownership of data and add a create node operation to this atomic
   * transaction.
   */
  IMultiOp& addCreate(
      const std::string& path,
      std::string&& data,
      CreateMode createMode = CreateMode(),
      const ACL& acl = ACL()) {
    return addCreate(path, storeString(std::move(data)), createMode, acl);
  }

  /**
   * Add a delete node operation to this atomic transaction.
   */
  virtual IMultiOp& addDelete(const std::string& path, int version = -1) = 0;

  /**
   * Add a set data operation to this atomic transaction.
   */
  IMultiOp&
  addSetData(const std::string& path, folly::ByteRange data, int version = -1) {
    addSetDataInternal(path, data, version);
    return *this;
  }

  /**
   * Take ownership of data and add a set data operation to this atomic
   * transaction.
   */
  IMultiOp& addSetData(
      const std::string& path,
      std::unique_ptr<folly::IOBuf>&& data,
      int version = -1) {
    return addSetData(path, storeIOBuf(std::move(data)), version);
  }

  /**
   * Take ownership of data and add a set data operation to this atomic
   * transaction.
   */
  IMultiOp&
  addSetData(const std::string& path, std::string&& data, int version = -1) {
    return addSetData(path, storeString(std::move(data)), version);
  }

  /**
   * Add a check that the given node exists with the specified version in order
   * for the atomic transaction to go through.  Setting version to -1 will only
   * check that the node exists, without any condition on its version.
   */
  virtual IMultiOp& addCheck(const std::string& path, int version = -1) = 0;

  /**
   * If there is an ephemeral create in this multiop, returns the path of the
   * first such create op.
   */
  const folly::Optional<std::string>& getFirstEphemeralCreatePath() const {
    return firstEphemeralCreatePath_;
  }

 protected:
  virtual void addCreateInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode,
      const ACL&) = 0;

  virtual void addSetDataInternal(
      const std::string& path,
      folly::ByteRange data,
      int version) = 0;

 private:
  folly::ByteRange storeIOBuf(std::unique_ptr<folly::IOBuf>&& buf) {
    auto rval = buf->coalesce();
    bufs_.push_back(std::move(buf));
    return rval;
  }

  folly::StringPiece storeString(std::string&& str) {
    stringBufs_.push_front(std::move(str));
    return folly::StringPiece(stringBufs_.front());
  }

  std::vector<std::unique_ptr<folly::IOBuf>> bufs_;
  // std::forward_list to make sure references remain valid
  std::forward_list<std::string> stringBufs_;
  folly::Optional<std::string> firstEphemeralCreatePath_;
};

struct GetDataResult {
  std::unique_ptr<folly::IOBuf> data;
  Stat stat;

  std::string str() {
    return folly::StringPiece(data->coalesce()).str();
  }
};

/**
 * The result returned when making a read call (getData, getChildren, or exists)
 * and setting a watch.  The provided sessionEventWatcher will start firing
 * session events after the completion of the response Future and until the
 * completion of the watch Future.  The watch Future will only complete after
 * response is completed, as soon as the results returned in response are out of
 * date.  The IZookeeperClient instance that generates this response will ensure
 * that the lifetime of sessionEventWatcher extends to the time of the
 * completion of dataWatch or the end of the IZookeeperClient instance's
 * lifetime, whichever comes first.
 */
template <class T>
struct WithWatch {
  folly::Future<T> response;
  std::shared_ptr<ISessionEventWatcher> sessionEventWatcher;
  folly::Future<NodeEvent> watch;
};

/**
 * The result returned when fetching a node's contents and setting a watch.
 * The provided sessionEventWatcher will start firing session events after the
 * completion of the response Future and until the completion of the watch
 * Future.  The watch Future will only complete after data is returned, as soon
 * as the contents returned in response are out of date because the node has
 * been written to.  The IZookeeperClient instance that generates this response
 * will ensure that the lifetime of sessionEventWatcher extends to the time of
 * the completion of dataWatch or the end of the IZookeeperClient instance's
 * lifetime, whichever comes first.
 */
using DataWithWatch = WithWatch<GetDataResult>;

struct CreateResult {
  std::string name;

  // will be filled in if supported by client's createNode implementation
  folly::Optional<Stat> stat;
};

struct GetChildrenResult {
  std::vector<std::string> children;
  Stat parentStat;
};

/**
 * The result returned when fetching a node's children and setting a watch.
 * The provided sessionEventWatcher will start firing session events after the
 * completion of the response Future and until the completion of the watch
 * Future.  The watch Future will only complete after the list of children is
 * returned, as soon as the list of children returned in response are out of
 * date because the node's children have been modified.  The IZookeeperClient
 * instance that generates this response will ensure that the lifetime of
 * sessionEventWatcher extends to the time of the completion of childWatch or
 * the end of the IZookeeperClient instance's lifetime, whichever comes first.
 */
using ChildrenWithWatch = WithWatch<GetChildrenResult>;

/**
 * The result returned when checking a node's existence and setting a watch.
 * The provided sessionEventWatcher will start firing session events after the
 * completion of the response Future and until the completion of the watch
 * Future.  The watch Future will only complete after response completes, as
 * soon as the returned stat is out of date because the node has been written
 * to.  The IZookeeperClient instance that generates this response will ensure
 * that the lifetime of sessionEventWatcher extends to the time of the
 * completion of dataWatch or the end of the IZookeeperClient instance's
 * lifetime, whichever comes first.
 */
using StatWithWatch = WithWatch<folly::Optional<Stat>>;

struct GetAclResult {
  Stat stat;
  ACL acl;
};

class UnrecognizedCallbackIdentifierException : std::runtime_error {
 public:
  UnrecognizedCallbackIdentifierException()
      : std::runtime_error("callback identifier was not recognized") {}
};

class IZookeeperClient : public virtual ISessionEventWatcher {
 public:
  virtual ~IZookeeperClient() {}

  /**
   * Returns a session-associated property that can be used for monitoring, etc.
   */
  virtual std::string getProperty(const std::string& key) const {
    return "";
  }

  virtual SessionState getState() const = 0;

  virtual SessionToken getSessionToken() const = 0;

  virtual int64_t getSessionID() const {
    return getSessionToken().sessionId;
  }

  virtual std::chrono::milliseconds getSessionTimeout() const = 0;

  virtual folly::Optional<folly::SocketAddress> getConnectedHost() const = 0;

  virtual void setServers(const std::vector<folly::SocketAddress>&) = 0;

  virtual void close() = 0;

  virtual folly::Future<GetDataResult> getData(const std::string& path) = 0;
  virtual DataWithWatch getDataWithWatch(const std::string& path) = 0;

  folly::Future<Stat>
  setData(const std::string& path, folly::ByteRange data, int version = -1) {
    return setDataInternal(path, data, version);
  }
  folly::Future<Stat>
  setData(const std::string& path, const std::string& data, int version = -1) {
    return setData(path, folly::StringPiece(data), version);
  }

  folly::Future<CreateResult> createNode(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode = CreateMode(),
      const ACL& acl = ACL()) {
    return createNodeInternal(path, data, createMode, acl);
  }
  folly::Future<CreateResult> createNode(
      const std::string& path,
      const std::string& data,
      CreateMode createMode = CreateMode(),
      const ACL& acl = ACL()) {
    return createNode(path, folly::StringPiece(data), createMode, acl);
  }

  virtual folly::Future<folly::Unit> deleteNode(
      const std::string& path,
      int version = -1) = 0;

  virtual folly::Future<GetChildrenResult> getChildren(
      const std::string& path) = 0;
  virtual ChildrenWithWatch getChildrenWithWatch(const std::string& path) = 0;

  /**
   * If the node at the given path exists, the returned folly::Future will
   * complete with that node's Stat.  If the node does not exist, it will
   * complete with folly::none.
   */
  virtual folly::Future<folly::Optional<Stat>> exists(
      const std::string& path) = 0;
  virtual StatWithWatch existsWithWatch(const std::string& path) = 0;

  virtual folly::Future<int64_t> getSubtreeSize(const std::string& path) = 0;

  virtual std::unique_ptr<IMultiOp> newMultiOp() const = 0;

  virtual folly::Future<std::vector<OpResponse>> multi(
      std::unique_ptr<IMultiOp>&&) = 0;

  virtual folly::Future<GetAclResult> getAcl(const std::string& path) = 0;

  virtual folly::Future<folly::Unit>
  setAcl(const std::string& path, const ACL& acl, int version = -1) = 0;

  virtual folly::Future<folly::Unit> addAuth(
      const std::string& scheme,
      const std::string& cert) = 0;

 protected:
  virtual folly::Future<Stat> setDataInternal(
      const std::string& path,
      folly::ByteRange data,
      int version) = 0;

  virtual folly::Future<CreateResult> createNodeInternal(
      const std::string& path,
      folly::ByteRange data,
      CreateMode createMode,
      const ACL& acl) = 0;
};

inline std::string toString(SessionState state) {
  switch (state) {
    case SessionState::DISCONNECTED:
      return "DISCONNECTED";
    case SessionState::CONNECTING:
      return "CONNECTING";
    case SessionState::ASSOCIATING:
      return "ASSOCIATING";
    case SessionState::CONNECTED:
      return "CONNECTED";
    case SessionState::READONLY:
      return "READONLY";
    case SessionState::EXPIRED:
      return "EXPIRED";
    case SessionState::AUTH_FAILED:
      return "AUTH_FAILED";
    case SessionState::TIMED_OUT:
      return "TIMED_OUT";
    default:
      return "[unrecognized session state]";
  }
}
}
}
}

namespace std {
template <>
struct hash<facebook::zeus::client::SessionState> {
  size_t operator()(const facebook::zeus::client::SessionState& state) const {
    return size_t(state);
  }
};
}
