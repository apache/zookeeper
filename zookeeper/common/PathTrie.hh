/*
 * PathTrie.hh
 *
 *  Created on: 2017-11-20

 */

#ifndef PATHTRIE_HH_
#define PATHTRIE_HH_

#include "Efc.hh"
#include "ELog.hh"

namespace efc {
namespace ezk {

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

class PathTrie: public EObject {
public:
	virtual ~PathTrie();

	/**
	 * construct a new PathTrie with
	 * a root node of /
	 */
	PathTrie();

	/**
	 * add a path to the path trie
	 * @param path
	 */
	void addPath(EString path);

	/**
	 * delete a path from the trie
	 * @param path the path to be deleted
	 */
	void deletePath(EString path);

	/**
	 * return the largest prefix for the input path.
	 * @param path the input path
	 * @return the largest prefix for the input path.
	 */
	EString findMaxPrefix(EString path);

	/**
	 * clear all nodes
	 */
	void clear();

private:
	static sp<ELogger> LOG;

	/**
	 * the root node of PathTrie
	 */
	sp<EObject> rootNode ;
};

} /* namespace ezk */
} /* namespace efc */
#endif /* PATHTRIE_HH_ */
