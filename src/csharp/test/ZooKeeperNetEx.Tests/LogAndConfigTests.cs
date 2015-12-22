using System;
using System.Linq;
using System.Xml.Linq;
using Xunit;
using org.apache.utils;
using System.Diagnostics;
using org.apache.zookeeper;
using Assert = Xunit.Assert;

namespace ZooKeeperNetEx.Tests
{
    public class LogAndConfigTests
    {
        private const string configXml =
            @"<ZooKeeperNetEx xmlns='urn:zookeeper'>
               <LogToTrace>true</LogToTrace>
               <LogToFile>false</LogToFile>
               <LogLevel>Warning</LogLevel>
               <LogOverrides>
                <LogOverride>
                 <ClassName>ZooKeeper</ClassName>
                 <LogLevel>Verbose</LogLevel>
                </LogOverride>
                <LogOverride>
                 <ClassName>ClientCnxn</ClassName>
                 <LogLevel>Warning</LogLevel>
                </LogOverride>
               </LogOverrides>
              </ZooKeeperNetEx>";
        
        [Fact]
        public void SanityApplicationBasePath()
        {
            Assert.False(string.IsNullOrWhiteSpace(ZooKeeperLogger.GetApplicationBasePath()));
        }

        [Fact]
        public void TestWrongValues()
        {
            var configXDocument = XDocument.Parse(configXml);
            configXDocument.Root.Name = "bla";
            TestThrows<FormatException>(configXDocument);

            configXDocument = XDocument.Parse(configXml);
            SetElementValue(configXDocument, "LogToTrace", "bla");
            TestThrows<FormatException>(configXDocument);

            configXDocument = XDocument.Parse(configXml);
            SetElementValue(configXDocument, "LogToFile", "bla");
            TestThrows<FormatException>(configXDocument);

            configXDocument = XDocument.Parse(configXml);
            SetElementValue(configXDocument, "LogLevel", "bla");
            TestThrows<ArgumentException>(configXDocument);
        }

        [Fact]
        public void TestAddConsumer()
        {
            var dummy = new DummyConsumer();
            ILogProducer log = TypeLogger<LogAndConfigTests>.Instance;
            ZooKeeper.AddLogConsumer(dummy);
            log.warn("test");
            Assert.True(dummy.called);
        }

        [Fact]
        public void TestParse()
        {
            var configXDocument = XDocument.Parse(configXml);
            var logConfig = LogConfigLoader.LoadFromElement(configXDocument.Root);
            Assert.Equal(logConfig.LogLevel, TraceLevel.Warning);
            Assert.Equal(logConfig.LogToTrace, true);
            Assert.Equal(logConfig.LogToFile, false);
            Assert.Equal(logConfig.LogOverrides.Count, 2);
            Assert.Equal(logConfig.LogOverrides["ZooKeeper"], TraceLevel.Verbose);
            Assert.Equal(logConfig.LogOverrides["ClientCnxn"], TraceLevel.Warning);
        }

        [Fact]
        public void TestMandatory()
        {
            var configXDocument = XDocument.Parse(configXml);
            configXDocument.Root.Descendants().First(x => x.Name.LocalName == "ClassName").Remove();
            TestThrows<FormatException>(configXDocument);

            configXDocument = XDocument.Parse(configXml);
            configXDocument.Root.Descendants().Last(x => x.Name.LocalName == "LogLevel").Remove();
            TestThrows<FormatException>(configXDocument);
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

        private void TestThrows<T>(XDocument xDocument) where T : Exception
        {
            Assert.Throws<T>(() => LogConfigLoader.LoadFromElement(xDocument.Root));
        }

        private void SetElementValue(XDocument xDocument, string elementName, string value)
        {
            xDocument.Root.Elements().First(x => x.Name.LocalName == elementName).Value = value;
        }
    }
}
