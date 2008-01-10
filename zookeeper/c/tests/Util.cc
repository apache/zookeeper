#include <zookeeper.h>
#include "Util.h"
#include "LibCSymTable.h"

#ifdef THREADED
#include <pthread.h>

struct Mutex::Impl{
    Impl(){
        LIBC_SYMBOLS.pthread_mutex_init(&mut_, 0);        
    }
    ~Impl(){
        LIBC_SYMBOLS.pthread_mutex_destroy(&mut_);        
    }
    pthread_mutex_t mut_;
};

Mutex::Mutex():impl_(new Impl) {}
Mutex::~Mutex() { delete impl_;}
void Mutex::acquire() {
    LIBC_SYMBOLS.pthread_mutex_lock(&impl_->mut_);
}
void Mutex::release() {
    LIBC_SYMBOLS.pthread_mutex_unlock(&impl_->mut_);
}
#endif

void millisleep(int ms){
    timespec ts;
    ts.tv_sec=ms/1000;
    ts.tv_nsec=(ms%1000)*1000000; // to nanoseconds
    nanosleep(&ts,0);
}

void activeWatcher(zhandle_t *zh, int type, int state, const char *path){
    if(zh==0 || zoo_get_context(zh)==0) return;
    WatcherAction* action=(WatcherAction*)zoo_get_context(zh);
        
    if(type==SESSION_EVENT && state==EXPIRED_SESSION_STATE)
        action->onSessionExpired(zh);
    // TODO: implement for the rest of the event types
}
