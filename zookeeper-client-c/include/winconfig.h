#ifndef WINCONFIG_H_
#define WINCONFIG_H_

/* Define to `__inline__' or `__inline' if that's what the C compiler
   calls it, or to nothing if 'inline' is not supported under any name.  */
#ifndef __cplusplus
#define inline __inline
#endif

#define __attribute__(x)
#define __func__ __FUNCTION__

#define ACL ZKACL /* Conflict with windows API */

#endif
