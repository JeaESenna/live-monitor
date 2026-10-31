using System;
using System.IO;
using System.Media;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace LiveMonitor
{
    public static class Sound
    {
        static byte[] _wav;
        static readonly object _lock = new object();
        static int _playing;
        static long _playStartedTicks;

        [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
        static extern int mciSendString(string command, StringBuilder buffer, int bufferSize, IntPtr hwndCallback);

        public static byte[] Wav
        {
            get
            {
                lock (_lock)
                {
                    if (_wav == null)
                    {
                        try
                        {
                            using (var s = Assembly.GetExecutingAssembly()
                                .GetManifestResourceStream("chime.wav"))
                            {
                                if (s == null) return null;
                                using (var ms = new MemoryStream())
                                {
                                    s.CopyTo(ms);
                                    _wav = ms.ToArray();
                                }
                            }
                        }
                        catch { _wav = null; }
                    }
                    return _wav;
                }
            }
        }

        /// <summary>Plays the custom sound file when set, otherwise the built-in chime.
        /// Overlapping plays are skipped.
        /// A dedicated background thread is used instead of a ThreadPool work item:
        /// mciSendString("play ... wait") blocks for the whole duration of the file,
        /// and a long custom mp3 must not starve the thread pool (or the UI).
        /// The "already playing" latch is reclaimed after 5 minutes so a hung audio
        /// driver can never silence notifications forever.</summary>
        public static void PlayChime(bool enabled, string soundFile)
        {
            if (!enabled) return;
            string file = soundFile;
            try
            {
                var t = new Thread(delegate()
                {
                    if (!TryBeginPlay()) return;
                    try
                    {
                        if (!string.IsNullOrEmpty(file) && File.Exists(file))
                        {
                            if (!PlayFile(file)) PlayBuiltIn();
                        }
                        else
                        {
                            PlayBuiltIn();
                        }
                    }
                    catch { }
                    finally { Interlocked.Exchange(ref _playing, 0); }
                });
                t.IsBackground = true;
                t.Name = "LiveMonitorSound";
                t.Start();
            }
            catch { Interlocked.Exchange(ref _playing, 0); }
        }

        static bool TryBeginPlay()
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                if (Interlocked.CompareExchange(ref _playing, 1, 0) == 0)
                {
                    Interlocked.Exchange(ref _playStartedTicks, DateTime.UtcNow.Ticks);
                    return true;
                }
                long started = Interlocked.Read(ref _playStartedTicks);
                if (started != 0 && DateTime.UtcNow.Ticks - started > TimeSpan.FromMinutes(5).Ticks)
                {
                    // playback has been "in progress" for >5 min: assume the audio
                    // device/driver is stuck and reclaim the latch
                    Interlocked.Exchange(ref _playing, 0);
                    continue;
                }
                return false;
            }
            return false;
        }

        static void PlayBuiltIn()
        {
            try
            {
                byte[] wav = Wav;
                if (wav == null) return;
                using (var ms = new MemoryStream(wav))
                using (var player = new SoundPlayer(ms))
                {
                    player.PlaySync();
                }
            }
            catch { }
        }

        static bool PlayFile(string path)
        {
            try
            {
                string ext = Path.GetExtension(path).ToLowerInvariant();
                if (ext == ".wav")
                {
                    using (var player = new SoundPlayer(path))
                    {
                        player.PlaySync();
                    }
                    return true;
                }

                // mp3 and other formats via MCI; "wait" blocks until playback ends
                string openCmd = "open \"" + path + "\" alias lm_sound";
                if (ext == ".mp3") openCmd += " type mpegvideo";
                mciSendString("close lm_sound", null, 0, IntPtr.Zero); // free a stale alias, ignore errors
                if (mciSendString(openCmd, null, 0, IntPtr.Zero) != 0) return false;
                try
                {
                    if (mciSendString("play lm_sound wait", null, 0, IntPtr.Zero) != 0) return false;
                }
                finally
                {
                    mciSendString("close lm_sound", null, 0, IntPtr.Zero);
                }
                return true;
            }
            catch { return false; }
        }
    }
}
