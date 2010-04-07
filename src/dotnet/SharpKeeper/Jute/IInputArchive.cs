namespace Org.Apache.Jute
{
    public interface IInputArchive
    {
        byte ReadByte(string tag);
        bool ReadBool(string tag);
        int ReadInt(string tag);
        long ReadLong(string tag);
        float ReadFloat(string tag);
        double ReadDouble(string tag);
        string ReadString(string tag);
        byte[] ReadBuffer(string tag);
        void ReadRecord(IRecord r, string tag);
        void StartRecord(string tag);
        void EndRecord(string tag);
        IIndex StartVector(string tag);
        void EndVector(string tag);
        IIndex StartMap(string tag);
        void EndMap(string tag);
    }
}