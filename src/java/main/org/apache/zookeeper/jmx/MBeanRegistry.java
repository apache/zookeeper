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
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides a unified interface for registering/unregistering of
 * zookeeper MBeans with the platform MBean server. It builds a hierarchy of MBeans
 * where each MBean represented by a filesystem-like path. Eventually, this hierarchy
 * will be stored in the zookeeper data tree instance as a virtual data tree.
 */
public class MBeanRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(MBeanRegistry.class);
    
    private static volatile MBeanRegistry instance = new MBeanRegistry();
    
    private final Object LOCK = new Object();
    
    private Map<ZKMBeanInfo, String> mapBean2Path =
        new ConcurrentHashMap<ZKMBeanInfo, String>();
    
    private MBeanServer mBeanServer;

    /**
     * Useful for unit tests. Change the MBeanRegistry instance
     *
     * @param instance new instance
     */
    public static void setInstance(MBeanRegistry instance) {
        MBeanRegistry.instance = instance;
    }

    public static MBeanRegistry getInstance() {
        return instance;
    }

    public MBeanRegistry () {
        try {
            mBeanServer = ManagementFactory.getPlatformMBeanServer();        
        } catch (Error e) {
            // Account for running within IKVM and create a new MBeanServer
            // if the PlatformMBeanServer does not exist.
            mBeanServer =  MBeanServerFactory.createMBeanServer();
        }
    }

    /**
     * Return the underlying MBeanServer that is being
     * used to register MBean's. The returned MBeanServer
     * may be a new empty MBeanServer if running through IKVM.
     */
    public MBeanServer getPlatformMBeanServer() {
        return mBeanServer;
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
        if(bean.isHidden())
            return;
        ObjectName oname = makeObjectName(path, bean);
        try {
            synchronized (LOCK) {
                mBeanServer.registerMBean(bean, oname);
                mapBean2Path.put(bean, path);
            }
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
    private void unregister(String path,ZKMBeanInfo bean) throws JMException  {
        if(path==null)
            return;
        if (!bean.isHidden()) {
            final ObjectName objName = makeObjectName(path, bean);
            LOG.debug("Unregister MBean [{}]", objName);
            synchronized (LOCK) {
               mBeanServer.unregisterMBean(objName);
            }
        }        
    }
    
    /**
     * @return a {@link Collection} with the {@link ZKMBeanInfo} instances not
     *         unregistered. Mainly for testing purposes.
     */
    public Set<ZKMBeanInfo> getRegisteredBeans() {
        return new HashSet<ZKMBeanInfo>(mapBean2Path.keySet());
    }

    /**
     * Unregister MBean.
     * @param bean
     */
    public void unregister(ZKMBeanInfo bean) {
        if(bean==null)
            return;
        String path = mapBean2Path.remove(bean);
        try {
            unregister(path,bean);
        } catch (JMException e) {
            LOG.warn("Error during unregister of [{}]", bean.getName(), e);
        } catch (Throwable t) {
            LOG.error("Unexpected exception during unregister of [{}]. It should be reviewed and fixed.", bean.getName(), t);
        }
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
