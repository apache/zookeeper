#include <cstdlib>
#include <new>

#include "MocksBase.h"
#include "LibCSymTable.h"

// *****************************************************************************
// Mock base
void* Mock::operator new(std::size_t s){
    void* p=malloc(s);
    if(!p)
        throw std::bad_alloc();
    return p;
}

void Mock::operator delete(void* p){
    LIBC_SYMBOLS.free(p);
}
