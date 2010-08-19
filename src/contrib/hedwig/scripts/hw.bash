#!/usr/bin/env bash

#  Licensed to the Apache Software Foundation (ASF) under one or more
#  contributor license agreements.  See the NOTICE file distributed with
#  this work for additional information regarding copyright ownership.
#  The ASF licenses this file to You under the Apache License, Version 2.0
#  (the "License"); you may not use this file except in compliance with
#  the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# This file is a collection of script functions. To use, just run this file
# with the function you want to execute as the first argument and arguments to
# the function following that.
#
# For instance, to install the prereq jars:
#
#   ./hw.bash install-zk
#
# Or to run BookKeeper:
#
#   ./hw.bash bk 3181 /tmp/bk/{journal,ledgers}

# Note: unbuffer can cause all sorts of funky problems, especially when dealing
# with high volumes of output from multiple sources!  Problems aren't just
# about causing your terminal to get garbled; they may be as severe as killing
# this script itself.

set -o errexit # -o nounset

dark_blue="\e[0;34m";     bright_blue="\e[1;34m"
dark_green="\e[0;32m";    bright_green="\e[1;32m"
dark_cyan="\e[0;36m";     bright_cyan="\e[1;36m"
dark_red="\e[0;31m";      bright_red="\e[1;31m"
dark_magenta="\e[0;35m";  bright_magenta="\e[1;35m"
brown="\e[0;33m";         yellow="\e[1;33m"
black="\e[0;30m";         dark_gray="\e[1;30m";
bold_white="\e[1;37m"     white="\e[0;37m"
normal_color="\e[0m"

# Default server ports used
PROFILER_PORT=9874
SERVER_PORT=9875
SSL_SERVER_PORT=9876
ZOOKEEPER_PORT=9877
BOOKKEEPER_PORT=9878

: ${script:=$0}
serverdir="$(readlink -f "$(dirname "$script")/../server/")" || serverdir=
cmd="$(basename "$script")"
jarbase=server-1.0-SNAPSHOT-jar-with-dependencies.jar
if [[ -f "$(dirname "$script")/$jarbase" ]]
then jar="$(readlink -f "$(dirname "$script")/$jarbase")"
else jar="$serverdir/target/$jarbase"
fi
already_pushed=false
: ${push_jar:=false} ${push_script:=true} ${use_yjp=false} ${unbuffer:=false}
: ${loglevel:=} ${dbg:=false} ${attach:=false} ${logconf:=} ${asserts:=false}
if $unbuffer
then unbuffercmd='unbuffer -p'
else unbuffercmd=
fi
if $dbg
then set -x
fi
if $asserts
then JAVAFLAGS=-ea
fi

#
# General utilities.
#

trace() { echo "$@" ; eval "$@" ; }

# Add the given prefix (first arg) to all subsequent words (latter args).
prefix-words() {
  local prefix="$1"
  shift
  for i in "$@"
  do echo "$prefix" "$i"
  done
}

quote(){
  "$(dirname "$script")/quote" "$@"
}

# Retrieve the substring of a string with a given prefix and suffix.
# For example, substr "Hello World!" "ell" "rld" returns "o Wo".
# Everything before the prefix and after the suffix (inclusive)
# is stripped off and the remaining substring is returned.
substr() {
    if [ $# == 3 ]
	then
	nopref="${1#${1%${2}*}${2}}"
	echo "${nopref%${3}*}"
    else
	echo "Usage: substr string prefix suffix"
    fi
}

# Must test connectivity via ssh because we may be firewalled.
wait-connect() {
  local prof="${1%:*}" port="${1#*:}"
  local host="$(ssh-hosts $prof)"
  while ! echo | ssh "$prof" nc -w 1 localhost "$port"
  do sleep 1
  done
}

#
# Java runners.
#

# Launch with yourkit profiler.
java-yjp() {
  if $use_yjp
  then LD_LIBRARY_PATH="${YJP:-$HOME/yjp-8.0.15}/bin/linux-x86-32/" \
       java -agentlib:yjpagent $JAVAFLAGS "$@"
  else java $JAVAFLAGS "$@"
  fi
}

with-attach() {
  if $attach
  then JAVAFLAGS="-agentlib:jdwp=transport=dt_socket,server=y,address=$PROFILER_PORT $JAVAFLAGS" "$@"
  else "$@"
  fi
}

with-logging() {
  if [[ $loglevel ]] ; then
    logconf="
log4j.rootLogger=$loglevel, A1
log4j.logger.org.apache.zookeeper = ERROR
log4j.logger.org.apache.bookkeeper.client.QuorumOpMonitor = ERROR
log4j.logger.org.apache.bookkeeper.proto.BookieClient = ERROR

# A1 is set to be a ConsoleAppender.
log4j.appender.A1=org.apache.log4j.ConsoleAppender

# A1 uses PatternLayout.
log4j.appender.A1.layout=org.apache.log4j.PatternLayout
log4j.appender.A1.layout.ConversionPattern=%d %-4r [%t] %-5p %c %x - %m%n
"
  fi
  if [[ "$logconf" ]] ; then
    mkdir -p /tmp/$USER/logging/
    local logdir="$(mktemp -d -p /tmp/$USER/logging/)"
    echo "$logconf" > "$logdir/log4j.properties"
    CLASSPATH="$logdir:$CLASSPATH" "$@"
  else
    "$@"
  fi
}

try-ulimit() { ulimit -n $((1024**2)) || true ; }
j() { CLASSPATH="$jar" with-logging with-attach java-yjp "$@" ; }
bk() { j org.apache.bookkeeper.proto.BookieServer "$@" ; }
#bk() { j com.yahoo.pubsub.client.benchmark.FakeBookie "$@" ; }
zk() { j org.apache.zookeeper.server.quorum.QuorumPeerMain "$@" ; }
zkc() { j org.apache.zookeeper.ZooKeeperMain -server "$@" ; }
hw() { try-ulimit; j org.apache.hedwig.server.netty.PubSubServer "$@" ; }
hwc() { try-ulimit; hwc-soft "$@" ; }
hwc-soft() { j org.apache.hedwig.client.benchmark.HedwigBenchmark "$@" ; }

ssh-zkc() {
  local server="$1" port="$2"
  ssh "$server" "hedwig/hw.bash zkc localhost:$port"
}

#
# Setup
#

# Get/build the ZK dependencies that must be manually installed and place those
# jars in the current directory.
get-zk() {
  local stagedir="$(pwd)" dstdir="$(pwd)" ver=3.2.0
  local unpacked="$stagedir/zookeeper-$ver/"
  local url="http://archive.apache.org/dist/hadoop/zookeeper/zookeeper-$ver/zookeeper-$ver.tar.gz"

  if [[ ! -d "$unpacked" ]]
  then 
       echo $url
       wget -q -O - "$url" | tar xzf - -C "$stagedir"
  fi
  ant -q -buildfile "$unpacked/build.xml" compile-test
  cp -r "$unpacked/src/java/test/"{config,data}/ "$unpacked/build/testclasses/"
  jar cf zookeeper-test-$ver.jar -C "$(readlink -f "$unpacked/build/testclasses/")" .
  cp "$unpacked/zookeeper-$ver.jar" .
  cp "$unpacked/contrib/bookkeeper/zookeeper-$ver-bookkeeper.jar" bookkeeper-$ver.jar
  jar cf zookeeper-test-$ver-sources.jar -C "$(readlink -f "$unpacked/src/java/test/")" .
  jar cf zookeeper-$ver-sources.jar -C "$(readlink -f "$unpacked/src/java/main/")" .
  jar cf bookkeeper-$ver-sources.jar -C "$(readlink -f "$unpacked/src/contrib/bookkeeper/src/java/")" .
}

get-bk() {
  local svn="$serverdir/../Zookeeper/" svnver=3.3.0-SNAPSHOT
  ant -q -buildfile "$svn/build.xml" compile-test
  ant -q -buildfile "$svn/src/contrib/bookkeeper/build.xml"
  jar cf bookkeeper-$svnver-sources.jar -C "$(readlink -f "$svn/src/contrib/bookkeeper/src/java/")" .
  cp "$svn/build/contrib/bookkeeper/zookeeper-dev-bookkeeper.jar" bookkeeper-$svnver.jar
}

# Install the jars from the current directory, as obtained by get-zk.
# For now, we will use the checked in ZK/BK jars in the server/lib directory.
# When an official ZK/BK release for those changes is done, then we can 
# modify the get-zk function to get the latest code.
install-zk-bk() {
  for pkg in zookeeper zookeeper-test bookkeeper ; do
    local grp="${pkg%-*}" ver=SNAPSHOT
    for srcs in '' -sources
    do trace mvn -q install:install-file -Dfile="$pkg-$ver$srcs.jar" \
        -DgroupId=org.apache.$grp -DartifactId=$pkg -Dpackaging=jar \
        -Dversion=$ver ${srcs:+-Dclassifier=sources}
    done
  done
}

setup-java() {
  # wget 'http://cds.sun.com/is-bin/INTERSHOP.enfinity/WFS/CDS-CDS_Developer-Site/en_US/-/USD/VerifyItem-Start/jdk-7-ea-linux-i586.bin?BundledLineItemUUID=dXBIBe.m0.UAAAEiZUUKrYfz&OrderID=BnZIBe.mencAAAEiU0UKrYfz&ProductID=O29IBe.py.oAAAEhK1kP50GU&FileName=/jdk-7-ea-linux-i586.bin'
  local jdk="$1"
  parscp "$jdk" ^:/tmp/jdk6
  parssh "
    echo yes | /tmp/jdk6 > /tmp/java-install-log &&
    if ! fgrep jdk1.6.0 ~/.bashrc > /dev/null
    then echo 'export PATH=~/jdk1.6.0_14/bin/:\$PATH' >> ~/.bashrc
    fi
  "
}

setup-yjp() {
  local pkg="$1"
  parscp "$pkg" ^:/tmp/yjp.zip
  parssh "
    yes A | unzip -q /tmp/yjp.zip
    if ! fgrep YJP= ~/.bashrc > /dev/null
    then echo 'export YJP=~/yjp-8.0.15/' >> ~/.bashrc
    fi
  "
}

# Usage: setup-bk ZKSERVER ZKSERVER_PORT
#
# Create the /ledgers and /ledgers/available ZK nodes on the given ZK server.
# The bookie servers will register themselves once they are up on ZK but they
# need these nodes to exist first.
setup-bk() {
  local server="$1" port="$2"
  shift 2
  ssh-zkc "$server" "$port" << EOF || true
create /ledgers 0
create /ledgers/available 0
EOF
}

# Get rid of duplicate files in a jar.
strip-jar() {
  local jar="${1:-$jar}" tmpdir=/tmp/$USER/jar
  rm -rf "$tmpdir"
  mkdir -p "$tmpdir"
  (
    cd "$tmpdir"
    jar xf "$jar"
    jar cf "$jar" .
  )
}

# Inspect the current logging level.
get-logging() {
  local jar="${1:-$jar}" tmpdir=/tmp/$USER/jar
  mkdir -p "$tmpdir"
  (
    cd "$tmpdir"
    jar xf "$jar" log4j.properties
    grep rootLogger= log4j.properties
  )
}

# Adjust the log level but without modifying the original source tree or going
# through the full rebuild process.
set-logging() {
  local level="$1" tmpdir=/tmp/$USER/jarlog
  mkdir -p "$tmpdir"
  (
    cd "$tmpdir"
    jar xf "$jar" log4j.properties
    sed -i "s/\(rootLogger\)=[[:alpha:]]\+/\1=$level/" log4j.properties
    jar uf "$jar" log4j.properties
  )
}

#
# General testbed tools.
#

hosts() {
  if [[ ! "$hosts" ]]
  then echo '$hosts not set' 1>&2 ; return 1
  fi
  echo $hosts | sed 's/[[:space:]]\+/\n/g' | sort -u
}

hostargs() { "$@" $hosts ; }
tagssh() {
  local prof="$1"
  shift
  {
    ssh "$prof" "export use_yjp=$use_yjp loglevel=$loglevel asserts=$asserts logconf=$(quote "$logconf"); attach=$attach; $@" 2>&1 &&
    echo -e "$bright_green[SUCCESS]$normal_color" ||
    echo -e "$bright_red[FAILURE: $?]$normal_color" && false
  } | $unbuffercmd sed "s/^/$prof: /g"
}
parssh() { hosts | xargs ${xargs--P0} -i^ "$script" tagssh ^ "$@" ; }
parscp() { hosts | xargs ${xargs--P0} -i^ scp "$@" ; }

# Resolves profile names to actual hostnames according to the user's .ssh/config.
ssh-hosts() {
  python -c "
from __future__ import with_statement
import sys, os
hosts = {}
with file(os.environ['HOME'] + '/.ssh/config') as f:
  for line in f:
    words = line.split('#')[0].split()
    if len(words) == 2:
      if words[0] == 'Host': key = words[1]
      if words[0] == 'HostName': hosts[key] = words[1]
for profile in sys.argv[1:]:
  parts = profile.split(':', 1)
  key, rest = parts[0], ('' if len(parts) == 1 else ':' + parts[1])
  print hosts.get(key, key) + rest,
" "$@"
}

#
# Hedwig testbed tools.
#

# Usage: hosts='wilbur2 wilbur3 wilbur4' ./hw.bash push
push() {
  if ! $already_pushed ; then
    if $push_jar || $push_script
    then parssh 'mkdir -p hedwig/'
    fi
    if $push_jar && $push_script
    then parscp -q "$script" "$jar" ^:hedwig/
    elif $push_jar && ! $push_script
    then parscp -q "$jar" ^:hedwig/
    elif ! $push_jar && $push_script
    then parscp -q "$script" ^:hedwig/
    else :
    fi
    already_pushed=true
  fi
}

# Kill processes and garbage-collect the log4j temp directories.
dkill() { parssh 'pkill java; rm -rf /tmp/$USER/logging/' ; }

# Pass in any argument to get a long listing.
lstatus() {
  for port in 2181 3181 4080 {9874..9878} ; do
    if /usr/sbin/lsof -i tcp:$port 2>&1 | fgrep 'LISTEN' > /dev/null ; then
      if (( $# > 0 )) ; then
        echo "$port:"
        ps u $(/usr/sbin/lsof -t -i tcp:$port) | cat
      else
        echo -n "$port "
      fi
    fi
  done
  (( $# > 0 )) || echo
}

# Pass in any argument to get a long listing.
dstatus() {
  push
  xargs= parssh "hedwig/hw.bash lstatus $@"
}

# See if anything is running on each machine.
tops() {
  xargs= parssh '
    echo
    hostname
    echo =====
    top -b -n 1 | fgrep -A3 COMMAND
  '
}

# Familiarize this machine with the given hosts' keys.
warmup() {
  hosts | xargs -P0 -i^ ssh -o StrictHostKeyChecking=no ^ hostname
}

# Add yourself to nopasswd sudoers for all hosts in $hosts.
setup-sudo() {
  local cmd='sudo su - -c "
  if ! fgrep \"$USER ALL=(yahoo) NOPASSWD:ALL\" /etc/sudoers >& /dev/null
  then echo -e \"$USER ALL=(ALL) ALL\n$USER ALL=(yahoo) NOPASSWD:ALL\" >> /etc/sudoers
  fi"'
  python -c '
import getpass, os, pexpect, sys
pw = getpass.getpass()
for host in os.environ["hosts"].split():
  c = pexpect.spawn(sys.argv[1], [host] + sys.argv[2:])
  i = c.expect(["Password:", pexpect.EOF])
  if i == 0: c.sendline(pw); c.read()
  filtered = filter(lambda x: pw not in x, c.before.split("\n"))
  sys.stdout.write("\n".join(filtered).lstrip("\n"))
  ' ssh "$cmd"
}

setup-limits() {
  ssh '
    if ! sudo fgrep "* hard nofile $((1024**2))" /etc/security/limits.conf >& /dev/null
    then sudo su - -c "echo \"* hard nofile $((1024**2))\" >> /etc/security/limits.conf"
    fi
  '
}

mkhomes() {
  ssh 'sudo mkdir -p /home/yang && sudo chown -R yang /home/yang'
}

#
# Distributed launchers.
#

# Given a hostname (*not* an ssh profile), figure out how to utilize the
# machine's disks for BK.
bk-journal() {
  case "$@" in
    * ) echo '"/d1/$USER/bk/journal"' ;;
  esac
}
bk-ledger() {
  case "$@" in
    * ) echo '"/home/$USER/bk/ledger"' ;;
  esac
}
bk-paths() {
  echo "$(bk-journal "$@") $(bk-ledger "$@")"
}

# Start ZK on the first arg host and BKs on the second argument which is
# a string list of hosts.
start-zk-bks() {
  hosts="$*" push jar
  local zk="$1" abks=( $2 )
  shift
  tagssh $zk "
    rm -rf /tmp/$USER/zk/
    mkdir -p /tmp/$USER/zk/
    cat > /tmp/$USER/zoo.cfg << EOF
tickTime=2000
dataDir=/tmp/$USER/zk/
clientPort=$ZOOKEEPER_PORT
EOF
    hedwig/hw.bash eval 'set -x; zk /tmp/$USER/zoo.cfg'
  " &
  wait-connect $zk:$ZOOKEEPER_PORT
  setup-bk $(ssh-hosts $zk) $ZOOKEEPER_PORT
  hosts="$*" parssh "
    rm -rf $(bk-paths "$1")
    mkdir -p $(bk-paths "$1")
    hedwig/hw.bash eval $(quote "set -x; bk $BOOKKEEPER_PORT $( ssh-hosts $zk ):$ZOOKEEPER_PORT $(bk-paths "$1")")
  " &
  for bk in "${abks[@]}" ; do
    wait-connect $bk:$BOOKKEEPER_PORT
  done
}

# The first argument is a string list of remote region default Hedwig servers
# in a multi-region setup (if any). The second argument is a string list of
# Hedwig hubs to start for this local region. The third argument is the single
# ZooKeeper server host the hubs should connect to.
start-hw() {
  local allhws="$1" ahws=( $2 ) zk="$3"
  if [[ $region ]]
  then regionconf="region=$region"
  fi
  shift
  hosts="$@" push
  for hw in "${ahws[@]}" ; do
    tagssh $hw "
    mkdir -p /tmp/$USER/
    cat > /tmp/$USER/hw.conf << EOF
zk_host=$( ssh-hosts $zk ):$ZOOKEEPER_PORT
regions=$( ssh-hosts $allhws )
server_port=$SERVER_PORT
ssl_server_port=$SSL_SERVER_PORT
ssl_enabled=true
inter_region_ssl_enabled=true
cert_name=/server.p12
password=eUySvp2phM2Wk
$regionconf
$extraconf
EOF
    hedwig/hw.bash eval 'set -x; hw /tmp/$USER/hw.conf'
  " &
    wait-connect $hw:$SERVER_PORT
    wait-connect $hw:$SSL_SERVER_PORT
  done
}

# The arguments are similar to those for start-hw() above.
# The additional 4th argument is a string list of BookKeeper servers to start up.
start-region() {
  local allhws="$1" hws="$2" zk="$3" bks="$4"
  shift
  hosts="$*" push jar
  start-zk-bks "$zk" "$bks"
  start-hw "$allhws" "$hws" "$zk"
}

# Start multiple regions from a file configuration. The format that is expected
# is to have each region on a separate line with the following format:
# region=<Region name>, hub=<list of hub servers>, default=<single hub server>, zk=<single ZK server>, bk=<list of BK servers>
# This will create all of the regions with an all-to-all topology. Each region 
# is connected to the default hub server of every other region.
start-regions() {
  local cfg="$1"
  local regionPref="region=" hubPref=", hub=" defaultPref=", default=" zkPref=", zk=" bkPref=", bk="
  while read line ; do
    local otherhws=
    while read subline ; do
      local profile="$(substr "$subline" "$defaultPref" "$zkPref")"
      if [[ $profile != "$(substr "$line" "$defaultPref" "$zkPref")" ]]
      then otherhws="$otherhws $profile:$SERVER_PORT:$SSL_SERVER_PORT"
      fi
    done < "$cfg"    
    local curRegion="$(substr "$line" "$regionPref" "$hubPref")" 
    local curHub="$(substr "$line" "$hubPref" "$defaultPref")"
    local curZk="$(substr "$line" "$zkPref" "$bkPref")" 
    local curBk="$(substr "$line" "$bkPref" "")"
    hosts="$curHub $curZk $curBk" push jar
    region="$curRegion" start-region "$otherhws" "$curHub" "$curZk" "$curBk" &
  done < "$cfg"
  wait
}

app() {
  local hw="$1" # the server to connect to
  push
  parssh "
    set -o errexit -x
    mkdir -p /tmp/\$USER/
    echo $(quote "default_server_host=$(ssh-hosts "$hw"):$SERVER_PORT:$SSL_SERVER_PORT") > /tmp/\$USER/hwc.conf
    JAVAFLAGS=$(quote "$JAVAFLAGS") hedwig/hw.bash hwc /tmp/\$USER/hwc.conf
  "
}

#
# Experiments.
#

sub-exp() {
  : ${start:=0}
  push
  for sync in ${syncs:-true false} ; do
    for count in ${counts:-1000} ; do
      for npar in ${npars:-1 10 20 30 40 50} ; do
        if (( $npar <= $count )) ; then
          for rep in {1..3} ; do
            echo JAVAFLAGS="-Dmode=sub -Dsynchronous=$sync -Dcount=$count -Dnpar=$npar -Dstart=$start $JAVAFLAGS" app "$@"
            JAVAFLAGS="-Dmode=sub -Dcount=$count -Dnpar=$npar -Dstart=$start $JAVAFLAGS" app "$@" >& ${outfile:-sub/sync-$sync-count-$count-npar-$npar-rep-$rep.out}
            let start+=$count
          done
        fi
      done
    done
  done
}

wait-sub() {
  local zk="$1" topic="$2" subid="$3" region="$4"
  while ! echo "ls /hedwig/$region/topics/$topic/subscribers/$subid" | \
          ssh-zkc "$zk" $ZOOKEEPER_PORT 2>&1 | \
          tee "${dbgfile:-/dev/null}" | \
          grep '^\[\]$' > /dev/null
  do sleep 1
  done
}

# Note: this code waits for subscribers to show up in ZK, so when running this
# multiple times on the same servers, adjust `start` to use a different topic
# each time; otherwise, you'll immediately see the subscribers from last time,
# thus causing the script to not wait for the current session's subscribers.
# Alternatively, adjust recvid.
#
# Params:
#
# recvs: the list of local receivers
# pubs: the list of publishers
# hw: the local hedwig node
# zk: the local zookeeper node (used to wait for receivers to join)
#
# Optional group of params:
#
# rrecv: the remote receiver
# rhw: the remote hedwig node
# rzk: the remote zookeeper node
pub-exp() {
  (( $# >= 4 ))
  local recvs="$1" pubs="$2" hw="$3" zk="$4"
  # Optional remote args.
  if (( $# > 4 ))
  then local remote=true rrecv="$5" rhw="$6" rzk="$7"
  else local remote=false
  fi
  : ${start:=0} ${count:=100000} ${recvid:=0} ${dir="pub"}
  hosts="$recvs $pubs $rrecv" push
  # Convert to arrays.
  local arecvs=( $recvs ) apubs=( $pubs )
  mkdir -p "$dir"

  #rregion="$(ssh $rhw cat /tmp/$USER/hw.conf)"
  region=$hw rregion=$rhw

  # Default to only using all recvs (rather than iterating over subsets).
  for nrecvs in ${nrecvss:-${#arecvs[*]}} ; do
    # Ditto for publishers.
    for npubs in ${npubss:-${#apubs[*]}} ; do
      # Default to only using a single value of npar.
      for npar in ${npars:-100} ; do
        # Default to repeating each trial thrice.
        for rep in $(seq ${nreps:-3}) ; do

          echo -n "nrecvs=$nrecvs npubs=$npubs npar=$npar rep=$rep"

          local outbase="$dir/nrecvs-$nrecvs-npubs-$npubs-npar-$npar-rep-$rep"

          # Skip if already done.
          if [[ -f "$outbase"* ]]
          then echo '...skipped' ; continue
          else echo
          fi

          if $remote ; then
            # Start remote receiver.
            hosts=$rrecv JAVAFLAGS="-Dmode=recv -DsubId=recv-$recvid -Dstart=$start -Dcount=$((count/npubs*npubs)) $JAVAFLAGS" app "$rhw" >& "${outfile:-$outbase-rrecv.out}" &
          fi

          # Start all receivers.
          for ((irecv = 0; irecv < nrecvs; irecv++)) ; do
            hosts="${arecvs[$irecv]}" JAVAFLAGS="-Dmode=recv -DsubId=recv-$((recvid+irecv)) -Dstart=$start -Dcount=$((count/npubs*npubs)) $JAVAFLAGS" app "$hw" >& "${outfile:-$outbase-recv-$irecv.out}" &
          done

          # Wait till subscribed.
          sleep 1
          for ((irecv = 0; irecv < nrecvs; irecv++))
          do wait-sub $zk topic-$start recv-$((recvid+irecv)) $region
          done
          if $remote ; then
            # Wait till remote subscribed.
            wait-sub $rzk topic-$start recv-$recvid $rregion
            # Wait till cross-region subscribed, since default is async subs.
            # This should only happen once.
            wait-sub $zk topic-$start hub-$rregion $region
          fi

          # Launch all publishers.
          for ((ipub = 0; ipub < npubs; ipub++)) ; do
            hosts="${apubs[$ipub]}" JAVAFLAGS="-Dmode=pub -Dnpar=$npar -Dstart=$start -Dcount=$((count/npubs)) $JAVAFLAGS" app "$hw" >& "${outfile:-$outbase-pub-$ipub.out}" &
          done

          # Wait for everyone to terminate.
          wait

          # To avoid reusing the same subscriber ID.
          let recvid+=$nrecvs

        done
      done
    done
  done
}

pub-exps() {
  local pool="$1" hw="$2" zk="$3" rrecv="$4" rhw="$5" rzk="$6"
  local apool=( $pool ) npool=${#apool[*]}
  echo $apool $npool
  : ${start:=0}
  hosts="$pool $rrecv $rhw" push

  quote start=$start npars="${npars:-20 40 60 80 100}" pub-exp ${apool[0]} ${apool[1]} "$hw" "$zk" $rrecv $rhw $rzk
  start=$start npars="${npars:-20 40 60 80 100}" pub-exp ${apool[0]} ${apool[1]} "$hw" "$zk" $rrecv $rhw $rzk

  pubs=${apool[0]}
  for ((i = 1; i < npool; i++))
  do recvs="${recvs:-} ${apool[$i]}"
  done
  quote start=$((start+1)) nrecvss="$( seq -s' ' $(( npool - 1 )) )" pub-exp "$recvs" "$pubs" "$hw" "$zk" $rrecv $rhw $rzk
  start=$((start+1)) nrecvss="$( seq -s' ' $(( npool - 1 )) )" pub-exp "$recvs" "$pubs" "$hw" "$zk" $rrecv $rhw $rzk

  recvs=${apool[0]}
  for ((i = 1; i < npool; i++))
  do pubs="${pubs:-} ${apool[$i]}"
  done
  quote start=$((start+2)) npubss="$( seq -s' ' $(( npool - 1 )) )" pub-exp "$recvs" "$pubs" "$hw" "$zk" $rrecv $rhw $rzk
  start=$((start+2)) npubss="$( seq -s' ' $(( npool - 1 )) )" pub-exp "$recvs" "$pubs" "$hw" "$zk" $rrecv $rhw $rzk
}

#
# Post-processing
#

# Consolidate to a directory.
sub-agg() {
  local dst="$1"
  mkdir -p "$dst"
  for s in 0 1 ; do
    for i in sync$s/*.out
    do cp "$i" "$dst/sync-$s-$(basename "$i")"
    done
  done
}

if [[ "$(type -t "$cmd")" == function ]]
then "$cmd" "$@"
else "$@"
fi

# vim: et sw=2 ts=2
