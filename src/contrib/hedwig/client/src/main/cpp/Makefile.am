ACLOCAL_AMFLAGS = -I m4

SUBDIRS = lib test

library_includedir=$(includedir)/hedwig-0.1/hedwig
library_include_HEADERS = inc/hedwig/callback.h inc/hedwig/client.h inc/hedwig/exceptions.h inc/hedwig/publish.h inc/hedwig/subscribe.h 

pkgconfigdir = $(libdir)/pkgconfig
nodist_pkgconfig_DATA = hedwig-0.1.pc

include aminclude.am

EXTRA_DIST = $(DX_CONFIG) doc/html
