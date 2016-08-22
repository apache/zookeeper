
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

import urllib2
import urllib
import simplejson

from contextlib import contextmanager

class RequestWithMethod(urllib2.Request):
    """ Request class that know how to set the method name """
    def __init__(self, *args, **kwargs):
        urllib2.Request.__init__(self, *args, **kwargs)
        self._method = None

    def get_method(self):
        return self._method or \
            urllib2.Request.get_method(self)

    def set_method(self, method):
        self._method = method

class ZooKeeper(object):

    class Error(Exception): pass

    class NotFound(Error): pass

    class ZNodeExists(Error): pass

    class InvalidSession(Error): pass

    class WrongVersion(Error): pass

    def __init__(self, uri = 'http://localhost:9998'):
        self._base = uri
        self._session = None

    def start_session(self, expire=5, id=None):
        """ Create a session and return the ID """
        if id is None:
            url = "%s/sessions/v1/?op=create&expire=%d" % (self._base, expire)
            self._session = self._do_post(url)['id']
        else:
            self._session = id
        return self._session

    def close_session(self):
        """ Close the session on the server """
        if self._session is not None:
            url = '%s/sessions/v1/%s' % (self._base, self._session)
            self._do_delete(url)
            self._session = None

    def heartbeat(self):
        """ Send a heartbeat request. This is needed in order to keep a session alive """
        if self._session is not None:
            url = '%s/sessions/v1/%s' % (self._base, self._session)
            self._do_put(url, '')

    @contextmanager
    def session(self, *args, **kwargs):
        """ Session handling using a context manager """
        yield self.start_session(*args, **kwargs)
        self.close_session()

    def get(self, path):
        """ Get a node """
        url = "%s/znodes/v1%s" % (self._base, path)
        return self._do_get(url)

    def get_children(self, path):
        """ Get all the children for a given path. This function creates a generator """
        for child_path in self.get_children_paths(path, uris=True):
            try:
                yield self._do_get(child_path)
            except ZooKeeper.NotFound:
                continue

    def get_children_paths(self, path, uris=False):
        """ Get the paths for children nodes """
        url = "%s/znodes/v1%s?view=children" % (self._base, path)
        resp = self._do_get(url)
        for child in resp.get('children', []):
            yield child if not uris else resp['child_uri_template']\
              .replace('{child}', urllib2.quote(child))
       
    def create(self, path, data=None, sequence=False, ephemeral=False):
        """ Create a new node. By default this call creates a persistent znode.

        You can also create an ephemeral or a sequential znode.
        """
        ri = path.rindex('/')
        head, name = path[:ri+1], path[ri+1:]
        if head != '/': head = head[:-1]

        flags = {
            'null': 'true' if data is None else 'false',
            'ephemeral': 'true' if ephemeral else 'false',
            'sequence': 'true' if sequence else 'false'
        }
        if ephemeral:
            if self._session:
                flags['session'] = self._session
            else:
                raise ZooKeeper.Error, 'You need a session '\
                    'to create an ephemeral node'
        flags = urllib.urlencode(flags)

        url = "%s/znodes/v1%s?op=create&name=%s&%s" % \
            (self._base, head, name, flags)

        return self._do_post(url, data)

    def set(self, path, data=None, version=-1, null=False):
        """ Set the value of node """
        url = "%s/znodes/v1%s?%s" % (self._base, path, \
            urllib.urlencode({
                'version': version,
                'null': 'true' if null else 'false'
        }))
        return self._do_put(url, data)

    def delete(self, path, version=-1):
        """ Delete a znode """
        if type(path) is list:
            map(lambda el: self.delete(el, version), path)
            return

        url = '%s/znodes/v1%s?%s' % (self._base, path, \
            urllib.urlencode({
                'version':version
        }))
        try:
            return self._do_delete(url)
        except urllib2.HTTPError, e:
            if e.code == 412:
                raise ZooKeeper.WrongVersion(path)
            elif e.code == 404:
                raise ZooKeeper.NotFound(path)
            raise

    def recursive_delete(self, path):
        """ Delete all the nodes from the tree """
        for child in self.get_children_paths(path):
            fp = ("%s/%s" % (path, child)).replace('//', '/')
            self.recursive_delete(fp)
        self.delete(path)

    def exists(self, path):
        """ Do a znode exists """
        try:
            self.get(path)
            return True
        except ZooKeeper.NotFound:
            return False

    def _do_get(self, uri):
        """ Send a GET request and convert errors to exceptions """
        try:
            req = urllib2.urlopen(uri)
            resp = simplejson.load(req)

            if 'Error' in resp:
               raise ZooKeeper.Error(resp['Error'])

            return resp
        except urllib2.HTTPError, e:
            if e.code == 404:
                raise ZooKeeper.NotFound(uri)
            raise

    def _do_post(self, uri, data=None):
        """ Send a POST request and convert errors to exceptions """
        try:
            req = urllib2.Request(uri, {})
            req.add_header('Content-Type', 'application/octet-stream')
            if data is not None:
                req.add_data(data)

            resp = simplejson.load(urllib2.urlopen(req))
            if 'Error' in resp:
                raise ZooKeeper.Error(resp['Error'])
            return resp

        except urllib2.HTTPError, e:
            if e.code == 201:
                return True
            elif e.code == 409:
                raise ZooKeeper.ZNodeExists(uri)
            elif e.code == 401:
                raise ZooKeeper.InvalidSession(uri)
            raise

    def _do_delete(self, uri):
        """ Send a DELETE request """
        req = RequestWithMethod(uri)
        req.set_method('DELETE')
        req.add_header('Content-Type', 'application/octet-stream')
        return urllib2.urlopen(req).read()

    def _do_put(self, uri, data):
        """ Send a PUT request """
        try:
            req = RequestWithMethod(uri)
            req.set_method('PUT')
            req.add_header('Content-Type', 'application/octet-stream')
            if data is not None:
                req.add_data(data)

            return urllib2.urlopen(req).read()
        except urllib2.HTTPError, e:
            if e.code == 412: # precondition failed
                raise ZooKeeper.WrongVersion(uri)
            raise

