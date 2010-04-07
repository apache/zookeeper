namespace Org.Apache.Jute
{
    using System.Collections;
    using System.Collections.Generic;
    using System.IO;
    using System.Text;

    public class BinaryOutputArchive : IOutputArchive
    {
        private readonly BinaryWriter writer;

        public static BinaryOutputArchive getArchive(BinaryWriter writer)
        {
            return new BinaryOutputArchive(writer);
        }

        /** Creates a new instance of BinaryOutputArchive */
        public BinaryOutputArchive(BinaryWriter writer)
        {
            this.writer = writer;
        }

        public void WriteByte(byte b, string tag)
        {
            writer.Write(b);
        }

        public void WriteBool(bool b, string tag)
        {
            writer.Write(b);
        }

        public void WriteInt(int i, string tag)
        {
            writer.Write(i);
        }

        public void WriteLong(long l, string tag)
        {
            writer.Write(l);
        }

        public void WriteFloat(float f, string tag)
        {
            writer.Write(f);
        }

        public void WriteDouble(double d, string tag)
        {
            writer.Write(d);
        }

        /**
         * create our own char encoder to utf8. This is faster 
         * then string.getbytes(UTF8).
         * @param s the string to encode into utf8
         * @return utf8 byte sequence.
         */
        private byte[] stringToByteBuffer(string s)
        {
            /*bb.clear();
            int len = s.Length;
            for (int i = 0; i < len; i++) {
                if (bb.remaining() < 3) {
                    ByteBuffer n = ByteBuffer.allocate(bb.capacity() << 1);
                    bb.flip();
                    n.put(bb);
                    bb = n;
                }
                char c = s.charAt(i);
                if (c < 0x80) {
                    bb.put((byte) c);
                } else if (c < 0x800) {
                    bb.put((byte) (0xc0 | (c >> 6)));
                    bb.put((byte) (0x80 | (c & 0x3f)));
                } else {
                    bb.put((byte) (0xe0 | (c >> 12)));
                    bb.put((byte) (0x80 | ((c >> 6) & 0x3f)));
                    bb.put((byte) (0x80 | (c & 0x3f)));
                }
            }
            bb.flip();
            return bb;*/
            return Encoding.UTF8.GetBytes(s);
        }

        public void WriteString(string s, string tag)
        {
            if (s == null)
            {
                WriteInt(-1, "len");
                return;
            }
            byte[] bb = stringToByteBuffer(s);
            WriteInt(bb.Length, "len");
            writer.Write(bb, 0, bb.Length);
        }

        public void WriteBuffer(byte[] barr, string tag)
        {
            if (barr == null)
            {
                writer.Write(-1);
                return;
            }
            writer.Write(barr.Length);
            writer.Write(barr);
        }

        public void WriteRecord(IRecord r, string tag)
        {
            r.Serialize(this, tag);
        }

        public void StartRecord(IRecord r, string tag) { }

        public void EndRecord(IRecord r, string tag) { }

        public void StartVector<T>(List<T> v, string tag)
        {
            if (v == null)
            {
                WriteInt(-1, tag);
                return;
            }
            WriteInt(v.Count, tag);
        }

        public void EndVector<T>(List<T> v, string tag) { }

        public void StartMap(SortedDictionary<string, string> v, string tag)
        {
            WriteInt(v.Count, tag);
        }

        public void EndMap(SortedDictionary<string, string> v, string tag) { }
    }
}
