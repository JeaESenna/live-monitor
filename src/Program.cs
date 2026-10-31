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

            try
            {
                Run(args);
            }
            catch (Exception ex)
            {
                // a winexe has no console: log it and show one clear message instead
                // of dying silently (e.g. theme/font/registry problems at startup)
                try { Log.Exception(ex); } catch { }
                try
                {
                    MessageBox.Show("直播监测启动失败:\r\n" + ex.Message, "直播监测",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                catch { }
            }
        }

        static void Run(string[] args)
        {
            bool minimized = false;
            if (args != null)
            {
                foreach (var a in args)
                {
                    if (a == "--minimized" || a == "-m") minimized = true;
                }
            }

            bool created = true;
            Mutex mutex = null;
            try
            {
                mutex = CreateSingleInstanceMutex(out created);
            }
            catch (Exception ex)
            {
                // failing to create the mutex must not prevent monitoring from
                // starting at all; a second instance would just show the dialog
                Log.Exception(ex);
                created = true;
            }

            try
            {
                if (!created)
                {
                    try { Log.Info("second instance refused (already running)"); } catch { }
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
            finally
            {
                if (mutex != null)
                {
                    if (created)
                    {
                        try { mutex.ReleaseMutex(); } catch { }
                    }
                    try { mutex.Dispose(); } catch { }
                }
            }
        }

        /// <summary>Global mutex is preferred (covers fast user switching), but
        /// creating objects in the Global namespace can fail for restricted
        /// accounts — fall back to a per-session mutex instead of failing to start.</summary>
        static Mutex CreateSingleInstanceMutex(out bool created)
        {
            try
            {
                return new Mutex(true, @"Global\LiveMonitor_SingleInstance_9F2C", out created);
            }
            catch
            {
                return new Mutex(true, @"Local\LiveMonitor_SingleInstance_9F2C", out created);
            }
        }
    }
}
