
using System;

namespace org.apache.zookeeper.client
{
    internal class HostAndPort : IEquatable<HostAndPort>
    {
        public readonly string Host;
        public readonly int Port;

        public HostAndPort(string host, int port)
        {
            Host = host;
            Port = port;
        }

        public override string ToString()
        {
            return $"{Host}:{Port}";
        }

        public bool Equals(HostAndPort other)
        {
            if (ReferenceEquals(null, other)) return false;
            if (ReferenceEquals(this, other)) return true;
            return string.Equals(Host, other.Host) && Port == other.Port;
        }

        public override bool Equals(object obj)
        {
            if (ReferenceEquals(null, obj)) return false;
            if (ReferenceEquals(this, obj)) return true;
            return obj.GetType() == GetType() && Equals((HostAndPort) obj);
        }

        public override int GetHashCode()
        {
            unchecked
            {
                return (Host.GetHashCode()*397) ^ Port;
            }
        }

        public static bool operator ==(HostAndPort left, HostAndPort right)
        {
            return Equals(left, right);
        }

        public static bool operator !=(HostAndPort left, HostAndPort right)
        {
            return !Equals(left, right);
        }
    }
}
