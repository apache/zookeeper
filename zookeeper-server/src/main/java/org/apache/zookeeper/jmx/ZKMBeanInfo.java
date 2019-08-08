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

package org.apache.zookeeper.jmx;

/**
 * Zookeeper MBean info interface. MBeanRegistry uses the interface to generate
 * JMX object name.
 * Zookeeper MBean信息界面。 MBeanRegistry使用该接口生成 JMX对象名称。
 */
public interface ZKMBeanInfo {
    /**
     * @return a string identifying the MBean 
     */
    public String getName();
    /**
     * If isHidden returns true, the MBean won't be registered with MBean server,
     * and thus won't be available for management tools. Used for grouping MBeans.
     * 如果isHidden返回true，则MBean将不会向MBean服务器注册，因此将无法用于管理工具。
     * 用于对MBean进行分组。
     * @return true if the MBean is hidden.
     */
    public boolean isHidden();
}
