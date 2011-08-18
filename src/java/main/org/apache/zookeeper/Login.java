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

package org.apache.zookeeper;

/** 
 * This class is responsible for refreshing Kerberos credentials for
 * logins for both Zookeeper client and server.
 * See ZooKeeperSaslServer for server-side usage.
 * See ZooKeeperSaslClient for client-side usage.
 */

import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.callback.CallbackHandler;

import org.apache.log4j.Logger;

import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.Subject;
import java.io.IOException;
import java.util.Date;
import java.util.Set;

public class Login {
    Logger LOG = Logger.getLogger(Login.class);
    public CallbackHandler callbackHandler;

    // LoginThread will sleep until 80% of time from last refresh to
    // ticket's expiry has been reached, at which time it will wake
    // and try to renew the ticket.
    private static final float TICKET_RENEW_WINDOW = 0.80f;

    // Regardless of TICKET_RENEW_WINDOW setting above and the ticket expiry time,
    // thread will not sleep between refresh attempts any less than 1 minute (60*1000 milliseconds = 1 minute).
    // Change the '1' to e.g. 5, to change this to 5 minutes.
    private static final long MIN_TIME_BEFORE_RELOGIN = 1 * 60 * 1000L;

    private Subject subject = null;
    private Thread t = null;
    private boolean isKrbTicket = false;
    private boolean isUsingTicketCache = false;
    /**
     * LoginThread constructor. The constructor starts the thread used
     * to periodically re-login to the Kerberos Ticket Granting Server.
     * @param loginContextName
     *               name of section in JAAS file that will be use to login.
     *               Passed as first param to javax.security.auth.login.LoginContext().
     *
     * @param callbackHandler
     *               Passed as second param to javax.security.auth.login.LoginContext().
     * @throws javax.security.auth.login.LoginException
     *               Thrown if authentication fails.
     */
    public Login(final String loginContextName, CallbackHandler callbackHandler)
      throws LoginException {
        this.callbackHandler = callbackHandler;
        final LoginContext loginContext = login(loginContextName);
        subject = loginContext.getSubject();
        isKrbTicket = !subject.getPrivateCredentials(KerberosTicket.class).isEmpty();
        AppConfigurationEntry entries[] = Configuration.getConfiguration().getAppConfigurationEntry(loginContextName);
        for (AppConfigurationEntry entry: entries) {
            if (entry.getOptions().get("useTicketCache") != null) {
                String val = (String)entry.getOptions().get("useTicketCache");
                if (val.equals("true")) {
                    isUsingTicketCache = true;
                }
                break;
            }
        }
        if (isKrbTicket && isUsingTicketCache) {
            // Refresh the Ticket Granting Ticket (TGT) cache periodically. How often to refresh is determined by the
            // TGT's existing expiry date and the configured MIN_TIME_BEFORE_RELOGIN. For testing and development,
            // you can decrease the interval of expiration of tickets (for example, to 3 minutes) by running :
            //  "modprinc -maxlife 3mins <principal>" in kadmin.
            t = new Thread(new Runnable() {
                public void run() {
                    LOG.info("TGT refresh thread started.");
                    while (true) {  // renewal thread's main loop. if it exits from here, thread will exit.
                        KerberosTicket tgt = getTGT();
                        long now = System.currentTimeMillis();
                        long nextRefresh;
                        Date nextRefreshDate;
                        if (tgt == null) {
                            nextRefresh = now + MIN_TIME_BEFORE_RELOGIN;
                            nextRefreshDate = new Date(nextRefresh);
                            LOG.warn("No TGT found: will try again at " + nextRefreshDate);
                        }
                        else {
                            // determine how long to sleep from looking at ticket's expiry.
                            // We must not allow the ticket to expire, but we should take into consideration
                            // MIN_TIME_BEFORE_RELOGIN. Will not sleep less than MIN_TIME_BEFORE_RELOGIN, except when
                            // unless it would cause ticket expiration.
                            nextRefresh = getRefreshTime(tgt);
                            long expiry = tgt.getEndTime().getTime();

                            if ((nextRefresh > expiry) ||
                              ((now + MIN_TIME_BEFORE_RELOGIN) > expiry)) {
                                // expiry is before next scheduled refresh).
                                LOG.info("refreshing now because expiry is before next scheduled refresh time.");
                                nextRefresh = now;
                            }
                            else {
                                if (nextRefresh < (now + MIN_TIME_BEFORE_RELOGIN)) {
                                    // next scheduled refresh is sooner than (now + MIN_TIME_BEFORE_LOGIN).
                                    Date until = new Date(nextRefresh);
                                    Date newuntil = new Date(now + MIN_TIME_BEFORE_RELOGIN);
                                    LOG.warn("TGT refresh thread time adjusted from : " + until + " to : " + newuntil + " since "
                                      + "the former is sooner than the minimum refresh interval ("
                                      + MIN_TIME_BEFORE_RELOGIN / 1000 + " seconds) from now.");
                                }
                                nextRefresh = Math.max(nextRefresh, now + MIN_TIME_BEFORE_RELOGIN);
                            }
                            nextRefreshDate = new Date(nextRefresh);
                            if (nextRefresh > expiry) {
                                Date expiryDate = new Date(expiry);
                                LOG.error("next refresh: " + nextRefreshDate + " is later than expiry " + expiryDate
                                  + ". This may indicated a clock skew problem. Check that this host and the KDC's "
                                  + "hosts' clocks are in sync.");
                                return;
                            }
                        }

                        if (now < nextRefresh) {
                            Date until = new Date(nextRefresh);
                            LOG.info("TGT refresh thread sleeping until: " + until.toString());
                            try {
                                Thread.sleep(nextRefresh - now);
                            }
                            catch (InterruptedException ie) {
                                LOG.warn("TGT renewal thread has been interrupted and will exit.");
                                break;
                            }
                        }
                        else {
                            LOG.error("nextRefresh:" + nextRefreshDate + " is in the past: exiting refresh thread. Check"
                              + " clock sync between this host and KDC - (KDC's clock is likely ahead of this host)."
                              + " Manual intervention will be required for this client to successfully authenticate.");
                            // TODO: if we have a keytab, we can use that to re-initialize and avoid the need for
                            // manual intervention.
                            return;
                        }

                        String cmd = "/usr/bin/kinit";
                        if (System.getProperty("zookeeper.kinit") != null) {
                            cmd = System.getProperty("zookeeper.kinit");
                        }
                        String kinitArgs = "-R";
                        try {
                            Shell.execCommand(cmd,kinitArgs);
                        }
                        catch (Shell.ExitCodeException e) {
                            LOG.error("Could not renew TGT due to problem running shell command: '" + cmd
                              + " " + kinitArgs + "'" + "; exception was:" + e + ". Will try shell command again at: "
                              + nextRefreshDate);
                        }
                        catch (IOException e) {
                            LOG.error("Could not renew TGT due to problem running shell command: '" + cmd
                              + " " + kinitArgs + "'; exception was:" + e + ". Will try shell command again at: "
                              + nextRefreshDate);
                        }
                        try {
                            reloginFromTicketCache(loginContextName, loginContext);
                            LOG.debug("renewed TGT successfully.");
                        }
                        catch (LoginException e) {
                            LOG.error("Could not renew TGT due to LoginException: " + e + "."
                              + " Will try again at: "
                              + nextRefreshDate);
                        }
                    }
                }
            });
            t.setDaemon(true);
        }
        else {
            LOG.error("Not using Ticket Granting Ticket cache: will not start a TGT renewal thread.");
        }
    }

    public void startThreadIfNeeded() {
        // thread object 't' will be null if a refresh thread is not needed.
        if (t != null) {
            t.start();
        }
    }


    private synchronized LoginContext login(final String loginContextName) throws LoginException {
        if (loginContextName == null) {
            throw new LoginException("loginContext name (JAAS file section header) was null. " +
              "Please check your java.security.login.auth.config setting.");
        }
        LoginContext loginContext = new LoginContext(loginContextName,callbackHandler);
        loginContext.login();
        LOG.info("successfully logged in.");
        return loginContext;
    }

    public Subject getSubject() {
        return subject;
    }

    // c.f. org.apache.hadoop.security.UserGroupInformation.
    private long getRefreshTime(KerberosTicket tgt) {
        long start = tgt.getStartTime().getTime();
        long expires = tgt.getEndTime().getTime();
        LOG.info("TGT valid starting at: " + tgt.getStartTime().toString());
        LOG.info("TGT expires: " + tgt.getEndTime().toString());
        long proposedRefresh = start + (long) ((expires - start) * TICKET_RENEW_WINDOW);
        if (proposedRefresh > expires) {
            // proposedRefresh is too far in the future: it's after ticket expires: simply return now.
            return System.currentTimeMillis();
        }
        else {
            return proposedRefresh;
        }
    }

    private synchronized KerberosTicket getTGT() {
        Set<KerberosTicket> tickets = subject.getPrivateCredentials(KerberosTicket.class);
        for(KerberosTicket ticket: tickets) {
            KerberosPrincipal server = ticket.getServer();
            if (server.getName().equals("krbtgt/" + server.getRealm() + "@" + server.getRealm())) {
                LOG.debug("Found tgt " + ticket + ".");
                return ticket;
            }
        }
        return null;
    }

    // TODO : refactor this with login() to maximize code-sharing.
    public synchronized void reloginFromTicketCache(final String loginContextName, LoginContext loginContext)
        throws LoginException {
        if (!(isKrbTicket && isUsingTicketCache)) {
            return;
        }
        if (loginContext == null) {
            throw new LoginException("login must be done first");
        }
        String principalName = getPrincipalName();
        try {
            LOG.info("Logging out " + principalName);
            //clear up the Kerberos state. But the tokens are not cleared! As per
            //the Java kerberos login module code, only the kerberos credentials
            //are cleared.
            loginContext.logout();
            //login and also update the subject field of this instance to
            //have the new credentials (pass it to the LoginContext constructor)
            if (loginContextName == null) {
                throw new LoginException("loginContext name (JAAS file section header) was null. " +
                  "Please check your java.security.login.auth.config setting.");
            }
            if (subject == null) {
                throw new LoginException("login subject was null.");
            }
            LOG.info("Logging in " + principalName);
            loginContext.login();
            if (principalName.equals("(no principal name)")) {
                // try again to get the principal name, in case the ticket cache was manually refreshed.
                principalName = getPrincipalName();
            }
            LOG.info("Login successful for " + principalName);
        } catch (LoginException le) {
            throw new LoginException("Login failure for " + principalName);
        }
    }

    private String getPrincipalName() {
        try {
            return getSubject().getPrincipals(KerberosPrincipal.class).toArray()[0].toString();
        }
        catch (NullPointerException e) {
            LOG.warn("could not display principal name because login was null or login's subject was null: returning '(no principal found)'.");
        }
        catch (ArrayIndexOutOfBoundsException e) {
            LOG.warn("could not display principal name because login's subject had no principals: returning '(no principal found)'.");
        }
        return "(no principal found)";
    }

    public void shutdown() {
        if ((t != null) && (t.isAlive())) {
            t.interrupt();
            try {
                t.join();
            }
            catch (InterruptedException e) {
                LOG.error("error while waiting for Login thread to shutdown: " + e);
            }
        }
    }

}

