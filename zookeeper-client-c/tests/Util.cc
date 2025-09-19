/**
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

#include <time.h>

#include "Util.h"
#include "string.h"

const std::string EMPTY_STRING;

TestConfig globalTestConfig;

void millisleep(int ms){
    timespec ts;
    ts.tv_sec=ms/1000;
    ts.tv_nsec=(ms%1000)*1000000; // to nanoseconds
    nanosleep(&ts,0);
}

FILE *openlogfile(const char* testname) {
  char name[1024];
  strcpy(name, "TEST-");
  strncpy(name + 5, testname, sizeof(name) - 5);
#ifdef THREADED
  strcpy(name + strlen(name), "-mt.txt");
#else
  strcpy(name + strlen(name), "-st.txt");
#endif

  FILE *logfile = fopen(name, "a");

  if (logfile == 0) {
    fprintf(stderr, "Can't open log file %s!\n", name);
    return 0;
  }

  return logfile;
}
