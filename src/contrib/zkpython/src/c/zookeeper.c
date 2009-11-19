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
		
//////////////////////////////////////////////
// EXCEPTIONS
PyObject *ZooKeeperException = NULL;
PyObject *SystemErrorException;
PyObject *RuntimeInconsistencyException;
PyObject *DataInconsistencyException;
PyObject *ConnectionLossException;
PyObject *MarshallingErrorException;
PyObject *UnimplementedException;
PyObject *OperationTimeoutException;
PyObject *BadArgumentsException;
PyObject *InvalidStateException;

PyObject *ApiErrorException;
PyObject *NoNodeException;
PyObject *NoAuthException;
PyObject *NodeExistsException;
PyObject *BadVersionException;
PyObject *NoChildrenForEphemeralsException;
PyObject *NotEmptyException;
PyObject *SessionExpiredException;
PyObject *SessionMovedException;
PyObject *InvalidCallbackException;
PyObject *InvalidACLException;
PyObject *AuthFailedException;
PyObject *ClosingException;
PyObject *NothingException;

PyObject *err_to_exception(int errcode) {
  switch (errcode) {
    case ZSYSTEMERROR:
      return SystemErrorException;
    case ZRUNTIMEINCONSISTENCY:
      return RuntimeInconsistencyException;
    case ZDATAINCONSISTENCY:
      return DataInconsistencyException;
    case ZCONNECTIONLOSS:
      return ConnectionLossException;
    case ZMARSHALLINGERROR:
      return MarshallingErrorException;
    case ZUNIMPLEMENTED:
      return UnimplementedException;
    case ZOPERATIONTIMEOUT:
      return OperationTimeoutException;
    case ZBADARGUMENTS:
      return BadArgumentsException;
    case ZAPIERROR:
      return ApiErrorException;
    case ZNONODE:
      return NoNodeException;
    case ZNOAUTH:
      return NoAuthException;
    case ZBADVERSION:
      return BadVersionException;
    case ZNOCHILDRENFOREPHEMERALS:
      return NoChildrenForEphemeralsException;
    case ZNODEEXISTS:
      return NodeExistsException;
    case ZINVALIDACL:
      return InvalidACLException;
    case ZAUTHFAILED:
      return AuthFailedException;
    case ZNOTEMPTY:
      return NotEmptyException;
    case ZSESSIONEXPIRED:
      return SessionExpiredException;
    case ZINVALIDCALLBACK:
      return InvalidCallbackException;
    case ZSESSIONMOVED:
      return SessionMovedException;
      
    case ZOK:
    default:
      return NULL;
    }
}


#define CHECK_ZHANDLE(z) if ( (z) < 0 || (z) >= num_zhandles) {			\
  PyErr_SetString( ZooKeeperException, "zhandle out of range" ); \	
return NULL;																											\
} else if ( zhandles[(z)] == NULL ) {															\
		PyErr_SetString(ZooKeeperException, "zhandle already freed"); \
		return NULL;																									\
	}

/////////////////////////////////////////////
// HELPER FUNCTIONS
typedef struct {
  int zhandle;
  PyObject *callback;
  int permanent;
}pywatcher_t;

// This array exists because we need to ref. count 
// the global watchers for each connection - but they're 
// inaccessible without pulling in zk_adaptor.h, which I'm
// trying to avoid. 
static pywatcher_t **watchers;

// We keep an array of zhandles available for use.
// When a zhandle is correctly closed, the C client
// frees the memory so we set the zhandles[i] entry to NULL.
// This entry can then be re-used 
static zhandle_t** zhandles = NULL;
static int num_zhandles = 0;
static int max_zhandles = 0;
#define REAL_MAX_ZHANDLES 32768

// Allocates an initial zhandle and watcher array
void init_zhandles(int num) {
	zhandles = malloc(sizeof(zhandle_t*)*num);
	watchers = malloc(sizeof(pywatcher_t*)*num);
	max_zhandles = num;
	num_zhandles = 0;
	memset(zhandles, 0, sizeof(zhandle_t*)*max_zhandles);
}

// Note that the following zhandle functions are not 
// thread-safe. The C-Python runtime does not seem to
// pre-empt a thread that is in a C module, so there's
// no need for synchronisation. 

// Doubles the size of the zhandle / watcher array
// Returns 0 if the new array would be >= REAL_MAX_ZHANDLES
// in size. 
int resize_zhandles() {
	zhandle_t **tmp = zhandles;
	pywatcher_t ** wtmp = watchers;
	if (max_zhandles >= REAL_MAX_ZHANDLES >> 1) {
		return -1;
	}
	max_zhandles *= 2;
	zhandles = malloc(sizeof(zhandle_t*)*max_zhandles);
	memset(zhandles, 0, sizeof(zhandle_t*)*max_zhandles);
	memcpy(zhandles, tmp, sizeof(zhandle_t*)*max_zhandles/2);

	watchers = malloc(sizeof(pywatcher_t*)*max_zhandles);
	memset(watchers, 0, sizeof(pywatcher_t*)*max_zhandles);
	memcpy(watchers, wtmp, sizeof(pywatcher_t*)*max_zhandles/2);

	free(wtmp);
	free(tmp);
	return 0;
}

// Find a free zhandle - this is expensive, but we 
// expect it to be infrequently called.
// There are optimisations that can be made if this turns out
// to be problematic. 
// Returns -1 if no free handle is found.
unsigned int next_zhandle() {
	int i = 0;
	for (i=0;i<max_zhandles;++i) {
		if (zhandles[i] == NULL) {
			num_zhandles++;
			return i;
		}
	}

	return -1;
}

/////////////////////////////////////
// Pywatcher funcs

pywatcher_t *create_pywatcher(int zh, PyObject* cb, int permanent)
{
  pywatcher_t *ret = (pywatcher_t*)calloc(sizeof(pywatcher_t),1);
  Py_INCREF(cb);
  ret->zhandle = zh; ret->callback = cb; ret->permanent = permanent;
  return ret;
}

void free_pywatcher( pywatcher_t *pw)
{
  Py_DECREF(pw->callback);
  free(pw);
}


PyObject *build_stat( const struct Stat *stat )
{
	if (stat == NULL) { 
		return Py_None;
	}
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
	if (!sv) {
		return PyList_New(0);
	}
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
   c) Makes the call into Python, checking for error conditions which we are responsible for
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
  if (pyw->permanent == 0)
    free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

static PyObject *pyzookeeper_init(PyObject *self, PyObject *args)
{
  const char *host;
  PyObject *watcherfn = Py_None;
  int recv_timeout = 10000;
  //  int clientid = -1;
  clientid_t cid;
  cid.client_id = -1;
  const char *passwd;
	int handle = next_zhandle();
	if (handle == -1) {
		resize_zhandles();
		handle = next_zhandle();
	}

	if (handle == -1) {
		PyErr_SetString(ZooKeeperException,"Couldn't find a free zhandle, something is very wrong");
		return NULL;
	}

  if (!PyArg_ParseTuple(args, "s|Oi(Ls)", &host, &watcherfn, &recv_timeout, &cid.client_id, &passwd)) 
    return NULL;
  
  if (cid.client_id != -1) {
    strncpy(cid.passwd, passwd, 16*sizeof(char));
  }
  pywatcher_t *pyw = NULL;
  if (watcherfn != Py_None) {
    pyw = create_pywatcher(handle, watcherfn,1);
  }
  watchers[handle] = pyw;
  zhandle_t *zh = zookeeper_init( host, watcherfn != Py_None ? watcher_dispatch : NULL, 
				  recv_timeout, cid.client_id == -1 ? 0 : &cid, 
				  pyw,
				  0 ); 

  if (zh == NULL)
    {
      PyErr_SetString( ZooKeeperException, "Could not internally obtain zookeeper handle" );
      return NULL;
    }

  zhandles[handle] = zh;
  return Py_BuildValue( "i", handle);
}

///////////////////////////////////////////////////////
// Similar kind of mechanisms for various completion 
// types

void void_completion_dispatch(int rc, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL)
    return;
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
  if (pyw == NULL)
    return;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,N)", pyw->zhandle,rc, build_stat(stat));
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

void data_completion_dispatch(int rc, const char *value, int value_len, const struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL)
    return;
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
  if (pyw == NULL)
    return;
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
  if (pyw == NULL)
    return;
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
  if (pyw == NULL)
    return;
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
  struct ACL_vector acl; int flags = 0;
  PyObject *completion_callback = Py_None;
  PyObject *pyacls = Py_None;
  if (!PyArg_ParseTuple(args, "iss#O|iO", &zkhid, &path, &value, &valuelen, &pyacls, &flags, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  if (pyacls != Py_None)
    parse_acls(&acl, pyacls);
  int err = zoo_acreate( zhandles[zkhid],
			 path,
			 value,
			 valuelen,
			 pyacls == Py_None ? NULL : &acl,
			 flags,
			 string_completion_dispatch,
			 completion_callback != Py_None ? create_pywatcher(zkhid, completion_callback,0 ) : NULL );
    
  free_acls(&acl);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_adelete(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version = -1;
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|iO", &zkhid, &path, &version, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_adelete( zhandles[zkhid],
			 path,
			 version,
			 void_completion_dispatch,
			 completion_callback != Py_None ? create_pywatcher(zkhid, 
									   completion_callback,
									   0 ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

PyObject *pyzoo_aexists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *exists_watch = Py_None;
  if (!PyArg_ParseTuple(args, "is|OO", &zkhid, &path, 
			&exists_watch, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_awexists( zhandles[zkhid],
			  path,
			  exists_watch != Py_None ? watcher_dispatch : NULL,
			  exists_watch != Py_None ? create_pywatcher(zkhid, exists_watch,0) : NULL,
			  stat_completion_dispatch,
			  (completion_callback != Py_None) ? create_pywatcher(zkhid, completion_callback,0) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aget(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *get_watch = Py_None;
  if (!PyArg_ParseTuple(args, "is|OO", &zkhid, &path, 
			&get_watch, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_awget( zhandles[zkhid],
		       path,
		       get_watch != Py_None ? watcher_dispatch : NULL,
		       get_watch != Py_None ? create_pywatcher(zkhid, get_watch,0) : NULL,
		       data_completion_dispatch,
		       completion_callback != Py_None ? 
		       create_pywatcher(zkhid, completion_callback,0 ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

PyObject *pyzoo_aset(PyObject *self, PyObject *args)
{
  int zkhid; char *path; char *buffer; int buflen; int version=-1;
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "iss#|iO", &zkhid, &path, &buffer, &buflen, &version, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_aset( zhandles[zkhid],
		      path,
		      buffer,
		      buflen,
		      version,
		      stat_completion_dispatch,
		      completion_callback != Py_None ? create_pywatcher(zkhid, 
									completion_callback,
									0 ) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

PyObject *pyzoo_aget_children(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *get_watch;
  if (!PyArg_ParseTuple(args, "is|OO", &zkhid,  &path,
			&get_watch, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_awget_children( zhandles[zkhid],
				path,
				get_watch != Py_None ? watcher_dispatch : NULL,
				get_watch != Py_None ? create_pywatcher(zkhid, get_watch,0) : NULL,
				strings_completion_dispatch,
				completion_callback != Py_None ? 
				create_pywatcher(zkhid, completion_callback,0) : NULL );    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_async(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, 
			&completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_async( zhandles[zkhid],
		       path,
		       string_completion_dispatch,
		       completion_callback != Py_None ? 
		       create_pywatcher(zkhid, completion_callback,0) : NULL );    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aget_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, 
			&completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_aget_acl( zhandles[zkhid],
			  path,
			  acl_completion_dispatch,
			  completion_callback != Py_None ? 
			  create_pywatcher(zkhid, completion_callback,0) : NULL );
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_aset_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version; 
  PyObject *completion_callback = Py_None, *pyacl;
  struct ACL_vector aclv;
  if (!PyArg_ParseTuple(args, "isiO|O", &zkhid, &path, &version, 
			&pyacl, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  parse_acls( &aclv, pyacl );
  int err = zoo_aset_acl( zhandles[zkhid],
			  path,
			  version,
			  &aclv,
			  void_completion_dispatch,
			  completion_callback != Py_None ? 
			  create_pywatcher(zkhid, completion_callback,0) : NULL );
  free_acls(&aclv);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

PyObject *pyzoo_add_auth(PyObject *self, PyObject *args)
{
  int zkhid;
  char *scheme, *cert;
  int certLen;
  PyObject *completion_callback;

  if (!PyArg_ParseTuple(args, "iss#O", &zkhid, &scheme, &cert, &certLen, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  
  int err = zoo_add_auth( zhandles[zkhid],
			  scheme,
			  cert,
			  certLen,
			  completion_callback != Py_None ? void_completion_dispatch : NULL,
			  completion_callback != Py_None ? create_pywatcher(zkhid, completion_callback,0) : NULL );
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
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
  if (!PyArg_ParseTuple(args, "iss#O|i",&zkhid, &path, &values, &valuelen,&acl,&flags))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  struct ACL_vector aclv;
  parse_acls(&aclv,acl);
  zhandle_t *zh = zhandles[zkhid];
  int err = zoo_create(zh, path, values, valuelen, &aclv, flags, realbuf, maxbuf_len);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }

  return Py_BuildValue("s", realbuf);
}

static PyObject *pyzoo_delete(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  int version = -1;
  if (!PyArg_ParseTuple(args, "is|i",&zkhid,&path,&version))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  zhandle_t *zh = zhandles[zkhid];
  int err = zoo_delete(zh, path, version);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

// Returns None if the node does not exists
static PyObject *pyzoo_exists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; PyObject *watcherfn = Py_None;
  struct Stat stat;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, &watcherfn))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  zhandle_t *zh = zhandles[zkhid];
  pywatcher_t *pw = NULL;
  if (watcherfn != Py_None) 
    pw = create_pywatcher(zkhid, watcherfn,0);
  int err = zoo_wexists(zh,  path, watcherfn != Py_None ? watcher_dispatch : NULL, pw, &stat);
  if (err != ZOK && err != ZNONODE)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
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
  PyObject *watcherfn = Py_None;
  struct String_vector strings; 
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, &watcherfn))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  pywatcher_t *pw = NULL;
  if (watcherfn != Py_None)
    pw = create_pywatcher( zkhid, watcherfn, 0 );
  int err = zoo_wget_children(zhandles[zkhid], path, 
			       watcherfn != Py_None ? watcher_dispatch : NULL, 
			       pw, &strings );

  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
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
  int version = -1; 
  if (!PyArg_ParseTuple(args, "iss#|i", &zkhid, &path, &buffer, &buflen, &version))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  int err = zoo_set(zhandles[zkhid], path, buffer, buflen, version);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
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
  int version = -1; 
  if (!PyArg_ParseTuple(args, "iss#|i", &zkhid, &path, &buffer, &buflen, &version))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  struct Stat *stat = NULL;
  int err = zoo_set2(zhandles[zkhid], path, buffer, buflen, version, stat);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }

  return build_stat(stat);
}

static PyObject *pyzoo_get(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char buffer[512];
  memset(buffer,0,sizeof(char)*512);
  int buffer_len=512;
  struct Stat stat;
  PyObject *watcherfn = Py_None;
  pywatcher_t *pw = NULL;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, &watcherfn))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  if (watcherfn != Py_None)
    pw = create_pywatcher( zkhid, watcherfn,0 );
  int err = zoo_wget(zhandles[zkhid], path, 
		     watcherfn != Py_None ? watcher_dispatch : NULL, 
		     pw, buffer, 
		     &buffer_len, &stat);
  PyObject *stat_dict = build_stat( &stat );
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }

  return Py_BuildValue( "(s#,N)", buffer,buffer_len, stat_dict );
}

PyObject *pyzoo_get_acl(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  struct ACL_vector acl;
  struct Stat stat;
  if (!PyArg_ParseTuple(args, "is", &zkhid, &path))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  int err = zoo_get_acl( zhandles[zkhid], path, &acl, &stat );
  if (err != ZOK) 
    { 
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL; 
    }
  PyObject *pystat = build_stat( &stat );
  PyObject *acls = build_acls( &acl );
  return Py_BuildValue( "(N,N)", pystat, acls );
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
  CHECK_ZHANDLE(zkhid);
  parse_acls(&acl, pyacls);
  int err = zoo_set_acl(zhandles[zkhid], path, version, &acl );
  free_acls(&acl);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
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
  CHECK_ZHANDLE(zkhid);
  int ret = zookeeper_close(zhandles[zkhid]);
	zhandles[zkhid] = NULL; // The zk C client frees the zhandle
  return Py_BuildValue("i", ret);
}

PyObject *pyzoo_client_id(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  const clientid_t *cid = zoo_client_id(zhandles[zkhid]);
  return Py_BuildValue("(Ls)", cid->client_id, cid->passwd);
}

PyObject *pyzoo_get_context(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
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
  CHECK_ZHANDLE(zkhid);
  PyObject *py_context = (PyObject*)zoo_get_context(zhandles[zkhid]);
  if (py_context != NULL) {
    Py_DECREF(py_context);
  }
  Py_INCREF(context);
  zoo_set_context(zhandles[zkhid], (void*)context);
  return Py_None;
}
///////////////////////////////////////////////////////
// misc

PyObject *pyzoo_set_watcher(PyObject *self, PyObject *args)
{
  int zkhid;
  PyObject *watcherfn;
  if (!PyArg_ParseTuple(args, "iO", &zkhid, &watcherfn))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  pywatcher_t *pyw = watchers[zkhid];
  if (pyw != NULL) {
    free_pywatcher( pyw );
  }
  
  pyw = create_pywatcher(zkhid, watcherfn,1);
  watchers[zkhid] = pyw;
  zoo_set_watcher(zhandles[zkhid], watcher_dispatch);
  zoo_set_context(zhandles[zkhid], pyw);
  
  return Py_None; 
}

PyObject *pyzoo_state(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
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
  CHECK_ZHANDLE(zkhid);
  int recv_timeout = zoo_recv_timeout(zhandles[zkhid]);
  return Py_BuildValue("i",recv_timeout);  
}

PyObject *pyis_unrecoverable(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
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

#include "pyzk_docstrings.h"

static PyMethodDef ZooKeeperMethods[] = {
  {"init", pyzookeeper_init, METH_VARARGS, pyzk_init_doc },
  {"create",pyzoo_create, METH_VARARGS, pyzk_create_doc },
  {"delete",pyzoo_delete, METH_VARARGS, pyzk_delete_doc },
  {"get_children", pyzoo_get_children, METH_VARARGS, pyzk_get_children_doc },
  {"set", pyzoo_set, METH_VARARGS, pyzk_set_doc },
  {"set2", pyzoo_set2, METH_VARARGS, pyzk_set2_doc },
  {"get",pyzoo_get, METH_VARARGS, pyzk_get_doc },
  {"exists",pyzoo_exists, METH_VARARGS, pyzk_exists_doc },
  {"get_acl", pyzoo_get_acl, METH_VARARGS, pyzk_get_acl_doc },
  {"set_acl", pyzoo_set_acl, METH_VARARGS, pyzk_set_acl_doc },
  {"close", pyzoo_close, METH_VARARGS, pyzk_close_doc },
  {"client_id", pyzoo_client_id, METH_VARARGS, pyzk_client_id_doc },
  // DO NOT USE get / set_context. Context is used internally
  // to pass the python watcher to a dispatch function. If you want 
  // context, set it through set_watcher. 
  //  {"get_context", pyzoo_get_context, METH_VARARGS, "" },
  // {"set_context", pyzoo_set_context, METH_VARARGS, "" },
  {"set_watcher", pyzoo_set_watcher, METH_VARARGS }, 
  {"state", pyzoo_state, METH_VARARGS, pyzk_state_doc },
  {"recv_timeout",pyzoo_recv_timeout, METH_VARARGS },
  {"is_unrecoverable",pyis_unrecoverable, METH_VARARGS, pyzk_is_unrecoverable_doc },
  {"set_debug_level",pyzoo_set_debug_level, METH_VARARGS, pyzk_set_debug_level_doc }, 
  {"set_log_stream",pyzoo_set_log_stream, METH_VARARGS, pyzk_set_log_stream_doc },
  {"deterministic_conn_order",pyzoo_deterministic_conn_order, METH_VARARGS, pyzk_deterministic_conn_order_doc },
  {"acreate", pyzoo_acreate, METH_VARARGS, pyzk_acreate_doc },
  {"adelete", pyzoo_adelete, METH_VARARGS,pyzk_adelete_doc },
  {"aexists", pyzoo_aexists, METH_VARARGS,pyzk_aexists_doc },
  {"aget", pyzoo_aget, METH_VARARGS, pyzk_aget_doc },
  {"aset", pyzoo_aset, METH_VARARGS, pyzk_aset_doc },
  {"aget_children", pyzoo_aget_children, METH_VARARGS, pyzk_aget_children_doc },
  {"async", pyzoo_async, METH_VARARGS, pyzk_async_doc },
  {"aget_acl", pyzoo_aget_acl, METH_VARARGS, pyzk_aget_acl_doc },
  {"aset_acl", pyzoo_aset_acl, METH_VARARGS, pyzk_aset_acl_doc },
  {"zerror", pyzerror, METH_VARARGS, pyzk_zerror_doc },
  {"add_auth", pyzoo_add_auth, METH_VARARGS, pyzk_add_auth_doc },
  {NULL, NULL}
};

#define ADD_INTCONSTANT(x) PyModule_AddIntConstant(module, #x, ZOO_##x)
#define ADD_INTCONSTANTZ(x) PyModule_AddIntConstant(module, #x, Z##x)



#define ADD_EXCEPTION(x) x = PyErr_NewException("zookeeper."#x, ZooKeeperException, NULL); \
	Py_INCREF(x); \
  PyModule_AddObject(module, #x, x);


PyMODINIT_FUNC initzookeeper() {
  PyEval_InitThreads();
  PyObject *module = Py_InitModule("zookeeper", ZooKeeperMethods );
	init_zhandles(32);

  ZooKeeperException = PyErr_NewException("zookeeper.ZooKeeperException",
					  PyExc_Exception,
					  NULL);

	PyModule_AddObject(module, "ZooKeeperException", ZooKeeperException);
  Py_INCREF(ZooKeeperException);

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

  ADD_EXCEPTION(SystemErrorException);
  ADD_EXCEPTION(RuntimeInconsistencyException);
  ADD_EXCEPTION(DataInconsistencyException);
  ADD_EXCEPTION(ConnectionLossException);
  ADD_EXCEPTION(MarshallingErrorException);
  ADD_EXCEPTION(UnimplementedException);
  ADD_EXCEPTION(OperationTimeoutException);
  ADD_EXCEPTION(BadArgumentsException);
  ADD_EXCEPTION(InvalidStateException);  

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
  ADD_INTCONSTANTZ(SESSIONMOVED);

  ADD_EXCEPTION(ApiErrorException);
  ADD_EXCEPTION(NoNodeException);
  ADD_EXCEPTION(NoAuthException);
  ADD_EXCEPTION(BadVersionException);
  ADD_EXCEPTION(NoChildrenForEphemeralsException);
  ADD_EXCEPTION(NodeExistsException);
  ADD_EXCEPTION(NotEmptyException);
  ADD_EXCEPTION(SessionExpiredException);
  ADD_EXCEPTION(InvalidCallbackException);
  ADD_EXCEPTION(InvalidACLException);
  ADD_EXCEPTION(AuthFailedException);
  ADD_EXCEPTION(ClosingException);
  ADD_EXCEPTION(NothingException);
  ADD_EXCEPTION(SessionMovedException);
}

int main(int argc, char **argv)
{
  Py_SetProgramName(argv[0]);
  Py_Initialize();
  initzookeeper();
  return 0;
}
