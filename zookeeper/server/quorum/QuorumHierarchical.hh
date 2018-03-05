/*
 * QuorumHierarchical.hh
 *
 *  Created on: 2017-11-22
 *      Author: cxxjava@163.com
 */

#ifndef QuorumHierarchical_HH_
#define QuorumHierarchical_HH_

#include "Efc.hh"
#include "ELog.hh"
#include "./QuorumVerifier.hh"
#include "./ConfigException.hh"

namespace efc {
namespace ezk {

/**
 * This class implements a validator for hierarchical quorums. With this
 * construction, zookeeper servers are split into disjoint groups, and 
 * each server has a weight. We obtain a quorum if we get more than half
 * of the total weight of a group for a majority of groups.
 * 
 * The configuration of quorums uses two parameters: group and weight. 
 * Groups are sets of ZooKeeper servers, and we set a group by passing
 * a colon-separated list of server ids. It is also necessary to assign
 * weights to server. Here is an example of a configuration that creates
 * three groups and assigns a weight of 1 to each server:
 * 
 *  group.1=1:2:3
 *  group.2=4:5:6
 *  group.3=7:8:9
 *  
 *  weight.1=1
 *  weight.2=1
 *  weight.3=1
 *  weight.4=1
 *  weight.5=1
 *  weight.6=1
 *  weight.7=1
 *  weight.8=1
 *  weight.9=1
 * 
 * Note that it is still necessary to define peers using the server keyword.
 */

class QuorumHierarchical : public QuorumVerifier {
private:
	static sp<ELogger> LOG;// = LoggerFactory.getLogger(QuorumHierarchical.class);

protected:
	sp<EHashMap<llong, ELLong*> > serverWeight;
    sp<EHashMap<llong, ELLong*> > serverGroup;
    sp<EHashMap<llong, ELLong*> > groupWeight;
    
    int numGroups;
   
public:
    /**
     * This contructor requires the quorum configuration
     * to be declared in a separate file, and it takes the
     * file as an input parameter.
     */
    QuorumHierarchical(EString filename) THROWS(ConfigException) {
        this->serverWeight = new EHashMap<llong, ELLong*>();
        this->serverGroup = new EHashMap<llong, ELLong*>();
        this->groupWeight = new EHashMap<llong, ELLong*>();
        this->numGroups = 0;
        
        readConfigFile(filename);
    }
    
    /**
     * This constructor takes a set of properties. We use
     * it in the unit test for this feature.
     */
    
    QuorumHierarchical(EProperties& qp)
		THROWS(ConfigException) {
    	this->serverWeight = new EHashMap<llong, ELLong*>();
    	this->serverGroup = new EHashMap<llong, ELLong*>();
    	this->groupWeight = new EHashMap<llong, ELLong*>();
    	this->numGroups = 0;
        
        parse(qp);
        
        LOG->info(EString(serverWeight->size()) + ", " + serverGroup->size() + ", " + groupWeight->size());
    }
    
   /**
    *  This contructor takes the two hash maps needed to enable 
    *  validating quorums. We use it with QuorumPeerConfig. That is,
    *  we declare weights and groups in the server configuration
    *  file along with the other parameters.
    * @param numGroups
    * @param serverWeight
    * @param serverGroup
    */
    QuorumHierarchical(int numGroups,
    		sp<EHashMap<llong, ELLong*> > serverWeight,
    		sp<EHashMap<llong, ELLong*> > serverGroup)
    {
    	this->serverWeight = serverWeight;
    	this->serverGroup = serverGroup;
    	this->groupWeight = new EHashMap<llong, ELLong*>();
        
    	this->numGroups = numGroups;
        computeGroupWeight();   
    }
    
    
    /**
     * Returns the weight of a server.
     * 
     * @param id
     */
    virtual llong getWeight(llong id){
        return serverWeight->get(id)->value;
    }
    
private:

    /**
     * Reads a configration file. Called from the constructor
     * that takes a file as an input.
     */
    void readConfigFile(EString& filename)
    THROWS(ConfigException){
        EFile configFile(filename.c_str());

        LOG->info("Reading configuration from: " + filename);

        try {
            if (!configFile.exists()) {
                throw EIllegalArgumentException(__FILE__, __LINE__, (filename
                        + " file is missing").c_str());
            }
    
            EProperties cfg;
			cfg.load(filename.c_str());
    
            parse(cfg);
        } catch (EIOException& e) {
            throw ConfigException(__FILE__, __LINE__, ("Error processing " + filename).c_str(), &e);
        } catch (EIllegalArgumentException& e) {
            throw ConfigException(__FILE__, __LINE__, ("Error processing " + filename).c_str(), &e);
        }
        
    }
    
    
    /**
     * Parse properties if configuration given in a separate file.
     */
    void parse(EProperties& quorumProp) {
    	sp<EIterator<EMapEntry<sp<EString>, sp<EString> >*> > iter = quorumProp.entrySet()->iterator();
    	while (iter->hasNext()) {
    		EMapEntry<sp<EString>, sp<EString> >* me = iter->next();
    		sp<EString> key = me->getKey();
    		sp<EString> val = me->getValue();

    		if (key->startsWith("group")) {
				int dot = key->indexOf('.');
				llong gid = ELLong::parseLLong(key->substring(dot + 1).c_str());

				numGroups++;

				EArrayList<EString*> parts = EPattern::split(":", val->c_str(), 0);
				for (int i=0; i<parts.size(); i++) {
					EString* s = parts[i];
					llong sid = ELLong::parseLLong(s->c_str());
					serverGroup->put(sid, new ELLong(gid));
				}
			} else if (key->startsWith("weight")) {
				int dot = key->indexOf('.');
				llong sid = ELLong::parseLLong(key->substring(dot + 1).c_str());
				serverWeight->put(sid, new ELLong(ELLong::parseLLong(val->c_str())));
			}
    	}

        computeGroupWeight();
    }
    
    /**
     * This method pre-computes the weights of groups to speed up processing
     * when validating a given set. We compute the weights of groups in 
     * different places, so we have a separate method.
     */
    void computeGroupWeight() {
    	sp<EIterator<EMapEntry<llong, ELLong*>*> > iter = serverGroup->entrySet()->iterator();
    	while (iter->hasNext()) {
    		EMapEntry<llong, ELLong*>* me = iter->next();
            llong sid = me->getKey();
            ELLong* gid = me->getValue();
            if(!groupWeight->containsKey(gid->value))
            	groupWeight->put(gid->value, new ELLong(serverWeight->get(sid)->value));
            else {
                llong totalWeight = serverWeight->get(sid)->value + groupWeight->get(gid->value)->value;
                groupWeight->put(gid->value, new ELLong(totalWeight));
            } 
        }    
        
        /*
         * Do not consider groups with weight zero
         */
    	iter = groupWeight->entrySet()->iterator();
    	while (iter->hasNext()) {
			EMapEntry<llong, ELLong*>* me = iter->next();
			ELLong* weight = me->getValue();
            LOG->debug("Group weight: " + EString(weight->value));
            if(weight->value == 0L){
                numGroups--;
                LOG->debug(EString("One zero-weight group: ") + 1 + ", " + numGroups);
            }
        }
    }
    
public:
    /**
     * Verifies if a given set is a quorum.
     */
    virtual boolean containsQuorum(EHashSet<llong>* set) {
		EHashMap<llong, ELLong*> expansion;

		/*
		 * Adds up weights per group
		 */
		if (set->size() == 0)
			return false;
		else
			LOG->debug(EString("Set size: ") + set->size());


		sp<EIterator<llong> > iter = set->iterator();
		while (iter->hasNext()) {
			llong sid = iter->next();
			ELLong* gid = serverGroup->get(sid);
			if (!expansion.containsKey(gid->value))
				expansion.put(gid->value, new ELLong(serverWeight->get(sid)->value));
			else {
				llong totalWeight = serverWeight->get(sid)->value + expansion.get(gid->value)->value;
				expansion.put(gid->value, new ELLong(totalWeight));
			}
		}

		/*
		 * Check if all groups have majority
		 */
		int majGroupCounter = 0;
		sp<EIterator<EMapEntry<llong, ELLong*>*> > iter2 = expansion.entrySet()->iterator();
		while (iter2->hasNext()) {
			EMapEntry<llong, ELLong*>* entry = iter2->next();
			llong gid = entry->getKey();
			LOG->debug(EString("Group info: ") + entry->getValue()->value + ", " + gid + ", "
							+ groupWeight->get(gid)->value);
			if (entry->getValue()->value > (groupWeight->get(gid)->value / 2))
				majGroupCounter++;
		}

		LOG->debug(EString("Majority group counter: ") + majGroupCounter + ", "
						+ numGroups);
		if ((majGroupCounter > (numGroups / 2))) {
			LOG->debug(EString("Positive set size: ") + set->size());
			return true;
		} else {
			LOG->debug(EString("Negative set size: ") + set->size());
			return false;
		}
	}
};

} /* namespace ezk */
} /* namespace efc */
#endif /* QuorumHierarchical_HH_ */
