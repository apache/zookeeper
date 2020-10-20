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
    agent {
        label 'Hadoop'
    }

    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(daysToKeepStr: '14'))
        timeout(time: 2, unit: 'HOURS')
        timestamps()
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
                        values 'jdk_1.8_latest', 'jdk_11_latest'
                    }
                }

                tools {
                    maven "maven_latest"
                    jdk "${JAVA_VERSION}"
                }

                stages {
                    stage('BuildAndTest') {
                        steps {
                            git 'https://github.com/apache/zookeeper'
                            sh "git clean -fxd"
                            sh "mvn verify spotbugs:check checkstyle:check -Pfull-build -Dsurefire-forkcount=4"
                        }
                        post {
                            always {
                               junit '**/target/surefire-reports/TEST-*.xml'
                               archiveArtifacts '**/target/*.jar'
                            }
                            // Jenkins pipeline jobs fill slaves on PRs without this :(
                            cleanup() {
                                script {
                                    sh label: 'Cleanup workspace', script: '''
                                        # See HADOOP-13951
                                        chmod -R u+rxw "${WORKSPACE}"
                                        '''
                                    deleteDir()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
