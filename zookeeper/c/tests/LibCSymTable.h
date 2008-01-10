#ifndef LIBCSYMTABLE_H_
#define LIBCSYMTABLE_H_

#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <stddef.h>
#include <dlfcn.h>
#include <cassert>

#ifdef THREADED
#include <pthread.h>
#endif

#ifdef __CYGWIN__
#define RANDOM_RET_TYPE int
#define GETTIMEOFDAY_ARG2_TYPE void*
#else
#define RANDOM_RET_TYPE long int
#define GETTIMEOFDAY_ARG2_TYPE struct timezone*
#endif

#define DECLARE_SYM(ret,sym,sig) \
    typedef ret (*sym##_sig)sig; \
    static sym##_sig preload_##sym () { \
        static sym##_sig ptr=0;\
        if(!ptr){ void* h=getHandle(); ptr=(sym##_sig)dlsym(h,#sym); } \
        assert("Unable to load "#sym" from libc"&&ptr); \
        return ptr; \
    } \
    sym##_sig sym

#define LIBC_SYMBOLS LibCSymTable::instance()

//******************************************************************************
// preload original libc symbols
struct LibCSymTable
{
    DECLARE_SYM(hostent*,gethostbyname,(const char*));
    DECLARE_SYM(void*,calloc,(size_t, size_t));
    DECLARE_SYM(void*,realloc,(void*, size_t));
    DECLARE_SYM(void,free,(void*));
    DECLARE_SYM(RANDOM_RET_TYPE,random,(void));
    DECLARE_SYM(void,srandom,(unsigned long));
    DECLARE_SYM(int,printf,(const char*, ...));
    DECLARE_SYM(int,socket,(int,int,int));
    DECLARE_SYM(int,close,(int));
    DECLARE_SYM(int,getsockopt,(int,int,int,void*,socklen_t*));
    DECLARE_SYM(int,setsockopt,(int,int,int,const void*,socklen_t));
    DECLARE_SYM(int,fcntl,(int,int,...));
    DECLARE_SYM(int,connect,(int,const struct sockaddr*,socklen_t));
    DECLARE_SYM(ssize_t,send,(int,const void*,size_t,int));
    DECLARE_SYM(ssize_t,recv,(int,const void*,size_t,int));
    DECLARE_SYM(int,select,(int,fd_set*,fd_set*,fd_set*,struct timeval*));
    DECLARE_SYM(int,gettimeofday,(struct timeval*,GETTIMEOFDAY_ARG2_TYPE));
#ifdef THREADED
    DECLARE_SYM(int,pthread_create,(pthread_t *, const pthread_attr_t *,
                void *(*)(void *), void *));
    DECLARE_SYM(int,pthread_detach,(pthread_t));
    DECLARE_SYM(int,pthread_cond_broadcast,(pthread_cond_t *));
    DECLARE_SYM(int,pthread_cond_destroy,(pthread_cond_t *));
    DECLARE_SYM(int,pthread_cond_init,(pthread_cond_t *, const pthread_condattr_t *));
    DECLARE_SYM(int,pthread_cond_signal,(pthread_cond_t *));
    DECLARE_SYM(int,pthread_cond_timedwait,(pthread_cond_t *,
                    pthread_mutex_t *, const struct timespec *));
    DECLARE_SYM(int,pthread_cond_wait,(pthread_cond_t *, pthread_mutex_t *));
    DECLARE_SYM(int,pthread_join,(pthread_t, void **));
    DECLARE_SYM(int,pthread_mutex_destroy,(pthread_mutex_t *));
    DECLARE_SYM(int,pthread_mutex_init,(pthread_mutex_t *, const pthread_mutexattr_t *));
    DECLARE_SYM(int,pthread_mutex_lock,(pthread_mutex_t *));
    DECLARE_SYM(int,pthread_mutex_trylock,(pthread_mutex_t *));
    DECLARE_SYM(int,pthread_mutex_unlock,(pthread_mutex_t *));
#endif
    LibCSymTable();
    
    static void* getHandle();
    static LibCSymTable& instance();
};

#endif /*LIBCSYMTABLE_H_*/
