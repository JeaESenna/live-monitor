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

        public RoomItem()
        {
            Platform = ""; Id = ""; Url = ""; Remark = ""; AutoName = "";
            AddedAt = DateTime.Now;
            Notify = true;
            LastStatus = ""; LastCheckAt = null; LastLiveAt = null; LastTitle = "";
        }

        public RoomItem(string platform, string id, string url, string remark)
        {
            Platform = platform; Id = id; Url = url; Remark = remark;
            AutoName = "";
            AddedAt = DateTime.Now;
            Notify = true;
            LastStatus = ""; LastCheckAt = null; LastLiveAt = null; LastTitle = "";
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
    }

    public class AppConfig
    {
        public int ConfigVer { get; set; }            // config format version
        public int IntervalSec { get; set; }          // polling interval, 10..3600
        public bool SoundOn { get; set; }             // play chime on live
        public int LoopSoundSec { get; set; }         // repeat chime every N sec while popup open, 0 = off
        public int PopupAutoCloseSec { get; set; }    // 0 = keep popup until user acts
        public bool NotifyAlreadyLive { get; set; }   // notify rooms already live at startup
        public bool CloseToTray { get; set; }         // close button minimizes to tray
        public string SoundFile { get; set; }         // custom sound file path, "" = built-in
        public bool AutoOpenRoom { get; set; }        // auto-open the room page after notifying
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
            Rooms = new List<RoomItem>();
        }

        static readonly JavaScriptSerializer _json = new JavaScriptSerializer();
        static string _dataDir;

        public static string DataDir { get { InitDataDir(); return _dataDir; } }
        public static string ConfigPath { get { InitDataDir(); return Path.Combine(_dataDir, "config.json"); } }

        public static void InitDataDir()
        {
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
            try
            {
                if (File.Exists(ConfigPath))
                {
                    string txt = File.ReadAllText(ConfigPath, Encoding.UTF8);
                    var cfg = _json.Deserialize<AppConfig>(txt);
                    if (cfg != null)
                    {
                        bool migrated = Fixup(cfg);
                        if (migrated)
                        {
                            try { cfg.Save(); } catch { }
                        }
                        Log.Info("config loaded: " + ConfigPath);
                        return cfg;
                    }
                }
            }
            catch (Exception ex) { Log.Exception(ex); }
            var fresh = new AppConfig();
            fresh.ConfigVer = 1;
            try { fresh.Save(); } catch { }
            return fresh;
        }

        /// <summary>Returns true when a one-time migration modified the config.</summary>
        static bool Fixup(AppConfig cfg)
        {
            bool changed = false;
            if (cfg.IntervalSec < 15 || cfg.IntervalSec > 3600) cfg.IntervalSec = 30;
            if (cfg.PopupAutoCloseSec < 0) cfg.PopupAutoCloseSec = 0;
            if (cfg.LoopSoundSec < 0 || cfg.LoopSoundSec > 3600) cfg.LoopSoundSec = 60;
            if (cfg.ConfigVer < 1)
            {
                // v0 -> v1: popup stays open until the user acts (old default auto-closed after 30s)
                if (cfg.PopupAutoCloseSec == 30) cfg.PopupAutoCloseSec = 0;
                cfg.ConfigVer = 1;
                changed = true;
            }
            if (cfg.Rooms == null) cfg.Rooms = new List<RoomItem>();
            foreach (var r in cfg.Rooms)
            {
                if (r == null) continue;
                if (string.IsNullOrEmpty(r.Url))
                {
                    string url;
                    if (PlatformApi.BuildUrlForRoom(r.Platform, r.Id, out url)) r.Url = url;
                }
            }
            return changed;
        }

        public void Save()
        {
            try
            {
                InitDataDir();
                string txt = _json.Serialize(this);
                File.WriteAllText(ConfigPath, txt, Encoding.UTF8);
            }
            catch (Exception ex) { Log.Exception(ex); }
        }
    }
}
