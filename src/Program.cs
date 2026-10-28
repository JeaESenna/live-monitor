using System;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace LiveMonitor
{
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e)
            {
                try { Log.Exception(e.ExceptionObject as Exception); } catch { }
            };
            Application.ThreadException += delegate(object s, ThreadExceptionEventArgs e)
            {
                try { Log.Exception(e.Exception); } catch { }
            };

            // bootstrap logging to temp; Config.Load re-points it into the data dir
            Log.Init(Path.Combine(Path.GetTempPath(), "LiveMonitor"));

            bool minimized = false;
            if (args != null)
            {
                foreach (var a in args)
                {
                    if (a == "--minimized" || a == "-m") minimized = true;
                }
            }

            bool created;
            using (var mutex = new Mutex(true, "Global\\LiveMonitor_SingleInstance_9F2C", out created))
            {
                if (!created)
                {
                    // a background auto-start must not disturb the running instance
                    if (!minimized)
                    {
                        MessageBox.Show("直播监测已经在运行中。\r\n请查看任务栏右下角托盘图标。", "直播监测",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
                    }
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm(minimized));
            }
        }
    }
}
