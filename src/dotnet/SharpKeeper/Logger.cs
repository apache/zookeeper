namespace SharpKeeper
{
    using System;

    public class Logger
    {
        private readonly string name;

        private Logger(string name)
        {
            this.name = name;
        }

        public static Logger getLogger(Type type)
        {
            return new Logger(type.Name);
        }

        public void Error(string msg)
        {
            Print(msg);
        }

        public void Warn(string msg)
        {
            Print(msg);
        }

        public void Warn(string msg, Exception exception)
        {
            Print(msg, exception);
        }

        public void info(string msg)
        {
            Print(msg);
        }

        public bool IsDebugEnabled()
        {
            return true;
        }

        public void Debug(string msg)
        {
            Print(msg);
        }

        public void Debug(string msg, Exception exception)
        {
            Print(msg, exception);
        }

        public void Error(string msg, Exception exception)
        {
            Print(msg, exception);
        }

        private void Print(string msg)
        {
            Console.WriteLine(string.Format("{0} {1} :: {2}", DateTime.Now, name, msg));
        }

        private void Print(string msg, Exception exception)
        {
            Console.WriteLine(string.Format("{0} {1} :: {2}", DateTime.Now, name, msg));
            Console.WriteLine("\t\t" + exception.Message);
            Console.WriteLine("\t\t" + exception.StackTrace);
        }
    }
}