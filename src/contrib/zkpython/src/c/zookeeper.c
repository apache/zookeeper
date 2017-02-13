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
  case ZINVALIDSTATE:
    return InvalidStateException;
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
  case ZCLOSING:
    return ClosingException;
  case ZNOTHING:
    return NothingException;
  case ZOK:
  default:
    return NULL;
  }
}


#define CHECK_ZHANDLE(z) if ( (z) < 0 || (z) >= num_zhandles) {     \
  PyErr_SetString( ZooKeeperException, "zhandle out of range" ); \
return NULL;                           \
} else if ( zhandles[(z)] == NULL ) {               \
    PyErr_SetString(ZooKeeperException, "zhandle already freed"); \
    return NULL;                         \
  }

/* Contains all the state required for a watcher callback - these are
   passed to the *dispatch functions as void*, cast to pywatcher_t and
   then their callback member is invoked if not NULL */
typedef struct {
  int zhandle;
  PyObject *callback;
  int permanent;
}pywatcher_t;

/* This array exists because we need to ref. count the global watchers
 for each connection - but they're inaccessible without pulling in
 zk_adaptor.h, which I'm trying to avoid. */
static pywatcher_t **watchers;

/* We keep an array of zhandles available for use.  When a zhandle is
 correctly closed, the C client frees the memory so we set the
 zhandles[i] entry to NULL.  This entry can then be re-used. */
static zhandle_t** zhandles = NULL;
static int num_zhandles = 0;
static int max_zhandles = 0;
#define REAL_MAX_ZHANDLES 32768

/* -------------------------------------------------------------------------- */
/* zhandles - unique connection ids - tracking */
/* -------------------------------------------------------------------------- */


/* Allocates an initial zhandle and watcher array */
int init_zhandles(int num) {
  zhandles = malloc(sizeof(zhandle_t*)*num);
  watchers = malloc(sizeof(pywatcher_t*)*num);
  if (zhandles == NULL || watchers == NULL) {
    return 0;
  }
  max_zhandles = num;
  num_zhandles = 0;
  memset(zhandles, 0, sizeof(zhandle_t*)*max_zhandles);
  return 1;
}

/* Note that the following zhandle functions are not thread-safe. The
 C-Python runtime does not seem to pre-empt a thread that is in a C
 module, so there's no need for synchronisation.  */

/* Doubles the size of the zhandle / watcher array Returns 0 if the
 new array would be >= REAL_MAX_ZHANDLES in size. Called when zhandles
 is full. Returns 0 if allocation failed or if max num zhandles
 exceeded. */
int resize_zhandles(void) {
  zhandle_t **tmp = zhandles;
  pywatcher_t ** wtmp = watchers;
  if (max_zhandles >= REAL_MAX_ZHANDLES >> 1) {
    return 0;
  }
  max_zhandles *= 2;
  zhandles = malloc(sizeof(zhandle_t*)*max_zhandles);
  if (zhandles == NULL) {
    PyErr_SetString(PyExc_MemoryError, "malloc for new zhandles failed");
    return 0;
  }
  memset(zhandles, 0, sizeof(zhandle_t*)*max_zhandles);
  memcpy(zhandles, tmp, sizeof(zhandle_t*)*max_zhandles/2);

  watchers = malloc(sizeof(pywatcher_t*)*max_zhandles);
  if (watchers == NULL) {
    PyErr_SetString(PyExc_MemoryError, "malloc for new watchers failed");
    return 0;
  }
  memset(watchers, 0, sizeof(pywatcher_t*)*max_zhandles);
  memcpy(watchers, wtmp, sizeof(pywatcher_t*)*max_zhandles/2);

  free(wtmp);
  free(tmp);
  return 1;
}

/* Find a free zhandle - this iterates through the list of open
 zhandles, but we expect it to be infrequently called.  There are
 optimisations that can be made if this turns out to be problematic.
 Returns -1 if no free handle is found - resize_handles() can be
 called in that case. */
unsigned int next_zhandle(void) {
  int i = 0;
  for (i=0;i<max_zhandles;++i) {
    if (zhandles[i] == NULL) {
      num_zhandles++;
      return i;
    }
  }
 
  return -1;
}

/* -------------------------------------------------------------------------- */
/* Utility functions to construct and deallocate data structures */
/* -------------------------------------------------------------------------- */


/* Creates a new pywatcher_t to hold connection state, a callback
   object and a flag to say if the watcher is permanent. Takes a new
   reference to the callback object. */
pywatcher_t *create_pywatcher(int zh, PyObject* cb, int permanent)
{
  pywatcher_t *ret = (pywatcher_t*)calloc(sizeof(pywatcher_t),1);
  if (ret == NULL) {
    PyErr_SetString(PyExc_MemoryError, "calloc failed in create_pywatcher");
    return NULL;
  }
  Py_INCREF(cb);
  ret->zhandle = zh; ret->callback = cb; ret->permanent = permanent;
  return ret;
}

/* Releases the reference taken in create_pywatcher to the callback,
   then frees the allocated pywatcher_t* */
void free_pywatcher(pywatcher_t *pw)
{
  if (pw == NULL) {
    return;
  }
  Py_DECREF(pw->callback);

  free(pw);
}

/* Constructs a new stat object. Returns Py_None if stat == NULL or a
   dictionary containing all the stat information otherwise. In either
   case, takes a reference to the returned object. */
PyObject *build_stat( const struct Stat *stat )
{
  if (stat == NULL) { 
    Py_INCREF(Py_None);
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

/* Creates a new list of strings from a String_vector. Returns the
   empty list if the String_vector is NULL. Takes a reference to the
   returned PyObject and gives that reference to the caller. */
PyObject *build_string_vector(const struct String_vector *sv)
{
  PyObject *ret;
  if (!sv) {
    return PyList_New(0);
  }

  ret = PyList_New(sv->count);
  if (ret) {
    int i;
    for (i=0;i<sv->count;++i)  {
#if PY_MAJOR_VERSION >= 3
      PyObject *s = PyUnicode_FromString(sv->data[i]);
#else
      PyObject *s = PyString_FromString(sv->data[i]);
#endif
      if (!s) {
        if (ret != Py_None) {
          Py_DECREF(ret);
        }
        ret = NULL;
        break;
      }
      PyList_SetItem(ret, i, s);
    }
  }
  return ret;
}

/* Returns 1 if the PyObject is a valid representation of an ACL, and
   0 otherwise. */
int check_is_acl(PyObject *o) {
  int i;
  PyObject *entry;
  if (o == NULL) {
    return 0;
  }
  if (!PyList_Check(o)) {
    return 0;
  }
  for (i=0;i<PyList_Size(o);++i) {
    PyObject *element = PyList_GetItem(o,i);
    if (!PyDict_Check(element)) {
      return 0;
    }
    entry = PyDict_GetItemString( element, "perms" );
    if (entry == NULL) {
      return 0;
    }

    entry = PyDict_GetItemString( element, "scheme" );
    if (entry == NULL) {
      return 0;
    }

    entry = PyDict_GetItemString( element, "id" );
    if (entry == NULL) {
      return 0;
    }
  }

  return 1;
}

/* Macro form to throw exception if o is not an ACL */
#define CHECK_ACLS(o) if (check_is_acl(o) == 0) {                       \
    PyErr_SetString(err_to_exception(ZINVALIDACL), zerror(ZINVALIDACL)); \
    return NULL;                                                        \
  }


/* Creates a new list of ACL dictionaries from an ACL_vector. Returns
   the empty list if the ACL_vector is NULL. Takes a reference to the
   returned PyObject and gives that reference to the caller. */
PyObject *build_acls( const struct ACL_vector *acls )
{
  if (acls == NULL) {
    return PyList_New(0);
  }

  PyObject *ret = PyList_New(acls->count);
  int i;
  for (i=0;i<acls->count;++i) {
    PyObject *acl = Py_BuildValue( "{s:i, s:s, s:s}", 
                                   "perms", acls->data[i].perms, 
                                   "scheme", acls->data[i].id.scheme,
                                   "id", acls->data[i].id.id );
    PyList_SetItem(ret, i, acl);
  }
  return ret;
}

/* Parse the Python representation of an ACL list into an ACL_vector
   (which needs subsequent freeing) */
int parse_acls(struct ACL_vector *acls, PyObject *pyacls)
{
  PyObject *a;
  int i;
  if (acls == NULL || pyacls == NULL) {
    PyErr_SetString(PyExc_ValueError, "acls or pyacls NULL in parse_acls");
    return 0;
  }

  acls->count = PyList_Size( pyacls );

  // Is this a list? If not, we can't do anything
  if (PyList_Check(pyacls) == 0) {
    PyErr_SetString(InvalidACLException, "List of ACLs required in parse_acls");
    return 0;
  }

  acls->data = (struct ACL *)calloc(acls->count, sizeof(struct ACL));
  if (acls->data == NULL) {
    PyErr_SetString(PyExc_MemoryError, "calloc failed in parse_acls");
    return 0;
  }

  for (i=0;i<acls->count;++i) {
    a = PyList_GetItem(pyacls, i);
    // a is now a dictionary
    PyObject *perms = PyDict_GetItemString( a, "perms" );
#if PY_MAJOR_VERSION >= 3
    acls->data[i].perms = (int32_t)(PyLong_AsLong(perms));
    acls->data[i].id.id = strdup( PyUnicode_AsUnicode( PyDict_GetItemString( a, "id" ) ) );
    acls->data[i].id.scheme = strdup( PyUnicode_AsUnicode( PyDict_GetItemString( a, "scheme" ) ) );
#else
    acls->data[i].perms = (int32_t)(PyInt_AsLong(perms));
    acls->data[i].id.id = strdup( PyString_AsString( PyDict_GetItemString( a, "id" ) ) );
    acls->data[i].id.scheme = strdup( PyString_AsString( PyDict_GetItemString( a, "scheme" ) ) );
#endif
  }
  return 1;
}

/* Deallocates the memory allocated inside an ACL_vector, but not the
   ACL_vector itself */
void free_acls( struct ACL_vector *acls )
{
  if (acls == NULL) {
    return;
  }
  int i;
  for (i=0;i<acls->count;++i) {
    free(acls->data[i].id.id);
    free(acls->data[i].id.scheme);
  }
  free(acls->data);
}

/* -------------------------------------------------------------------------- */
/* Watcher and callback implementation */
/* -------------------------------------------------------------------------- */

/* Every watcher invocation goes through this dispatch point, which 
   a) acquires the global interpreter lock

   b) unpacks the PyObject to call from the passed context pointer,
   which handily includes the index of the relevant zookeeper handle
   to pass back to Python.

   c) Makes the call into Python, checking for error conditions which
   we are responsible for detecting and doing something about (we just
   print the error and plough right on)

   d) releases the lock after freeing up the context object, which is
   only used for one watch invocation (watches are one-shot, unless
   'permanent' != 0)
*/
void watcher_dispatch(zhandle_t *zzh, int type, int state, 
                      const char *path, void *context)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)context;
  PyObject *callback = pyw->callback;
  if (callback == NULL) {
    // This is unexpected
    char msg[256];
    sprintf(msg, "pywatcher: %d %p %d", pyw->zhandle, pyw->callback, pyw->permanent);
    PyErr_SetString(PyExc_ValueError, msg);
    return;
  }

  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,i,s)", pyw->zhandle,type, state, path);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL) {
    PyErr_Print();
  }
  Py_DECREF(arglist);
  if (pyw->permanent == 0 && (type != ZOO_SESSION_EVENT || state < 0)) {
    free_pywatcher(pyw);
  }
  PyGILState_Release(gstate);
}

/* The completion callbacks (from asynchronous calls) are implemented similarly */

/* Called when an asynchronous call that returns void completes and
   dispatches user provided callback */
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
  Py_DECREF(arglist);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* Called when an asynchronous call that returns a stat structure
   completes and dispatches user provided callback */
void stat_completion_dispatch(int rc, const struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL)
    return;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *pystat = build_stat(stat);
  PyObject *arglist = Py_BuildValue("(i,i,O)", pyw->zhandle,rc, pystat);
  Py_DECREF(pystat);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  Py_DECREF(arglist);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* Called when an asynchronous call that returns a stat structure and
   some untyped data completes and dispatches user provided
   callback (used by aget) */
void data_completion_dispatch(int rc, const char *value, int value_len, const struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL)
    return;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *pystat = build_stat(stat);
  PyObject *arglist = Py_BuildValue("(i,i,s#,O)", pyw->zhandle,rc, value,value_len, pystat);
  Py_DECREF(pystat);

  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  Py_DECREF(arglist);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* Called when an asynchronous call that returns a list of strings
   completes and dispatches user provided callback */
void strings_completion_dispatch(int rc, const struct String_vector *strings, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL)
    return;
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *pystrings = build_string_vector(strings);
  if (pystrings)
    {
      PyObject *arglist = Py_BuildValue("(i,i,O)", pyw->zhandle, rc, pystrings);   
      if (arglist == NULL || PyObject_CallObject((PyObject*)callback, arglist) == NULL)
        PyErr_Print();
      Py_DECREF(arglist);
    }
  else
    PyErr_Print();
  Py_DECREF(pystrings);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* Called when an asynchronous call that returns a single string
   completes and dispatches user provided callback */
void string_completion_dispatch(int rc, const char *value, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL) {
    return;
  }
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *arglist = Py_BuildValue("(i,i,s)", pyw->zhandle,rc, value);
  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL)
    PyErr_Print();
  Py_DECREF(arglist);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* Called when an asynchronous call that returns a list of ACLs
   completes and dispatches user provided callback */
void acl_completion_dispatch(int rc, struct ACL_vector *acl, struct Stat *stat, const void *data)
{
  PyGILState_STATE gstate;
  pywatcher_t *pyw = (pywatcher_t*)data;
  if (pyw == NULL) {
    return;
  }
  PyObject *callback = pyw->callback;
  gstate = PyGILState_Ensure();
  PyObject *pystat = build_stat(stat);
  PyObject *pyacls = build_acls(acl);
  PyObject *arglist = Py_BuildValue("(i,i,O,O)", pyw->zhandle,rc, pyacls, pystat);

  Py_DECREF(pystat);
  Py_DECREF(pyacls);

  if (PyObject_CallObject((PyObject*)callback, arglist) == NULL) {
    PyErr_Print();
  }
  Py_DECREF(arglist);
  free_pywatcher(pyw);
  PyGILState_Release(gstate);
}

/* -------------------------------------------------------------------------- */
/* ZOOKEEPER API IMPLEMENTATION */
/* -------------------------------------------------------------------------- */

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
    if (resize_zhandles() == 0) {
      return NULL;
    }
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
    if (pyw == NULL) {
      return NULL;
    }
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


/* -------------------------------------------------------------------------- */
/* Asynchronous API implementation */
/* -------------------------------------------------------------------------- */

/* Asynchronous node creation, returns integer error code */
PyObject *pyzoo_acreate(PyObject *self, PyObject *args)
{
  int zkhid; char *path; char *value; int valuelen;
  struct ACL_vector acl; int flags = 0;
  PyObject *completion_callback = Py_None;
  PyObject *pyacls = Py_None;
  if (!PyArg_ParseTuple(args, "iss#O|iO", &zkhid, &path, 
                        &value, &valuelen, &pyacls, &flags, 
                        &completion_callback)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  CHECK_ACLS(pyacls);
  if (parse_acls(&acl, pyacls) == 0) {
    return NULL;
  }
  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }
  int err = zoo_acreate( zhandles[zkhid],
                         path,
                         value,
                         valuelen,
                         pyacls == Py_None ? NULL : &acl,
                         flags,
                         string_completion_dispatch,
                         pyw);
  free_acls(&acl);
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

/* Asynchronous node deletion, returns integer error code */
PyObject *pyzoo_adelete(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version = -1;
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|iO", &zkhid, &path, &version, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_adelete( zhandles[zkhid],
                         path,
                         version,
                         void_completion_dispatch,
                         pyw);
    
  if (err != ZOK) {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);
}

/* Asynchronous node existence check, returns integer error code */
PyObject *pyzoo_aexists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *exists_watch = Py_None;
  if (!PyArg_ParseTuple(args, "is|OO", &zkhid, &path, 
                        &exists_watch, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  void *comp_pyw = NULL;
  if (completion_callback != Py_None) {
    comp_pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (comp_pyw == NULL) {
      return NULL;
    }
  }
  void *exist_pyw = NULL;
  if (exists_watch != Py_None) {
    exist_pyw = create_pywatcher(zkhid, exists_watch, 0);
    if (exist_pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_awexists( zhandles[zkhid],
                          path,
                          exists_watch != Py_None ? watcher_dispatch : NULL,
                          exist_pyw,
                          stat_completion_dispatch,
                          comp_pyw);
    
  if (err != ZOK)
    {
      PyErr_SetString(err_to_exception(err), zerror(err));
      return NULL;
    }
  return Py_BuildValue("i", err);;
}

/* Asynchronous node data retrieval, returns integer error code */
PyObject *pyzoo_aget(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *get_watch = Py_None;
  void *comp_pw = NULL;
  void *watch_pw = NULL;

  if (!PyArg_ParseTuple(args, "is|OO", &zkhid, &path, 
                        &get_watch, &completion_callback)) {
    return NULL;
  }

  CHECK_ZHANDLE(zkhid);

  if (get_watch != Py_None) {
    if ((watch_pw = create_pywatcher(zkhid, get_watch, 0)) == NULL) {
      return NULL;
    }
  }

  if (completion_callback != Py_None) {
    if ((comp_pw = create_pywatcher(zkhid, completion_callback, 0)) == NULL) {
      return NULL;
    }
  }

  int err = zoo_awget( zhandles[zkhid],
                       path,
                       get_watch != Py_None ? watcher_dispatch : NULL,
                       watch_pw,
                       data_completion_dispatch,
                       comp_pw);
    
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);
}

/* Asynchronous node contents update, returns integer error code */
PyObject *pyzoo_aset(PyObject *self, PyObject *args)
{
  int zkhid; char *path; char *buffer; int buflen; int version=-1;
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "iss#|iO", &zkhid, &path, &buffer, &buflen, &version, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }
  int err = zoo_aset( zhandles[zkhid],
                      path,
                      buffer,
                      buflen,
                      version,
                      stat_completion_dispatch,
                      pyw);
    
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);
}

/* Asynchronous node child retrieval, returns integer error code */
PyObject *pyzoo_aget_children(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  PyObject *get_watch;
  if (!PyArg_ParseTuple(args, "is|OO", &zkhid,  &path,
                        &get_watch, &completion_callback))
    return NULL;
  CHECK_ZHANDLE(zkhid);

  void *get_pyw = NULL;
  if (get_watch != Py_None) {
    get_pyw = create_pywatcher(zkhid, get_watch, 0);
    if (get_pyw == NULL) {
      return NULL;
    }
  }

  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_awget_children( zhandles[zkhid],
                                path,
                                get_watch != Py_None ? watcher_dispatch : NULL,
                                get_pyw,
                                strings_completion_dispatch,
                                pyw);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);;
}

/* Asynchronous sync, returns integer error code */
PyObject *pyzoo_async(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, 
                        &completion_callback)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);

  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_async( zhandles[zkhid],
                       path,
                       string_completion_dispatch,
                       pyw);
 
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);;
}

/* Asynchronous node ACL retrieval, returns integer error code */
PyObject *pyzoo_aget_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; 
  PyObject *completion_callback = Py_None;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, 
                        &completion_callback)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);

  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_aget_acl( zhandles[zkhid],
                          path,
                          acl_completion_dispatch,
                          pyw);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);;
}

/* Asynchronous node ACL update, returns integer error code */
PyObject *pyzoo_aset_acl(PyObject *self, PyObject *args)
{
  int zkhid; char *path; int version; 
  PyObject *completion_callback = Py_None, *pyacl;
  struct ACL_vector aclv;
  if (!PyArg_ParseTuple(args, "isiO|O", &zkhid, &path, &version, 
                        &pyacl, &completion_callback)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  CHECK_ACLS(pyacl);
  if (parse_acls(&aclv, pyacl) == 0) {
    return NULL;
  }

  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_aset_acl( zhandles[zkhid],
                          path,
                          version,
                          &aclv,
                          void_completion_dispatch,
                          pyw);
  free_acls(&aclv);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);;
}

/* Asynchronous authorization addition, returns integer error code */
PyObject *pyzoo_add_auth(PyObject *self, PyObject *args)
{
  int zkhid;
  char *scheme, *cert;
  int certLen;
  PyObject *completion_callback;

  if (!PyArg_ParseTuple(args, "iss#O", &zkhid, &scheme, &cert, &certLen, 
                        &completion_callback)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  
  void *pyw = NULL;
  if (completion_callback != Py_None) {
    pyw = create_pywatcher(zkhid, completion_callback, 0);
    if (pyw == NULL) {
      return NULL;
    }
  }

  int err = zoo_add_auth( zhandles[zkhid],
                          scheme,
                          cert,
                          certLen,
                          void_completion_dispatch,
                          pyw);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);
}

/* -------------------------------------------------------------------------- */
/* Synchronous API implementation */
/* -------------------------------------------------------------------------- */

/* Synchronous node creation, returns node path string */
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
  CHECK_ACLS(acl);
  if (parse_acls(&aclv,acl) == 0) {
    return NULL;
  }
  zhandle_t *zh = zhandles[zkhid];
  int err = zoo_create(zh, path, values, valuelen, &aclv, flags, realbuf, maxbuf_len);
  free_acls(&aclv);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }

  return Py_BuildValue("s", realbuf);
}

/* Synchronous node deletion, returns integer error code */
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
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);
}

/* Synchronous node existence check, returns stat if exists, None if
   absent */
static PyObject *pyzoo_exists(PyObject *self, PyObject *args)
{
  int zkhid; char *path; PyObject *watcherfn = Py_None;
  struct Stat stat;
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, &watcherfn)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  zhandle_t *zh = zhandles[zkhid];
  pywatcher_t *pw = NULL;
  void *callback = NULL;
  if (watcherfn != Py_None) {
    pw = create_pywatcher(zkhid, watcherfn,0);
    callback = watcher_dispatch;
    if (pw == NULL) {
      return NULL;
    }
  }
  int err = zoo_wexists(zh,  path, callback, pw, &stat);
  if (err != ZOK && err != ZNONODE) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    free_pywatcher(pw);
    return NULL;
  }
  if (err == ZNONODE) {
    Py_INCREF(Py_None);
    return Py_None; // This isn't exceptional
  }
  return build_stat(&stat);
}

/* Synchronous node child retrieval, returns list of children's path
   as strings */
static PyObject *pyzoo_get_children(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  PyObject *watcherfn = Py_None;
  struct String_vector strings; 
  if (!PyArg_ParseTuple(args, "is|O", &zkhid, &path, &watcherfn)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  pywatcher_t *pw = NULL;
  void *callback = NULL;
  if (watcherfn != Py_None) {
    pw = create_pywatcher( zkhid, watcherfn, 0 );
    callback = watcher_dispatch;
    if (pw == NULL) {
      return NULL;
    }
  }
  int err = zoo_wget_children(zhandles[zkhid], path, 
                              callback,
                              pw, &strings );

  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    free_pywatcher(pw);
    return NULL;
  }

  PyObject *ret = build_string_vector(&strings);
  deallocate_String_vector(&strings);
  return ret;
}

/* Synchronous node data update, returns integer error code */
static PyObject *pyzoo_set(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char *buffer;
  int buflen;
  int version = -1; 
  if (!PyArg_ParseTuple(args, "iss#|i", &zkhid, &path, &buffer, &buflen, 
                        &version)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);

  int err = zoo_set(zhandles[zkhid], path, buffer, buflen, version);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }

  return Py_BuildValue("i", err);
}

/* Synchronous node data update, returns node's stat data structure */
static PyObject *pyzoo_set2(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char *buffer;
  int buflen;
  int version = -1; 
  if (!PyArg_ParseTuple(args, "iss#|i", &zkhid, &path, &buffer, &buflen, 
                        &version)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  struct Stat stat;
  int err = zoo_set2(zhandles[zkhid], path, buffer, buflen, version, &stat);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }

  return build_stat(&stat);
}

/* As per ZK documentation, datanodes are limited to 1Mb. Why not do a
 stat followed by a get, to determine how big the buffer should be?
 Because the znode may get updated between calls, so we can't
 guarantee a complete get anyhow.  */
#define GET_BUFFER_SIZE 1024*1024

/* pyzoo_get has an extra parameter over the java/C equivalents.  If
 you set the fourth integer parameter buffer_len, we return
 min(buffer_len, datalength) bytes. This is set by default to
 GET_BUFFER_SIZE */
static PyObject *pyzoo_get(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  char *buffer; 
  int buffer_len=GET_BUFFER_SIZE;
  struct Stat stat;
  PyObject *watcherfn = Py_None;
  pywatcher_t *pw = NULL;
  if (!PyArg_ParseTuple(args, "is|Oi", &zkhid, &path, &watcherfn, &buffer_len)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  if (watcherfn != Py_None) {
    pw = create_pywatcher( zkhid, watcherfn,0 );
    if (pw == NULL) {
      return NULL;
    }
  }
  buffer = malloc(sizeof(char)*buffer_len);
  if (buffer == NULL) {
    free_pywatcher(pw);
    PyErr_SetString(PyExc_MemoryError, "buffer could not be allocated in pyzoo_get");
    return NULL;
  }
      
  int err = zoo_wget(zhandles[zkhid], path, 
                     watcherfn != Py_None ? watcher_dispatch : NULL, 
                     pw, buffer, 
                     &buffer_len, &stat);
 
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    free_pywatcher(pw);
    free(buffer);
    return NULL;
  }

  PyObject *stat_dict = build_stat( &stat );
  PyObject *ret = Py_BuildValue( "(s#,N)", buffer,buffer_len < 0 ? 0 : buffer_len, stat_dict );
  free(buffer);

  return ret;
}

/* Synchronous node ACL retrieval, returns list of ACLs */
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
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL; 
  }
  PyObject *pystat = build_stat( &stat ); 
  PyObject *acls = build_acls( &acl );
  PyObject *ret = Py_BuildValue( "(O,O)", pystat, acls );
  Py_DECREF(pystat);
  Py_DECREF(acls);
  return ret;
}

/* Synchronous node ACL update, returns integer error code */
PyObject *pyzoo_set_acl(PyObject *self, PyObject *args)
{
  int zkhid;
  char *path;
  int version;
  PyObject *pyacls;
  struct ACL_vector acl;
  if (!PyArg_ParseTuple(args, "isiO", &zkhid, &path, &version, &pyacls)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  if (parse_acls(&acl, pyacls) == 0) {
    return NULL;
  }
  int err = zoo_set_acl(zhandles[zkhid], path, version, &acl );
  free_acls(&acl);
  if (err != ZOK) {
    PyErr_SetString(err_to_exception(err), zerror(err));
    return NULL;
  }
  return Py_BuildValue("i", err);;
}

/* -------------------------------------------------------------------------- */
/* Session and context methods */
/* -------------------------------------------------------------------------- */

/* Closes a connection, returns integer error code */
PyObject *pyzoo_close(PyObject *self, PyObject *args)
{
  int zkhid, ret;
  if (!PyArg_ParseTuple(args, "i", &zkhid)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  zhandle_t *handle = zhandles[zkhid];
  Py_BEGIN_ALLOW_THREADS
  ret = zookeeper_close(handle);
  Py_END_ALLOW_THREADS
  zhandles[zkhid] = NULL; // The zk C client frees the zhandle
  return Py_BuildValue("i", ret);
}

/* Returns the ID of current client as a tuple (client_id, passwd) */
PyObject *pyzoo_client_id(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  const clientid_t *cid = zoo_client_id(zhandles[zkhid]);
  return Py_BuildValue("(L,s)", cid->client_id, cid->passwd);
}

/* DO NOT USE - context is used internally. This method is not exposed
   in the Python module */
PyObject *pyzoo_get_context(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args, "i", &zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  PyObject *context = NULL;
  context = (PyObject*)zoo_get_context(zhandles[zkhid]);
  if (context) return context;
  Py_INCREF(Py_None);
  return Py_None;  
}

/* DO NOT USE - context is used internally. This method is not exposed
   in the Python module */
PyObject *pyzoo_set_context(PyObject *self, PyObject *args)
{
  int zkhid;
  PyObject *context;
  if (!PyArg_ParseTuple(args, "iO", &zkhid, &context)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  PyObject *py_context = (PyObject*)zoo_get_context(zhandles[zkhid]);
  if (py_context != NULL && py_context != Py_None) {
    Py_DECREF(py_context);
  }
  Py_INCREF(context);
  zoo_set_context(zhandles[zkhid], (void*)context);
  Py_INCREF(Py_None);
  return Py_None;
}


/* -------------------------------------------------------------------------- */
/* Miscellaneous methods */
/* -------------------------------------------------------------------------- */

/* Sets the global watcher. Returns None */
PyObject *pyzoo_set_watcher(PyObject *self, PyObject *args)
{
  int zkhid;
  PyObject *watcherfn;
  if (!PyArg_ParseTuple(args, "iO", &zkhid, &watcherfn)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  pywatcher_t *pyw = watchers[zkhid];
  if (pyw != NULL) {
    free_pywatcher( pyw );
  }
  
  // Create a *permanent* watcher object, not deallocated when called
  pyw = create_pywatcher(zkhid, watcherfn,1);
  if (pyw == NULL) {
    return NULL;
  }
  watchers[zkhid] = pyw;
  zoo_set_watcher(zhandles[zkhid], watcher_dispatch);
  zoo_set_context(zhandles[zkhid], pyw);
  Py_INCREF(Py_None);
  return Py_None; 
}

/* Returns an integer code representing the current connection
   state */
PyObject *pyzoo_state(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid)) {
    return NULL;
  }
  CHECK_ZHANDLE(zkhid);
  int state = zoo_state(zhandles[zkhid]);
  return Py_BuildValue("i",state);
}


/* Convert an integer error code into a string */
PyObject *pyzerror(PyObject *self, PyObject *args)
{
  int rc;
  if (!PyArg_ParseTuple(args,"i", &rc))
    return NULL;
  return Py_BuildValue("s", zerror(rc));
}

/* Returns the integer receive timeout for a connection */
PyObject *pyzoo_recv_timeout(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  int recv_timeout = zoo_recv_timeout(zhandles[zkhid]);
  return Py_BuildValue("i",recv_timeout);  
}

/* Returns True if connection is unrecoverable, False otherwise */
PyObject *pyis_unrecoverable(PyObject *self, PyObject *args)
{
  int zkhid;
  if (!PyArg_ParseTuple(args,"i",&zkhid))
    return NULL;
  CHECK_ZHANDLE(zkhid);
  int ret = is_unrecoverable(zhandles[zkhid]);
  if (ret == ZINVALIDSTATE)
    Py_RETURN_TRUE;
  Py_RETURN_FALSE;
}

/* Set the debug level for logging, returns None */
PyObject *pyzoo_set_debug_level(PyObject *self, PyObject *args)
{
  int loglevel;
  if (!PyArg_ParseTuple(args, "i", &loglevel))
    return NULL;
  zoo_set_debug_level((ZooLogLevel)loglevel);
  Py_INCREF(Py_None);
  return Py_None;
}

static PyObject *log_stream = NULL;

/* Set the output file-like object for logging output. Returns Py_None */
PyObject *pyzoo_set_log_stream(PyObject *self, PyObject *args)
{
  PyObject *pystream = NULL;
  if (!PyArg_ParseTuple(args,"O",&pystream)) {
    PyErr_SetString(PyExc_ValueError, "Must supply a Python object to set_log_stream");
    return NULL;
  }
  
#if PY_MAJOR_VERSION >= 3
  extern PyTypeObject PyIOBase_Type;
  if (!PyObject_IsInstance(pystream, (PyObject *)&PyIOBase_Type)) {
#else
  if(!PyFile_Check(pystream)) {
#endif

    PyErr_SetString(PyExc_ValueError, "Must supply a file object to set_log_stream");
    return NULL;
  }
  /* Release the previous reference to log_stream that we took */
  if (log_stream != NULL) {
    Py_DECREF(log_stream);
  }

  log_stream = pystream;
  Py_INCREF(log_stream);

#if PY_MAJOR_VERSION >= 3
  int fd = PyObject_AsFileDescriptor(log_stream);
  FILE *fp = fdopen(fd, "w");
#else 
  FILE *fp = PyFile_AsFile(log_stream);
#endif
  zoo_set_log_stream(fp);

  Py_INCREF(Py_None);
  return Py_None;
}

/* Set the connection order - randomized or in-order. Returns None. */
PyObject *pyzoo_deterministic_conn_order(PyObject *self, PyObject *args)
{
  int yesOrNo;
  if (!PyArg_ParseTuple(args, "i",&yesOrNo))
    return NULL;
  zoo_deterministic_conn_order( yesOrNo );
  Py_INCREF(Py_None);
  return Py_None;
}

/* -------------------------------------------------------------------------- */
/* Module setup */
/* -------------------------------------------------------------------------- */

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
  /* DO NOT USE get / set_context. Context is used internally to pass
   the python watcher to a dispatch function. If you want context, set
   it through set_watcher. */
  // {"get_context", pyzoo_get_context, METH_VARARGS, "" },
  // {"set_context", pyzoo_set_context, METH_VARARGS, "" },
  {NULL, NULL}
};

#if PY_MAJOR_VERSION >= 3 
static struct PyModuleDef zookeeper_moddef = {
  PyModuleDef_HEAD_INIT,
  "zookeeper",
  NULL,
  0,
  ZooKeeperMethods,
  0,
  0,
  0,
  0
};
#endif

#define ADD_INTCONSTANT(x) PyModule_AddIntConstant(module, #x, ZOO_##x)
#define ADD_INTCONSTANTZ(x) PyModule_AddIntConstant(module, #x, Z##x)

#define ADD_EXCEPTION(x) x = PyErr_NewException("zookeeper."#x, ZooKeeperException, NULL); \
  Py_INCREF(x);                                                         \
  PyModule_AddObject(module, #x, x);

#if PY_MAJOR_VERSION >= 3
PyMODINIT_FUNC PyInit_zookeeper(void) {
#else
PyMODINIT_FUNC initzookeeper(void) {
#endif
  PyEval_InitThreads();

#if PY_MAJOR_VERSION >= 3
  PyObject *module = PyModule_Create(&zookeeper_moddef);
#else
  PyObject *module = Py_InitModule("zookeeper", ZooKeeperMethods);
#endif
  if (init_zhandles(32) == 0) {
    return; // TODO: Is there any way to raise an exception here?
  }

  ZooKeeperException = PyErr_NewException("zookeeper.ZooKeeperException",
                                          PyExc_Exception,
                                          NULL);

  PyModule_AddObject(module, "ZooKeeperException", ZooKeeperException);
  Py_INCREF(ZooKeeperException);

  int size = 10;
  char version_str[size];
  snprintf(version_str, size, "%i.%i.%i", ZOO_MAJOR_VERSION, ZOO_MINOR_VERSION, ZOO_PATCH_VERSION);

  PyModule_AddStringConstant(module, "__version__", version_str);

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

#if PY_MAJOR_VERSION >= 3
  return module;
#endif
}
