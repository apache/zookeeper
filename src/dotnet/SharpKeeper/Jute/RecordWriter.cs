namespace Org.Apache.Jute
{
    using System.IO;

    public class RecordWriter
    {
        private readonly BinaryOutputArchive archive;

        public RecordWriter(BinaryWriter writer, string format)
        {
            archive = new BinaryOutputArchive(writer);
        }

        public void Write(IRecord r)
        {
            r.Serialize(archive, "");
        }
    }
}