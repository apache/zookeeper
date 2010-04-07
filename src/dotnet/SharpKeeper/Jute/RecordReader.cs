namespace Org.Apache.Jute
{
    using System.IO;

    public class RecordReader
    {
        private readonly IInputArchive archive;

        public RecordReader(BinaryReader reader, string format)
        {
            archive = new BinaryInputArchive(reader);
        }

        public void Read(IRecord r)
        {
            r.Deserialize(archive, "");
        }

    }
}