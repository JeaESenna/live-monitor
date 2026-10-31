using System;
using System.IO;
using System.Text;

namespace LiveMonitor
{
    public static class Log
    {
        const long MaxBytes = 1024 * 1024;      // rotate at 1 MB
        static readonly object _lock = new object();
        static string _dir;

        public static void Init(string dir)
        {
            if (dir == _dir) return;
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
                if (msg == null) msg = "";
                if (msg.Length > 8000) msg = msg.Substring(0, 8000) + " ...(" + msg.Length + " chars truncated)";
                lock (_lock)
                {
                    string dir = _dir;
                    if (string.IsNullOrEmpty(dir)) return;
                    try { Directory.CreateDirectory(dir); } catch { }
                    string file = Path.Combine(dir, "app.log");
                    RotateIfNeeded(file);
                    File.AppendAllText(file,
                        DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " [" + level + "] " + msg + Environment.NewLine,
                        new UTF8Encoding(false));
                }
            }
            catch { }
        }

        /// <summary>Keeps one previous log (app.prev.log) instead of deleting the
        /// whole history, so problems that show up after a long run can still be
        /// diagnosed from the log.</summary>
        static void RotateIfNeeded(string file)
        {
            try
            {
                if (!File.Exists(file)) return;
                if (new FileInfo(file).Length <= MaxBytes) return;
                string prev = Path.Combine(Path.GetDirectoryName(file), "app.prev.log");
                try { if (File.Exists(prev)) File.Delete(prev); } catch { }
                try { File.Move(file, prev); }
                catch
                {
                    // move can fail (e.g. another reader); fall back to truncating
                    try { File.WriteAllText(file, ""); } catch { }
                }
            }
            catch { }
        }
    }
}
