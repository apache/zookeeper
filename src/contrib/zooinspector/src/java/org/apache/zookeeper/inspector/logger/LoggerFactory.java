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
package org.apache.zookeeper.inspector.logger;

import org.apache.log4j.Logger;

/**
 * Provides a {@link Logger} for use across the entire application
 * 
 */
public class LoggerFactory
{
	private static final Logger logger = Logger.getLogger("org.apache.zookeeper.inspector"); //$NON-NLS-1$

	/**
	 * @return {@link Logger} for ZooInspector
	 */
	public static Logger getLogger()
	{
		return logger;
	}

}
