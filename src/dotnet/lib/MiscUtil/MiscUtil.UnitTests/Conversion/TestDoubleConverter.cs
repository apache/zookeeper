using System;

using MiscUtil.Conversion;

using NUnit.Framework;

namespace MiscUtil.UnitTests.Conversion
{
	[TestFixture]
	public class TestDoubleConverter
	{
		[Test]
		public void PositiveInfinity()
		{
			Assert.AreEqual("+Infinity", DoubleConverter.ToExactString(double.PositiveInfinity));
		}

		[Test]
		public void NegativeInfinity()
		{
			Assert.AreEqual("-Infinity", DoubleConverter.ToExactString(double.NegativeInfinity));
		}
	}
}
