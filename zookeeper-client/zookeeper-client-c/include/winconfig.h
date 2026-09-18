#ifndef WINCONFIG_H_
#define WINCONFIG_H_

/* GCC-compatible attributes and C99 keywords are supported by MinGW. */
#ifdef _MSC_VER
#ifndef __cplusplus
#define inline __inline
#endif
#define __attribute__(x)
#define __func__ __FUNCTION__
#endif

#define ACL ZKACL /* Conflict with windows API */

#endif
