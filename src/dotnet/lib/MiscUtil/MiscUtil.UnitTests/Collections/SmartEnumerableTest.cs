using System.Collections.Generic;
using MiscUtil.Collections;
using NUnit.Framework;
using System;

namespace MiscUtil.UnitTests.Collections
{
    [TestFixture]
    public class SmartEnumerableTest
    {
        [Test]
        public void NullEnumerableThrowsException()
        {
            try
            {
                new SmartEnumerable<string>(null);
                Assert.Fail("Expected exception");
            }
            catch (ArgumentNullException)
            {
                // Expected
            }
        }

        [Test]
        public void EmptyEnumerable()
        {
            List<string> emptyList = new List<string>();

            SmartEnumerable<string> subject = new SmartEnumerable<string>(emptyList);
            using (IEnumerator<SmartEnumerable<string>.Entry> iterator = subject.GetEnumerator())
            {
                Assert.IsFalse(iterator.MoveNext());
            }
        }

        [Test]
        public void SingleEntryEnumerable()
        {
            List<string> list = new List<string>();
            list.Add("x");

            SmartEnumerable<string> subject = new SmartEnumerable<string>(list);
            using (IEnumerator<SmartEnumerable<string>.Entry> iterator = subject.GetEnumerator())
            {
                Assert.IsTrue(iterator.MoveNext());
                Assert.IsTrue(iterator.Current.IsFirst);
                Assert.IsTrue(iterator.Current.IsLast);
                Assert.AreEqual("x", iterator.Current.Value);
                Assert.AreEqual(0, iterator.Current.Index);
                Assert.IsFalse(iterator.MoveNext());
            }
        }

        [Test]
        public void DoubleEntryEnumerable()
        {
            List<string> list = new List<string>();
            list.Add("x");
            list.Add("y");

            SmartEnumerable<string> subject = new SmartEnumerable<string>(list);
            using (IEnumerator<SmartEnumerable<string>.Entry> iterator = subject.GetEnumerator())
            {
                Assert.IsTrue(iterator.MoveNext());
                Assert.IsTrue(iterator.Current.IsFirst);
                Assert.IsFalse(iterator.Current.IsLast);
                Assert.AreEqual("x", iterator.Current.Value);
                Assert.AreEqual(0, iterator.Current.Index);

                Assert.IsTrue(iterator.MoveNext());
                Assert.IsFalse(iterator.Current.IsFirst);
                Assert.IsTrue(iterator.Current.IsLast);
                Assert.AreEqual("y", iterator.Current.Value);
                Assert.AreEqual(1, iterator.Current.Index);
                Assert.IsFalse(iterator.MoveNext());
            }
        }

        [Test]
        public void TripleEntryEnumerable()
        {
            List<string> list = new List<string>();
            list.Add("x");
            list.Add("y");
            list.Add("z");

            SmartEnumerable<string> subject = new SmartEnumerable<string>(list);
            using (IEnumerator<SmartEnumerable<string>.Entry> iterator = subject.GetEnumerator())
            {
                Assert.IsTrue(iterator.MoveNext());
                Assert.IsTrue(iterator.Current.IsFirst);
                Assert.IsFalse(iterator.Current.IsLast);
                Assert.AreEqual("x", iterator.Current.Value);
                Assert.AreEqual(0, iterator.Current.Index);

                Assert.IsTrue(iterator.MoveNext());
                Assert.IsFalse(iterator.Current.IsFirst);
                Assert.IsFalse(iterator.Current.IsLast);
                Assert.AreEqual("y", iterator.Current.Value);
                Assert.AreEqual(1, iterator.Current.Index);

                Assert.IsTrue(iterator.MoveNext());
                Assert.IsFalse(iterator.Current.IsFirst);
                Assert.IsTrue(iterator.Current.IsLast);
                Assert.AreEqual("z", iterator.Current.Value);
                Assert.AreEqual(2, iterator.Current.Index);
                Assert.IsFalse(iterator.MoveNext());
            }
        }
    }
}
