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

/*
 * Diagnostic client environment logging. Emits host, operating system, user identity, and
 * working directory diagnostics matching the legacy C client's startup banner format.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* POSIX names under -std=c99 on glibc */
#endif
#include "zks_internal.h"

#include <pwd.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <unistd.h>

/* Fallback client version identifier in standalone compilation environments lacking config.h. */
#ifndef PACKAGE_STRING
#define PACKAGE_STRING "zookeeper C client " ZOO_VERSION
#endif

/*
 * Collects host environment metadata via POSIX system calls and logs diagnostic
 * records to the configured log stream, matching legacy log_env output formatting.
 */
ZKS_HIDDEN void zks_log_env(zhandle_t *zh)
{
    char buf[2048];
    struct utsname utsname;
    struct passwd pw;
    struct passwd *pwp = NULL;
    uid_t uid;

    ZKS_LOG(zh, ZKS_SITE_ENV_VERSION, PACKAGE_STRING);

    buf[0] = '\0';
    gethostname(buf, sizeof(buf));
    // Ensure null termination on truncation across POSIX gethostname variants.
    buf[sizeof(buf) - 1] = '\0';
    ZKS_LOG(zh, ZKS_SITE_ENV_HOST_NAME, buf);

    memset(&utsname, 0, sizeof(utsname));
    uname(&utsname);
    ZKS_LOG(zh, ZKS_SITE_ENV_OS_NAME, utsname.sysname);
    ZKS_LOG(zh, ZKS_SITE_ENV_OS_ARCH, utsname.release);
    ZKS_LOG(zh, ZKS_SITE_ENV_OS_VERSION, utsname.version);

    if (ZKS_LOAD(&logLevel) >= ZOO_LOG_LEVEL_INFO) {
        const char *login = getlogin();
        ZKS_LOG(zh, ZKS_SITE_ENV_USER_NAME, login != NULL ? login : "(null)");
    }

    uid = getuid();
    if (!getpwuid_r(uid, &pw, buf, sizeof(buf), &pwp) && pwp) {
        ZKS_LOG(zh, ZKS_SITE_ENV_USER_HOME, pw.pw_dir);
    } else {
        ZKS_LOG(zh, ZKS_SITE_ENV_USER_HOME_NA);
    }

    if (!getcwd(buf, sizeof(buf))) {
        ZKS_LOG(zh, ZKS_SITE_ENV_USER_DIR_TOOLONG);
    } else {
        ZKS_LOG(zh, ZKS_SITE_ENV_USER_DIR, buf);
    }
}
