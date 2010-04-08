using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using NUnit.Framework;

namespace SharpKeeper.Tests
{
    [SetUpFixture]
    public class AssemblyFixture
    {
        private ZooKeeperServer server;

        [SetUp]
        public void Setup()
        {
            server = new ZooKeeperServer();
        }

        [TearDown]
        public void Teardown()
        {
            server.Dispose();
        }

        public class ZooKeeperServer : IDisposable
        {
            private Process proc;

            public ZooKeeperServer()
            {
                StartRemoteServer();
            }

            public bool IsAlive
            {
                get
                {
                    return proc.HasExited == false;
                }
            }

            public void AssertIsAlive()
            {
                if (IsAlive)
                    return;

                throw new InvalidOperationException("Server process is dead!");
            }

            private void StartRemoteServer()
            {
                var currentDirectory = AppDomain.CurrentDomain.BaseDirectory;
                while (!Directory.Exists(Path.Combine(currentDirectory, "bin")) || !File.Exists(Path.Combine(Path.Combine(currentDirectory, "bin"), "zkServer.cmd"))) 
                {
                    currentDirectory = Directory.GetParent(currentDirectory).ToString();
                }
                var configDir = Path.Combine(currentDirectory, "conf");
                currentDirectory = Path.Combine(currentDirectory, "bin");
                
                if (!File.Exists(Path.Combine(configDir, "zoo.cfg")))
                    File.Copy(Path.Combine(configDir, "zoo_sample.cfg"), Path.Combine(configDir, "zoo.cfg"));

                try
                {
                    proc = new Process
                    {
                        StartInfo =
                        {
                            WorkingDirectory = currentDirectory,
                            WindowStyle = ProcessWindowStyle.Hidden,
                            FileName = Path.Combine(currentDirectory, "zkServer.cmd"),
                            UseShellExecute = false,
                            RedirectStandardError = true,
                            RedirectStandardOutput = true
                        }
                    };

                    bool bound = false;
                    proc.OutputDataReceived += (sender, e) =>
                    {
                        Console.WriteLine(e.Data);
                        if (e.Data.Contains("binding to port 0.0.0.0/0.0.0.0:2181")) bound = true;
                    };

                    proc.Start();
                    proc.BeginOutputReadLine();

                    ManualResetEvent reset = new ManualResetEvent(false);
                    ThreadPool.QueueUserWorkItem((s) => 
                    {
                        while (!bound) {}
                        reset.Set();
                    });
                    if (!reset.WaitOne(10000)) throw new InvalidOperationException("Could not start ZooKeeper server.  Check stderr.");
                }
                catch (Exception e)
                {
                    Console.WriteLine(e.Message);
                    Console.WriteLine(e.StackTrace);
                }
            }

            public void Dispose()
            {
                proc.Kill();
            }
        }
    }

    [TestFixture]
    public class ZooKeeperIntegrationTests : Watcher
    {
        [Test]
        public void StartServer()
        {
            ZooKeeper zk = new ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 3), this);
            for (int i = 0; i < 100; i++)
            {
                string path = "/" + i;
                zk.Create(path, Encoding.UTF8.GetBytes(path), Ids.OPEN_ACL_UNSAFE, CreateMode.Persistent);
            }
        }

        public void process(WatchedEvent @event)
        {            
        }
    }
}
