using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.IO;

using MiscUtil.IO;
using NUnit.Framework;

namespace MiscUtil.UnitTests.IO
{
    [TestFixture]
    public class StringWriterWithEncodingTest
    {
        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void NullEncoding()
        {
            new StringWriterWithEncoding(null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void NullEncodingWithFormat()
        {
            new StringWriterWithEncoding(CultureInfo.CurrentCulture, null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void NullEncodingWithStringBuilder()
        {
            new StringWriterWithEncoding(new StringBuilder(), null);
        }

        [Test]
        [ExpectedException(typeof(ArgumentNullException))]
        public void NullEncodingWithStringBuilderAndFormat()
        {
            new StringWriterWithEncoding(new StringBuilder(), CultureInfo.CurrentCulture, null);
        }

        [Test]
        public void EncodingIsReturnedConstructingWithJustEncoding()
        {
            // Create a new one so we are really sure it's not fiddling things
            Encoding enc = new ASCIIEncoding();

            StringWriter writer = new StringWriterWithEncoding(enc);
            Assert.AreSame(enc, writer.Encoding);
        }

        [Test]
        public void EncodingIsReturnedConstructingWithFormat()
        {
            // Create a new one so we are really sure it's not fiddling things
            Encoding enc = new ASCIIEncoding();

            StringWriter writer = new StringWriterWithEncoding(CultureInfo.CurrentCulture, enc);
            Assert.AreSame(enc, writer.Encoding);
        }


        [Test]
        public void EncodingIsReturnedConstructingWithStringBuilder()
        {
            // Create a new one so we are really sure it's not fiddling things
            Encoding enc = new ASCIIEncoding();

            StringWriter writer = new StringWriterWithEncoding(new StringBuilder(), enc);
            Assert.AreSame(enc, writer.Encoding);
        }

        [Test]
        public void EncodingIsReturnedConstructingWithStringBuilderAndFormat()
        {
            // Create a new one so we are really sure it's not fiddling things
            Encoding enc = new ASCIIEncoding();

            StringWriter writer = new StringWriterWithEncoding(new StringBuilder(), 
                                                               CultureInfo.CurrentCulture,
                                                               enc);
            Assert.AreSame(enc, writer.Encoding);
        }
    }
}
