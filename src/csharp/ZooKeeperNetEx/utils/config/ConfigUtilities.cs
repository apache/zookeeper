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
using System.Xml;

namespace org.apache.utils {
    /// <summary>
    ///     Utilities class for handling configuration.
    /// </summary>
    public static class ConfigUtilities {

        internal static void ParseTracing(ZooKeeperConfiguration config, XmlElement root) {
            if (root.HasAttribute("DefaultTraceLevel")) {
                config.DefaultTraceLevel = ParseSeverity(root.GetAttribute("DefaultTraceLevel"),
                    "Invalid trace level DefaultTraceLevel attribute value on Tracing element for ZooKeeper Client");
            }

            foreach (XmlNode node in root.ChildNodes) {
                var grandchild = node as XmlElement;
                if (grandchild == null) continue;

                if (grandchild.LocalName.Equals("TraceLevelOverride") && grandchild.HasAttribute("TraceLevel") &&
                    grandchild.HasAttribute("LogPrefix")) {
                        config.TraceLevelOverrides.Add(new Tuple<string, TraceLevel>(grandchild.GetAttribute("LogPrefix"),
                        ParseSeverity(grandchild.GetAttribute("TraceLevel"),
                            "Invalid trace level TraceLevel attribute value on TraceLevelOverride element for ZooKeeper Client prefix " +
                            grandchild.GetAttribute("LogPrefix"))));
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