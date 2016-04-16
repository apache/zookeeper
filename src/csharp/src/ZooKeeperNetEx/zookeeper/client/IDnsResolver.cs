using System.Collections.Generic;
using System.Threading.Tasks;

namespace org.apache.zookeeper.client
{
    internal interface IDnsResolver
    {
        Task<IEnumerable<ResolvedEndPoint>> Resolve(IEnumerable<HostAndPort> unresolvedHosts);
    }
}