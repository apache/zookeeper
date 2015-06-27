using org.apache.jute;

namespace org.apache.zookeeper {
    internal static class Assert
    {
        public static void assertEquals(string msg, object a, object b)
        {
            NUnit.Framework.Assert.AreEqual(a, b, msg);
        }

        public static void assertTrue(bool test)
        {
            NUnit.Framework.Assert.IsTrue(test);
        }

        public static void assertNull(object obj)
        {
            NUnit.Framework.Assert.Null(obj);
        }

        public static void assertNull(string msg, object obj)
        {
            NUnit.Framework.Assert.Null(obj, msg);
        }

        public static void fail(string msg)
        {
            NUnit.Framework.Assert.Fail(msg);
        }

        public static void assertEquals<T>(T a, T b)
        {
            NUnit.Framework.Assert.AreEqual(a, b);
        }

        public static void assertNotNull(object obj)
        {
            NUnit.Framework.Assert.NotNull(obj);
        }

        public static void assertNotNull(string msg, object obj)
        {
            NUnit.Framework.Assert.NotNull(obj, msg);
        }

        public static void assertArrayEquals(string msg, byte[] o1, byte[] o2)
        {
            NUnit.Framework.Assert.IsTrue(SequenceUtils.EqualsEx(o1, o2), msg);
        }

        public static void assertArrayEquals(byte[] o1, byte[] o2)
        {
            NUnit.Framework.Assert.IsTrue(SequenceUtils.EqualsEx(o1, o2));
        }

        public static void fail()
        {
            NUnit.Framework.Assert.Fail();
        }

        public static void assertFalse(bool test)
        {
            NUnit.Framework.Assert.IsFalse(test);
        }

        public static void assertTrue(string msg, bool test)
        {
            NUnit.Framework.Assert.IsTrue(test, msg);
        }

        public static void assertNotSame<T>(T a, T b)
        {
            NUnit.Framework.Assert.AreNotEqual(a, b);
        }

        public static void assertSame<T>(T a, T b)
        {
            NUnit.Framework.Assert.AreEqual(a, b);
        }

        public static void assertFalse(string msg, bool test) {
            NUnit.Framework.Assert.IsFalse(test, msg);
        }
    }
}