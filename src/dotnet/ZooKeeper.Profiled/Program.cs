using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using ZooKeeperNet.Tests;

namespace ZooKeeper.Profiled
{
    class Program
    {
        static void Main(string[] args)
        {
            log4net.Config.XmlConfigurator.Configure();
            ACLRootTests aclTest = new ACLRootTests();
            Console.WriteLine("ACL Tests");
            aclTest.testRootAcl();

            Console.WriteLine("Chroot Tests");
            ChrootTests chrootTest = new ChrootTests();
            chrootTest.testChrootSynchronous();

            Console.WriteLine("Client Tests"); 
            ClientTests clientTest = new ClientTests();
            clientTest.testACLs();
            clientTest.testClientWithoutWatcherObj();
            clientTest.testClientWithWatcherObj();
            clientTest.testDeleteWithChildren();
            clientTest.testMutipleWatcherObjs();
            clientTest.testPathValidation();
            clientTest.testPing();
            clientTest.testSequentialNodeData();
            clientTest.testSequentialNodeNames();

            Console.WriteLine("GetChildren2 Tests");
            GetChildren2Tests getTest = new GetChildren2Tests();
            getTest.Setup();
            getTest.testChild();
            getTest.Teardown();
            
            getTest.Setup();
            getTest.testChildren();
            getTest.Teardown();

            Console.WriteLine("NullData Tests"); 
            NullDataTests nullTest = new NullDataTests();
            nullTest.testNullData();

            Console.WriteLine("Socket Tests");
            SocketTests socketTest = new SocketTests();
            socketTest.CanReadAndWriteOverASingleFrame();
            socketTest.CanReadAndWriteOverManyFrames();
            socketTest.CanReadAndWriteOverTwoFrames();

            Console.WriteLine("Stat Tests");
            StatTests statTest = new StatTests();
            statTest.SetUp();
            statTest.testBasic();
            statTest.TearDown();

            statTest.SetUp();
            statTest.testChild();
            statTest.TearDown();

            statTest.SetUp();
            statTest.testChildren();
            statTest.TearDown();
            
            statTest.SetUp();
            statTest.testDataSizeChange();
            statTest.TearDown();

            statTest.SetUp();
            statTest.testDeleteAllNodeExceptPraweda();
            statTest.TearDown();
        }

        //protected static global::ZooKeeperNet.ZooKeeper CreateClient()
        //{
        //    return new global::ZooKeeperNet.ZooKeeper("127.0.0.1:2181", new TimeSpan(0, 0, 0, 10000), null);
        //}

        //private static void DeleteChild(global::ZooKeeperNet.ZooKeeper zk, string path)
        //{
        //    if (!string.IsNullOrEmpty(path) && !path.Contains("praweda") && !path.Contains("zookeeper"))
        //    {
        //        var lstChild = zk.GetChildren(path, false);
        //        foreach (var child in lstChild)
        //        {
        //            if (path != "/")
        //                DeleteChild(zk, path + "/" + child);
        //            else
        //                DeleteChild(zk, "/" + child);
        //        }
        //        if (path != "/")
        //            zk.Delete(path, -1);
        //    }
        //}

    }
}
