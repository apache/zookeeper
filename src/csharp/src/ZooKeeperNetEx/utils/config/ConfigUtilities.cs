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
using System.Diagnostics;
using System.Xml.Linq;

namespace org.apache.utils {
    /// <summary>
    ///     Utilities class for handling configuration.
    /// </summary>
    internal static class ConfigUtilities {

        internal static void ParseTracing(ZooKeeperConfiguration config, XElement root) {
            if (root.Attribute("DefaultTraceLevel")!=null) {
                config.DefaultTraceLevel = ParseSeverity(root.Attribute("DefaultTraceLevel").Value,
                    "Invalid trace level DefaultTraceLevel attribute value on Tracing element for ZooKeeper Client");
            }

            foreach (XElement grandchild in root.Elements()) {
                if (grandchild == null) continue;

                if (grandchild.Name.LocalName.Equals("TraceLevelOverride") && grandchild.Attribute("TraceLevel")!=null &&
                    grandchild.Attribute("LogPrefix")!=null) {
                        config.TraceLevelOverrides.Add(new Tuple<string, TraceLevel>(grandchild.Attribute("LogPrefix").Value,
                        ParseSeverity(grandchild.Attribute("TraceLevel").Value,
                            "Invalid trace level TraceLevel attribute value on TraceLevelOverride element for ZooKeeper Client prefix " +
                            grandchild.Attribute("LogPrefix").Value)));
                }
            }
        }

        private static TraceLevel ParseSeverity(string input, string errorMessage)
        {
            TraceLevel s;
            if (!Enum.TryParse(input, out s)) {
                throw new FormatException(errorMessage + ". Tried to parse " + input);
            }
            return s;
        }
    }
}