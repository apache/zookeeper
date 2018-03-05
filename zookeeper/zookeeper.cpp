/*
 * zookeeper.cpp
 *
 *  Created on: 2017-11-24
 *      Author: cxxjava@163.com
 */

#include "es_main.h"
#include "Ezk.hh"

#define ZK_VERSION "version 3.4.11"

#define LOG(fmt,...) ESystem::out->printfln(fmt, ##__VA_ARGS__)

MAIN_IMPL(zookeeper) {
	/* Copyright information*/
	LOG("======================================");
	LOG("| Name: CxxZookeeper, " ZK_VERSION " |");
	LOG("| Author: cxxjava@163.com            |");
	LOG("| https://github.com/cxxjava         |");
	LOG("======================================");

	ESystem::init(argc, argv);
	ELoggerManager::init("log4e.properties");

	LOG("zookeeper running...");

	QuorumPeerMain::main(argc, argv);

	LOG("exit...");

	ESystem::exit(0);

	return 0;
}
