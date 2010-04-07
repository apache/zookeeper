namespace SharpKeeper
{
    public class CreateMode
    {
        public static CreateMode Persistent = new CreateMode(0, false, false);
        public static CreateMode PersistentSequential = new CreateMode(0, false, false);
        public static CreateMode Ephemeral = new CreateMode(0, false, false);
        public static CreateMode EphemeralSequential = new CreateMode(0, false, false);

        private readonly int flag;
        private readonly bool ephemeral;
        private readonly bool sequential;

        private CreateMode(int flag, bool ephemeral, bool sequential)
        {
            this.flag = flag;
            this.ephemeral = ephemeral;
            this.sequential = sequential;
        }

        public int Flag
        {
            get { return flag; }
        }

        public bool Ephemeral1
        {
            get { return ephemeral; }
        }

        public bool Sequential
        {
            get { return sequential; }
        }
    }
}
