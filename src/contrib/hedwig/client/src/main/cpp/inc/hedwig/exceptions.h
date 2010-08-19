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
#ifndef HEDWIG_EXCEPTION_H
#define HEDWIG_EXCEPTION_H

#include <exception>

namespace Hedwig {

  class ClientException : public std::exception { };

  class ServiceDownException : public ClientException {};
  class CannotConnectException : public ClientException {};
  class UnexpectedResponseException : public ClientException {};
  class OomException : public ClientException {};
  class UnknownRequestException : public ClientException {};
  class InvalidRedirectException : public ClientException {};

  class PublisherException : public ClientException { };
  

  class SubscriberException : public ClientException { };
  class AlreadySubscribedException : public SubscriberException {};
  class NotSubscribedException : public SubscriberException {};

  class ConfigurationException : public ClientException { };
  class InvalidPortException : public ConfigurationException {};
  class HostResolutionException : public ClientException {};
  
  class InvalidStateException : public ClientException {};
  class ShuttingDownException : public InvalidStateException {};
};

#endif
