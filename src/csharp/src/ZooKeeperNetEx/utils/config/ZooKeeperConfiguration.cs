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
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;
using System.Xml.Linq;

namespace org.apache.utils
{
    /// <summary>
    /// client configuration parameters.
    /// </summary>
    internal class ZooKeeperConfiguration
    {
        private string SourceFile { get; set; }

        public TraceLevel DefaultTraceLevel { get; set; }

        public IList<Tuple<string, TraceLevel>> TraceLevelOverrides { get; private set; }

        const string dateFormat = "yyyy-MM-dd-HH.mm.ss.fffZ";

        public readonly string TraceFileName = string.Format(CultureInfo.InvariantCulture, "ZK.{0}.{1}.log", Dns.GetHostName(),
            DateTime.UtcNow.ToString(dateFormat, CultureInfo.InvariantCulture));

        /// <summary>
        /// </summary>
        public ZooKeeperConfiguration()
        {
            DefaultTraceLevel = TraceLevel.Warning;
            TraceLevelOverrides = new List<Tuple<string, TraceLevel>>();
        }

        private void LoadFromXml(XElement root)
        {
            foreach (XElement child in root.Elements())
            {
                if (child != null)
                {
                    switch (child.Name.LocalName)
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
            if (fileName == null) throw new ArgumentNullException("fileName");

            var config = new ZooKeeperConfiguration();
            string xml = File.ReadAllText(fileName);
            XElement root = XDocument.Load(xml).Root;

            config.LoadFromXml(root);
            config.SourceFile = fileName;
            return config;
        }

        /// <summary/>
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
