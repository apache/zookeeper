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

#include <Python.h>
#include <zookeeper.h>
#include <assert.h>

#define MAX_ZHANDLES 256
static zhandle_t* zhandles[MAX_ZHANDLES];
static int num_zhandles = 0;

/////////////////////////////////////////////
// HELPER FUNCTIONS
typedef struct {
  int zhandle;
  PyObject *callback;
}pywatcher_t;

pywatcher_t *create_pywatcher(int zh, PyObject* cb)
{
  pywatcher_t *ret = (pywatcher_t*)calloc(sizeof(pywatcher_t),1);
  Py_INCREF(cb);
  ret->zhandle = zh; ret->callback = cb;
  return ret;
}

void free_pywatcher( pywatcher_t *pw)
{
  Py_DECREF(pw->callback);
  free(pw);
}


PyObject *build_stat( const struct Stat *stat )
{
  return Py_BuildValue( "{s:K, s:K, s:K, s:K,"
			"s:i, s:i, s:i, s:K,"
			"s:i, s:i, s:K}",
			"czxid", stat->czxid,
			"mzxid", stat->mzxid,
			"ctime", stat->ctime,
			"mtime", stat->mtime,
			"version", stat->version,
			"cversion", stat->cversion,
			"aversion", stat->aversion,
			"ephemeralOwner", stat->ephemeralOwner,
			"dataLength", stat->dataLength,
			"numChildren", stat->numChildren,
			"pzxid", stat->pzxid );
}

PyObject *build_string_vector(const struct String_vector *sv)
{
  PyObject *ret = PyList_New(sv->count);
  int i;
  for (i=0;i<sv->count;++i) 
    {
      PyObject *s = PyString_FromString(sv->data[i]);
      PyList_SetItem(ret, i, s);
    }
  return ret;
}

PyObject *build_acls( const struct ACL_vector *acls )
{
  PyObject *ret = PyList_New( acls->count );
  int i;
  for (i=0;i<acls->count;++i)
    {
      PyObject *acl = Py_BuildValue( "{s:i, s:s, s:s}", 
				     "perms", acls->data[i].perms, 
				     "scheme", acls->data[i].id.scheme,
				     "id", acls->data[i].id.id );
      PyList_SetItem(ret, i, acl);
    }
  return ret;
}

void parse_acls( struct ACL_vector *acls, PyObject *pyacls )
{
  PyObject *a;
  acls->count = PyList_Size( pyacls );
  acls->data = (struct ACL *)calloc( acls->count, sizeof(struct ACL) );
  int i;
  for (i=0;i<acls->count;++i)
    {
      a = PyList_GetItem( pyacls, i );
      // a is now a dictionary
      PyObject *perms = PyDict_GetItemString( a, "perms" );
      acls->data[i].perms = (int32_t)(PyInt_AsLong(perms));
      acls->data[i].id.id = strdup( PyString_AsString( PyDict_GetItemString( a, "id" ) ) );
      acls->data[i].id.scheme = strdup( PyString_AsString( PyDict_GetItemString( a, "scheme" ) ) );
    }
}

void free_acls( struct ACL_vector *acls )
{
  int i;
  for (i=0;i<acls->count;++i)
    {
      free(acls->data[i].id.id);
      free(acls->data[i].id.scheme);
    }
  free(acls->data);
}

/////////////////////////////////////////////
/* Every watcher invocation goes through this dispatch point, which 
   a) acquires the global interpreter lock
   b) unpacks the PyObject to call from the passed context pointer, which
      handily includes the index of the relevant zookeeper handle to pass back to Python.
   c) Makes the call into Python, checking for error conditions which we are responsible fro
      detecting and doing something about (we just print the error and plough right on)
   d) releases the lock after freeing up the context object, which is only used for one
      watch invocation (watches are one-shot)
*/
void watcher_dispatch(zhandle_t *zzh, int type, int state, const char *path, void *context)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)context;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,i,s)", pyw->zhandle,type, state, path);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

static PyObject *pyzookeeper_init(PyObject *self, PyObject *args)
{
  // TODO: Unpack clientid
  const char *host;
  PyObject *watcherfn;
  int recv_timeout;
  PyObject *clientid;

  if (!PyArg_ParseTuple(args, "sOii", &host, &watcherfn, &recv_timeout, &clientid)) 
    return NULL;

  zhandle_t *zh = zookeeper_init( host, watcherfn != Py_None ? watcher_dispatch : NULL, 
				  recv_timeout, NULL, 
				  create_pywatcher(num_zhandles,watcherfn), 0 ); 

  if (zh == NULL)
    {
      PyErr_SetString( PyExc_IOError, "Unknown error" );
      return NULL;
    }

  zhandles[num_zhandles] = zh;
  return Py_BuildValue( "i", num_zhandles++ );
}

///////////////////////////////////////////////////////
// Similar kind of mechanisms for various completion 
// types

void void_completion_dispatch(int rc, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i)", pyw->zhandle, rc);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void stat_completion_dispatch(int rc, const struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,O)", pyw->zhandle,rc, build_stat(stat));
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void data_completion_dispatch(int rc, const char *value, int value_len, const struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,s#,O)", pyw->zhandle,rc, value,value_len, build_stat(stat));
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void strings_completion_dispatch(int rc, const struct String_vector *strings, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,O)", pyw->zhandle,rc, build_string_vector(strings));
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void string_completion_dispatch(int rc, const char *value, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,s)", pyw->zhandle,rc, value);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void acl_completion_dispatch(int rc, struct ACL_vector *acl, struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,O,O)", pyw->zhandle,rc, build_acls(acl), build_stat(stat));
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

///////////////////////////////////////////////////////
// Asynchronous API

PyObject *pyzoo_acreate(PyObject *self, PyObject *args)
{
  int zkhid; char *path; char *value; int valuelen;
  struct ACL_vector acl; int flags;
  PyObject *completion_callback;
  PyObject *pyacls;
  if (!PyArg_ParseTuple(args, "iss#OiO", &zkhid, &path, &value, &valuelen, &pyacls, &flags, &completion_callback))
    return NULL;

  parse_acls(&acl, pyacls);
  int err = zoo_acreate( zhandles[zkhid],
			 path,
			 value,
			 valuelen,
			 &acl,
			 flags,
			 completion_callback != Py_None ? string_completion_dispatch : NULL,
			 completion_callback != Py_None ? create_pywatcher(zkhid, completion_callback ) : NULL );
    
  free_acls(&acl);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_adelete(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version;
  PyObject *completion_callback;
  if (!PyArg_ParseTuple(args, "isiO", &zkhid, &path, &version, &completion_callback))
    return NULL;

  int err = zoo_adelete( zhandles[zkhid],
			 path,
			 version,
			 completion_callback != Py_None ? void_completion_dispatch : NULL,
			 completion_callback != Py_None ? create_pywatcher(zkhid, 
									   completion_callback ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aexists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  PyObject *exists_watch;
  if (!PyArg_ParseTuple(args, "isOO", &zkhid, &path, 
			&exists_watch, &completion_callback))
    return NULL;
  int err = zoo_awexists( zhandles[zkhid],
			  path,
			  exists_watch != Py_None ? watcher_dispatch : NULL,
			  exists_watch != Py_None ? create_pywatcher(zkhid, exists_watch) : NULL,
			  (completion_callback != Py_None) ? stat_completion_dispatch : NULL,
			  (completion_callback != Py_None) ? create_pywatcher(zkhid, completion_callback) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aget(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  PyObject *get_watch;
  if (!PyArg_ParseTuple(args, "isOO", &zkhid, &path, 
			&get_watch, &completion_callback))
    return NULL;

  int err = zoo_awget( zhandles[zkhid],
		       path,
		       get_watch != Py_None ? watcher_dispatch : NULL,
		       get_watch != Py_None ? create_pywatcher(zkhid, get_watch) : NULL,
		       completion_callback != Py_None ? data_completion_dispatch : NULL,
		       completion_callback != Py_None ? 
		       create_pywatcher(zkhid, completion_callback ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

PyObject *pyzoo_aset(PyObject *self, PyObject *args)
{
  int zkhid; char *path; char *buffer; int buflen; int version;
  PyObject *completion_callback;
  if (!PyArg_ParseTuple(args, "iss#iO", &zkhid, &path, &buffer, &buflen, &version, &completion_callback))
    return NULL;

  int err = zoo_aset( zhandles[zkhid],
		      path,
		      buffer,
		      buflen,
		      version,
		      completion_callback != Py_None ? stat_completion_dispatch : NULL,
		      completion_callback != Py_None ? create_pywatcher(zkhid, 
									completion_callback ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

PyObject *pyzoo_aget_children(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  PyObject *get_watch;
  if (!PyArg_ParseTuple(args, "isOO", &zkhid,  &path,
			&get_watch, &completion_callback))
    return NULL;

  int err = zoo_awget_children( zhandles[zkhid],
				path,
				get_watch != Py_None ? watcher_dispatch : NULL,
				get_watch != Py_None ? create_pywatcher(zkhid, get_watch) : NULL,
				completion_callback != Py_None ? strings_completion_dispatch : NULL,
				completion_callback != Py_None ? 
				create_pywatcher(zkhid, completion_callback ) : NULL );    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_async(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  if (!PyArg_ParseTuple(args, "isO", &zkhid, &path, 
			&completion_callback))
    return NULL;

  int err = zoo_async( zhandles[zkhid],
		       path,
		       completion_callback != Py_None ? string_completion_dispatch : NULL,
		       completion_callback != Py_None ? 
		       create_pywatcher(zkhid, completion_callback ) : NULL );    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aget_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  if (!PyArg_ParseTuple(args, "isiOO", &zkhid, &path, 
			&completion_callback))
    return NULL;

  int err = zoo_aget_acl( zhandles[zkhid],
			  path,
			  completion_callback != Py_None ? acl_completion_dispatch : NULL,
			  completion_callback != Py_None ? 
			  create_pywatcher(zkhid, completion_callback ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aset_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version; 
  PyObject *completion_callback, *pyacl;
  struct ACL_vector aclv;
  if (!PyArg_ParseTuple(args, "isiOO", &zkhid, &path, &version, 
			&pyacl, &completion_callback))
    return NULL;
  parse_acls( &aclv, pyacl );
  int err = zoo_aset_acl( zhandles[zkhid],
			  path,
			  version,
			  &aclv,
			  completion_callback != Py_None ? void_completion_dispatch : NULL,
			  completion_callback != Py_None ? 
			  create_pywatcher(zkhid, completion_callback ) : NULL );
  free_acls(&aclv);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

/*PyObject *pyzoo_aget(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback;
  PyObject *get_watch;
  if (!PyArg_ParseTuple(args, "isiOO", &zkhid, &path, 
			&get_watch, &completion_callback))
    return NULL;

  int err = zoo_awget( zhandles[zkhid],
		       path,
		       get_watch != Py_None ? watcher_dispatch : NULL,
		       get_watch != Py_None ? create_pywatcher(zkhid, get_watch) : NULL,
		       completion_callback != Py_None ? void_completion_dispatch : NULL,
		       completion_callback != Py_None ? 
		       create_pywatcher(zkhid, completion_callback ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
  }*/

PyObject *pyzoo_add_auth(PyObject *self, PyObject *args)
{
  int zkhid;
  char *scheme, *cert;
  int certLen;
  PyObject *completion_callback;

  if (!PyArg_ParseTuple(args, "iss#O", &zkhid, &scheme, &cert, &certLen, &completion_callback))
    return NULL;
  
  int err = zoo_add_auth( zhandles[zkhid],
			  scheme,
			  cert,
			  certLen,
			  completion_callback != Py_None ? void_completion_dispatch : NULL,
			  completion_callback != Py_None ? create_pywatcher(zkhid, completion_callback) : NULL );
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

///////////////////////////////////////////////////////
// synchronous API

static PyObject *pyzoo_create(PyObject *self, PyObject *args)
{
  char *path;
  int zkhid;
  char* values;
  int valuelen;
  PyObject *acl = NULL;  
  int flags = 0;
  char realbuf[256];
  const int maxbuf_len = 256;
  if (!PyArg_ParseTuple(args, "iss#Oi",&zkhid, &path, &values, &valuelen,&acl,&flags))
    return NULL;
  struct ACL_vector aclv;
  parse_acls(&aclv,acl);
  zhandle_t *zh = zhandles[zkhid];
  int err = zoo_create(zh, path, values, valuelen, &aclv, flags, realbuf, maxbuf_len);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }

  return Py_BuildValue("i", err);;
}

static PyObject *pyzoo_delete(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  int version;
  if (!PyArg_ParseTuple(args, "isi",&zkhid,&path,&version))
    return NULL;
  zhandle_t *zh = zhandles[zkhid];
  int err = zoo_delete(zh, path, version);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

static PyObject *pyzoo_exists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; PyObject *watcherfn = NULL;
  struct Stat stat;
  if (!PyArg_ParseTuple(args, "isO", &zkhid, &path, &watcherfn))
    return NULL;
  zhandle_t *zh = zhandles[zkhid];
  pywatcher_t *pw = NULL;
  if (watcherfn != Py_None) 
    pw = create_pywatcher(zkhid, watcherfn);
  int err = zoo_wexists(zh,  path, watcherfn != Py_None ? watcher_dispatch : NULL, pw, &stat);
  if (err != ZOK && err != ZNONODE)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      free_pywatcher( pw );
      return NULL;
    }
  if (err == ZOK)
    return build_stat( &stat );
  if (err == ZNONODE)
    return Py_None; // This isn't exceptional
  return NULL;
}

static PyObject *pyzoo_get_children(PyObject *self, PyObject *args)
{
  // TO DO: Does Python copy the string or the reference? If it's the former
  // we should free the String_vector
  int zkhid;
  char *path;
  PyObject *watcherfn = NULL;
  struct String_vector strings; 
  if (!PyArg_ParseTuple(args, "isO", &zkhid, &path, &watcherfn))
    return NULL;
  pywatcher_t *pw = NULL;
  if (watcherfn != Py_None)
    pw = create_pywatcher( zkhid, watcherfn );
  int err = zoo_wget_children( zhandles[zkhid], path, 
			       watcherfn != Py_None ? watcher_dispatch : NULL, 
			       pw, &strings );

  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      return NULL;
    }

  PyObject *ret = PyList_New(strings.count);
  int i;
  for (i=0;i<strings.count;++i) 
    {
      PyObject *s = PyString_FromString(strings.data[i]);
      PyList_SetItem(ret, i, s);
    }
  return ret;
}

static PyObject *pyzoo_set(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char *buffer;
  int buflen;
  int version; 
  if (!PyArg_ParseTuple(args, "iss#i", &zkhid, &path, &buffer, &buflen, &version))
    return NULL;

  assert(zkhid < num_zhandles);
  int err = zoo_set(zhandles[zkhid], path, buffer, buflen, version);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      return NULL;
    }

  return Py_BuildValue("i", err);
}

static PyObject *pyzoo_set2(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char *buffer;
  int buflen;
  int version; 
  if (!PyArg_ParseTuple(args, "iss#i", &zkhid, &path, &buffer, &buflen, &version))
    return NULL;
  struct Stat *stat = NULL;
  int err = zoo_set2(zhandles[zkhid], path, buffer, buflen, version, stat);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      return NULL;
    }

  return Py_BuildValue("O", build_stat(stat));
}

static PyObject *pyzoo_get(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char buffer[512];
  memset(buffer,0,sizeof(char)*512);
  int buffer_len=512;
  struct Stat stat;
  PyObject *watcherfn = NULL;
  pywatcher_t *pw = NULL;
  if (!PyArg_ParseTuple(args, "isO", &zkhid, &path, &watcherfn))
    return NULL;

  if (watcherfn != Py_None)
    pw = create_pywatcher( zkhid, watcherfn );
  int err = zoo_wget(zhandles[zkhid], path, 
		     watcherfn != Py_None ? watcher_dispatch : NULL, 
		     pw, buffer, 
		     &buffer_len, &stat);
  PyObject *stat_dict = build_stat( &stat );
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      return NULL;
    }

  return Py_BuildValue( "(s#,O)", buffer,buffer_len, stat_dict );
}

PyObject *pyzoo_get_acl(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  struct ACL_vector acl;
  struct Stat stat;
  if (!PyArg_ParseTuple(args, "is", &zkhid, &path))
    return NULL;
  int err = zoo_get_acl( zhandles[zkhid], path, &acl, &stat );
  if (err != ZOK) 
    { 
      PyErr_SetString( PyExc_IOError, zerror(err) ); 
      return NULL; 
    }
  PyObject *pystat = build_stat( &stat );
  PyObject *acls = build_acls( &acl );
  return Py_BuildValue( "(O,O)", pystat, acls );
}

PyObject *pyzoo_set_acl(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  int version;
  PyObject *pyacls;
  struct ACL_vector acl;
  if (!PyArg_ParseTuple(args, "isiO", &zkhid, &path, &version, &pyacls))
    return NULL;

  parse_acls(&acl, pyacls);
  int err = zoo_set_acl(zhandles[zkhid], path, version, &acl );
  free_acls(&acl);
  if (err != ZOK)
    {
      PyErr_SetString( PyExc_IOError, zerror(err) );
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

///////////////////////////////////////////////////
// session and context methods
PyObject *pyzoo_close(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  int ret = zookeeper_close(zhandles[zkhid]);
  return Py_BuildValue("i", ret);
}

PyObject *pyzoo_client_id(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  const clientid_t *cid = zoo_client_id(zhandles[zkhid]);
  return Py_BuildValue("(L,s)", cid->client_id, cid->passwd);
}

PyObject *pyzoo_get_context(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  PyObject *context = NULL;
  context = (PyObject*)zoo_get_context(zhandles[zkhid]);
  if (context) return context;
  return Py_None;  
}

PyObject *pyzoo_set_context(PyObject *self, PyObject *args)
{
  int zkhid;
  PyObject *context;
  if (!PyArg_ParseTuple(args, "iO", &zkhid, &context))
    return NULL;
  zoo_set_context(zhandles[zkhid], (void*)context);
  return Py_None;
}
///////////////////////////////////////////////////////
// misc
// static PyObject *generic_python_callback = NULL;
// void generic_watcher(zhandle_t *zh, int type, int state, const char *path, void *watcherCtx)
// {
//   if (generic_python_callback == NULL)
//     return;  
// }

PyObject *pyzoo_set_watcher(PyObject *self, PyObject *args)
{
  /*  int zkhid;
  PyObject *watcherfn;
  if (!PyArg_ParseTuple(args, "iO", &zkhid, &watcherfn))
    return NULL;
  watcher_fn cwatcher = zoo_set_watcher(
  */
  return NULL; // TODO
}

PyObject *pyzoo_state(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;

  int state = zoo_state(zhandles[zkhid]);
  return Py_BuildValue("i",state);
}


// Synchronous calls return ZOK or throw an exception, but async calls get
// an integer so should use this. This could perhaps use standardising. 
PyObject *pyzerror(PyObject *self, PyObject *args)
{
  int rc;
  if (!PyArg_ParseTuple(args,"i", &rc))
    return NULL;
  return Py_BuildValue("s", zerror(rc));
}

PyObject *pyzoo_recv_timeout(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;

  int recv_timeout = zoo_recv_timeout(zhandles[zkhid]);
  return Py_BuildValue("i",recv_timeout);  
}

PyObject *pyis_unrecoverable(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;
  int ret = is_unrecoverable(zhandles[zkhid]);
  return Py_BuildValue("i",ret); // TODO: make this a boolean
}

PyObject *pyzoo_set_debug_level(PyObject *self, PyObject *args)
{
  int loglevel;
  if (!PyArg_ParseTuple(args, "i", &loglevel))
      return NULL;
  zoo_set_debug_level((ZooLogLevel)loglevel);
  return Py_None;
}
static PyObject *log_stream = NULL;

PyObject *pyzoo_set_log_stream(PyObject *self, PyObject *args)
{
  PyObject *pystream = NULL;
  if (!PyArg_ParseTuple(args,"O",&pystream))
    return NULL;
  if (!PyFile_Check(pystream))
    return NULL;
  if (log_stream != NULL) {
    Py_DECREF(log_stream);
  }
  log_stream = pystream;
  Py_INCREF(log_stream);
  zoo_set_log_stream(PyFile_AsFile(log_stream));

  return Py_None;
}

PyObject *pyzoo_deterministic_conn_order(PyObject *self, PyObject *args)
{
  int yesOrNo;
  if (!PyArg_ParseTuple(args, "i",&yesOrNo))
    return NULL;
  zoo_deterministic_conn_order( yesOrNo );
  return Py_None;
}

///////////////////////////////////////////////////

static PyMethodDef ZooKeeperMethods[] = {
  {"init", pyzookeeper_init, METH_VARARGS },
  {"create",pyzoo_create, METH_VARARGS },
  {"delete",pyzoo_delete, METH_VARARGS },
  {"get_children", pyzoo_get_children, METH_VARARGS },
  {"set", pyzoo_set, METH_VARARGS },
  {"set2", pyzoo_set2, METH_VARARGS },
  {"get",pyzoo_get, METH_VARARGS },
  {"exists",pyzoo_exists, METH_VARARGS },
  {"get_acl", pyzoo_get_acl, METH_VARARGS },
  {"set_acl", pyzoo_set_acl, METH_VARARGS },
  {"close", pyzoo_close, METH_VARARGS },
  {"client_id", pyzoo_client_id, METH_VARARGS },
  {"get_context", pyzoo_get_context, METH_VARARGS },
  {"set_context", pyzoo_set_context, METH_VARARGS },
  //  {"set_watcher", pyzoo_set_watcher, METH_VARARGS }, // Not yet implemented
  {"state", pyzoo_state, METH_VARARGS },
  {"recv_timeout",pyzoo_recv_timeout, METH_VARARGS },
  {"is_unrecoverable",pyis_unrecoverable, METH_VARARGS },
  {"set_debug_level",pyzoo_set_debug_level, METH_VARARGS }, 
  {"set_log_stream",pyzoo_set_log_stream, METH_VARARGS },
  {"deterministic_conn_order",pyzoo_deterministic_conn_order, METH_VARARGS },
  {"acreate", pyzoo_acreate, METH_VARARGS },
  {"adelete", pyzoo_adelete, METH_VARARGS },
  {"aexists", pyzoo_aexists, METH_VARARGS },
  {"aget", pyzoo_aget, METH_VARARGS },
  {"aset", pyzoo_aset, METH_VARARGS },
  {"aget_children", pyzoo_aget_children, METH_VARARGS },
  {"async", pyzoo_async, METH_VARARGS },
  {"aget_acl", pyzoo_aget_acl, METH_VARARGS },
  {"aset_acl", pyzoo_aset_acl, METH_VARARGS },
  {"zerror", pyzerror, METH_VARARGS },
  {NULL, NULL}
};

#define ADD_INTCONSTANT(x) PyModule_AddIntConstant(module, #x, ZOO_##x)
#define ADD_INTCONSTANTZ(x) PyModule_AddIntConstant(module, #x, Z##x)

PyMODINIT_FUNC initzookeeper() {
  PyEval_InitThreads();
  PyObject *module = Py_InitModule("zookeeper", ZooKeeperMethods );
  ADD_INTCONSTANT(PERM_READ);
  ADD_INTCONSTANT(PERM_WRITE);
  ADD_INTCONSTANT(PERM_CREATE);
  ADD_INTCONSTANT(PERM_DELETE);
  ADD_INTCONSTANT(PERM_ALL);
  ADD_INTCONSTANT(PERM_ADMIN);

  ADD_INTCONSTANT(EPHEMERAL);
  ADD_INTCONSTANT(SEQUENCE);

  ADD_INTCONSTANT(EXPIRED_SESSION_STATE);
  ADD_INTCONSTANT(AUTH_FAILED_STATE);
  ADD_INTCONSTANT(CONNECTING_STATE);
  ADD_INTCONSTANT(ASSOCIATING_STATE);
  ADD_INTCONSTANT(CONNECTED_STATE);

  ADD_INTCONSTANT(CREATED_EVENT);
  ADD_INTCONSTANT(DELETED_EVENT);
  ADD_INTCONSTANT(CHANGED_EVENT);
  ADD_INTCONSTANT(CHILD_EVENT);
  ADD_INTCONSTANT(SESSION_EVENT);
  ADD_INTCONSTANT(NOTWATCHING_EVENT);

  ADD_INTCONSTANT(LOG_LEVEL_ERROR);
  ADD_INTCONSTANT(LOG_LEVEL_WARN);
  ADD_INTCONSTANT(LOG_LEVEL_INFO);
  ADD_INTCONSTANT(LOG_LEVEL_DEBUG);

  ADD_INTCONSTANTZ(SYSTEMERROR);
  ADD_INTCONSTANTZ(RUNTIMEINCONSISTENCY);
  ADD_INTCONSTANTZ(DATAINCONSISTENCY);
  ADD_INTCONSTANTZ(CONNECTIONLOSS);
  ADD_INTCONSTANTZ(MARSHALLINGERROR);
  ADD_INTCONSTANTZ(UNIMPLEMENTED);
  ADD_INTCONSTANTZ(OPERATIONTIMEOUT);
  ADD_INTCONSTANTZ(BADARGUMENTS);
  ADD_INTCONSTANTZ(INVALIDSTATE);

  ADD_INTCONSTANTZ(OK);  
  ADD_INTCONSTANTZ(APIERROR);
  ADD_INTCONSTANTZ(NONODE);
  ADD_INTCONSTANTZ(NOAUTH);
  ADD_INTCONSTANTZ(BADVERSION);
  ADD_INTCONSTANTZ(NOCHILDRENFOREPHEMERALS);
  ADD_INTCONSTANTZ(NODEEXISTS);
  ADD_INTCONSTANTZ(NOTEMPTY);
  ADD_INTCONSTANTZ(SESSIONEXPIRED);
  ADD_INTCONSTANTZ(INVALIDCALLBACK);
  ADD_INTCONSTANTZ(INVALIDACL);
  ADD_INTCONSTANTZ(AUTHFAILED);
  ADD_INTCONSTANTZ(CLOSING);
  ADD_INTCONSTANTZ(NOTHING);
}

int main(int argc, char **argv)
{
  Py_SetProgramName(argv[0]);
  Py_Initialize();
  initzookeeper();
  return 0;
}
