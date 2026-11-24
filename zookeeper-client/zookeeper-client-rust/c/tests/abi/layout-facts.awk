# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Transforms a `tests/abi/layout/<target>.expect` baseline into the C macro
# table consumed by `layout_check.c`.
#
# Usage: awk -v config=plain|tls-sasl -f layout-facts.awk <target>.expect
#
# Emits `ZK_FACT(expression, value, "description")` entries. Because `.expect`
# files record only incremental deltas for `tls-sasl`, selecting `tls-sasl`
# overlays those rows onto the `plain` baseline.

BEGIN {
    FS = "\t"
    n = 0
    if (config != "plain" && config != "tls-sasl") {
        print "layout-facts.awk: config must be plain or tls-sasl" > "/dev/stderr"
        exit 2
    }
}

/^#/ || $1 == "config" || NF < 5 { next }

$1 == "plain" || $1 == config {
    key = $2 SUBSEP $3 SUBSEP $4
    if (!(key in value)) {
        order[n++] = key
        fact[key] = $2
        type[key] = $3
        member[key] = $4
    }
    value[key] = $5
}

END {
    for (i = 0; i < n; i++) {
        k = order[i]
        f = fact[k]
        t = type[k]
        m = member[k]
        if (f == "sizeof") {
            e = "sizeof(" t ")"
        } else if (f == "alignof") {
            e = "_Alignof(" t ")"
        } else if (f == "offsetof") {
            e = "offsetof(" t ", " m ")"
        } else if (f == "signed") {
            e = "((" t ")-1 < 0)"
        } else {
            print "layout-facts.awk: unknown fact " f > "/dev/stderr"
            exit 2
        }
        printf "ZK_FACT(%s, %sULL, \"%s %s %s %s\")\n", e, value[k], f, t, m, value[k]
    }
}
