using System;
using System.Collections.Generic;
using System.Threading;

namespace LiveMonitor
{
    public class RoomState
    {
        public LiveState LastState = LiveState.Unknown;
        public string LastTitle = "";
        public string LastError = "";
        public DateTime? LastCheck = null;
        public int FailCount = 0;
        public bool EverChecked = false;
        public LiveState Pending = LiveState.Unknown;   // unconfirmed unreliable result
        public int PendingCount = 0;
        public DateTime? LastNotifyAt = null;
    }

    public class MonitorEvent
    {
        public RoomItem Room;
        public CheckResult Result;
        public bool BecameLive;
    }

    /// <summary>
    /// Single background thread polls all rooms every IntervalSec seconds.
    /// CPU usage is essentially zero while waiting (event-wait based).
    /// AutoResetEvent is essential: a manual-reset event stays signaled after
    /// one Wake() and would make the loop hammer the platforms in a tight loop.
    /// </summary>
    public class RoomMonitor
    {
        readonly AppConfig _cfg;
        Thread _thread;
        readonly AutoResetEvent _wake = new AutoResetEvent(false);
        volatile bool _running;
        volatile bool _paused;
        readonly Dictionary<string, RoomState> _states = new Dictionary<string, RoomState>();
        DateTime? _lastCheckEnd;

        /// <summary>Raised on the worker thread.</summary>
        public event Action<MonitorEvent> Event;
        public event Action CheckStarted;
        public event Action CheckFinished;

        /// <summary>Minimum seconds between two popup notifications for the same room.</summary>
        public int NotifyCooldownSec = 180;

        public bool Paused
        {
            get { return _paused; }
            set { _paused = value; if (!value) _wake.Set(); }
        }

        public DateTime? LastCheckEnd { get { return _lastCheckEnd; } }

        public int LiveCount
        {
            get
            {
                lock (_states)
                {
                    int c = 0;
                    foreach (var s in _states.Values)
                        if (s.LastState == LiveState.Live) c++;
                    return c;
                }
            }
        }

        public RoomMonitor(AppConfig cfg) { _cfg = cfg; }

        public void Start()
        {
            if (_thread != null) return;
            _running = true;
            try
            {
                _thread = new Thread(Loop);
                _thread.IsBackground = true;
                _thread.Name = "LiveMonitorWorker";
                _thread.Start();
            }
            catch (Exception ex)
            {
                // thread creation can fail right after logon under memory pressure;
                // wait a moment and retry once
                Log.Exception(ex);
                try { Thread.Sleep(3000); } catch { }
                try
                {
                    _thread = new Thread(Loop);
                    _thread.IsBackground = true;
                    _thread.Name = "LiveMonitorWorker";
                    _thread.Start();
                }
                catch (Exception ex2) { Log.Exception(ex2); }
            }
        }

        public void Stop()
        {
            _running = false;
            _wake.Set();
            try { if (_thread != null) _thread.Join(2000); } catch { }
        }

        public void Wake() { _wake.Set(); }

        void Loop()
        {
            _wake.WaitOne(1500);
            while (_running)
            {
                if (!_paused) CheckAll();
                _wake.WaitOne(TimeSpan.FromSeconds(_cfg.IntervalSec));
            }
        }

        public void CheckAll()
        {
            List<RoomItem> rooms;
            lock (_cfg) { rooms = new List<RoomItem>(_cfg.Rooms); }

            var started = CheckStarted;
            if (started != null) started();
            try
            {
                foreach (var room in rooms)
                {
                    if (!_running) break;
                    CheckOne(room);
                    Thread.Sleep(120);
                }
            }
            finally
            {
                _lastCheckEnd = DateTime.Now;
                var finished = CheckFinished;
                if (finished != null) finished();
            }
        }

        public void CheckRoomAsync(RoomItem room)
        {
            ThreadPool.QueueUserWorkItem(delegate { CheckOne(room); });
        }

        void CheckOne(RoomItem room)
        {
            CheckResult res;
            try { res = PlatformApi.Check(room.Platform, room.Id, room.Url); }
            catch (Exception ex) { res = new CheckResult(LiveState.Error) { Error = ex.Message }; }
            Log.Info(string.Format("check {0}:{1} => {2} title={3} err={4} src={5} start={6}",
                room.Platform, room.Id, res.State,
                res.Title == null ? "" : res.Title, res.Error == null ? "" : res.Error,
                res.Src == null ? "" : res.Src,
                res.LiveStartAt == null ? "" : ((DateTime)res.LiveStartAt).ToString("yyyy-MM-dd HH:mm:ss")));
            Raise(room, res);
        }

        /// <summary>
        /// State machine that turns a raw check result into the displayed state:
        /// - reliable results (Live/Offline) commit immediately;
        /// - unreliable results (Error/NotFound) need 2 consecutive checks before
        ///   overriding the known state (transient failures keep the last state);
        /// - a popup fires only on Offline→Live or on the very first Live result,
        ///   and at most once per NotifyCooldownSec per room.
        /// </summary>
        public void Raise(RoomItem room, CheckResult res)
        {
            bool becameLive = false;
            string transLog = "";

            lock (_states)
            {
                RoomState st;
                if (!_states.TryGetValue(room.Key, out st))
                {
                    st = new RoomState();
                    _states[room.Key] = st;
                }
                st.LastCheck = DateTime.Now;
                st.LastError = res.Error;
                if (!string.IsNullOrEmpty(res.Title)) st.LastTitle = res.Title;

                bool reliable = res.State == LiveState.Live || res.State == LiveState.Offline;

                if (!st.EverChecked)
                {
                    st.EverChecked = true;
                    st.LastState = res.State;
                    st.Pending = LiveState.Unknown;
                    st.PendingCount = 0;
                    becameLive = res.State == LiveState.Live;
                    transLog = "first=" + res.State;
                }
                else if (reliable)
                {
                    st.Pending = LiveState.Unknown;
                    st.PendingCount = 0;
                    if (res.State != st.LastState)
                    {
                        transLog = st.LastState + "→" + res.State;
                        if (res.State == LiveState.Live && st.LastState == LiveState.Offline)
                            becameLive = true;
                        st.LastState = res.State;
                    }
                }
                else
                {
                    // Error / NotFound: 2 consecutive occurrences required to override
                    if (st.Pending == res.State) st.PendingCount++;
                    else { st.Pending = res.State; st.PendingCount = 1; }

                    if (st.PendingCount >= 2)
                    {
                        transLog = st.LastState + "→" + res.State;
                        st.LastState = res.State;
                        st.Pending = LiveState.Unknown;
                        st.PendingCount = 0;
                    }
                    else
                    {
                        // keep the last known state, mark the result as transient
                        res.Sub = "网络异常";
                        res.State = st.LastState;
                        if (string.IsNullOrEmpty(res.Title) && !string.IsNullOrEmpty(st.LastTitle))
                            res.Title = st.LastTitle;
                    }
                }

                if (becameLive)
                {
                    bool allowed = true;
                    DateTime now = DateTime.Now;
                    if (st.LastNotifyAt != null
                        && (now - (DateTime)st.LastNotifyAt).TotalSeconds < NotifyCooldownSec)
                        allowed = false;
                    if (allowed) st.LastNotifyAt = now;
                    if (!allowed) Log.Info("notify suppressed (cooldown): " + room.Key);
                    becameLive = allowed;
                }
            }

            if (transLog.Length > 0)
                Log.Info(string.Format("trans {0}: {1} notify={2}", room.Key, transLog, becameLive));

            var ev = new MonitorEvent { Room = room, Result = res, BecameLive = becameLive };
            var h = Event;
            if (h != null)
            {
                try { h(ev); }
                catch (Exception ex) { Log.Exception(ex); }
            }
        }

        public RoomState GetState(RoomItem room)
        {
            lock (_states)
            {
                RoomState st;
                if (_states.TryGetValue(room.Key, out st)) return st;
                return null;
            }
        }

        public void RemoveState(RoomItem room)
        {
            lock (_states) { _states.Remove(room.Key); }
        }
    }
}
