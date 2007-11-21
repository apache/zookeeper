cc = gcc -Wall -g -O2
ldflags = -g

JUTE_C=generated

all:    lib cli_mt cli_st

lib:	libzookeeper_st.so libzookeeper_mt.so

libzookeeper_st.so:	recordio.o zookeeper_st.o zookeeper.jute.o st_adaptor.o
	${cc} -g  -shared -o $@ $^

libzookeeper_mt.so:	recordio.o zookeeper_mt.o zookeeper.jute.o mt_adaptor.o
	${cc} -g -shared -o $@ $^

zookeeper_mt.o:	src/zookeeper.c Makefile include/*.h ${JUTE_C}/* src/*.h
	${cc} -DTHREADED ${ldflags} -I${JUTE_C} -Iinclude/ -o $@ -c $<

zookeeper_st.o:	src/zookeeper.c Makefile include/*.h ${JUTE_C}/*
	${cc} ${ldflags} -I${JUTE_C} -Iinclude/ -o $@ -c $<

zookeeper.jute.o: ${JUTE_C}/*
	${cc} ${ldflags} -I${JUTE_C} -Iinclude/ -o $@ -c $<

%.o:	src/%.c Makefile include/*.h
	${cc} ${ldflags} -I${JUTE_C} -Iinclude/ -c $<


cli_mt:	src/cli.c include/*.h Makefile ${JUTE_C}/* libzookeeper_mt.so
	${cc} -I${JUTE_C} -I include/ -L. -DTHREADED -lzookeeper_mt -lpthread $< -o cli_mt

cli_st:	src/cli.c include/*.h Makefile libzookeeper_st.so
	${cc} -g  -I${JUTE_C} -Iinclude/ -L. -lzookeeper_st $< -o cli_st

clean : 
	rm -rf *.o *.so cli_mt cli_st
