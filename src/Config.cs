using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Web.Script.Serialization;

namespace LiveMonitor
{
    public class RoomItem
    {
        public string Platform { get; set; }  // douyu / huya / bilibili / douyin / kuaishou
        public string Id { get; set; }        // identifier used for status checking
        public string Url { get; set; }       // web page url
        public string Remark { get; set; }    // user remark (display name)
        public string AutoName { get; set; }  // name fetched from platform
        public DateTime AddedAt { get; set; }
        public bool Notify { get; set; }      // per-room live notification switch
        public string LastStatus { get; set; }   // recorded last state: live/offline/error/notfound/unknown/""
        public DateTime? LastCheckAt { get; set; }
        public DateTime? LastLiveAt { get; set; } // when the streamer most recently went live
        public string LastTitle { get; set; }
        public string Area { get; set; }        // current live area/zone reported by the platform, e.g. "最终幻想14" (sub-area preferred, parent as fallback)

        public RoomItem()
        {
            Platform = ""; Id = ""; Url = ""; Remark = ""; AutoName = "";
            AddedAt = DateTime.Now;
            Notify = true;
            LastStatus = ""; LastCheckAt = null; LastLiveAt = null; LastTitle = ""; Area = "";
        }

        public RoomItem(string platform, string id, string url, string remark)
        {
            Platform = platform; Id = id; Url = url; Remark = remark;
            AutoName = "";
            AddedAt = DateTime.Now;
            Notify = true;
            LastStatus = ""; LastCheckAt = null; LastLiveAt = null; LastTitle = ""; Area = "";
        }

        public string DisplayName
        {
            get
            {
                if (!string.IsNullOrEmpty(Remark)) return Remark;
                if (!string.IsNullOrEmpty(AutoName)) return AutoName;
                return Id;
            }
        }

        public string Key { get { return Platform + ":" + Id; } }

        /// <summary>Only the month/day of the last live session is shown ("上次开播"),
        /// because platform-reported start times can be imprecise; showing minutes
        /// made the list look wrong when a platform time was off by a few minutes.</summary>
        public static string FormatLastLive(DateTime? t)
        {
            if (t == null) return "—";
            return ((DateTime)t).ToString("MM-dd");
        }
    }

    public class AppConfig
    {
        public int ConfigVer { get; set; }            // config format version
        public int IntervalSec { get; set; }          // polling interval, 15..3600
        public bool SoundOn { get; set; }             // play chime on live
        public int LoopSoundSec { get; set; }         // repeat chime every N sec while popup open, 0 = off
        public int PopupAutoCloseSec { get; set; }    // 0 = keep popup until user acts
        public bool NotifyAlreadyLive { get; set; }   // notify rooms already live at startup
        public bool CloseToTray { get; set; }         // close button minimizes to tray
        public string SoundFile { get; set; }         // custom sound file path, "" = built-in
        public bool AutoOpenRoom { get; set; }        // auto-open the room page after notifying
        public bool LegacyAutostartCleaned { get; set; } // one-time cleanup of old versions' auto-start entries
        public List<RoomItem> Rooms { get; set; }

        public AppConfig()
        {
            ConfigVer = 0;   // 0 = legacy config; Fixup migrates it to 1
            IntervalSec = 30;
            SoundOn = true;
            LoopSoundSec = 60;
            PopupAutoCloseSec = 0;
            NotifyAlreadyLive = true;
            CloseToTray = true;
            SoundFile = "";
            AutoOpenRoom = false;
            LegacyAutostartCleaned = false;
            Rooms = new List<RoomItem>();
        }

        static readonly JavaScriptSerializer _json = new JavaScriptSerializer();
        static readonly object _initLock = new object();
        static string _dataDir;

        public static string DataDir { get { InitDataDir(); return _dataDir; } }
        public static string ConfigPath { get { InitDataDir(); return Path.Combine(_dataDir, "config.json"); } }

        public static void InitDataDir()
        {
            lock (_initLock)
            {
                if (_dataDir != null) return;

                // test/beta hook: lets the offline self-test (and portable test
                // builds) use an isolated data directory instead of the real one
                string envDir = "";
                try { envDir = Environment.GetEnvironmentVariable("LIVEMONITOR_DATA_DIR"); } catch { }
                if (!string.IsNullOrEmpty(envDir))
                {
                    try { Directory.CreateDirectory(envDir); } catch { }
                    _dataDir = envDir;
                    Log.Init(Path.Combine(_dataDir, "logs"));
                    return;
                }

                string exeDir = "";
                try { exeDir = Path.GetDirectoryName(Assembly.GetEntryAssembly().Location); } catch { }

                string candidate = exeDir;
                if (!CanWrite(candidate))
                {
                    candidate = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LiveMonitor");
                }
                try { Directory.CreateDirectory(candidate); } catch { candidate = Path.GetTempPath(); }
                _dataDir = candidate;
                Log.Init(Path.Combine(_dataDir, "logs"));
            }
        }

        static bool CanWrite(string dir)
        {
            if (string.IsNullOrEmpty(dir)) return false;
            try
            {
                string p = Path.Combine(dir, ".wtest_" + Guid.NewGuid().ToString("N"));
                File.WriteAllText(p, "x");
                File.Delete(p);
                return true;
            }
            catch { return false; }
        }

        public static AppConfig Load()
        {
            InitDataDir();
            string path = ConfigPath;
            string bak = path + ".bak";
            bool exists = false;
            try { exists = File.Exists(path); } catch { }

            if (exists)
            {
                AppConfig cfg = null;
                bool corrupt = false;
                try
                {
                    string txt = File.ReadAllText(path, Encoding.UTF8);
                    cfg = _json.Deserialize<AppConfig>(txt);
                    if (cfg == null) corrupt = true;
                }
                catch (Exception ex)
                {
                    Log.Exception(ex);
                    corrupt = true;
                }

                if (!corrupt && cfg != null)
                {
                    bool migrated = Fixup(cfg);
                    if (migrated)
                    {
                        try { cfg.Save(); } catch { }
                    }
                    Log.Info("config loaded: " + path + " rooms=" + cfg.Rooms.Count);
                    return cfg;
                }

                // never silently overwrite a config the user may still want:
                // keep the broken file next to the new one for inspection
                try
                {
                    string bad = path + ".bad-" + DateTime.Now.ToString("yyyyMMdd-HHmmss");
                    File.Move(path, bad);
                    Log.Info("corrupted config moved to " + bad);
                }
                catch { }

                // last known good copy written by Save() (atomic replace keeps it)
                var backup = TryLoadBackup(bak);
                if (backup != null)
                {
                    Log.Info("config recovered from backup: " + bak);
                    try { backup.Save(); } catch { }
                    return backup;
                }
            }
            else
            {
                // deliberately do NOT resurrect an old .bak here: if the user
                // deleted config.json they want a fresh configuration
                Log.Info("config not found: " + path);
            }

            var fresh = new AppConfig();
            fresh.ConfigVer = 1;
            try { fresh.Save(); } catch { }
            return fresh;
        }

        static AppConfig TryLoadBackup(string bak)
        {
            try
            {
                if (!File.Exists(bak)) return null;
                var cfg = _json.Deserialize<AppConfig>(File.ReadAllText(bak, Encoding.UTF8));
                if (cfg == null) return null;
                Fixup(cfg);
                return cfg;
            }
            catch (Exception ex) { Log.Exception(ex); return null; }
        }

        /// <summary>Returns true when a one-time migration modified the config.</summary>
        static bool Fixup(AppConfig cfg)
        {
            bool changed = false;
            if (cfg.IntervalSec < 15 || cfg.IntervalSec > 3600) { cfg.IntervalSec = 30; changed = true; }
            if (cfg.PopupAutoCloseSec < 0) { cfg.PopupAutoCloseSec = 0; changed = true; }
            if (cfg.PopupAutoCloseSec > 3600) { cfg.PopupAutoCloseSec = 3600; changed = true; }
            if (cfg.LoopSoundSec < 0 || cfg.LoopSoundSec > 3600) { cfg.LoopSoundSec = 60; changed = true; }
            if (cfg.ConfigVer < 1)
            {
                // v0 -> v1: popup stays open until the user acts (old default auto-closed after 30s)
                if (cfg.PopupAutoCloseSec == 30) cfg.PopupAutoCloseSec = 0;
                cfg.ConfigVer = 1;
                changed = true;
            }
            if (cfg.Rooms == null) { cfg.Rooms = new List<RoomItem>(); changed = true; }

            // drop null/duplicate/broken entries so a hand-edited or half-written
            // config can never break the list or the monitor thread
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            for (int i = cfg.Rooms.Count - 1; i >= 0; i--)
            {
                var r = cfg.Rooms[i];
                if (r == null || string.IsNullOrEmpty(r.Platform) || string.IsNullOrEmpty(r.Id))
                {
                    cfg.Rooms.RemoveAt(i);
                    changed = true;
                    continue;
                }
                if (!seen.Add(r.Key))
                {
                    cfg.Rooms.RemoveAt(i);
                    changed = true;
                }
            }

            foreach (var r in cfg.Rooms)
            {
                // JavaScriptSerializer reads "/Date(...)/" as a UTC DateTime.  Normalise
                // back to local time, otherwise "上次开播"/"最后检测" would be shifted by
                // the time-zone offset and could even show the previous day.
                r.LastCheckAt = NormalizeLocal(r.LastCheckAt);
                r.LastLiveAt = NormalizeLocal(r.LastLiveAt);
                if (string.IsNullOrEmpty(r.Url))
                {
                    string url;
                    if (PlatformApi.BuildUrlForRoom(r.Platform, r.Id, out url)) { r.Url = url; changed = true; }
                }
            }
            return changed;
        }

        static DateTime? NormalizeLocal(DateTime? t)
        {
            if (t == null) return null;
            DateTime d = (DateTime)t;
            if (d.Kind == DateTimeKind.Utc) return d.ToLocalTime();
            if (d.Kind == DateTimeKind.Unspecified) return DateTime.SpecifyKind(d, DateTimeKind.Local);
            return d;
        }

        /// <summary>Serializes and saves atomically (temp file + replace), keeping a
        /// .bak copy so a crash/power loss mid-write cannot corrupt the config.
        /// Locks the config object, which is also the lock used when the UI mutates
        /// the room list, so a background save never enumerates a changing list.
        /// Returns false when the file could not be written (disk full, ACL, ...)
        /// so the caller can tell the user instead of silently losing settings.</summary>
        public bool Save()
        {
            lock (this)
            {
                try
                {
                    InitDataDir();
                    string txt = _json.Serialize(this);
                    string path = ConfigPath;
                    string tmp = path + ".tmp";
                    File.WriteAllText(tmp, txt, new UTF8Encoding(false));

                    if (File.Exists(path))
                    {
                        string bak = path + ".bak";
                        bool replaced = false;
                        try
                        {
                            if (File.Exists(bak)) File.Delete(bak);
                            File.Replace(tmp, path, bak, true);
                            replaced = true;
                        }
                        catch { }
                        if (!replaced)
                        {
                            File.WriteAllText(path, txt, new UTF8Encoding(false));
                            try { File.Delete(tmp); } catch { }
                        }
                    }
                    else
                    {
                        File.Move(tmp, path);
                    }
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Exception(ex);
                    return false;
                }
            }
        }
    }
}
