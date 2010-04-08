using System;
using System.Collections.Generic;
using System.Text;

using NUnit.Framework;
using MiscUtil.Collections;
using Rhino.Mocks;

namespace MiscUtil.UnitTests.Collections
{
    [TestFixture]
    public class ComparisonComparerTest
    {
        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void ConstructionWithNull()
        {
            new ComparisonComparer<string>(null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void CreateComparisonWithNull()
        {
            ComparisonComparer<string>.CreateComparison(null);
        }

        [Test]
        public void CreateAndCall()
        {
            MockRepository mocks = new MockRepository();
            Comparison<string> comparison = mocks.CreateMock<Comparison<string>>();
            Expect.Call(comparison("hello", "there")).Return(5);
            Expect.Call(comparison("x", "y")).Return(-3);
            Exception exception = new Exception();
            Expect.Call(comparison("throw", "exception")).Throw(exception);

            mocks.ReplayAll();
            IComparer<string> comparer = new ComparisonComparer<string>(comparison);
            Assert.AreEqual(5, comparer.Compare("hello", "there"));
            Assert.AreEqual(-3, comparer.Compare("x", "y"));
            try
            {
                comparer.Compare("throw", "exception");
                Assert.Fail("Expected exception");
            }
            catch (Exception e)
            {
                Assert.AreSame(exception, e);
            }
            mocks.VerifyAll();
        }

        [Test]
        public void CreateAndCallComparsison()
        {
            MockRepository mocks = new MockRepository();
            IComparer<string> comparer = mocks.CreateMock<IComparer<string>>();
            Expect.Call(comparer.Compare("hello", "there")).Return(5);
            Expect.Call(comparer.Compare("x", "y")).Return(-3);
            Exception exception = new Exception();
            Expect.Call(comparer.Compare("throw", "exception")).Throw(exception);

            mocks.ReplayAll();
            Comparison<string> comparison = ComparisonComparer<string>.CreateComparison(comparer);
            Assert.AreEqual(5, comparison("hello", "there"));
            Assert.AreEqual(-3, comparison("x", "y"));
            try
            {
                comparison("throw", "exception");
                Assert.Fail("Expected exception");
            }
            catch (Exception e)
            {
                Assert.AreSame(exception, e);
            }
            mocks.VerifyAll();
        }
    }
}
