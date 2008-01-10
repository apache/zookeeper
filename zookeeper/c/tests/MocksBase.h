#ifndef MOCKSBASE_H_
#define MOCKSBASE_H_

#include <cstddef>

// *****************************************************************************
// Mock base

class Mock
{
public:
    virtual ~Mock(){}

    static void* operator new(std::size_t s);
    static void operator delete(void* p);
};

#endif /*MOCKSBASE_H_*/
