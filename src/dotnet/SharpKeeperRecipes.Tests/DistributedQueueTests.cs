using System;

namespace SharpKeeperRecipes.Tests
{
    using System.Text;
    using NUnit.Framework;
    using SharpKeeper;
    using SharpKeeper.Recipes;
    using SharpKeeper.Tests;

    [TestFixture]
    public class DistributedQueueTests : AbstractZooKeeperTests
    {

        [Test]
        public void testOffer1()
        {
            String dir = "/testOffer1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(Encoding.UTF8.GetBytes(testString));

            byte[] dequeuedBytes = queueHandles[0].Dequeue();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testOffer2()
        {
            String dir = "/testOffer2" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 2;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(Encoding.UTF8.GetBytes(testString));

            byte[] dequeuedBytes = queueHandles[1].Dequeue();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testTake1()
        {
            String dir = "/testTake1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            queueHandles[0].Enqueue(testString.GetBytes());

            byte[] dequeuedBytes = queueHandles[0].Take();
            Assert.AreEqual(Encoding.UTF8.GetString(dequeuedBytes), testString);
        }

        [Test]
        public void testRemove1()
        {
            String dir = "/testRemove1" + Guid.NewGuid();
            String testString = "Hello World";
            int num_clients = 1;
            ZooKeeper[] clients = new ZooKeeper[num_clients];
            DistributedQueue[] queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            try
            {
                queueHandles[0].Dequeue();
            }
            catch (NoSuchElementException e)
            {
                return;
            }
            Assert.Fail();
        }


        public void createNremoveMtest(String dir, int n, int m)
        {
            String testString = "Hello World";
            int num_clients = 2;
            var clients = new ZooKeeper[num_clients];
            var queueHandles = new DistributedQueue[num_clients];
            for (int i = 0; i < clients.Length; i++)
            {
                clients[i] = CreateClient();
                queueHandles[i] = new DistributedQueue(clients[i], dir, null);
            }

            for (int i = 0; i < n; i++)
            {
                var offerString = testString + i;
                queueHandles[0].Enqueue(offerString.GetBytes());
            }

            byte[] data = null;
            for (int i = 0; i < m; i++)
            {
                data = queueHandles[1].Dequeue();
            }
            Assert.AreEqual(testString + (m - 1), Encoding.UTF8.GetString(data));
        }

        [Test]
        public void testRemove2()
        {
            createNremoveMtest("/testRemove2" + Guid.NewGuid(), 10, 2);
        }

        [Test]
        public void testRemove3()
        {
            createNremoveMtest("/testRemove3" + Guid.NewGuid(), 1000, 1000);
        }

        /*
    public void createNremoveMelementTest(String dir,int n,int m){
        String testString = "Hello World";
        stringint num_clients = 2;
        ZooKeeper clients[] = new ZooKeeper[num_clients];
        DistributedQueue queueHandles[] = new DistributedQueue[num_clients];
        for(int i=0; i < clients.length; i++){
            clients[i] = createClient();
            queueHandles[i] = new DistributedQueue(clients[i], dir, null);
        }

        for(int i=0; i< n; i++){
            String offerString = testString + i;
            queueHandles[0].offer(offerString.getBytes());
        }

        byte data[] = null;
        for(int i=0; i<m; i++){
            data=queueHandles[1].remove();
        }
        Assert.AreEqual(new String(queueHandles[1].element()), testString+m);
    }

    public void testElement1() {
        createNremoveMelementTest("/testElement1",1,0);
    }

    public void testElement2() {
        createNremoveMelementTest("/testElement2",10,2);
    }

    public void testElement3() {
        createNremoveMelementTest("/testElement3",1000,500);
    }

    public void testElement4() {
        createNremoveMelementTest("/testElement4",1000,1000-1);
    }

    public void testTakeWait1(){
        String dir = "/testTakeWait1";
        stringString testString = "Hello World";
        stringint num_clients = 1;
        stringZooKeeper clients[] = new ZooKeeper[num_clients];
        stringDistributedQueue queueHandles[] = new DistributedQueue[num_clients];
        for(int i=0; i < clients.length; i++){
            clients[i] = createClient();
            queueHandles[i] = new DistributedQueue(clients[i], dir, null);
        }

        stringbyte[] takeResult[] = new byte[1][];
        Thread takeThread = new Thread(){
            public void run(){
                try{
                    takeResult[0] = queueHandles[0].take();
                }catch(KeeperException e){

                }catch(InterruptedException e){

                }
            }
        };
        takeThread.start();

        Thread.sleep(1000);
        Thread offerThread= new Thread() {
            public void run(){
                try {
                    queueHandles[0].offer(testString.getBytes());
                } catch (KeeperException e) {

                } catch (InterruptedException e) {

                }
            }
        };
        offerThread.start();
        offerThread.join();

        takeThread.join();

        assertTrue(takeResult[0] != null);
        Assert.AreEqual(new String(takeResult[0]), testString);
    }

    public void testTakeWait2(){
        String dir = "/testTakeWait2";
        stringString testString = "Hello World";
        stringint num_clients = 1;
        stringZooKeeper clients[] = new ZooKeeper[num_clients];
        stringDistributedQueue queueHandles[] = new DistributedQueue[num_clients];
        for(int i=0; i < clients.length; i++){
            clients[i] = createClient();
            queueHandles[i] = new DistributedQueue(clients[i], dir, null);
        }
        int num_attempts =2;
        for(int i=0; i< num_attempts; i++){
            stringbyte[] takeResult[] = new byte[1][];
            stringString threadTestString = testString + i;
            Thread takeThread = new Thread(){
                public void run(){
                    try{
                        takeResult[0] = queueHandles[0].take();
                    }catch(KeeperException e){

                    }catch(InterruptedException e){

                    }
                }
            };
            takeThread.start();

            Thread.sleep(1000);
            Thread offerThread= new Thread() {
                public void run(){
                    try {
                        queueHandles[0].offer(threadTestString.getBytes());
                    } catch (KeeperException e) {

                    } catch (InterruptedException e) {

                    }
                }
            };
            offerThread.start();
            offerThread.join();

            takeThread.join();

            assertTrue(takeResult[0] != null);
            Assert.AreEqual(new String(takeResult[0]), threadTestString);
        }
    }
         */
    }
}
