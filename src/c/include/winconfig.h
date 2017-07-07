#ifndef WINCONFIG_H_
#define WINCONFIG_H_

/* Define to `__inline__' or `__inline' if that's what the C compiler
   calls it, or to nothing if 'inline' is not supported under any name.  */
#ifndef __cplusplus
#define inline __inline
#endif

#define __attribute__(x)
#define __func__ __FUNCTION__

#ifndef _WIN32_WINNT_NT4
#define _WIN32_WINNT_NT4 0x0400
#endif

#define NTDDI_VERSION _WIN32_WINNT_NT4
#define _WIN32_WINNT _WIN32_WINNT_NT4

#define _CRT_SECURE_NO_WARNINGS
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <Winsock2.h>
#include <winstdint.h>
#include <process.h>
#include <ws2tcpip.h>
#undef AF_INET6
#undef min
#undef max

#include <errno.h>

#define strtok_r strtok_s
#define localtime_r(a,b) localtime_s(b,a)
#define get_errno() errno=GetLastError()
#define random rand

/* After this version of MSVC, snprintf became a defined function,
   and so cannot be redefined, nor can #ifndef be used to guard it. */
#if ((defined(_MSC_VER) && _MSC_VER < 1900) || !defined(_MSC_VER))
#define snprintf _snprintf
#endif

#define ACL ZKACL  // Conflict with windows API

#define EAI_ADDRFAMILY WSAEINVAL
#define EHOSTDOWN EPIPE
#define ESTALE ENODEV

#ifndef EWOULDBLOCK
#define EWOULDBLOCK WSAEWOULDBLOCK
#endif

#ifndef EINPROGRESS
#define EINPROGRESS WSAEINPROGRESS
#endif

typedef int pid_t;
#endif
