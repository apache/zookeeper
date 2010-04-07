namespace SharpKeeper
{
    public class Perms
    {
        public static int READ = 1 << 0;
        public static int WRITE = 1 << 1;
        public static int CREATE = 1 << 2;
        public static int DELETE = 1 << 3;
        public static int ADMIN = 1 << 4;
        public int ALL = READ | WRITE | CREATE | DELETE | ADMIN;
    }
}
