#!/usr/bin/env bash
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.


#set -x

### Setup some variables.
### GIT_COMMIT and BUILD_URL are set by Hudson if it is run by patch process
### Read variables from properties file
. `dirname $0`/test-patch.properties

###############################################################################
parseArgs() {
  case "$1" in
    QABUILD)
      ### Set QABUILD to true to indicate that this script is being run by Hudson
      QABUILD=true
      if [[ $# != 14 ]] ; then
        echo "ERROR: usage $0 QABUILD <PATCH_DIR> <PS_CMD> <WGET_CMD> <JIRACLI> <GIT_CMD> <GREP_CMD> <PATCH_CMD> <FINDBUGS_HOME> <FORREST_HOME> <WORKSPACE_BASEDIR> <JIRA_PASSWD> <JAVA5_HOME> <CURL_CMD>"
        cleanupAndExit 0
      fi
      PATCH_DIR=$2
      PS=$3
      WGET=$4
      JIRACLI=$5
      GIT=$6
      GREP=$7
      PATCH=$8
      FINDBUGS_HOME=$9
      FORREST_HOME=${10}
      BASEDIR=${11}
      JIRA_PASSWD=${12}
      JAVA5_HOME=${13}
      CURL=${14}
      if [ ! -e "$PATCH_DIR" ] ; then
        mkdir -p $PATCH_DIR
      fi

      ## Obtain PR number and title
      PULLREQUEST_ID=${GIT_PR_NUMBER}
      PULLREQUEST_TITLE="${GIT_PR_TITLE}"

      ## Extract jira number from PR title
      local prefix=${PULLREQUEST_TITLE%ZOOKEEPER\-[0-9]*}
      local noprefix=${PULLREQUEST_TITLE#$prefix}
      local regex='\(ZOOKEEPER-.[0-9]*\)'
      defect=$(expr "$noprefix" : ${regex})

      echo "Pull request id: ${PULLREQUEST_ID}"
      echo "Pull request title: ${PULLREQUEST_TITLE}"
      echo "Defect number: ${defect}"

      JIRA_COMMENT="GitHub Pull Request ${PULLREQUEST_NUMBER} Build
      "
      ;;
    DEVELOPER)
      ### Set QABUILD to false to indicate that this script is being run by a developer
      QABUILD=false
      if [[ $# != 10 ]] ; then
        echo "ERROR: usage $0 DEVELOPER <GIT_PR_URL> <SCRATCH_DIR> <GIT_CMD> <GREP_CMD> <PATCH_CMD> <FINDBUGS_HOME> <FORREST_HOME> <WORKSPACE_BASEDIR> <JAVA5_HOME>"
        cleanupAndExit 0
      fi
      PATCH_DIR=$3
      PATCH_FILE=${PATCH_DIR}/patch
      curl -L $2.diff > ${PATCH_FILE}
      ### PATCH_FILE contains the location of the patchfile
      if [[ ! -e "$PATCH_FILE" ]] ; then
        echo "Unable to locate the patch file $PATCH_FILE"
        cleanupAndExit 0
      fi
      ### Check if $PATCH_DIR exists. If it does not exist, create a new directory
      if [[ ! -e "$PATCH_DIR" ]] ; then
	mkdir "$PATCH_DIR"
	if [[ $? == 0 ]] ; then
	  echo "$PATCH_DIR has been created"
	else
	  echo "Unable to create $PATCH_DIR"
	  cleanupAndExit 0
	fi
      fi
      GIT=$4
      GREP=$5
      PATCH=$6
      FINDBUGS_HOME=$7
      FORREST_HOME=$8
      BASEDIR=$9
      JAVA5_HOME=${10}
      ### Obtain the patch filename to append it to the version number
      local subject=`grep "Subject:" ${PATCH_FILE}`
      local length=`expr match ${subject} ZOOKEEPER-[0-9]*`
      local position=`expr index ${subject} ZOOKEEPER-`
      defect=${${subject:$position:$length}#ZOOKEEPER-}
      ;;
    *)
      echo "ERROR: usage $0 QABUILD [args] | DEVELOPER [args]"
      cleanupAndExit 0
      ;;
  esac
}

###############################################################################
checkout () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Testing patch for pull request ${PULLREQUEST_ID}."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  ### When run by a developer, if the workspace contains modifications, do not continue
  # Ref http://stackoverflow.com/a/2659808 for details on checking dirty status
  ${GIT} diff-index --quiet HEAD
  if [[ $? -ne 0 ]] ; then
    uncommitted=`${GIT} diff --name-only HEAD`
    uncommitted="You have the following files with uncommitted changes:${NEWLINE}${uncommitted}"
  fi
  untracked="$(${GIT} ls-files --exclude-standard --others)" && test -z "${untracked}"
  if [[ $? -ne 0 ]] ; then
    untracked="You have untracked and unignored files:${NEWLINE}${untracked}"
  fi

  if [[ $QABUILD == "false" ]] ; then
    if [[ $uncommitted || $untracked ]] ; then
      echo "ERROR: can't run in a workspace that contains the following modifications"
      echo ""
      echo "${uncommitted}"
      echo ""
      echo "${untracked}"
      cleanupAndExit 1
    fi
  else
    # I don't believe we need to do anything here - the jenkins job will
    # cleanup the environment for us ("cleanup before checkout" action)
    # on the precommit jenkins job
    echo
  fi
  return $?
}

###############################################################################
setup () {
  ### exit if warnings are NOT defined in the properties file
  if [ -z "$OK_FINDBUGS_WARNINGS" ] || [[ -z "$OK_JAVADOC_WARNINGS" ]] || [[ -z $OK_RELEASEAUDIT_WARNINGS ]]; then
    echo "Please define the following properties in test-patch.properties file"
	 echo  "OK_FINDBUGS_WARNINGS"
	 echo  "OK_RELEASEAUDIT_WARNINGS"
	 echo  "OK_JAVADOC_WARNINGS"
    cleanupAndExit 1
  fi
  ### get pull request diff
  ${CURL} -L ${GIT_PR_URL}.diff > $PATCH_DIR/patch

  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo " Pre-build trunk to verify trunk stability and javac warnings"
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant  -Djavac.args="-Xlint -Xmaxwarns 1000" -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= clean tar > $PATCH_DIR/trunkJavacWarnings.txt 2>&1"
 $ANT_HOME/bin/ant -Djavac.args="-Xlint -Xmaxwarns 1000" -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= clean tar > $PATCH_DIR/trunkJavacWarnings.txt 2>&1
  if [[ $? != 0 ]] ; then
    echo "Trunk compilation is broken?"
    cleanupAndExit 1
  fi
}

###############################################################################
### Check for @author tags in the patch
checkAuthor () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Checking there are no @author tags in the patch."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  authorTags=`$GREP -c -i '@author' $PATCH_DIR/patch`
  echo "There appear to be $authorTags @author tags in the patch."
  if [[ $authorTags != 0 ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 @author.  The patch appears to contain $authorTags @author tags which the Zookeeper community has agreed to not allow in code contributions."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 @author.  The patch does not contain any @author tags."
  return 0
}

###############################################################################
### Check for tests in the patch
checkTests () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Checking there are new or changed tests in the patch."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  testReferences=`$GREP -c -i '/test' $PATCH_DIR/patch`
  echo "There appear to be $testReferences test files referenced in the patch."
  if [[ $testReferences == 0 ]] ; then
    if [[ $QABUILD == "true" ]] ; then
      patchIsDoc=`$GREP -c -i 'title="documentation' $PATCH_DIR/jira`
      if [[ $patchIsDoc != 0 ]] ; then
        echo "The patch appears to be a documentation patch that doesn't require tests."
        JIRA_COMMENT="$JIRA_COMMENT

    +0 tests included.  The patch appears to be a documentation patch that doesn't require tests."
        return 0
      fi
    fi
    JIRA_COMMENT="$JIRA_COMMENT

    -1 tests included.  The patch doesn't appear to include any new or modified tests.
                        Please justify why no new tests are needed for this patch.
                        Also please list what manual steps were performed to verify this patch."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 tests included.  The patch appears to include $testReferences new or modified tests."
  return 0
}

###############################################################################
### Check there are no javadoc warnings
checkJavadocWarnings () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Determining number of patched javadoc warnings."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant -DZookeeperPatchProcess= clean javadoc | tee $PATCH_DIR/patchJavadocWarnings.txt"
  $ANT_HOME/bin/ant -DZookeeperPatchProcess= clean javadoc | tee $PATCH_DIR/patchJavadocWarnings.txt
  javadocWarnings=`$GREP -o '\[javadoc\] [0-9]* warning' $PATCH_DIR/patchJavadocWarnings.txt | awk '{total += $2} END {print total}'`
  echo ""
  echo ""
  echo "There appear to be $javadocWarnings javadoc warnings generated by the patched build."

  ### if current warnings greater than OK_JAVADOC_WARNINGS
  if [[ $javadocWarnings > $OK_JAVADOC_WARNINGS ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 javadoc.  The javadoc tool appears to have generated `expr $(($javadocWarnings-$OK_JAVADOC_WARNINGS))` warning messages."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 javadoc.  The javadoc tool did not generate any warning messages."
  return 0
}

###############################################################################
### Check there are no changes in the number of Javac warnings
checkJavacWarnings () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Determining number of patched javac warnings."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant -Djavac.args="-Xlint -Xmaxwarns 1000" -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= clean tar > $PATCH_DIR/patchJavacWarnings.txt 2>&1"
  $ANT_HOME/bin/ant -Djavac.args="-Xlint -Xmaxwarns 1000" -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= clean tar > $PATCH_DIR/patchJavacWarnings.txt 2>&1
  if [[ $? != 0 ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 javac.  The patch appears to cause tar ant target to fail."
    return 1
  fi
  ### Compare trunk and patch javac warning numbers
  if [[ -f $PATCH_DIR/patchJavacWarnings.txt ]] ; then
    trunkJavacWarnings=`$GREP -o '\[javac\] [0-9]* warning' $PATCH_DIR/trunkJavacWarnings.txt | awk '{total += $2} END {print total}'`
    patchJavacWarnings=`$GREP -o '\[javac\] [0-9]* warning' $PATCH_DIR/patchJavacWarnings.txt | awk '{total += $2} END {print total}'`
    echo "There appear to be $trunkJavacWarnings javac compiler warnings before the patch and $patchJavacWarnings javac compiler warnings after applying the patch."
    if [[ $patchJavacWarnings != "" && $trunkJavacWarnings != "" ]] ; then
      if [[ $patchJavacWarnings -gt $trunkJavacWarnings ]] ; then
        JIRA_COMMENT="$JIRA_COMMENT

    -1 javac.  The applied patch generated $patchJavacWarnings javac compiler warnings (more than the trunk's current $trunkJavacWarnings warnings)."
        return 1
      fi
    fi
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 javac.  The applied patch does not increase the total number of javac compiler warnings."
  return 0
}

###############################################################################
### Check there are no changes in the number of release audit (RAT) warnings
checkReleaseAuditWarnings () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Determining number of patched release audit warnings."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= releaseaudit > $PATCH_DIR/patchReleaseAuditWarnings.txt 2>&1"
  $ANT_HOME/bin/ant -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= releaseaudit > $PATCH_DIR/patchReleaseAuditWarnings.txt 2>&1

  ### Compare trunk and patch release audit warning numbers
  if [[ -f $PATCH_DIR/patchReleaseAuditWarnings.txt ]] ; then
    patchReleaseAuditWarnings=`$GREP -c '\!?????' $PATCH_DIR/patchReleaseAuditWarnings.txt`
    echo ""
    echo ""
    echo "There appear to be $OK_RELEASEAUDIT_WARNINGS release audit warnings before the patch and $patchReleaseAuditWarnings release audit warnings after applying the patch."
    if [[ $patchReleaseAuditWarnings != "" && $OK_RELEASEAUDIT_WARNINGS != "" ]] ; then
      if [[ $patchReleaseAuditWarnings -gt $OK_RELEASEAUDIT_WARNINGS ]] ; then
        JIRA_COMMENT="$JIRA_COMMENT

    -1 release audit.  The applied patch generated $patchReleaseAuditWarnings release audit warnings (more than the trunk's current $OK_RELEASEAUDIT_WARNINGS warnings)."
        $GREP '\!?????' $PATCH_DIR/patchReleaseAuditWarnings.txt > $PATCH_DIR/patchReleaseAuditProblems.txt
        echo "Lines that start with ????? in the release audit report indicate files that do not have an Apache license header." >> $PATCH_DIR/patchReleaseAuditProblems.txt
        JIRA_COMMENT_FOOTER="Release audit warnings: $BUILD_URL/artifact/trunk/patchprocess/patchReleaseAuditProblems.txt
$JIRA_COMMENT_FOOTER"
        return 1
      fi
    fi
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 release audit.  The applied patch does not increase the total number of release audit warnings."
  return 0
}

###############################################################################
### Check there are no changes in the number of Checkstyle warnings
checkStyle () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Determining number of patched checkstyle warnings."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "THIS IS NOT IMPLEMENTED YET"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant -DZookeeperPatchProcess= checkstyle"
  $ANT_HOME/bin/ant -DZookeeperPatchProcess= checkstyle
  JIRA_COMMENT_FOOTER="Checkstyle results: $BUILD_URL/artifact/trunk/build/test/checkstyle-errors.html
$JIRA_COMMENT_FOOTER"
  ### TODO: calculate actual patchStyleErrors
#  patchStyleErrors=0
#  if [[ $patchStyleErrors != 0 ]] ; then
#    JIRA_COMMENT="$JIRA_COMMENT
#
#    -1 checkstyle.  The patch generated $patchStyleErrors code style errors."
#    return 1
#  fi
#  JIRA_COMMENT="$JIRA_COMMENT
#
#    +1 checkstyle.  The patch generated 0 code style errors."
  return 0
}

###############################################################################
### Check there are no changes in the number of Findbugs warnings
checkFindbugsWarnings () {
  findbugs_version=`${FINDBUGS_HOME}/bin/findbugs -version`
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Determining number of patched Findbugs warnings."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  echo "$ANT_HOME/bin/ant -Dfindbugs.home=$FINDBUGS_HOME -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= findbugs"
  $ANT_HOME/bin/ant -Dfindbugs.home=$FINDBUGS_HOME -Djava5.home=${JAVA5_HOME} -Dforrest.home=${FORREST_HOME} -DZookeeperPatchProcess= findbugs
  if [ $? != 0 ] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 findbugs.  The patch appears to cause Findbugs (version ${findbugs_version}) to fail."
    return 1
  fi
JIRA_COMMENT_FOOTER="Findbugs warnings: $BUILD_URL/artifact/trunk/build/test/findbugs/newPatchFindbugsWarnings.html
$JIRA_COMMENT_FOOTER"
  cp $BASEDIR/build/test/findbugs/*.xml $PATCH_DIR/patchFindbugsWarnings.xml
  $FINDBUGS_HOME/bin/setBugDatabaseInfo -timestamp "01/01/2000" \
    $PATCH_DIR/patchFindbugsWarnings.xml \
    $PATCH_DIR/patchFindbugsWarnings.xml
  findbugsWarnings=`$FINDBUGS_HOME/bin/filterBugs -first "01/01/2000" $PATCH_DIR/patchFindbugsWarnings.xml \
    $BASEDIR/build/test/findbugs/newPatchFindbugsWarnings.xml | /usr/bin/awk '{print $1}'`
  $FINDBUGS_HOME/bin/convertXmlToText -html \
    $BASEDIR/build/test/findbugs/newPatchFindbugsWarnings.xml \
    $BASEDIR/build/test/findbugs/newPatchFindbugsWarnings.html
  cp $BASEDIR/build/test/findbugs/newPatchFindbugsWarnings.html $PATCH_DIR/newPatchFindbugsWarnings.html
  cp $BASEDIR/build/test/findbugs/newPatchFindbugsWarnings.xml $PATCH_DIR/newPatchFindbugsWarnings.xml

  ### if current warnings greater than OK_FINDBUGS_WARNINGS
  if [[ $findbugsWarnings > $OK_FINDBUGS_WARNINGS ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 findbugs.  The patch appears to introduce `expr $(($findbugsWarnings-$OK_FINDBUGS_WARNINGS))` new Findbugs (version ${findbugs_version}) warnings."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 findbugs.  The patch does not introduce any new Findbugs (version ${findbugs_version}) warnings."
  return 0
}

###############################################################################
### Run the test-core target
runCoreTests () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Running core tests."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""

  ### Kill any rogue build processes from the last attempt
  $PS auxwww | $GREP ZookeeperPatchProcess | /usr/bin/nawk '{print $2}' | /usr/bin/xargs -t -I {} /bin/kill -9 {} > /dev/null

  echo "$ANT_HOME/bin/ant -DZookeeperPatchProcess= -Dtest.junit.output.format=xml -Dtest.output=yes -Dtest.junit.threads=8 -Dcompile.c++=yes -Dforrest.home=$FORREST_HOME -Djava5.home=$JAVA5_HOME test-core"
  $ANT_HOME/bin/ant -DZookeeperPatchProcess= -Dtest.junit.output.format=xml -Dtest.output=yes -Dtest.junit.threads=8 -Dcompile.c++=yes -Dforrest.home=$FORREST_HOME -Djava5.home=$JAVA5_HOME test-core
  if [[ $? != 0 ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 core tests.  The patch failed core unit tests."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 core tests.  The patch passed core unit tests."
  return 0
}

###############################################################################
### Run the test-contrib target
runContribTests () {
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Running contrib tests."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""

  ### Kill any rogue build processes from the last attempt
  $PS auxwww | $GREP ZookeeperPatchProcess | /usr/bin/nawk '{print $2}' | /usr/bin/xargs -t -I {} /bin/kill -9 {} > /dev/null

  echo "$ANT_HOME/bin/ant -DZookeeperPatchProcess= -Dtest.junit.output.format=xml -Dtest.output=yes test-contrib"
  $ANT_HOME/bin/ant -DZookeeperPatchProcess= -Dtest.junit.output.format=xml -Dtest.output=yes test-contrib
  if [[ $? != 0 ]] ; then
    JIRA_COMMENT="$JIRA_COMMENT

    -1 contrib tests.  The patch failed contrib unit tests."
    return 1
  fi
  JIRA_COMMENT="$JIRA_COMMENT

    +1 contrib tests.  The patch passed contrib unit tests."
  return 0
}

###############################################################################
### Submit a comment to the defect's Jira
submitJiraComment () {
  local result=$1
  ### Do not output the value of JIRA_COMMENT_FOOTER when run by a developer
  if [[  $QABUILD == "false" ]] ; then
    JIRA_COMMENT_FOOTER=""
  fi
  if [[ $result == 0 ]] ; then
    comment="+1 overall.  $JIRA_COMMENT

$JIRA_COMMENT_FOOTER"
  else
    comment="-1 overall.  $JIRA_COMMENT

$JIRA_COMMENT_FOOTER"
  fi
  ### Output the test result to the console
  echo "



$comment"

  if [[ $QABUILD == "true" ]] ; then
    echo ""
    echo ""
    echo "======================================================================"
    echo "======================================================================"
    echo "    Adding comment to Jira."
    echo "======================================================================"
    echo "======================================================================"
    echo ""
    echo ""
    ### Update Jira with a comment
    export USER=jenkins
    $JIRACLI -s https://issues.apache.org/jira -a addcomment -u hadoopqa -p $JIRA_PASSWD --comment "$comment" --issue $defect
    $JIRACLI -s https://issues.apache.org/jira -a logout -u hadoopqa -p $JIRA_PASSWD
  fi
}

###############################################################################
### Cleanup files
cleanupAndExit () {
  local result=$1
  if [[ $QABUILD == "true" ]] ; then
    if [ -e "$PATCH_DIR" ] ; then
      mv $PATCH_DIR $BASEDIR
    fi
  fi
  echo ""
  echo ""
  echo "======================================================================"
  echo "======================================================================"
  echo "    Finished build."
  echo "======================================================================"
  echo "======================================================================"
  echo ""
  echo ""
  exit $result
}

###############################################################################
###############################################################################
###############################################################################

JIRA_COMMENT=""
JIRA_COMMENT_FOOTER="Console output: $BUILD_URL/console

This message is automatically generated."

### Check if arguments to the script have been specified properly or not
echo "----- Going to parser args -----"
parseArgs $@
cd $BASEDIR

echo "----- Parsed args, going to checkout -----"
checkout
RESULT=$?
if [[ $QABUILD == "true" ]] ; then
  if [[ $RESULT != 0 ]] ; then
    exit 100
  fi
fi
setup
checkAuthor
(( RESULT = RESULT + $? ))

checkTests
checkTestsResult=$?
(( RESULT = RESULT + $checkTestsResult ))
if [[ $checkTestsResult != 0 ]] ; then
  submitJiraComment 1
  cleanupAndExit 1
fi
checkJavadocWarnings
(( RESULT = RESULT + $? ))
checkJavacWarnings
(( RESULT = RESULT + $? ))
### Checkstyle not implemented yet
#checkStyle
#(( RESULT = RESULT + $? ))
checkFindbugsWarnings
(( RESULT = RESULT + $? ))
checkReleaseAuditWarnings
(( RESULT = RESULT + $? ))
### Do not call these when run by a developer
if [[ $QABUILD == "true" ]] ; then
  runCoreTests
  (( RESULT = RESULT + $? ))
  runContribTests
  (( RESULT = RESULT + $? ))
fi
JIRA_COMMENT_FOOTER="Test results: $BUILD_URL/testReport/
$JIRA_COMMENT_FOOTER"

submitJiraComment $RESULT
cleanupAndExit $RESULT
