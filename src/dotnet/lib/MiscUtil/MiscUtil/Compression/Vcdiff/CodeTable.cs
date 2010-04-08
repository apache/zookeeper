using System;

namespace MiscUtil.Compression.Vcdiff
{
	/// <summary>
	/// Table used to encode/decode instructions.
	/// </summary>
	internal sealed class CodeTable
	{
		/// <summary>
		/// Default code table specified in RFC 3284.
		/// </summary>
		static internal CodeTable Default = BuildDefaultCodeTable();

		/// <summary>
		/// Array of entries in the code table
		/// </summary>
		Instruction[,] entries = new Instruction[256,2];

		/// <summary>
		/// 
		/// </summary>
		internal Instruction this[int i, int j]
		{
			get
			{
				return entries[i, j];
			}
		}

		internal CodeTable(byte[] bytes)
		{
			for (int i=0; i < 256; i++)
			{
				entries[i,0] = new Instruction((InstructionType)bytes[i], bytes[i+512], bytes[i+1024]);
				entries[i,1] = new Instruction((InstructionType)bytes[i+256], bytes[i+768], bytes[i+1280]);
			}
		}

		internal CodeTable(Instruction[,] entries)
		{
			if (entries==null)
			{
				throw new ArgumentNullException("entries");
			}
			if (entries.Rank != 2)
			{
				throw new ArgumentException ("Array must be rectangular.", "entries");
			}
			if (entries.GetLength(0) != 256)
			{
				throw new ArgumentException ("Array must have outer length 256.", "entries");
			}
			if (entries.GetLength(1) != 2)
			{
				throw new ArgumentException ("Array must have inner length 256.", "entries");
			}
			Array.Copy (entries, 0, this.entries, 0, 512);
		}

		/// <summary>
		/// Builds the default code table specified in RFC 3284
		/// </summary>
		/// <returns>
		/// The default code table.
		/// </returns>
		static CodeTable BuildDefaultCodeTable()
		{
			// Defaults are NoOps with size and mode 0.
			Instruction[,] entries = new Instruction[256,2];
			entries[0, 0] = new Instruction(InstructionType.Run, 0, 0);
			for (byte i=0; i < 18; i++)
			{
				entries[i+1, 0] = new Instruction(InstructionType.Add, i, 0);
			}

			int index = 19;

			// Entries 19-162
			for (byte mode = 0; mode < 9; mode++)
			{
				entries[index++, 0] = new Instruction (InstructionType.Copy, 0, mode);
				for (byte size = 4; size < 19; size++)
				{
					entries[index++, 0] = new Instruction (InstructionType.Copy, size, mode);
				}
			}

			// Entries 163-234
			for (byte mode = 0; mode < 6; mode++)
			{
				for (byte addSize = 1; addSize < 5; addSize++)
				{
					for (byte copySize = 4; copySize < 7; copySize++)
					{
						entries[index, 0] = new Instruction (InstructionType.Add, addSize, 0);
						entries[index++, 1] = new Instruction (InstructionType.Copy, copySize, mode);
					}
				}
			}

			// Entries 235-246
			for (byte mode = 6; mode < 9; mode++)
			{
				for (byte addSize = 1; addSize < 5; addSize++)
				{
					entries[index, 0] = new Instruction (InstructionType.Add, addSize, 0);
					entries[index++, 1] = new Instruction (InstructionType.Copy, 4, mode);
				}
			}

			// Entries 247-255
			for (byte mode = 0; mode < 9; mode++)
			{
				entries[index, 0] = new Instruction (InstructionType.Copy, 4, mode);
				entries[index++, 1] = new Instruction (InstructionType.Add, 1, 0);
			}

			return new CodeTable(entries);
		}

		internal byte[] GetBytes()
		{
			byte[] ret = new byte[1536];
			for (int i=0; i < 256; i++)
			{
				ret[i]=(byte)entries[i,0].Type;
				ret[i+256]=(byte)entries[i,1].Type;
				ret[i+512]=entries[i,0].Size;
				ret[i+768]=entries[i,1].Size;
				ret[i+1024]=entries[i,0].Mode;
				ret[i+1280]=entries[i,1].Mode;
			}
			return ret;
		}
	}
}
