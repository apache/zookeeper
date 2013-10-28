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

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.log4j.Logger;

/**
 * This class provides a unified interface for registering/unregistering of
 * zookeeper MBeans with the platform MBean server. It builds a hierarchy of MBeans
 * where each MBean represented by a filesystem-like path. Eventually, this hierarchy
 * will be stored in the zookeeper data tree instance as a virtual data tree.
 */
public class MBeanRegistry {
    private static final Logger LOG = Logger.getLogger(MBeanRegistry.class);
    
    private static MBeanRegistry instance=new MBeanRegistry(); 
    
    private Map<ZKMBeanInfo, String> mapBean2Path =
        new ConcurrentHashMap<ZKMBeanInfo, String>();
    
    private Map<String, ZKMBeanInfo> mapName2Bean =
        new ConcurrentHashMap<String, ZKMBeanInfo>();

    public static MBeanRegistry getInstance(){
        return instance;
    }
    
    /**
     * Registers a new MBean with the platform MBean server. 
     * @param bean the bean being registered
     * @param parent if not null, the new bean will be registered as a child
     * node of this parent.
     */
    public void register(ZKMBeanInfo bean, ZKMBeanInfo parent)
        throws JMException
    {
        assert bean != null;
        String path = null;
        if (parent != null) {
            path = mapBean2Path.get(parent);
            assert path != null;
        }
        path = makeFullPath(path, parent);
        mapBean2Path.put(bean, path);
        mapName2Bean.put(bean.getName(), bean);
        if(bean.isHidden())
            return;
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName oname = makeObjectName(path, bean);
        try {
            mbs.registerMBean(bean, oname);
        } catch (JMException e) {
            LOG.warn("Failed to register MBean " + bean.getName());
            throw e;
        }
    }

    /**
     * Unregister the MBean identified by the path.
     * @param path
     * @param bean
     */
    private void unregister(String path,ZKMBeanInfo bean) throws JMException {
        if(path==null)
            return;
        if (!bean.isHidden()) {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            try {
                mbs.unregisterMBean(makeObjectName(path, bean));
            } catch (JMException e) {
                LOG.warn("Failed to unregister MBean " + bean.getName());
                throw e;
            }
        }        
    }
    
    /**
     * Unregister MBean.
     * @param bean
     */
    public void unregister(ZKMBeanInfo bean) {
        if(bean==null)
            return;
        String path=mapBean2Path.get(bean);
        try {
            unregister(path,bean);
        } catch (JMException e) {
            LOG.warn("Error during unregister", e);
        }
        mapBean2Path.remove(bean);
        mapName2Bean.remove(bean.getName());
    }
    /**
     * Unregister all currently registered MBeans
     */
    public void unregisterAll() {
        for(Map.Entry<ZKMBeanInfo,String> e: mapBean2Path.entrySet()) {
            try {
                unregister(e.getValue(), e.getKey());
            } catch (JMException e1) {
                LOG.warn("Error during unregister", e1);
            }
        }
        mapBean2Path.clear();
        mapName2Bean.clear();
    }
    /**
     * Generate a filesystem-like path.
     * @param prefix path prefix
     * @param name path elements
     * @return absolute path
     */
    public String makeFullPath(String prefix, String... name) {
        StringBuilder sb=new StringBuilder(prefix == null ? "/" : (prefix.equals("/")?prefix:prefix+"/"));
        boolean first=true;
        for (String s : name) {
            if(s==null) continue;
            if(!first){
                sb.append("/");
            }else
                first=false;
            sb.append(s);
        }
        return sb.toString();
    }
    
    protected String makeFullPath(String prefix, ZKMBeanInfo bean) {
        return makeFullPath(prefix, bean == null ? null : bean.getName());
    }

    /**
     * This takes a path, such as /a/b/c, and converts it to 
     * name0=a,name1=b,name2=c
     */
    private int tokenize(StringBuilder sb, String path, int index){
        String[] tokens = path.split("/");
        for (String s: tokens) {
            if (s.length()==0)
                continue;
            sb.append("name").append(index++)
                    .append("=").append(s).append(",");
        }
        return index;
    }
    /**
     * Builds an MBean path and creates an ObjectName instance using the path. 
     * @param path MBean path
     * @param bean the MBean instance
     * @return ObjectName to be registered with the platform MBean server
     */
    protected ObjectName makeObjectName(String path, ZKMBeanInfo bean)
        throws MalformedObjectNameException
    {
        if(path==null)
            return null;
        StringBuilder beanName = new StringBuilder(CommonNames.DOMAIN + ":");
        int counter=0;
        counter=tokenize(beanName,path,counter);
        tokenize(beanName,bean.getName(),counter);
        beanName.deleteCharAt(beanName.length()-1);
        try {
            return new ObjectName(beanName.toString());
        } catch (MalformedObjectNameException e) {
            LOG.warn("Invalid name \"" + beanName.toString() + "\" for class "
                    + bean.getClass().toString());
            throw e;
        }
    }
}
