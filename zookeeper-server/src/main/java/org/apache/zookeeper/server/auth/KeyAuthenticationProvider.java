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

package org.apache.zookeeper.server.auth;

import java.io.UnsupportedEncodingException;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This class is a sample implementation of being passed the ZooKeeperServer
 * handle in the constructor, and reading data from zknodes to authenticate.
 * At a minimum, a real Auth provider would need to override validate() to
 * e.g. perform certificate validation of auth based a public key.
 *
 * See the "Pluggable ZooKeeper authentication" section of the 
 * "Zookeeper Programmer's Guide" for general details of implementing an
 * authentication plugin. e.g.
 * http://zookeeper.apache.org/doc/trunk/zookeeperProgrammers.html#sc_ZooKeeperPluggableAuthentication
 *
 * This class looks for a numeric "key" under the /key node.
 * Authorization is granted if the user passes in as authorization a number
 * which is a multiple of the key value, i.e. 
 *   (auth % key) == 0
 * In a real implementation, you might do something like storing a public
 * key in /key, and using it to verify that auth tokens passed in were signed
 * by the corresponding private key.
 *
 * When the node /key does not exist, any auth token is accepted, so that 
 * bootstrapping may occur.
 *
 */
public class KeyAuthenticationProvider extends ServerAuthenticationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(KeyAuthenticationProvider.class);

    public String getScheme() {
        return "key";
    }

    private byte[] getKey(ZooKeeperServer zks) {
        ZKDatabase db = zks.getZKDatabase();
        if (db != null) {
            try {
                Stat stat = new Stat();
                return db.getData("/key", stat, null);
            } catch (NoNodeException e) {
                LOG.error("getData failed", e);
            }
        }
        return null;
    }

    private boolean validate(byte[] key, byte[] auth) {
        // perform arbitrary function (auth is a multiple of key)
        try {
            String keyStr = new String(key, "UTF-8");
            String authStr = new String(auth, "UTF-8");
            int keyVal = Integer.parseInt(keyStr);
            int authVal = Integer.parseInt(authStr);
            if (keyVal!=0 && ((authVal % keyVal) != 0)) {
              return false;
            }
        } catch (NumberFormatException | UnsupportedEncodingException nfe) {
            LOG.error("bad formatting", nfe);
          return false;
        }
        return true;
    }

    @Override
    public KeeperException.Code handleAuthentication(ServerObjs serverObjs, byte[] authData) {
        byte[] key = getKey(serverObjs.getZks());
        String authStr = "";
        String keyStr = "";
        try {
          authStr = new String(authData, "UTF-8");
        } catch (Exception e) {
            LOG.error("UTF-8", e);
        }
        if (key != null) {
            if (!validate(key, authData)) {
                try {
                  keyStr = new String(key, "UTF-8");
                } catch (Exception e) {
                    LOG.error("UTF-8", e);
                    // empty key
                    keyStr = authStr;
                }
                LOG.debug("KeyAuthenticationProvider handleAuthentication ("+keyStr+", "+authStr+") -> FAIL.\n");
                return KeeperException.Code.AUTHFAILED;
            }
        }
        // default to allow, so the key can be initially written
        LOG.debug("KeyAuthenticationProvider handleAuthentication -> OK.\n");
        // NOTE: keyStr in addAuthInfo() sticks with the created node ACLs.
        //   For transient keys or certificates, this presents a problem.
        //   In that case, replace it with something non-ephemeral (or punt with null).
        //
        // BOTH addAuthInfo and an OK return-code are needed for authentication.
        serverObjs.getCnxn().addAuthInfo(new Id(getScheme(), keyStr));
        return KeeperException.Code.OK;
    }

    @Override
    public boolean matches(ServerObjs serverObjs, MatchValues matchValues) {
        return matchValues.getId().equals(matchValues.getAclExpr());
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public boolean isValid(String id) {
        return true;
    }
}
