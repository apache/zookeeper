/*
 * PathTrie.cpp
 *
 *  Created on: 2017-11-20
 *      Author: cxxjava@163.com
 */

#include "./PathTrie.hh"

namespace efc {
namespace ezk {

sp<ELogger> PathTrie::LOG = ELoggerManager::getLogger("PathTrie");


/**
 * a class that implements prefix matching for
 * components of a filesystem path. the trie
 * looks like a tree with edges mapping to
 * the component of a path.
 * example /ab/bc/cf would map to a trie
 *           /
 *        ab/
 *        (ab)
 *      bc/
 *       /
 *      (bc)
 *   cf/
 *   (cf)
 */

class TrieNode: public EObject {
public:
	boolean property;// = false;
	EHashMap<sp<EString>, sp<TrieNode> > children;
	sp<TrieNode> parent;// = null;
	EReentrantLock childrenLock;

	/**
	 * get the parent of this node
	 * @return the parent node
	 */
	sp<TrieNode> getParent() {
		return this->parent;
	}

	/**
	 * set the parent of this node
	 * @param parent the parent to set to
	 */
	void setParent(sp<TrieNode> parent) {
		this->parent = parent;
	}

	/**
	 * a property that is set
	 * for a node - making it
	 * special.
	 */
	void setProperty(boolean prop) {
		this->property = prop;
	}

	/** the property of this
	 * node
	 * @return the property for this
	 * node
	 */
	boolean getProperty() {
		return this->property;
	}

	/**
	 * add a child to the existing node
	 * @param childName the string name of the child
	 * @param node the node that is the child
	 */
	void addChild(const char* childName, sp<TrieNode> node) {
		sp<EString> cn = new EString(childName);
		SYNCBLOCK(&childrenLock) {
			if (children.containsKey(cn.get())) {
				return;
			}
			children.put(cn, node);
		}}
	}

	/**
	 * delete child from this node
	 * @param childName the string name of the child to
	 * be deleted
	 */
	void deleteChild(const char* childName) {
		EString key(childName);
		SYNCBLOCK(&childrenLock) {
			/* @see:
			if (!children.containsKey(&key)) {
				return;
			}
			sp<TrieNode> childNode = children.get(&key);
			*/
			sp<TrieNode> childNode = children.get(&key);
			if (childNode == null) {
				return;
			}
			// this is the only child node.
			if (children.keySet()->size() == 1) {
				childNode->setParent(null);
				children.remove(&key);
			}
			else {
				// their are more child nodes
				// so just reset property.
				childNode->setProperty(false);
			}
		}}
	}

	void clearChildren() {
		SYNCBLOCK(&childrenLock) {
			int count = children.keySet()->size();
			int i = 1;
			sp<EIterator<EMapEntry<sp<EString>, sp<TrieNode> > *> > iter = children.entrySet()->iterator();
			while (iter->hasNext()) {
				EMapEntry<sp<EString>, sp<TrieNode> >* me = iter->next();
				if (i == count) { // the last one.
					me->getValue()->setParent(null);
					iter->remove();
				} else {
					me->getValue()->setProperty(false);
				}
				i++;
			}
		}}
	}

	/**
	 * return the child of a node mapping
	 * to the input childname
	 * @param childName the name of the child
	 * @return the child of a node
	 */
	sp<TrieNode> getChild(const char* childName) {
		EString key(childName);
		SYNCBLOCK(&childrenLock) {
		   if (!children.containsKey(&key)) {
			   return null;
		   }
		   else {
			   return children.get(&key);
		   }
		}}
	}

	/**
	 * get the string representation
	 * for this node
	 */
	virtual EString toString() {
		EString sb;
		sb.append("Children of trienode: ");
		SYNCBLOCK(&childrenLock) {
			sp<EIterator<sp<EString> > > iter = children.keySet()->iterator();
			while (iter->hasNext()) {
				sp<EString> str = iter->next();
				sb.append(" ");
				sb.append((str != null) ? str->c_str() : "null");
			}
		}}
		return sb;
	}

	/**
	 * create a trienode with parent
	 * as parameter
	 * @param parent the parent of this trienode
	 */
	TrieNode(sp<TrieNode> parent): property(false), parent(null) {
		this->parent = parent;
	}
};

//=============================================================================

PathTrie::~PathTrie() {

}

PathTrie::PathTrie() {
	this->rootNode = new TrieNode(null);
}

void PathTrie::addPath(EString path) {
	EArrayList<EString*> pathComponents = EPattern::split("/", path.c_str(), 0);
	sp<TrieNode> parent = dynamic_pointer_cast<TrieNode>(rootNode);
	EString* part = null;
	if (pathComponents.size() <= 1) {
		EString msg("Invalid path ");
		msg += path;
		throw EIllegalArgumentException(__FILE__, __LINE__, msg.c_str());
	}
	for (int i=1; i<pathComponents.size(); i++) {
		part = pathComponents[i];
		if (parent->getChild(part->c_str()) == null) {
			parent->addChild(part->c_str(), new TrieNode(parent));
		}
		parent = parent->getChild(part->c_str());
	}
	parent->setProperty(true);
}

void PathTrie::deletePath(EString path) {
	EArrayList<EString*> pathComponents = EPattern::split("/", path.c_str(), 0);
	sp<TrieNode> parent = dynamic_pointer_cast<TrieNode>(rootNode);
	EString* part = null;
	if (pathComponents.size() <= 1) {
		EString msg("Invalid path ");
		msg += path;
		throw EIllegalArgumentException(__FILE__, __LINE__, msg.c_str());
	}
	for (int i=1; i<pathComponents.size(); i++) {
		part = pathComponents[i];
		if (parent->getChild(part->c_str()) == null) {
			//the path does not exist
			return;
		}
		parent = parent->getChild(part->c_str());
		LOG->info(parent->toString().c_str());
	}
	sp<TrieNode> realParent  = parent->getParent();
	realParent->deleteChild(part->c_str());
}

EString PathTrie::findMaxPrefix(EString path) {
	if (path.equals("/")) {
		return path;
	}
	EArrayList<EString*> pathComponents = EPattern::split("/", path.c_str(), 0);
	sp<TrieNode> parent = dynamic_pointer_cast<TrieNode>(rootNode);
	if (pathComponents.size() <= 1) {
		EString msg("Invalid path ");
		msg += path;
		throw EIllegalArgumentException(__FILE__, __LINE__, msg.c_str());
	}
	int i = 1;
	EString* part = null;
	EString sb;
	int lastindex = -1;
	EArrayList<EString*> components(false);
	while((i < pathComponents.size())) {
		if (parent->getChild(pathComponents[i]->c_str()) != null) {
			part = pathComponents[i];
			parent = parent->getChild(part->c_str());
			components.add(part);
			if (parent->getProperty()) {
				lastindex = i-1;
			}
		}
		else {
			break;
		}
		i++;
	}
	for (int j=0; j< (lastindex+1); j++) {
		sb.append("/");
		sb.append(components.getAt(j));
	}
	return sb;
}

void PathTrie::clear() {
	/* @see:
	for(String child : rootNode.getChildren()) {
		rootNode.deleteChild(child);
	}
	*/
	sp<TrieNode> parent = dynamic_pointer_cast<TrieNode>(rootNode);
	parent->clearChildren();
}

} /* namespace ezk */
} /* namespace efc */
