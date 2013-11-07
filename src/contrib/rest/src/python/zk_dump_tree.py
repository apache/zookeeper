#!/usr/bin/python

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

import getopt
import sys
import simplejson
import urllib2
from base64 import b64decode

printdata = False
fullpath = False

def dump_node(url, depth):
    """Dump the node, then dump children recursively
    
    Arguments:
    - `url`:
    - `depth`:
    """
    req = urllib2.urlopen(url)
    resp = simplejson.load(req)
    if 'Error' in resp:
      raise resp['Error']

    if fullpath:
      name = resp['path']
    else:
      name = '/' + resp['path'].split('/')[-1]

    data64 = resp.get('data64')
    dataUtf8 = resp.get('dataUtf8')
    if data64 and printdata:
      data = b64decode(data64)
      print '%(indent)s%(name)s = b64(%(data64)s) str(%(data)s)' % \
          {'indent':' '*2*depth, 'name':name, 'data64':data64, 'data':data}
    elif dataUtf8 and printdata:
      print '%(indent)s%(name)s = %(data)s' % \
          {'indent':' '*2*depth, 'name':name, 'data':dataUtf8}
    else:
      print '%(indent)s%(name)s' % {'indent':' '*2*depth, 'name':name}

    req = urllib2.urlopen(resp['uri'] + '?view=children')
    resp = simplejson.load(req)

    for child in resp.get('children', []):
        dump_node(resp['child_uri_template']
                  .replace("{child}", urllib2.quote(child)),
                  depth + 1)

def zk_dump_tree(url, root):
    """Dump the tree starting at the roota
    
    Arguments:
    - `root`:
    """
    dump_node(url + '/znodes/v1' + root, 0)

def usage():
    """Usage
    """
    print 'Usage: zk_dump_tree.py [-h|--help -u|--url=url -d|--data -f|--fullpath -r|--root=root]'
    print '  where url is the url of the rest server, data is whether to'
    print '  to include node data on output, root is the znode root'
    print '  fullpath prints the full node path (useful for copy/paste)'

if __name__ == '__main__':
    try:
        opts, args = getopt.getopt(sys.argv[1:],
            "hu:dfr:", ["help", "url=", "data", "fullpath", "root="])
    except getopt.GetoptError, err:
        # print help information and exit:
        print str(err) # will print something like "option -a not recognized"
        usage()
        sys.exit(2)
    url ='http://localhost:9998'
    root = '/'
    for o, a in opts:
        if o in ("-d", "--data"):
            printdata = True
        elif o in ("-h", "--help"):
            usage()
            sys.exit()
        elif o in ("-u", "--url"):
            url = a
        elif o in ("-r", "--root"):
            root = a
        elif o in ("-f", "--fullpath"):
            fullpath = True
        else:
            assert False, "unhandled option"
    
    print 'Accessing REST server at ' + url
    zk_dump_tree(url, root)
