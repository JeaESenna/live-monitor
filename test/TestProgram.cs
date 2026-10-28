using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using LiveMonitor;

/// <summary>Console self-test: exercises parsers, config and resources offline.</summary>
class TestProgram
{
    static int _pass, _fail;
    static readonly StringBuilder _log = new StringBuilder();

    static void Check(string name, bool ok, string detail)
    {
        if (ok) { _pass++; _log.AppendLine("PASS  " + name); }
        else { _fail++; _log.AppendLine("FAIL  " + name + "  detail: " + detail); }
    }

    static Dictionary<string, object> Json(string txt)
    {
        return new JavaScriptSerializer().DeserializeObject(txt) as Dictionary<string, object>;
    }

    static string SelfDir()
    {
        try { return Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location); }
        catch { return "."; }
    }

    static void LogCrash(object eo)
    {
        try
        {
            var ex = eo as Exception;
            string msg = ex == null ? "non-exception: " + (eo == null ? "null" : eo.GetType().FullName)
                : ex.GetType().FullName + " | " + ex.Message + " | " + (ex.InnerException == null ? "" : ex.InnerException.GetType().FullName + ":" + ex.InnerException.Message);
            File.WriteAllText(Path.Combine(SelfDir(), "crash.txt"), msg, new UTF8Encoding(true));
        }
        catch { }
    }

    static int Main()
    {
        AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e)
        {
            LogCrash(e.ExceptionObject);
        };
        try
        {
            return Run();
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            try { _log.AppendLine("CRASH " + ex.GetType().FullName + " " + ex.Message); } catch { }
            try { File.WriteAllText(Path.Combine(SelfDir(), "selftest_result.txt"), _log.ToString(), new UTF8Encoding(true)); } catch { }
            try { Console.WriteLine(_log.ToString()); } catch { }
            return 2;
        }
    }

    static int Run()
    {
        // ---------- input parsing ----------
        string p, id, url, msg;
        bool ok = PlatformApi.ParseInput("https://www.douyu.com/9999", "auto", out p, out id, out url, out msg);
        Check("parse douyu url", ok && p == "douyu" && id == "9999" && url.Contains("9999"), "p=" + p + " id=" + id + " url=" + url + " " + msg);

        ok = PlatformApi.ParseInput("9999", "douyu", out p, out id, out url, out msg);
        Check("parse douyu bare id", ok && p == "douyu" && id == "9999", msg);

        ok = PlatformApi.ParseInput("https://live.bilibili.com/12345", "auto", out p, out id, out url, out msg);
        Check("parse bili room url", ok && p == "bilibili" && id == "12345", msg);

        ok = PlatformApi.ParseInput("https://space.bilibili.com/123456", "auto", out p, out id, out url, out msg);
        Check("parse bili uid url", ok && p == "bilibili" && id == "123456" && url.Contains("space.bilibili.com"), url);

        ok = PlatformApi.ParseInput("https://www.huya.com/660000", "auto", out p, out id, out url, out msg);
        Check("parse huya url", ok && p == "huya" && id == "660000", msg);

        ok = PlatformApi.ParseInput("https://live.douyin.com/123456789012345", "auto", out p, out id, out url, out msg);
        Check("parse douyin url", ok && p == "douyin" && id == "123456789012345", msg);

        ok = PlatformApi.ParseInput("https://live.kuaishou.com/u/3xabc123", "auto", out p, out id, out url, out msg);
        Check("parse kuaishou url", ok && p == "kuaishou" && id == "3xabc123", id);

        ok = PlatformApi.ParseInput("12345", "auto", out p, out id, out url, out msg);
        Check("parse auto bare digits rejected", !ok, msg);

        // ---------- douyu ----------
        var dy = PlatformApi.ParseDouyu("{\"error\":0,\"data\":{\"room_id\":\"9999\",\"room_status\":\"1\",\"room_name\":\"t1\",\"owner_name\":\"n1\"}}");
        Check("douyu live", dy.State == LiveState.Live && dy.Owner == "n1" && dy.Title == "t1", dy.State.ToString());
        var dy2 = PlatformApi.ParseDouyu("{\"error\":0,\"data\":{\"room_status\":\"2\",\"room_name\":\"\",\"owner_name\":\"\"}}");
        Check("douyu offline", dy2.State == LiveState.Offline, dy2.State.ToString());
        var dy3 = PlatformApi.ParseDouyu("{\"error\":105,\"data\":null}");
        Check("douyu notfound", dy3.State == LiveState.NotFound, dy3.State.ToString());
        var dy4 = PlatformApi.ParseDouyuBetard("{\"error\":0,\"room\":{\"show_status\":\"1\",\"room_name\":\"t2\",\"nickname\":\"n2\"}}");
        Check("douyu betard live", dy4.State == LiveState.Live && dy4.Owner == "n2", dy4.State.ToString());

        // ---------- bilibili ----------
        var br = PlatformApi.ParseBiliGetInfo(Json("{\"code\":0,\"data\":{\"live_status\":1,\"title\":\"bt\",\"uid\":1,\"room_id\":6}}"));
        Check("bili live", br.State == LiveState.Live && br.Title == "bt", br.State.ToString());
        var br2 = PlatformApi.ParseBiliGetInfo(Json("{\"code\":0,\"data\":{\"live_status\":2,\"title\":\"\",\"uid\":1,\"room_id\":6}}"));
        Check("bili round(轮播)", br2.State == LiveState.Live && br2.Sub == "轮播中", br2.Sub);
        var br3 = PlatformApi.ParseBiliGetInfo(Json("{\"code\":0,\"data\":{\"live_status\":0,\"title\":\"\",\"uid\":1,\"room_id\":6}}"));
        Check("bili offline", br3.State == LiveState.Offline, br3.State.ToString());
        var br4 = PlatformApi.ParseBiliGetRoomInfoOld(Json("{\"code\":0,\"data\":{\"roomid\":6,\"uname\":\"bu\",\"title\":\"ot\",\"liveStatus\":0,\"roundStatus\":1}}"));
        Check("bili uid round", br4.State == LiveState.Live && br4.Owner == "bu" && br4.Sub == "轮播中", br4.Owner);

        var br5 = PlatformApi.ParseBiliGetInfoByRoom(Json("{\"code\":0,\"data\":{\"room_info\":{\"live_status\":1,\"title\":\"gt\"},\"anchor_info\":{\"base_info\":{\"uname\":\"gn\"}}}}"));
        Check("bili getInfoByRoom live+uname", br5 != null && br5.State == LiveState.Live && br5.Owner == "gn" && br5.Title == "gt",
            br5 == null ? "null" : br5.Owner);
        var br6 = PlatformApi.ParseBiliGetInfoByRoom(Json("{\"code\":0,\"data\":{\"room_info\":{\"live_status\":2,\"title\":\"\"},\"anchor_info\":{\"base_info\":{\"uname\":\"gn\"}}}}"));
        Check("bili getInfoByRoom round", br6 != null && br6.State == LiveState.Live && br6.Sub == "轮播中", br6 == null ? "null" : br6.Sub);
        var br7 = PlatformApi.ParseBiliGetInfoByRoom(Json("{\"code\":0,\"data\":{\"room_info\":{\"live_status\":0},\"anchor_info\":{\"base_info\":{\"uname\":\"gn\"}}}}"));
        Check("bili getInfoByRoom offline", br7 != null && br7.State == LiveState.Offline && br7.Owner == "gn", br7 == null ? "null" : br7.State.ToString());
        Check("bili getInfoByRoom bad code", PlatformApi.ParseBiliGetInfoByRoom(Json("{\"code\":-400,\"data\":null}")) == null, "");

        var bp = PlatformApi.ParseBiliLivePage("{\"live_status\":1,\"title\":\"pt\",\"uname\":\"pn\"}");
        Check("bili page live+uname", bp != null && bp.State == LiveState.Live && bp.Owner == "pn" && bp.Title == "pt",
            bp == null ? "null" : bp.Owner);
        var bp2 = PlatformApi.ParseBiliLivePage("{\"live_status\":0,\"uname\":\"pn\"}");
        Check("bili page offline", bp2 != null && bp2.State == LiveState.Offline && bp2.Owner == "pn",
            bp2 == null ? "null" : bp2.State.ToString());
        var bp3 = PlatformApi.ParseBiliLivePage("{\"live_status\":2,\"title\":\"pt\",\"uname\":\"pn\"}");
        Check("bili page round", bp3 != null && bp3.State == LiveState.Live && bp3.Sub == "轮播中", bp3 == null ? "null" : bp3.Sub);
        Check("bili page unparseable", PlatformApi.ParseBiliLivePage("<html>nothing here</html>") == null, "");

        // ---------- real live-start time ----------
        var bp4 = PlatformApi.ParseBiliLivePage("{\"live_status\":1,\"live_time\":\"2026-08-15 14:30:00\",\"uname\":\"pn\"}");
        Check("bili page live_time",
            bp4 != null && bp4.LiveStartAt != null
            && ((DateTime)bp4.LiveStartAt).ToString("yyyy-MM-dd HH:mm:ss") == "2026-08-15 14:30:00",
            bp4 == null || bp4.LiveStartAt == null ? "null" : ((DateTime)bp4.LiveStartAt).ToString());
        var bp5 = PlatformApi.ParseBiliLivePage("{\"live_status\":1,\"live_start_time\":1786793400,\"uname\":\"pn\"}");
        Check("bili page live_start_time unix",
            bp5 != null && bp5.LiveStartAt != null && bp5.LiveStartAt == PlatformApi.ParseUnix(1786793400),
            bp5 == null || bp5.LiveStartAt == null ? "null" : ((DateTime)bp5.LiveStartAt).ToString());
        Check("parseunix sanity",
            PlatformApi.ParseUnix(1786793400) != null
            && ((DateTime)PlatformApi.ParseUnix(1786793400)).Year == 2026,
            PlatformApi.ParseUnix(1786793400) == null ? "null" : ((DateTime)PlatformApi.ParseUnix(1786793400)).Year.ToString());
        var bi2 = PlatformApi.ParseBiliGetInfoByRoom(Json("{\"code\":0,\"data\":{\"room_info\":{\"live_status\":1,\"title\":\"\",\"live_start_time\":1786793400}}}"));
        Check("bili getInfoByRoom live_start_time", bi2 != null && bi2.LiveStartAt != null,
            bi2 == null || bi2.LiveStartAt == null ? "null" : ((DateTime)bi2.LiveStartAt).ToString());
        var bgi = PlatformApi.ParseBiliGetInfo(Json("{\"code\":0,\"data\":{\"live_status\":1,\"title\":\"\",\"live_time\":\"2026-08-15 10:00:00\",\"uid\":1,\"room_id\":6}}"));
        Check("bili get_info live_time",
            bgi.LiveStartAt != null && ((DateTime)bgi.LiveStartAt).ToString("yyyy-MM-dd HH:mm:ss") == "2026-08-15 10:00:00",
            bgi.LiveStartAt == null ? "null" : ((DateTime)bgi.LiveStartAt).ToString());
        var dy5 = PlatformApi.ParseDouyu("{\"error\":0,\"data\":{\"room_status\":\"1\",\"room_name\":\"\",\"owner_name\":\"\",\"start_time\":\"2026-08-15 09:05:00\"}}");
        Check("douyu start_time", dy5.LiveStartAt != null,
            dy5.LiveStartAt == null ? "null" : ((DateTime)dy5.LiveStartAt).ToString());
        var dy6 = PlatformApi.ParseDouyu("{\"error\":0,\"data\":{\"room_status\":\"2\",\"room_name\":\"\",\"owner_name\":\"\"}}");
        Check("douyu offline no start_time", dy6.LiveStartAt == null, "");

        // ---------- huya ----------
        var h1 = PlatformApi.ParseHuyaHtml("{\"isOn\": true, \"sNick\":\"hn\", \"sRoomName\":\"ht\"}");
        Check("huya live", h1 != null && h1.State == LiveState.Live && h1.Owner == "hn" && h1.Title == "ht", h1 == null ? "null" : h1.State.ToString());
        var h2 = PlatformApi.ParseHuyaHtml("{\"isOn\": false}");
        Check("huya offline", h2 != null && h2.State == LiveState.Offline, h2 == null ? "null" : h2.State.ToString());
        Check("huya unparseable", PlatformApi.ParseHuyaHtml("<html>short</html>") == null, "");

        // ---------- douyin ----------
        var de = PlatformApi.ParseDouyinEnter("{\"data\":{\"data\":[{\"status\":2,\"title\":\"dt\",\"owner\":{\"nickname\":\"dn\"}}]}}");
        Check("douyin live", de != null && de.State == LiveState.Live && de.Owner == "dn" && de.Title == "dt", de == null ? "null" : de.State.ToString());
        var de2 = PlatformApi.ParseDouyinEnter("{\"data\":{\"data\":[{\"status\":4}]}}");
        Check("douyin offline", de2 != null && de2.State == LiveState.Offline, de2 == null ? "null" : de2.State.ToString());
        Check("douyin empty array", PlatformApi.ParseDouyinEnter("{\"data\":{\"data\":[]}}") == null, "");

        // ---------- kuaishou ----------
        var k1 = PlatformApi.ParseKuaishouHtml("{\"name\":\"kn\",\"isLiving\":true}");
        Check("kuaishou live", k1.State == LiveState.Live && k1.Owner == "kn", k1.Owner);
        var k2 = PlatformApi.ParseKuaishouHtml("{\"isLiving\":false}");
        Check("kuaishou offline", k2.State == LiveState.Offline, k2.State.ToString());
        Check("kuaishou unknown", PlatformApi.ParseKuaishouHtml("no isLiving here, page too short") == null
            || PlatformApi.ParseKuaishouHtml("no isLiving here, page too short").State == LiveState.Unknown, "");

        // ---------- misc ----------
        Check("unescape \\uXXXX", PlatformApi.Unescape("\\u76f4\\u64ad") == "直播", PlatformApi.Unescape("\\u76f4\\u64ad"));

        // ---------- config roundtrip (backup/restore the real config to avoid clobbering it) ----------
        string cfgPath = AppConfig.ConfigPath;
        string backup = cfgPath + ".selftest.bak";
        bool hadConfig = File.Exists(cfgPath);
        if (hadConfig)
        {
            try { File.Copy(cfgPath, backup, true); } catch { backup = ""; }
        }

        var cfg = new AppConfig();
        Check("config new defaults",
            cfg.LoopSoundSec == 60 && cfg.PopupAutoCloseSec == 0 && cfg.ConfigVer == 0
            && cfg.AutoOpenRoom == false && cfg.SoundFile == "",
            cfg.LoopSoundSec + "/" + cfg.PopupAutoCloseSec + "/" + cfg.ConfigVer);
        var r1 = new RoomItem("douyu", "9999", "https://www.douyu.com/9999", "r1");
        r1.Notify = false;
        r1.LastStatus = "live";
        r1.LastLiveAt = DateTime.Now;
        r1.LastTitle = "记录标题";
        cfg.Rooms.Add(r1);
        cfg.Rooms.Add(new RoomItem("bilibili", "6", "https://live.bilibili.com/6", ""));
        _log.AppendLine("INFO rooms before save: " + cfg.Rooms.Count);
        cfg.Save();
        var cfg2 = AppConfig.Load();
        _log.AppendLine("INFO rooms after load: " + cfg2.Rooms.Count);
        Check("config roundtrip count", cfg2.Rooms.Count == 2, cfg2.Rooms.Count.ToString());
        if (cfg2.Rooms.Count >= 2)
        {
            Check("config roundtrip fields",
                cfg2.Rooms[0].Platform == "douyu" && cfg2.Rooms[0].Remark == "r1"
                && cfg2.Rooms[1].Url.StartsWith("https://live.bilibili.com"), "");
            Check("config roundtrip notify flag", cfg2.Rooms[0].Notify == false, cfg2.Rooms[0].Notify.ToString());
            Check("config roundtrip recorded state",
                cfg2.Rooms[0].LastStatus == "live" && cfg2.Rooms[0].LastLiveAt != null
                && cfg2.Rooms[0].LastTitle == "记录标题", cfg2.Rooms[0].LastStatus);
        }
        Check("config interval default", cfg2.IntervalSec == 30, cfg2.IntervalSec.ToString());

        // restore the user's real config
        try
        {
            if (hadConfig && backup.Length > 0)
            {
                File.Copy(backup, cfgPath, true);
                File.Delete(backup);
            }
            else if (!hadConfig)
            {
                File.Delete(cfgPath);
            }
        }
        catch (Exception ex) { _log.AppendLine("INFO config restore failed: " + ex.Message); }

        // ---------- room monitor state machine (no network needed) ----------
        var cfgm = new AppConfig();
        var room = new RoomItem("bilibili", "5038", "https://live.bilibili.com/5038", "");
        var mon = new RoomMonitor(cfgm);
        mon.NotifyCooldownSec = 0;
        var evs = new List<MonitorEvent>();
        mon.Event += delegate(MonitorEvent e) { evs.Add(e); };

        mon.Raise(room, new CheckResult(LiveState.Live, "t", "n"));
        Check("sm first live notifies", evs.Count == 1 && evs[0].BecameLive, "");
        mon.Raise(room, new CheckResult(LiveState.Live, "t", "n"));
        Check("sm live persists, no notify", !evs[evs.Count - 1].BecameLive, "");
        mon.Raise(room, new CheckResult(LiveState.Error) { Error = "net" });
        var lastEv = evs[evs.Count - 1];
        Check("sm 1st error sticks to live",
            lastEv.Result.State == LiveState.Live && lastEv.Result.Sub == "网络异常"
            && lastEv.Result.Title == "t", lastEv.Result.State.ToString());
        mon.Raise(room, new CheckResult(LiveState.Error) { Error = "net" });
        lastEv = evs[evs.Count - 1];
        Check("sm 2nd error commits error",
            lastEv.Result.State == LiveState.Error && lastEv.Result.Sub != "网络异常",
            lastEv.Result.State.ToString());
        mon.Raise(room, new CheckResult(LiveState.Live, "t", "n"));
        lastEv = evs[evs.Count - 1];
        Check("sm error->live restores without popup",
            lastEv.Result.State == LiveState.Live && !lastEv.BecameLive, "");
        mon.Raise(room, new CheckResult(LiveState.Offline, "", ""));
        mon.Raise(room, new CheckResult(LiveState.Live, "t2", "n"));
        lastEv = evs[evs.Count - 1];
        Check("sm offline->live notifies", lastEv.BecameLive, "");

        mon.NotifyCooldownSec = 3600;
        mon.Raise(room, new CheckResult(LiveState.Offline, "", ""));
        mon.Raise(room, new CheckResult(LiveState.Live, "t3", "n"));
        lastEv = evs[evs.Count - 1];
        Check("sm cooldown suppresses repeated popup", !lastEv.BecameLive, "");
        mon.NotifyCooldownSec = 0;

        // ---------- wake must not cause a tight polling loop ----------
        // platform "test" -> instant Error result (no network), so with a
        // manual-reset bug the loop would spin dozens of cycles in 2.5s.
        var cfgLoop = new AppConfig();
        cfgLoop.Rooms.Add(new RoomItem("test", "1", "http://x", ""));
        var monLoop = new RoomMonitor(cfgLoop);
        int cycles = 0;
        monLoop.CheckStarted += delegate { cycles++; };
        monLoop.Start();
        Thread.Sleep(300);
        monLoop.Wake();
        monLoop.Wake();
        monLoop.Wake();
        Thread.Sleep(2500);
        monLoop.Stop();
        Check("wake does not cause tight loop", cycles <= 3, "cycles=" + cycles);

        // ---------- resources ----------
        var asm = Assembly.GetExecutingAssembly();
        Check("resource chime.wav", asm.GetManifestResourceStream("chime.wav") != null, "");
        Check("resource app.ico", asm.GetManifestResourceStream("app.ico") != null, "");

        // ---------- live network attempt (informational; may fail offline) ----------
        try
        {
            var live = PlatformApi.Check("douyu", "9999", null);
            _log.AppendLine("INFO  network douyu: state=" + live.State + " owner=" + live.Owner + " title=" + live.Title + " err=" + live.Error);
        }
        catch (Exception ex)
        {
            _log.AppendLine("INFO  network check failed: " + ex.Message);
        }

        _log.AppendLine(string.Format("TOTAL {0} pass, {1} fail", _pass, _fail));
        string result = _log.ToString();
        try
        {
            File.WriteAllText(Path.Combine(SelfDir(), "selftest_result.txt"), result, new UTF8Encoding(true));
        }
        catch { }
        try { Console.WriteLine(result); }
        catch { }
        return _fail == 0 ? 0 : 1;
    }
}
