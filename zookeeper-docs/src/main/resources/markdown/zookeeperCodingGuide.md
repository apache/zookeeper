<!--
Copyright 2002-2019 The Apache Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# ZooKeeper Coding Guide
* [Java Coding Guide](#ch_java)
* [Unit Tests](#ch_ut)
* [Configuration](#ch_configuration)
* [Logging](#sc_logging)
    * [Developer Guidelines](#sc_developerGuidelines)
        * [Logging at the Right Level](#sc_rightLevel)
        * [Use of Standard slf4j Idioms](#sc_slf4jIdioms)
* [Other Things To Remember](#ch_other)

<a name="ch_java"></a>
## Java Coding Guide

- We are also using checkstyle to enforce coding style.
Please refer to our [checkstyle rules](https://github.com/apache/zookeeper/blob/master/checkstyle-strict.xml) for all enforced checkstyle rules.
``mvn checkstyle:check`` to make sure no checkstyle violations before submitting your patch.
- Lines can not be longer than 120 characters.
- Indentation should be 4 spaces. Tabs should never be used.
- Use curly braces even for single-line ifs and elses.
- No @author tags in any javadoc.
- TODOs should be associated to at least one issue.
- We prefer Java-8 Lambda expression to simplify some cumbersome java codes.
- Use try-with-resources blocks whenever is possible.
- If synchronization and locking is required, they should be in a fine granularity way.
- All threads should have proper meaningful name.


<a name="ch_ut"></a>
## Unit Tests

- New changes should come with unit tests that verify the functionality being added
- Unit tests should test the least amount of code possible. Don't start the whole server unless there is no other way to
  test a single class or small group of classes in isolation.
- Tests should not depend on any external resources. They need to setup and teardown their own stuff.
- It is okay to use the filesystem and network in tests since that's our business but you need to clean up them after yourself.
- Do not use sleep or other timing assumptions in tests. It is always, always, wrong and will fail intermittently on any test server with other things going on that causes delays.
- We are strongly recommending adding a timeout value to all our test cases, to prevent a build from completing indefinitely. ``@Test(timeout=60000)``

<a name="ch_configuration"></a>
## Configuration

Names should be thought through from the point of view of the person using the config.
The default values should be thought as best value for people who runs the program without tuning parameters.
All configuration settings should be added to the [documentation](https://github.com/apache/zookeeper/blob/master/zookeeper-docs/src/main/resources/markdown/zookeeperAdmin.md#sc_configuration).

<a name="sc_logging"></a>
## Logging

Zookeeper uses [slf4j](http://www.slf4j.org/index.html) as an abstraction layer for logging. [log4j](http://logging.apache.org/log4j) in version 1.2 is chosen as the final logging implementation for now.
For better embedding support, it is planned in the future to leave the decision of choosing the final logging implementation to the end user.
Therefore, always use the slf4j api to write log statements in the code, but configure log4j for how to log at runtime.
Note that slf4j has no FATAL level, former messages at FATAL level have been moved to ERROR level.
For information on configuring log4j for
ZooKeeper, see the [Logging](https://github.com/apache/zookeeper/blob/master/zookeeper-docs/src/main/resources/markdown/zookeeperAdmin.md#logging) section
of the **ZooKeeper Administrator's Guide**

<a name="sc_developerGuidelines"></a>

### Developer Guidelines

Please follow the  [slf4j manual](http://www.slf4j.org/manual.html) when creating log statements within code.
Also read the [FAQ on performance](http://www.slf4j.org/faq.html#logging\_performance), when creating log statements. Patch reviewers will look for the following:

<a name="sc_rightLevel"></a>

#### Logging at the Right Level

There are several levels of logging in slf4j.

It's important to pick the right one. In order of higher to lower severity:

1. ERROR level designates error events that might still allow the application to continue running.
1. WARN level designates potentially harmful situations.
1. INFO level designates informational messages that highlight the progress of the application at coarse-grained level.
1. DEBUG Level designates fine-grained informational events that are most useful to debug an application.
1. TRACE Level designates finer-grained informational events than the DEBUG.

ZooKeeper is typically run in production such that log messages of INFO level
severity and higher (more severe) are output to the log.

<a name="sc_slf4jIdioms"></a>

#### Use of Standard slf4j Idioms

_Static Message Logging_

    LOG.debug("process completed successfully!");

However when creating parameterized messages are required, use formatting anchors.

    LOG.debug("got {} messages in {} minutes",new Object[]{count,time});

_Naming_

Loggers should be named after the class in which they are used.

    public class Foo {
        private static final Logger LOG = LoggerFactory.getLogger(Foo.class);
        ....
        public Foo() {
            LOG.info("constructing Foo");

_Exception handling_

    try {
        // code
    } catch (XYZException e) {
        // do this
        LOG.error("Something bad happened", e);
        // don't do this (generally)
        // LOG.error(e);
        // why? because "don't do" case hides the stack trace

        // continue process here as you need... recover or (re)throw
    }


<a name="ch_other"></a>
## Other Things To Remember

- For a new big feature, please provide a design documentation, at least a detailed introduction. It's good for reviewers
  to save time, the successors to track problems, and technological accumulation/communication.
- For a notable performance improvement, please provide experimental control group, environmental configuration, benchmark
  metrics(e.g:throughput, latency, gc time) to make the improvement persuasive and reproducible.