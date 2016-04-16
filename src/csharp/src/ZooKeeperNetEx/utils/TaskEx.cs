using System.Collections.Generic;

namespace System.Threading.Tasks
{
#if !NET40
    internal class TaskEx
    {
        public static Task<TResult> FromResult<TResult>(TResult result)
        {
            return Task.FromResult(result);
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

        public static Task<T[]> WhenAll<T>(IEnumerable<Task<T>> tasks)
        {
            return Task.WhenAll(tasks);
        }
    }

#endif


    internal static class TaskExtensions
    {
        public static void Ignore(this Task task)
        {
            if (task.IsCompleted)
            {
                var ignored = task.Exception;
            }
            else
            {
                task.ContinueWith(
                    t => { var ignored = t.Exception; },
                    CancellationToken.None,
                    TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously,
                    TaskScheduler.Default);
            }
        }
    }
}
