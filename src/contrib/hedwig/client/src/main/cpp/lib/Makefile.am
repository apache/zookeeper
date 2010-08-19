PROTODEF = ../../../../../protocol/src/main/protobuf/PubSubProtocol.proto

lib_LTLIBRARIES = libhedwig01.la
libhedwig01_la_SOURCES = protocol.cpp channel.cpp client.cpp util.cpp exceptions.cpp clientimpl.cpp publisherimpl.cpp subscriberimpl.cpp
libhedwig01_la_CPPFLAGS = -I../inc $(DEPS_CFLAGS)
libhedwig01_la_LIBADD = $(DEPS_LIBS) 
libhedwig01_la_LDFLAGS = -no-undefined

protocol.cpp: $(PROTODEF)
	protoc --cpp_out=. -I`dirname $(PROTODEF)` $(PROTODEF)
	mv PubSubProtocol.pb.cc protocol.cpp
	sed -i "s/PubSubProtocol.pb.h/hedwig\/protocol.h/" protocol.cpp
	mv PubSubProtocol.pb.h ../inc/hedwig/protocol.h

