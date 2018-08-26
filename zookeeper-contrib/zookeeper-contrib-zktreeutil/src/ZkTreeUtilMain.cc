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

#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#include <unistd.h>
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <getopt.h>
#include <iostream>
#include "ZkTreeUtil.h"

using namespace zktreeutil;

// The set of "long" options accepted by this program.
static struct option long_options[] = {
    {"help",         no_argument,             0, 'h'},
    {"import",        no_argument,             0, 'I'},
    {"export",     no_argument,             0, 'E'},
    {"update",     no_argument,             0, 'U'},
    {"diff",         no_argument,             0, 'F'},
    {"dump",         no_argument,             0, 'D'},
    {"force",         no_argument,             0, 'f'},
    {"xmlfile",     required_argument,     0, 'x'},
    {"path",         required_argument,     0, 'p'},
    {"depth",         required_argument,     0, 'd'},
    {"zookeeper", required_argument,     0, 'z'},
    {0, 0, 0, 0}
};
static char *short_options = "IEUFDfx:p:d:hz:";

static void usage(int argc, char *argv[])
{
    std::cout << "ZK-tree utility for managing ZK-tree with XML import/export," << std::endl;
    std::cout << "viewing diff between live and saved ZK-tree and performing" << std::endl;
    std::cout << "incremental update of the same." << std::endl;
    std::cout << "Usage: " << argv[0] << " [args-and-values]+" << std::endl;
    std::cout 
        << "\t--import or -I: " 
        << std::endl
        << "\t  Imports the zookeeper tree from XML file. Must be specified with"
        << std::endl
        << "\t  --zookeeper AND --xmlfile options. Optionally takes --path for"
        << std::endl
        << "\t  importing subtree"
        << std::endl;
    std::cout 
        << "\t--export or -E: " 
        << std::endl
        << "\t  Exports the zookeeper tree to XML file. Must be specified with"
        << std::endl
        << "\t  --zookeeper option. Optionally takes --path for exporting subtree"
        << std::endl;
    std::cout
        << "\t--update or -U: "
        << std::endl
        << "\t  Updates zookeeper tree with changes from XML file. Update operation"
        << std::endl
        << "\t  is interactive unless specified with --force option. Must be speci-"
        << std::endl
        << "\t  fied with --zookeeper AND --xmlfile options. Optionally takes --path"
        << std::endl
        << "\t  for updating subtree."
        << std::endl;
    std::cout
        << "\t--diff or -F: "
        << std::endl
        << "\t  Creates a list of diff actions on ZK tree based on XML data. Must"
        << std::endl
        << "\t  be specified with --zookeeper OR --xmlfile options. Optionally takes"
        << std::endl
        << "\t  --path for subtree diff"
        << std::endl;
    std::cout
        << "\t--dump or -D: "
        << std::endl
        << "\t  Dumps the entire ZK (sub)tree to standard output. Must be specified"
        << std::endl
        << "\t  with --zookeeper OR --xmlfile options. Optionally takes --path and"
        << std::endl
        << "\t  --depth for dumping subtree."
        << std::endl;
    std::cout
        << "\t--xmlfile=<filename> or -x <filename>: "
        << std::endl
        << "\t  Zookeeper tree-data XML file."
        << std::endl;
    std::cout
        << "\t--path=<znodepath> or -p <znodepath>: "
        << std::endl
        << "\t  Path to the zookeeper subtree rootnode."
        << std::endl;
    std::cout
        << "\t--depth=<tree-depth> or -d <tree-depth>: "
        << std::endl
        << "\t  Depth of the ZK tree to be dumped (ignored for XML dump)."
        << std::endl;
    std::cout
        << "\t--force or -f: Forces cleanup before import; also used for forceful"
        << std::endl
        << "\t  update. Optionally be specified with --import and --update."
        << std::endl;
    std::cout
        << "\t--help or -h: "
        << std::endl
        << "\t  prints this message"
        << std::endl;
    std::cout
        << "\t--zookeeper=<zkhosts> or -z <zkhosts>: "
        << std::endl
        << "\t  specifies information to connect to zookeeper."
        << std::endl;
}

int main(int argc, char **argv)
{
    if (argc == 1) {
        usage(argc, argv);
        exit(0);
    }

    // Parse the arguments.
     int op = 0;
     bool force = false;
     string zkHosts;
     string xmlFile;
     string path = "/";
     int depth = 0;
     while (1)
     {
         int c = getopt_long(argc, argv, short_options, long_options, 0);
         if (c == -1)
             break;

         switch (c) {
             case 'I': op = c;
                          break;
             case 'E': op = c;
                          break;
             case 'U': op = c;
                          break;
             case 'F': op = c;
                          break;
             case 'D': op = c;
                          break;
             case 'f': force = true;
                          break;
             case 'x': xmlFile = optarg;
                          break;
             case 'p': path = optarg;
                          break;
             case 'd': depth = atoi (optarg);
                          break;
             case 'z': zkHosts = optarg;
                          break;
             case 'h': usage (argc, argv);
                          exit(0);
         }
     }

     ZkTreeUtil zkTreeUtil;
     switch (op)
     {
         case 'I':    {
                            if (zkHosts == "" || xmlFile == "")
                            {
                                std::cout << "[zktreeutil] missing params; please see usage" << std::endl;
                                exit (-1);
                            }
                            zkTreeUtil.loadZkTreeXml (xmlFile);
                            zkTreeUtil.writeZkTree (zkHosts, path, force);
                            std::cout << "[zktreeutil] import successful!" << std::endl;
                            break;
                        }
         case 'E':    {
                            if (zkHosts == "")
                            {
                                std::cout << "[zktreeutil] missing params; please see usage" << std::endl;
                                exit (-1);
                            }
                            zkTreeUtil.loadZkTree (zkHosts, path);
                            zkTreeUtil.dumpZkTree (true);
                            break;
                        }
         case 'U':    {
                            if (zkHosts == "" || xmlFile == "")
                            {
                                std::cout << "[zktreeutil] missing params; please see usage" << std::endl;
                                exit (-1);
                            }
                            zkTreeUtil.loadZkTreeXml (xmlFile);
                            vector< ZkAction > zkActions = zkTreeUtil.diffZkTree (zkHosts, path);
                            int flags = ZkTreeUtil::EXECUTE;
                            if (!force) flags |= ZkTreeUtil::INTERACTIVE;
                            zkTreeUtil.executeZkActions (zkHosts, zkActions, flags);
                            std::cout << "[zktreeutil] update successful!" << std::endl;
                            break;
                        }
         case 'F':    {
                            if (zkHosts == "" || xmlFile == "")
                            {
                                std::cout << "[zktreeutil] missing params; please see usage" << std::endl;
                                exit (-1);
                            }
                            zkTreeUtil.loadZkTreeXml (xmlFile);
                            vector< ZkAction > zkActions = zkTreeUtil.diffZkTree (zkHosts, path);
                            zkTreeUtil.executeZkActions (zkHosts, zkActions, ZkTreeUtil::PRINT);
                            break;
                        }
         case 'D':    {
                            if (zkHosts != "")
                                zkTreeUtil.loadZkTree (zkHosts, path);
                            else if (xmlFile != "")
                                zkTreeUtil.loadZkTreeXml (xmlFile);
                            else
                            {
                                std::cout << "[zktreeutil] missing params; please see usage" << std::endl;
                                exit (-1);
                            }
                            // Dump the ZK tree
                            if (depth) zkTreeUtil.dumpZkTree (false, depth);
                            else zkTreeUtil.dumpZkTree (false);
                            break;
                        }
     }

     exit(0);
}

