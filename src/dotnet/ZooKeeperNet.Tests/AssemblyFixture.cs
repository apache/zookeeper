/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
namespace ZooKeeperNet.Tests
{
    using System;
    using System.Diagnostics;
    using System.IO;
    using System.Threading;
    using NUnit.Framework;

    [SetUpFixture]
    public class AssemblyFixture
    {
        private IDisposable server;

        //[SetUp]
        public void Setup()
        {
            server = new CommandLineZooKeeperServer();
        }

        //[TearDown]
        public void Teardown()
        {
            server.Dispose();
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
                proc.CancelErrorRead();
                if (!proc.CloseMainWindow()) proc.Kill();                
            }
        }
    }
}
