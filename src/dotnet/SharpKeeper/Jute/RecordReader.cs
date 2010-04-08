namespace Org.Apache.Jute
{
    using SharpKeeper;

    public class RecordReader
    {
        private readonly IInputArchive archive;

        public RecordReader(ZooKeeperBinaryReader reader, string format)
        {
            archive = new BinaryInputArchive(reader);
        }

        public void Read(IRecord r)
        {
            r.Deserialize(archive, "");
        }

    }
}