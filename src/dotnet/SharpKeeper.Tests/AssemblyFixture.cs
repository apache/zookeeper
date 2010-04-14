namespace SharpKeeper.Tests
{
    using System;
    using System.Collections.Generic;
    using System.Diagnostics;
    using System.IO;
    using System.Runtime.CompilerServices;
    using System.Threading;
    using java.io;
    using java.lang;
    using java.net;
    using NUnit.Framework;
    using org.apache.zookeeper.server;
    using Console = System.Console;
    using Exception = System.Exception;
    using File = System.IO.File;
    using IOException = System.IO.IOException;
    using Process = System.Diagnostics.Process;
    using String = System.String;

    [SetUpFixture]
    public class AssemblyFixture
    {
        private IDisposable server;

        [SetUp]
        public void Setup()
        {
            server = new IKvmServer();
        }

        [TearDown]
        public void Teardown()
        {
            server.Dispose();
        }

        public class IKvmServer : IDisposable
        {
            protected String hostPort = "127.0.0.1:" + PortAssignment.unique();
            protected int maxCnxns = 0;
            protected NIOServerCnxn.Factory serverFactory = null;
            protected static int CONNECTION_TIMEOUT = 30000;
            private NIOServerCnxn.Factory factory;

            public IKvmServer()
            {
                StartServer();
            }

            private void StartServer()
            {
                try
                {
                    ZooKeeperServer zks = new ZooKeeperServer(new java.io.File(@"D:\temp"), new java.io.File(@"D:\temp"),
                                                              3000);
                    int PORT = GetPort(hostPort);
                    if (factory == null)
                    {
                        factory = new NIOServerCnxn.Factory(new InetSocketAddress(PORT), maxCnxns);
                    }
                    factory.startup(zks);
                    Assert.True(waitForServerUp("127.0.0.1:" + PORT, CONNECTION_TIMEOUT), "waiting for server up");
                } 
                catch (Throwable t)
                {
                    Console.WriteLine(t);    
                }
            }

            private static int GetPort(String hostPort)
            {
                String[] split = hostPort.Split(':');
                String portstr = split[split.Length - 1];
                String[] pc = portstr.Split('/');
                if (pc.Length > 1)
                {
                    portstr = pc[0];
                }
                return Integer.parseInt(portstr);
            }

            public static bool waitForServerUp(String hp, long timeout)
            {
                long start = System.currentTimeMillis();
                while (true)
                {
                    try
                    {
                        // if there are multiple hostports, just take the first one
                        HostPort hpobj = parseHostPortList(hp)[0];
                        String result = send4LetterWord(hpobj.host, hpobj.port, "stat");
                        if (result.StartsWith("Zookeeper version:"))
                        {
                            return true;
                        }
                    }
                    catch (IOException e)
                    {
                        // ignore as this is expected
                    }

                    if (System.currentTimeMillis() > start + timeout)
                    {
                        break;
                    }
                    try
                    {
                        java.lang.Thread.sleep(250);
                    }
                    catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
                return false;
            }

            public static List<HostPort> parseHostPortList(String hplist)
            {
                List<HostPort> alist = new List<HostPort>();
                foreach (String hp in hplist.Split(','))
                {
                    int idx = hp.LastIndexOf(':');
                    String host = hp.Substring(0, idx);
                    int port;
                    try
                    {
                        port = Integer.parseInt(hp.Substring(idx + 1));
                    }
                    catch (RuntimeException e)
                    {
                        throw new RuntimeException("Problem parsing " + hp + e.toString());
                    }
                    alist.Add(new HostPort(host, port));
                }
                return alist;
            }

            public static String send4LetterWord(String host, int port, String cmd)
            {
                Socket sock = new Socket(host, port);
                BufferedReader reader = null;
                try
                {
                    OutputStream outstream = sock.getOutputStream();
                    outstream.write(cmd.GetBytes());
                    outstream.flush();

                    reader =
                        new BufferedReader(
                            new InputStreamReader(sock.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        sb.append(line + "\n");
                    }
                    return sb.toString();
                }
                finally
                {
                    sock.close();
                    if (reader != null)
                    {
                        reader.close();
                    }
                }
            }

            public static bool waitForServerDown(String hp, long timeout)
            {
                long start = System.currentTimeMillis();
                while (true)
                {
                    try
                    {
                        HostPort hpobj = parseHostPortList(hp)[0];
                        send4LetterWord(hpobj.host, hpobj.port, "stat");
                    }
                    catch (IOException e)
                    {
                        return true;
                    }

                    if (System.currentTimeMillis() > start + timeout)
                    {
                        break;
                    }
                    try
                    {
                        java.lang.Thread.sleep(250);
                    }
                    catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
                return false;
            }

            static void shutdownServerInstance(NIOServerCnxn.Factory factory, String hostPort)
            {
                if (factory != null)
                {
                    ZKDatabase zkDb = factory.getZooKeeperServer().getZKDatabase();
                    factory.shutdown();
                    try
                    {
                        zkDb.close();
                    }
                    catch (IOException ie)
                    {
                        //LOG.warn("Error closing logs ", ie);
                    }
                    int PORT = GetPort(hostPort);

                    Assert.True(waitForServerDown("127.0.0.1:" + PORT, CONNECTION_TIMEOUT), "waiting for server down");
                }
            }

            public void Dispose()
            {
                shutdownServerInstance(serverFactory, hostPort);
                serverFactory = null;
            }

            public class HostPort
            {
                public String host;
                public int port;
                public HostPort(String host, int port)
                {
                    this.host = host;
                    this.port = port;
                }
            }

            public class PortAssignment
            {
                private static Logger LOG = Logger.getLogger(typeof(PortAssignment));

                private static int nextPort = 11221;

                /** Assign a new, unique port to the test */
                [MethodImpl(MethodImplOptions.Synchronized)]
                public static int unique()
                {
                    LOG.Info("assigning port " + nextPort);
                    return nextPort++;
                }
            }
        }

        public class CommandLineZooKeeperServer : IDisposable
        {
            private Process proc;

            public CommandLineZooKeeperServer()
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
                        try
                        {
                            Console.WriteLine(e.Data);
                            if (e.Data.Contains("binding to port 0.0.0.0/0.0.0.0:2181")) bound = true;
                        }
                        catch
                        {                            
                        }
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
                proc.CancelOutputRead();
                proc.CloseMainWindow();
            }
        }
    }
}