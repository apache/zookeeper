using System;
using Xunit;
using org.apache.utils;
namespace org.apache.zookeeper
{
    public class LogAndConfigTests
    {
        [Fact]
        public void SanityApplicationBasePath()
        {
            Assert.assertFalse(string.IsNullOrWhiteSpace(ZooKeeperLogger.GetApplicationBasePath()));
        }
    }
}
