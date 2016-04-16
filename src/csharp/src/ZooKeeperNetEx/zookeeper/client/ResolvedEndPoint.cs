using System;
using System.Net;

namespace org.apache.zookeeper.client
{
    internal class ResolvedEndPoint : IPEndPoint, IEquatable<ResolvedEndPoint>
    {
        private readonly string host;

        public ResolvedEndPoint(IPAddress ip, HostAndPort hostAndPort) : this(ip, hostAndPort.Port)
        {
            host = hostAndPort.Host;
        }

        public ResolvedEndPoint(IPAddress ip, int port) : base(ip, port)
        {
        }

        public override string ToString()
        {
            return $"{{{(host == null ? null : $"{host}=>")}{base.ToString()}}}";
        }

        public bool Equals(ResolvedEndPoint other)
        {
            return base.Equals(other);
        }

        public override bool Equals(object obj)
        {
            if (ReferenceEquals(null, obj)) return false;
            if (ReferenceEquals(this, obj)) return true;
            return obj.GetType() == GetType() && Equals((ResolvedEndPoint) obj);
        }

        public override int GetHashCode()
        {
            return base.GetHashCode();
        }

        public static bool operator ==(ResolvedEndPoint left, ResolvedEndPoint right)
        {
            return Equals(left, right);
        }

        public static bool operator !=(ResolvedEndPoint left, ResolvedEndPoint right)
        {
            return !Equals(left, right);
        }
    }
}
