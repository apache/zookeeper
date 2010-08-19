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
#ifndef HEDWIG_PUBLISH_H
#define HEDWIG_PUBLISH_H

#include <string>

#include <hedwig/exceptions.h>
#include <hedwig/callback.h>
#include <hedwig/protocol.h>
#include <boost/noncopyable.hpp>

namespace Hedwig {

  /**
     Interface for publishing to a hedwig instance.
  */
  class Publisher : private boost::noncopyable {
  public:
    /**
       Publish message for topic, and block until we receive a ACK response from the hedwig server.
       
       @param topic Topic to publish to.
       @param message Data to publish for topic.
    */
    virtual void publish(const std::string& topic, const std::string& message) = 0;
    
    /** 
	Asynchronously publish message for topic. 
	
	@code
	OperationCallbackPtr callback(new MyCallback());
	pub.asyncPublish(callback);
	@endcode

	@param topic Topic to publish to.
	@param message Data to publish to topic
	@param callback Callback which will be used to report success or failure. Success is only reported once the server replies with an ACK response to the publication.
    */
    virtual void asyncPublish(const std::string& topic, const std::string& message, const OperationCallbackPtr& callback) = 0;
  };
};

#endif
