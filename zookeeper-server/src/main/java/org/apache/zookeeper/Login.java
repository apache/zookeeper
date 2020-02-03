/*
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

import java.util.Date;
import java.util.Random;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Login {

    private static final String KINIT_COMMAND_DEFAULT = "/usr/bin/kinit";
    private static final Logger LOG = LoggerFactory.getLogger(Login.class);
    public static final String SYSTEM_USER = System.getProperty("user.name", "<NA>");
    public CallbackHandler callbackHandler;

    // LoginThread will sleep until 80% of time from last refresh to
    // ticket's expiry has been reached, at which time it will wake
    // and try to renew the ticket.
    private static final float TICKET_RENEW_WINDOW = 0.80f;

    /**
     * Percentage of random jitter added to the renewal time
     */
    private static final float TICKET_RENEW_JITTER = 0.05f;

    // Regardless of TICKET_RENEW_WINDOW setting above and the ticket expiry time,
    // thread will not sleep between refresh attempts any less than 1 minute (60*1000 milliseconds = 1 minute).
    // Change the '1' to e.g. 5, to change this to 5 minutes.
    private static final long MIN_TIME_BEFORE_RELOGIN = 1 * 60 * 1000L;

    private Subject subject = null;
    private Thread t = null;
    private boolean isKrbTicket = false;
    private boolean isUsingTicketCache = false;

    /** Random number generator */
    private static Random rng = new Random();

    private LoginContext login = null;
    private String loginContextName = null;
    private String principal = null;

    // Initialize 'lastLogin' to do a login at first time
    private long lastLogin = Time.currentElapsedTime() - MIN_TIME_BEFORE_RELOGIN;
    private final ZKConfig zkConfig;

    /**
     * LoginThread constructor. The constructor starts the thread used to
     * periodically re-login to the Kerberos Ticket Granting Server.
     *
     * @param loginContextName
     *            name of section in JAAS file that will be use to login. Passed
     *            as first param to javax.security.auth.login.LoginContext().
     *
     * @param callbackHandler
     *            Passed as second param to
     *            javax.security.auth.login.LoginContext().
     * @param zkConfig
     *            client or server configurations
     * @throws javax.security.auth.login.LoginException
     *             Thrown if authentication fails.
     */
    public Login(final String loginContextName, CallbackHandler callbackHandler, final ZKConfig zkConfig) throws LoginException {
        this.zkConfig = zkConfig;
        this.callbackHandler = callbackHandler;
        login = login(loginContextName);
        this.loginContextName = loginContextName;
        subject = login.getSubject();
        isKrbTicket = !subject.getPrivateCredentials(KerberosTicket.class).isEmpty();
        AppConfigurationEntry[] entries = Configuration.getConfiguration().getAppConfigurationEntry(loginContextName);
        for (AppConfigurationEntry entry : entries) {
            // there will only be a single entry, so this for() loop will only be iterated through once.
            if (entry.getOptions().get("useTicketCache") != null) {
                String val = (String) entry.getOptions().get("useTicketCache");
                if (val.equals("true")) {
                    isUsingTicketCache = true;
                }
            }
            if (entry.getOptions().get("principal") != null) {
                principal = (String) entry.getOptions().get("principal");
            }
            break;
        }

        if (!isKrbTicket) {
            // if no TGT, do not bother with ticket management.
            return;
        }

        // Refresh the Ticket Granting Ticket (TGT) periodically. How often to refresh is determined by the
        // TGT's existing expiry date and the configured MIN_TIME_BEFORE_RELOGIN. For testing and development,
        // you can decrease the interval of expiration of tickets (for example, to 3 minutes) by running :
        //  "modprinc -maxlife 3mins <principal>" in kadmin.
        t = new Thread(new Runnable() {
            public void run() {
                LOG.info("TGT refresh thread started.");
                while (true) {  // renewal thread's main loop. if it exits from here, thread will exit.
                    KerberosTicket tgt = getTGT();
                    long now = Time.currentWallTime();
                    long nextRefresh;
                    Date nextRefreshDate;
                    if (tgt == null) {
                        nextRefresh = now + MIN_TIME_BEFORE_RELOGIN;
                        nextRefreshDate = new Date(nextRefresh);
                        LOG.warn("No TGT found: will try again at {}", nextRefreshDate);
                    } else {
                        nextRefresh = getRefreshTime(tgt);
                        long expiry = tgt.getEndTime().getTime();
                        Date expiryDate = new Date(expiry);
                        if ((isUsingTicketCache) && (tgt.getEndTime().equals(tgt.getRenewTill()))) {
                            LOG.error(
                                "The TGT cannot be renewed beyond the next expiry date: {}."
                                    + "This process will not be able to authenticate new SASL connections after that "
                                    + "time (for example, it will not be authenticate a new connection with a Zookeeper "
                                    + "Quorum member).  Ask your system administrator to either increase the "
                                    + "'renew until' time by doing : 'modprinc -maxrenewlife {}' within "
                                    + "kadmin, or instead, to generate a keytab for {}. Because the TGT's "
                                    + "expiry cannot be further extended by refreshing, exiting refresh thread now.",
                                expiryDate,
                                principal,
                                principal);
                            return;
                        }
                        // determine how long to sleep from looking at ticket's expiry.
                        // We should not allow the ticket to expire, but we should take into consideration
                        // MIN_TIME_BEFORE_RELOGIN. Will not sleep less than MIN_TIME_BEFORE_RELOGIN, unless doing so
                        // would cause ticket expiration.
                        if ((nextRefresh > expiry) || ((now + MIN_TIME_BEFORE_RELOGIN) > expiry)) {
                            // expiry is before next scheduled refresh).
                            nextRefresh = now;
                        } else {
                            if (nextRefresh < (now + MIN_TIME_BEFORE_RELOGIN)) {
                                // next scheduled refresh is sooner than (now + MIN_TIME_BEFORE_LOGIN).
                                Date until = new Date(nextRefresh);
                                Date newuntil = new Date(now + MIN_TIME_BEFORE_RELOGIN);
                                LOG.warn(
                                    "TGT refresh thread time adjusted from : {} to : {} since "
                                        + "the former is sooner than the minimum refresh interval ("
                                        + "{} seconds) from now.",
                                    until,
                                    newuntil,
                                    (MIN_TIME_BEFORE_RELOGIN / 1000));
                            }
                            nextRefresh = Math.max(nextRefresh, now + MIN_TIME_BEFORE_RELOGIN);
                        }
                        nextRefreshDate = new Date(nextRefresh);
                        if (nextRefresh > expiry) {
                            LOG.error(
                                "next refresh: {} is later than expiry {}."
                                    + " This may indicate a clock skew problem."
                                    + " Check that this host and the KDC's "
                                    + "hosts' clocks are in sync. Exiting refresh thread.",
                                nextRefreshDate,
                                expiryDate);
                            return;
                        }
                    }
                    if (now == nextRefresh) {
                        LOG.info("refreshing now because expiry is before next scheduled refresh time.");
                    } else if (now < nextRefresh) {
                        Date until = new Date(nextRefresh);
                        LOG.info("TGT refresh sleeping until: {}", until.toString());
                        try {
                            Thread.sleep(nextRefresh - now);
                        } catch (InterruptedException ie) {
                            LOG.warn("TGT renewal thread has been interrupted and will exit.");
                            break;
                        }
                    } else {
                        LOG.error(
                            "nextRefresh:{} is in the past: exiting refresh thread. Check"
                                + " clock sync between this host and KDC - (KDC's clock is likely ahead of this host)."
                                + " Manual intervention will be required for this client to successfully authenticate."
                                + " Exiting refresh thread.",
                            nextRefreshDate);
                        break;
                    }
                    if (isUsingTicketCache) {
                        String cmd = zkConfig.getProperty(ZKConfig.KINIT_COMMAND, KINIT_COMMAND_DEFAULT);
                        String kinitArgs = "-R";
                        int retry = 1;
                        while (retry >= 0) {
                            try {
                                LOG.debug("running ticket cache refresh command: {} {}", cmd, kinitArgs);
                                Shell.execCommand(cmd, kinitArgs);
                                break;
                            } catch (Exception e) {
                                if (retry > 0) {
                                    --retry;
                                    // sleep for 10 seconds
                                    try {
                                        Thread.sleep(10 * 1000);
                                    } catch (InterruptedException ie) {
                                        LOG.error("Interrupted while renewing TGT, exiting Login thread");
                                        return;
                                    }
                                } else {
                                    LOG.warn(
                                        "Could not renew TGT due to problem running shell command: '{} {}'."
                                            + " Exiting refresh thread.",
                                        cmd,
                                        kinitArgs,
                                        e);
                                    return;
                                }
                            }
                        }
                    }
                    try {
                        int retry = 1;
                        while (retry >= 0) {
                            try {
                                reLogin();
                                break;
                            } catch (LoginException le) {
                                if (retry > 0) {
                                    --retry;
                                    // sleep for 10 seconds.
                                    try {
                                        Thread.sleep(10 * 1000);
                                    } catch (InterruptedException e) {
                                        LOG.error("Interrupted during login retry after LoginException:", le);
                                        throw le;
                                    }
                                } else {
                                    LOG.error("Could not refresh TGT for principal: {}.", principal, le);
                                }
                            }
                        }
                    } catch (LoginException le) {
                        LOG.error("Failed to refresh TGT: refresh thread exiting now.", le);
                        break;
                    }
                }
            }
        });
        t.setDaemon(true);
    }

    public void startThreadIfNeeded() {
        // thread object 't' will be null if a refresh thread is not needed.
        if (t != null) {
            t.start();
        }
    }

    public void shutdown() {
        if ((t != null) && (t.isAlive())) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                LOG.warn("error while waiting for Login thread to shutdown.", e);
            }
        }
    }

    public Subject getSubject() {
        return subject;
    }

    public String getUserName() {
        if (principal == null || principal.isEmpty()) {
            return SYSTEM_USER;
        }
        return principal;
    }

    public String getLoginContextName() {
        return loginContextName;
    }

    private synchronized LoginContext login(final String loginContextName) throws LoginException {
        if (loginContextName == null) {
            throw new LoginException("loginContext name (JAAS file section header) was null. "
                                     + "Please check your java.security.login.auth.config (="
                                     + System.getProperty("java.security.login.auth.config")
                                     + ") and your "
                                     + getLoginContextMessage());
        }
        LoginContext loginContext = new LoginContext(loginContextName, callbackHandler);
        loginContext.login();
        LOG.info("{} successfully logged in.", loginContextName);
        return loginContext;
    }

    private String getLoginContextMessage() {
        if (zkConfig instanceof ZKClientConfig) {
            return ZKClientConfig.LOGIN_CONTEXT_NAME_KEY
                   + "(="
                   + zkConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT)
                   + ")";
        } else {
            return ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY
                   + "(="
                   + System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY, ZooKeeperSaslServer.DEFAULT_LOGIN_CONTEXT_NAME)
                   + ")";
        }
    }

    // c.f. org.apache.hadoop.security.UserGroupInformation.
    private long getRefreshTime(KerberosTicket tgt) {
        long start = tgt.getStartTime().getTime();
        long expires = tgt.getEndTime().getTime();
        LOG.info("TGT valid starting at:        {}", tgt.getStartTime().toString());
        LOG.info("TGT expires:                  {}", tgt.getEndTime().toString());
        long proposedRefresh = start + (long) ((expires - start) * (TICKET_RENEW_WINDOW + (TICKET_RENEW_JITTER * rng.nextDouble())));
        if (proposedRefresh > expires) {
            // proposedRefresh is too far in the future: it's after ticket expires: simply return now.
            return Time.currentWallTime();
        } else {
            return proposedRefresh;
        }
    }

    private synchronized KerberosTicket getTGT() {
        Set<KerberosTicket> tickets = subject.getPrivateCredentials(KerberosTicket.class);
        for (KerberosTicket ticket : tickets) {
            KerberosPrincipal server = ticket.getServer();
            if (server.getName().equals("krbtgt/" + server.getRealm() + "@" + server.getRealm())) {
                LOG.debug("Client principal is \"{}\".", ticket.getClient().getName());
                LOG.debug("Server principal is \"{}\".", ticket.getServer().getName());
                return ticket;
            }
        }
        return null;
    }

    private boolean hasSufficientTimeElapsed() {
        long now = Time.currentElapsedTime();
        if (now - getLastLogin() < MIN_TIME_BEFORE_RELOGIN) {
            LOG.warn("Not attempting to re-login since the last re-login was "
                     + "attempted less than {} seconds before.",
                     (MIN_TIME_BEFORE_RELOGIN / 1000));
            return false;
        }
        // register most recent relogin attempt
        setLastLogin(now);
        return true;
    }

    /**
     * Returns login object
     * @return login
     */
    private LoginContext getLogin() {
        return login;
    }

    /**
     * Set the login object
     * @param login
     */
    private void setLogin(LoginContext login) {
        this.login = login;
    }

    /**
     * Set the last login time.
     * @param time the number of milliseconds since the beginning of time
     */
    private void setLastLogin(long time) {
        lastLogin = time;
    }

    /**
     * Get the time of the last login.
     * @return the number of milliseconds since the beginning of time.
     */
    private long getLastLogin() {
        return lastLogin;
    }

    /**
     * Re-login a principal. This method assumes that {@link #login(String)} has happened already.
     * @throws javax.security.auth.login.LoginException on a failure
     */
    // c.f. HADOOP-6559
    private synchronized void reLogin() throws LoginException {
        if (!isKrbTicket) {
            return;
        }
        LoginContext login = getLogin();
        if (login == null) {
            throw new LoginException("login must be done first");
        }
        if (!hasSufficientTimeElapsed()) {
            return;
        }
        LOG.info("Initiating logout for {}", principal);
        synchronized (Login.class) {
            //clear up the kerberos state. But the tokens are not cleared! As per
            //the Java kerberos login module code, only the kerberos credentials
            //are cleared
            login.logout();
            //login and also update the subject field of this instance to
            //have the new credentials (pass it to the LoginContext constructor)
            login = new LoginContext(loginContextName, getSubject());
            LOG.info("Initiating re-login for {}", principal);
            login.login();
            setLogin(login);
        }
    }

}
