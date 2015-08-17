namespace System.Threading.Tasks
    {
        public class TaskUtils
        {
            public static Task<TResult> FromResult<TResult>(TResult result)
            {
    #if NET4
              return TaskEx.FromResult(result);
    #else
                return Task.FromResult(result);
    #endif
            }

            public static Task Delay(TimeSpan fromMilliseconds)
            {
    #if NET4
              return TaskEx.Delay(fromMilliseconds);
    #else
              return Task.Delay(fromMilliseconds);
    #endif
            }

            public static Task Delay(int next)
            {
    #if NET4
              return TaskEx.Delay(next);
    #else
              return Task.Delay(next);
    #endif
            }
        
            public static Task WhenAny(params Task[] tasks)
            {
    #if NET4
              return TaskEx.WhenAny(tasks);
    #else
              return Task.WhenAny(tasks);
    #endif
            }

            public static Task WhenAll(params Task[] tasks)
            {
    #if NET4
              return TaskEx.WhenAll(tasks);
    #else
              return Task.WhenAll(tasks);
    #endif
            }
    }
}
