namespace Org.Apache.Jute
{
    public interface IRecord
    {
        void Serialize(IOutputArchive archive, string tag);
        void Deserialize(IInputArchive archive, string tag);
    }
}