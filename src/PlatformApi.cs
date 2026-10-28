using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace LiveMonitor
{
    public enum LiveState { Unknown, Offline, Live, NotFound, Error }

    public class CheckResult
    {
        public LiveState State = LiveState.Unknown;
        public string Title = "";
        public string Owner = "";
        public string Sub = "";     // e.g. "轮播中"
        public string Error = "";
        public DateTime? LiveStartAt = null;   // real live-start time reported by the platform
        public string Src = "";     // which data source produced this result (for diagnostics)

        public CheckResult() { }

        public CheckResult(LiveState state) { State = state; }

        public CheckResult(LiveState state, string title, string owner)
        {
            State = state;
            Title = title == null ? "" : title;
            Owner = owner == null ? "" : owner;
        }
    }

    /// <summary>
    /// Live-status checking for douyu / huya / bilibili / douyin / kuaishou.
    /// All parse methods are pure (string/dict in -> CheckResult out) so they
    /// can be exercised by the offline self-test.
    /// </summary>
    public static class PlatformApi
    {
        const string UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

        static readonly JavaScriptSerializer _json = new JavaScriptSerializer();
        static readonly HttpClient _http;
        static readonly HttpClient _douyin;
        static readonly CookieContainer _douyinCookies = new CookieContainer();

        static PlatformApi()
        {
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch { }
            _http = CreateClient(new CookieContainer());
            _douyin = CreateClient(_douyinCookies);
        }

        static HttpClient CreateClient(CookieContainer cookies)
        {
            var handler = new HttpClientHandler();
            handler.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            handler.UseCookies = true;
            handler.CookieContainer = cookies;
            var hc = new HttpClient(handler);
            hc.Timeout = TimeSpan.FromSeconds(10);
            hc.DefaultRequestHeaders.Add("User-Agent", UA);
            hc.DefaultRequestHeaders.Add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            hc.DefaultRequestHeaders.Add("Accept", "*/*");
            return hc;
        }

        public static string PlatformName(string platform)
        {
            switch (platform)
            {
                case "douyu": return "斗鱼";
                case "huya": return "虎牙";
                case "bilibili": return "B站";
                case "douyin": return "抖音";
                case "kuaishou": return "快手";
                default: return platform == null ? "" : platform;
            }
        }

        // ---------------- input parsing ----------------

        public static bool ParseInput(string input, string platform,
            out string outPlatform, out string id, out string url, out string message)
        {
            outPlatform = ""; id = ""; url = ""; message = "";
            input = (input ?? "").Trim();
            if (input.Length == 0) { message = "请输入直播间链接或房间号"; return false; }

            string lower = input.ToLower();
            if (lower.StartsWith("http://")) lower = lower.Substring(7);
            else if (lower.StartsWith("https://")) lower = lower.Substring(8);

            string host = lower, path = "";
            int slash = lower.IndexOf('/');
            if (slash >= 0)
            {
                host = lower.Substring(0, slash);
                path = lower.Substring(slash + 1);
            }

            string detected = platform == null ? "" : platform;
            if (host.Contains("douyu")) detected = "douyu";
            else if (host.Contains("huya")) detected = "huya";
            else if (host.Contains("bilibili")) detected = "bilibili";
            else if (host.Contains("douyin")) detected = "douyin";
            else if (host.Contains("kuaishou")) detected = "kuaishou";

            bool known = detected == "douyu" || detected == "huya" || detected == "bilibili"
                         || detected == "douyin" || detected == "kuaishou";
            if (!known)
            {
                message = "无法识别平台。请先选择平台,或粘贴斗鱼/虎牙/B站/抖音/快手的直播间链接";
                return false;
            }

            if (host.Contains("v.douyin.com"))
            {
                message = "暂不支持抖音短链接,请打开直播间后复制浏览器完整地址";
                return false;
            }

            if (detected == "kuaishou")
            {
                var km = Regex.Match(lower, "kuaishou\\.com/(?:u|profile)/([A-Za-z0-9_-]{2,40})");
                if (km.Success) id = km.Groups[1].Value;
                else
                {
                    var km2 = Regex.Match(input, "([A-Za-z0-9_-]{4,40})");
                    if (km2.Success) id = km2.Groups[1].Value;
                }
                if (id.Length == 0) { message = "未从链接中提取到快手用户ID"; return false; }
                url = "https://live.kuaishou.com/u/" + id;
                outPlatform = detected;
                return true;
            }

            var m = Regex.Match(lower, "(\\d{2,20})");
            if (!m.Success) { message = "未从链接中提取到数字ID"; return false; }
            id = m.Groups[1].Value;

            bool biliUid = detected == "bilibili" && host.Contains("space.bilibili.com");
            if (detected == "bilibili" && !biliUid && path.Contains("search"))
            {
                message = "请粘贴具体直播间的链接或房间号";
                return false;
            }

            if (biliUid) url = "https://space.bilibili.com/" + id + "/live";
            else
            {
                switch (detected)
                {
                    case "douyu": url = "https://www.douyu.com/" + id; break;
                    case "huya": url = "https://www.huya.com/" + id; break;
                    case "bilibili": url = "https://live.bilibili.com/" + id; break;
                    case "douyin": url = "https://live.douyin.com/" + id; break;
                }
            }
            outPlatform = detected;
            return true;
        }

        public static bool BuildUrlForRoom(string platform, string id, out string url)
        {
            url = "";
            if (string.IsNullOrEmpty(id)) return false;
            switch (platform)
            {
                case "douyu": url = "https://www.douyu.com/" + id; return true;
                case "huya": url = "https://www.huya.com/" + id; return true;
                case "bilibili": url = "https://live.bilibili.com/" + id; return true;
                case "douyin": url = "https://live.douyin.com/" + id; return true;
                case "kuaishou": url = "https://live.kuaishou.com/u/" + id; return true;
            }
            return false;
        }

        // ---------------- dispatch ----------------

        public static CheckResult Check(string platform, string id, string url)
        {
            try
            {
                switch (platform)
                {
                    case "douyu": return CheckDouyu(id);
                    case "bilibili": return CheckBilibili(id, url);
                    case "huya": return CheckHuya(id);
                    case "douyin": return CheckDouyin(id);
                    case "kuaishou": return CheckKuaishou(id);
                    default: return new CheckResult(LiveState.Error) { Error = "未知平台" };
                }
            }
            catch (Exception ex)
            {
                return new CheckResult(LiveState.Error) { Error = ShortError(ex) };
            }
        }

        static string ShortError(Exception ex)
        {
            if (ex is HttpRequestException)
            {
                string s = ex.Message;
                if (s.Contains("412")) s = s + " (平台限流,稍后自动恢复)";
                return s;
            }
            if (ex is System.Threading.Tasks.TaskCanceledException
                || ex is OperationCanceledException) return "请求超时";
            if (ex is WebException) return "网络错误";
            string m = ex.Message;
            return m.Length > 120 ? m.Substring(0, 120) : m;
        }

        // ---------------- HTTP helpers ----------------

        static string Get(HttpClient hc, string url)
        {
            return Get(hc, url, null);
        }

        static string Get(HttpClient hc, string url, string referer)
        {
            try
            {
                using (var req = new HttpRequestMessage(HttpMethod.Get, url))
                {
                    if (!string.IsNullOrEmpty(referer))
                    {
                        try { req.Headers.Referrer = new Uri(referer); } catch { }
                    }
                    using (var resp = hc.SendAsync(req).Result)
                    {
                        if ((int)resp.StatusCode >= 400)
                            throw new HttpRequestException("HTTP " + (int)resp.StatusCode + " " + url);
                        return resp.Content.ReadAsStringAsync().Result;
                    }
                }
            }
            catch (AggregateException ae)
            {
                var inner = ae.Flatten().InnerException;
                if (inner != null) throw inner;
                throw;
            }
        }

        static Dictionary<string, object> GetJson(HttpClient hc, string url)
        {
            return GetJson(hc, url, null);
        }

        static Dictionary<string, object> GetJson(HttpClient hc, string url, string referer)
        {
            string txt = Get(hc, url, referer);
            var o = _json.DeserializeObject(txt) as Dictionary<string, object>;
            if (o == null) throw new InvalidOperationException("JSON解析失败");
            return o;
        }

        static long GetInt(Dictionary<string, object> o, string key)
        {
            object v;
            if (o != null && o.TryGetValue(key, out v) && v != null)
            {
                if (v is int) return (int)v;
                if (v is long) return (long)v;
                if (v is decimal) return (long)(decimal)v;
                if (v is double) return (long)(double)v;
                if (v is string)
                {
                    long r;
                    if (long.TryParse((string)v, out r)) return r;
                }
                try { return Convert.ToInt64(v); } catch { }
            }
            return 0;
        }

        static string GetStr(Dictionary<string, object> o, string key)
        {
            object v;
            if (o != null && o.TryGetValue(key, out v) && v != null) return Convert.ToString(v);
            return "";
        }

        static string RegexGroup(string input, string pattern)
        {
            var m = Regex.Match(input, pattern);
            return m.Success ? Unescape(m.Groups[1].Value) : "";
        }

        public static string Unescape(string s)
        {
            if (s == null || s.IndexOf("\\u") < 0) return s;
            return Regex.Replace(s, "\\\\u([0-9a-fA-F]{4})", delegate(Match m)
            {
                int code = Convert.ToInt32(m.Groups[1].Value, 16);
                return ((char)code).ToString();
            });
        }

        /// <summary>Parses "2026-08-15 14:00:00" style timestamps, tolerant of junk like "0000-00-00".</summary>
        public static DateTime? ParseDate(string s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            DateTime d;
            if (DateTime.TryParse(s, out d))
            {
                if (d.Year < 2000) return null;
                return d;
            }
            return null;
        }

        public static DateTime? ParseUnix(long sec)
        {
            if (sec <= 0) return null;
            try
            {
                var dto = DateTimeOffset.FromUnixTimeSeconds(sec);
                DateTime local = dto.LocalDateTime;
                if (local.Year < 2000) return null;
                return local;
            }
            catch { return null; }
        }

        // ---------------- douyu ----------------

        public static CheckResult CheckDouyu(string id)
        {
            string errCode = "";
            try
            {
                string txt = Get(_http, "https://open.douyucdn.cn/api/RoomApi/room/" + id);
                var o = _json.DeserializeObject(txt) as Dictionary<string, object>;
                if (o == null) throw new InvalidOperationException("JSON解析失败");
                long err = GetInt(o, "error");
                if (err != 0)
                {
                    // non-zero error may be rate-limiting, not "room missing" —
                    // fall through to the betard endpoint before concluding anything
                    errCode = err.ToString();
                    throw new InvalidOperationException("douyu error " + errCode);
                }
                var r1 = ParseDouyu(txt);
                r1.Src = "douyu-api";
                return r1;
            }
            catch (Exception)
            {
                try
                {
                    string txt = Get(_http, "https://www.douyu.com/betard/" + id, "https://www.douyu.com/");
                    var r2 = ParseDouyuBetard(txt);
                    r2.Src = "douyu-betard";
                    return r2;
                }
                catch (Exception)
                {
                    if (errCode == "105" || errCode == "404")
                        return new CheckResult(LiveState.NotFound) { Error = "房间不存在(code=" + errCode + ")" };
                    throw;
                }
            }
        }

        public static CheckResult ParseDouyu(string txt)
        {
            var o = _json.DeserializeObject(txt) as Dictionary<string, object>;
            if (o == null) throw new InvalidOperationException("JSON解析失败");
            if (GetInt(o, "error") != 0)
                return new CheckResult(LiveState.NotFound) { Error = "房间不存在" };
            var d = o.ContainsKey("data") ? o["data"] as Dictionary<string, object> : null;
            if (d == null) throw new InvalidOperationException("JSON结构异常");
            bool live = GetStr(d, "room_status") == "1";
            var res = new CheckResult(live ? LiveState.Live : LiveState.Offline,
                GetStr(d, "room_name"), GetStr(d, "owner_name"));
            if (live) res.LiveStartAt = ParseDate(GetStr(d, "start_time"));
            return res;
        }

        public static CheckResult ParseDouyuBetard(string txt)
        {
            var o = _json.DeserializeObject(txt) as Dictionary<string, object>;
            if (o == null) throw new InvalidOperationException("JSON解析失败");
            var room = o.ContainsKey("room") ? o["room"] as Dictionary<string, object> : null;
            if (room == null) throw new InvalidOperationException("JSON结构异常");
            bool live = GetStr(room, "show_status") == "1";
            var res = new CheckResult(live ? LiveState.Live : LiveState.Offline,
                GetStr(room, "room_name"), GetStr(room, "nickname"));
            if (live) res.LiveStartAt = ParseDate(GetStr(room, "start_time"));
            return res;
        }

        // ---------------- bilibili ----------------

        static readonly object _biliCookieLock = new object();
        static DateTime _biliCookieAt = DateTime.MinValue;

        /// <summary>Visit the bilibili homepage to collect buvid3/b_nut cookies —
        /// requests without them get HTTP 412 from the API's anti-crawl WAF.</summary>
        static void EnsureBiliCookies()
        {
            lock (_biliCookieLock)
            {
                if ((DateTime.Now - _biliCookieAt).TotalMinutes < 20) return;
                try
                {
                    string _ = Get(_http, "https://www.bilibili.com/", "https://www.bilibili.com/");
                }
                catch { }
                _biliCookieAt = DateTime.Now;
            }
        }

        public static CheckResult CheckBilibili(string id, string url)
        {
            EnsureBiliCookies();
            try
            {
                return CheckBilibiliInner(id, url);
            }
            catch (HttpRequestException ex)
            {
                if (ex.Message.Contains("412"))
                {
                    // cookies rejected: re-warm once and retry
                    lock (_biliCookieLock) { _biliCookieAt = DateTime.MinValue; }
                    EnsureBiliCookies();
                    return CheckBilibiliInner(id, url);
                }
                throw;
            }
        }

        static CheckResult CheckBilibiliInner(string id, string url)
        {
            // A transient failure of the room-id endpoint must NEVER fall back to
            // interpreting the room id as a user uid — that would return a
            // DIFFERENT user's live state and make the status flip back and forth.
            bool isUid = url != null && url.Contains("space.bilibili.com");

            // Primary for room ids: the public live-room web page — exactly what a
            // browser loads. It carries live_status AND the anchor name in the
            // embedded state, and it is the least likely request to be risk-flagged.
            if (!isUid)
            {
                try
                {
                    var pageRes = CheckBiliPage("https://live.bilibili.com/" + id);
                    if (pageRes != null) return pageRes;
                }
                catch (HttpRequestException) { throw; }
                catch (Exception ex) { Log.Info("bili page check failed: " + ex.Message); }
            }

            // API fallbacks (lighter, but sometimes gated by risk control)
            if (isUid)
            {
                try
                {
                    var old = GetJson(_http, "https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=" + id,
                        "https://live.bilibili.com/");
                    var uidRes = ParseBiliGetRoomInfoOld(old);
                    uidRes.Src = "bili-getRoomInfoOld";
                    return uidRes;
                }
                catch (HttpRequestException) { throw; }
                catch (Exception) { }
                try
                {
                    var pageRes = CheckBiliPage("https://space.bilibili.com/" + id + "/live");
                    if (pageRes != null) return pageRes;
                }
                catch (HttpRequestException) { throw; }
                catch (Exception ex) { Log.Info("bili space page check failed: " + ex.Message); }
                throw new InvalidOperationException("B站用户直播间检测失败");
            }

            try
            {
                string raw = Get(_http,
                    "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom?room_id=" + id,
                    "https://live.bilibili.com/");
                var o = _json.DeserializeObject(raw) as Dictionary<string, object>;
                var res = o == null ? null : ParseBiliGetInfoByRoom(o);
                if (res != null)
                {
                    res.Src = "bili-getInfoByRoom";
                    return res;
                }
                Log.Info("bili getInfoByRoom unexpected response: " + Truncate(raw));
            }
            catch (HttpRequestException) { throw; }
            catch (Exception ex) { Log.Info("bili getInfoByRoom failed: " + ex.Message); }

            try
            {
                var init = GetJson(_http, "https://api.live.bilibili.com/room/v1/Room/room_init?id=" + id,
                    "https://live.bilibili.com/");
                if (GetInt(init, "code") == 0)
                {
                    var dd = init.ContainsKey("data") ? init["data"] as Dictionary<string, object> : null;
                    if (dd != null)
                    {
                        string realId = GetStr(dd, "room_id");
                        if (realId.Length > 0)
                        {
                            var info = GetJson(_http, "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + realId,
                                "https://live.bilibili.com/");
                            var res = ParseBiliGetInfo(info);
                            res.Src = "bili-get_info";
                            if (res.State != LiveState.NotFound)
                            {
                                // best-effort anchor name via uid
                                var d2 = info.ContainsKey("data") ? info["data"] as Dictionary<string, object> : null;
                                long uid = GetInt(d2, "uid");
                                if (uid > 0)
                                {
                                    try
                                    {
                                        var old = GetJson(_http,
                                            "https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=" + uid,
                                            "https://live.bilibili.com/");
                                        var ores = ParseBiliGetRoomInfoOld(old);
                                        if (ores.Owner.Length > 0) res.Owner = ores.Owner;
                                    }
                                    catch (Exception) { }
                                }
                            }
                            return res;
                        }
                    }
                }
                throw new InvalidOperationException("room_init未返回房间信息");
            }
            catch (HttpRequestException) { throw; }
            catch (Exception ex) { Log.Info("bili room_init/get_info failed: " + ex.Message); }

            throw new InvalidOperationException("B站检测失败(所有途径均不可用)");
        }

        static CheckResult CheckBiliPage(string url)
        {
            string page = Get(_http, url, "https://www.bilibili.com/");
            var res = ParseBiliLivePage(page);
            if (res == null) Log.Info("bili page unparseable: " + url + " len=" + page.Length);
            else res.Src = "bili-page";
            return res;
        }

        static string Truncate(string s)
        {
            if (s == null) return "";
            return s.Length > 300 ? s.Substring(0, 300) : s;
        }

        /// <summary>Parses a live.bilibili.com room page: live_status + anchor name from the embedded state.</summary>
        public static CheckResult ParseBiliLivePage(string html)
        {
            if (html == null) return null;
            var m = Regex.Match(html, "\"live_status\"\\s*:\\s*(\\d)");
            if (!m.Success) return null;
            int st = int.Parse(m.Groups[1].Value);

            // name/title live in the same embedded JSON block, usually after the status
            string tail = html.Substring(m.Index);
            string uname = RegexGroup(tail, "\"uname\"\\s*:\\s*\"([^\"]{1,60})\"");
            if (uname.Length == 0) uname = RegexGroup(html, "\"uname\"\\s*:\\s*\"([^\"]{1,60})\"");
            string title = RegexGroup(tail, "\"title\"\\s*:\\s*\"([^\"]{1,120})\"");
            if (title.Length == 0) title = RegexGroup(html, "\"title\"\\s*:\\s*\"([^\"]{1,120})\"");

            LiveState state = (st == 1 || st == 2) ? LiveState.Live : LiveState.Offline;
            var res = new CheckResult(state, title, uname);
            if (st == 2) res.Sub = "轮播中";
            if (state == LiveState.Live)
            {
                // roomInitRes style: "live_time":"2026-08-15 14:00:00"
                var tm = Regex.Match(tail, "\"live_time\"\\s*:\\s*\"([^\"]{4,30})\"");
                if (!tm.Success) tm = Regex.Match(html, "\"live_time\"\\s*:\\s*\"([^\"]{4,30})\"");
                if (tm.Success) res.LiveStartAt = ParseDate(tm.Groups[1].Value);

                // roomInfoRes (getInfoByRoom) style: "live_start_time":1786793400
                if (res.LiveStartAt == null)
                {
                    var um = Regex.Match(tail, "\"live_start_time\"\\s*:\\s*(\\d{9,12})");
                    if (!um.Success) um = Regex.Match(html, "\"live_start_time\"\\s*:\\s*(\\d{9,12})");
                    if (um.Success) res.LiveStartAt = ParseUnix(long.Parse(um.Groups[1].Value));
                }

                // camelCase variant: "liveTime":"..."
                if (res.LiveStartAt == null)
                {
                    var cm = Regex.Match(tail, "\"liveTime\"\\s*:\\s*\"([^\"]{4,30})\"");
                    if (!cm.Success) cm = Regex.Match(html, "\"liveTime\"\\s*:\\s*\"([^\"]{4,30})\"");
                    if (cm.Success) res.LiveStartAt = ParseDate(cm.Groups[1].Value);
                }
            }
            return res;
        }

        /// <summary>Parses xlive/web-room/v1/index/getInfoByRoom: live status + anchor name in one shot.</summary>
        public static CheckResult ParseBiliGetInfoByRoom(Dictionary<string, object> o)
        {
            if (GetInt(o, "code") != 0) return null;
            var data = o.ContainsKey("data") ? o["data"] as Dictionary<string, object> : null;
            if (data == null) return null;
            var ri = data.ContainsKey("room_info") ? data["room_info"] as Dictionary<string, object> : null;
            if (ri == null) return null;
            long live = GetInt(ri, "live_status");
            string title = GetStr(ri, "title");
            string uname = "";
            var ai = data.ContainsKey("anchor_info") ? data["anchor_info"] as Dictionary<string, object> : null;
            if (ai != null)
            {
                var bb = ai.ContainsKey("base_info") ? ai["base_info"] as Dictionary<string, object> : null;
                if (bb != null) uname = GetStr(bb, "uname");
            }
            LiveState st = (live == 1 || live == 2) ? LiveState.Live : LiveState.Offline;
            var res = new CheckResult(st, title, uname);
            if (live == 2) res.Sub = "轮播中";
            if (st == LiveState.Live) res.LiveStartAt = ParseUnix(GetInt(ri, "live_start_time"));
            return res;
        }

        public static CheckResult ParseBiliGetInfo(Dictionary<string, object> o)
        {
            if (GetInt(o, "code") != 0)
                return new CheckResult(LiveState.NotFound) { Error = "房间不存在" };
            var d = o.ContainsKey("data") ? o["data"] as Dictionary<string, object> : null;
            if (d == null) throw new InvalidOperationException("JSON结构异常");
            long live = GetInt(d, "live_status");
            LiveState st = (live == 1 || live == 2) ? LiveState.Live : LiveState.Offline;
            var res = new CheckResult(st, GetStr(d, "title"), "");
            if (live == 2) res.Sub = "轮播中";
            if (st == LiveState.Live) res.LiveStartAt = ParseDate(GetStr(d, "live_time"));
            return res;
        }

        public static CheckResult ParseBiliGetRoomInfoOld(Dictionary<string, object> o)
        {
            if (GetInt(o, "code") != 0)
                return new CheckResult(LiveState.NotFound) { Error = "房间不存在" };
            var d = o.ContainsKey("data") ? o["data"] as Dictionary<string, object> : null;
            if (d == null) throw new InvalidOperationException("JSON结构异常");
            long live = GetInt(d, "liveStatus");
            long round = GetInt(d, "roundStatus");
            var res = new CheckResult(live == 1 ? LiveState.Live : LiveState.Offline,
                GetStr(d, "title"), GetStr(d, "uname"));
            if (live == 0 && round == 1) { res.State = LiveState.Live; res.Sub = "轮播中"; }
            if (res.State == LiveState.Live) res.LiveStartAt = ParseDate(GetStr(d, "liveTime"));
            return res;
        }

        // ---------------- huya ----------------

        public static CheckResult CheckHuya(string id)
        {
            string html = Get(_http, "https://www.huya.com/" + id);
            CheckResult res = ParseHuyaHtml(html);
            if (res != null) { res.Src = "huya-page"; return res; }
            string mhtml = Get(_http, "https://m.huya.com/" + id);
            res = ParseHuyaHtml(mhtml);
            if (res != null) { res.Src = "huya-page"; return res; }
            return new CheckResult(LiveState.Unknown) { Error = "无法解析虎牙页面" };
        }

        public static CheckResult ParseHuyaHtml(string html)
        {
            if (html == null) return null;
            if (html.Contains("页面不存在") || html.Contains("没有找到该主播"))
                return new CheckResult(LiveState.NotFound) { Error = "房间不存在" };
            var m = Regex.Match(html, "\"isOn\"\\s*:\\s*(true|false)");
            if (!m.Success) return null;
            bool live = m.Groups[1].Value == "true";
            string nick = RegexGroup(html, "\"sNick\"\\s*:\\s*\"([^\"]{1,60})\"");
            string title = RegexGroup(html, "\"sRoomName\"\\s*:\\s*\"([^\"]{1,120})\"");
            if (title.Length == 0)
                title = RegexGroup(html, "\"introduction\"\\s*:\\s*\"([^\"]{1,120})\"");
            return new CheckResult(live ? LiveState.Live : LiveState.Offline, title, nick);
        }

        // ---------------- douyin ----------------

        public static CheckResult CheckDouyin(string id)
        {
            string page = Get(_douyin, "https://live.douyin.com/" + id);
            if (page == null || page.Length < 500)
                return new CheckResult(LiveState.Error) { Error = "页面无内容" };
            if (page.Contains("直播已结束") && page.IndexOf("\"status\"") < 0)
                return new CheckResult(LiveState.Offline);

            string nonce = RegexGroup(page, "window\\.__ac_nonce\\s*=\\s*\"([^\"]+)\"");
            if (nonce.Length > 0)
            {
                try { _douyinCookies.Add(new Cookie("__ac_nonce", nonce, "/", ".douyin.com")); }
                catch { }
            }
            string roomId = RegexGroup(page, "\"roomId\"\\s*:\\s*\"(\\d+)\"");

            if (roomId.Length > 0 && nonce.Length > 0)
            {
                string url =
                    "https://live.douyin.com/webcast/room/web/enter/?aid=6383&app_name=douyin_web&live_id=1" +
                    "&device_platform=web&language=zh-CN&cookie_enabled=true&browser_language=zh-CN" +
                    "&browser_platform=Win32&browser_name=Chrome&browser_version=126.0.0.0" +
                    "&web_rid=" + id + "&room_id=" + roomId +
                    "&enter_from=web_live&is_need_double_stream=false&source=0";
                string resp = Get(_douyin, url);
                CheckResult r = ParseDouyinEnter(resp);
                if (r != null) { r.Src = "douyin-enter"; return r; }
            }

            // HTML fallback: "web_rid" then "status" nearby
            var m = Regex.Match(page,
                "\"web_rid\"\\s*:\\s*\"?" + id + "\"?\\s*[,\\}][\\s\\S]{0,600}?\"status\"\\s*:\\s*(\\d)");
            if (m.Success)
            {
                int st = int.Parse(m.Groups[1].Value);
                if (st == 2)
                {
                    var r1 = new CheckResult(LiveState.Live);
                    r1.Src = "douyin-page";
                    return r1;
                }
                if (st == 4)
                {
                    var r2 = new CheckResult(LiveState.Offline);
                    r2.Src = "douyin-page";
                    return r2;
                }
            }
            return new CheckResult(LiveState.Unknown) { Error = "抖音接口受限,未能确认状态" };
        }

        public static CheckResult ParseDouyinEnter(string txt)
        {
            try
            {
                var o = _json.DeserializeObject(txt) as Dictionary<string, object>;
                if (o == null) return null;
                var data = o.ContainsKey("data") ? o["data"] as Dictionary<string, object> : null;
                if (data == null) return null;
                var arr = data.ContainsKey("data") ? data["data"] as object[] : null;
                if (arr == null || arr.Length == 0) return null;
                var room = arr[0] as Dictionary<string, object>;
                if (room == null) return null;
                long status = GetInt(room, "status");
                string title = GetStr(room, "title");
                string nick = "";
                var owner = room.ContainsKey("owner") ? room["owner"] as Dictionary<string, object> : null;
                if (owner != null) nick = GetStr(owner, "nickname");
                if (status == 2) return new CheckResult(LiveState.Live, title, nick);
                if (status == 4) return new CheckResult(LiveState.Offline, title, nick);
                return null;
            }
            catch { return null; }
        }

        // ---------------- kuaishou ----------------

        public static CheckResult CheckKuaishou(string id)
        {
            string html = Get(_http, "https://live.kuaishou.com/u/" + id);
            var res = ParseKuaishouHtml(html);
            res.Src = "kuaishou-page";
            return res;
        }

        public static CheckResult ParseKuaishouHtml(string html)
        {
            if (html == null)
                return new CheckResult(LiveState.Unknown) { Error = "页面无内容" };
            var m = Regex.Match(html, "\"isLiving\"\\s*:\\s*(true|false)");
            if (!m.Success)
            {
                if (html.Length < 1000)
                    return new CheckResult(LiveState.Unknown) { Error = "页面无内容" };
                return new CheckResult(LiveState.Unknown) { Error = "未能解析直播状态" };
            }
            bool live = m.Groups[1].Value == "true";

            string name = "";
            var nm = Regex.Match(html, "\"name\"\\s*:\\s*\"([^\"]{1,40})\"[^}]{0,800}?\"isLiving\"");
            if (!nm.Success)
                nm = Regex.Match(html, "\"isLiving\"[^}]{0,800}?\"name\"\\s*:\\s*\"([^\"]{1,40})\"");
            if (nm.Success) name = Unescape(nm.Groups[1].Value);

            string title = RegexGroup(html, "\"caption\"\\s*:\\s*\"([^\"]{1,120})\"");
            return new CheckResult(live ? LiveState.Live : LiveState.Offline, title, name);
        }
    }
}
