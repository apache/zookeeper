using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Xml.Linq;

namespace org.apache.utils.log
{
    internal static class LogConfigLoader
    {
        internal static LogConfig LoadFromFileOrDefault()
        {
            try
            {
                var dir = GetAssemblyLocation();
                Trace.TraceInformation($"ZooKeeperNetEx directory is: {dir}");
                var logfileText = File.ReadAllText($"{dir}{Path.DirectorySeparatorChar}ZooKeeperNetEx.config");
                Trace.TraceInformation($"Configuration Loaded:{Environment.NewLine}{logfileText}");
                var rootElement = XDocument.Parse(logfileText).Root;
                var parsedRoot = new SimpleElement(rootElement);
                if (parsedRoot.Name != "ZooKeeperNetEx") throw new FormatException("Root is not ZooKeeperNetEx");
                var logToTrace = GetBool(parsedRoot, "LogToTrace", mandatory: false);
                var logToFile = GetBool(parsedRoot, "LogToFile", mandatory: false);
                var logLevel = GetLogLevel(parsedRoot, mandatory: false);
                var logOverrides = GetLogOverrides(parsedRoot);
                return new LogConfig(logToTrace, logToFile, logLevel, logOverrides);
            }
            catch (Exception e)
            {
                Trace.TraceError("Error opening log configuration file, using defaults. Exception:" + e);
                return LogConfig.Default;
            }
        }

        private static LogOverride[] GetLogOverrides(SimpleElement root)
        {
            var logOverridesElement = GetChild(root, "LogOverrides", mandatory: false);
            if (logOverridesElement == null) return null;
            var overrides = new List<LogOverride>();
            foreach (var overrideChild in logOverridesElement.Children)
            {
                if (overrideChild.Key != "LogOverride") throw new FormatException("LogOverride");
                var className = GetChild(overrideChild.Value, "ClassName", mandatory: true);
                var logLevel = GetLogLevel(overrideChild.Value, mandatory: true);
                overrides.Add(new LogOverride(className.Value, logLevel.GetValueOrDefault()));
            }
            if (overrides.Select(ov => ov.ClassName).Distinct().Count() < overrides.Count)
            {
                throw new FormatException("Duplicate overrides");
            }
            return overrides.ToArray();
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
            SimpleElement element;
            if (!root.Children.TryGetValue(elementName, out element) && mandatory)
                throw new FormatException(elementName);
            return element;
        }

        private static TraceLevel? GetLogLevel(SimpleElement root, bool mandatory)
        {
            var element = GetChild(root, "LogLevel", mandatory);
            if (element == null) return null;
            return (TraceLevel) Enum.Parse(typeof (TraceLevel), element.Value);
        }

        private static string GetAssemblyLocation()
        {
#if NET40 || NET45
            object dir = AppDomain.CurrentDomain.GetData("APP_CONTEXT_BASE_DIRECTORY");
            if (dir != null) return dir.ToString();
            return Path.GetDirectoryName(new Uri(System.Reflection.Assembly.GetExecutingAssembly().CodeBase).LocalPath);
#else
            return Microsoft.Extensions.PlatformAbstractions.PlatformServices.Default.Application.ApplicationBasePath;
#endif
        }

        private class SimpleElement
        {
            public readonly Dictionary<string, SimpleElement> Children = new Dictionary<string, SimpleElement>();
            public readonly string Name;
            public readonly string Value;

            public SimpleElement(XElement elementNode)
            {
                if (elementNode == null) throw new ArgumentNullException(nameof(elementNode));
                Name = elementNode.Name.LocalName;
                Value = elementNode.Value;

                foreach (XElement element in elementNode.Elements())
                {
                    Children.Add(element.Name.LocalName, new SimpleElement(element));
                }
            }
        }
    }
}