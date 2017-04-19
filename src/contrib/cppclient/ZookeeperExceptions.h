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

#include <folly/Format.h>
#include <folly/Try.h>
#include <exception>
#include <string>

namespace folly {
class IOBuf;
}

namespace facebook {
namespace zeus {
namespace client {

class ZookeeperException : public std::runtime_error {
 public:
  virtual int getReturnCode() const = 0;
  std::string getReturnCodeDescription() const;

 protected:
  explicit ZookeeperException(const std::string& what)
      : std::runtime_error(what) {}
  virtual ~ZookeeperException() {}
};

/**
 * Exception thrown when an return code the client library didn't expect was
 * returned by the server.  This exception being thrown represents a bug in the
 * server, client library, or both.
 */
class ZookeeperUnexpectedException : public ZookeeperException {
 public:
  explicit ZookeeperUnexpectedException(int rc);
  virtual ~ZookeeperUnexpectedException() = default;

  virtual int getReturnCode() const override;

 private:
  int rc_;
};

class ZookeeperSystemException : public ZookeeperException {
 protected:
  explicit ZookeeperSystemException(const std::string& what)
      : ZookeeperException(what) {}
  virtual ~ZookeeperSystemException() {}
};

class ZookeeperNetworkException : public ZookeeperSystemException {
 protected:
  explicit ZookeeperNetworkException(const std::string& what)
      : ZookeeperSystemException(what) {}

 public:
  virtual ~ZookeeperNetworkException() {}
};

class ZookeeperClientException : public ZookeeperException {
 protected:
  explicit ZookeeperClientException(const std::string& what)
      : ZookeeperException(what) {}
  virtual ~ZookeeperClientException() {}
};

/**
 * Exceptions derived from this require the client to be closed and reopened.
 */
class ZookeeperClientClosingException : public ZookeeperClientException {
 protected:
  explicit ZookeeperClientClosingException(const std::string& what)
      : ZookeeperClientException(what) {}
  virtual ~ZookeeperClientClosingException() {}
};

class ZookeeperPathException : public ZookeeperClientException {
 protected:
  ZookeeperPathException(folly::StringPiece&& fmt, std::string&& path)
      : ZookeeperClientException(folly::sformat(std::move(fmt), path)),
        path_(std::move(path)) {}
  virtual ~ZookeeperPathException() {}

 public:
  const std::string& getPath() const {
    return path_;
  }

 private:
  std::string path_;
};

class ZookeeperSystemErrorException : public ZookeeperSystemException {
 public:
  ZookeeperSystemErrorException()
      : ZookeeperSystemException(std::strerror(errno)) {}
  virtual ~ZookeeperSystemErrorException() = default;

  virtual int getReturnCode() const override;
};

class ZookeeperRuntimeInconsistencyException : public ZookeeperSystemException {
 public:
  ZookeeperRuntimeInconsistencyException()
      : ZookeeperSystemException("runtime inconsistency found") {}

  virtual int getReturnCode() const override;
};

class ZookeeperDataInconsistencyException : public ZookeeperSystemException {
 public:
  ZookeeperDataInconsistencyException()
      : ZookeeperSystemException("data inconsistency found") {}

  virtual int getReturnCode() const override;
};

class ZookeeperConnectionLossException : public ZookeeperNetworkException {
 public:
  ZookeeperConnectionLossException()
      : ZookeeperNetworkException("connection lost") {}

  virtual int getReturnCode() const override;
};

class ZookeeperMarshallingException : public ZookeeperSystemException {
 public:
  ZookeeperMarshallingException()
      : ZookeeperSystemException("marshalling error") {}

  virtual int getReturnCode() const override;
};

class ZookeeperUnimplementedException : public ZookeeperSystemException {
 public:
  ZookeeperUnimplementedException()
      : ZookeeperSystemException("unimplemented operation") {}

  virtual int getReturnCode() const override;
};

class ZookeeperTimeoutException : public ZookeeperNetworkException {
 public:
  ZookeeperTimeoutException()
      : ZookeeperNetworkException("operation timed out") {}

  virtual int getReturnCode() const override;
};

class ZookeeperBadArgumentsException : public ZookeeperSystemException {
 public:
  ZookeeperBadArgumentsException()
      : ZookeeperSystemException("bad arguments") {}

  virtual int getReturnCode() const override;
};

class ZookeeperInvalidStateException : public ZookeeperSystemException {
 public:
  ZookeeperInvalidStateException()
      : ZookeeperSystemException("invalid client state") {}

  virtual int getReturnCode() const override;
};

class ZookeeperNoNodeException : public ZookeeperPathException {
 public:
  explicit ZookeeperNoNodeException(std::string&& path)
      : ZookeeperPathException("no node found at path {}", std::move(path)) {}

  virtual int getReturnCode() const override;
};

class ZookeeperNoAuthException : public ZookeeperClientException {
 public:
  ZookeeperNoAuthException() : ZookeeperClientException("not authenticated") {}

  virtual int getReturnCode() const override;
};

class ZookeeperBadVersionException : public ZookeeperPathException {
 public:
  explicit ZookeeperBadVersionException(std::string&& path)
      : ZookeeperPathException("version conflict at node {}", std::move(path)) {
  }

  virtual int getReturnCode() const override;
};

class ZookeeperNoChildrenForEphemeralsException
    : public ZookeeperPathException {
 public:
  explicit ZookeeperNoChildrenForEphemeralsException(std::string&& path)
      : ZookeeperPathException(
            "ephemeral node {} cannot have children",
            std::move(path)) {}

  virtual int getReturnCode() const override;
};

class ZookeeperNodeExistsException : public ZookeeperPathException {
 public:
  explicit ZookeeperNodeExistsException(std::string&& path)
      : ZookeeperPathException("node {} already exists", std::move(path)) {}

  virtual int getReturnCode() const override;
};

class ZookeeperNotEmptyException : public ZookeeperPathException {
 public:
  explicit ZookeeperNotEmptyException(std::string&& path)
      : ZookeeperPathException("node {} has children", std::move(path)) {}

  virtual int getReturnCode() const override;
};

class ZookeeperSessionExpiredException
    : public ZookeeperClientClosingException {
 public:
  ZookeeperSessionExpiredException()
      : ZookeeperClientClosingException("session expired") {}

  virtual int getReturnCode() const override;
};

class ZookeeperInvalidCallbackException : public ZookeeperClientException {
 public:
  ZookeeperInvalidCallbackException()
      : ZookeeperClientException("invalid callback specified") {}

  virtual int getReturnCode() const override;
};

class ZookeeperInvalidACLException : public ZookeeperPathException {
 public:
  explicit ZookeeperInvalidACLException(std::string&& path)
      : ZookeeperPathException(
            "invalid ACL specified for {}",
            std::move(path)) {}

  virtual int getReturnCode() const override;
};

class ZookeeperAuthFailedException : public ZookeeperClientClosingException {
 public:
  ZookeeperAuthFailedException()
      : ZookeeperClientClosingException("authentication failed") {}

  virtual int getReturnCode() const override;
};

class ZookeeperClosingException : public ZookeeperClientException {
 public:
  ZookeeperClosingException()
      : ZookeeperClientException("Zookeeper is closing") {}

  virtual int getReturnCode() const override;
};

class ZookeeperNothingException : public ZookeeperClientException {
 public:
  ZookeeperNothingException()
      : ZookeeperClientException("no server responses to process") {}

  virtual int getReturnCode() const override;
};

class ZookeeperSessionMovedException : public ZookeeperClientException {
 public:
  ZookeeperSessionMovedException()
      : ZookeeperClientException(
            "session moved to another server, so operation is ignored") {}

  virtual int getReturnCode() const override;
};

struct Stat;

struct OpResponse {
  std::unique_ptr<std::string> path;
  std::unique_ptr<folly::IOBuf> data;
  std::unique_ptr<Stat> stat;
};

class ZookeeperMultiOpException : public ZookeeperClientException {
 public:
  ZookeeperMultiOpException(
      int rc,
      std::vector<folly::Try<OpResponse>>&& responses);

  const std::vector<folly::Try<OpResponse>>& getResponses() const {
    return *responses_;
  }

  virtual int getReturnCode() const override;

 private:
  int rc_;
  std::shared_ptr<std::vector<folly::Try<OpResponse>>> responses_;
};
}
}
}
