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
 * Error translation tables, client version reporting, and session validity queries.
 *
 * Implements zerror and is_unrecoverable according to legacy C client contracts,
 * mapping ZooKeeper error codes to human readable strings and detecting unrecoverable
 * terminal session states (e.g. session expiration or authentication failure).
 */

#include "zks_internal.h"

/* Canonical error strings corresponding to ZooKeeper return codes. */
static const struct {
    int code;
    const char *text;
} zks_error_texts[] = {
    {ZOK, "ok"},
    {ZSYSTEMERROR, "system error"},
    {ZRUNTIMEINCONSISTENCY, "run time inconsistency"},
    {ZDATAINCONSISTENCY, "data inconsistency"},
    {ZCONNECTIONLOSS, "connection loss"},
    {ZMARSHALLINGERROR, "marshalling error"},
    {ZUNIMPLEMENTED, "unimplemented"},
    {ZOPERATIONTIMEOUT, "operation timeout"},
    {ZBADARGUMENTS, "bad arguments"},
    {ZINVALIDSTATE, "invalid zhandle state"},
    {ZNEWCONFIGNOQUORUM,
     "no quorum of new config is connected and up-to-date with the leader of last "
     "committed config - try invoking reconfiguration after new servers are connected "
     "and synced"},
    {ZRECONFIGINPROGRESS,
     "Another reconfiguration is in progress -- concurrent reconfigs not supported "
     "(yet)"},
    {ZSSLCONNECTIONERROR, "ssl connection error"},
    {ZAPIERROR, "api error"},
    {ZNONODE, "no node"},
    {ZNOAUTH, "not authenticated"},
    {ZBADVERSION, "bad version"},
    {ZNOCHILDRENFOREPHEMERALS, "no children for ephemerals"},
    {ZNODEEXISTS, "node exists"},
    {ZNOTEMPTY, "not empty"},
    {ZSESSIONEXPIRED, "session expired"},
    {ZINVALIDCALLBACK, "invalid callback"},
    {ZINVALIDACL, "invalid acl"},
    {ZAUTHFAILED, "authentication failed"},
    {ZCLOSING, "zookeeper is closing"},
    {ZNOTHING, "(not error) no server responses to process"},
    {ZSESSIONMOVED, "session moved to another server, so operation is ignored"},
    {ZNOTREADONLY, "state-changing request is passed to read-only server"},
    {ZEPHEMERALONLOCALSESSION, "attempt to create ephemeral node on a local session"},
    {ZNOWATCHER, "the watcher couldn't be found"},
    {ZRECONFIGDISABLED,
     "attempts to perform a reconfiguration operation when reconfiguration feature is "
     "disable"},
    {ZSESSIONCLOSEDREQUIRESASLAUTH,
     "session closed by server because client is required to do SASL authentication"},
    {ZTHROTTLEDOP, "Operation was throttled due to high load"},
};

/* Look up the human readable description for a ZooKeeper return code. */
static const char *zks_error_text(int c)
{
    size_t i;
    for (i = 0; i < sizeof(zks_error_texts) / sizeof(zks_error_texts[0]); i++) {
        if (zks_error_texts[i].code == c) {
            return zks_error_texts[i].text;
        }
    }
    return NULL;
}

const char *zerror(int c)
{
    const char *text = zks_error_text(c);
    if (text != NULL) {
        return text;
    }
    if (c > 0) {
        return strerror(c);
    }
    return "unknown error";
}

const char *zoo_version_str(void)
{
    return ZOO_VERSION;
}

int is_unrecoverable(zhandle_t *zh)
{
    /*
     * Negative state values denote terminal session conditions such as session
     * expiration or authentication failure.
     */
    if (zh == NULL) {
        return ZINVALIDSTATE;
    }
    return ZKS_CALL(zkrs_state(zh->core)) < 0 ? ZINVALIDSTATE : ZOK;
}
