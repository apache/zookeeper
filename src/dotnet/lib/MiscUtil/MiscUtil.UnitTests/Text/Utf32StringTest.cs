using NUnit.Framework;

using MiscUtil.Text;

namespace MiscUtil.UnitTests.Conversion
{
	[TestFixture]
	public class Utf32StringTest
	{
		[SetUp]
		public void Setup()
		{
        }

        #region Constructors
        [Test]
        public void IntegerArrayConstructorTakesACopy()
        {
            int[] intArray = new int[] {'a'};
            Utf32String text = new Utf32String(intArray);
            intArray[0] = 'b';
            Assert.AreEqual("a", text.ToString());
        }
	    #endregion

        #region IndexOf
        [Test]
		public void IndexOfInitialMatch()
		{
			CheckIndexOf("start", "start of string", 0);
		}

		[Test]
		public void IndexOfNoMatch()
		{
			CheckIndexOf ("foo", "only bars in this string", -1);
		}

		[Test]
		public void IndexOfMiddleMatch()
		{
			CheckIndexOf("dle of", "middle of string", 3);
		}

		[Test]
		public void IndexOfPartialMatch()
		{
			CheckIndexOf("start of string", "start", -1);
		}

		void CheckIndexOf (string needle, string haystack, int expectedResult)
		{
			Utf32String bigNeedle = new Utf32String(needle);
			Utf32String bigHaystack = new Utf32String(haystack);
			Assert.AreEqual(expectedResult, bigHaystack.IndexOf(bigNeedle));
		}
		#endregion

		#region Equality

		[Test]
		public void NullEquality()
		{
			Utf32String x = null;
			Utf32String y = null;
			Assert.IsTrue (x==y);
		}

		[Test]
		public void NullInequality()
		{
			Utf32String x = new Utf32String("Hello");
			Assert.IsFalse (x==null);
			Assert.IsFalse (null==x);
		}
		#endregion
	}
}