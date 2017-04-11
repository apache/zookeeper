#!/usr/bin/env python
##
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

# pylint: disable=invalid-name
# To disable 'invalid constant name' warnings.
# pylint: disable=import-error
# Testing environment may not have all dependencies.

"""
This script uses Jenkins REST api to collect test results of given builds and generate test report.
It is primarily used to monitor build health and flaky tests across different builds.
Print help: zk-unittest-reporter.py -h
"""

import argparse
import logging
import os
import time
import re
import requests
from collections import OrderedDict
from jinja2 import Template

# If any of these strings appear in the console output, it's a build one should probably ignore
# for analyzing failed/hanging tests.
BAD_RUN_STRINGS = [
    "Slave went offline during the build",  # Machine went down, can't do anything about it.
    "The forked VM terminated without properly saying goodbye",  # JVM crashed.
]

PATTERN_RUNNING_TEST = re.compile('(.*) RUNNING TEST METHOD (.*)')
PATTERN_FAILED_TEST = re.compile('(.*)- FAILED (.*)')
PATTERN_SUCCEED_TEST = re.compile('(.*)- SUCCEEDED (.*)')

logging.basicConfig()
logger = logging.getLogger(__name__)

# Set of timeout/failed tests across all given urls.
all_timeout_tests = set()
all_failed_tests = set()
all_hanging_tests = set()
# Contains { <url> : { <bad_test> : { 'all': [<build ids>], 'failed': [<build ids>],
#                                     'timeout': [<build ids>], 'hanging': [<builds ids>] } } }
url_to_bad_test_results = OrderedDict()


def classify_tests(build_url):
    response = requests.get(build_url)
    if response.status_code != 200:
        print "Error getting consoleText. Response = {} {}".format(
            response.status_code, response.reason)
        return

    all_tests_set = set()
    failed_tests_set = set()

    print len(response.content)

    for line in response.content.splitlines():
        ans = PATTERN_RUNNING_TEST.match(line)
        if ans:

            test_case = ans.group(2)
            if test_case in all_tests_set:
                """print ("ERROR! Multiple tests with same name '{}'. Might get wrong results "
                       "for this test.".format(test_case))
                """
            else:
                all_tests_set.add(test_case)
        ans = PATTERN_FAILED_TEST.match(line)
        if ans:
            test_case = ans.group(2)
            failed_tests_set.add(test_case)
        for bad_string in BAD_RUN_STRINGS:
            if re.match(".*" + bad_string + ".*", line):
                print "Bad string found in build:\n > {}".format(line)
                return
    print "Result > total tests: {:4}   failed : {:4}".format(
        len(all_tests_set), len(failed_tests_set))
    return [all_tests_set, failed_tests_set]


def generate_report(build_url):
    """
    Given build_url of an executed build, analyzes its console text, and returns
    [list of all tests, list of failed tests].
    Returns None if can't get console text or if there is any other error.
    """
    logger.info("Analyzing %s", build_url)
    response = requests.get(build_url + "/api/json").json()
    if response["building"]:
        logger.info("Skipping this build since it is in progress.")
        return {}
    build_result = classify_tests(build_url + "/consoleText")
    if not build_result:
        logger.info("Ignoring build %s", build_url)
        return
    return build_result


def parse_cli_args(cli_args):
    job_urls = cli_args.urls
    excluded_builds_arg = cli_args.excluded_builds
    max_builds_arg = cli_args.max_builds
    if excluded_builds_arg is not None and len(excluded_builds_arg) != len(job_urls):
        raise Exception("Number of --excluded-builds arguments should be same as that of --urls "
                        "since values are matched.")
    if max_builds_arg is not None and len(max_builds_arg) != len(job_urls):
        raise Exception("Number of --max-builds arguments should be same as that of --urls "
                        "since values are matched.")
    final_expanded_urls = []
    for (i, job_url) in enumerate(job_urls):
        max_builds = 10000  # Some high number
        if max_builds_arg is not None and max_builds_arg[i] != 0:
            max_builds = int(max_builds_arg[i])
        excluded_builds = []
        if excluded_builds_arg is not None and excluded_builds_arg[i] != "None":
            excluded_builds = [int(x) for x in excluded_builds_arg[i].split(",")]
        response = requests.get(job_url + "/api/json").json()
        if response.has_key("activeConfigurations"):
            for config in response["activeConfigurations"]:
                final_expanded_urls.append({'url':config["url"], 'max_builds': max_builds,
                                            'excludes': excluded_builds})
        else:
            final_expanded_urls.append({'url':job_url, 'max_builds': max_builds,
                                        'excludes': excluded_builds})
    return final_expanded_urls


def analyze_build(args):
    # Iterates over each url, gets test results and prints flaky tests.
    expanded_urls = parse_cli_args(args)
    for url_max_build in expanded_urls:
        url = url_max_build["url"]
        excludes = url_max_build["excludes"]
        json_response = requests.get(url + "/api/json").json()
        if json_response.has_key("builds"):
            builds = json_response["builds"]
            logger.info("Analyzing job: %s", url)
        else:
            builds = [{'number' : json_response["id"], 'url': url}]
            logger.info("Analyzing build : %s", url)
        build_id_to_results = {}
        num_builds = 0
        build_ids = []
        build_ids_without_tests_run = []
        for build in builds:
            build_id = build["number"]
            if build_id in excludes:
                continue
            result = generate_report(build["url"])
            if not result:
                continue
            if len(result[0]) > 0:
                build_id_to_results[build_id] = result
            else:
                build_ids_without_tests_run.append(build_id)
            num_builds += 1
            build_ids.append(build_id)
            if num_builds == url_max_build["max_builds"]:
                break

        # Collect list of bad tests.
        bad_tests = set()
        for build in build_id_to_results:
            [all_tests, failed_tests] = build_id_to_results[build]
            all_failed_tests.update(failed_tests)
            bad_tests.update(failed_tests)

        # For each bad test, get build ids where it ran, timed out, failed or hanged.
        test_to_build_ids = {key : {'all' : set(), 'timeout': set(), 'failed': set(),
                                    'hanging' : set(), 'bad_count' : 0}
                             for key in bad_tests}
        for build in build_id_to_results:
            [all_tests, failed_tests] = build_id_to_results[build]
            for bad_test in test_to_build_ids:
                is_bad = False
                if all_tests.issuperset([bad_test]):
                    test_to_build_ids[bad_test]["all"].add(build)
                if failed_tests.issuperset([bad_test]):
                    test_to_build_ids[bad_test]['failed'].add(build)
                    is_bad = True
                if is_bad:
                    test_to_build_ids[bad_test]['bad_count'] += 1

        # Calculate flakyness % and successful builds for each test. Also sort build ids.
        for bad_test in test_to_build_ids:
            test_result = test_to_build_ids[bad_test]
            test_result['flakyness'] = test_result['bad_count'] * 100.0 / len(test_result['all'])
            test_result['success'] = (test_result['all'].difference(
                test_result['failed'].union(test_result['hanging'])))
            for key in ['all', 'timeout', 'failed', 'hanging', 'success']:
                test_result[key] = sorted(test_result[key])

        # Sort tests in descending order by flakyness.
        sorted_test_to_build_ids = OrderedDict(
            sorted(test_to_build_ids.iteritems(), key=lambda x: x[1]['flakyness'], reverse=True))
        url_to_bad_test_results[url] = sorted_test_to_build_ids

        if len(sorted_test_to_build_ids) > 0:
            print "URL: {}".format(url)
            print "{:>60}  {:10}  {:25}  {}".format(
                "Test Name", "Total Runs", "Bad Runs(failed/timeout/hanging)", "Flakyness")
            for bad_test in sorted_test_to_build_ids:
                test_status = sorted_test_to_build_ids[bad_test]
                print "{:>60}  {:10}  {:7} ( {:4} / {:5} / {:5} )  {:2.0f}%".format(
                    bad_test, len(test_status['all']), test_status['bad_count'],
                    len(test_status['failed']), len(test_status['timeout']),
                    len(test_status['hanging']), test_status['flakyness'])
        else:
            print "No flaky tests founds."
            if len(build_ids) == len(build_ids_without_tests_run):
                print "None of the analyzed builds have test result."

        print "Builds analyzed: {}".format(build_ids)
        print "Builds without any test runs: {}".format(build_ids_without_tests_run)
        print ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--urls', metavar='URL', action='append', required=True,
                        help='Urls to analyze')
    parser.add_argument('--excluded-builds', metavar='n1,n2', action='append',
                        help='List of build numbers to exclude (or "None"). Not required, '
                        'but if specified, number of uses should be same as that of --urls '
                        'since the values are matched.')
    parser.add_argument('--max-builds', metavar='n', action='append', type=int,
                        help='The maximum number of builds to use (if available on jenkins). Specify '
                        'should be same as that of --urls since the values are matched.')
    parser.add_argument("-v", "--verbose", help="Prints more logs.", action="store_true")
    args = parser.parse_args()
    if args.verbose:
        logger.setLevel(logging.INFO)

    analyze_build(args)

    all_bad_tests = all_hanging_tests.union(all_failed_tests)
    dev_support_dir = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(dev_support_dir, "report_template.html"), "r") as f:
        template = Template(f.read())

    with open("report.html", "w") as f:
        datetime = time.strftime("%m/%d/%Y %H:%M:%S")
        f.write(template.render(datetime=datetime, bad_tests_count=len(all_bad_tests),
                                results=url_to_bad_test_results))

if __name__ == "__main__":
    main()


