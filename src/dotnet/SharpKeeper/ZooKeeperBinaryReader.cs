namespace SharpKeeper
{
    using System.IO;
    using System.Text;
    using MiscUtil.Conversion;
    using MiscUtil.IO;

    public class ZooKeeperBinaryReader : EndianBinaryReader
    {
        public ZooKeeperBinaryReader(Stream stream): base(EndianBitConverter.Big, stream, Encoding.UTF8)
        {
        }
    }
}