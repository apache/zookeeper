#!/usr/bin/env python
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Script which checks Java API compatibility between two revisions of the
# Java client.
#
# Based on the compatibility checker from the HBase project, but ported to
# Python for better readability.

# Lifted from Kudu: https://github.com/apache/kudu/blob/master/build-support/check_compatibility.py

import logging
import optparse
import os
import shutil
import subprocess
import sys

JAVA_ACC_GIT_URL = "https://github.com/lvc/japi-compliance-checker.git"

# The annotations for what we consider our public API.
PUBLIC_ANNOTATIONS = ["org.apache.yetus.audience.InterfaceAudience.LimitedPrivate",
                      "org.apache.yetus.audience.InterfaceAudience.Public"]

# Various relative paths
PATH_TO_REPO_DIR = "../../../../"
PATH_TO_BUILD_DIR = PATH_TO_REPO_DIR + "build/compat-check"
PATH_TO_JACC_DIR = PATH_TO_REPO_DIR + "build/jacc"

def check_output(*popenargs, **kwargs):
    # r"""Run command with arguments and return its output as a byte string.
    # Backported from Python 2.7 as it's implemented as pure python on stdlib.
    # >>> check_output(['/usr/bin/python', '--version'])
    # Python 2.6.2
    # """
    process = subprocess.Popen(stdout=subprocess.PIPE, *popenargs, **kwargs)
    output, unused_err = process.communicate()
    retcode = process.poll()
    if retcode:
        cmd = kwargs.get("args")
        if cmd is None:
            cmd = popenargs[0]
        error = subprocess.CalledProcessError(retcode, cmd)
        error.output = output
        raise error
    return output

def get_repo_dir():
    """ Return the path to the top of the repo. """
    dirname, _ = os.path.split(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(dirname, PATH_TO_REPO_DIR))


def get_scratch_dir():
    """ Return the path to the scratch dir that we build within. """
    dirname, _ = os.path.split(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(dirname, PATH_TO_BUILD_DIR))


def get_java_acc_dir():
    dirname, _ = os.path.split(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(dirname, PATH_TO_JACC_DIR))


def clean_scratch_dir(scratch_dir):
    """ Clean up and re-create the scratch directory. """
    if os.path.exists(scratch_dir):
        logging.info("Removing scratch dir %s...", scratch_dir)
        shutil.rmtree(scratch_dir)
    logging.info("Creating empty scratch dir %s...", scratch_dir)
    os.makedirs(scratch_dir)


def checkout_tree(rev, path):
    """ Check out the Java source tree for the given revision into the given path. """
    logging.info("Checking out %s in %s", rev, path)
    os.makedirs(path)
    # Extract java source
    subprocess.check_call(["bash", '-o', 'pipefail', "-c",
                           ("git archive --format=tar %s | " +
                            "tar -C \"%s\" -xf -") % (rev, path)],
                          cwd=get_repo_dir())


def get_git_hash(revname):
    """ Convert 'revname' to its SHA-1 hash. """
    return check_output(["git", "rev-parse", revname],
                        cwd=get_repo_dir()).strip()


def build_tree(path):
    """ Run the Java build within 'path'. """
    logging.info("Building in %s...", path)
    subprocess.check_call(["ant", "jar"], cwd=path)


def checkout_java_acc(force):
    """
    Check out the Java API Compliance Checker. If 'force' is true, will re-download even if the
    directory exists.
    """
    acc_dir = get_java_acc_dir()
    if os.path.exists(acc_dir):
        logging.info("Java JAVA_ACC is already downloaded.")
        if not force:
            return
        logging.info("Forcing re-download.")
        shutil.rmtree(acc_dir)
    logging.info("Checking out Java JAVA_ACC...")
    subprocess.check_call(["git", "clone", "-b", "2.1", "--single-branch", "--depth=1", JAVA_ACC_GIT_URL, acc_dir])


def find_client_jars(path):
    """ Return a list of jars within 'path' to be checked for compatibility. """
    return check_output(["find", path, "-name", "zookeeper*.jar"]).rstrip('\n')


def run_java_acc(src_name, src, dst_name, dst):
    """ Run the compliance checker to compare 'src' and 'dst'. """
    src_jar = find_client_jars(src)
    dst_jar = find_client_jars(dst)
    logging.info("Will check compatibility between original jars:\n%s\n" +
                 "and new jars:\n%s",
                 src_jar, dst_jar)

    annotations_path = os.path.join(get_scratch_dir(), "annotations.txt")
    with file(annotations_path, "w") as f:
        for ann in PUBLIC_ANNOTATIONS:
            print >>f,  ann

    java_acc_path = os.path.join(get_java_acc_dir(), "japi-compliance-checker.pl")

    out_path = os.path.join(get_scratch_dir(), "report.html")
    subprocess.check_call(["perl", java_acc_path,
                           "-lib", "ZooKeeper",
                           "-v1", src_name,
                           "-v2", dst_name,
                           "-d1", src_jar,
                           "-d2", dst_jar,
                           "-annotations-list", annotations_path,
                           "-report-path", out_path])


def main(argv):
    logging.basicConfig(level=logging.INFO)
    parser = optparse.OptionParser(
        usage="usage: %prog SRC..[DST]")
    parser.add_option("-f", "--force-download", dest="force_download_deps",
                      help=("Download dependencies (i.e. Java JAVA_ACC) even if they are " +
                            "already present"))
    opts, args = parser.parse_args()

    if len(args) != 1:
        parser.error("no src/dst revision specified")
        sys.exit(1)

    src_rev, dst_rev = args[0].split("..", 1)
    if dst_rev == "":
        dst_rev = "HEAD"
    src_rev = get_git_hash(src_rev)
    dst_rev = get_git_hash(dst_rev)

    logging.info("Source revision: %s", src_rev)
    logging.info("Destination revision: %s", dst_rev)

    # Download deps.
    checkout_java_acc(opts.force_download_deps)

    # Set up the build.
    scratch_dir = get_scratch_dir()
    clean_scratch_dir(scratch_dir)

    # Check out the src and dst source trees.
    src_dir = os.path.join(scratch_dir, "src")
    dst_dir = os.path.join(scratch_dir, "dst")
    checkout_tree(src_rev, src_dir)
    checkout_tree(dst_rev, dst_dir)

    # Run the build in each.
    build_tree(src_dir)
    build_tree(dst_dir)

    run_java_acc(src_rev, src_dir + "/build",
                 dst_rev, dst_dir + "/build")


if __name__ == "__main__":
    main(sys.argv)