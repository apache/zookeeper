using NUnit.Framework;

using MiscUtil.Collections;
using System.Collections.Generic;
using System.Collections;
using System;

using StringQueue = MiscUtil.Collections.RandomAccessQueue<string>;

namespace MiscUtil.UnitTests.Collections
{
    [TestFixture]
    public class RandomAccessQueueTest
    {
        [Test]
        public void ConstructWithCapacity()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity + 5);

            Assert.AreEqual(StringQueue.DefaultCapacity + 5, queue.Capacity);
        }

        [Test]
        public void ConstructWithSmallerThanDefaultCapacity()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity-1);

            Assert.AreEqual(StringQueue.DefaultCapacity, queue.Capacity);
        }

        [Test]
        public void SimpleAccess()
        {
            StringQueue queue = new StringQueue();

            Assert.AreEqual(0, queue.Count);
            queue.Enqueue("1");
            queue.Enqueue("2");
            Assert.AreEqual(2, queue.Count);

            Assert.AreEqual("1", queue.Dequeue());
            Assert.AreEqual("2", queue.Dequeue());
            Assert.AreEqual(0, queue.Count);
        }

        [Test]
        public void EnqueueAndDequeuePastInitialCapacity()
        {
            StringQueue queue = new StringQueue (StringQueue.DefaultCapacity);

            for (int i=0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                queue.Enqueue(i.ToString());
            }

            for (int i = 0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                Assert.AreEqual(i.ToString(), queue.Dequeue());
            }            
        }

        [Test]
        public void EnqueueAndDequeueAtStartPastInitialCapacity()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity);

            for (int i = 0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                queue.Enqueue(i.ToString(), 0);
            }

            for (int i = StringQueue.DefaultCapacity + 4; i >=0; i--)
            {
                Assert.AreEqual(i.ToString(), queue.Dequeue());
            }
        }

        [Test]
        public void TrimToSize()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity);

            for (int i = 0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                queue.Enqueue(i.ToString());
            }

            Assert.AreNotEqual(queue.Capacity, queue.Count);
            queue.TrimToSize();
            Assert.AreEqual(queue.Capacity, queue.Count);

            for (int i = 0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                Assert.AreEqual(i.ToString(), queue.Dequeue());
            }
        }

        [Test]
        public void TrimToSizeNoOp()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity+5);

            for (int i = 0; i < queue.Capacity; i++)
            {
                queue.Enqueue(i.ToString());
            }

            Assert.AreEqual(queue.Capacity, queue.Count);
            queue.TrimToSize();
            Assert.AreEqual(queue.Capacity, queue.Count);

            for (int i = 0; i < StringQueue.DefaultCapacity + 5; i++)
            {
                Assert.AreEqual(i.ToString(), queue.Dequeue());
            }
        }

        [Test]
        public void TrimToSizeWithWrap()
        {
            StringQueue queue = new StringQueue(StringQueue.DefaultCapacity+5);

            // Move the start along a bit
            for (int i = 0; i < 5; i++)
            {
                queue.Enqueue("ignore me");
            }
            for (int i = 0; i < 5; i++)
            {
                queue.Dequeue();
            }
            for (int i = 0; i < StringQueue.DefaultCapacity + 2; i++)
            {
                queue.Enqueue(i.ToString());
            }

            Assert.AreNotEqual(queue.Capacity, queue.Count);
            queue.TrimToSize();
            Assert.AreEqual(queue.Capacity, queue.Count);

            for (int i = 0; i < StringQueue.DefaultCapacity + 2; i++)
            {
                Assert.AreEqual(i.ToString(), queue.Dequeue());
            }
        }

        [Test]
        public void DequeueOnEmptyIsInvalid()
        {
            StringQueue queue = new StringQueue();

            try
            {
                queue.Dequeue();
                Assert.Fail("Expected exception");
            }
            catch (InvalidOperationException)
            {
                // Expected
            }
        }

        [Test]
        public void BinarySearchComparableNull()
        {
            StringQueue queue = new StringQueue();

            Assert.AreEqual(~0, queue.BinarySearch(null));
            queue.Add("hello");
            Assert.AreEqual(~0, queue.BinarySearch(null));
            queue.Dequeue();
            queue.Add(null);
            Assert.AreEqual(0, queue.BinarySearch(null));
        }

        [Test]
        public void BinarySearchIncomparable()
        {
            RandomAccessQueue<object> queue = new RandomAccessQueue<object>();

            try
            {
                // object doesn't implement IComparable
                queue.BinarySearch(new object());
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void BinarySearchComparableEmpty()
        {
            StringQueue queue = new StringQueue();
            Assert.AreEqual(~0, queue.BinarySearch("1"));
        }

        [Test]
        public void BinarySearchNullComparison()
        {
            StringQueue queue = new StringQueue();
            try
            {
                queue.BinarySearch("1", (Comparison<string>)null);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentNullException)
            {
                // Expected
            }
        }

        [Test]
        public void BinarySearchComparisonEmpty()
        {
            StringQueue queue = new StringQueue();

            Assert.AreEqual(~0, queue.BinarySearch("1", delegate(string x, string y)
            {
                throw new Exception("Don't expect to be called");
            }));
        }

        [Test]
        public void BinarySearchComparerNull()
        {
            StringQueue queue = new StringQueue();

            IComparer<string> comparer = Comparer<string>.Default;
            Assert.AreEqual(~0, queue.BinarySearch(null, comparer));
            queue.Add("hello");
            Assert.AreEqual(~0, queue.BinarySearch(null, comparer));
            queue.Dequeue();
            queue.Add(null);
            Assert.AreEqual(0, queue.BinarySearch(null, comparer));
        }

        [Test]
        public void BinarySearchNullComparer()
        {
            StringQueue queue = new StringQueue();
            try
            {
                queue.BinarySearch("1", (IComparer<string>)null);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentNullException)
            {
                // Expected
            }
        }

        [Test]
        public void BinarySearchComparable()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("3");
            queue.Enqueue("5");

            Assert.AreEqual(0, queue.BinarySearch("1"));
            Assert.AreEqual(~1, queue.BinarySearch("2"));
            Assert.AreEqual(1, queue.BinarySearch("3"));
            Assert.AreEqual(~2, queue.BinarySearch("4"));
            Assert.AreEqual(2, queue.BinarySearch("5"));
            Assert.AreEqual(~3, queue.BinarySearch("6"));
        }

        // Note: this test will also test IComparer due to the implementation.
        [Test]
        public void BinarySearchComparison()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("13");
            queue.Enqueue("50");
            queue.Enqueue("200");

            Comparison<string> comparison = delegate(string x, string y)
            {
                int first = int.Parse(x);
                int second = int.Parse(y);
                return first.CompareTo(second);
            };

            Assert.AreEqual(0, queue.BinarySearch("1", comparison));
            Assert.AreEqual(~1, queue.BinarySearch("12", comparison));
            Assert.AreEqual(1, queue.BinarySearch("13", comparison));
            Assert.AreEqual(~2, queue.BinarySearch("35", comparison));
            Assert.AreEqual(2, queue.BinarySearch("50", comparison));
            Assert.AreEqual(~3, queue.BinarySearch("100", comparison));
            Assert.AreEqual(3, queue.BinarySearch("200", comparison));
            Assert.AreEqual(~4, queue.BinarySearch("1000", comparison));
        }

        [Test]
        public void StronglyTypedClone()
        {
            RandomAccessQueue<object> queue = new RandomAccessQueue<object>();

            object first = new object();
            object second = new object();
            queue.Enqueue(first);
            queue.Enqueue(second);

            RandomAccessQueue<object> clone = queue.Clone();

            Assert.AreEqual(queue.Count, clone.Count);

            Assert.AreSame(first, queue.Dequeue());
            Assert.AreSame(first, clone.Dequeue());
            Assert.AreSame(second, queue.Dequeue());
            Assert.AreSame(second, clone.Dequeue());
            Assert.AreNotSame(queue.SyncRoot, clone.SyncRoot);
        }

        [Test]
        public void ICloneableClone()
        {
            RandomAccessQueue<object> queue = new RandomAccessQueue<object>();

            object first = new object();
            object second = new object();
            queue.Enqueue(first);
            queue.Enqueue(second);

            ICloneable cloneable = queue;
            RandomAccessQueue<object> clone = (RandomAccessQueue<object>)cloneable.Clone();

            Assert.AreEqual(queue.Count, clone.Count);

            Assert.AreSame(first, queue.Dequeue());
            Assert.AreSame(first, clone.Dequeue());
            Assert.AreSame(second, queue.Dequeue());
            Assert.AreSame(second, clone.Dequeue());
            Assert.AreNotSame(queue.SyncRoot, clone.SyncRoot);
        }

        [Test]
        public void Clear()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Clear();
            Assert.AreEqual(0, queue.Count);
        }

        public void EnqueueWithIndex()
        {
            StringQueue queue = new StringQueue();
            queue.Enqueue("1");
            queue.Enqueue("3");
            queue.Enqueue("2", 1);

            Assert.AreEqual("1", queue.Dequeue());
            Assert.AreEqual("2", queue.Dequeue());
            Assert.AreEqual("3", queue.Dequeue());
        }

        [Test]
        public void EnqueueWithNegativeIndex()
        {
            StringQueue queue = new StringQueue();
            queue.Enqueue("1");
            queue.Enqueue("3");

            try
            {
                queue.Enqueue("2", -1);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void EnqueueWithExcessiveIndex()
        {
            StringQueue queue = new StringQueue();
            queue.Enqueue("1");
            queue.Enqueue("3");

            try
            {
                queue.Enqueue("2", 3);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void EnqueueWithIndexEqualToCount()
        {
            StringQueue queue = new StringQueue();
            queue.Enqueue("1");
            queue.Enqueue("3");
            queue.Enqueue("2", 2);

            Assert.AreEqual("1", queue.Dequeue());
            Assert.AreEqual("3", queue.Dequeue());
            Assert.AreEqual("2", queue.Dequeue());
        }

        [Test]
        public void StrongCopyTo()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            string[] array = new string[5];
            queue.CopyTo(array, 1);
            Assert.IsNull(array[0]);
            Assert.AreEqual("1", array[1]);
            Assert.AreEqual("2", array[2]);
            Assert.AreEqual("3", array[3]);
            Assert.IsNull(array[4]);
        }

        [Test]
        public void StrongCopyToNull()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            try
            {
                queue.CopyTo((string[])null, 0);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentNullException)
            {
                // Expected
            }
        }

        [Test]
        public void WeakCopyToNull()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");
            try
            {
                queue.CopyTo((Array)null, 0);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentNullException)
            {
                // Expected
            }
        }

        [Test]
        public void CopyToWrongArrayType()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            try
            {
                queue.CopyTo(new object[5], 0);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void CopyToTooShort()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            try
            {
                queue.CopyTo(new string[5], 3);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void CopyToNegativeIndex()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            try
            {
                queue.CopyTo(new string[5], -1);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentException)
            {
                // Expected
            }
        }

        [Test]
        public void WeakCopyTo()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            string[] array = new string[5];
            Array weakArray = array;
            queue.CopyTo(weakArray, 1);
            Assert.IsNull(array[0]);
            Assert.AreEqual("1", array[1]);
            Assert.AreEqual("2", array[2]);
            Assert.AreEqual("3", array[3]);
            Assert.IsNull(array[4]);
        }

        [Test]
        public void ContainsNull()
        {
            StringQueue queue = new StringQueue();
            queue.Add("1");
            Assert.IsFalse(queue.Contains(null));
            queue.Add(null);
            Assert.IsTrue(queue.Contains(null));
        }

        [Test]
        public void RemovePresentItem()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            Assert.IsTrue(queue.Remove("1"));
            Assert.IsFalse(queue.Contains("1"));
            Assert.AreEqual(1, queue.Count);
        }

        [Test]
        public void RemoveMissingItem()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            Assert.IsFalse(queue.Remove("2"));
            Assert.AreEqual(1, queue.Count);
        }

        [Test]
        public void RemoveMissingNull()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            Assert.IsFalse(queue.Remove(null));
            Assert.AreEqual(1, queue.Count);
        }

        [Test]
        public void RemovePresentNull()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue(null);
            queue.Enqueue("2");
            Assert.IsTrue(queue.Remove(null));
            Assert.IsFalse(queue.Contains(null));
            Assert.AreEqual(2, queue.Count);
            Assert.AreEqual("2", queue[1]);
        }

        [Test]
        public void RemoveAtBeforeStart()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            try
            {
                queue.RemoveAt(-1);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentOutOfRangeException)
            {
                // Expected
            }
        }

        [Test]
        public void RemoveAtAfterEnd()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");

            try
            {
                queue.RemoveAt(2);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentOutOfRangeException)
            {
                // Expected
            }
        }

        [Test]
        public void RemoveAtStart()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");
            queue.RemoveAt(0);
            Assert.AreEqual(2, queue.Count);
            Assert.AreEqual("2", queue[0]);
            Assert.AreEqual("3", queue[1]);
        }

        [Test]
        public void RemoveAtEnd()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");
            queue.RemoveAt(2);
            Assert.AreEqual(2, queue.Count);
            Assert.AreEqual("1", queue[0]);
            Assert.AreEqual("2", queue[1]);
        }

        [Test]
        public void RemoveAtShuffleUp()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");
            queue.RemoveAt(1);
            Assert.AreEqual(2, queue.Count);
            Assert.AreEqual("1", queue[0]);
            Assert.AreEqual("3", queue[1]);
        }

        [Test]
        public void RemoveAtShuffleDown()
        {
            StringQueue queue = new StringQueue();

            for (int i = 0; i < StringQueue.DefaultCapacity - 1; i++)
            {
                queue.Enqueue(null);
            }
            for (int i = 0; i < StringQueue.DefaultCapacity - 1; i++)
            {
                Assert.IsNull(queue.Dequeue());
            }

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");
            queue.RemoveAt(1);
            Assert.AreEqual(2, queue.Count);
            Assert.AreEqual("1", queue[0]);
            Assert.AreEqual("3", queue[1]);
        }

        [Test]
        public void ContainsMissingItem()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            Assert.IsTrue(queue.Contains("1"));
        }

        [Test]
        public void ContainsPresentItem()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            Assert.IsFalse(queue.Contains("2"));
        }

        [Test]
        public void SimpleEnumeration()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");
            queue.Enqueue("3");

            IEnumerator<string> enumerator = queue.GetEnumerator();
            Assert.IsTrue(enumerator.MoveNext());
            Assert.AreEqual("1", enumerator.Current);
            Assert.IsTrue(enumerator.MoveNext());
            Assert.AreEqual("2", enumerator.Current);
            Assert.IsTrue(enumerator.MoveNext());
            Assert.AreEqual("3", enumerator.Current);
            Assert.IsFalse(enumerator.MoveNext());
        }

        [Test]
        public void ChangeToQueueDuringIterationCausesException()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");

            IEnumerator<string> enumerator = queue.GetEnumerator();
            Assert.IsTrue(enumerator.MoveNext());
            queue.Enqueue("3");
            try
            {
                enumerator.MoveNext();
                Assert.Fail("Expected exception");
            }
            catch (InvalidOperationException)
            {
                // Expected
            }
        }

        [Test]
        public void ChangeQueueDuringIterationRemovingRemainingElements()
        {
            StringQueue queue = new StringQueue();

            queue.Enqueue("1");
            queue.Enqueue("2");

            IEnumerator<string> enumerator = queue.GetEnumerator();
            Assert.IsTrue(enumerator.MoveNext());
            Assert.IsTrue(enumerator.MoveNext());
            queue.Dequeue();
            queue.Dequeue();
            try
            {
                enumerator.MoveNext();
                Assert.Fail("Expected exception");
            }
            catch (InvalidOperationException)
            {
                // Expected
            }
        }

        [Test]
        public void SyncRoot()
        {
            StringQueue queue = new StringQueue();

            object syncRoot = queue.SyncRoot;
            Assert.IsNotNull(syncRoot);
            queue.Enqueue("hello");
            Assert.AreSame(syncRoot, queue.SyncRoot);
        }

        [Test]
        public void Add()
        {
            StringQueue queue = new StringQueue();

            Assert.AreEqual(0, queue.Count);
            queue.Add("1");
            queue.Add("2");
            Assert.AreEqual(2, queue.Count);

            Assert.AreEqual("1", queue.Dequeue());
            Assert.AreEqual("2", queue.Dequeue());
        }

        [Test]
        public void AlwaysWritable()
        {
            StringQueue queue = new StringQueue();
            Assert.IsFalse(queue.IsReadOnly);
        }

        [Test]
        public void NeverSynchronized()
        {
            StringQueue queue = new StringQueue();
            Assert.IsFalse(queue.IsSynchronized);
        }

        [Test]
        public void NonGenericGetEnumerator()
        {
            StringQueue queue = new StringQueue();
            for (int i = 0; i < 5; i++)
            {
                queue.Add(i.ToString());
            }

            int count = 0;
            foreach (string x in (IEnumerable)queue)
            {
                Assert.AreEqual(count.ToString(), x);
                count++;
            }
            Assert.AreEqual(5, queue.Count);
            Assert.AreEqual(5, count);
        }
    }
}
