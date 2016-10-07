using System.Threading.Tasks;

namespace org.apache.utils
{
    internal class SignalTask
    {
        private readonly Fenced<TaskCompletionSource<bool>> tcs = new Fenced<TaskCompletionSource<bool>>(new TaskCompletionSource<bool>());

        public Task Task => tcs.Value.Task;

        public void Reset()
        {
            tcs.Value = new TaskCompletionSource<bool>();
        }

        public void Set()
        {
            tcs.Value.TrySetResult(true);
        }
    }
}
