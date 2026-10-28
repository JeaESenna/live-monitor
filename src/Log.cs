using System;
using System.IO;

namespace LiveMonitor
{
    public static class Log
    {
        static readonly object _lock = new object();
        static string _dir;

        public static void Init(string dir)
        {
            _dir = dir;
            try { Directory.CreateDirectory(dir); } catch { }
        }

        public static void Info(string msg) { Write("INFO", msg); }
        public static void Error(string msg) { Write("ERROR", msg); }
        public static void Exception(Exception ex)
        {
            if (ex != null) Write("ERROR", ex.ToString());
        }

        static void Write(string level, string msg)
        {
            try
            {
                lock (_lock)
                {
                    if (string.IsNullOrEmpty(_dir)) return;
                    string file = Path.Combine(_dir, "app.log");
                    try
                    {
                        if (File.Exists(file) && new FileInfo(file).Length > 1024 * 1024) File.Delete(file);
                    }
                    catch { }
                    File.AppendAllText(file,
                        DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " [" + level + "] " + msg + Environment.NewLine);
                }
            }
            catch { }
        }
    }
}
