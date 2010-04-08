namespace Org.Apache.Jute
{
    using SharpKeeper;

    public class RecordWriter
    {
        private readonly BinaryOutputArchive archive;

        public RecordWriter(ZooKeeperBinaryWriter writer, string format)
        {
            archive = new BinaryOutputArchive(writer);
        }

        public void Write(IRecord r)
        {
            r.Serialize(archive, "");
        }
    }
}