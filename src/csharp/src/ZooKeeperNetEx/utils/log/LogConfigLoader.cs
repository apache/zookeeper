using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Xml.Linq;

namespace org.apache.utils
{
    internal static class LogConfigLoader
    {
        internal static LogConfig LoadFromElement(XElement rootElement)
        {
            var parsedRoot = new SimpleElement(rootElement);
            if (parsedRoot.Name != "ZooKeeperNetEx") throw new FormatException("Root is not ZooKeeperNetEx");
            var logToTrace = GetBool(parsedRoot, "LogToTrace", mandatory: false);
            var logToFile = GetBool(parsedRoot, "LogToFile", mandatory: false);
            var logLevel = GetLogLevel(parsedRoot, mandatory: false);
            var logOverrides = GetLogOverrides(parsedRoot);
            return new LogConfig(logToTrace, logToFile, logLevel, logOverrides);
        }

        private static Dictionary<string, TraceLevel> GetLogOverrides(SimpleElement root)
        {
            var logOverridesElement = GetChild(root, "LogOverrides", mandatory: false);
            if (logOverridesElement == null) return null;
            var overrides = new Dictionary<string, TraceLevel>();
            foreach (var overrideChild in logOverridesElement.Children)
            {
                if (overrideChild.Name != "LogOverride") throw new FormatException("LogOverride");
                var className = GetChild(overrideChild, "ClassName", mandatory: true).Value;
                var logLevel = GetLogLevel(overrideChild, mandatory: true).GetValueOrDefault();
                if (overrides.ContainsKey(className)) throw new FormatException($"{className} is duplicate");
                overrides.Add(className, logLevel);
            }
            return overrides;
        }

        private static bool? GetBool(SimpleElement root, string elementName, bool mandatory)
        {
            var element = GetChild(root, elementName, mandatory);
            if (element == null) return null;
            var v = element.Value;
            if (v == "0" || v == "false") return false;
            if (v == "1" || v == "true") return true;
            throw new FormatException(elementName);
        }

        private static SimpleElement GetChild(SimpleElement root, string elementName, bool mandatory)
        {
            SimpleElement element = root.Children.FirstOrDefault(x => x.Name == elementName);
            if (element == null && mandatory)
                throw new FormatException(elementName);
            return element;
        }

        private static TraceLevel? GetLogLevel(SimpleElement root, bool mandatory)
        {
            var element = GetChild(root, "LogLevel", mandatory);
            if (element == null) return null;
            return (TraceLevel) Enum.Parse(typeof (TraceLevel), element.Value);
        }

        private class SimpleElement
        {
            public readonly List<SimpleElement> Children = new List<SimpleElement>();
            public readonly string Name;
            public readonly string Value;

            public SimpleElement(XElement elementNode)
            {
                if (elementNode == null) throw new ArgumentNullException(nameof(elementNode));
                Name = elementNode.Name.LocalName;
                Value = elementNode.Value;

                foreach (XElement element in elementNode.Elements())
                {
                    Children.Add(new SimpleElement(element));
                }
            }
        }
    }
}