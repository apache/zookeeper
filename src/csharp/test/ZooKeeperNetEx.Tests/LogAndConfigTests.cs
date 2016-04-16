using System;
using Xunit;
using org.apache.utils;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using org.apache.zookeeper;
using Assert = Xunit.Assert;

namespace ZooKeeperNetEx.Tests
{
    public class LogAndConfigTests
    {
        [Fact]
        public async Task TestFileWriter()
        {
            string filename = Guid.NewGuid().ToString();
            NonBlockingFileWriter writer = new NonBlockingFileWriter(filename);
            await AssertInTimeout(() => File.Exists(filename) == false);
            await AssertInTimeout(() => writer.HasFailed == false);
            await AssertInTimeout(() => writer.IsEnabled);
            await AssertInTimeout(() => writer.IsDisposed);
            await AssertInTimeout(() => writer.IsEmpty);
            for (int j = 0; j < 3; j++)
            {
                writer.IsEnabled = true;

                for (int i = 0; i < 50; i++) writer.Write("test" + i);
                await AssertInTimeout(() => File.Exists(filename));
                await AssertInTimeout(() => writer.HasFailed == false);
                await AssertInTimeout(() => writer.IsEnabled);
                await AssertInTimeout(() => writer.IsDisposed == false);
                await AssertInTimeout(() => writer.IsEmpty);
                
                writer.IsEnabled = false;

                for (int i = 0; i < 50; i++) writer.Write("test" + i);
                await AssertInTimeout(() => writer.HasFailed == false);
                await AssertInTimeout(() => writer.IsEnabled == false);
                await AssertInTimeout(() => writer.IsDisposed);
                await AssertInTimeout(() => writer.IsEmpty);

                File.Delete(filename);

                writer.Write("should not write");
                await AssertInTimeout(() => writer.HasFailed == false);
                await AssertInTimeout(() => writer.IsEnabled == false);
                await AssertInTimeout(() => writer.IsDisposed);
                await AssertInTimeout(() => writer.IsEmpty);
            }
            var cancellationTokenSource = new CancellationTokenSource();
            var token = cancellationTokenSource.Token;
            var concurrentWrites = new[]
            {
                CreateWriteTask(token, writer), CreateWriteTask(token, writer), CreateWriteTask(token, writer),
                CreateWriteTask(token, writer)
            };

            for (int i = 0; i < 3; i++)
            {
                writer.IsEnabled = true;
                await Task.Delay(50);
                writer.IsEnabled = false;
                await AssertInTimeout(() => writer.HasFailed == false);
                await AssertInTimeout(() => writer.IsEnabled == false);
                await AssertInTimeout(() => writer.IsDisposed);
                await AssertInTimeout(() => writer.IsEmpty);
            }

            writer.IsEnabled = true;
            writer.ThrowWrite = true;
            await AssertInTimeout(() => writer.HasFailed);
            await AssertInTimeout(() => writer.IsEnabled);
            await AssertInTimeout(() => writer.IsDisposed);
            cancellationTokenSource.Cancel();
            await Task.WhenAll(concurrentWrites);
            await AssertInTimeout(() => writer.IsEmpty);

            File.Delete(filename);
            writer.Write("should not write");
            Assert.False(File.Exists(filename));
        }

        private async Task AssertInTimeout(Func<bool> assertion)
        {
            for (int i = 0; i < 300 && !assertion(); i++)
                await Task.Delay(100);

            Assert.True(assertion());
        }

        private Task CreateWriteTask(CancellationToken cancellationToken, NonBlockingFileWriter writer)
        {
            return Task.Run(async () =>
            {
                int i = 0;
                while (!cancellationToken.IsCancellationRequested)
                {
                    writer.Write("test" + i);
                    await Task.Delay(i%50 + 10);
                    i++;
                }
            });
        }

        [Fact]
        public void TestCustomConsumer()
        {
            bool logToFile = ZooKeeper.LogToFile;
            ZooKeeper.LogToFile = false;
            var dummy = new DummyConsumer();
            ILogProducer log = TypeLogger<LogAndConfigTests>.Instance;
            ZooKeeper.CustomLogConsumer = dummy;
            log.warn("test");
            Assert.True(dummy.called);
            dummy.called = false;
            ZooKeeper.CustomLogConsumer = null;
            log.warn("test");
            Assert.False(dummy.called);
            ZooKeeper.LogToFile = logToFile;
        }

        private class DummyConsumer : ILogConsumer
        {
            public bool called;
            public void Log(TraceLevel severity, string className, string message, Exception exception)
            {
                if (className == "LogAndConfigTests")
                    called = true;
            }
        }
    }
}
