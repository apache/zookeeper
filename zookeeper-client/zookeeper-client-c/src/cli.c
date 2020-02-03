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

/**
 * cli.c is a example/sample C client shell for ZooKeeper. It contains
 * basic shell functionality which exercises some of the features of
 * the ZooKeeper C client API. It is not a full fledged client and is
 * not meant for production usage - see the Java client shell for a
 * fully featured shell.
 */

#include <zookeeper.h>
#include <proto.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#ifndef WIN32
#include <sys/time.h>
#include <unistd.h>
#include <sys/select.h>
#include <getopt.h>
#else
#include "winport.h"
//#include <io.h> <-- can't include, conflicting definitions of close()
int read(int _FileHandle, void * _DstBuf, unsigned int _MaxCharCount);
int write(int _Filehandle, const void * _Buf, unsigned int _MaxCharCount);
#define ctime_r(tctime, buffer) ctime_s (buffer, 40, tctime)
#include "win_getopt.h" // VisualStudio doesn't contain 'getopt'
#endif

#include <time.h>
#include <errno.h>
#include <assert.h>

#ifdef YCA
#include <yca/yca.h>
#endif

#define _LL_CAST_ (long long)

static zhandle_t *zh;
static clientid_t myid;
static const char *clientIdFile = 0;
struct timeval startTime;
static const char *cmd;
static const char *cert;
static int batchMode=0;

static int to_send=0;
static int sent=0;
static int recvd=0;

static int shutdownThisThing=0;

static __attribute__ ((unused)) void
printProfileInfo(struct timeval start, struct timeval end, int thres,
                 const char* msg)
{
  int delay=(end.tv_sec*1000+end.tv_usec/1000)-
    (start.tv_sec*1000+start.tv_usec/1000);
  if(delay>thres)
    fprintf(stderr,"%s: execution time=%dms\n",msg,delay);
}

static const char* state2String(int state){
  if (state == 0)
    return "CLOSED_STATE";
  if (state == ZOO_CONNECTING_STATE)
    return "CONNECTING_STATE";
  if (state == ZOO_ASSOCIATING_STATE)
    return "ASSOCIATING_STATE";
  if (state == ZOO_CONNECTED_STATE)
    return "CONNECTED_STATE";
  if (state == ZOO_READONLY_STATE)
    return "READONLY_STATE";
  if (state == ZOO_EXPIRED_SESSION_STATE)
    return "EXPIRED_SESSION_STATE";
  if (state == ZOO_AUTH_FAILED_STATE)
    return "AUTH_FAILED_STATE";

  return "INVALID_STATE";
}

static const char* type2String(int state){
  if (state == ZOO_CREATED_EVENT)
    return "CREATED_EVENT";
  if (state == ZOO_DELETED_EVENT)
    return "DELETED_EVENT";
  if (state == ZOO_CHANGED_EVENT)
    return "CHANGED_EVENT";
  if (state == ZOO_CHILD_EVENT)
    return "CHILD_EVENT";
  if (state == ZOO_SESSION_EVENT)
    return "SESSION_EVENT";
  if (state == ZOO_NOTWATCHING_EVENT)
    return "NOTWATCHING_EVENT";

  return "UNKNOWN_EVENT_TYPE";
}

void watcher(zhandle_t *zzh, int type, int state, const char *path,
             void* context)
{
    /* Be careful using zh here rather than zzh - as this may be mt code
     * the client lib may call the watcher before zookeeper_init returns */

    fprintf(stderr, "Watcher %s state = %s", type2String(type), state2String(state));
    if (path && strlen(path) > 0) {
      fprintf(stderr, " for path %s", path);
    }
    fprintf(stderr, "\n");

    if (type == ZOO_SESSION_EVENT) {
        if (state == ZOO_CONNECTED_STATE) {
            const clientid_t *id = zoo_client_id(zzh);
            if (myid.client_id == 0 || myid.client_id != id->client_id) {
                myid = *id;
                fprintf(stderr, "Got a new session id: 0x%llx\n",
                        _LL_CAST_ myid.client_id);
                if (clientIdFile) {
                    FILE *fh = fopen(clientIdFile, "w");
                    if (!fh) {
                        perror(clientIdFile);
                    } else {
                        int rc = fwrite(&myid, sizeof(myid), 1, fh);
                        if (rc != sizeof(myid)) {
                            perror("writing client id");
                        }
                        fclose(fh);
                    }
                }
            }
        } else if (state == ZOO_AUTH_FAILED_STATE) {
            fprintf(stderr, "Authentication failure. Shutting down...\n");
            zookeeper_close(zzh);
            shutdownThisThing=1;
            zh=0;
        } else if (state == ZOO_EXPIRED_SESSION_STATE) {
            fprintf(stderr, "Session expired. Shutting down...\n");
            zookeeper_close(zzh);
            shutdownThisThing=1;
            zh=0;
        }
    }
}

void dumpStat(const struct Stat *stat) {
    char tctimes[40];
    char tmtimes[40];
    time_t tctime;
    time_t tmtime;

    if (!stat) {
        fprintf(stderr,"null\n");
        return;
    }
    tctime = stat->ctime/1000;
    tmtime = stat->mtime/1000;

    ctime_r(&tmtime, tmtimes);
    ctime_r(&tctime, tctimes);

    fprintf(stderr, "\tctime = %s\tczxid=%llx\n"
    "\tmtime=%s\tmzxid=%llx\n"
    "\tversion=%x\taversion=%x\n"
    "\tephemeralOwner = %llx\n",
     tctimes, _LL_CAST_ stat->czxid, tmtimes,
    _LL_CAST_ stat->mzxid,
    (unsigned int)stat->version, (unsigned int)stat->aversion,
    _LL_CAST_ stat->ephemeralOwner);
}

void my_string_completion(int rc, const char *name, const void *data) {
    fprintf(stderr, "[%s]: rc = %d\n", (char*)(data==0?"null":data), rc);
    if (!rc) {
        fprintf(stderr, "\tname = %s\n", name);
    }
    if(batchMode)
      shutdownThisThing=1;
}

void my_string_completion_free_data(int rc, const char *name, const void *data) {
    my_string_completion(rc, name, data);
    free((void*)data);
}

void my_string_stat_completion(int rc, const char *name, const struct Stat *stat,
        const void *data)  {
    my_string_completion(rc, name, data);
    dumpStat(stat);
}

void my_string_stat_completion_free_data(int rc, const char *name,
        const struct Stat *stat, const void *data)  {
    my_string_stat_completion(rc, name, stat, data);
    free((void*)data);
}

void my_data_completion(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data) {
    struct timeval tv;
    int sec;
    int usec;
    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    if (value) {
        fprintf(stderr, " value_len = %d\n", value_len);
        assert(write(2, value, value_len) == value_len);
    }
    fprintf(stderr, "\nStat:\n");
    dumpStat(stat);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_silent_data_completion(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data) {
    recvd++;
    fprintf(stderr, "Data completion %s rc = %d\n",(char*)data,rc);
    free((void*)data);
    if (recvd==to_send) {
        fprintf(stderr,"Recvd %d responses for %d requests sent\n",recvd,to_send);
        if(batchMode)
          shutdownThisThing=1;
    }
}

void my_strings_completion(int rc, const struct String_vector *strings,
        const void *data) {
    struct timeval tv;
    int sec;
    int usec;
    int i;

    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    if (strings)
        for (i=0; i < strings->count; i++) {
            fprintf(stderr, "\t%s\n", strings->data[i]);
        }
    free((void*)data);
    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    if(batchMode)
      shutdownThisThing=1;
}

void my_strings_stat_completion(int rc, const struct String_vector *strings,
        const struct Stat *stat, const void *data) {
    my_strings_completion(rc, strings, data);
    dumpStat(stat);
    if(batchMode)
      shutdownThisThing=1;
}

void my_void_completion(int rc, const void *data) {
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_stat_completion(int rc, const struct Stat *stat, const void *data) {
    fprintf(stderr, "%s: rc = %d Stat:\n", (char*)data, rc);
    dumpStat(stat);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_silent_stat_completion(int rc, const struct Stat *stat,
        const void *data) {
    //    fprintf(stderr, "State completion: [%s] rc = %d\n", (char*)data, rc);
    sent++;
    free((void*)data);
}

static void sendRequest(const char* data) {
    zoo_aset(zh, "/od", data, strlen(data), -1, my_silent_stat_completion,
            strdup("/od"));
    zoo_aget(zh, "/od", 1, my_silent_data_completion, strdup("/od"));
}

void od_completion(int rc, const struct Stat *stat, const void *data) {
    int i;
    fprintf(stderr, "od command response: rc = %d Stat:\n", rc);
    dumpStat(stat);
    // send a whole bunch of requests
    recvd=0;
    sent=0;
    to_send=200;
    for (i=0; i<to_send; i++) {
        char buf[4096*16];
        memset(buf, -1, sizeof(buf)-1);
        buf[sizeof(buf)-1]=0;
        sendRequest(buf);
    }
}

int startsWith(const char *line, const char *prefix) {
    int len = strlen(prefix);
    return strncmp(line, prefix, len) == 0;
}

static const char *hostPort;
static int verbose = 0;

void processline(const char *line) {
    int rc;
    int async = ((line[0] == 'a') && !(startsWith(line, "addauth ")));
    if (async) {
        line++;
    }
    if (startsWith(line, "help")) {
      fprintf(stderr, "    create [+[e|s|c|t=ttl]] <path>\n");
      fprintf(stderr, "    create2 [+[e|s|c|t=ttl]] <path>\n");
      fprintf(stderr, "    delete <path>\n");
      fprintf(stderr, "    set <path> <data>\n");
      fprintf(stderr, "    get <path>\n");
      fprintf(stderr, "    ls <path>\n");
      fprintf(stderr, "    ls2 <path>\n");
      fprintf(stderr, "    sync <path>\n");
      fprintf(stderr, "    exists <path>\n");
      fprintf(stderr, "    wexists <path>\n");
      fprintf(stderr, "    myid\n");
      fprintf(stderr, "    verbose\n");
      fprintf(stderr, "    addauth <id> <scheme>\n");
      fprintf(stderr, "    config\n");
      fprintf(stderr, "    reconfig [-file <path> | -members <serverId=host:port1:port2;port3>,... | "
                          " -add <serverId=host:port1:port2;port3>,... | -remove <serverId>,...] [-version <version>]\n");
      fprintf(stderr, "    quit\n");
      fprintf(stderr, "\n");
      fprintf(stderr, "    prefix the command with the character 'a' to run the command asynchronously.\n");
      fprintf(stderr, "    run the 'verbose' command to toggle verbose logging.\n");
      fprintf(stderr, "    i.e. 'aget /foo' to get /foo asynchronously\n");
    } else if (startsWith(line, "verbose")) {
      if (verbose) {
        verbose = 0;
        zoo_set_debug_level(ZOO_LOG_LEVEL_WARN);
        fprintf(stderr, "logging level set to WARN\n");
      } else {
        verbose = 1;
        zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);
        fprintf(stderr, "logging level set to DEBUG\n");
      }
    } else if (startsWith(line, "get ")) {
        line += 4;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }

        rc = zoo_aget(zh, line, 1, my_data_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (strcmp(line, "config") == 0) {
       gettimeofday(&startTime, 0);
        rc = zoo_agetconfig(zh, 1, my_data_completion, strdup(ZOO_CONFIG_NODE));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
   } else if (startsWith(line, "reconfig ")) {
           int syntaxError = 0;
           char* p = NULL;
           char* joining = NULL;
           char* leaving = NULL;
           char* members = NULL;
           size_t members_size = 0;

           int mode = 0; // 0 = not set, 1 = incremental, 2 = non-incremental
           int64_t version = -1;

           line += 9;
           p = strtok (strdup(line)," ");
           while (p != NULL) {
               if (strcmp(p, "-add")==0) {
                   p = strtok (NULL," ");
                   if (mode == 2 || p == NULL) {
                       syntaxError = 1;
                       break;
                   }
                   mode = 1;
                   joining = strdup(p);
               } else if (strcmp(p, "-remove")==0){
                   p = strtok (NULL," ");
                   if (mode == 2 || p == NULL) {
                       syntaxError = 1;
                       break;
                   }
                   mode = 1;
                   leaving = strdup(p);
               } else if (strcmp(p, "-members")==0) {
                   p = strtok (NULL," ");
                   if (mode == 1 || p == NULL) {
                       syntaxError = 1;
                       break;
                   }
                   mode = 2;
                   members = strdup(p);
               } else if (strcmp(p, "-file")==0){
                   FILE *fp = NULL;
                   p = strtok (NULL," ");
                   if (mode == 1 || p == NULL) {
                       syntaxError = 1;
                       break;
                   }
                   mode = 2;
                   fp = fopen(p, "r");
                   if (fp == NULL) {
                       fprintf(stderr, "Error reading file: %s\n", p);
                       syntaxError = 1;
                       break;
                   }
                   fseek(fp, 0L, SEEK_END);  /* Position to end of file */
                   members_size = ftell(fp);     /* Get file length */
                   rewind(fp);               /* Back to start of file */
                   members = calloc(members_size + 1, sizeof(char));
                   if(members == NULL )
                   {
                       fprintf(stderr, "\nInsufficient memory to read file: %s\n", p);
                       syntaxError = 1;
                       fclose(fp);
                       break;
                   }

                   /* Read the entire file into members
                    * NOTE: -- fread returns number of items successfully read
                    * not the number of bytes. We're requesting one item of
                    * members_size bytes. So we expect the return value here
                    * to be 1.
                    */
                   if (fread(members, members_size, 1, fp) != 1){
                       fprintf(stderr, "Error reading file: %s\n", p);
                       syntaxError = 1;
                       fclose(fp);
                        break;
                   }
                   fclose(fp);
               } else if (strcmp(p, "-version")==0){
                   p = strtok (NULL," ");
                   if (version != -1 || p == NULL){
                       syntaxError = 1;
                       break;
                   }
#ifdef WIN32
                   version = _strtoui64(p, NULL, 16);
#else
                   version = strtoull(p, NULL, 16);
#endif
                   if (version < 0) {
                       syntaxError = 1;
                       break;
                   }
               } else {
                   syntaxError = 1;
                   break;
               }
               p = strtok (NULL," ");
           }
           if (syntaxError) return;

           rc = zoo_areconfig(zh, joining, leaving, members, version, my_data_completion, strdup(line));
           free(joining);
           free(leaving);
           free(members);
           if (rc) {
               fprintf(stderr, "Error %d for %s\n", rc, line);
           }

   } else if (startsWith(line, "set ")) {
        char *ptr;
        line += 4;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        ptr = strchr(line, ' ');
        if (!ptr) {
            fprintf(stderr, "No data found after path\n");
            return;
        }
        *ptr = '\0';
        ptr++;
        rc = zoo_aset(zh, line, ptr, strlen(ptr), -1, my_stat_completion,
                strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "ls ")) {
        line += 3;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        gettimeofday(&startTime, 0);
        rc= zoo_aget_children(zh, line, 1, my_strings_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "ls2 ")) {
        line += 4;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        gettimeofday(&startTime, 0);
        rc= zoo_aget_children2(zh, line, 1, my_strings_stat_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "create ") || startsWith(line, "create2 ")) {
        int mode = 0;
        int64_t ttl_value = -1;
        int is_create2 = startsWith(line, "create2 ");
        line += is_create2 ? 8 : 7;

        if (line[0] == '+') {
            int ephemeral = 0;
            int sequential = 0;
            int container = 0;
            int ttl = 0;
            char *p = NULL;

            line++;

            while (*line != ' ' && *line != '\0') {
                switch (*line) {
                case 'e':
                    ephemeral = 1;
                    break;
                case 's':
                    sequential = 1;
                    break;
                case 'c':
                    container = 1;
                    break;
                case 't':
                    ttl = 1;

                    line++;

                    if (*line != '=') {
                        fprintf(stderr, "Missing ttl value after +t\n");
                        return;
                    }

                    line++;

                    ttl_value = strtol(line, &p, 10);

                    if (ttl_value <= 0) {
                        fprintf(stderr, "ttl value must be a positive integer\n");
                        return;
                    }

                    // move back line pointer to the last digit
                    line = p - 1;

                    break;
                default:
                    fprintf(stderr, "Unknown option: %c\n", *line);
                    return;
                }

                line++;
            }

            if (ephemeral != 0 && sequential == 0 && container == 0 && ttl == 0) {
                mode = ZOO_EPHEMERAL;
            } else if (ephemeral == 0 && sequential != 0 && container == 0 && ttl == 0) {
                mode = ZOO_PERSISTENT_SEQUENTIAL;
            } else if (ephemeral != 0 && sequential != 0 && container == 0 && ttl == 0) {
                mode = ZOO_EPHEMERAL_SEQUENTIAL;
            } else if (ephemeral == 0 && sequential == 0 && container != 0 && ttl == 0) {
                mode = ZOO_CONTAINER;
            } else if (ephemeral == 0 && sequential == 0 && container == 0 && ttl != 0) {
                mode = ZOO_PERSISTENT_WITH_TTL;
            } else if (ephemeral == 0 && sequential != 0 && container == 0 && ttl != 0) {
                mode = ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL;
            } else {
                fprintf(stderr, "Invalid mode.\n");
                return;
            }

            if (*line == ' ') {
                line++;
            }
        }
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        fprintf(stderr, "Creating [%s] node (mode: %d)\n", line, mode);
//        {
//            struct ACL _CREATE_ONLY_ACL_ACL[] = {{ZOO_PERM_CREATE, ZOO_ANYONE_ID_UNSAFE}};
//            struct ACL_vector CREATE_ONLY_ACL = {1,_CREATE_ONLY_ACL_ACL};
//            rc = zoo_acreate(zh, line, "new", 3, &CREATE_ONLY_ACL, flags,
//                    my_string_completion, strdup(line));
//        }
        if (is_create2) {
          rc = zoo_acreate2_ttl(zh, line, "new", 3, &ZOO_OPEN_ACL_UNSAFE, mode, ttl_value,
                my_string_stat_completion_free_data, strdup(line));
        } else {
          rc = zoo_acreate_ttl(zh, line, "new", 3, &ZOO_OPEN_ACL_UNSAFE, mode, ttl_value,
                my_string_completion_free_data, strdup(line));
        }
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "delete ")) {
        line += 7;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        rc = zoo_adelete(zh, line, -1, my_void_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "sync ")) {
        line += 5;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        rc = zoo_async(zh, line, my_string_completion_free_data, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "wexists ")) {
#ifdef THREADED
        struct Stat stat;
#endif
        line += 8;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
#ifndef THREADED
        rc = zoo_awexists(zh, line, watcher, (void*) 0, my_stat_completion, strdup(line));
#else
        rc = zoo_wexists(zh, line, watcher, (void*) 0, &stat);
#endif
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "exists ")) {
#ifdef THREADED
        struct Stat stat;
#endif
        line += 7;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
#ifndef THREADED
        rc = zoo_aexists(zh, line, 1, my_stat_completion, strdup(line));
#else
        rc = zoo_exists(zh, line, 1, &stat);
#endif
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (strcmp(line, "myid") == 0) {
        printf("session Id = %llx\n", _LL_CAST_ zoo_client_id(zh)->client_id);
    } else if (strcmp(line, "reinit") == 0) {
        zookeeper_close(zh);
        // we can't send myid to the server here -- zookeeper_close() removes
        // the session on the server. We must start anew.
        zh = zookeeper_init(hostPort, watcher, 30000, 0, 0, 0);
    } else if (startsWith(line, "quit")) {
        fprintf(stderr, "Quitting...\n");
        shutdownThisThing=1;
    } else if (startsWith(line, "od")) {
        const char val[]="fire off";
        fprintf(stderr, "Overdosing...\n");
        rc = zoo_aset(zh, "/od", val, sizeof(val)-1, -1, od_completion, 0);
        if (rc)
            fprintf(stderr, "od command failed: %d\n", rc);
    } else if (startsWith(line, "addauth ")) {
      char *ptr;
      line += 8;
      ptr = strchr(line, ' ');
      if (ptr) {
        *ptr = '\0';
        ptr++;
      }
      zoo_add_auth(zh, line, ptr, ptr ? strlen(ptr) : 0, NULL, NULL);
    }
}

/*
 * Look for a command in the form 'cmd:command', and store a pointer
 * to the command (without its prefix) into *buf if found.
 *
 * Returns 0 if the argument does not start with the prefix.
 * Returns 1 in case of success.
 */
int handleBatchMode(const char* arg, const char** buf) {
    size_t cmdlen = strlen(arg);
    if (cmdlen < 4) {
        // too short
        return 0;
    }
    cmdlen -= 4;
    if(strncmp("cmd:", arg, 4) != 0){
        return 0;        
    }
    *buf = arg + 4;
    return 1;
}

#ifdef THREADED
static void millisleep(int ms) {
#ifdef WIN32
    Sleep(ms);
#else /* !WIN32 */
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000; // to nanoseconds
    nanosleep(&ts, NULL);
#endif /* WIN32 */
}
#endif /* THREADED */

int main(int argc, char **argv) {
    static struct option long_options[] = {
            {"host",     required_argument, NULL, 'h'}, //hostPort
            {"ssl",      required_argument, NULL, 's'}, //certificate files
            {"myid",     required_argument, NULL, 'm'}, //myId file
            {"cmd",      required_argument, NULL, 'c'}, //cmd
            {"readonly", no_argument, NULL, 'r'}, //read-only
            {"debug",    no_argument, NULL, 'd'}, //set log level to DEBUG from the beginning
#ifdef HAVE_CYRUS_SASL_H
            // Parameters for SASL authentication.
            {"service",       required_argument, NULL, 'z'},
            {"server-fqdn",   required_argument, NULL, 'o'}, //Host used for SASL auth
            {"mechlist",      required_argument, NULL, 'n'}, //SASL mechanism list
            {"user",          required_argument, NULL, 'u'}, //SASL user
            {"realm",         required_argument, NULL, 'l'}, //SASL realm
            {"password-file", required_argument, NULL, 'p'},
#endif /* HAVE_CYRUS_SASL_H */
            {NULL,      0,                 NULL, 0},
    };
#ifndef THREADED
    fd_set rfds, wfds, efds;
    int processed=0;
#endif
    char buffer[4096];
    char p[2048];
#ifdef YCA
    char *cert=0;
    char appId[64];
#endif
    int bufoff = 0;
    int flags;
    FILE *fh;
#ifdef HAVE_CYRUS_SASL_H
    char *service = "zookeeper";
    char *serverFQDN = NULL;
    char *mechlist = NULL;
    char *user = NULL;
    char *realm = NULL;
    char *passwordFile = NULL;
#endif /* HAVE_CYRUS_SASL_H */

    int opt;
    int option_index = 0;

    verbose = 0;
    zoo_set_debug_level(ZOO_LOG_LEVEL_WARN);

    flags = 0;
    while ((opt = getopt_long(argc, argv, "h:s:m:c:rdz:o:n:u:l:p:", long_options, &option_index)) != -1) {
        switch (opt) {
            case 'h':
                hostPort = optarg;
                break;
            case 'm':
                clientIdFile = optarg;
                break;
            case 'r':
                flags = ZOO_READONLY;
                break;
            case 'c':
                cmd = optarg;
                batchMode = 1;
                fprintf(stderr,"Batch mode: %s\n",cmd);
                break;
            case 's':
                cert = optarg;
                break;
            case 'd':
                verbose = 1;
                zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);
                fprintf(stderr, "logging level set to DEBUG\n");
                break;
#ifdef HAVE_CYRUS_SASL_H
            case 'z':
                service = optarg;
                break;
            case 'o':
                serverFQDN = optarg;
                break;
            case 'n':
                mechlist = optarg;
                break;
            case 'u':
                user = optarg;
                break;
            case 'l':
                realm = optarg;
                break;
            case 'p':
                passwordFile = optarg;
                break;
#endif /* HAVE_CYRUS_SASL_H */
            case '?':
                if (optopt == 'h') {
                    fprintf (stderr, "Option -%c requires host list.\n", optopt);
                } else if (isprint (optopt)) {
                    fprintf (stderr, "Unknown option `-%c'.\n", optopt);
                } else {
                    fprintf (stderr,
                             "Unknown option character `\\x%x'.\n",
                             optopt);
                    return 1;
                }
        }
    }

    if (!hostPort && optind < argc) {
        /*
         * getopt_long did not find a '-h <connect-string>' option.
         *
         * The invoker may be using using the "old-style" command
         * syntax, with positional parameters and "magical" prefixes
         * such as 'cmd:'; let's see if we can make sense of it.
         */
        hostPort = argv[optind++];

        if (optind < argc && !cmd && !clientIdFile) {
            int batchModeRes = handleBatchMode(argv[optind], &cmd);
            if (batchModeRes == 1) {
                batchMode=1;
                fprintf(stderr, "Batch mode: '%s'\n", cmd);
            } else {
                clientIdFile = argv[optind];
            }

            optind++;
        }
    }

    if (!hostPort || optind < argc) {
        fprintf(stderr,
                "\nUSAGE:    %s -h zk_host_1:port_1,zk_host_2:port_2,... [OPTIONAL ARGS]\n\n"
                "MANDATORY ARGS:\n"
                "-h, --host <host:port pairs>   Comma separated list of ZooKeeper host:port pairs\n\n"
                "OPTIONAL ARGS:\n"
                "-m, --myid <clientid file>     Path to the file contains the client ID\n"
                "-c, --cmd <command>            Command to execute, e.g. ls|ls2|create|create2|od|...\n"
#ifdef HAVE_OPENSSL_H
                "-s, --ssl <ssl params>         Comma separated parameters to initiate SSL connection\n"
                "                                 e.g.: server_cert.crt,client_cert.crt,client_priv_key.pem,passwd\n"
#endif
#ifdef HAVE_CYRUS_SASL_H
                "-u, --user <user>              SASL user name\n"
                "-n, --mechlist <mechlist>      Comma separated list of SASL mechanisms (GSSAPI and/or DIGEST-MD5)\n"
                "-o, --server-fqdn <fqdn>       SASL server name ('zk-sasl-md5' for DIGEST-MD5; default: reverse DNS lookup)\n"
                "-p, --password-file <file>     File containing the password (recommended for SASL/DIGEST-MD5)\n"
                "-l, --realm <realm>            Realm (for SASL/GSSAPI)\n"
                "-z, --service <service>        SASL service parameter (default: 'zookeeper')\n"
#endif /* HAVE_CYRUS_SASL_H */
                "-r, --readonly                 Connect in read-only mode\n"
                "-d, --debug                    Activate debug logs right from the beginning (you can also use the \n"
                "                                 command 'verbose' later to activate debug logs in the cli shell)\n\n",
                argv[0]);
#ifdef HAVE_CYRUS_SASL_H
        fprintf(stderr,
                "SASL EXAMPLES:\n"
                "$ %s --mechlist DIGEST-MD5 --user bob --password-file bob.secret --server-fqdn zk-sasl-md5 -h ...\n"
                "$ %s --mechlist GSSAPI --user bob --realm BOBINC.COM -h ...\n"
                "Notes:\n"
                "  * SASL and SSL support are currently incompatible (ZOOKEEPER-3482);\n"
                "  * SASL parameters map to Cyrus SASL's _new/_start APIs and callbacks;\n"
                "  * DIGEST-MD5 requires '--server-fqdn zk-sasl-md5' for historical reasons.\n"
                "  * Passwords are obtained via the obsolete 'getpass()' if not provided via '--password-file'.\n"
                "\n",
                argv[0], argv[0]);
#endif /* HAVE_CYRUS_SASL_H */
        fprintf(stderr,
                "Version: ZooKeeper cli (c client) version %s\n",
                ZOO_VERSION);
        return 2;
    }

    if (clientIdFile) {
        fh = fopen(clientIdFile, "r");
        if (fh) {
            if (fread(&myid, sizeof(myid), 1, fh) != sizeof(myid)) {
                memset(&myid, 0, sizeof(myid));
            }
            fclose(fh);
        }
    }

#ifdef YCA
    strcpy(appId,"yahoo.example.yca_test");
    cert = yca_get_cert_once(appId);
    if(cert!=0) {
        fprintf(stderr,"Certificate for appid [%s] is [%s]\n",appId,cert);
        strncpy(p,cert,sizeof(p)-1);
        free(cert);
    } else {
      fprintf(stderr,"Certificate for appid [%s] not found\n",appId);
      strcpy(p,"dummy");
    }
#else
    strcpy(p, "dummy");
#endif
    zoo_deterministic_conn_order(1); // enable deterministic order

#ifdef HAVE_CYRUS_SASL_H
    if (mechlist) {
        zoo_sasl_params_t sasl_params = { 0 };
        int sr;

        if (cert) {
            fprintf(stderr, "SASL and SSL support are currently incompatible (ZOOKEEPER-3482); exiting.\n");
            return 1;
        }

        sr = sasl_client_init(NULL);
        if (sr != SASL_OK) {
            fprintf(stderr, "Unable to initialize SASL library: %s\n",
                    sasl_errstring(sr, NULL, NULL));
            return 1;
        }

        sasl_params.service = service;
        sasl_params.host = serverFQDN;
        sasl_params.mechlist = mechlist;
        sasl_params.callbacks = zoo_sasl_make_basic_callbacks(user, realm,
            passwordFile);

        zh = zookeeper_init_sasl(hostPort, watcher, 30000, &myid, NULL, flags,
            NULL, &sasl_params);

        if (!zh) {
            return errno;
        }
    }
#endif /* HAVE_CYRUS_SASL_H */

    if (!zh) {
#ifdef HAVE_OPENSSL_H
        if (!cert) {
            zh = zookeeper_init(hostPort, watcher, 30000, &myid, NULL, flags);
        } else {
            zh = zookeeper_init_ssl(hostPort, cert, watcher, 30000, &myid, NULL, flags);
        }
#else
        zh = zookeeper_init(hostPort, watcher, 30000, &myid, NULL, flags);
#endif

        if (!zh) {
            return errno;
        }
    }

#ifdef YCA
    if(zoo_add_auth(zh,"yca",p,strlen(p),0,0)!=ZOK)
    return 2;
#endif

#ifdef THREADED
    if (batchMode) {
        processline(cmd);
    }
    while(!shutdownThisThing) {
        int rc, len;
        if (batchMode) {
            // We are just waiting for the asynchronous command to complete.
            millisleep(10);
            continue;
        }
        len = sizeof(buffer) - bufoff -1;
        if (len <= 0) {
            fprintf(stderr, "Can't handle lines that long!\n");
            exit(2);
        }
        rc = read(0, buffer+bufoff, len);
        if (rc <= 0) {
            fprintf(stderr, "bye\n");
            shutdownThisThing=1;
            break;
        }
        bufoff += rc;
        buffer[bufoff] = '\0';
        while (strchr(buffer, '\n')) {
            char *ptr = strchr(buffer, '\n');
            *ptr = '\0';
            processline(buffer);
            ptr++;
            memmove(buffer, ptr, strlen(ptr)+1);
            bufoff = 0;
        }
    }
#else
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    FD_ZERO(&efds);
    while (!shutdownThisThing) {
        int fd;
        int interest;
        int events;
        struct timeval tv;
        int rc;
        zookeeper_interest(zh, &fd, &interest, &tv);
        if (fd != -1) {
            if (interest&ZOOKEEPER_READ) {
                FD_SET(fd, &rfds);
            } else {
                FD_CLR(fd, &rfds);
            }
            if (interest&ZOOKEEPER_WRITE) {
                FD_SET(fd, &wfds);
            } else {
                FD_CLR(fd, &wfds);
            }
        } else {
            fd = 0;
        }
        FD_SET(0, &rfds);
        rc = select(fd+1, &rfds, &wfds, &efds, &tv);
        events = 0;
        if (rc > 0) {
            if (FD_ISSET(fd, &rfds)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wfds)) {
                events |= ZOOKEEPER_WRITE;
            }
        }
        if(batchMode && processed==0){
          //batch mode
          processline(cmd);
          processed=1;
        }
        if (!processed && FD_ISSET(0, &rfds)) {
            int rc;
            int len = sizeof(buffer) - bufoff -1;
            if (len <= 0) {
                fprintf(stderr, "Can't handle lines that long!\n");
                exit(2);
            }
            rc = read(0, buffer+bufoff, len);
            if (rc <= 0) {
                fprintf(stderr, "bye\n");
                break;
            }
            bufoff += rc;
            buffer[bufoff] = '\0';
            while (strchr(buffer, '\n')) {
                char *ptr = strchr(buffer, '\n');
                *ptr = '\0';
                processline(buffer);
                ptr++;
                memmove(buffer, ptr, strlen(ptr)+1);
                bufoff = 0;
            }
        }
        zookeeper_process(zh, events);
    }
#endif
    if (to_send!=0)
        fprintf(stderr,"Recvd %d responses for %d requests sent\n",recvd,sent);
    zookeeper_close(zh);
    return 0;
}
