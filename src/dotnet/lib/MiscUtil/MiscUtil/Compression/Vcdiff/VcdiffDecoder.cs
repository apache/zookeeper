using System;
using System.IO;

using MiscUtil.Checksum;

namespace MiscUtil.Compression.Vcdiff
{
	/// <summary>
	/// Decoder for VCDIFF (RFC 3284) streams.
	/// </summary>
	public sealed class VcdiffDecoder
	{
		#region Fields
		/// <summary>
		/// Reader containing original data, if any. May be null.
		/// If non-null, will be readable and seekable.
		/// </summary>
		Stream original;

		/// <summary>
		/// Stream containing delta data. Will be readable.
		/// </summary>
		Stream delta;
		
		/// <summary>
		/// Stream containing target data. Will be readable,
		/// writable and seekable.
		/// </summary>
		Stream output;

		/// <summary>
		/// Code table to use for decoding.
		/// </summary>
		CodeTable codeTable = CodeTable.Default;

		/// <summary>
		/// Address cache to use when decoding; must be reset before decoding each window.
		/// Default to the default size.
		/// </summary>
		AddressCache cache = new AddressCache(4, 3);
		#endregion

		#region Constructor
		/// <summary>
		/// Sole constructor; private to prevent instantiation from
		/// outside the class.
		/// </summary>
		VcdiffDecoder(Stream original, Stream delta, Stream output)
		{
			this.original = original;
			this.delta = delta;
			this.output = output;
		}
		#endregion

		#region Public interface
		/// <summary>
		/// Decodes an original stream and a delta stream, writing to a target stream.
		/// The original stream may be null, so long as the delta stream never
		/// refers to it. The original and delta streams must be readable, and the
		/// original stream (if any) and the target stream must be seekable. 
		/// The target stream must be writable and readable. The original and target
		/// streams are rewound to their starts before any data is read; the relevant data
		/// must occur at the beginning of the original stream, and any data already present
		/// in the target stream may be overwritten. The delta data must begin
		/// wherever the delta stream is currently positioned. The delta stream must end
		/// after the last window. The streams are not disposed by this method.
		/// </summary>
		/// <param name="original">Stream containing delta. May be null.</param>
		/// <param name="delta">Stream containing delta data.</param>
		/// <param name="output">Stream to write resulting data to.</param>
		public static void Decode (Stream original, Stream delta, Stream output)
		{
			#region Simple argument checking
			if (original != null && (!original.CanRead || !original.CanSeek))
			{
				throw new ArgumentException ("Must be able to read and seek in original stream", "original");
			}
			if (delta==null)
			{
				throw new ArgumentNullException("delta");
			}
			if (!delta.CanRead)
			{
				throw new ArgumentException ("Unable to read from delta stream");
			}
			if (output==null)
			{
				throw new ArgumentNullException("output");
			}
			if (!output.CanWrite || !output.CanRead || !output.CanSeek)
			{
				throw new ArgumentException ("Must be able to read, write and seek in output stream", "output");
			}
			#endregion

			// Now the arguments are checked, we construct an instance of the
			// class and ask it to do the decoding.
			VcdiffDecoder instance = new VcdiffDecoder(original, delta, output);
			instance.Decode();
		}
		#endregion

		#region Private methods
		/// <summary>
		/// Top-level decoding method. When this method exits, all decoding has been performed.
		/// </summary>
		void Decode()
		{
			ReadHeader();
			
			while (DecodeWindow());
		}

		/// <summary>
		/// Read the header, including any custom code table. The delta stream is left
		/// positioned at the start of the first window.
		/// </summary>
		void ReadHeader()
		{
			byte[] header = IOHelper.CheckedReadBytes(delta, 4);
			if (header[0] != 0xd6 ||
				header[1] != 0xc3 ||
				header[2] != 0xc4)
			{
				throw new VcdiffFormatException("Invalid VCDIFF header in delta stream");
			}
			if (header[3] != 0)
			{
				throw new VcdiffFormatException("VcdiffDecoder can only read delta streams of version 0");
			}

			// Load the header indicator
			byte headerIndicator = IOHelper.CheckedReadByte(delta);
			if ((headerIndicator&1) != 0)
			{
				throw new VcdiffFormatException
					("VcdiffDecoder does not handle delta stream using secondary compressors");
			}
			bool customCodeTable = ((headerIndicator&2) != 0);
			bool applicationHeader = ((headerIndicator&4) != 0);

			if ((headerIndicator & 0xf8) != 0)
			{
				throw new VcdiffFormatException ("Invalid header indicator - bits 3-7 not all zero.");
			}

			// Load the custom code table, if there is one
			if (customCodeTable)
			{
				ReadCodeTable();
			}

			// Ignore the application header if we have one. This tells xdelta3 what the right filenames are.
			if (applicationHeader)
			{
				int appHeaderLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);
				IOHelper.CheckedReadBytes(delta, appHeaderLength);
			}
		}

		/// <summary>
		/// Reads the custom code table, if there is one
		/// </summary>
		void ReadCodeTable()
		{
			// The length given includes the nearSize and sameSize bytes
			int compressedTableLength = IOHelper.ReadBigEndian7BitEncodedInt(delta)-2;
			int nearSize = IOHelper.CheckedReadByte(delta);
			int sameSize = IOHelper.CheckedReadByte(delta);
			byte[] compressedTableData = IOHelper.CheckedReadBytes(delta, compressedTableLength);

			byte[] defaultTableData = CodeTable.Default.GetBytes();

			MemoryStream tableOriginal = new MemoryStream(defaultTableData, false);
			MemoryStream tableDelta = new MemoryStream(compressedTableData, false);
			byte[] decompressedTableData = new byte[1536];
			MemoryStream tableOutput = new MemoryStream(decompressedTableData, true);
			VcdiffDecoder.Decode(tableOriginal, tableDelta, tableOutput);

			if (tableOutput.Position != 1536)
			{
				throw new VcdiffFormatException("Compressed code table was incorrect size");
			}

			codeTable = new CodeTable(decompressedTableData);
			cache = new AddressCache(nearSize, sameSize);
		}

		/// <summary>
		/// Reads and decodes a window, returning whether or not there was
		/// any more data to read.
		/// </summary>
		/// <returns>
		/// Whether or not the delta stream had reached the end of its data.
		/// </returns>
		bool DecodeWindow ()
		{
			int windowIndicator = delta.ReadByte();
			// Have we finished?
			if (windowIndicator==-1)
			{
				return false;
			}
			
			// The stream to load source data from for this window, if any
			Stream sourceStream;
			// Where to reposition the source stream to after reading from it, if anywhere
			int sourceStreamPostReadSeek=-1;

			// xdelta3 uses an undocumented extra bit which indicates that there are an extra
			// 4 bytes at the end of the encoding for the window
			bool hasAdler32Checksum = ((windowIndicator&4)==4);

			// Get rid of the checksum bit for the rest
			windowIndicator &= 0xfb;

			// Work out what the source data is, and detect invalid window indicators
			switch (windowIndicator&3)
			{
				// No source data used in this window
				case 0:
					sourceStream = null;
					break;
				// Source data comes from the original stream
				case 1:
					if (original==null)
					{
						throw new VcdiffFormatException
							("Source stream requested by delta but not provided by caller.");
					}
					sourceStream = original;
					break;
				case 2:
					sourceStream = output;
					sourceStreamPostReadSeek = (int)output.Position;
					break;
				case 3:								
					throw new VcdiffFormatException
						("Invalid window indicator - bits 0 and 1 both set.");			
				default:
					throw new VcdiffFormatException("Invalid window indicator - bits 3-7 not all zero.");
			}

			// Read the source data, if any
			byte[] sourceData = null;
			int sourceLength = 0;
			if (sourceStream != null)
			{
				sourceLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);
				int sourcePosition = IOHelper.ReadBigEndian7BitEncodedInt(delta);
		
				sourceStream.Position = sourcePosition;

				sourceData = IOHelper.CheckedReadBytes(sourceStream, sourceLength);
				// Reposition the source stream if appropriate
				if (sourceStreamPostReadSeek != -1)
				{
					sourceStream.Position = sourceStreamPostReadSeek;
				}
			}

			// Read how long the delta encoding is - then ignore it
			IOHelper.ReadBigEndian7BitEncodedInt(delta);

			// Read how long the target window is
			int targetLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);
			byte[] targetData = new byte[targetLength];
			MemoryStream targetDataStream = new MemoryStream(targetData, true);

			// Read the indicator and the lengths of the different data sections
			byte deltaIndicator = IOHelper.CheckedReadByte(delta);
			if (deltaIndicator != 0)
			{
				throw new VcdiffFormatException("VcdiffDecoder is unable to handle compressed delta sections.");
			}

			int addRunDataLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);
			int instructionsLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);
			int addressesLength = IOHelper.ReadBigEndian7BitEncodedInt(delta);

			// If we've been given a checksum, we have to read it and we might as well
			// use it to check the data.
			int checksumInFile=0;
			if (hasAdler32Checksum)
			{
				byte[] checksumBytes = IOHelper.CheckedReadBytes(delta, 4);
				checksumInFile = (checksumBytes[0]<<24) |
					             (checksumBytes[1]<<16) |
					             (checksumBytes[2]<<8) |
					             checksumBytes[3];
			}

			// Read all the data for this window
			byte[] addRunData = IOHelper.CheckedReadBytes(delta, addRunDataLength);
			byte[] instructions = IOHelper.CheckedReadBytes(delta, instructionsLength);
			byte[] addresses = IOHelper.CheckedReadBytes(delta, addressesLength);

			int addRunDataIndex = 0;

			MemoryStream instructionStream = new MemoryStream(instructions, false);

			cache.Reset(addresses);

			while (true)
			{
				int instructionIndex = instructionStream.ReadByte();
				if (instructionIndex==-1)
				{
					break;
				}
				
				for (int i=0; i < 2; i++)
				{
					Instruction instruction = codeTable[instructionIndex, i];
					int size = instruction.Size;
					if (size==0 && instruction.Type != InstructionType.NoOp)
					{
						size = IOHelper.ReadBigEndian7BitEncodedInt(instructionStream);
					}
					switch (instruction.Type)
					{
						case InstructionType.NoOp:
							break;
						case InstructionType.Add:
							targetDataStream.Write(addRunData, addRunDataIndex, size);
							addRunDataIndex += size;
							break;
						case InstructionType.Copy:
							int addr = cache.DecodeAddress((int)targetDataStream.Position+sourceLength, instruction.Mode);							
							if (sourceData != null && addr < sourceData.Length)
							{
								targetDataStream.Write(sourceData, addr, size);
							}
							else // Data is in target data
							{
								// Get rid of the offset
								addr -= sourceLength;
								// Can we just ignore overlap issues?
								if (addr + size < targetDataStream.Position)
								{
									targetDataStream.Write(targetData, addr, size);
								}
								else
								{
									for (int j=0; j < size; j++)
									{
										targetDataStream.WriteByte(targetData[addr++]);
									}
								}
							}
							break;
						case InstructionType.Run:
							byte data = addRunData[addRunDataIndex++];
							for (int j=0; j < size; j++)
							{
								targetDataStream.WriteByte(data);
							}
							break;
						default:
							throw new VcdiffFormatException("Invalid instruction type found.");
					}
				}
			}
			output.Write(targetData, 0, targetLength);
			
			if (hasAdler32Checksum)
			{
				int actualChecksum = Adler32.ComputeChecksum(1, targetData);
				if (actualChecksum != checksumInFile)
				{
					throw new VcdiffFormatException("Invalid checksum after decoding window");
				}
			}
			return true;
		}
		#endregion
	}
}
