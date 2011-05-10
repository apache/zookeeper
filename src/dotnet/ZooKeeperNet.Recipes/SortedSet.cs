namespace ZooKeeperNet.Recipes
{
    using System;
    using System.Collections;
    using System.Collections.Generic;
    using System.Linq;

    public class SortedSet<T> : ICollection<T> where T : IComparable<T>
    {
        private readonly SortedDictionary<T, object> backing = new SortedDictionary<T, object>(ComparableComparer.Instance);

        private class ComparableComparer : IComparer<T>
        {
            internal static readonly ComparableComparer Instance = new ComparableComparer();

            public int Compare(T x, T y)
            {
                return x.CompareTo(y);
            }
        }

        public void Add(T element)
        {
            backing[element] = new object();
        }

        public void Clear()
        {
            backing.Clear();
        }

        public bool Contains(T item)
        {
            return backing.ContainsKey(item);
        }

        public void CopyTo(T[] array, int arrayIndex)
        {
            var keys = backing.Keys.ToArray();
            Array.Copy(keys, array, arrayIndex);
        }

        public bool Remove(T item)
        {
            return backing.Remove(item);
        }

        public int Count
        {
            get { return backing.Count; }
        }

        public bool IsReadOnly
        {
            get { return false; }
        }

        public IEnumerator<T> GetEnumerator()
        {
            foreach (var key in backing.Keys)
                yield return key;
        }

        IEnumerator IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }

        public SortedSet<T> HeadSet(T element)
        {
            var headSet = new SortedSet<T>();
            foreach (var key in backing.Keys.TakeWhile(key => !key.Equals(element)))
                headSet.Add(key);
            
            return headSet;
        }
    }
}