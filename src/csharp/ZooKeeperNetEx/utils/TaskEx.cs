namespace System.Threading.Tasks
    {
#if !NET40
    public class TaskEx
    {
        public static Task<TResult> FromResult<TResult>(TResult result)
        {
            return Task.FromResult(result);
        }

        public static Task Delay(TimeSpan fromMilliseconds)
        {
            return Task.Delay(fromMilliseconds);
        }

        public static Task Delay(int next)
        {
            return Task.Delay(next);
        }

        public static Task WhenAny(params Task[] tasks)
        {
            return Task.WhenAny(tasks);
        }

        public static Task WhenAll(params Task[] tasks)
        {
            return Task.WhenAll(tasks);
        }
    }

#endif
  
}
