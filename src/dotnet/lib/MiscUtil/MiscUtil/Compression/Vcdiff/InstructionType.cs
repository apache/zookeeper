using System;

namespace MiscUtil.Compression.Vcdiff
{
	/// <summary>
	/// Enumeration of the different instruction types.
	/// </summary>
	internal enum InstructionType : byte
	{
		NoOp=0,
		Add=1,
		Run=2,
		Copy=3
	}
}
