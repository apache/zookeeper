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

#define FUSE_USE_VERSION 26

#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#undef _GNU_SOURCE
#define _GNU_SOURCE

extern "C" {
#include <fuse.h>
#include <ulockmgr.h>
}
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <sys/time.h>
#ifdef HAVE_SETXATTR
#include <sys/xattr.h>
#endif

#include <getopt.h>

#include <iostream>
#include <sstream>
#include <map>
#include <string>
#include <boost/utility.hpp>
#include <boost/weak_ptr.hpp>

#include "log.h"
#include "mutex.h"
#include "zkadapter.h"

#define ZOOKEEPER_ROOT_CHILDREN_WATCH_BUG

/**
   Typedef for ZooKeeperAdapter::Data.
*/
typedef std::string Data;
/**
   Typedef for ZooKeeperAdapter::NodeNames.
*/
typedef vector<std::string> NodeNames;

#define MAX_DATA_SIZE 1024;

DEFINE_LOGGER(LOG, "zkfuse");

inline 
uint64_t millisecsToSecs(uint64_t millisecs)
{
    return millisecs / 1000;
}
inline
uint64_t secsToMillisecs(uint64_t secs)
{
    return secs * 1000;
}
inline
uint64_t nanosecsToMillisecs(uint64_t nanosecs)
{
    return nanosecs / 1000000;
}
inline
uint64_t timespecToMillisecs(const struct timespec & ts)
{ 
    return secsToMillisecs(ts.tv_sec) + nanosecsToMillisecs(ts.tv_nsec);
}

typedef boost::shared_ptr<ZooKeeperAdapter> ZooKeeperAdapterSharedPtr;

/**
 * ZkFuseCommon - holds immutable configuration objects.
 *
 * No locks are required to access these objects.
 * A ZkFuseCommon instance is considered to be a data object and may be copied.
 */
class ZkFuseCommon 
{
  private:
    /**
      References the ZooKeeperAdapter instance to be used.
     */
    ZooKeeperAdapterSharedPtr _zkAdapter;
    /** 
      Path to the ZooKeeper root node.
     */
    std::string _rootPathName;
    /**
      Name used to access data "file" when the ZK node has 
      children.
     */
    std::string _dataFileName;
    /**
      Suffix added to path components to force interpretation of 
      path components as directory. This is usually only required
      for the last component. For example, ZkFuse may consider
      a leaf node a regular file, e.g. /a/b/c/leaf. The suffix
      can be used to create child under this node, e.g.
      mkdir /a/b/c/leaf{forceDirSuffix}/new_leaf.
     */
    std::string _forceDirSuffix;
    /**
      Prefix common to all metadata nodes created by ZkFuse.
     */  
    std::string _metadataNamePrefix;
    /**
      Path component name that identifies a directory metadata node.
      A directory metadata node is currently empty. It is used by ZkFuse
      to create a child when mkdir is used. This prevents ZkFuse
      from interpreting the new child as a regular file.
     */
    std::string _dirMetadataName;
    /**
      Path component name that identifies a regular file metadata node.
      A regular metadata node holds metadata required to implement
      Posix regular file semantics, such as setting mtime.
     */
    std::string _regMetadataName;
    /**
      Number of not-in-use nodes to cache.
     */
    unsigned _cacheSize;
    /**
      Assume this userid owns all nodes.
     */
    const uid_t _uid;
    /**
      Assume this groupid owns all nodes.
     */
    const gid_t _gid;
    /**
      Blocksize used to calculate number of blocks used for stat.
     */
    const unsigned _blkSize;

  public:
    /**
      Constructor.
     */
    ZkFuseCommon()
      : _zkAdapter(),
        _rootPathName("/"),
        _dataFileName(),
        _forceDirSuffix(),
        _metadataNamePrefix(".zkfuse."),
        _dirMetadataName(_metadataNamePrefix + "dir"),
        _regMetadataName(_metadataNamePrefix + "file"),
        _cacheSize(256),
        _uid(geteuid()),
        _gid(getegid()),
        _blkSize(8192)
    {
    }
    /**
      Get root path name. Always "/".
      \see _rootPathName
     */
    const std::string & getRootPathName() const
    {
        return _rootPathName;
    }
    /**
      Get dataFileName - the name for synthesized files to access
      ZooKeeper node data.
      \see _dataFileName
     */
    const std::string & getDataFileName() const
    {
        return _dataFileName;
    }
    /**
      Set dataFileName.
      \see getDataFileName
      \see _dataFileName
     */
    void setDataFileName(const std::string & dataFileName)
    {
        _dataFileName = dataFileName;
    }
    /**
      Get metadataNamePrefix - the common prefix for all ZkFuse created
      metadata ZooKeeper nodes.
      \see _metadataNamePrefix
     */
    const std::string & getMetadataNamePrefix() const
    {
        return _metadataNamePrefix;
    }
    /**
      Get forceDirSuffix - the suffix added to a path component to force
      the path component to be treated like a directory.
      \see _forceDirSuffix
     */
    const std::string & getForceDirSuffix() const
    {
        return _forceDirSuffix;
    }
    /**
      Set forceDirSuffix.
      \see getForceDirSuffix
      \see _forceDirSuffix
     */
    void setForceDirSuffix(const std::string & forceDirSuffix)
    {
        _forceDirSuffix = forceDirSuffix;
    }
    /**
      Get dirMetadataName - path component name of all directory 
      metadata ZooKeeper nodes. 
      \see _dirMetadataname
     */
    const std::string & getDirMetadataName() const
    {
        return _dirMetadataName;
    }
    /**
      Get regMetadataName - path component name of all regular file 
      metadata ZooKeeper nodes. 
      \see _regMetadataname
     */
    const std::string & getRegMetadataName() const
    {
        return _regMetadataName;
    }
    /**
      Get number of not-in-use ZkFuseFile instances to to cache.
      \see _cacheSize
     */
    unsigned getCacheSize() const
    {
        return _cacheSize;
    }
    /**
      Set cache size.
      \see getCacheSize
      \see _cacheSize
     */
    void setCacheSize(unsigned v) 
    {
        _cacheSize = v;
    }
    /** 
      Get userid.
      \see _uid
     */
    uid_t getUid() const
    {
        return _uid;
    }
    /**
      Get groupid.
      \see _gid
     */
    gid_t getGid() const
    {
        return _gid;
    }
    /**
      Get block size.
      \see _blkSize
     */
    unsigned getBlkSize() const
    {
        return _blkSize;
    }
    /**
      Get ZooKeeperAdapter.
      \see _zkAdapter.
     */
    const ZooKeeperAdapterSharedPtr & getZkAdapter() const
    {
        return _zkAdapter;
    }
    /**
      Set ZooKeeperAdapter.
      \see _zkAdaptor
     */
    void setZkAdapter(const ZooKeeperAdapterSharedPtr & zkAdapter)
    {
        _zkAdapter = zkAdapter;
    }
};

/**
  ZkFuseNameType - identifies the type of the ZkFuse path.
 */
enum ZkFuseNameType {
    /**
      ZkFuse path is not syntheiszed. 
      ZkFuse should use its default rules to determine the Posix representation
      of the path.
     */
    ZkFuseNameDefaultType = 0, 
    /**
      ZkFuse path is synthesized and identifies the data part of a
      ZooKeeper node, i.e.  Posix regular file semantics is expected.
     */
    ZkFuseNameRegType = 1,
    /**
      ZkFuse path is synthesized and identifies the chidlren part of a
      ZooKeeper node, i.e.  Posix directory semantics is expected.
     */
    ZkFuseNameDirType = 2
};

class ZkFuseFile;

typedef ZkFuseFile * ZkFuseFilePtr;

class ZkFuseHandleManagerFactory;

/**
  ZkFuseHandleManager - keeps track of all the ZkFuseFile instances 
  allocated by a ZkFuseHandleManager instance and provides them
  with a handle that can be used by FUSE. 

  It maps a ZooKeeper path to a handle and a handle to a ZkFuse instance.
  It also implements the methods that takes path names as arguments, such
  as open, mknod, rmdir, and rename.

  Memory management
  - References ZkFuseFile instances using regular pointers
    Smart pointer is not used because reference counts are needed to
    determine how many time a node is opened as a regular file or
    directory. This also avoids circular smart pointer references.
  - Each ZkFuseFile instance holds a reference to its ZkFuseHandleManager
    using a boost::shared_ptr. This ensures that the ZkFuseHandleManager
    instance that has the handle for the ZkFuseFile instance does not
    get garbage collected while the ZkFuseFile instance exists.

  Concurrency control
  - Except for the immutable ZkFuseCommon, all other member variables
    are protected by _mutex.
  - A method in this class can hold _mutex when it directly or
    indirectly invokes ZkFuseFile methods. A ZkFuseFile method that holds
    a ZkFuseFile instance _mutex cannot invoke a ZkFuseHandleManager
    method that acquires the ZkFuseHandleManager instance's _mutex.
    Otherwise, this may cause a dead lock.
  - Methods that with names that begin with "_" do not acquire _mutex. 
    They are usually called by public methods that acquire and hold _mutex.
 */
class ZkFuseHandleManager : boost::noncopyable
{
  private:
    /**
      Typedef of handle, which is an int.
     */
    typedef int Handle;
    /**
      Typedef of std::map used to map path to handle.
     */
    typedef std::map<std::string, Handle> Map;
    /**
      Typedef of std::vector used to map handle to ZkFuseFile instances.
     */
    typedef std::vector<ZkFuseFilePtr> Files;
    /**
      Typedef of std::vector used to hold unused handles.
     */
    typedef std::vector<Handle> FreeList;
    /**
      Typedef of boost::weak_ptr to the ZkFuseHandleManager instance.
     */
    typedef boost::weak_ptr<ZkFuseHandleManager> WeakPtr;

    /* Only ZkFuseHandleManagerFactory can create instances of this class */
    friend class ZkFuseHandleManagerFactory;

    /**
      Contains common configuration.
      Immutable so that it can be accessed without locks.
     */
    const ZkFuseCommon _common;
    /**
      Maps a path name to a Handle.
     */
    Map _map;
    /**
      Maps a handle to a ZkFuseFile instances.
      Also holds pointers to all known ZkFuseFile instances.
      An element may point to an allocated ZkFuseFile instance or be NULL.

      An allocated ZkFuseFile instance may be in one of the following states:
      - in-use
        Currently open, i.e. the ZkFuseFile instance's reference count 
        greater than 0.
      - in-cache
        Not currently open, i.e. the ZkFuseFile instances's 
        reference count is 0.
     */
    Files _files;
    /**
      List of free'ed handles.
     */
    FreeList _freeList;
    /**
      Mutex used to protect this instance.
     */
    mutable zkfuse::Mutex _mutex;
    /**
      Count of number of in-use entries.
      It used to calculate number of cached nodes.
      Number cached nodes is (_files.size() - _numInUse).
     */
    unsigned _numInUse;
    /**
      WeakPtr to myself.
     */
    WeakPtr _thisWeakPtr;
   
    /**
      Obtain a handle for the given path.
      - If path is not known, then allocate a new handle and increment
        _numInUse, and set newFile to true. The allocated 
        ZkFuseFile instance's reference count should be 1.
      - If path is known, increase the corresponding 
        ZkFuseFile instance's reference count.

      \return the allocated handle.
      \param path the path to lookup.
      \param newFile indicates whether a new handle has been allocated.
     */
    Handle allocate(const std::string & path, bool & newFile);

    /**
      Constructor.

      \param common the immutable common configuration.
      \param reserve number of elements to pre-allocate for 
                     _files and _freeList.
     */
    ZkFuseHandleManager(
            const ZkFuseCommon & common, 
            const unsigned reserve) 
      : _common(common),
        _files(), 
        _freeList(), 
        _mutex(),
        _numInUse(0)
    {
        _files.reserve(reserve);
        _files[0] = NULL; /* 0 never allocated */
        _files.resize(1); 
        _freeList.reserve(reserve);
    }

  public:
    /** 
      Typedef for boost::shared_ptr for this ZkFuseHandleManager class.
     */
    typedef boost::shared_ptr<ZkFuseHandleManager> SharedPtr;

    /**
      Destructor.
     */
    ~ZkFuseHandleManager()
    {
    }
    /** 
      Get the ZkFuseFile instance for a handle.

      \return the ZkFuseFile instance identified by the handle.
      \param handle get ZkFuseFile instance for this handle.
     */
    ZkFuseFilePtr getFile(Handle handle) const
    {
        AutoLock lock(_mutex);
        return _files[handle];
    }
    /**
      Get the immutable common configuration.

      \return the common configuration instance.
     */
    const ZkFuseCommon & getCommon() const
    {
        return _common;
    }
    /**
      Deallocate a previously allocated handle.
      This decrements the reference count of the corresponding
      ZkFuseFile instance. If the reference count becomes zero,
      decrement _numInUse. It may also cause the ZkFuseFile instance
      to be reclaimed if there are too many cached ZkFuseFile instances.

      The ZkFuseFile instance should be reclaimed if the number of
      unused ZkFuseFile instances exceeds the configured cache size, i.e.
      (_files.size() - _numInUse) > _common.getCacheSize()
      and the ZkFuseFile instance has a reference count of zero.

      Reclaiming a ZkFuseFile instance involves removing the ZkFuseFile
      instance's path to handle mapping from _map and the handle to the 
      ZkFuseFile instance mapping from _files, adding the handle to 
      the _freeList, and finally deleting the ZkFuseFile instance.

      \param handle the handle that should be deallocated.
     */
    void deallocate(Handle handle);
    /**
      Handles ZooKeeper session events.
      It invokes the known ZkFuseFile instances to let them know
      that their watches will no longer be valid. 
     */
    void eventReceived(const ZKWatcherEvent & event);
    /**
      Get data from the specified the ZooKeeper path.

      \return 0 if successful, otherwise return negative errno.
      \param path the path of the ZooKeeper node.
      \param data return data read.
     */
    int getData(const std::string & path, Data & data);
    /**
      Set data into the specified ZooKeeper path.

      \return 0 if successful, otherwise return negative errno.
      \param path the path of the ZooKeeper node.
      \param data the data to be written.
      \param exists set to true if this path exists.
      \param doFlush set to true if new data should be flushed to ZooKeeper.
     */
    int setData(const std::string & path,
                const Data & data,
                bool exists,
                bool doFlush);
    /**
      Create a ZooKeeper node to represent a ZkFuse file or directory.

      \return handle if successful, otherwise return negative errno.
      \param path to create.
      \param mode should be either S_IFDIR for directory or 
                  S_IFREG for regular file.
      \param mayExist if set and the ZooKeeper node already exist, return
                      valid handle instead of -EEXIST.
      \param created returns whether a new ZooKeeper node had been created.
     */
    int mknod(const std::string & path, 
              mode_t mode, 
              bool mayExist, 
              bool & created);
    /**
      Open a ZooKeeper node.  

      The justCreated argument is used to differentiate if the _deleted flag 
      of the ZkFuseFile instance is to be trusted  (i.e. the path 
      does not exist in ZooKeeper.) The _deleted flag is trusted 
      if the ZkFuseFile instance is known to exist in ZooKeeper after
      invoking ZooKeeper with the path. 
      
      If justCreated is true, then the ZkFuseFile instance was just created. 
      The ZkFuseFile constructor sets the _deleted flag to true because 
      path is not known to exist and hence should not be accessed. 
      The justCreated flag will force the ZkFuseFile instance to invoke 
      ZooKeeper to determine if the path exists.

      \return handle if successful, otherwise return negative errno.
      \param path the path to open.
      \param justCreated indicates if this is newly created ZkFuseFile instance.
     */
    int open(const std::string & path, bool justCreated);
    /**
      Remove a ZkFuse directory.

      If force is not set, then the ZooKeeper node will be removed only
      if it has no data and no child nodes except ZkFuse metadata nodes.

      \return 0 if successful, otherwise return negative errno.
      \param path the path to remove.
      \param force force removal, i.e. bypass checks.
      */
    int rmdir(const char * path, bool force = false);
    /**
      Make a ZkFuse directory.

      ZkFuse represents a ZooKeeper node with no data and no children 
      as a regular file. In order to differentiate a newly created
      directory from an empty regular file, mkdir will create a directory
      metadata node as a child of the directory.

      \return 0 if successful, otherwise return negative errno.
      \param path the path of the directory to create.
      \param mode create directory with this mode 
                  (mode currently not implemented).
     */
    int mkdir(const char * path, mode_t mode);
    /**
      Remove a ZkFuse regular file.

      A file is the abstraction for the data part of a ZooKeeper node.
      - If ZkFuse represents a ZooKeeper node as a directory, the data part
        of the node is represented by synthesizing a name for this file. This
        synthesized name is visible through readdir if the ZooKeeper node's
        data is not empty. Removing such a file is done by truncating 
        the ZooKeeper node's data to 0 length.
      - If ZkFuse represents a ZooKeeper node as a file, then removing the
        is done by removing the ZooKeeper node (and its metadata).

      \return 0 if successful, otherwise return negative errno.
      \param path the path of the file to remove.
     */
    int unlink(const char * path);
    /**
      Get attributes of a ZkFuse regular file or directory.

      \return 0 if successful, otherwise return negative errno.
      \param path get attributes for this path
      \param stbuf store attributes here.
     */
    int getattr(const char * path, struct stat & stbuf);
    /**
      Rename a ZkFuse regular file.

      It creates a new ZooKeeper node at toPath, copies data and file
      metadata from the ZooKeeper node at fromPath to the new node, 
      and deletes the current ZooKeeper node. If the current ZooKeeper 
      node is not deleted if the new ZooKeeper node cannot be created 
      or the data copy fails.

      It cannot be used to rename a directory.

      \return 0 if successful, otherwise return negative errno.
      \param fromPath the current path.
      \param toPath rename to this path.
     */
    int rename(const char * fromPath, const char * toPath);
    /**
      Add a child ZooKeeper path to the children information cache
      of the ZkFuseFile instance that caches the parent ZooKeeper node.

      This is used to add a child path after a new ZooKeeper node has
      been created to the children information cache of the parent
      ZooKeeper node. This is needed because waiting for the children
      changed event to update the cache may result in inconsistent local
      views of the changes.
      \see removeChildFromParent

      \parama childPath the path of the child ZooKeeper node.
     */
    void addChildToParent(const std::string & childPath) const;
    /**
      Remove a child ZooKeeper path from the children information cache
      of the ZkFuseFile instance that caches the parent ZooKeeper node.
      
      For example, this should happen whenever a path is deleted.
      This child information cache of the parent will eventually be 
      invalidated by watches. However, the delivery of the children 
      change event may come after the next access and thus provide 
      the client with an inconsistent view. One example is that 
      client deletes the last file in a directory, but the children
      changed event is not delivered before the client invokes rmdir.
      to remove the parent. In this case, the rmdir fails because 
      the cached children information of the parent indicates the 
      "directory" is not empty.

      \param childPath the path of the child ZooKeeper node.
     */
    void removeChildFromParent(const std::string & childPath) const;
    /**
      Return the path for the parent of the specified ZooKeeper path.

      \return the parent path.
      \param childPath the child path.
     */
    std::string getParentPath(const std::string & childPath) const;
    /**
      Return the ZooKeeper path from a ZkFuse path.

      The ZkFuse path may be a synthesized path. For example, a synthesized
      path is required to access the data part of a ZooKeeper node's 
      data when ZkFuse represents the ZooKeeper node as directory. 
      A synthesized path is also required to create a child ZooKeeper node
      under a ZooKeeper node that is represented by a regular file.

      \return the ZooKeeper path for path.
      \param path the ZkFuse path, which may be a synthesized path.
      \param nameType indicate whether the ZkFuse path is synthesized and
                      whether the synthesized ZkFuse path identifies a
                      directory or a regular file.
     */
    std::string getZkPath(const char * path, ZkFuseNameType & nameType) const;
};

/**
  ZkFuseHandleManagerFactory - factory for ZkFuseHandleManager.
  
  This is the only way to create a ZkFuseHandleManager instance. 
  to make sure that _thisWeakPtr of the instance is intialized 
  after the instance is created.
 */
class ZkFuseHandleManagerFactory
{
  public:
    /**
      Create an instance of ZkFuseHandleManager.
      
      \return the created ZkFuseHandleManager instance.
      \param common the common configuration.
      \param reserve initially reserve space for this number of handles.
     */
    static ZkFuseHandleManager::SharedPtr create(
       const ZkFuseCommon & common, 
       unsigned reserve = 1000)
    {
        ZkFuseHandleManager::SharedPtr manager
            (new ZkFuseHandleManager(common, reserve));
        manager->_thisWeakPtr = manager;
        return manager;
    }
};

/**
  ZkFuseAutoHandle - automatically closes handle.

  It holds an opened handle and automatically closes this handle
  when it is destroyed. This enables code that open a handle
  to be exception safe.
 */
class ZkFuseAutoHandle
{
  private:
    /**
      Typedef for Handle which is an int.
     */
    typedef int Handle;
    /**
      Holds a reference to the ZkFuseHandlerManager instance that
      allocated the handle.
     */
    ZkFuseHandleManager::SharedPtr _manager;
    /**
      The handle that should be closed when this instance is destroyed.
      A valid handle has value that is equal or greater than 0.
      A negative value indicates an error condition, usually the value
      is a negative errno.
     */
    Handle _handle;
    /**
      Caches a reference to the ZkFuseFile instance with this handle.
      This is a performance optimization so that _manager.getFile(_handle) 
      is only called once when the handle is initialized.
     */
    ZkFuseFilePtr _file;

    /**
      Initialize reference to the ZkFuseFile instance with this handle.
     */
    void _initFile()
    {
        if (_handle >= 0) {
            _file = _manager->getFile(_handle);
        } else {
            _file = NULL;
        }
    }

  public:
    /**
      Constructor - takes an previously opened handle.

      \param manager the ZkFuseHandleManager instance who allocated the handle.
      \param handle the handle.
     */
    ZkFuseAutoHandle(
        const ZkFuseHandleManager::SharedPtr & manager, 
        int handle)
      : _manager(manager),
        _handle(handle),
        _file()
    {
        _initFile();
    }
    /**
      Constructor - open path and remember handle.

      \param manager the ZkFuseHandleManager instance who allocated the handle.
      \param path open this path and remember its handle in this instance.
     */
    ZkFuseAutoHandle( 
        const ZkFuseHandleManager::SharedPtr & manager, 
        const std::string & path)
      : _manager(manager),
        _handle(_manager->open(path, false)),
        _file()
    {
        _initFile();
    }
    /**
      Constructor - create path and remember handle.

      The creation mode indicates whether the path identifies a regular file
      or a directory.

      \param manager the ZkFuseHandleManager instance who allocated the handle.
      \param path create this path and remember its handle in this instance.
      \param mode the creation mode for the path, should be either
                  S_IFDIR or S_IFDIR.
      \param mayExist, if set and the path already exists, 
                       then the ZkFuseAutoHandle will hold the handle
                       for the path instead of -EEXIST.
                       If not set and the path does not exist, then the handle
                       be -EEXIST.
     */
    ZkFuseAutoHandle( 
        const ZkFuseHandleManager::SharedPtr & manager, 
        const std::string & path,
        mode_t mode,
        bool mayExist)
      : _manager(manager),
        _handle(-1),
        _file()
    {
        bool created;
        _handle = _manager->mknod(path, mode, mayExist, created);
        _initFile();
    }
    /**
      Destructor - closes the handle.
     */
    ~ZkFuseAutoHandle()
    {
        reset();
    }
    /**
      Get the handle.
      \see _handle
     */
    int get() const
    {
        return _handle;
    }
    /**
      Get the ZkFuseFile instance of the handle.
      \see _file
     */
    ZkFuseFilePtr getFile() const
    {
        return _file;
    }
    /**
      Forget the handle, don't close the handle.
     */
    void release() 
    {
        _handle = -1;
        _file = NULL;
    }
    /**
      Change the remembered handle.

      It will close the current handle (if valid).
     */
    void reset(int handle = -1);
};

/**
  ZkFuseStat - C++ wrapper for ZooKeeper Stat.

  This wrapper provides ZooKeeper Stat will constructors that
  initializes the instance variables of Stat.
 */
class ZkFuseStat : public Stat 
{
  public:
    /**
      Constructor - clear instance variables.
     */
    ZkFuseStat() 
    {
        clear();
    }
    /**
      Destructor - do nothing.
     */
    ~ZkFuseStat()
    {
    }
    /**
      Clear instance variables.
     */
    void clear()
    {
        czxid = 0;
        mzxid = 0;
        ctime = 0;
        mtime = 0;
        version = 0;
        cversion = 0;
        aversion = 0;
    }
};

/**
  ZkFuseFile - an instance encapsulates the runtime state of an allocated
  ZooKeeper node.

  Memory management
  - Referenced by the ZkFuseHandleManager that created this instance.
  - Uses boost::shared_ptr to reference the ZkFuseHandleManager that 
    created this instance. This makes sure that this ZkFuseHandleManager
    instance cannot be deleted when it has allocated ZkFuseFile instances.
  - A ZkFuseHandleManager deletes itself if it can be reclaimed.
    It can be reclaimed if it has no watches, its reference count is zero,
    and the ZkFuseHandleManager instance would have more than the 
    configured number of cached ZkFuseFile instances. 
  - A ZkFuseFile instance cannot be deleted if it has active watches on
    its ZooKeeper node. When one of its watches fires, the ZkFuseFile
    instance must exist because one of its methods will be invoked 
    to process the event. If the ZkFuseFile instance has been deleted,
    the method will access previously freed memory.

  Concurrency control
  - _mutex protects the instance variables of an instance.
  - Callers should assume that a public method will acquire _mutex. 
  - Methods of this class may not hold _mutex while invoking an
    ZkFuseHandleManager instance.
  - Methods that with names that begin with "_" do not acquire _mutex. 
    They are usually called by public methods that acquire and hold _mutex.
*/
class ZkFuseFile : boost::noncopyable
{
  public:
    /**
      Maximum size for the data part of a ZooKeeper node.
     */
    static const unsigned maxDataFileSize = MAX_DATA_SIZE;

  private:
    /**
      Mode returned by getattr for a ZkFuse directory.
     */
    static const mode_t dirMode = (S_IFDIR | 0777);
    /**
      Mode returned by getattr for a ZkFuse regular file.
     */
    static const mode_t regMode = (S_IFREG | 0777);

    /**
      References the ZkFuseHandleManager that created this instance.
     */
    ZkFuseHandleManager::SharedPtr _manager;
    /**
      Handle for this instance.
     */
    const int _handle;
    /**
      Path of the ZooKeeper node represented by this instance.
     */
    const std::string _path;
    /**
      Mutex that protects the instance variables of this instance.
     */
    mutable zkfuse::Mutex _mutex;
    /**
      Reference count for this instance, i.e. the number of opens 
      minus the number of closes.
     */
    int _refCount;
    /**
      Indicates whether the ZooKeeper node exist.
      This flag allows caching of deleted ZooKeeper node to avoid
      repeated ZooKeeper lookups for a non-existent path, and avoid
      using cached information. 
      
      Its value is true if 
      - it is verified to exist (by calling ZooKeeper), or
      - it is existence is unknown because ZooKeeper has not been
        invoked to verify its path's existence.
     */
    bool _deleted;
    /**
      Count of current number directory opens minus directory closes.
     */
    int _openDirCount;
    /**
      Indicates whether cached children information is valid.
      
      It is true if the cached children information is valid.
     */
    bool _initializedChildren;
    /**
      Indicates whether there is an outstanding children watch.

      It is true if it has an outstanding children watch.
     */
    bool _hasChildrenListener;
    /**
      Cached children information. 

      The cache is valid if _initializedChildren is true.
     */
    NodeNames _children;

    /**
      Indicates whether the cached data is valid.

      It is true if the cached data and ZooKeeper Stat are valid.
     */
    bool _initializedData;
    /**
      Indicates whether there is an outstanding data watch.

      It is true if it has an outstanding data watch.
     */
    bool _hasDataListener;
    /**
      Indicates whether the cached data (_activeData) has been modified.

      It is true if the cached data has been modified.
     */
    bool _dirtyData;
    /**
      Currently active data.

      To maintain atomicity of updates and emulate Posix semantics, 
      when a ZkFuse file remains open, the same data will be accessed
      by the file's clients. The data will be flushed to ZooKeeper when
      the flush method is called. The flush method may be called
      explicitly by a client or implicitly when the ZkFuse file is no 
      longer currently open.

      _activeData and _activeStat stores the data and ZooKeeper Stat
      that will be accessed by the file's clients.

      If there are changes when the ZkFuse file is open, new data is
      cached as latest data (by _latestData and _latestStat).
     */
    Data _activeData;
    /**
      Currently active ZooKeeper Stat.
      \see _activeData
     */
    ZkFuseStat _activeStat;
    /**
      Latest data.
      This is either the same as _activeData or it is newer. It is newer
      is it has been updated by event triggered by a data watch.
     */
    Data _latestData;
    /**
      Latest ZooKeeper data.
      This is either the same as _activeStat or it is newer. It is newer
      is it has been updated by event triggered by a data watch.
     */
    ZkFuseStat _latestStat;

    /**
      Get userid.

      \return the userid.
     */
    uid_t _getUid() const
    {
        return _manager->getCommon().getUid();
    }
    /**
      Get groupid.

      \return the groupid.
     */
    gid_t _getGid() const
    {
        return _manager->getCommon().getGid();
    }
    /** 
      Get block size.

      \return the block size.
     */
    unsigned _getBlkSize() const
    {
        return _manager->getCommon().getBlkSize();
    }
    /**
      Get number of children, include metadata children in the count.

      \return the number of children including metadata children.
     */
    unsigned _numChildrenIncludeMeta() const
    {
        unsigned count = _children.size();
        LOG_DEBUG(LOG, "numChildrenIncludeMeta() returns %u", count);
        return count;
    }
    /**
      Get number of children, exclude metadata children in the count.

      \return the number of children excluding metadata children.
     */
    unsigned _numChildrenExcludeMeta() const
    {
        unsigned count = 0;
        for (NodeNames::const_iterator it = _children.begin();
             it != _children.end();
             it++) {
            if (!_isMeta(*it)) {
                count++;
            }
        }
        LOG_DEBUG(LOG, "numChildrenExcludeMeta() returns %u", count);
        return count;
    }
    /**
      Whether the ZooKeeper node has children, include metadata
      children.

      \return true if it has children including metadata children.
     */
    bool _hasChildrenIncludeMeta() const
    { 
        return _numChildrenIncludeMeta() != 0;
    }
    /**
      Return true if the ZooKeeper node has children, include metadata
      children.

      \return true if it has children excluding metadata children.
     */
    bool _hasChildrenExcludeMeta() const
    {
        return _numChildrenExcludeMeta() != 0;
    }
    /**
      Whether the ZooKeeper node has data.

      \return true if _activeData is not empty.
     */
    bool _hasData() const
    {
        return _activeData.empty() == false;
    }
    /**
      Whether the ZooKeeper node has child with the specified path.

      \return true if the ZooKeeper node has a child with the specified path.
      \param childPath the path of the child.
     */
    bool _hasChildPath(const std::string & childPath) const
    {
        bool hasChild =
            std::find(_children.begin(), _children.end(), childPath) 
            != _children.end();
        LOG_DEBUG(LOG, "hasChild(childPath %s) returns %d", 
                  childPath.c_str(), hasChild);
        return hasChild;
    }
    /**
      Whether the given path component is a ZkFuse synthesized path
      component.

      A ZkFuse synthesized path component will begin with 
      the metadataNamePrefix obtained from the common configuration.
      \see _metadataNamePrefix

      \return true if the path component is a ZkFuse synthesized path
                   component.
      \param childName the path component to check if it is synthesized by
                       ZkFuse.
     */
    bool _isMeta(const std::string & childName) const
    {
        bool isMeta;
        const std::string & prefix = 
            _manager->getCommon().getMetadataNamePrefix();
        unsigned offset = 
            (_path.length() > 1 ?
             _path.length() + 1 :
             1 /* special case for root dir */ ); 
        unsigned minLength = offset + prefix.length();
        if (childName.length() < minLength ||
            childName.compare(offset, prefix.length(), prefix) != 0) {
            isMeta = false;
        } else {
            isMeta = true;
        }
        LOG_DEBUG(LOG, "isMeta(childName %s) returns %d", 
                  childName.c_str(), isMeta);
        return isMeta;
    }
    /**
      Build a path for a specific child of the ZooKeeper node.
 
      This is done by appending "/" (unless it is the ZooKeeper node
      is the root node) and the name of the child.

      \return the path for the specified child of the ZooKeeper node.
      \param name the name of the child.
     */
    std::string _getChildPath(const std::string & name) const
    {
        return buildChildPath(_path, name);
    }
    /**
      Whether the ZooKeeper node has a regular file metadata child node.

      \return true if the ZooKeeper node has a regular file metadata child
                   node.
     */
    bool _hasRegMetadata() const
    {
        bool res = _hasChildPath(
                _getChildPath(_manager->getCommon().getRegMetadataName()));
        LOG_DEBUG(LOG, "hasRegMetadata() returns %d", res);
        return res;
    }
    /**
      Whether the ZooKeeper node has a directory metadata child node.

      \return true if the ZooKeeper node has a directory metadata child
                   node.
     */
    bool _hasDirMetadata() const
    {
        bool res = _hasChildPath(
                _getChildPath(_manager->getCommon().getDirMetadataName()));
        LOG_DEBUG(LOG, "hasDirMetadata() returns %d", res);
        return res;
    }
    /** 
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse regular
      file.
     
      It should be a ZkFuse regular file it has no children or its 
      only children is its regular file metadata child node.

      \return true if the Zookeeper node should be presented as a ZkFuse
                   regular file.
     */
    bool _isReg() const
    {
        unsigned numChildrenIncludeMeta = _numChildrenIncludeMeta();
        bool res =
            (numChildrenIncludeMeta == 0) ||
            (numChildrenIncludeMeta == 1 && _hasRegMetadata() == true);
        LOG_DEBUG(LOG, "isReg() returns %d", res);
        return res;
    }
    /**
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse directory.
     
      It should be a ZkFuse directory if it should not be presented as
      a ZkFuse regular directory.
      \see _isReg

      \return true if the Zookeeper node should be presented as a ZkFuse
                   directory.
     */
    bool _isDir() const 
    {
        return !_isReg();
    }
    /**
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse regular
      file by taking into account the specified ZkFuseNameType.

      The ZkFuseNameType may override the default ZkFuse presentation of
      a ZooKeeper node. 

      \return true if ZkFuse should present the ZooKeeper node as a ZkFuse
                   regular file.
      \param nameType specifies the ZkFuseNameType.
      \param doLock whether _mutex should be acquired, it should be true
                    if the caller did not acquire _mutex.
     */
    bool _isRegNameType(ZkFuseNameType nameType, bool doLock = false) const
    {
        bool res;
        switch (nameType) {
          case ZkFuseNameRegType:
            res = true;
            break;
          case ZkFuseNameDirType:
            res = false;
            break;
          case ZkFuseNameDefaultType:
          default: 
            if (doLock) {
                AutoLock lock(_mutex);
                res = _isReg();
            } else {
                res = _isReg();
            }
            break;
        }
        LOG_DEBUG(LOG, "isRegNameType(nameType %d) returns %d", 
                  int(nameType), res);
        return res;
    }
    /**
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse 
      directory by taking into account the specified ZkFuseNameType.

      The ZkFuseNameType may override the default ZkFuse presentation of
      a ZooKeeper node. 

      \return true if ZkFuse should present the ZooKeeper node as a ZkFuse
                   directory.
      \param nameType specifies the ZkFuseNameType.
      \param doLock whether _mutex should be acquired, it should be true
                    if the caller did not acquire _mutex.
     */
    bool _isDirNameType(ZkFuseNameType nameType, bool doLock = false) const
    {
        bool res;
        switch (nameType) {
          case ZkFuseNameRegType:
            res = false; 
            break;
          case ZkFuseNameDirType:
            res = true;
            break;
          case ZkFuseNameDefaultType:
          default: 
            if (doLock) {
                AutoLock lock(_mutex);
                res = _isDir();
            } else {
                res = _isDir();
            }
            break;
        }
        LOG_DEBUG(LOG, "isDirNameType(nameType %d) returns %d", 
                  int(nameType), res);
        return res;
    }
    /**
      ZkFuse regular file metadata.
     */
    struct Metadata {
        /**
          Version of the ZooKeeper node data that this metadata is good for.
         */
        uint32_t version;
        /**
          Acces time in milliseconds.
         */
        uint64_t atime;
        /**
          Modified time in milliseconds.
         */
        uint64_t mtime;

        /**
          Constructor.
         */
        Metadata() 
          : version(0),
            atime(0),
            mtime(0)
        {
        }
    };
    /**
      Encode Metadata into Data so that it can be stored in a metadata
      ZooKeeper node.

      Each Metadata attribute is encoded as "<key>: <value>" on single line
      terminated by newline.

      \param meta the input Metadata.
      \param data the output Data after encoding.
     */
    void _encodeMetadata(const Metadata & meta, Data & data) const
    {
        LOG_DEBUG(LOG, "encodeMetadata()");
        std::ostringstream oss;
        oss << "version: " << meta.version << endl
            << "atime: " << meta.atime << endl
            << "mtime: " << meta.mtime << endl;
        data = oss.str();
    }
    /**
      Decode Data from a metadata child ZooKeeper node into Metadata. 

      Data is a stream of "<key>: <value>" records separated by newline.

      \param data the input Data.
      \param meta the output Metadata after decoding.
     */
    void _decodeMetadata(const Data & data, Metadata & meta) const
    {
        LOG_DEBUG(LOG, "decodeMetadata(data %s)", data.c_str());
        std::istringstream iss(data);
        char key[128];
        char value[1024];
        while (!iss.eof()) {
            key[0] = 0;
            value[0] = 0;
            iss.get(key, sizeof(key), ' ');
            if (iss.eof()) {
                break;
            }
            iss.ignore(32, ' ');
            iss.getline(value, sizeof(value));
            LOG_DEBUG(LOG, "key %s value %s", key, value);
            if (strcmp(key, "version:") == 0) {
                unsigned long long v = strtoull(value, NULL, 0);
                LOG_DEBUG(LOG, "version: %llu", v);
                meta.version = v;
            }
            else if (strcmp(key, "atime:") == 0) {
                unsigned long long v = strtoull(value, NULL, 0);
                LOG_DEBUG(LOG, "atime: %llu", v);
                meta.atime = v;
            }
            else if (strcmp(key, "mtime:") == 0) {
                unsigned long long v = strtoull(value, NULL, 0);
                LOG_DEBUG(LOG, "mtime: %llu", v);
                meta.mtime = v;
            }
            else {
                LOG_WARN(LOG, "decodeMetadata: path %s unknown key %s %s\n",
                         _path.c_str(), key, value);
            }
        }
        LOG_DEBUG(LOG, "decodeMetadata done");
    }
    /**
      Flush data to the ZooKeeper node.

      If cached active data has been modified, flush it to the ZooKeeper node.
      Returns -EIO if the data cannot be written because the cached active
      data is not the expected version, i.e. ZooKeeper returns ZBADVERSION.
      -EIO may also indicate a more general failure, such as unable to 
      communicate with ZooKeeper.

      \return 0 if successful, otherwise negative errno.
     */
    int _flush()
    {
        LOG_DEBUG(LOG, "flush() path %s", _path.c_str());

        int res = 0;
        try {
            if (_dirtyData) {
                LOG_DEBUG(LOG, "is dirty, active version %d",
                          _activeStat.version);
                _manager->getCommon().getZkAdapter()->
                    setNodeData(_path, _activeData, _activeStat.version);
                /* assumes version always increments by one if successful */
                _deleted = false;
                _activeStat.version++;
                _dirtyData = false;
                res = 0;
            } 
            else {
                LOG_DEBUG(LOG, "not dirty");
                res = 0;
            }
        } catch (const ZooKeeperException & e) {
            if (e.getZKErrorCode() == ZBADVERSION) {
                LOG_ERROR(LOG, "flush %s bad version, was %d",
                          _path.c_str(), _activeStat.version);
                res = -EIO;
            } 
            else {
                LOG_ERROR(LOG, "flush %s exception %s", 
                          _path.c_str(), e.what());
                res = -EIO;
            }
        }

        LOG_DEBUG(LOG, "flush returns %d", res);
        return res;
    }
    /**
      Truncate or expand the size of the cached active data.

      This method only changes the size of the cached active data. 
      This change is committed to ZooKeeper when the cached data 
      is written to the ZooKeeper node by flush().

      Return -EFBIG is the requested size exceeds the maximum.

      \return 0 if successful, otherwise negative errno.
      \param size the requested size.
     */
    int _truncate(off_t size) 
    {
        LOG_DEBUG(LOG, "truncate(size %zu) path %s", size, _path.c_str());
        
        int res = 0;

        if (!_isInitialized()) {
            LOG_DEBUG(LOG, "not initialized");
            res = -EIO;
        }
        else if (size > _activeData.size()) {
            if (size > maxDataFileSize) {
                LOG_DEBUG(LOG, "size > maxDataFileSize");
                res = -EFBIG;
            } else {
                LOG_DEBUG(LOG, "increase to size");
                _activeData.insert(_activeData.begin() + 
                                   (size - _activeData.size()), 0);
                _dirtyData = true;
                res = 0;
            }
        }
        else if (size < _activeData.size()) {
            LOG_DEBUG(LOG, "decrease to size");
            _activeData.resize(size);
            _dirtyData = true;
            res = 0;
        }
        else {
            LOG_DEBUG(LOG, "do nothing, same size");
        }

        LOG_DEBUG(LOG, "truncate returns %d", res);
        return res;
    }
    /**
      Remove a ZkFuse directory.

      If force is true, then the ZooKeeper node and its decendants
      will be deleted.

      If force is false, then this method implements the semantics
      of removing a ZkFuse directory. It will delete the ZooKeeper node
      only if the ZooKeeper node have no data and no non-metadata 
      children.
      - Return -ENOTDIR if the ZooKeeper node is not considered
        to be a directory (after taking into consideration the specified
        ZkFuseNameType). 
      - Return -ENOTEMPTY if the ZooKeeper node has data or it has 
        non-metadata children.
      - Return -ENOENT if the ZooKeeper cannot be deleted, usually this
        is because it does not exist.

      \return 0 if successful, otherwise negative errno.
      \param nameType the ZkFuseNameType of the path used to specify the
                      directory to be removed. It influences whether ZkFuse
                      considers the ZooKeeper node to be a regular file or
                      directory. \see ZkFuseNameType
      \param force    set to true to bypass ZkFuse rmdir semantic check.
     */
    int _rmdir(ZkFuseNameType nameType, bool force)
    {
        LOG_DEBUG(LOG, "rmdir(nameType %d, force %d) path %s", 
                  int(nameType), force, _path.c_str());

        int res = 0;
        try {
            if (!force && !_isDirNameType(nameType)) {
                LOG_DEBUG(LOG, "failed because not directory");
                res = -ENOTDIR;
            } 
            else if (!force && _hasData()) {
                /* rmdir cannot occur if there non-empty "data file" */
                LOG_DEBUG(LOG, "failed because node has data");
                res = -ENOTEMPTY;
            } 
            else if (!force && _hasChildrenExcludeMeta()) {
                /* rmdir cannot occur if there are "subdirs" */
                LOG_DEBUG(LOG, "failed because node has children");
                res = -ENOTEMPTY;
            } 
            else {
                LOG_DEBUG(LOG, "delete node");
                bool deleted = _manager->getCommon().getZkAdapter()->
                     deleteNode(_path, true);
                if (deleted) {
                    _deleted = true;
                    _clearChildren();
                    res = 0;
                } else {
                    /* TODO: differentiate delete error conditions,
                     * e.g. access permission, not exists, ... ?
                     */
                    LOG_DEBUG(LOG, "delete failed");
                    res = -ENOENT;
                }
            }
        } catch (const std::exception & e) {
            LOG_ERROR(LOG, "rmdir %s exception %s", _path.c_str(), e.what());
            res = -EIO;
        }

        LOG_DEBUG(LOG, "rmdir returns %d", res);
        return res;
    }
    /**
      Remove a ZkFuse regular file.

      This method implements the semantics of removing a ZkFuse regular file.
      - If the ZkFuse regular file represents the data part of the 
        ZooKeeper node which is presented as a ZkFuse directory, 
        the regular file is virtually deleted by truncating the
        ZooKeeper node's data. Readdir will not synthesize a regular 
        file entry for the data part of a ZooKeeper node if 
        the ZooKeeper node has no data.
      - If the ZkFuse regular file represents the data part of the 
        ZooKeeper node which is presented as a ZkFuse regular file,
        the ZooKeeper node and its decendants are deleted.

      Returns -EISDIR if the ZkFuse regular file cannot be deleted
      because ZkFuse consider it to be a directory.

      \return 0 if successful, otherwise negative errno.
      \param nameType the ZkFuseNameType of the path used to specify the
                      directory to be removed. It influences whether ZkFuse
                      considers the ZooKeeper node to be a regular file or
                      directory. \see ZkFuseNameType
    */
    int _unlink(ZkFuseNameType nameType) 
    {
        LOG_DEBUG(LOG, "unlink(nameType %d) path %s", 
                  int(nameType), _path.c_str());

        int res = 0;
        switch (nameType) {
          case ZkFuseNameRegType:
            if (_isDir()) {
                res = _truncate(0);
            } else {
                res = _rmdir(nameType, true);
            }
            break;
          case ZkFuseNameDirType:
            res = -EISDIR;
            break;
          case ZkFuseNameDefaultType:
          default:
            if (_isReg()) {
                res = _rmdir(nameType, true);
            } else {
                res = -EISDIR;
            }
            break;
        }

        LOG_DEBUG(LOG, "unlink returns %d", res);
        return res;
    }
    /**
      Whether cached children and data are valid.

      \return true if cached children and data are valid.
     */
    bool _isInitialized() const
    {
        return _initializedChildren && _initializedData;
    }
    /**
      Clear and invalidate cached children information.
     */
    void _clearChildren()
    {
        _initializedChildren = false;
        _children.clear();
    }
    /**
      Clear and invalidate cached data.
     */
    void _clearData() 
    {
        _initializedData = false;
        _dirtyData = false;
        _activeData.clear();
        _activeStat.clear();
        _latestData.clear();
        _latestStat.clear();
    }
    /**
      Whether the ZkFuseFile instance is a zombie.
      
      It is a zombie if it is not currently open, i.e. its reference count
      is 0.
     */
    bool _isZombie() const 
    {
        return (_refCount == 0);
    }
    /**
      Whether the ZkFuseFile instance is currently opened as a regular file
      only once.
      
      It is used to determine when the cached data can be replaced with
      the latest data. \see _activeData.
      
      \return true if its currently opened as a regular file only once.
     */
    bool _isOnlyRegOpen() const
    {
        return ((_refCount - _openDirCount) == 1);
    }
    /**
      Get attributes without accessing metadata.
      
      The atime and mtime returned does not take into consideration
      overrides present in a matadata file.

      \return 0 if successful, otherwise negative errno.
      \param stbuf return attributes here.
      \param nameType specifies the ZkFuseNameType of the ZkFuse path used
                      to get attributes. It influences whether the directory
                      or regular file attributes are returned.
     */
    int _getattrNoMetaAccess(struct stat & stbuf, ZkFuseNameType nameType) const
    {
        int res = 0;
        if (_deleted) {
            LOG_DEBUG(LOG, "deleted");
            res = -ENOENT;
        } 
        else if (!_isInitialized()) {
            LOG_DEBUG(LOG, "not initialized");
            res = -EIO;
        }
        else {   
            assert(_isInitialized());
            bool isRegular = _isRegNameType(nameType);
            if (isRegular) {
                LOG_DEBUG(LOG, "regular");
                stbuf.st_mode = regMode;
                stbuf.st_nlink = 1;
                stbuf.st_size = _activeData.size();
            } else {
                LOG_DEBUG(LOG, "directory");
                stbuf.st_mode = dirMode;
                stbuf.st_nlink = 
                    _children.size() + (_activeData.empty() ? 0 : 1);
                stbuf.st_size = stbuf.st_nlink;
            }
            stbuf.st_uid = _getUid();
            stbuf.st_gid = _getGid();
            /* IMPORTANT:
             * Conversion to secs from millisecs must occur before 
             * assigning to st_atime, st_mtime, and st_ctime. Otherwise
             * truncating from 64-bit to 32-bit will cause lost of
             * most significant 32-bits before converting to secs.
             */
            stbuf.st_atime = millisecsToSecs(_activeStat.mtime);
            stbuf.st_mtime = millisecsToSecs(_activeStat.mtime);
            stbuf.st_ctime = millisecsToSecs(_activeStat.ctime);
            stbuf.st_blksize = _getBlkSize();
            stbuf.st_blocks = 
                (stbuf.st_size + stbuf.st_blksize - 1) / stbuf.st_blksize;
            res = 0;
        }
        return res;
    }
    /**
      Get the context that should be registered with the data and
      children watches.

      The returned context is a pointer to the ZkFuseFile instance
      cast to the desired ContextType.

      \return the context.
     */
    ZooKeeperAdapter::ContextType _getZkContext() const
    {
        return (ZooKeeperAdapter::ContextType) NULL;
    }

    /**
      DataListener - listener that listens for ZooKeeper data events
      and calls dataEventReceived on the ZkFuseFile instance 
      identified by the event context.
      \see dataEventReceived
     */
    class DataListener : public ZKEventListener {
      public:
       /**
         Received a data event and invoke ZkFuseFile instance obtained from
         event context to handle the event.
        */
        virtual void eventReceived(const ZKEventSource & source,
                                   const ZKWatcherEvent & event)
        {
            assert(event.getContext() != 0);
            ZkFuseFile * file = static_cast<ZkFuseFile *>(event.getContext());
            file->dataEventReceived(event);
        }
    };
    
    /**
      DataListener - listener that listens for ZooKeeper children events
      and calls childrenEventReceived on the ZkFuseFile instance 
      identified by the event context.
      \see childrenEventReceived
     */
    class ChildrenListener : public ZKEventListener {
      public:
       /**
         Received a children event and invoke ZkFuseFile instance obtained from
         event context to handle the event.
        */
        virtual void eventReceived(const ZKEventSource & source,
                                   const ZKWatcherEvent & event)
        {
            assert(event.getContext() != 0);
            ZkFuseFile * file = static_cast<ZkFuseFile *>(event.getContext());
            file->childrenEventReceived(event);
        }
    };
    
    /**
      Globally shared DataListener. 
     */
    static DataListener _dataListener;
    /**
      Globally shared ChildrenListener. 
     */
    static ChildrenListener _childrenListener;

  public:
    /**
      Constructor.

      Sets reference count to one, i.e. it has been constructed because
      a client is trying to open the path. \see _refCount.
      Sets deleted to true. \see _deleted.
      Sets number of currently directory opens to zero. \see _openDirCount.
      Invalidate cach for children information and data. 

      \param manager the ZkFuseHandleManager instance who is creating this 
                     ZkFuseFile instance.
      \param handle  the handle assigned by the ZkFuseHandleManager instance
                     for this ZkFuseFile instance.
      \param path    the ZooKeeper path represented by this ZkFuseFile instance.
     */
    ZkFuseFile(const ZkFuseHandleManager::SharedPtr & manager,
               const int handle,
               const std::string & path)
      : _manager(manager),
        _handle(handle),
        _path(path),
        _mutex(),
        _refCount(1),
        _deleted(true),
        /* children stuff */
        _openDirCount(0),
        _initializedChildren(false),
        _hasChildrenListener(false),
        _children(),
        /* data stuff */
        _initializedData(false),
        _hasDataListener(false),
        _dirtyData(false), 
        _activeData(),
        _activeStat(),
        _latestData(),
        _latestStat()
    {
        LOG_DEBUG(LOG, "constructor() path %s", _path.c_str());
    }
    /**
      Destructor.
     */
    ~ZkFuseFile()
    {
        LOG_DEBUG(LOG, "destructor() path %s", _path.c_str());

        assert(_isZombie());
        _clearChildren();
        _clearData();
    }
    /**
      Whether the ZooKeeper node represented by this ZkFuseFile instance
      has been deleted.
      \see _deleted

      \return true if it is deleted.
     */
    bool isDeleted() const 
    { 
        AutoLock lock(_mutex);
        return _deleted;
    }
    /**
      Return the path of the ZooKeeper node represented by this ZkFuseFile
      instance.
      \see _path.

      \return the ZooKeeper node's path.
     */
    const string & getPath() const 
    {
        return _path;
    }
    /**
      Add a childPath to the children information cache.
      
      \return 0 if successful, otherwise return negative errno.
      \param childPath the ZooKeeper path of the child.
     */
    int addChild(const std::string & childPath) 
    {
        LOG_DEBUG(LOG, "addChild(childPath %s) path %s", 
                  childPath.c_str(), _path.c_str());

        int res = 0;
        {
            AutoLock lock(_mutex);
            if (_initializedChildren) {
                NodeNames::iterator it = 
                    std::find(_children.begin(), _children.end(), childPath);
                if (it == _children.end()) {
                    LOG_DEBUG(LOG, "child not found, adding child path");
                    _children.push_back(childPath);
                    res = 0;
                } 
                else {
                    LOG_DEBUG(LOG, "child found");
                    res = -EEXIST;
                }
            }
        }
        
        LOG_DEBUG(LOG, "addChild returns %d", res);
        return res;
    }
    /**
      Remove a childPath from the children information cache.
      
      \return 0 if successful, otherwise return negative errno.
      \param childPath the ZooKeeper path of the child.
     */
    int removeChild(const std::string & childPath) 
    {
        LOG_DEBUG(LOG, "removeChild(childPath %s) path %s", 
                  childPath.c_str(), _path.c_str());

        int res = 0;
        {
            AutoLock lock(_mutex);
            if (_initializedChildren) {
                NodeNames::iterator it = 
                    std::find(_children.begin(), _children.end(), childPath);
                if (it != _children.end()) {
                    LOG_DEBUG(LOG, "child found");
                    _children.erase(it);
                    res = 0;
                } 
                else {
                    LOG_DEBUG(LOG, "child not found");
                    res = -ENOENT;
                }
            }
        }
        
        LOG_DEBUG(LOG, "removeChild returns %d", res);
        return res;
    }
    /**
      Invalidate the cached children information and cached data.
      \see _clearChildren
      \see _clearData

      \param clearChildren set to true to invalidate children information cache.
      \param clearData set to true to invalidate data cache.
     */
    void clear(bool clearChildren = true, bool clearData = true)
    {
        LOG_DEBUG(LOG, "clear(clearChildren %d, clearData %d) path %s", 
                  clearChildren, clearData, _path.c_str());

        {
            AutoLock lock(_mutex);
            if (clearChildren) {
                _clearChildren();
            }
            if (clearData) {
                _clearData();
            }
        }
    }
    /** 
      Whether reference count is zero.
      \see _refCount

      \return true if reference count is zero.
     */
    bool isZombie() const 
    {
        AutoLock lock(_mutex);

        return (_refCount == 0);
    }
    /**
      Increment the reference count of the ZkFuseFile instance.

      This method may be called by a ZkFuseFileManager instance while
      holding the ZkFuseFileManager's _mutex. To avoid deadlocks, 
      this methods must never invoke a ZkFuseFileManager instance 
      directly or indirectly while holding the ZkFuseFile instance's
      _mutex.
      \see _refCount

      \return the post-increment reference count.
      \param count value to increment the reference count by.
     */
    int incRefCount(int count = 1)
    {
        LOG_DEBUG(LOG, "incRefCount(count %d) path %s", count, _path.c_str());

        int res = 0;
        {
            AutoLock lock(_mutex);
            _refCount += count;
            assert(_refCount >= 0);
            res = _refCount;
        }

        LOG_DEBUG(LOG, "incRefCount returns %d", res); 
        return res;
    }
    /**
      Decrement the reference count of the ZkFuseFile instance.

      This method may be called by a ZkFuseFileManager instance while
      holding the ZkFuseFileManager's _mutex. To avoid deadlocks, 
      this methods must never invoke a ZkFuseFileManager instance 
      directly or indirectly while holding the ZkFuseFile instance's
      _mutex.
      \see _refCount

      \return the post-decrement reference count.
      \param count value to decrement the reference count by.
     */
    int decRefCount(int count = 1)
    {
        return incRefCount(-count);
    }
    /**
      Increment the count of number times the ZkFuseFile instance has
      been opened as a directory.
      
      This count is incremented by opendir and decremented by releasedir.
      \see _openDirCount.

      \return the post-increment count.
      \param count the value to increment the count by.
     */
    int incOpenDirCount(int count = 1)
    {
        LOG_DEBUG(LOG, "incOpenDirCount(count %d) path %s", 
                  count, _path.c_str());

        int res = 0;
        {
            AutoLock lock(_mutex);
            _openDirCount += count;
            assert(_openDirCount >= 0);
            res = _openDirCount;
            assert(_openDirCount <= _refCount);
        }

        LOG_DEBUG(LOG, "incOpenDirCount returns %d", res); 
        return res;

    }
    /**
      Decrement the count of number times the ZkFuseFile instance has
      been opened as a directory.
      
      This count is incremented by opendir and decremented by releasedir.
      \see _openDirCount.

      \return the post-decrement count.
      \param count the value to decrement the count by.
     */
    int decOpenDirCount(int count = 1)
    {
        return incOpenDirCount(-count);
    }
    /**
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse 
      directory by taking into account the specified ZkFuseNameType.

      The ZkFuseNameType may override the default ZkFuse presentation of
      a ZooKeeper node. 
      \see _isDirNameType

      \return true if ZkFuse should present the ZooKeeper node as a ZkFuse
                   directory.
      \param nameType specifies the ZkFuseNameType.
     */
    bool isDirNameType(ZkFuseNameType nameType) const
    {
        return _isDirNameType(nameType, true);
    }
    /**
      Whether ZkFuse should present the ZooKeeper node as a ZkFuse 
      regular file by taking into account the specified ZkFuseNameType.

      The ZkFuseNameType may override the default ZkFuse presentation of
      a ZooKeeper node. 
      \see _isRegNameType

      \return true if ZkFuse should present the ZooKeeper node as a ZkFuse
                   regular file.
      \param nameType specifies the ZkFuseNameType.
     */
    bool isRegNameType(ZkFuseNameType nameType) const
    {
        return _isRegNameType(nameType, true);
    }
    /**
      Get the active data.
      \see _activeData

      \param data return data here.
     */
    void getData(Data & data) const
    {
        AutoLock lock(_mutex);

        data = _activeData;
    }
    /**
      Set the active data.
      \see _activeData

      Return -EFBIG is the data to be written is bigger than the maximum
      permitted size (and no data is written).

      \return 0 if successful, otherwise return negative errno.
      \param data set to this data.
      \param doFlush whether to flush the data to the ZooKeeper node.
     */
    int setData(const Data & data, bool doFlush)
    {
        LOG_DEBUG(LOG, "setData(doFlush %d) path %s", doFlush, _path.c_str());
        int res = 0;

        if (data.size() > maxDataFileSize) {
            res = -EFBIG;
        } 
        else {
            AutoLock lock(_mutex);
            _activeData = data;
            _dirtyData = true;
            if (doFlush) {
                res = _flush();
            }
        }

        LOG_DEBUG(LOG, "setData() returns %d", res);
        return res;
    }
    /**
      Update the children information and the data caches as needed.

      This method is invoked when a ZkFuse regular file or directory 
      implemented by this ZkFuseFile instance is opened, e.g.
      using open or opendir. It attempts to:
      - make sure that the cache has valid children information
      - register for watches for changes if no previous watches have
        been registered.

      The newFile flag indicates if the ZkFuseFile instance has just
      been constructed and that ZooKeeper has not been contacted to
      determine if the ZooKeeper path for this file really exist.
      When a ZkFuseFile instance is created, the _deleted flag is set to
      true because it is safer to assume that the ZooKeeper node does
      not exist. The newFile flag causes the _deleted flag to be
      ignored and ZooKeeper to be contacted to update the caches.

      If the newFile flag is false, then the ZkFuseFile instance is
      currently open and have been opened before. Hence, these previous
      opens should have contacted ZooKeeper and would like learned from
      ZooKeeper whether the ZooKeeper path exists. Therefore, 
      the _deleted flag should be trustworthy, i.e. it has accurate 
      information on whether the ZooKeeper path actually exists.

      \return 0 if successful, otherwise return negative errno.
      \param newFile set to true if the ZkFuseFile instance is newly created.
     */
    int update(bool newFile)
    {
        LOG_DEBUG(LOG, "update(newFile %d) path %s", newFile, _path.c_str());

        int res = 0;
        {
            AutoLock lock(_mutex);

            /* At this point, cannot be zombie.
             */
            assert(!_isZombie());
            if (!newFile && _deleted) {
                /* Deleted file, don't bother to update caches */
                LOG_DEBUG(LOG, "deleted, not new file"); 
                res = -ENOENT;
            }
            else {
                try {
                    LOG_DEBUG(LOG, "initialized children %d, data %d",
                              _initializedChildren, _initializedData);
                    LOG_DEBUG(LOG, "has children watch %d, data watch %d",
                              _hasChildrenListener, _hasDataListener);
                    /*
                     * Children handling starts here.
                     * If don't have children listener,
                     *    then must establish listener.
                     * If don't have cached children information, 
                     *    then must get children information. 
                     * It just happens, that the same ZooKeeper API 
                     * is used for both.
                     */
                    if (_initializedChildren == false ||
                        _hasChildrenListener == false
#ifdef ZOOKEEPER_ROOT_CHILDREN_WATCH_BUG
                        /* HACK for root node because changes to children
                         * on a root node does not cause children watches to
                         * fire.
                         */
                        || _path.length() == 1
#endif // ZOOKEEPER_ROOT_CHILDREN_WATCH_BUG
                    ) {
                        LOG_DEBUG(LOG, "update children");
                        NodeNames children;
                        _manager->getCommon().getZkAdapter()->
                          getNodeChildren( children, _path, 
                                          &_childrenListener, _getZkContext());
                        _hasChildrenListener = true;
                        LOG_DEBUG(LOG, "update children done"); 
                        _children.swap(children);
                        _initializedChildren = true;
                        /* Since getNodeChildren is successful, the
                         * path must exist */
                        _deleted = false;
                    }
                    else {
                        /* Children information is fresh since 
                         * it is initialized and and have been 
                         * updated by listener.
                         */
                    }
                    /*
                     * Data handling starts here.
                     */
                    assert(newFile == false || _isOnlyRegOpen());
                    if (!_isOnlyRegOpen()) {
                        /* If is already currently opened by someone,
                         * then don't update data with latest from ZooKeeper,
                         * use current active data (which may be initialized 
                         * or not).
                         * \see _activeData
                         */
                        LOG_DEBUG(LOG, "node currently in-use, no data update");
                    } 
                    else {
                        /* If not opened/reopened by someone else, 
                         *    then perform more comprehensive checks of
                         *    to make data and listener is setup correctly.
                         * If don't have data listener,
                         *    then must establish listener.
                         * If don't have cached data, 
                         *    then must get data.
                         * It just happens, that the same ZooKeeper API 
                         * is used for both.  
                         */
                        LOG_DEBUG(LOG, "node first use or reuse");
                        if (_initializedData == false ||
                            _hasDataListener == false) {
                            /* Don't have any data for now or need to register
                             * for callback */
                            LOG_DEBUG(LOG, "update data");
                            _latestData = 
                                _manager->getCommon().getZkAdapter()->
                                getNodeData(_path, &_dataListener, 
                                            _getZkContext(), 
                                            &_latestStat);
                            _hasDataListener = true;
                            LOG_DEBUG(LOG, 
                                      "update data done, latest version %d",
                                      _latestStat.version);
                            /* Since getNodeData is successful, the
                             * path must exist. */
                            _deleted = false;
                        } 
                        else {
                            /* Data is fresh since it is initialized and
                             * and have been updated by listener.
                             */
                        }
                        /* Update active data to the same as the most 
                         * recently acquire data.
                         */
                        _activeData = _latestData;
                        _activeStat = _latestStat;
                        _initializedData = true;
                        _dirtyData = false;
                        LOG_DEBUG(LOG, "update set active version %d",
                                  _activeStat.version);
                    } 
                    res = 0;
                } catch (const ZooKeeperException & e) {
                    /* May have ZNONODE exception if path does exist. */
                    if (e.getZKErrorCode() == ZNONODE) {
                        LOG_DEBUG(LOG, "update %s exception %s", 
                                  _path.c_str(), e.what());
                        /* Path does not exist, set _deleted, 
                         * clear children information cache 
                         */
                        _deleted = true;
                        _clearChildren();
                        res = -ENOENT;
                    } else {
                        LOG_ERROR(LOG, "update %s exception %s", 
                                  _path.c_str(), e.what());
                        res = -EIO;
                    }
                }
            }
        }
    
        LOG_DEBUG(LOG, "update returns %d", res);
        return res;
    }
    /**
      Process a data event.

      This method may:
      - Invalidate the data cache.
      - Invoke ZooKeeper to update the data cache and register a new
        data watch so that the cache can be kept in-sync with the
        ZooKeeper node's data.

      This method does not change the active data. Active data will be
      changed to a later version by update() at the appropriate time.
      \see update.
     */
    void dataEventReceived(const ZKWatcherEvent & event) 
    {
        bool reclaim = false;
        int eventType = event.getType();
        int eventState = event.getState();

        /*
          IMPORTANT: 
          
          Do not mark ZkFuseFile instance as deleted when a ZOO_DELETED_EVENT 
          is received without checking with ZooKeeper. An example of 
          problematic sequence would be:

          1. Create node.
          2. Set data and watch.
          3. Delete node.
          4. Create node.
          5. Deleted event received.

          It is a bug to mark the ZkFuseFile instance as deleted after 
          step 5 because the node exists.
          
          Therefore, this method should always contact ZooKeeper to keep the
          data cache (and deleted status) up-to-date if necessary.
         */
        LOG_DEBUG(LOG, "dataEventReceived() path %s, type %d, state %d",
                  _path.c_str(), eventType, eventState);
        {
            AutoLock lock(_mutex);

            _hasDataListener = false;
            /* If zombie, then invalidate cached data.
             * This clears _initializedData and eliminate 
             * the need to get the latest data from ZooKeeper and
             * re-register data watch. 
             */
            if (_isZombie() && _initializedData) {
                LOG_DEBUG(LOG, "invalidate data");
                _clearData();
            }
            else if ((_refCount - _openDirCount) > 0) {
                /* Don't invalidate cached data because clients of currently
                 * open files don't expect the data to change from under them.
                 * If data acted upon by these clients have become stale,
                 * then the clients will get an error when ZkFuse attempts to
                 * flush dirty data. The clients will not get error 
                 * notification if they don't modify the stale data.
                 *
                 * If data cache is cleared here, then the following code 
                 * to update data cache and re-register data watch will not 
                 * be executed and may result in the cached data being
                 * out-of-sync with ZooKeeper.
                 */
                LOG_WARN(LOG, 
                         "%s data has changed while in-use, "
                         "type %d, state %d, refCount %d",
                         _path.c_str(), eventType, eventState, _refCount);
            }
            /* If cache was valid and still connected
             * then get the latest data from ZooKeeper 
             * and re-register data watch. This is required to keep 
             * the data cache in-sync with ZooKeeper.
             */ 
            if (_initializedData && 
                eventState == ZOO_CONNECTED_STATE 
               ) {
                try {
                    LOG_DEBUG(LOG, "register data watcher");
                    _latestData = 
                        _manager->getCommon().getZkAdapter()->
                        getNodeData(_path, &_dataListener, _getZkContext(), 
                                    &_latestStat);
                    _hasDataListener = true;
                    LOG_DEBUG(LOG, 
                              "get data done, version %u, cversion %u done",
                              _latestStat.version, _latestStat.cversion);
                    _deleted = false;
                } catch (const ZooKeeperException & e) {
                    if (e.getZKErrorCode() == ZNONODE) {
                        _deleted = true;
                        _clearChildren();
                    }
                    LOG_ERROR(LOG, "dataEventReceived %s exception %s", 
                              _path.c_str(), e.what());
                }
            }
        }
        LOG_DEBUG(LOG, "dataEventReceived return %d", reclaim);
    }
    /**
      Process a children event.

      This method may:
      - Invalidate the children information cache.
      - Invoke ZooKeeper to update the children cache and register a new
        data watch so that the cache can be kept in-sync with the
        ZooKeeper node's children information.
     */
    void childrenEventReceived(const ZKWatcherEvent & event) 
    {
        bool reclaim = false;
        int eventType = event.getType();
        int eventState = event.getState();

        LOG_DEBUG(LOG, "childrenEventReceived() path %s, type %d, state %d",
                  _path.c_str(), eventType, eventState);
        {
            AutoLock lock(_mutex);

            _hasChildrenListener = false;
            /* If zombie or disconnected, then invalidate cached children 
             * information. This clears _initializedChildren and eliminate 
             * the need to get the latest children information and
             * re-register children watch.
             */
            if (_initializedChildren && 
                (_isZombie() || eventState != ZOO_CONNECTED_STATE)) {
                LOG_DEBUG(LOG, "invalidate children");
                _clearChildren();
            }
            else if (_initializedChildren) {
                /* Keep cached children information so that we have some
                 * children information if get new children information
                 * fails. If there is failure, then on next open, 
                 * update() will attempt again to get children information
                 * again because _hasChildrenListener will be false.
                 *
                 * If children information cache is cleared here, then
                 * the following code to update children information cache
                 * and re-register children watch will not be executed
                 * and may result in the cached children information being
                 * out-of-sync with ZooKeeper.
                 *
                 * The children cache will be cleared if unable to 
                 * get children and re-establish watch.
                 */
                LOG_WARN(LOG, 
                         "%s children has changed while in-use, "
                         "type %d, state %d, refCount %d",
                         _path.c_str(), eventType, eventState, _refCount);
            }
            /* If children cache was valid and still connected, 
             * then get the latest children information from ZooKeeper 
             * and re-register children watch. This is required to 
             * keep the children information cache in-sync with ZooKeeper.
             */ 
            if (_initializedChildren && 
                eventState == ZOO_CONNECTED_STATE 
               ) {
                /* Should try to keep the cache in-sync, register call 
                 * callback again and get current children.
                 */ 
                try {
                    LOG_DEBUG(LOG, "update children");
                    NodeNames children;
                    _manager->getCommon().getZkAdapter()->
                      getNodeChildren(children, _path, 
                                      &_childrenListener, _getZkContext());
                    _hasChildrenListener = true;
                    LOG_DEBUG(LOG, "update children done");
                    _children.swap(children);
                    _deleted = false;
                } catch (const ZooKeeperException & e) {
                    if (e.getZKErrorCode() == ZNONODE) {
                        _deleted = true;
                        _clearChildren();
                    }
                    LOG_ERROR(LOG, "childrenEventReceived %s exception %s", 
                              _path.c_str(), e.what());
                    _children.clear();
                }
            }
        }
        LOG_DEBUG(LOG, "childrenEventReceived returns %d", reclaim);
    }
    /**
      Truncate or expand the size of the cached active data.

      This method only changes the size of the cached active data. 
      This change is committed to ZooKeeper when the cached data 
      is written to the ZooKeeper node by flush().

      Return -EFBIG is the requested size exceeds the maximum.

      \return 0 if successful, otherwise negative errno.
      \param size the requested size.
     */
    int truncate(off_t size) 
    {
        int res = 0;

        {
            AutoLock lock(_mutex); 
            res = _truncate(size);
        }

        return res;
    }
    /**
      Copy range of active data into specified output buffer.

      \return if successful, return number of bytes copied, otherwise
              return negative errno.
      \param buf  address of the output buffer.
      \param size size of the output buffer and desired number of bytes to copy.
      \param offset offset into active data to start copying from.
     */
    int read(char *buf, size_t size, off_t offset) const
    {
        LOG_DEBUG(LOG, "read(size %zu, off_t %zu) path %s", 
                  size, offset, _path.c_str());

        int res = 0;

        {
            AutoLock lock(_mutex);
            if (!_initializedData) {
                LOG_DEBUG(LOG, "not initialized");
                res = -EIO;
            }
            else {
                off_t fileSize = _activeData.size();
                if (offset > fileSize) {
                    LOG_DEBUG(LOG, "offset > fileSize %zu", fileSize);
                    res = 0;
                } 
                else {
                    if (offset + size > fileSize) {
                        size = fileSize - offset;
                        LOG_DEBUG(LOG, 
                                  "reducing read size to %zu for fileSize %zu",
                                  size, fileSize);
                    }
                    copy(_activeData.begin() + offset,
                         _activeData.begin() + offset + size,
                         buf);
                    res = size;
                }
            }
        }

        LOG_DEBUG(LOG, "read returns %d", res);
        return res; 
    }
    /**
      Copy buffer content to active data.

      \return if successful, return number of bytes copied, otherwise
              return negative errno.
      \param buf  address of the buffer.
      \param size size of the input buffer and desired number of bytes to copy.
      \param offset offset into active data to start copying to.
     */
    int write(const char *buf, size_t size, off_t offset)
    {
        LOG_DEBUG(LOG, "write(size %zu, off_t %zu) path %s", 
                  size, offset, _path.c_str());

        int res = 0;

        {
            AutoLock lock(_mutex);
            if (!_initializedData) {
                LOG_DEBUG(LOG, "not initialized");
                res = -EIO;
            }
            else if (offset >= maxDataFileSize) {
                LOG_DEBUG(LOG, "offset > maxDataFileSize %u", maxDataFileSize);
                res = -ENOSPC;
            }
            else {
                if (offset + size > maxDataFileSize) {
                    LOG_DEBUG(LOG, 
                              "reducing write size to %zu "
                              "for maxDataFileSize %u",
                              size, maxDataFileSize);
                    size = maxDataFileSize - offset;
                }
                off_t fileSize = _activeData.size();
                if (offset + size > fileSize) {
                    LOG_DEBUG(LOG, "resizing to %zu", offset + size);
                    _activeData.resize(offset + size);
                } 
                copy(buf, buf + size, _activeData.begin() + offset);
                memcpy(&_activeData[offset], buf, size);
                _dirtyData = true;
                res = size;
            }
        }

        LOG_DEBUG(LOG, "write returns %d", res);
        return res; 
    }
    /**
      Flush data to the ZooKeeper node.

      If cached active data has been modified, flush it to the ZooKeeper node.
      Returns -EIO if the data cannot be written because the cached active
      data is not the expected version, i.e. ZooKeeper returns ZBADVERSION.
      -EIO may also indicate a more general failure, such as unable to 
      communicate with ZooKeeper.

      \return 0 if successful, otherwise negative errno.
     */
    int flush()
    {
        int res = 0;
        {
            AutoLock lock(_mutex);
            res = _flush();
        }
        return res;
    }
    /**
      Close of the ZkFuse regular file represented by the ZkFuseFile instance.

      This may: 
      - Flush dirty data to the ZooKeeper node, and return the result of the
        flush operation.
      - Reclaim the ZkFuseFile instance. 
        \see ZkFuseHandleManaer::reclaimIfNecessary

      \return result of flush operation - 0 if successful, 
              otherwise negative errno.
     */
    int close()
    {
        LOG_DEBUG(LOG, "close() path %s", _path.c_str());
        int res = 0;

        bool reclaim = false;
        {
            AutoLock lock(_mutex);
            res = _flush();
            if (_deleted) {
                _clearData();
                _clearChildren();
            }
        }
        _manager->deallocate(_handle);

        LOG_DEBUG(LOG, "close returns %d", res);
        return res;
    }
    /**
      Get ZkFuse regular file or directory attributes.

      \return 0 if successful, otherwise negative errno.
      \param stbuf return attributes here.
      \param nameType specifies the ZkFuseNameType of the ZkFuse path used
                      to get attributes. It influences whether the directory
                      or regular file attributes are returned.
     */
    int getattr(struct stat & stbuf, ZkFuseNameType nameType) const
    {
        LOG_DEBUG(LOG, "getattr(nameType %d) path %s", 
                  int(nameType), _path.c_str());

        int res = 0;
        int version = 0;
        std::string metaPath;
        {
            AutoLock lock(_mutex);

            res = _getattrNoMetaAccess(stbuf, nameType);
            if (res == 0) {
                version = _activeStat.version;
                metaPath = _getChildPath( 
                    ((stbuf.st_mode & S_IFMT) == S_IFREG) ? 
                    _manager->getCommon().getRegMetadataName() :
                    _manager->getCommon().getDirMetadataName());
                if (_hasChildPath(metaPath) == false) {
                    metaPath.clear();
                }
            }
        }
        if (res == 0 && metaPath.empty() == false) {
            Data data;
            int metaRes = _manager->getData(metaPath, data);
            LOG_DEBUG(LOG, "metaRes %d dataSize %zu",
                      metaRes, data.size());
            if (metaRes == 0 && data.empty() == false) {
                 Metadata metadata; 
                 _decodeMetadata(data, metadata);
                 LOG_DEBUG(LOG, "metadata version %u active version %u",
                           metadata.version, version);
                 if (metadata.version == version) {
                     /* IMPORTANT: 
                      * Must convert from millisecs to secs before setting
                      * st_atime and st_mtime to avoid truncation error
                      * due to 64-bit to 32-bit conversion.
                      */
                     stbuf.st_atime = millisecsToSecs(metadata.atime);
                     stbuf.st_mtime = millisecsToSecs(metadata.mtime);
                }
            }
        }
    
        LOG_DEBUG(LOG, "getattr returns %d", res);
        return res;
    }
    /**
      Read directory entries.
      This interface is defined by FUSE.
      
      \return 0 if successful, otherwise negative errno.
      \param buf output buffer to store output directory entries.
      \param filler function used to fill the output buffer.
      \param offset start filling from a specific offset.
     */
    int readdir(void *buf, fuse_fill_dir_t filler, off_t offset) const
    {
        LOG_DEBUG(LOG, "readdir(offset %zu) path %s", offset, _path.c_str());
        int res = 0;

        int dataFileIndex = -1;
        unsigned leftTrim = 0;
        typedef std::pair<std::string, int> DirEntry;
        typedef std::vector<DirEntry> DirEntries; 
        DirEntries dirEntries;

        /* Get directory entries in two phase to avoid invoking
         * ZkFuseHandleManager while holding _mutex.
         * In first phase, get all the names of child nodes starting
         * at offset. Also remember their index for use in second phase.
         * The first phase hold _mutex.
         */
        {
            AutoLock lock(_mutex);
            if (!_isInitialized()) {
                LOG_DEBUG(LOG, "not initialized");
                res = -EIO;
            }
            else {
                leftTrim = (_path.length() == 1 ? 1 : _path.length() + 1);
                unsigned start = offset;
                unsigned i;
                for (i = start; i < _children.size(); i++) { 
                    const std::string & childName = _children[i];
                    if (_isMeta(childName)) {
                        continue;
                    }
                    dirEntries.push_back(DirEntry(childName, i));
                }
                if (i == _children.size() && !_activeData.empty()) {
                    dataFileIndex = i + 1;
                }
                res = 0;
            }
        }
        
        /* Second phase starts here.
         * DONOT hold _mutex as this phase invokes ZkFuseHandleManager to
         * get attributes for the directory entries.
         */ 
        if (res == 0) {
            bool full = false;
            for (DirEntries::const_iterator it = dirEntries.begin();
                it != dirEntries.end();
                it++) {
               
                ZkFuseAutoHandle childAutoHandle(_manager, it->first);
                int childRes = childAutoHandle.get();
                if (childRes >= 0) {
                    struct stat stbuf; 
                    int attrRes = childAutoHandle.getFile()->
                        getattr(stbuf, ZkFuseNameDefaultType);
                    if (attrRes == 0) {
                        if (filler(buf, it->first.c_str() + leftTrim, 
                                   &stbuf, it->second + 1)) {
                            LOG_DEBUG(LOG, "filler full");
                            full = true;
                            break;
                        } 
                    }
                }
            } 
            if (full == false && dataFileIndex != -1) { 
                LOG_DEBUG(LOG, "include data file name");
                struct stat stbuf; 
                int attrRes = getattr(stbuf, ZkFuseNameRegType); 
                if (attrRes == 0) {
                    filler(buf, 
                           _manager->getCommon().getDataFileName().c_str(), 
                           &stbuf, dataFileIndex + 1);
                }
            }
        }
    
        LOG_DEBUG(LOG, "readdir returns %d", res);
        return res;
    }
    /**
      Set the access time and modified time.

      Set the access and modifieds times on the ZkFuse regular file
      or directory represented by this ZkFuseFile instance.
      
      Since there is no interface to change these times on a 
      ZooKeeper node, ZkFuse simulates this by writing to a 
      metadata node which is a child node of the ZooKeeper node.
      ZkFuse writes the current version, the specified access 
      and modified times to the metadata node. 
      
      When get attributes is invoked, get attributes will check 
      for the presence of this metadata node and if the version
      number matches the current data version, then get attributes
      will return the access and modified times stored in the 
      metadata node.

      \return 0 if successful, otherwise negative errno.
      \param atime access time in milliseconds.
      \param mtime modified time in milliseconds.
      \param nameType specifies the ZkFuseNameType of the ZkFuse path used
                      to set access and modified times. It influences 
                      whether the directory or regular file access and
                      modified times are set.
     */
    int utime(uint64_t atime, uint64_t mtime, ZkFuseNameType nameType) 
    {
        LOG_DEBUG(LOG, 
                  "utime(atime %llu, mtime %llu, nameType %d) path %s",
                  (unsigned long long) atime, 
                  (unsigned long long) mtime, 
                  (int) nameType, _path.c_str());

        int res = 0;
        std::string metaPath;
        bool exists = false;
        Data data;
        {
            AutoLock lock(_mutex);
    
            if (!_isInitialized()) {
                LOG_DEBUG(LOG, "not initialized");
                res = -EIO;
            }
            else {
                bool isRegular = _isRegNameType(nameType);
                Metadata metadata;
                metadata.version = _activeStat.version;
                metadata.atime = atime;
                metadata.mtime = mtime;
                metaPath = _getChildPath( 
                    isRegular ?  
                    _manager->getCommon().getRegMetadataName() :
                    _manager->getCommon().getDirMetadataName());
                exists = _hasChildPath(metaPath);
                _encodeMetadata(metadata, data);
                res = 0;
            }
        }
        if (res == 0 && metaPath.empty() == false) { 
            res = _manager->setData(metaPath, data, exists, true);
        }

        LOG_DEBUG(LOG, "utime returns %d", res);
        return res;
    }
    /**
      Remove a ZkFuse directory.

      If force is true, then the ZooKeeper node and its decendants
      will be deleted.

      If force is false, then this method implements the semantics
      of removing a ZkFuse directory. It will delete the ZooKeeper node
      only if the ZooKeeper node have no data and no non-metadata 
      children.
      - Return -ENOTDIR if the ZooKeeper node is not considered
        to be a directory (after taking into consideration the specified
        ZkFuseNameType). 
      - Return -ENOTEMPTY if the ZooKeeper node has data or it has 
        non-metadata children.
      - Return -ENOENT if the ZooKeeper cannot be deleted, usually this
        is because it does not exist.

      \return 0 if successful, otherwise negative errno.
      \param nameType the ZkFuseNameType of the path used to specify the
                      directory to be removed. It influences whether ZkFuse
                      considers the ZooKeeper node to be a regular file or
                      directory. \see ZkFuseNameType
      \param force    set to true to bypass ZkFuse rmdir semantic check.
     */
    int rmdir(ZkFuseNameType nameType, bool force)
    {
        int res = 0;

        {
            AutoLock lock(_mutex);
            res = _rmdir(nameType, force);
        }
        if (res == 0) {
            _manager->removeChildFromParent(_path);
        }
        return res;
    }
    /**
      Remove a ZkFuse regular file.

      This method implements the semantics of removing a ZkFuse regular file.
      - If the ZkFuse regular file represents the data part of the 
        ZooKeeper node which is presented as a ZkFuse directory, 
        the regular file is virtually deleted by truncating the
        ZooKeeper node's data. Readdir will not synthesize a regular 
        file entry for the data part of a ZooKeeper node if 
        the ZooKeeper node has no data.
      - If the ZkFuse regular file represents the data part of the 
        ZooKeeper node which is presented as a ZkFuse regular file,
        the ZooKeeper node and its decendants are deleted.

      Returns -EISDIR if the ZkFuse regular file cannot be deleted
      because ZkFuse consider it to be a directory.

      \return 0 if successful, otherwise negative errno.
      \param nameType the ZkFuseNameType of the path used to specify the
                      directory to be removed. It influences whether ZkFuse
                      considers the ZooKeeper node to be a regular file or
                      directory. \see ZkFuseNameType
    */
    int unlink(ZkFuseNameType nameType) 
    {
        int res = 0;
        {
            AutoLock lock(_mutex);
            res = _unlink(nameType);
        }
        if (res == 0) {
            _manager->removeChildFromParent(_path);
        }
        return res;
    }
    /**
      Utility function to construct a ZooKeeper path for a child
      of a ZooKeeper node.
      
      \return the full path of the child.
      \param  parent the parent's full path.
      \param  child  the child's parent component.
     */
    static std::string buildChildPath(const std::string & parent,
                                      const std::string & child)
    {
        std::string s;
        s.reserve(parent.length() + child.length() + 32);
        if (parent.length() > 1) {
            // special case for root dir
            s += parent;
        }
        s += "/";
        s += child;
        return s;
    }
};

ZkFuseFile::DataListener ZkFuseFile::_dataListener;
ZkFuseFile::ChildrenListener ZkFuseFile::_childrenListener;

void ZkFuseAutoHandle::reset(int handle)
{
    int old = _handle;
    ZkFuseFilePtr oldFile = _file;
    _handle = handle;
    _initFile();
    if (old >= 0) {
        assert(oldFile != NULL);
        oldFile->close();
    }
}

ZkFuseHandleManager::Handle 
ZkFuseHandleManager::allocate(const std::string & path, bool & newFile)
{
    LOG_DEBUG(LOG, "allocate(path %s)", path.c_str());

    Handle handle;
    {
        AutoLock lock(_mutex);
        Map::iterator it = _map.find(path);
        if (it == _map.end()) {
            LOG_DEBUG(LOG, "not found");
            if (_freeList.empty()) {
                handle = _files.size();
                _files.resize(handle + 1);
                LOG_DEBUG(LOG, "free list empty, resize handle %d", handle);
            } else {
                handle = _freeList.back();
                _freeList.pop_back();
                LOG_DEBUG(LOG, "get from free list, handle %d", handle);
            }
            assert(_files[handle] == NULL);
            _files[handle] = 
                new ZkFuseFile(SharedPtr(_thisWeakPtr), handle, path);
            /* Not really supposed to invoke the new ZkFuseFile instance 
             * because this method is not supposed to invoke ZkFuseFile
             * methods that while holding _mutex. However, it is safe
             * to do without casuing deadlock because these methods
             * are known not to invoke other methods, especially one
             * that invoke this ZkFuseHandleManager instance.
             */
            assert(_files[handle]->incRefCount(0) == 1);
            _map[path] = handle;
            _numInUse++;
            LOG_DEBUG(LOG, "numInUse %u", _numInUse);
            newFile = true;
        } else {
            LOG_DEBUG(LOG, "found");
            handle = it->second;
            assert(_files[handle] != NULL);
            int refCount = _files[handle]->incRefCount();
            if (refCount == 1) {
                _numInUse++;
                LOG_DEBUG(LOG, "resurrecting zombie, numInUse %u", _numInUse);
            }
            newFile = false;
        }
    }

    LOG_DEBUG(LOG, "allocate returns %d, newFile %d", handle, newFile);
    return handle;
}

void ZkFuseHandleManager::deallocate(Handle handle) 
{
    LOG_DEBUG(LOG, "deallocate(handle %d)", handle);

    if (handle >= 0) {
        bool reclaim = false;
        ZkFuseFilePtr file; 
        {
            AutoLock lock(_mutex);
            file = _files[handle];
            assert(file != NULL);
            int refCount = file->decRefCount();
            const std::string & path = file->getPath();
            LOG_DEBUG(LOG, "path %s ref count %d", path.c_str(), refCount);
            if (refCount == 0) {
                _numInUse--;
                unsigned numCached = _files.size() - _numInUse;
                if (numCached > _common.getCacheSize()) {
                   LOG_TRACE(LOG, 
                             "reclaim path %s, cacheSize %u, filesSize %zu, "
                             "numInUse %u", 
                             path.c_str(),
                             _common.getCacheSize(), _files.size(), _numInUse);
                   _map.erase(path); 
                   _files[handle] = NULL;
                   _freeList.push_back(handle); 
                   reclaim = true;
                }
            }
        } 
        if (reclaim) {
            delete file;
        }
    }
    else {
        LOG_DEBUG(LOG, "handle invalid");
    }

    LOG_DEBUG(LOG, "deallocate done");
}

void ZkFuseHandleManager::eventReceived(const ZKWatcherEvent & event)
{
    int eventType = event.getType();
    int eventState = event.getState();
    const std::string & path = event.getPath();
    LOG_DEBUG(LOG, "eventReceived() eventType %d, eventState %d, path %s",
              eventType, eventState, path.c_str());

    if (eventType == ZOO_DELETED_EVENT ||
        eventType == ZOO_CHANGED_EVENT ||
        eventType == ZOO_CHILD_EVENT) {
        {
            AutoLock lock(_mutex);
            Map::iterator it = _map.find(path);
            if (it != _map.end()) {
                LOG_DEBUG(LOG, "path found");
                Handle handle = it->second;
                ZkFuseFilePtr file = _files[handle];
                assert(file != NULL);
                /* Prevent the ZkFuseFile instance from being
                 * deleted while handling the event.
                 */
                int refCount = file->incRefCount();
                if (refCount == 1) {
                    _numInUse++;
                }
                /* Pretent to be dir open.
                 */
                int dirCount = file->incOpenDirCount();
                {
                    /* _mutex is unlocked in this scope */
                    AutoUnlockTemp autoUnlockTemp(lock);
                    if (eventType == ZOO_CHILD_EVENT) {
                        file->childrenEventReceived(event);
                    }
                    else if (eventType == ZOO_CHANGED_EVENT) {
                        file->dataEventReceived(event);
                    }
                    else {
                        assert(eventType == ZOO_DELETED_EVENT);
                        file->dataEventReceived(event);
                        // file->childrenEventReceived(event);
                    }
                    file->decOpenDirCount();
                    deallocate(handle);
                }
            }
            else {
                LOG_WARN(LOG, 
                         "path %s not found for event type %d, event state %d",
                          path.c_str(), eventType, eventState);
            }
        }
    } 
    else if (eventType == ZOO_SESSION_EVENT) {
        if (eventState == ZOO_CONNECTING_STATE) {
            LOG_TRACE(LOG, "*** CONNECTING ***");
            {
                AutoLock lock(_mutex);
                for (int handle = 0; handle < _files.size(); handle++) { 
                    ZkFuseFilePtr file = _files[handle];
                    if (file != NULL) {
                        /* prevent the ZkFuseFile instance from being 
                         * deleted while handling the event. 
                         */
                        int refCount = file->incRefCount();
                        if (refCount == 1) {
                             _numInUse++;
                        }
                        /* Pretent to be dir open.
                         */ 
                        int dirCount = file->incOpenDirCount();
                        {
                            /* _mutex is unlocked in this scope */
                            AutoUnlockTemp autoUnlockTemp(lock);
                            file->dataEventReceived(event);
                            file->childrenEventReceived(event);
                            file->decOpenDirCount();
                            deallocate(handle);
                        }
                        /* this will eventually call decrement ref count */
                    }
                }
            }
        }
        else if (eventState == ZOO_CONNECTED_STATE) {
            LOG_TRACE(LOG, "*** CONNECTED ***");
        }
    }
    else {
        LOG_WARN(LOG, 
                 "eventReceived ignoring event type %d, event state %d, "
                 "path %s", eventType, eventState, path.c_str());
    }
}

int ZkFuseHandleManager::getData(const std::string & path, 
                                 Data & data) 
{
    LOG_DEBUG(LOG, "getData(path %s)", path.c_str());

    int res = 0;
    data.clear();
    ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), path);
    res = autoHandle.get();
    if (res >= 0) {
        autoHandle.getFile()->getData(data);
        res = 0;
    }

    LOG_DEBUG(LOG, "getData returns %d", res);
    return res;
}

int ZkFuseHandleManager::setData(const std::string & path, 
                                 const Data & data, 
                                 bool exists, 
                                 bool doFlush) 
{
    LOG_DEBUG(LOG, "setData(path %s, exists %d)\n%s", 
              path.c_str(), exists, data.c_str());

    int res = 0;
    if (exists) {
        res = open(path, false);
    } else {
        bool created;
        res = mknod(path, S_IFREG, true, created);
    }
    if (res >= 0) {
        ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), res);
        res = autoHandle.getFile()->setData(data, doFlush);
    }

    LOG_DEBUG(LOG, "setData returns %d", res);
    return res;
}

int ZkFuseHandleManager::mknod(const std::string & path, 
                               mode_t mode, 
                               bool mayExist,
                               bool & created)
{
    LOG_DEBUG(LOG, "mknod(path %s, mode %o, mayExist %d)", 
              path.c_str(), mode, mayExist);

    int res = 0;
    created = false;
    try {
        if (S_ISREG(mode) == false && S_ISDIR(mode) == false) {
            LOG_DEBUG(LOG, "bad mode %o", mode);
            res = -EINVAL;
        } 
        else {
            Data data;
            LOG_DEBUG(LOG, "create %s", path.c_str());
            created = 
                _common.getZkAdapter()->createNode(path, data, 0, false);
            if (created) {
                LOG_DEBUG(LOG, "created");
                if (S_ISDIR(mode)) {
                    /* is mkdir - create directory marker */
                    std::string dirMetaPath = ZkFuseFile::buildChildPath
                        (path, _common.getDirMetadataName());
                    LOG_DEBUG(LOG, "create %s", dirMetaPath.c_str());
                    bool created;
                    int metaRes = mknod(dirMetaPath, S_IFREG, true, created);
                    if (metaRes >= 0) {
                        getFile(metaRes)->close();
                    }
                }
                addChildToParent(path);
                LOG_DEBUG(LOG, "open after create");
                res = open(path, true);
            } else {
                LOG_DEBUG(LOG, "create failed");
                int openRes = open(path, false);
                if (openRes >= 0) {
                    if (mayExist == false) {
                        LOG_DEBUG(LOG, "create failed because already exist");
                        getFile(openRes)->close();
                        res = -EEXIST;
                    } else {
                        res = openRes;
                    }
                } else {
                    LOG_DEBUG(LOG, "create failed but does not exist");
                    res = -ENOENT;
                }
            }
        }
    } catch (const ZooKeeperException & e) {
        LOG_ERROR(LOG, "mknod %s exception %s", path.c_str(), e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "mknod returns %d created %d", res, created);
    return res;
}

int ZkFuseHandleManager::mkdir(const char * path, mode_t mode)
{
    LOG_DEBUG(LOG, "mkdir(path %s, mode %o)", path, mode);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = getZkPath(path, nameType);
        mode = (mode & ~S_IFMT) | S_IFDIR;
        ZkFuseAutoHandle autoHandle
            (SharedPtr(_thisWeakPtr), zkPath, mode, false);
        res = autoHandle.get();
        if (res >= 0) {
            res = 0;
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "mkdir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "mkdir returns %d", res);
    return res;
}

int ZkFuseHandleManager::open(const std::string & path, bool justCreated)
{
    LOG_DEBUG(LOG, "open(path %s, justCreated %d)", 
              path.c_str(), justCreated);

    int res = 0;
    try {
        bool newFile;
        Handle handle = allocate(path, newFile);
        ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), handle);
        res = getFile(handle)->update(newFile || justCreated);
        if (res == 0) {
            res = handle;
            autoHandle.release();
        }
    } catch (const ZooKeeperException & e) {
        LOG_ERROR(LOG, "open %s exception %s", path.c_str(), e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "open returns %d", res);
    return res;
}

int ZkFuseHandleManager::rmdir(const char * path, bool force)
{
    LOG_DEBUG(LOG, "rmdir(path %s, force %d)", path, force);

    int res = 0;

    try {
        ZkFuseNameType nameType;
        std::string zkPath = getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), zkPath);
        res = autoHandle.get();
        if (res >= 0) {
            res = autoHandle.getFile()->rmdir(nameType, force);
        } 
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "rmdir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "rmdir returns %d", res);
    return res;
}


int 
ZkFuseHandleManager::unlink(const char * path)
{
    LOG_DEBUG(LOG, "unlink(path %s)", path);

    ZkFuseNameType nameType;
    std::string zkPath = getZkPath(path, nameType);
    ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), zkPath);
    int res = autoHandle.get();
    if (res >= 0) {
        res = autoHandle.getFile()->unlink(nameType);
    }

    LOG_DEBUG(LOG, "unlink returns %d", res);
    return res;
}

int ZkFuseHandleManager::getattr(const char *path, struct stat &stbuf)
{
    LOG_DEBUG(LOG, "getattr(path %s)", path);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(SharedPtr(_thisWeakPtr), zkPath);
        res = autoHandle.get();
        if (res >= 0) {
            res = autoHandle.getFile()->getattr(stbuf, nameType);
        } 
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "getattr %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "getattr returns %d", res);
    return res;
}

int 
ZkFuseHandleManager::rename(const char * fromPath, const char * toPath)
{
    LOG_DEBUG(LOG, "rename(fromPath %s, toPath %s)", fromPath, toPath);

    ZkFuseNameType fromNameType;
    std::string fromZkPath = getZkPath(fromPath, fromNameType);
    ZkFuseAutoHandle fromAutoHandle(SharedPtr(_thisWeakPtr), fromZkPath);
    int res = fromAutoHandle.get();
    if (res >= 0) {
        LOG_DEBUG(LOG, "good fromPath");
        if (fromAutoHandle.getFile()->isDirNameType(fromNameType)) {
            LOG_DEBUG(LOG, "fromPath is directory");
            res = -EISDIR;
        }
    }
    if (res >= 0) {
        ZkFuseNameType toNameType;
        std::string toZkPath = getZkPath(toPath, toNameType);
        bool created;
        res = mknod(toZkPath.c_str(), S_IFREG, true, created);
        if (res >= 0) {
            ZkFuseAutoHandle toAutoHandle(SharedPtr(_thisWeakPtr), res);
            if (toAutoHandle.getFile()->isDirNameType(toNameType)) {
                LOG_DEBUG(LOG, "toPath is directory");
                res = -EISDIR;
            }
            if (res >= 0) {
                LOG_DEBUG(LOG, "copy data");
                Data data; 
                fromAutoHandle.getFile()->getData(data);
                toAutoHandle.getFile()->setData(data, true);
                LOG_DEBUG(LOG, "copy metadata");
                struct stat stbuf;
                int metaRes = 
                    fromAutoHandle.getFile()->getattr(stbuf, fromNameType);
                if (metaRes < 0) {
                    LOG_DEBUG(LOG, "get metadata failed");
                } 
                else {
                    metaRes = toAutoHandle.getFile()->
                        utime(secsToMillisecs(stbuf.st_atime),
                              secsToMillisecs(stbuf.st_mtime),
                              toNameType);
                    if (metaRes < 0) {
                        LOG_DEBUG(LOG, "set metadata failed");
                    }
                }
            }
            if (created && res < 0) {
                LOG_DEBUG(LOG, "undo create because copy data failed");
                int rmRes = toAutoHandle.getFile()->rmdir(toNameType, true);
            }
        }
    }
    if (res >= 0) {
        LOG_DEBUG(LOG, "copy successful, unlink fromPath");
        res = fromAutoHandle.getFile()->unlink(fromNameType);
    }

    LOG_DEBUG(LOG, "rename returns %d", res);
    return res;
}

void
ZkFuseHandleManager::addChildToParent(const std::string & childPath) const
{
    LOG_DEBUG(LOG, "addChildToParent(childPath %s)", childPath.c_str());

    std::string parentPath = getParentPath(childPath);
    if (!parentPath.empty()) {
        AutoLock lock(_mutex);
        Map::const_iterator it = _map.find(parentPath);
        if (it != _map.end()) {
            Handle handle = it->second;
            assert(_files[handle] != NULL);
            _files[handle]->addChild(childPath);
        } 
    }
    
    LOG_DEBUG(LOG, "addChildToParent done");
}

void
ZkFuseHandleManager::removeChildFromParent(const std::string & childPath) const
{
    LOG_DEBUG(LOG, "removeChildFromParent(childPath %s)", childPath.c_str());

    std::string parentPath = getParentPath(childPath);
    if (!parentPath.empty()) {
        AutoLock lock(_mutex);
        Map::const_iterator it = _map.find(parentPath);
        if (it != _map.end()) {
            Handle handle = it->second;
            assert(_files[handle] != NULL);
            _files[handle]->removeChild(childPath);
        } 
    }
    
    LOG_DEBUG(LOG, "removeChildFromParent done");
}

std::string
ZkFuseHandleManager::getParentPath(const std::string & childPath) const
{
    std::string::size_type lastPos = childPath.rfind('/');
    if (lastPos > 0) {
        return std::string(childPath, 0, lastPos);
    }
    else {
        assert(childPath[0] == '/');
        return std::string();
    }
}

std::string 
ZkFuseHandleManager::getZkPath(const char * path, ZkFuseNameType & nameType)
    const
{
    LOG_DEBUG(LOG, "getZkPath(path %s)", path);

    std::string res;
    unsigned pathLen = strlen(path);
    const std::string & dataFileName = _common.getDataFileName();
    unsigned dataSuffixLen = dataFileName.length();
    const char * dataSuffix = dataFileName.c_str();
    unsigned dataSuffixIncludeSlashLen = dataSuffixLen + 1;
    const std::string & forceDirSuffix = _common.getForceDirSuffix();
    unsigned forceDirSuffixLen = _common.getForceDirSuffix().length();
    /* Check if path is "/". If so, it is always a directory.
     */
    if (pathLen == 1) {
        assert(path[0] == '/');
        res = _common.getRootPathName();
        nameType = ZkFuseNameDirType;
    }
    /* Check if path ends of /{dataSuffix}, e.g. /foo/bar/{dataSuffix}.
     * If so remove dataSuffix and nameType is ZkFuseNameRegType. 
     */
    else if (
        (pathLen >= dataSuffixIncludeSlashLen) && 
        (path[pathLen - dataSuffixIncludeSlashLen] == '/') &&
        (strncmp(path + (pathLen - dataSuffixLen), 
                 dataSuffix, dataSuffixLen) == 0) 
       ) {
        if ((pathLen - dataSuffixIncludeSlashLen) == 0) {
            res = _common.getRootPathName();
        } else { 
            res.assign(path, pathLen - dataSuffixIncludeSlashLen);
        }
        nameType = ZkFuseNameRegType;
    }
    /* If not ZkFuseNameRegType, then check if path ends of 
     * {forceDirSuffix}, e.g. /foo/bar{forceDirSuffix}.
     * If so remove forceDirSuffix and nameType is ZkFuseNameDirType.
     */
    else if (forceDirSuffixLen > 0 &&
        pathLen >= forceDirSuffixLen &&
        strncmp(path + (pathLen - forceDirSuffixLen),
                forceDirSuffix.c_str(), forceDirSuffixLen) == 0) {
        res.assign(path, pathLen - forceDirSuffixLen);
        nameType = ZkFuseNameDirType;
    } 
    /* If not ZkFuseNameRegType and not ZkFuseNameDirType, then
     * it is ZkFuseNameDefaultType. ZkFuse will infer type from
     * ZooKeeper node's content.
     */
    else {
        res = path;
        nameType = ZkFuseNameDefaultType;
    }
    /* Intermediate components of the path name may have 
     * forceDirSuffix, e.g. /foo/bar{forceDirSuffix}/baz.
     * If so, remove the intermediate {forceDirSuffix}es.
     */
    if (forceDirSuffixLen > 0) {
        /* pos is an optimization to avoid always scanning from 
         * beginning of path
         */
        unsigned pos = 0;
        while ((res.length() - pos) > forceDirSuffixLen + 1) {
            const char * found = 
                strstr(res.c_str() + pos, forceDirSuffix.c_str());
            if (found == NULL) {
                break;
            } 
            if (found[forceDirSuffixLen] == '/' ||
                found[forceDirSuffixLen] == '\0') {
                pos = found - res.c_str();
                res.erase(pos, forceDirSuffixLen);
            }
            else {
                pos += forceDirSuffixLen;
            }
        }
    }

    LOG_DEBUG(LOG, "getZkPath returns %s, nameType %d", 
              res.c_str(), int(nameType));
    return res;
}

static ZkFuseHandleManager::SharedPtr singletonZkFuseHandleManager;

inline const ZkFuseHandleManager::SharedPtr & zkFuseHandleManager()
{
    return singletonZkFuseHandleManager;
}

static 
int zkfuse_getattr(const char *path, struct stat *stbuf)
{
    LOG_DEBUG(LOG, "zkfuse_getattr(path %s)", path);

    int res = 0;
    try {
        res = zkFuseHandleManager()->getattr(path, *stbuf);
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_getattr %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_getattr returns %d", res);
    return res;
}

static 
int zkfuse_fgetattr(const char *path, struct stat *stbuf,
	            struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_fgetattr(path %s)", path);

    int res = 0;
    int handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            res = zkFuseHandleManager()->getFile(handle)->
                getattr(*stbuf, ZkFuseNameDefaultType);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_fgetattr %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_fgetattr returns %d", res);
    return res;
}

static 
int zkfuse_access(const char *path, int mask)
{
    /* not implemented */
    return -1;
}

static 
int zkfuse_readlink(const char *path, char *buf, size_t size)
{
    /* not implemented */
    return -1;
}

static 
int zkfuse_opendir(const char *path, struct fuse_file_info *fi)
{ 
    LOG_DEBUG(LOG, "zkfuse_opendir(path %s)", path);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = zkFuseHandleManager()->getZkPath(path, nameType);
        if (nameType == ZkFuseNameRegType) {
            res = -ENOENT;
        }
        else {
            ZkFuseAutoHandle autoHandle(zkFuseHandleManager(), zkPath);
            res = autoHandle.get();
            if (res >= 0) {
                autoHandle.getFile()->incOpenDirCount();
                autoHandle.release();
                fi->fh = res;
                res = 0;
            }
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_opendir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_opendir returns %d", res);
    return res;
}

static int 
zkfuse_readdir(const char *path, void *buf, fuse_fill_dir_t filler, 
               off_t offset, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_readdir(path %s, offset %zu)", path, offset);

    int res = 0;
    int handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            res = zkFuseHandleManager()->getFile(handle)->
                readdir(buf, filler, offset);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_readdir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_readdir returns %d", res);
    return res;
}

static 
int zkfuse_releasedir(const char *path, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_releasedir(path %s)", path);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            zkFuseHandleManager()->getFile(handle)->decOpenDirCount();
            zkFuseHandleManager()->getFile(handle)->close();
        } 
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_releasedir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_releasedir returns %d", res);
    return res;
}

static 
int zkfuse_mknod(const char *path, mode_t mode, dev_t rdev)
{
    LOG_DEBUG(LOG, "zkfuse_mknod(path %s, mode %o)", path, mode);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = zkFuseHandleManager()->getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(zkFuseHandleManager(), zkPath, mode, false);
        res = autoHandle.get();
        if (res >= 0) {
            res = 0;
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_mknod %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_mknod returns %d", res);
    return res;
}

static int zkfuse_mkdir(const char *path, mode_t mode)
{
    LOG_DEBUG(LOG, "zkfuse_mkdir(path %s, mode %o", path, mode);

    int res = 0;
    try {
        res = zkFuseHandleManager()->mkdir(path, mode);
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_mkdir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_mkdir returns %d", res);
    return res;
}

static int zkfuse_unlink(const char *path)
{
    LOG_DEBUG(LOG, "zkfuse_unlink(path %s)", path);

    int res = 0;
    try {
        res = zkFuseHandleManager()->unlink(path);
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_unlink %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_unlink returns %d", res);
    return res;
}

static int zkfuse_rmdir(const char *path)
{
    LOG_DEBUG(LOG, "zkfuse_rmdir(path %s)", path);

    int res = 0;
    try {
        res = zkFuseHandleManager()->rmdir(path);
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_rmdir %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_rmdir returns %d", res);

    return res;
}

static int zkfuse_symlink(const char *from, const char *to)
{
    /* not implemented */
    return -1;
}

static int zkfuse_rename(const char *from, const char *to)
{
    LOG_DEBUG(LOG, "zkfuse_rename(from %s, to %s)", from, to);

    int res = 0;
    try {
        res = zkFuseHandleManager()->rename(from, to);
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_rename %s %s exception %s", from, to, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_rename returns %d", res);

    return res;
}

static int zkfuse_link(const char *from, const char *to)
{
    /* not implemented */
    return -1;
}

static int zkfuse_chmod(const char *path, mode_t mode)
{
    LOG_DEBUG(LOG, "zkfuse_chmod(path %s, mode %o)", path, mode);
    int res = 0;

    LOG_DEBUG(LOG, "zkfuse_chmod returns %d", res);
    return res;
}

static int zkfuse_chown(const char *path, uid_t uid, gid_t gid)
{
    LOG_DEBUG(LOG, "zkfuse_chown(path %s, uid %d, gid %d)", path, uid, gid);

    int res = 0;

    if (zkFuseHandleManager()->getCommon().getUid() == uid &&
        zkFuseHandleManager()->getCommon().getGid() == gid) {
        res = 0;
    }
    else {
        res = -EPERM;
    }

    LOG_DEBUG(LOG, "zkfuse_chown returns %d", res);
    return 0;
}

static int zkfuse_truncate(const char *path, off_t size)
{
    LOG_DEBUG(LOG, "zkfuse_truncate(path %s, size %zu)", path, size);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = zkFuseHandleManager()->getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(zkFuseHandleManager(), zkPath);
        res = autoHandle.get();
        if (res >= 0) {
            res = autoHandle.getFile()->truncate(size);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_truncate %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_truncate returns %d", res);
    return res;
}

static 
int zkfuse_ftruncate(const char *path, off_t size, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_ftruncate(path %s, size %zu)", path, size);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            res = zkFuseHandleManager()->getFile(handle)->truncate(size);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_ftruncate %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_ftruncate returns %d", res);
    return res;
}

static 
int zkfuse_utimens(const char *path, const struct timespec ts[2])
{
    LOG_DEBUG(LOG, "zkfuse_utimens(path %s)", path);

    int res = 0;
    try {
        uint64_t atime = timespecToMillisecs(ts[0]);
        uint64_t mtime = timespecToMillisecs(ts[1]);
        ZkFuseNameType nameType;
        std::string zkPath = zkFuseHandleManager()->getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(zkFuseHandleManager(), zkPath);
        res = autoHandle.get();
        if (res >= 0) {
            res = autoHandle.getFile()->utime(atime, mtime, nameType);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_utimens %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_utimens returns %d", res);
    return res;
}

static 
int zkfuse_create(const char *path, mode_t mode, struct fuse_file_info *fi)
{
	int fd;

	fd = open(path, fi->flags, mode);
	if (fd == -1)
		return -errno;

	fi->fh = fd;
	return 0;
}

static 
int zkfuse_open(const char *path, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_open(path %s, flags %o)", path, fi->flags);

    int res = 0;
    try {
        ZkFuseNameType nameType;
        std::string zkPath = zkFuseHandleManager()->getZkPath(path, nameType);
        ZkFuseAutoHandle autoHandle(zkFuseHandleManager(), zkPath);
        res = autoHandle.get();
        if (res >= 0) {
            if (autoHandle.getFile()->isDirNameType(nameType)) {
                res = -ENOENT;
            }
        } 
        if (res >= 0) {
            autoHandle.release();
            fi->fh = res;
            res = 0;
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_open %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_open returns %d", res);
    return res;
}

static 
int zkfuse_read(const char *path, char *buf, size_t size, off_t offset,
		struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_read(path %s, size %zu, offset %zu)", 
              path, size, offset);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            res = zkFuseHandleManager()->getFile(handle)-> 
                read(buf, size, offset);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_read %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_read returns %d", res);
    return res;
}

static 
int zkfuse_write(const char *path, const char *buf, size_t size,
                 off_t offset, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_write(path %s, size %zu, offset %zu)", 
              path, size, offset);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        } 
        else {
            res = zkFuseHandleManager()->getFile(handle)-> 
                write(buf, size, offset);
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_write %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_write returns %d", res);
    return res;
}

static int zkfuse_statfs(const char *path, struct statvfs *stbuf)
{
    /* not implemented */
    return -1;
}

static 
int zkfuse_flush(const char *path, struct fuse_file_info *fi)
{
    /* This is called from every close on an open file, so call the 
       close on the underlying filesystem. But since flush may be
       called multiple times for an open file, this must not really
       close the file.  This is important if used on a network 
       filesystem like NFS which flush the data/metadata on close() */

    LOG_DEBUG(LOG, "zkfuse_flush(path %s)", path);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            res = zkFuseHandleManager()->getFile(handle)->flush();
        }
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_flush %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_flush returns %d", res);
    return res;
}

static 
int zkfuse_release(const char *path, struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_release(path %s)", path);

    int res = 0;
    unsigned handle = fi->fh;
    try {
        if (handle <= 0) {
            res = -EINVAL;
        }
        else {
            zkFuseHandleManager()->getFile(handle)->close();
        } 
    } catch (const std::exception & e) {
        LOG_ERROR(LOG, "zkfuse_release %s exception %s", path, e.what());
        res = -EIO;
    }

    LOG_DEBUG(LOG, "zkfuse_release returns %d", res);
    return res;
}

static 
int zkfuse_fsync(const char *path, int isdatasync, 
                 struct fuse_file_info *fi)
{
    LOG_DEBUG(LOG, "zkfuse_fsync(path %s, isdatasync %d)", path, isdatasync);

    (void) isdatasync;
    int res = zkfuse_flush(path, fi);

    LOG_DEBUG(LOG, "zkfuse_fsync returns %d", res);
    return res;
}

#ifdef HAVE_SETXATTR
/* xattr operations are optional and can safely be left unimplemented */
static int zkfuse_setxattr(const char *path, const char *name, const char *value,
			size_t size, int flags)
{
	int res = lsetxattr(path, name, value, size, flags);
	if (res == -1)
		return -errno;
	return 0;
}

static int zkfuse_getxattr(const char *path, const char *name, char *value,
			size_t size)
{
	int res = lgetxattr(path, name, value, size);
	if (res == -1)
		return -errno;
	return res;
}

static int zkfuse_listxattr(const char *path, char *list, size_t size)
{
	int res = llistxattr(path, list, size);
	if (res == -1)
		return -errno;
	return res;
}

static int zkfuse_removexattr(const char *path, const char *name)
{
	int res = lremovexattr(path, name);
	if (res == -1)
		return -errno;
	return 0;
}
#endif /* HAVE_SETXATTR */

static 
int zkfuse_lock(const char *path, struct fuse_file_info *fi, int cmd,
                struct flock *lock)
{ 
    (void) path;
    return ulockmgr_op(fi->fh, cmd, lock, &fi->lock_owner,
		       sizeof(fi->lock_owner));
}


static 
void init_zkfuse_oper(fuse_operations & fo)
{
        memset(&fo, 0, sizeof(fuse_operations));
	fo.getattr = zkfuse_getattr;
	fo.fgetattr = zkfuse_fgetattr;
	// fo.access = zkfuse_access;
	// fo.readlink = zkfuse_readlink;
	fo.opendir = zkfuse_opendir;
	fo.readdir = zkfuse_readdir;
	fo.releasedir = zkfuse_releasedir;
	fo.mknod = zkfuse_mknod;
	fo.mkdir = zkfuse_mkdir;
	// fo.symlink = zkfuse_symlink;
	fo.unlink = zkfuse_unlink;
	fo.rmdir = zkfuse_rmdir;
	fo.rename = zkfuse_rename;
	// fo.link = zkfuse_link;
	fo.chmod = zkfuse_chmod;
	fo.chown = zkfuse_chown;
	fo.truncate = zkfuse_truncate;
	fo.ftruncate = zkfuse_ftruncate;
	fo.utimens = zkfuse_utimens;
	// fo.create = zkfuse_create;
	fo.open = zkfuse_open;
	fo.read = zkfuse_read;
	fo.write = zkfuse_write;
	fo.statfs = zkfuse_statfs;
	fo.flush = zkfuse_flush;
	fo.release = zkfuse_release;
	fo.fsync = zkfuse_fsync;
#ifdef HAVE_SETXATTR
	// fo.setxattr = zkfuse_setxattr;
	// fo.getxattr = zkfuse_getxattr;
	// fo.listxattr = zkfuse_listxattr;
	// fo.removexattr = zkfuse_removexattr;
#endif
	fo.lock = zkfuse_lock;
};


/**
 * The listener of ZK events.
 */
class SessionEventListener : public ZKEventListener 
{
  private:
    /** 
      References the ZkFuseHandleManager instance that should be
      invoked to service events.
     */
    ZkFuseHandleManager::SharedPtr _manager;

  public:
    /**
      Sets the ZkFuseHandleManager instance that should be invoked
      to service events.
     */
    void setManager(const ZkFuseHandleManager::SharedPtr & manager) 
    {
        _manager = manager;
    }
    /**
      Received an event and invoke ZkFuseHandleManager instance to handle
      received event.
     */
    virtual void eventReceived(const ZKEventSource & source,
                               const ZKWatcherEvent & event)
    {
        _manager->eventReceived(event);
    }
};

void 
usage(int argc, char *argv[])
{
    cout 
        << argv[0] 
        << " usage: " 
        << argv[0] 
        << " [args-and-values]+" << endl
        << "nodepath == a complete path to a ZooKeeper node" << endl
        << "\t--cachesize=<cachesize> or -c <cachesize>:" << endl
        << "    number of ZooKeeper nodes to cache." << endl
        << "\t--debug or -d: " << endl
        << "\t  enable fuse debug mode." << endl
        << "\t--help or -h: " << endl
        << "\t  print this message." << endl
        << "\t--mount=<mountpoint> or -m <mountpoint>: " << endl
        << "\t  specifies where to mount the zkfuse filesystem." << endl
        << "\t--name or -n: " << endl
        << "\t  name of file for accessing node data." << endl
        << "\t--zookeeper=<hostspec> or -z <hostspec>: " << endl
        << "\t  specifies information needed to connect to zeekeeper." << endl;
}

int 
main(int argc, char *argv[])
{
    /**
     * Initialize log4cxx 
     */
    const std::string file("log4cxx.properties");
    PropertyConfigurator::configureAndWatch( file, 5000 );
    LOG_INFO(LOG, "Starting zkfuse");

    /**
     * Supported operations.
     */
    enum ZkOption {
        ZkOptionCacheSize = 1000,
        ZkOptionDebug = 1001,
        ZkOptionForceDirSuffix = 1002,
        ZkOptionHelp = 1003,
        ZkOptionMount = 1004,
        ZkOptionName = 1005,
        ZkOptionZookeeper = 1006,
        ZkOptionInvalid = -1
    };
    
    static const char *shortOptions = "c:df:hm:n:z:";
    static struct option longOptions[] = {
        { "cachesize", 1, 0, ZkOptionCacheSize },
        { "debug", 0, 0, ZkOptionDebug },
        { "forcedirsuffix", 1, 0, ZkOptionForceDirSuffix },
        { "help", 0, 0, ZkOptionHelp },
        { "mount", 1, 0, ZkOptionMount },
        { "name", 1, 0, ZkOptionName },
        { "zookeeper", 1, 0, ZkOptionZookeeper },
        { 0, 0, 0, 0 }
    };
    
    /**
     * Parse arguments 
     */
    bool debugFlag = false;
    std::string mountPoint = "/tmp/zkfuse";
    std::string nameOfFile = "_data_";
    std::string forceDirSuffix = "._dir_";
    std::string zkHost;
    unsigned cacheSize = 256;

    while (true) {
        int c;

        c = getopt_long(argc, argv, shortOptions, longOptions, 0);
        if (c == -1) {
            break;
        }

        switch (c) {
          case ZkOptionInvalid:
            cerr 
                << argv[0]
                << ": ERROR: Did not specify legal argument!"
                << endl;
            return 99;
          case 'c':
          case ZkOptionCacheSize:
            cacheSize = strtoul(optarg, NULL, 0);
            break;
          case 'd':
          case ZkOptionDebug:
            debugFlag = true;
            break;
          case 'f':
          case ZkOptionForceDirSuffix:
            forceDirSuffix = optarg;
            break;
          case 'h':
          case ZkOptionHelp: 
            usage(argc, argv);
            return 0;
          case 'm':
          case ZkOptionMount:
            mountPoint = optarg;
            break;
          case 'n':
          case ZkOptionName:
            nameOfFile = optarg;
            break;
          case 'z':
          case ZkOptionZookeeper:
            zkHost = optarg;
            break;
        }
    }

    /**
     * Check that zkHost has a value, otherwise abort.
     */
    if (zkHost.empty()) {
        cerr 
            << argv[0] 
            << ": ERROR: " 
            << "required argument \"--zookeeper <hostspec>\" was not given!"
            << endl;
        return 99;
    }
    /**
     * Check that zkHost has a value, otherwise abort.
     */
    if (forceDirSuffix.empty()) {
        cerr 
            << argv[0] 
            << ": ERROR: " 
            << "required argument \"--forcedirsuffix <suffix>\" " 
               "not cannot be empty!"
            << endl;
        return 99;
    }
    /**
     * Check nameOfFile has no forward slash
     */
    if (nameOfFile.find_first_of('/') != std::string::npos) {
        cerr 
            << argv[0] 
            << ": ERROR: " 
            << "'/' present in name which is not allowed"
            << endl;
        return 99;
    }

    if (debugFlag) {
        cout
            << "cacheSize = " 
            << cacheSize  
            << ", debug = "
            << debugFlag 
            << ", forceDirSuffix = \""
            << forceDirSuffix
            << "\", mount = \""
            << mountPoint
            << "\", name = \""
            << nameOfFile
            << "\", zookeeper = \""
            << zkHost
            << "\", optind = "
            << optind
            << ", argc = "
            << argc
            << ", current arg = \""
            << (optind >= argc ? "NULL" : argv[optind])
            << "\""
            << endl;
    }

    SessionEventListener listener;
    SynchronousEventAdapter<ZKWatcherEvent> eventAdapter;
    LOG_INFO(LOG, "Create ZK adapter");
    try {
        /**
         * Create an instance of ZK adapter.
         */
        std::string h(zkHost);
        ZooKeeperConfig config(h, 1000, true, 10000);
        ZkFuseCommon zkFuseCommon;
        ZooKeeperAdapterSharedPtr zkPtr(
            new ZooKeeperAdapter(
                config, 
                &listener,
                false
                )
            );
        zkFuseCommon.setZkAdapter(zkPtr);
        zkFuseCommon.setDataFileName(nameOfFile);
        zkFuseCommon.setForceDirSuffix(forceDirSuffix);
        zkFuseCommon.setCacheSize(cacheSize);
        singletonZkFuseHandleManager =
            ZkFuseHandleManagerFactory::create(zkFuseCommon);
        listener.setManager(singletonZkFuseHandleManager);
        zkPtr->reconnect();

    } catch (const ZooKeeperException & e) {
        cerr 
            << argv[0]
            << ": ERROR: ZookKeeperException caught: "
            << e.what() 
            << endl;
    } catch (std::exception & e) {
        cerr 
            << argv[0]
            << ": ERROR: std::exception caught: "
            << e.what() 
            << endl;
    }

#ifdef ZOOKEEPER_ROOT_CHILDREN_WATCH_BUG
    cerr << "ZOOKEEPER_ROOT_CHILDREN_WATCH_BUG enabled" << endl;
#endif 
    /**
     * Initialize fuse 
     */
    LOG_INFO(LOG, "Initialize fuse");
    umask(0); 
    fuse_operations zkfuse_oper; 
    init_zkfuse_oper(zkfuse_oper); 
    int fakeArgc = debugFlag ? 3 : 2;
    char * fakeArgv[] = {
        argv[0],
        strdup(mountPoint.c_str()),
        debugFlag ? strdup("-d") : NULL,
        NULL
    };
    int res = fuse_main(fakeArgc, fakeArgv, &zkfuse_oper, NULL);
    for (unsigned i = 1; i <= 2; i++) {
        if (fakeArgv[i] != NULL) {
            free(fakeArgv[i]);
        }
    }

    return res;
}
