using System;
using System.IO;

namespace MiscUtil.Checksum
{
	/// <summary>
	/// Implementation of the Adler32 checksum routine.
	/// TODO: Derive from HashAlgorithm.
	/// </summary>
	public class Adler32
	{
        /// <summary>
        /// Base for modulo arithmetic
        /// </summary>
		const int Base = 65521;

        /// <summary>
        /// Number of iterations we can safely do before applying the modulo.
        /// </summary>
		const int NMax = 5552;

		/// <summary>
		/// Computes the Adler32 checksum for the given data.
		/// </summary>
		/// <param name="initial">
		/// Initial value or previous result. Use 1 for the
		/// first transformation.
		/// </param>
		/// <param name="data">The data to compute the checksum of</param>
		/// <param name="start">Index of first byte to compute checksum for</param>
		/// <param name="length">Number of bytes to compute checksum for</param>
		/// <returns>The checksum of the given data</returns>
		public static int ComputeChecksum (int initial, byte[] data, int start, int length)
		{
			if (data == null)
			{
				throw (new System.ArgumentNullException("data"));
			}
			uint s1 = (uint)(initial & 0xffff);
			uint s2 = (uint)((initial >> 16) & 0xffff);

			int index=start;
			int len = length;

			int k;
			while (len > 0)
			{
				k = (len < NMax) ? len : NMax;
				len -= k;

				for (int i=0; i < k; i++)
				{
					s1 += data[index++];
					s2 += s1;
				}
				s1 %= Base;
				s2 %= Base;
			}

			return (int)((s2<<16) | s1);
		}

		/// <summary>
		/// Computes the Adler32 checksum for the given data.
		/// </summary>
		/// <param name="initial">
		/// Initial value or previous result. Use 1 for the
		/// first transformation.
		/// </param>
		/// <param name="data">The data to compute the checksum of</param>
		/// <returns>The checksum of the given data</returns>
		public static int ComputeChecksum (int initial, byte[] data)
		{
			if (data == null)
			{
				throw (new System.ArgumentNullException("data"));
			}
			return ComputeChecksum(initial, data, 0, data.Length);
		}

		/// <summary>
		/// Computes the checksum for a stream, starting from the current
		/// position and reading until no more can be read
		/// </summary>
		/// <param name="stream">The stream to compute the checksum for</param>
		/// <returns>The checksum for the stream</returns>
		public static int ComputeChecksum (Stream stream)
		{
			if (stream == null)
			{
				throw (new System.ArgumentNullException("stream"));
			}
			byte[] buffer = new byte[8172];
			int size;
			int checksum=1;
			while ((size = stream.Read(buffer, 0, buffer.Length)) > 0)
			{
				checksum = ComputeChecksum(checksum, buffer, 0, size);
			}
			return checksum;
		}

		/// <summary>
		/// Computes the checksum of a file
		/// </summary>
		/// <param name="path">The file to compute the checksum of</param>
		/// <returns>The checksum for the file</returns>
		public static int ComputeChecksum (string path)
		{
			using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read))
			{
				return ComputeChecksum(stream);
			}
		}
	}
}
