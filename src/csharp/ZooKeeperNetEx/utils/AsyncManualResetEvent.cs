using System.Threading;
using System.Threading.Tasks;

namespace org.apache.utils
{
    //http://blogs.msdn.com/b/pfxteam/archive/2012/02/11/10266920.aspx
    public class AsyncManualResetEvent {
        private volatile TaskCompletionSource<bool> m_tcs = new TaskCompletionSource<bool>();

        public Task WaitAsync() {
            return m_tcs.Task;
        }

        public void Set()
        {
            var tcs = m_tcs;
            Task.Factory.StartNew(s => ((TaskCompletionSource<bool>)s).TrySetResult(true),
                tcs, CancellationToken.None, TaskCreationOptions.PreferFairness, TaskScheduler.Default);
            tcs.Task.Wait();
        }

        public void Reset() {
            while (true) {
                var tcs = m_tcs;
                if (!tcs.Task.IsCompleted ||
#pragma warning disable 420
                    Interlocked.CompareExchange(ref m_tcs,
                    new TaskCompletionSource<bool>(), tcs) == tcs)
                    return;
#pragma warning restore 420
            }
        }
    }
}
