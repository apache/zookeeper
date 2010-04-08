using System.Text;

namespace SharpKeeper
{
    using System.IO;
    using MiscUtil.Conversion;
    using MiscUtil.IO;

    public class ZooKeeperBinaryWriter : EndianBinaryWriter
    {
        public ZooKeeperBinaryWriter(Stream stream) : base(EndianBitConverter.Big, stream, Encoding.UTF8)
        {
        }
    }
}
