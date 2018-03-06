/*
 * ServerCnxn.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./ServerCnxn.hh"

namespace efc {
namespace ezk {

sp<EObject> ServerCnxn::me = new EObject();

sp<ELogger> ServerCnxn::LOG = ELoggerManager::getLogger("ServerCnxn");

sp<EMap<int, EString*> > ServerCnxn::cmd2String = new EHashMap<int, EString*>();

EString ServerCnxn::ZOOKEEPER_4LW_COMMANDS_WHITELIST = "zookeeper.4lw.commands.whitelist";

sp<ESet<EString*> > ServerCnxn::whiteListedCommands = new EHashSet<EString*>();

boolean ServerCnxn::whiteListInitialized = false;

ESimpleLock ServerCnxn::glock;

int ServerCnxn::confCmd;// = ntohl(*(int*)"conf");
int ServerCnxn::consCmd;// = ntohl(*(int*)"cons");
int ServerCnxn::crstCmd;// = ntohl(*(int*)"crst");
int ServerCnxn::dumpCmd;// = ntohl(*(int*)"dump");
int ServerCnxn::enviCmd;// = ntohl(*(int*)"envi");
int ServerCnxn::ruokCmd;// = ntohl(*(int*)"ruok");
int ServerCnxn::srvrCmd;// = ntohl(*(int*)"srvr");
int ServerCnxn::srstCmd;// = ntohl(*(int*)"srst");
int ServerCnxn::statCmd;// = ntohl(*(int*)"stat");
int ServerCnxn::wchcCmd;// = ntohl(*(int*)"wchc");
int ServerCnxn::wchpCmd;// = ntohl(*(int*)"wchp");
int ServerCnxn::wchsCmd;// = ntohl(*(int*)"wchs");
int ServerCnxn::mntrCmd;// = ntohl(*(int*)"mntr");
int ServerCnxn::isroCmd;// = ntohl(*(int*)"isro");
int ServerCnxn::getTraceMaskCmd;// = ntohl(*(int*)"gtmk");
int ServerCnxn::setTraceMaskCmd;// = ntohl(*(int*)"stmk");

// specify all of the commands that are available
DEFINE_STATIC_INITZZ_BEGIN(ServerCnxn)
    ESystem::_initzz_();

	//@see: ubuntu gcc 4.8.1
	// statement-expressions are not allowed outside functions nor in template-argument lists
	confCmd = ntohl(*(int*)"conf");
	consCmd = ntohl(*(int*)"cons");
	crstCmd = ntohl(*(int*)"crst");
	dumpCmd = ntohl(*(int*)"dump");
	enviCmd = ntohl(*(int*)"envi");
	ruokCmd = ntohl(*(int*)"ruok");
	srvrCmd = ntohl(*(int*)"srvr");
	srstCmd = ntohl(*(int*)"srst");
	statCmd = ntohl(*(int*)"stat");
	wchcCmd = ntohl(*(int*)"wchc");
	wchpCmd = ntohl(*(int*)"wchp");
	wchsCmd = ntohl(*(int*)"wchs");
	mntrCmd = ntohl(*(int*)"mntr");
	isroCmd = ntohl(*(int*)"isro");
	getTraceMaskCmd = ntohl(*(int*)"gtmk");
	setTraceMaskCmd = ntohl(*(int*)"stmk");

	cmd2String->put(confCmd, new EString("conf"));
	cmd2String->put(consCmd, new EString("cons"));
	cmd2String->put(crstCmd, new EString("crst"));
	cmd2String->put(dumpCmd, new EString("dump"));
	cmd2String->put(enviCmd, new EString("envi"));
	cmd2String->put(ruokCmd, new EString("ruok"));
	cmd2String->put(srstCmd, new EString("srst"));
	cmd2String->put(srvrCmd, new EString("srvr"));
	cmd2String->put(statCmd, new EString("stat"));
	cmd2String->put(wchcCmd, new EString("wchc"));
	cmd2String->put(wchpCmd, new EString("wchp"));
	cmd2String->put(wchsCmd, new EString("wchs"));
	cmd2String->put(mntrCmd, new EString("mntr"));
	cmd2String->put(isroCmd, new EString("isro"));
	cmd2String->put(getTraceMaskCmd, new EString("gtmk"));
	cmd2String->put(setTraceMaskCmd, new EString("stmk"));
DEFINE_STATIC_INITZZ_END

ServerCnxn::~ServerCnxn() {
	//
}

} /* namespace ezk */
} /* namespace efc */
