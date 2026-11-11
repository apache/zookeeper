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
 * Error code string formatting via zerror(). Mode: EXACT.
 *
 * Verifies that every status code in [-200, 10] (excluding ZSSLCONNECTIONERROR
 * (-15), which is verified in dev_misc) as well as INT_MIN and
 * INT_MAX map to identical error strings on both libraries.
 *
 * zkconf-flavors: mt st
 */

#include "zkconf.h"

#include <limits.h>

int main(int argc, char **argv)
{
    int rc;
    zkc_init(argc, argv, "zerror", 0, "EXACT", "ignore");
    for (rc = -200; rc <= 10; rc++) {
        if (rc != ZSSLCONNECTIONERROR) {
            zkc_ev("ret", "zerror(%d) -> \"%s\"", rc, zerror(rc));
        }
    }
    zkc_ev("ret", "zerror(INT_MIN) -> \"%s\"", zerror(INT_MIN));
    zkc_ev("ret", "zerror(INT_MAX) -> \"%s\"", zerror(INT_MAX));
    return zkc_finish();
}
