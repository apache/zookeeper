using System;
using System.Collections.Generic;
using System.Text;
using NUnit.Framework;
using MiscUtil.Collections;

namespace MiscUtil.UnitTests.Collections
{
    [TestFixture]
    public class RangeTest
    {
        Range<int> m_subject;

        [SetUp]
        public void SetUp()
        {
            m_subject = new Range<int>(5, 10);
        }

        [Test]
        public void DoesNotContainLowerThanStart()
        {
            Assert.IsFalse(m_subject.Contains(4));
        }

        [Test]
        public void ContainsStart()
        {
            Assert.IsTrue(m_subject.Contains(5));
        }

        [Test]
        public void ContainsMiddle()
        {
            Assert.IsTrue(m_subject.Contains(7));
        }

        [Test]
        public void DoesNotContainHigherThanEnd()
        {
            Assert.IsFalse(m_subject.Contains(11));
        }

        [Test]
        public void ContainsEndByDefault()
        {
            Assert.IsTrue(m_subject.Contains(10));
        }

        [Test]
        public void DoesNotContainEndWhenExclusive()
        {
            Assert.IsFalse(m_subject.Exclusive.Contains(10));
        }

        [Test]
        public void DoesContainEndWhenMadeExclusiveThenInclusive()
        {
            Assert.IsTrue(m_subject.Exclusive.Inclusive.Contains(10));
        }

        [Test]
        public void InclusiveReturnsThisWhenAlreadyInclusive()
        {
            Assert.AreSame(m_subject, m_subject.Inclusive);
        }

        [Test]
        public void ExclusiveReturnsThisWhenAlreadyExclusive()
        {
            Range<int> exclusive = m_subject.Exclusive;
            Assert.AreSame(exclusive, exclusive.Exclusive);
        }

    }
}
