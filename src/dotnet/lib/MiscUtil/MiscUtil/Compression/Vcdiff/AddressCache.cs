using System;
using System.IO;

namespace MiscUtil.Compression.Vcdiff
{
	/// <summary>
	/// Cache used for encoding/decoding addresses.
	/// </summary>
	internal sealed class AddressCache
	{
		const byte SelfMode = 0;
		const byte HereMode = 1;

		int nearSize;
		int sameSize;
		int[] near;
		int nextNearSlot;
		int[] same;

		Stream addressStream;

		internal AddressCache(int nearSize, int sameSize)
		{
			this.nearSize = nearSize;
			this.sameSize = sameSize;
			near = new int[nearSize];
			same = new int[sameSize*256];
		}

		internal void Reset(byte[] addresses)
		{
			nextNearSlot = 0;
			Array.Clear(near, 0, near.Length);
			Array.Clear(same, 0, same.Length);

			addressStream = new MemoryStream(addresses, false);
		}

		internal int DecodeAddress (int here, byte mode)
		{
			int ret;
			if (mode==SelfMode)
			{
				ret = IOHelper.ReadBigEndian7BitEncodedInt(addressStream);
			}
			else if (mode==HereMode)
			{
				ret = here - IOHelper.ReadBigEndian7BitEncodedInt(addressStream);
			}
			else if (mode-2 < nearSize) // Near cache
			{
				ret = near[mode-2] + IOHelper.ReadBigEndian7BitEncodedInt(addressStream);
			}
			else // Same cache
			{
				int m = mode-(2+nearSize);
				ret = same[(m*256)+IOHelper.CheckedReadByte(addressStream)];
			}

			Update (ret);
			return ret;
		}

		void Update (int address)
		{
			if (nearSize > 0)
			{
				near[nextNearSlot] = address;
				nextNearSlot=(nextNearSlot+1)%nearSize;
			}
			if (sameSize > 0)
			{
				same[address%(sameSize*256)] = address;
			}
		}
	}
}
