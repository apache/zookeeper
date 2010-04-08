using System;
using System.IO;
using System.Text;

namespace MiscUtil.IO
{
	/// <summary>
	/// A simple class derived from StringWriter, but which allows
	/// the user to select which Encoding is used. This is most
	/// likely to be used with XmlTextWriter, which uses the Encoding
	/// property to determine which encoding to specify in the XML.
	/// </summary>
	public class StringWriterWithEncoding : StringWriter
	{
		/// <summary>
		/// The encoding to return in the Encoding property.
		/// </summary>
		Encoding encoding;

		/// <summary>
		/// Initializes a new instance of the StringWriterWithEncoding class
		/// with the specified encoding.
		/// </summary>
		/// <param name="encoding">The encoding to report.</param>
		public StringWriterWithEncoding(Encoding encoding)
		{
            if (encoding == null)
            {
                throw new ArgumentNullException("encoding");
            }
            this.encoding = encoding;
		}

		/// <summary>
		/// Initializes a new instance of the StringWriter class with the 
		/// specified format control and encoding.
		/// </summary>
		/// <param name="formatProvider">An IFormatProvider object that controls formatting.</param>
		/// <param name="encoding">The encoding to report.</param>
		public StringWriterWithEncoding (IFormatProvider formatProvider, Encoding encoding) 
			: base (formatProvider)
		{
            if (encoding == null)
            {
                throw new ArgumentNullException("encoding");
            }
            this.encoding = encoding;
		}

		/// <summary>
		/// Initializes a new instance of the StringWriter class that writes to the
		/// specified StringBuilder, and reports the specified encoding.
		/// </summary>
		/// <param name="sb">The StringBuilder to write to. </param>
		/// <param name="encoding">The encoding to report.</param>
		public StringWriterWithEncoding (StringBuilder sb, Encoding encoding) 
			: base (sb)
		{
            if (encoding == null)
            {
                throw new ArgumentNullException("encoding");
            }
            this.encoding = encoding;
		}

		/// <summary>
		/// Initializes a new instance of the StringWriter class that writes to the specified 
		/// StringBuilder, has the specified format provider, and reports the specified encoding.
		/// </summary>
		/// <param name="sb">The StringBuilder to write to. </param>
		/// <param name="formatProvider">An IFormatProvider object that controls formatting.</param>
		/// <param name="encoding">The encoding to report.</param>
		public StringWriterWithEncoding (StringBuilder sb, IFormatProvider formatProvider, Encoding encoding)
			: base (sb, formatProvider)
		{
            if (encoding == null)
            {
                throw new ArgumentNullException("encoding");
            }
			this.encoding = encoding;
		}

		/// <summary>
		/// Gets the Encoding in which the output is written.
		/// </summary>
		public override Encoding Encoding
		{
			get
			{
				return encoding;
			}
		}

	}
}
