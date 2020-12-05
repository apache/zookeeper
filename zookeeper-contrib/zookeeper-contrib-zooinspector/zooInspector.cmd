@echo off
@rem Licensed to the Apache Software Foundation (ASF) under one or more
@rem contributor license agreements.  See the NOTICE file distributed with
@rem this work for additional information regarding copyright ownership.
@rem The ASF licenses this file to You under the Apache License, Version 2.0
@rem (the "License"); you may not use this file except in compliance with
@rem the License.  You may obtain a copy of the License at
@rem
@rem     http://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.

@rem Get the path to the directory containing this script
SET SCRIPT_DIR=%~dp0

@rem Get the path to the uber jar for this tool
@rem (Requires "mvn install" or "mvn package" be run first)
set JAVA_LIB=
for /F %%f in ('dir /b "%SCRIPT_DIR%\target\zookeeper-contrib-zooinspector-*-jar-with-dependencies.jar" 2^>nul') do (
   set JAVA_LIB=%SCRIPT_DIR%target\%%f
)
java -jar %JAVA_LIB%
