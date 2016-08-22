/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef _COLLECTION_UTIL_H_
#define _COLLECTION_UTIL_H_

/**
 * \file
 * CollectionBuilder and DictionaryBuilder classes and collection utility functions
 */

namespace Util 
{

// *********************************************************
/** A shortcut to use for building collections.
 * This class is a wrapper around standard STL collection containers such as vector.
 * It allows one to conveniently build collections at the variable initialization time:
 * \code
 * #include "CollectionUtil.h"
 * #include "Vector.h"  // for ostream << operator overload for STL vector
 * using Util;
 * 
 * int main()
 * {
 *   typedef vector<string> MyVector;
 *   MyVector myVector=CollectionBuilder<MyVector>()("str1")("str2")("str3");
 *   cout<<myVector;
 *   // the following output will be produced:
 *   // [str1,str2,str3]
 * }
 * \endcode
 */
template <class CONT>
class CollectionBuilder
{
public:
  /// Type of the collection container. 
  typedef CONT CollectionType;
  /// Container's value type.
  typedef typename CollectionType::value_type value_type;
  /// Container's constant iterator type.
  typedef typename CollectionType::const_iterator const_iterator;
  /// Container's size type.
  typedef typename CollectionType::size_type size_type;

  /** Operator function call overload to allow call chaining.
   * \param value the value to be inserted into the container
   */
  CollectionBuilder<CONT>& operator()(const value_type& value){
    return push_back(value);
  }
  /** Same as regular STL push_back() but allows call chaining.
   * \param value the value to be inserted into the container
   */
  CollectionBuilder<CONT>& push_back(const value_type& value){
    collection_.push_back(value);
    return *this;
  }
  /// \name Standard STL container interface
  /// @{
  const_iterator begin() const{return collection_.begin();}
  const_iterator end() const{return collection_.end();}
  size_type size() const{return collection_.size();}
  void clear() {collection_.clear();}
  ///@}
  /// Explicit typecast operator.
  operator const CollectionType&() const {return collection_;}
private:
  /// \cond PRIVATE
  CollectionType collection_;
  /// \endcond
};


// *********************************************************
/** A shortcut to use for building dictionaries.
 * This class is a wrapper around standard STL associative containers such as map.
 * It allows one to conveniently build dictionaries at the variable initialization time:
 * \code
 * #include "CollectionUtil.h"
 * #include "Map.h"  // for ostream << operator overload for STL map
 * using Util;
 * 
 * int main()
 * {
 *   typedef map<string,int> MyMap;
 *   MyMap myMap=DictionaryBuilder<MyMap>()("str1",1)("str2",2)("str3",3);
 *   cout<<myMap;
 *   // the following output will be produced:
 *   // [str1=1,str2=2,str3=3]
 * }
 * \endcode
 */
template <class CONT>
class DictionaryBuilder
{
public:
  /// The type of the associative container
  typedef CONT DictionaryType;
  /// Container's element type (usually a pair<key_type,mapped_type>)
  typedef typename DictionaryType::value_type value_type;
  /// Container's key type
  typedef typename DictionaryType::key_type key_type;
  /// Container's value type 
  typedef typename DictionaryType::mapped_type mapped_type;
  /// Container's constant iterator type 
  typedef typename DictionaryType::const_iterator const_iterator;
  /// Container's writable iterator type   
  typedef typename DictionaryType::iterator iterator;
  /// Container's size type
  typedef typename DictionaryType::size_type size_type;
 
  /** Operator function call overload to allow call chaining.
   * \param key the value key to be inserted
   * \param value the value to be inserted into the container
   * \return a non-const reference to self
   */
  DictionaryBuilder<CONT>& operator()(const key_type& key,const mapped_type& value){
    dict_.insert(value_type(key,value));
    return *this;
  }
  /** Lookup value by key.
   * \param key the key associated with the value.
   * \return a non-const iterator pointing to the element whose key matched the \a key parameter
   */
  iterator find(const key_type& key){
    return dict_.find(key);
  }
  /** Lookup value by key.
   * \param key the key associated with the value.
   * \return a const iterator pointing to the element whose key matched the \a key parameter
   */
  const_iterator find(const key_type& key) const{
    return dict_.find(key);
  }

  /// \name Standard STL container interface
  /// @{
  const_iterator begin() const{return dict_.begin();}
  const_iterator end() const{return dict_.end();}
  size_type size() const{return dict_.size();}
  void clear() {dict_.clear();}
  ///@}
  /// Explicit typecast operator.
  operator const DictionaryType&() const {return dict_;}
private:
  DictionaryType dict_;
};


// ***********************************************************
/** Deletes all dynamically allocated elements of a collection.
 * C::value_type is expected to be a pointer to a dynamically allocated object, or it won't compile.
 * The function will iterate over all container elements and call delete for each of them.
 * \param c a collection (vector,set) whose elements are being deleted.
 */
template <class C>
void clearCollection(C& c){
  for(typename C::const_iterator it=c.begin();it!=c.end();++it)
    delete *it;
  c.clear();
}

/** Deletes all dynamically allocated values of the assotiative container.
 * The function expects the M::value_type to be a pair<..., ptr_to_type>, or it won't compile.
 * It first deletes the objects pointed to by ptr_to_type
 * and then clears (calls m.clear()) the container.
 * \param m an associative container (map,hash_map) whose elements are being deleted.
 */
template <class M>
void clearMap(M& m){
  for(typename M::const_iterator it=m.begin();it!=m.end();++it)
    delete it->second;
  m.clear();
}

} // namespace Util


#endif // _COLLECTION_UTIL_H_
