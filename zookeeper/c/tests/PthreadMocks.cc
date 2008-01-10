#include "PthreadMocks.h"
#include "LibCSymTable.h"
#include "Util.h"

MockPthreadsBase* MockPthreadsBase::mock_=0;

int pthread_cond_broadcast (pthread_cond_t *c){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_broadcast(c);
    return MockPthreadsBase::mock_->pthread_cond_broadcast(c);
}
int pthread_cond_destroy (pthread_cond_t *c){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_destroy(c);
    return MockPthreadsBase::mock_->pthread_cond_destroy(c);
}
int pthread_cond_init (pthread_cond_t *c, const pthread_condattr_t *a){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_init(c,a);
    return MockPthreadsBase::mock_->pthread_cond_init(c,a);
}
int pthread_cond_signal (pthread_cond_t *c){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_signal(c);
    return MockPthreadsBase::mock_->pthread_cond_signal(c);
}
int pthread_cond_timedwait (pthread_cond_t *c,
                pthread_mutex_t *m, const struct timespec *t){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_timedwait(c,m,t);
    return MockPthreadsBase::mock_->pthread_cond_timedwait(c,m,t);
}
int pthread_cond_wait (pthread_cond_t *c, pthread_mutex_t *m){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_cond_wait(c,m);
    return MockPthreadsBase::mock_->pthread_cond_wait(c,m);
}
int pthread_create (pthread_t *t, const pthread_attr_t *a,
            void *(*f)(void *), void *d){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_create(t,a,f,d);
    return MockPthreadsBase::mock_->pthread_create(t,a,f,d);
}
int pthread_detach(pthread_t t){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_detach(t);
    return MockPthreadsBase::mock_->pthread_detach(t);    
}
int pthread_join (pthread_t t, void **r){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_join(t,r);
    return MockPthreadsBase::mock_->pthread_join(t,r);
}
int pthread_mutex_destroy (pthread_mutex_t *m){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_mutex_destroy(m);
    return MockPthreadsBase::mock_->pthread_mutex_destroy(m);
}
int pthread_mutex_init (pthread_mutex_t *m, const pthread_mutexattr_t *a){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_mutex_init(m,a);
    return MockPthreadsBase::mock_->pthread_mutex_init(m,a);
}

DECLARE_WRAPPER(int,pthread_mutex_lock,(pthread_mutex_t *m)){
    if(!MockPthreadsBase::mock_)
        return CALL_REAL(pthread_mutex_lock,(m));
    return MockPthreadsBase::mock_->pthread_mutex_lock(m);
}

int pthread_mutex_trylock (pthread_mutex_t *m){
    if(!MockPthreadsBase::mock_)
        return LIBC_SYMBOLS.pthread_mutex_trylock(m);
    return MockPthreadsBase::mock_->pthread_mutex_trylock(m);
}

DECLARE_WRAPPER(int,pthread_mutex_unlock,(pthread_mutex_t *m)){
    if(!MockPthreadsBase::mock_)
        return CALL_REAL(pthread_mutex_unlock,(m));
    return MockPthreadsBase::mock_->pthread_mutex_unlock(m);
}

CheckedPthread::ThreadMap CheckedPthread::tmap_;
CheckedPthread::MutexMap CheckedPthread::mmap_;
CheckedPthread::CVMap CheckedPthread::cvmap_;
Mutex CheckedPthread::mx;
