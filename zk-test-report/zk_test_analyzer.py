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

"""
This script uses Jenkins REST api to collect test results of given builds and generate test report.
It is primarily used to monitor build health and flaky tests across different builds.
Print help: zk_test_analyzer.py -h
"""

from collections import OrderedDict, defaultdict
import argparse
import logging
import os
import time
import re
import requests
import urllib
from jinja2 import Template

MAX_NUM_OF_BUILDS = 10000
# The upper bound of number of failed tests for a legimitate build.
# If more tests are failing, then the build is bad (e.g. JVM crash, flaky host, etc.).
MAX_LEGITIMATE_FAILURES = 30

logging.basicConfig()
LOG = logging.getLogger(__name__)

ALL_FAILED_TESTS = defaultdict(int)
# Contains { <url> : { <bad_test> : { 'all': [<build ids>], 'failed': [<build ids>]} } }
URL_TO_BAD_TEST_RESULTS = OrderedDict()


def classify_tests(junit_suites):
    """
    Given a testResponse, identify all test cases and failed test cases by name.

    :param build_url: Jenkins job build suites as reported by junit test reporter.
    :return: [all_tests, failed_tests]
    """
    all_tests = defaultdict(int)
    failed_tests = defaultdict(int)

    for suite in junit_suites:
        for case in suite["cases"]:
            name = case["name"]
            status = case["status"]
            all_tests[name] += 1
            if status in ("REGRESSION","FAILED"):
                failed_tests[name] += 1
            elif status not in ("PASSED","SKIPPED","FIXED"):
                print "Unknown status {}".format(status)
    
    print "Result > total tests: {:4}   failed : {:4}".format(
        sum(all_tests.values()), sum(failed_tests.values()))
    return [all_tests, failed_tests]


def generate_report(build_url):
    """
    Given a build url, retrieve the content of the build through
    Jenkins API and then classify test cases to prepare final
    test report.
    :param build_url: Jenkins build url.
    :return: classified test results.
    """
    LOG.info("Analyzing %s", build_url)

    report_url = build_url + "testReport/api/json?tree=suites" + urllib.quote("[name,cases[className,name,status]]")
    try:
        response = requests.get(report_url).json()
    except:
        LOG.error("failed to get: " + report_url)
        return
    build_result = classify_tests(response["suites"])
    if not build_result:
        LOG.info("Ignoring build %s", build_url)
        return
    return build_result


def parse_cli_args(cli_args):
    """
    Parse command line arguments.

    :param cli_args: command line arguments.
    :return: None
    """
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
        max_builds = MAX_NUM_OF_BUILDS
        if max_builds_arg is not None and max_builds_arg[i] != 0:
            max_builds = int(max_builds_arg[i])
        excluded_builds = []
        if excluded_builds_arg is not None and excluded_builds_arg[i] != "None":
            excluded_builds = [int(x) for x in excluded_builds_arg[i].split(",")]
        try:    
            response = requests.get(job_url + "/api/json").json()
        except:
            LOG.error("failed to get: " + job_url + "/api/json")
            continue
        if "activeConfigurations" in response:
            for config in response["activeConfigurations"]:
                final_expanded_urls.append({'url': config["url"], 'max_builds': max_builds,
                                            'excludes': excluded_builds})
        else:
            final_expanded_urls.append({'url': job_url, 'max_builds': max_builds,
                                        'excludes': excluded_builds})
    return final_expanded_urls


def analyze_build(args):
    """
    Given a set of command line arguments, analyze the build and populate
    various data structures used to generate final test report.
    :param args: arguments
    :return: None
    """
    expanded_urls = parse_cli_args(args)
    for url_max_build in expanded_urls:
        url = url_max_build["url"]
        excludes = url_max_build["excludes"]

        json_response = requests.get(url + "/api/json?tree=" + urllib.quote("builds[number,url]")).json()
        if json_response.has_key("builds"):
            builds = json_response["builds"]
            LOG.info("Analyzing job: %s", url)
        else:
            builds = [{'number': json_response["id"], 'url': url}]
            LOG.info("Analyzing build : %s", url)
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

        bad_tests = dict()
        for build in build_id_to_results:
            [_, failed_tests] = build_id_to_results[build]
            if len(failed_tests) > MAX_LEGITIMATE_FAILURES:
                LOG.info("Skipping build %s due to too many (%s) failures.", build, len(failed_tests))
                continue
            ALL_FAILED_TESTS.update(failed_tests)
            bad_tests.update(failed_tests)

        # For each bad test, get build ids where it ran, timed out, failed or hanged.
        test_to_build_ids = {key: {'all': dict(), 'timeout': dict(), 'failed': dict(),
                                   'hanging': dict(), 'good': dict(), 'bad_count': 0}
                             for key in bad_tests}
        for build in build_id_to_results:
            [all_tests, failed_tests] = build_id_to_results[build]
            for bad_test in test_to_build_ids:
                is_bad = False
                if bad_test in all_tests:
                    test_to_build_ids[bad_test]["all"].setdefault(build, 0)
                if bad_test in failed_tests:
                    test_to_build_ids[bad_test]['failed'].setdefault(build, 0)
                    is_bad = True
                if is_bad:
                    test_to_build_ids[bad_test]['bad_count'] += 1
                else:
                    test_to_build_ids[bad_test]['good'].setdefault(build, 0)

        # Calculate flakyness % and successful builds for each test. Also sort build ids.
        for bad_test in test_to_build_ids:
            test_result = test_to_build_ids[bad_test]
            test_result['flakyness'] = test_result['bad_count'] * 100.0 / len(test_result['all'])
            test_result['success'] = test_result['good']
            for key in ['all', 'timeout', 'failed', 'hanging', 'success']:
                test_result[key] = sorted(test_result[key])

        # Sort tests in descending order by flakyness.
        sorted_test_to_build_ids = OrderedDict(
            sorted(test_to_build_ids.iteritems(), key=lambda x: x[1]['flakyness'], reverse=True))
        URL_TO_BAD_TEST_RESULTS[url] = sorted_test_to_build_ids

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
    """
    Main entry of the module if used as an executable.
    :return: None
    """
    parser = argparse.ArgumentParser()
    parser.add_argument('--urls', metavar='URL', action='append', required=True,
                        help='Urls to analyze')
    parser.add_argument('--excluded-builds', metavar='n1,n2', action='append',
                        help='List of build numbers to exclude (or "None"). Not required, '
                        'but if specified, number of uses should be same as that of --urls '
                        'since the values are matched.')
    parser.add_argument('--max-builds', metavar='n', action='append', type=int,
                        help='The maximum number of builds to use (if available on jenkins).'
                             'Specify should be same as that of --urls since the values '
                             'are matched.')
    parser.add_argument("-v", "--verbose", help="Prints more logs.", action="store_true")
    args = parser.parse_args()
    if args.verbose:
        LOG.setLevel(logging.INFO)

    analyze_build(args)

    all_bad_tests = ALL_FAILED_TESTS
    dev_support_dir = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(dev_support_dir, "report_template.html"), "r") as report_template:
        template = Template(report_template.read())

    with open("report.html", "w") as report:
        datetime = time.strftime("%m/%d/%Y %H:%M:%S")
        report.write(template.render(datetime=datetime, bad_tests_count=len(all_bad_tests),
                                     results=URL_TO_BAD_TEST_RESULTS))

if __name__ == "__main__":
    main()
