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

# Generates the export and keep lists of one flavor from the ABI goldens.
# The first input is symbols.tsv. The second input is
# static-symbols.tsv, and the keep format reads it.
#
# Variables:
#   flavor  mt or st (required)
#   fmt     sym, keep, exp, map or def (required)
#   ssl     1 when TLS is built, else 0 (default 0)
#   sasl    1 when SASL is built, else 0 (default 0)
#
# Formats:
#   sym   the export names, one for each line (libtool -export-symbols)
#   keep  the export names and the archive-only names (prelink.sh)
#   exp   the export names with the Mach-O "_" prefix
#   map   an anonymous GNU ld version script
#   def   a module definition file (MinGW)
#
# Usage:
#   awk -f gen-exports.awk -v flavor=mt -v fmt=sym symbols.tsv static-symbols.tsv

BEGIN {
    FS = "\t"
    if (flavor != "mt" && flavor != "st") {
        print "gen-exports.awk: flavor must be mt or st" > "/dev/stderr"
        failed = 1
        exit 1
    }
    if (fmt !~ /^(sym|keep|exp|map|def)$/) {
        print "gen-exports.awk: fmt must be sym, keep, exp, map or def" > "/dev/stderr"
        failed = 1
        exit 1
    }
    if (ssl == "") ssl = 0
    if (sasl == "") sasl = 0
    n = 0
}

FNR == 1 { fileno++ }

/^#/ || NF == 0 || $1 == "name" { next }

fileno == 1 {
    if (NF != 9) {
        print "gen-exports.awk: " FILENAME ":" FNR ": need 9 columns" > "/dev/stderr"
        failed = 1
        exit 1
    }
    if (!has_flavor($3)) next
    if ($4 == "ssl" && ssl != 1) next
    if ($4 == "sasl" && sasl != 1) next
    if ($4 != "-" && $4 != "ssl" && $4 != "sasl") {
        print "gen-exports.awk: " FILENAME ":" FNR ": unknown feature " $4 > "/dev/stderr"
        failed = 1
        exit 1
    }
    add($1, $2)
    next
}

fileno == 2 && fmt == "keep" {
    if (NF != 4) {
        print "gen-exports.awk: " FILENAME ":" FNR ": need 4 columns" > "/dev/stderr"
        failed = 1
        exit 1
    }
    add($1, $2)
}

END {
    if (failed) exit 1
    if (fmt == "map") print "{"
    if (fmt == "map") print "  global:"
    if (fmt == "def") print "EXPORTS"
    for (i = 1; i <= n; i++) {
        s = names[i]
        if (fmt == "sym" || fmt == "keep") print s
        else if (fmt == "exp") print "_" s
        else if (fmt == "map") print "    " s ";"
        else if (kinds[s] == "data") print "    " s " DATA"
        else print "    " s
    }
    if (fmt == "map") print "  local:"
    if (fmt == "map") print "    *;"
    if (fmt == "map") print "};"
}

# The flavors column is a comma list, for example "mt,st".
function has_flavor(list,    parts, k, m) {
    m = split(list, parts, ",")
    for (k = 1; k <= m; k++) {
        if (parts[k] == flavor) return 1
    }
    return 0
}

# A name occurs one time in the output, in the input order.
function add(name, kind) {
    if (name in kinds) return
    kinds[name] = kind
    names[++n] = name
}
