/*
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

pipeline {
   agent none

    options {
        buildDiscarder(logRotator(daysToKeepStr: '14'))
        timeout(time: 59, unit: 'MINUTES')
    }

    triggers {
        cron('@daily')
    }

    stages {
        stage('Prepare') {
            matrix {
                agent any
                axes {
                    axis {
                        name 'JAVA_VERSION'
                        values 'JDK 1.8 (latest)', 'JDK 11 (latest)'
                    }
                }

                tools {
                    // Install the Maven version configured as "M3" and add it to the path.
                    maven "Maven (latest)"
                    jdk "${JAVA_VERSION}"
                }

                stages {
                    stage('BuildAndTest') {
                        steps {
                            // Get some code from a GitHub repository
                            git 'https://github.com/apache/zookeeper'

                            // Run Maven on a Unix agent.
                            sh "mvn verify spotbugs:check checkstyle:check -Pfull-build -Dsurefire-forkcount=4"
                        }
                        post {
                            // If Maven was able to run the tests, even if some of the test
                            // failed, record the test results and archive the jar file.
                            always {
                               junit '**/target/surefire-reports/TEST-*.xml'
                               archiveArtifacts '**/target/*.jar'
                            }
                        }
                    }
                }
            }
        }
    }
}
