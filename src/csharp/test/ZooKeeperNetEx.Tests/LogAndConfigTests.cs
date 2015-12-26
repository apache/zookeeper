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
            Assert.False(File.Exists(filename));
            Assert.False(writer.HasFailed);
            Assert.True(writer.IsEnabled);
            Assert.True(writer.IsDisposed);
            Assert.True(writer.IsEmpty);
            for (int j = 0; j < 3; j++)
            {
                writer.IsEnabled = true;

                for (int i = 0; i < 50; i++) writer.Write("test" + i);
                await Task.Delay(300);
                Assert.True(File.Exists(filename));

                Assert.False(writer.HasFailed);
                Assert.True(writer.IsEnabled);
                Assert.False(writer.IsDisposed);
                Assert.True(writer.IsEmpty);

                writer.IsEnabled = false;

                for (int i = 0; i < 50; i++) writer.Write("test" + i);
                await Task.Delay(300);
                Assert.False(writer.HasFailed);
                Assert.False(writer.IsEnabled);
                Assert.True(writer.IsDisposed);
                Assert.True(writer.IsEmpty);

                File.Delete(filename);

                writer.Write("should not write");
                await Task.Delay(300);
                Assert.False(writer.HasFailed);
                Assert.False(writer.IsEnabled);
                Assert.True(writer.IsDisposed);
                Assert.True(writer.IsEmpty);
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
                await Task.Delay(500);
                Assert.False(writer.HasFailed);
                Assert.False(writer.IsEnabled);
                Assert.True(writer.IsDisposed);
                Assert.True(writer.IsEmpty);
            }

            writer.IsEnabled = true;
            writer.ThrowWrite = true;
            await Task.Delay(100);
            Assert.True(writer.HasFailed);
            Assert.True(writer.IsEnabled);
            Assert.True(writer.IsDisposed);
            cancellationTokenSource.Cancel();
            await Task.WhenAll(concurrentWrites);
            Assert.True(writer.IsEmpty);

            File.Delete(filename);
            writer.Write("should not write");
            Assert.False(File.Exists(filename));
        }

        private Task CreateWriteTask(CancellationToken cancellationToken, NonBlockingFileWriter writer)
        {
            return Task.Run(async () =>
            {
                int i = 0;
                Random random = new Random();
                while (!cancellationToken.IsCancellationRequested)
                {
                    writer.Write("test" + i);
                    await Task.Delay(random.Next(50) + 10);
                    i++;
                }
            });
        }

        [Fact]
        public void TestCustomConsumer()
        {
            var dummy = new DummyConsumer();
            ILogProducer log = TypeLogger<LogAndConfigTests>.Instance;
            ZooKeeper.CustomLogConsumer = dummy;
            log.warn("test");
            Assert.True(dummy.called);
            dummy.called = false;
            ZooKeeper.CustomLogConsumer = null;
            log.warn("test");
            Assert.False(dummy.called);
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
