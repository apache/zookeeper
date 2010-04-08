using System;
using System.Collections.Generic;
using System.Text;
using NUnit.Framework;
using Rhino.Mocks;

namespace MiscUtil.UnitTests
{
    [TestFixture]
    public class PartialComparerTest
    {
        #region ReferenceCompare
        [Test]
        public void EqualNonNullReferencesReturnZero()
        {
            Assert.AreEqual(0, PartialComparer.ReferenceCompare("x", "x").Value);
        }

        [Test]
        public void TwoNullReferencesReturnZero()
        {
            Assert.AreEqual(0, PartialComparer.ReferenceCompare<string>(null, null).Value);
        }

        [Test]
        public void NullLessThanNonNull()
        {
            Assert.AreEqual(-1, PartialComparer.ReferenceCompare(null, "x").Value);
        }

        [Test]
        public void NonNullGreaterThanNull()
        {
            Assert.AreEqual(1, PartialComparer.ReferenceCompare("x", null).Value);
        }

        [Test]
        public void DifferentNonNullReferencesReturnNull()
        {
            // Just to be extra careful, make two different references to equal strings.
            // That way we know we're not actually doing comparisons!
            string x1 = new StringBuilder("x").ToString();
            string x2 = new StringBuilder("x").ToString();
            Assert.AreEqual(x1, x2);
            Assert.AreNotSame(x1, x2);
            Assert.IsNull(PartialComparer.ReferenceCompare(x1, x2));
        }
        #endregion

        #region Compare
        [Test]
        public void DefaultLessThanReturnsLessThanZero()
        {
            Assert.IsTrue(PartialComparer.Compare(3, 5) < 0);
        }

        [Test]
        public void DefaultGreaterThanReturnsGreaterThanZero()
        {
            Assert.IsTrue(PartialComparer.Compare(5, 3) > 0);
        }

        [Test]
        public void DefaultEqualReturnsNull()
        {
            Assert.IsNull(PartialComparer.Compare(4, 4));
        }

        [Test]
        public void CompareReturnsNullWhenSpecifiedComparerReturnsNonZero()
        {
            MockRepository factory = new MockRepository();
            IComparer<string> comparer = factory.CreateMock<IComparer<string>>();

            Expect.Call(comparer.Compare("first", "second")).Return(15);

            factory.ReplayAll();

            Assert.AreEqual(15, PartialComparer.Compare(comparer, "first", "second").Value);

            factory.VerifyAll();
        }

        [Test]
        public void CompareReturnsNullWhenSpecifiedComparerReturnsZero()
        {
            MockRepository factory = new MockRepository();
            IComparer<string> comparer = factory.CreateMock<IComparer<string>>();

            Expect.Call(comparer.Compare("first", "second")).Return(0);

            factory.ReplayAll();

            Assert.IsNull(PartialComparer.Compare(comparer, "first", "second"));

            factory.VerifyAll();
        }
        #endregion

        #region Equals
        [Test]
        public void TwoNullReferencesAreEqual()
        {
            Assert.IsTrue(PartialComparer.Equals<string>(null, null).Value);
        }

        [Test]
        public void NullAndNonNullAreUnequal()
        {
            Assert.IsFalse(PartialComparer.Equals<string>(null, "non-null").Value);
        }

        [Test]
        public void NonNullAndNullAreUnequal()
        {
            Assert.IsFalse(PartialComparer.Equals("non-null", null).Value);
        }

        [Test]
        public void DifferentNonNullReferencesReturnMaybe()
        {
            Assert.IsNull(PartialComparer.Equals("first", "second"));
        }
        #endregion
    }
}
