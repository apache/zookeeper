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

package org.apache.zookeeper.server.jersey;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.server.jersey.cfg.Endpoint;

/**
 * Singleton which provides JAX-RS resources access to the ZooKeeper client.
 * There's a single session for each base uri (so usually just one).
 */
public class ZooKeeperService {

   private static Logger LOG = LoggerFactory.getLogger(ZooKeeperService.class);

   /** Map base uri to ZooKeeper host:port parameters */
   private static Map<String, Endpoint> contextMap = new HashMap<String, Endpoint>();

   /** Map base uri to ZooKeeper session */
   private static Map<String, ZooKeeper> zkMap = new HashMap<String, ZooKeeper>();

   /** Session timers */
   private static Map<String, SessionTimerTask> zkSessionTimers = new HashMap<String, SessionTimerTask>();
   private static Timer timer = new Timer();

   /** Track the status of the ZooKeeper session */
   private static class MyWatcher implements Watcher {
       final String contextPath;

       /** Separate watcher for each base uri */
       public MyWatcher(String contextPath) {
           this.contextPath = contextPath;
       }

       /**
        * Track state - in particular watch for expiration. if it happens for
        * re-creation of the ZK client session
        */
       synchronized public void process(WatchedEvent event) {
           if (event.getState() == KeeperState.Expired) {
               close(contextPath);
           }
       }
   }

   /** ZooKeeper session timer */
   private static class SessionTimerTask extends TimerTask {

       private int delay;
       private String contextPath, session;
       private Timer timer;

       public SessionTimerTask(int delayInSeconds, String session,
               String contextPath, Timer timer) {
           delay = delayInSeconds * 1000; // convert to milliseconds
           this.contextPath = contextPath;
           this.session = session;
           this.timer = timer;
           reset();
       }

       public SessionTimerTask(SessionTimerTask t) {
           this(t.delay / 1000, t.session, t.contextPath, t.timer);
       }

       @Override
       public void run() {
           if (LOG.isInfoEnabled()) {
               LOG.info(String.format("Session '%s' expired after "
                       + "'%d' milliseconds.", session, delay));
           }
           ZooKeeperService.close(contextPath, session);
       }

       public void reset() {
           timer.schedule(this, delay);
       }

   }

   /**
    * Specify ZooKeeper host:port for a particular context path. The host:port
    * string is passed to the ZK client, so this can be formatted with more
    * than a single host:port pair.
    */
   synchronized public static void mapContext(String contextPath, Endpoint e) {
       contextMap.put(contextPath, e);
   }

   /**
    * Reset timer for a session
    */
   synchronized public static void resetTimer(String contextPath,
           String session) {
       if (session != null) {
           String uri = concat(contextPath, session);

           SessionTimerTask t = zkSessionTimers.remove(uri);
           t.cancel();

           zkSessionTimers.put(uri, new SessionTimerTask(t));
       }
   }

   /**
    * Close the ZooKeeper session and remove it from the internal maps
    */
   public static void close(String contextPath) {
       close(contextPath, null);
   }

   /**
    * Close the ZooKeeper session and remove it
    */
   synchronized public static void close(String contextPath, String session) {
       String uri = concat(contextPath, session);

       TimerTask t = zkSessionTimers.remove(uri);
       if (t != null) {
           t.cancel();
       }

       ZooKeeper zk = zkMap.remove(uri);
       if (zk == null) {
           return;
       }
       try {
           zk.close();
       } catch (InterruptedException e) {
           LOG.error("Interrupted while closing ZooKeeper connection.", e);
       }
   }

   /**
    * Close all the ZooKeeper sessions and remove them from the internal maps
    */
   synchronized public static void closeAll() {
       Set<String> sessions = new TreeSet<String>(zkMap.keySet());
       for (String key : sessions) {
           close(key);
       }
   }

   /**
    * Is there an active connection for this session?
    */
   synchronized public static boolean isConnected(String contextPath,
           String session) {
       return zkMap.containsKey(concat(contextPath, session));
   }

   /**
    * Return a ZooKeeper client not tied to a specific session.
    */
   public static ZooKeeper getClient(String contextPath) throws IOException {
       return getClient(contextPath, null);
   }

   /**
    * Return a ZooKeeper client for a session with a default expire time
    * 
    * @throws IOException
    */
   public static ZooKeeper getClient(String contextPath, String session)
           throws IOException {
       return getClient(contextPath, session, 5);
   }

   /**
    * Return a ZooKeeper client which may or may not be connected, but it will
    * not be expired. This method can be called multiple times, the same object
    * will be returned except in the case where the session expires (at which
    * point a new session will be returned)
    */
   synchronized public static ZooKeeper getClient(String contextPath,
           String session, int expireTime) throws IOException {
       final String connectionId = concat(contextPath, session);

       ZooKeeper zk = zkMap.get(connectionId);
       if (zk == null) {

           if (LOG.isInfoEnabled()) {
               LOG.info(String.format("creating new "
                       + "connection for : '%s'", connectionId));
           }
           Endpoint e = contextMap.get(contextPath);
           zk = new ZooKeeper(e.getHostPort(), 30000, new MyWatcher(
                   connectionId));
           
           for (Map.Entry<String, String> p : e.getZooKeeperAuthInfo().entrySet()) {
               zk.addAuthInfo("digest", String.format("%s:%s", p.getKey(),
                       p.getValue()).getBytes());
           }
           
           zkMap.put(connectionId, zk);

           // a session should automatically expire after an amount of time
           if (session != null) {
               zkSessionTimers.put(connectionId, new SessionTimerTask(
                       expireTime, session, contextPath, timer));
           }
       }
       return zk;
   }

   private static String concat(String contextPath, String session) {
       if (session != null) {
           return String.format("%s@%s", contextPath, session);
       }
       return contextPath;
   }

}
