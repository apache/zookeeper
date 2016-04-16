using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using org.apache.utils;
// ReSharper disable PossibleMultipleEnumeration

namespace org.apache.zookeeper.client
{
    internal class DnsResolver : IDnsResolver
    {
        private const int DNS_TIMEOUT = 10000;
        private readonly ILogProducer log;

        public DnsResolver(ILogProducer log)
        {
            this.log = log;
        }

        /// <summary>
        /// The method "Dns.GetHostAddressesAsync" doesn't exist in .NET 4
        /// But, it isn't really async in .NET 4.5 and up. The internal implementation
        /// just queues a blocking call on the thread pool. see: http://stackoverflow.com/questions/11480742/dns-begingethost-methods-blocking
        /// Therefore, the .NET 4 implementation here is equivalent to "Dns.GetHostAddressesAsync".
        /// In .NET Core the implementation is OS specific, so real async implementations
        /// might exist.
        /// </summary>
        private Task<IPAddress[]> GetHostAddressesAsync(string host)
        {
#if NET40
            return Task.Factory.StartNew(() => Dns.GetHostAddresses(host), System.Threading.CancellationToken.None,
                TaskCreationOptions.PreferFairness, TaskScheduler.Default);
#else
            return Dns.GetHostAddressesAsync(host);
#endif
        }

        private async Task<IEnumerable<ResolvedEndPoint>> Resolve(HostAndPort hostAndPort)
        {
            string host = hostAndPort.Host;
            log.debug($"Resolving Host={host}");
            var dnsTimeoutTask = TaskEx.Delay(DNS_TIMEOUT);
            var dnsResolvingTask = GetHostAddressesAsync(host);
            await TaskEx.WhenAny(dnsResolvingTask, dnsTimeoutTask).ConfigureAwait(false);
            if (dnsTimeoutTask.IsCompleted)
            {
                dnsResolvingTask.Ignore();
                log.warn($"Timeout of {DNS_TIMEOUT}ms elapsed when resolving Host={host}");
            }
            else
            {
                try
                {
                    var allResolvedIPs = await dnsResolvingTask.ConfigureAwait(false);
                    var resolvedIPv4s = allResolvedIPs.Where(ip => ip.AddressFamily == AddressFamily.InterNetwork);
                    log.debug($"Resolved Host={host} to {{{resolvedIPv4s.ToCommaDelimited()}}}");
                    return resolvedIPv4s.Select(ip => new ResolvedEndPoint(ip, hostAndPort));
                }
                catch (Exception e)
                {
                    log.error($"Failed resolving Host={host}", e);
                }
            }
            return Enumerable.Empty<ResolvedEndPoint>();
        }

        public async Task<IEnumerable<ResolvedEndPoint>> Resolve(IEnumerable<HostAndPort> unresolvedHosts)
        {
            var unresolvedForLog = unresolvedHosts.ToCommaDelimited();
            log.debug($"Resolving Hosts={{{unresolvedForLog}}}");
            var resolved = new List<ResolvedEndPoint>();
            var resolvingTasks = new List<Task<IEnumerable<ResolvedEndPoint>>>();
            foreach (var hostAndPort in unresolvedHosts)
            {
                IPAddress ip;
                if (IPAddress.TryParse(hostAndPort.Host, out ip))
                {
                    resolved.Add(new ResolvedEndPoint(ip, hostAndPort.Port));
                }
                else
                {
                    resolvingTasks.Add(Resolve(hostAndPort));
                }
            }
            var resolvedTasks = (await TaskEx.WhenAll(resolvingTasks).ConfigureAwait(false)).SelectMany(i => i);
            resolved.AddRange(resolvedTasks);
            var res = resolved.Distinct();
            log.debug($"Resolved Hosts={{{unresolvedForLog}}} to {{{res.ToCommaDelimited()}}}");
            return res;
        }
    }
}
