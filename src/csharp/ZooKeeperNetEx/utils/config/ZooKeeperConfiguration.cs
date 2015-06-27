/*
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and 
associated documentation files (the ""Software""), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Xml;

namespace org.apache.utils
{
    /// <summary>
    /// client configuration parameters.
    /// </summary>
    public class ZooKeeperConfiguration
    {
        private string SourceFile { get; set; }

        public TraceLevel DefaultTraceLevel { get; set; }

        public IList<Tuple<string, TraceLevel>> TraceLevelOverrides { get; private set; }

        const string dateFormat = "yyyy-MM-dd-HH.mm.ss.fffZ";

        public readonly string TraceFileName = String.Format("ZK.{0}.{1}.log", Dns.GetHostName(), DateTime.UtcNow.ToString(dateFormat));

        /// <summary>
        /// </summary>
        public ZooKeeperConfiguration()
        {
            DefaultTraceLevel = TraceLevel.Warning;
            TraceLevelOverrides = new List<Tuple<string, TraceLevel>>();
        }

        private void Load(TextReader input)
        {
            var xml = new XmlDocument();
            var xmlReader = XmlReader.Create(input);
            xml.Load(xmlReader);
            XmlElement root = xml.DocumentElement;

            LoadFromXml(root);
        }

        private void LoadFromXml(XmlElement root)
        {
            foreach (XmlNode node in root.ChildNodes)
            {
                var child = node as XmlElement;
                if (child != null)
                {
                    switch (child.LocalName)
                    {
                        case "Tracing":
                            ConfigUtilities.ParseTracing(this, child);
                            break;
                    }
                }
            }
        }

        /// <summary>
        /// </summary>
        public static ZooKeeperConfiguration LoadFromFile(string fileName)
        {
            if (fileName == null) return null;

            TextReader input = null;
            try
            {
                var config = new ZooKeeperConfiguration();
                input = File.OpenText(fileName);
                config.Load(input);
                config.SourceFile = fileName;
                return config;
            }
            finally
            {
                if (input != null) input.Dispose();
            }
        }

        public override string ToString()
        {
            var sb = new StringBuilder();
            sb.Append("   Host: ").AppendLine(Dns.GetHostName());
            sb.Append("   Processor Count: ").Append(Environment.ProcessorCount).AppendLine();

            sb.AppendLine("Client Configuration:");
            sb.Append("   Config File Name: ").AppendLine(string.IsNullOrEmpty(SourceFile) ? "" : Path.GetFullPath(SourceFile));
            sb.Append("   Start time: ").AppendLine(TraceLogger.PrintDate(DateTime.UtcNow));
            
            return sb.ToString();
        }
    }
}
