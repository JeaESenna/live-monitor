using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using WinFormsTimer = System.Windows.Forms.Timer;

namespace LiveMonitor
{
    public class MainForm : Form
    {
        AppConfig _cfg;
        RoomMonitor _monitor;
        DataGridView _grid;
        Label _statusLabel, _emptyLabel;
        FlatButton _btnAdd, _btnDelete, _btnOpen, _btnCheck, _btnSettings;
        NotifyIcon _tray;
        ToolStripMenuItem _miPause, _miNotify;

        // grid column indexes (kept as constants: the columns are updated from
        // several places, hard-coded numbers are too easy to break)
        const int COL_STATUS = 0, COL_NAME = 1, COL_PLATFORM = 2, COL_AREA = 3, COL_ROOMID = 4,
                  COL_TITLE = 5, COL_NOTIFY = 6, COL_CHECK = 7, COL_LIVE = 8;
        WinFormsTimer _uiTimer, _bootTimer, _healthTimer;
        ContextMenuStrip _trayMenu;
        string _exitReason = "";
        readonly List<NotifyForm> _popups = new List<NotifyForm>();
        readonly HashSet<string> _knownKeys = new HashSet<string>();
        readonly HashSet<string> _mutedKeys = new HashSet<string>();
        readonly Dictionary<string, int> _rowByKey = new Dictionary<string, int>();
        bool _forceExit;
        bool _firstMinimizeHint;
        bool _checking;
        Icon _appIcon;
        bool _startHidden;          // launched with -m/--minimized: start in the tray
        bool _hiddenStartHandled;
        bool _inResize;
        volatile bool _autostartCleanupDone;   // set by the cleanup thread, persisted by the UI thread
        bool _autostartCleanupSaved;

        // Broadcast message Explorer sends after it (re)starts; receiving it here
        // means the shell crashed/restarted and our tray icon was re-registered.
        static readonly uint WM_TASKBARCREATED = RegisterWindowMessage("TaskbarCreated");

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern uint RegisterWindowMessage(string lpString);
        [DllImport("user32.dll")]
        static extern uint GetGuiResources(IntPtr hProcess, uint uiFlags);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr h);

        public MainForm(bool minimized)
        {
            _cfg = AppConfig.Load();
            Log.Info("start: rooms=" + _cfg.Rooms.Count + " interval=" + _cfg.IntervalSec);
            Log.Info(string.Format("env: os={0} clr={1} exe={2} data={3} smallIcon={4}",
                Environment.OSVersion,
                Environment.Version,
                Application.ExecutablePath,
                AppConfig.DataDir,
                SystemInformation.SmallIconSize));

            Text = "直播监测";
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(1000, 560);
            Size = new Size(1120, 660);
            BackColor = Ui.Bg;
            Font = Ui.F(9, FontStyle.Regular);
            DoubleBuffered = true;

            _appIcon = LoadAppIcon();
            Icon = _appIcon;

            BuildUi();
            BuildTray();

            _monitor = new RoomMonitor(_cfg);
            _monitor.RoomExists = delegate(RoomItem r) { lock (_cfg) { return _cfg.Rooms.Contains(r); } };
            _monitor.Event += OnMonitorEvent;
            _monitor.CheckStarted += delegate
            {
                try { BeginInvoke((Action)delegate { _checking = true; UpdateStatus(); }); }
                catch { }
            };
            _monitor.CheckFinished += delegate
            {
                try { BeginInvoke((Action)delegate { _checking = false; UpdateStatus(); }); }
                catch { }
            };

            // Remove auto-start entries left by older versions. This is a one-time
            // job (it spawns schtasks.exe), so run it on a background thread and
            // remember it in the config instead of on every UI startup.
            if (!_cfg.LegacyAutostartCleaned)
            {
                ThreadPool.QueueUserWorkItem(delegate
                {
                    try
                    {
                        SettingsForm.CleanupAutostart();
                        // Persisting the marker is done by the UI thread (UpdateStatus)
                        // so it still happens even if this completes before the form
                        // handle exists (BeginInvoke would fail in that window).
                        _autostartCleanupDone = true;
                    }
                    catch (Exception ex) { Log.Exception(ex); }
                });
            }

            // start the worker thread shortly after the UI is up
            _bootTimer = new WinFormsTimer();
            _bootTimer.Interval = 3000;
            _bootTimer.Tick += delegate
            {
                _bootTimer.Stop();
                _bootTimer.Dispose();
                try { _monitor.Start(); }
                catch (Exception ex) { Log.Exception(ex); }
            };
            _bootTimer.Start();

            _uiTimer = new WinFormsTimer();
            _uiTimer.Interval = 500;
            _uiTimer.Tick += delegate { UpdateStatus(); };
            _uiTimer.Start();

            // hourly self-diagnostic line: makes a long-run problem visible in
            // app.log (handle/GDI/memory trend) without any external tool
            _healthTimer = new WinFormsTimer();
            _healthTimer.Interval = 60 * 60 * 1000;
            _healthTimer.Tick += delegate { LogHealth(); };
            _healthTimer.Start();

            LoadRoomsToGrid();

            // NOTE: do NOT set WindowState=Minimized here.  Doing that from the
            // constructor made OnResize->Hide()->ShowInTaskbar re-enter each other
            // while the handle was being created, overflowing the stack
            // (0xC00000FD "无法创建新的堆栈防护页面") whenever the app was started
            // with -m/--minimized.  SetVisibleCore (below) starts hidden instead.
            _startHidden = minimized;
        }

        /// <summary>Classic "start in tray" pattern: create the window handle but do
        /// not show the window the first time Application.Run asks for it.</summary>
        protected override void SetVisibleCore(bool value)
        {
            if (!_hiddenStartHandled && _startHidden)
            {
                _hiddenStartHandled = true;
                try { if (!IsHandleCreated) CreateHandle(); } catch { }
                base.SetVisibleCore(false);
                return;
            }
            base.SetVisibleCore(value);
        }

        // ---------------- UI construction ----------------

        void BuildUi()
        {
            // ---- center (grid): added FIRST so it docks last and is not overlapped ----
            var center = new Panel();
            center.Dock = DockStyle.Fill;
            center.Padding = new Padding(16, 12, 16, 8);
            center.BackColor = Ui.Bg;
            Controls.Add(center);

            _grid = BuildGrid();
            center.Controls.Add(_grid);

            _emptyLabel = new Label();
            _emptyLabel.Text = "还没有监测的直播间\r\n点击右上角「+ 添加直播间」开始监测";
            _emptyLabel.TextAlign = ContentAlignment.MiddleCenter;
            _emptyLabel.Dock = DockStyle.Fill;
            _emptyLabel.ForeColor = Ui.SubText;
            _emptyLabel.Font = Ui.F(10, FontStyle.Regular);
            center.Controls.Add(_emptyLabel);
            _emptyLabel.BringToFront();

            // ---- top bar ----
            var top = new Panel();
            top.Dock = DockStyle.Top;
            top.Height = 64;
            top.BackColor = Ui.Bg;
            top.Padding = new Padding(16, 0, 16, 0);
            Controls.Add(top);

            var sep = new Panel();
            sep.Dock = DockStyle.Bottom;
            sep.Height = 1;
            sep.BackColor = Ui.Border;
            top.Controls.Add(sep);

            if (_appIcon != null)
            {
                var pic = new PictureBox();
                pic.Bounds = new Rectangle(0, 12, 40, 40);
                pic.SizeMode = PictureBoxSizeMode.Zoom;
                pic.Image = Ui.AppImage;
                top.Controls.Add(pic);
            }

            var lblTitle = new Label();
            lblTitle.Text = "直播监测";
            lblTitle.Font = Ui.F(14, FontStyle.Bold);
            lblTitle.ForeColor = Ui.Text;
            lblTitle.AutoSize = true;
            lblTitle.Location = new Point(50, 8);
            top.Controls.Add(lblTitle);

            var lblSub = new Label();
            lblSub.Text = "实时监测主播开播 · 轻量后台运行";
            lblSub.Font = Ui.F(8, FontStyle.Regular);
            lblSub.ForeColor = Ui.SubText;
            lblSub.AutoSize = true;
            lblSub.Location = new Point(52, 36);
            top.Controls.Add(lblSub);

            var flow = new FlowLayoutPanel();
            flow.Dock = DockStyle.Right;
            flow.Width = 460;
            flow.FlowDirection = FlowDirection.RightToLeft;
            flow.WrapContents = false;
            flow.Padding = new Padding(0, 16, 0, 0);
            top.Controls.Add(flow);

            _btnSettings = MakeButton("设置", 68, false);
            _btnSettings.Click += delegate
            {
                using (var f = new SettingsForm(_cfg))
                {
                    if (f.ShowDialog(this) == DialogResult.OK && f.Saved) UpdateStatus();
                }
            };
            flow.Controls.Add(_btnSettings);

            _btnCheck = MakeButton("立即检测", 88, false);
            _btnCheck.Click += delegate { _monitor.Wake(); };
            flow.Controls.Add(_btnCheck);

            _btnOpen = MakeButton("打开直播间", 100, false);
            _btnOpen.Enabled = false;
            _btnOpen.Click += OpenSelected;
            flow.Controls.Add(_btnOpen);

            _btnDelete = MakeButton("删除", 68, false);
            _btnDelete.Enabled = false;
            _btnDelete.Click += DeleteSelected;
            flow.Controls.Add(_btnDelete);

            _btnAdd = MakeButton("+ 添加直播间", 108, true);
            _btnAdd.Click += AddRoom;
            flow.Controls.Add(_btnAdd);

            // ---- bottom status bar ----
            var bottom = new Panel();
            bottom.Dock = DockStyle.Bottom;
            bottom.Height = 34;
            bottom.BackColor = Ui.PanelBg;
            bottom.Padding = new Padding(16, 0, 16, 0);
            Controls.Add(bottom);

            var sepB = new Panel();
            sepB.Dock = DockStyle.Top;
            sepB.Height = 1;
            sepB.BackColor = Ui.Border;
            bottom.Controls.Add(sepB);

            _statusLabel = new Label();
            _statusLabel.Dock = DockStyle.Fill;
            _statusLabel.TextAlign = ContentAlignment.MiddleLeft;
            _statusLabel.ForeColor = Ui.SubText;
            _statusLabel.Font = Ui.F(8.5f, FontStyle.Regular);
            _statusLabel.Text = "正在启动…";
            bottom.Controls.Add(_statusLabel);
        }

        FlatButton MakeButton(string text, int width, bool primary)
        {
            var b = new FlatButton();
            b.Text = text;
            b.Width = width;
            b.Height = 32;
            b.Margin = new Padding(6, 0, 0, 0);
            if (!primary)
            {
                b.Back = Ui.PanelBg;
                b.Hover = Color.FromArgb(233, 238, 246);
                b.Fore = Ui.Text;
                b.UseBorder = true;
            }
            return b;
        }

        DataGridView BuildGrid()
        {
            var g = new DataGridView();
            g.Dock = DockStyle.Fill;
            g.BorderStyle = BorderStyle.None;
            g.BackgroundColor = Color.White;
            g.CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal;
            g.GridColor = Ui.GridLine;
            g.ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.None;
            g.ColumnHeadersHeight = 38;
            g.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing;
            g.EnableHeadersVisualStyles = false;
            g.ColumnHeadersDefaultCellStyle = new DataGridViewCellStyle
            {
                BackColor = Ui.PanelBg,
                ForeColor = Ui.SubText,
                Font = Ui.F(8.5f, FontStyle.Regular),
                SelectionBackColor = Ui.PanelBg,
                SelectionForeColor = Ui.SubText,
                Alignment = DataGridViewContentAlignment.MiddleLeft,
                Padding = new Padding(10, 0, 0, 0)
            };
            g.RowTemplate.Height = 38;
            g.RowHeadersVisible = false;
            g.AllowUserToAddRows = false;
            g.AllowUserToResizeRows = false;
            g.AllowUserToDeleteRows = false;
            g.ReadOnly = true;
            g.MultiSelect = true;
            g.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            g.DefaultCellStyle = new DataGridViewCellStyle
            {
                BackColor = Color.White,
                ForeColor = Ui.Text,
                Font = Ui.F(9, FontStyle.Regular),
                SelectionBackColor = Ui.RowHover,
                SelectionForeColor = Ui.Text,
                Alignment = DataGridViewContentAlignment.MiddleLeft,
                Padding = new Padding(10, 0, 0, 0)
            };
            Ui.EnableDoubleBuffer(g);

            AddCol(g, "状态", 118);
            AddCol(g, "主播", 140);
            AddCol(g, "平台", 58);
            AddCol(g, "分区", 150);
            AddCol(g, "房间号", 92);
            AddCol(g, "直播标题", 200);
            AddCol(g, "提醒", 50);
            AddCol(g, "最后检测", 90);
            AddCol(g, "上次开播", 86);

            g.Columns[COL_TITLE].AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
            g.Columns[COL_TITLE].MinimumWidth = 140;

            g.CellFormatting += GridCellFormatting;
            g.CellDoubleClick += delegate(object s, DataGridViewCellEventArgs e)
            {
                if (e.RowIndex >= 0 && e.RowIndex < g.Rows.Count)
                    OpenRoom((RoomItem)g.Rows[e.RowIndex].Tag);
            };
            g.CellMouseDown += delegate(object s, DataGridViewCellMouseEventArgs e)
            {
                if (e.Button == MouseButtons.Right && e.RowIndex >= 0)
                {
                    g.ClearSelection();
                    g.Rows[e.RowIndex].Selected = true;
                    g.CurrentCell = g.Rows[e.RowIndex].Cells[e.ColumnIndex];
                }
            };
            // clicking empty space below the rows clears the selection
            g.MouseDown += delegate(object s, MouseEventArgs e)
            {
                var ht = g.HitTest(e.X, e.Y);
                if (ht.RowIndex < 0)
                {
                    g.ClearSelection();
                    try { g.CurrentCell = null; } catch { }
                }
            };
            g.SelectionChanged += delegate
            {
                bool any = g.SelectedRows.Count > 0;
                _btnOpen.Enabled = any;
                _btnDelete.Enabled = any;
            };

            var cm = new ContextMenuStrip();
            cm.Items.Add("打开直播间", null, delegate { OpenSelected(null, EventArgs.Empty); });
            cm.Items.Add("立即检测该房间", null, delegate { CheckSelectedNow(); });
            _miNotify = new ToolStripMenuItem("关闭开播提醒");
            _miNotify.Click += ToggleNotify;
            cm.Items.Add(_miNotify);
            cm.Items.Add("复制链接", null, delegate { CopySelectedUrl(); });
            cm.Items.Add(new ToolStripSeparator());
            cm.Items.Add("删除", null, delegate { DeleteSelected(null, EventArgs.Empty); });
            cm.Opening += delegate
            {
                if (g.SelectedRows.Count == 1)
                {
                    var room = g.SelectedRows[0].Tag as RoomItem;
                    if (room != null)
                    {
                        _miNotify.Text = room.Notify ? "关闭开播提醒(该主播)" : "开启开播提醒(该主播)";
                        _miNotify.Enabled = true;
                        return;
                    }
                }
                _miNotify.Enabled = false;
            };
            g.ContextMenuStrip = cm;

            return g;
        }

        static void AddCol(DataGridView g, string header, int width)
        {
            var c = new DataGridViewTextBoxColumn();
            c.HeaderText = header;
            c.Width = width;
            c.SortMode = DataGridViewColumnSortMode.NotSortable;
            c.ReadOnly = true;
            g.Columns.Add(c);
        }

        void GridCellFormatting(object sender, DataGridViewCellFormattingEventArgs e)
        {
            if (e.RowIndex < 0 || e.RowIndex >= _grid.Rows.Count) return;
            string status = _grid.Rows[e.RowIndex].Cells[COL_STATUS].Value as string;
            bool live = status != null && (status.Contains("直播中") || status.Contains("轮播"));
            bool warn = status != null && status.Contains("异常");
            bool bad = status != null && (status.Contains("失败") || status.Contains("不存在"));

            Color tint = Color.White;
            if (live && !warn) tint = Color.FromArgb(240, 253, 244);
            else if (warn) tint = Color.FromArgb(255, 248, 235);
            else if (bad) tint = Color.FromArgb(254, 243, 243);
            e.CellStyle.BackColor = tint;

            // selected row: clear blue background, but the status column keeps
            // its semantic color so live/offline stay distinguishable
            e.CellStyle.SelectionBackColor = Color.FromArgb(219, 234, 254);
            e.CellStyle.SelectionForeColor = Ui.Text;

            if (e.ColumnIndex == COL_STATUS)
            {
                Color c = Ui.Accent;
                if (warn) c = Ui.Orange;
                else if (live) c = Ui.Green;
                else if (status != null && status.Contains("未开播")) c = Ui.Gray;
                else if (bad) c = Ui.Red;
                e.CellStyle.ForeColor = c;
                e.CellStyle.SelectionForeColor = c;
                e.CellStyle.Font = Ui.F(9, FontStyle.Bold);
            }
            else if (e.ColumnIndex == COL_NAME)
            {
                e.CellStyle.Font = Ui.F(9, FontStyle.Bold);
            }
            else if (e.ColumnIndex == COL_AREA)
            {
                // 未开播时分区用灰色；开播（直播中/轮播）时和标题一样用正文黑色
                Color areaColor = live ? Ui.Text : Ui.Gray;
                e.CellStyle.ForeColor = areaColor;
                e.CellStyle.SelectionForeColor = areaColor;
            }
            else if (e.ColumnIndex == COL_NOTIFY)
            {
                string v = e.Value as string;
                e.CellStyle.ForeColor = v == "开" ? Ui.Green : Ui.Gray;
                e.CellStyle.SelectionForeColor = e.CellStyle.ForeColor;
                e.CellStyle.Font = Ui.F(9, FontStyle.Bold);
            }
        }

        void BuildTray()
        {
            _tray = new NotifyIcon();
            _tray.Icon = _appIcon;
            _tray.Text = "直播监测 - 后台监测中";
            _tray.Visible = true;

            var menu = new ContextMenuStrip();
            menu.Items.Add("显示主界面", null, delegate { ShowMain(); });
            _miPause = new ToolStripMenuItem("暂停监测");
            _miPause.CheckOnClick = true;
            _miPause.Click += delegate { _monitor.Paused = _miPause.Checked; UpdateStatus(); };
            menu.Items.Add(_miPause);
            menu.Items.Add("立即检测", null, delegate { _monitor.Wake(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate { ExitApp(); });
            _tray.ContextMenuStrip = menu;
            _trayMenu = menu;
            _tray.DoubleClick += delegate { ShowMain(); };
        }

        // ---------------- rooms / events ----------------

        void LoadRoomsToGrid()
        {
            _grid.Rows.Clear();
            _rowByKey.Clear();
            lock (_cfg)
            {
                foreach (var room in _cfg.Rooms) AddRoomRow(room);
            }
            _emptyLabel.Visible = _grid.Rows.Count == 0;
            UpdateStatus();
        }

        void AddRoomRow(RoomItem room)
        {
            if (_rowByKey.ContainsKey(room.Key)) return;
            string title = string.IsNullOrEmpty(room.LastTitle) ? "—" : room.LastTitle;
            string area = string.IsNullOrEmpty(room.Area) ? "—" : room.Area;
            string lastCheck = room.LastCheckAt == null ? "—" : ((DateTime)room.LastCheckAt).ToString("HH:mm:ss");
            string lastLive = RoomItem.FormatLastLive(room.LastLiveAt);
            int idx = _grid.Rows.Add(InitialStatus(room.LastStatus), room.DisplayName,
                PlatformApi.PlatformName(room.Platform), area, room.Id, title,
                room.Notify ? "开" : "关", lastCheck, lastLive);
            _grid.Rows[idx].Tag = room;
            _rowByKey[room.Key] = idx;
            _emptyLabel.Visible = _grid.Rows.Count == 0;
        }

        void OnMonitorEvent(MonitorEvent ev)
        {
            if (IsDisposed || _forceExit) return;
            try { BeginInvoke((Action)delegate { HandleEvent(ev); }); }
            catch { }
        }

        void HandleEvent(MonitorEvent ev)
        {
            if (IsDisposed || Disposing || _forceExit) return;
            if (ev == null || ev.Room == null || ev.Result == null) return;
            var room = ev.Room;
            var res = ev.Result;
            bool changed = false;

            // auto-fill streamer name
            if (string.IsNullOrEmpty(room.AutoName) && string.IsNullOrEmpty(room.Remark)
                && !string.IsNullOrEmpty(res.Owner))
            {
                room.AutoName = res.Owner;
                changed = true;
            }

            // record the state persistently
            string stKey = StatusKey(res);
            if (room.LastStatus != stKey)
            {
                room.LastStatus = stKey;
                changed = true;
            }

            // 分区/分类：每轮检测都更新（平台未返回时不覆盖已有值，避免网络异常把分区清空）
            if (res.State != LiveState.Error && !string.IsNullOrEmpty(res.Area) && room.Area != res.Area)
            {
                Log.Info("area " + room.Key + ": " + (room.Area == null ? "" : room.Area) + " → " + res.Area);
                room.Area = res.Area;
                changed = true;
            }

            if (ev.BecameLive)
            {
                // prefer the REAL live-start time reported by the platform; only a
                // plausible value is accepted (RoomMonitor already sanitized it),
                // otherwise fall back to the moment we detected the live state
                room.LastLiveAt = res.LiveStartAt ?? DateTime.Now;
                changed = true;
            }
            else if (res.State == LiveState.Live)
            {
                // while still live, correct the record with the platform time; a
                // >10 min jump means a new session (or a detection-time fallback
                // being refined), smaller differences are jitter and are ignored
                if (res.LiveStartAt != null)
                {
                    if (room.LastLiveAt == null
                        || Math.Abs((((DateTime)room.LastLiveAt) - ((DateTime)res.LiveStartAt)).TotalMinutes) > 10)
                    {
                        room.LastLiveAt = res.LiveStartAt;
                        changed = true;
                    }
                }
                else if (room.LastLiveAt == null)
                {
                    // platform doesn't report a start time at all (huya/douyin/...):
                    // use our detection time so the column is never wrong-looking
                    room.LastLiveAt = DateTime.Now;
                    changed = true;
                }
            }
            if (res.State == LiveState.Live && res.Sub != "网络异常"
                && !string.IsNullOrEmpty(res.Title) && room.LastTitle != res.Title)
            {
                room.LastTitle = res.Title;
                changed = true;
            }
            room.LastCheckAt = DateTime.Now;
            if (changed && !_cfg.Save()) Log.Error("config save failed (room " + room.Key + ")");

            UpdateRow(room, res);

            bool first = !_knownKeys.Contains(room.Key);
            _knownKeys.Add(room.Key);

            // a genuine offline result re-arms "不再提醒" for the next live session
            if (res.State == LiveState.Offline && res.Sub != "网络异常")
                _mutedKeys.Remove(room.Key);

            if (ev.BecameLive)
            {
                if (first && !_cfg.NotifyAlreadyLive) { UpdateStatus(); return; }
                if (room.Notify && !_mutedKeys.Contains(room.Key))
                    ShowNotifyPopup(room, res);
            }
            UpdateStatus();
        }

        void UpdateRow(RoomItem room, CheckResult res)
        {
            int idx;
            if (!_rowByKey.TryGetValue(room.Key, out idx)) return;
            if (idx < 0 || idx >= _grid.Rows.Count) return;
            var row = _grid.Rows[idx];
            row.Cells[COL_STATUS].Value = StatusText(res);
            row.Cells[COL_NAME].Value = room.DisplayName;
            row.Cells[COL_AREA].Value = string.IsNullOrEmpty(room.Area) ? "—" : room.Area;
            row.Cells[COL_TITLE].Value = string.IsNullOrEmpty(res.Title) ? "—" : res.Title;
            row.Cells[COL_NOTIFY].Value = room.Notify ? "开" : "关";
            row.Cells[COL_CHECK].Value = DateTime.Now.ToString("HH:mm:ss");
            row.Cells[COL_LIVE].Value = RoomItem.FormatLastLive(room.LastLiveAt);
            string tip = string.IsNullOrEmpty(res.Title) ? "" : res.Title;
            if (!string.IsNullOrEmpty(room.Area)) tip = "分区: " + room.Area + (tip.Length > 0 ? "\r\n" + tip : "");
            if (!string.IsNullOrEmpty(res.Error)) tip = (tip.Length > 0 ? tip + "\r\n" : "") + res.Error;
            row.Cells[COL_TITLE].ToolTipText = tip;
            row.Cells[COL_AREA].ToolTipText = string.IsNullOrEmpty(room.Area) ? "" : room.Area;
            row.Tag = room;
        }

        static string StatusText(CheckResult res)
        {
            if (res.Sub == "网络异常")
                return res.State == LiveState.Live ? "● 直播中(异常)" : "● 未开播(异常)";
            switch (res.State)
            {
                case LiveState.Live:
                    return string.IsNullOrEmpty(res.Sub) ? "● 直播中" : "● " + res.Sub;
                case LiveState.Offline: return "● 未开播";
                case LiveState.NotFound: return "● 不存在";
                case LiveState.Error: return "● 检测失败";
                default: return "● 未知";
            }
        }

        static string InitialStatus(string lastStatus)
        {
            switch (lastStatus)
            {
                case "live": return "● 直播中";
                case "offline": return "● 未开播";
                case "notfound": return "● 不存在";
                case "error": return "● 检测失败";
                default: return "● 检测中…";
            }
        }

        static string StatusKey(CheckResult res)
        {
            switch (res.State)
            {
                case LiveState.Live: return "live";
                case LiveState.Offline: return "offline";
                case LiveState.NotFound: return "notfound";
                case LiveState.Error: return "error";
                default: return "unknown";
            }
        }

        void UpdateStatus()
        {
            if (IsDisposed || Disposing) return;
            if (_statusLabel == null || _monitor == null || _grid == null) return;
            string pfx;
            if (_monitor.Paused) pfx = "已暂停监测";
            else if (_checking) pfx = "正在检测…";
            else pfx = "监测运行中";

            string last = _monitor.LastCheckEnd == null
                ? "尚未完成检测"
                : "上次检测 " + ((DateTime)_monitor.LastCheckEnd).ToString("HH:mm:ss");

            int live = _monitor.LiveCount;
            string statusText = string.Format(
                "{0} · 每 {1} 秒检测一次 · 共 {2} 个房间 · {3} 个正在直播 · {4}",
                pfx, _cfg.IntervalSec, _grid.Rows.Count, live, last);
            if (_statusLabel.Text != statusText) _statusLabel.Text = statusText;

            // NotifyIcon.Text is limited to 63 chars; only touch the shell when the
            // text really changes (the .NET setter would skip it anyway, but being
            // explicit keeps the shell interaction minimal and auditable).
            string trayText = "直播监测" + (live > 0 ? " · " + live + " 个直播中" : "");
            if (trayText.Length > 63) trayText = trayText.Substring(0, 63);
            try
            {
                if (_tray != null && _tray.Text != trayText) _tray.Text = trayText;
            }
            catch (Exception ex) { Log.Exception(ex); }

            // persist the one-time auto-start cleanup marker from the UI thread
            if (_autostartCleanupDone && !_autostartCleanupSaved)
            {
                _autostartCleanupSaved = true;
                _cfg.LegacyAutostartCleaned = true;
                if (!_cfg.Save()) _autostartCleanupSaved = false;
            }
        }

        // ---------------- actions ----------------

        void AddRoom(object sender, EventArgs e)
        {
            using (var f = new AddRoomForm(_cfg))
            {
                if (f.ShowDialog(this) == DialogResult.OK && f.ResultRoom != null)
                {
                    lock (_cfg) { _cfg.Rooms.Add(f.ResultRoom); }
                    if (!_cfg.Save())
                        MessageBox.Show(this, "写入配置文件失败,新增的直播间可能在重启后丢失。", "提示",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    AddRoomRow(f.ResultRoom);
                    _knownKeys.Remove(f.ResultRoom.Key);
                    _monitor.Wake();
                }
            }
        }

        void DeleteSelected(object sender, EventArgs e)
        {
            if (_grid.SelectedRows.Count == 0) return;
            int n = _grid.SelectedRows.Count;
            var r = MessageBox.Show(this,
                string.Format("确定删除选中的 {0} 个直播间吗?", n), "删除确认",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r != DialogResult.Yes) return;

            var toDelete = new List<RoomItem>();
            foreach (DataGridViewRow row in _grid.SelectedRows)
            {
                var room = row.Tag as RoomItem;
                if (room != null) toDelete.Add(room);
            }
            lock (_cfg)
            {
                foreach (var room in toDelete)
                {
                    _cfg.Rooms.Remove(room);
                    _monitor.RemoveState(room);
                    _knownKeys.Remove(room.Key);
                    _mutedKeys.Remove(room.Key);
                }
            }
            if (!_cfg.Save())
                MessageBox.Show(this, "写入配置文件失败,删除操作可能在重启后失效。", "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            LoadRoomsToGrid();
        }

        void ToggleNotify(object sender, EventArgs e)
        {
            if (_grid.SelectedRows.Count != 1) return;
            var room = _grid.SelectedRows[0].Tag as RoomItem;
            if (room == null) return;
            room.Notify = !room.Notify;
            if (!_cfg.Save()) Log.Error("config save failed (toggle notify)");
            int idx;
            if (_rowByKey.TryGetValue(room.Key, out idx) && idx < _grid.Rows.Count)
                _grid.Rows[idx].Cells[COL_NOTIFY].Value = room.Notify ? "开" : "关";
            _grid.Invalidate();
        }

        void OpenSelected(object sender, EventArgs e)
        {
            foreach (DataGridViewRow row in _grid.SelectedRows)
            {
                var room = row.Tag as RoomItem;
                if (room != null) OpenRoom(room);
            }
        }

        void CheckSelectedNow()
        {
            foreach (DataGridViewRow row in _grid.SelectedRows)
            {
                var room = row.Tag as RoomItem;
                if (room != null) _monitor.CheckRoomAsync(room);
            }
        }

        void CopySelectedUrl()
        {
            if (_grid.SelectedRows.Count == 0) return;
            var room = _grid.SelectedRows[0].Tag as RoomItem;
            if (room != null)
            {
                try { Clipboard.SetText(room.Url); } catch { }
            }
        }

        void OpenRoom(RoomItem room)
        {
            if (room == null) return;
            if (!PlatformApi.IsHttpUrl(room.Url))
            {
                Log.Info("refused to open non-http url: " + (room.Url == null ? "" : room.Url));
                MessageBox.Show(this, "该直播间的地址无效,请删除后重新添加。", "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            try
            {
                Process.Start(room.Url);
            }
            catch (Exception ex)
            {
                Log.Exception(ex);
                MessageBox.Show(this, "打开直播间失败: " + ex.Message, "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        void ShowNotifyPopup(RoomItem room, CheckResult res)
        {
            _popups.RemoveAll(p => p == null || p.IsDisposed);
            var popup = new NotifyForm(_cfg, room, res, _appIcon, delegate
            {
                // "不再提醒": mute this room until it goes offline (popup AND its loop sound stop)
                _mutedKeys.Add(room.Key);
                for (int i = _popups.Count - 1; i >= 0; i--)
                {
                    var p = _popups[i];
                    if (p != null && !p.IsDisposed && p.RoomKey == room.Key)
                    {
                        try { p.Close(); } catch { }
                    }
                }
            });
            popup.PlaceBottomRight(_popups.Count);
            popup.FormClosed += delegate
            {
                _popups.Remove(popup);
                RelayoutPopups();
            };
            _popups.Add(popup);
            popup.Show();
            if (_popups.Count > 4)
            {
                var oldest = _popups[0];
                _popups.RemoveAt(0);
                try { oldest.Close(); } catch { }
            }
        }

        /// <summary>Re-stacks the remaining popups after one closes, so they do not
        /// leave holes on screen.</summary>
        void RelayoutPopups()
        {
            for (int i = 0; i < _popups.Count; i++)
            {
                var p = _popups[i];
                if (p == null || p.IsDisposed) continue;
                try { p.PlaceBottomRight(i); } catch { }
            }
        }

        // ---------------- window / tray behavior ----------------

        void ShowMain()
        {
            Show();
            WindowState = FormWindowState.Normal;
            ShowInTaskbar = true;
            Activate();
        }

        void ExitApp()
        {
            _forceExit = true;
            ClosePopups();
            Close();
        }

        void ClosePopups()
        {
            for (int i = _popups.Count - 1; i >= 0; i--)
            {
                try
                {
                    var p = _popups[i];
                    if (p != null && !p.IsDisposed) p.Close();
                }
                catch { }
            }
            _popups.Clear();
        }

        /// <summary>Logs handle/GDI/memory usage once an hour so a long-run leak is
        /// visible in app.log without needing Process Explorer.</summary>
        void LogHealth()
        {
            try
            {
                var p = Process.GetCurrentProcess();
                int gdi = -1, usr = -1;
                IntPtr h = OpenProcess(0x1000, false, p.Id);
                if (h != IntPtr.Zero)
                {
                    gdi = (int)GetGuiResources(h, 0);
                    usr = (int)GetGuiResources(h, 1);
                    CloseHandle(h);
                }
                double minutes = 0;
                try { minutes = (DateTime.Now - p.StartTime).TotalMinutes; } catch { }
                Log.Info(string.Format(
                    "health: gdi={0} user={1} handles={2} ws={3}MB threads={4} uptime={5:F0}min rooms={6} live={7}",
                    gdi, usr, p.HandleCount, p.WorkingSet64 / (1024 * 1024), p.Threads.Count,
                    minutes, _cfg.Rooms.Count, _monitor.LiveCount));
            }
            catch (Exception ex) { Log.Exception(ex); }
        }

        /// <summary>Explorer broadcasts "TaskbarCreated" after it (re)starts. Logging
        /// it makes later "explorer.exe crashed again" reports much easier to correlate.</summary>
        protected override void WndProc(ref Message m)
        {
            try
            {
                if (WM_TASKBARCREATED != 0 && m.Msg == (int)WM_TASKBARCREATED)
                    Log.Info("shell restarted: TaskbarCreated received (tray icon re-registered)");
            }
            catch { }
            base.WndProc(ref m);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (_inResize) return;
            _inResize = true;
            try
            {
                if (!_forceExit && _cfg != null && _cfg.CloseToTray && WindowState == FormWindowState.Minimized)
                {
                    Hide();
                    ShowInTaskbar = false;
                }
            }
            finally { _inResize = false; }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            try { _exitReason = e.CloseReason.ToString(); } catch { }
            if (!_forceExit && _cfg.CloseToTray && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                ShowInTaskbar = false;
                if (!_firstMinimizeHint)
                {
                    _firstMinimizeHint = true;
                    try
                    {
                        _tray.ShowBalloonTip(2500, "直播监测", "已最小化到托盘,继续在后台监测", ToolTipIcon.Info);
                    }
                    catch { }
                }
                return;
            }
            base.OnFormClosing(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            _forceExit = true;
            try { if (_uiTimer != null) { _uiTimer.Stop(); _uiTimer.Dispose(); } } catch { }
            try { if (_bootTimer != null) { _bootTimer.Stop(); _bootTimer.Dispose(); } } catch { }
            try { if (_healthTimer != null) { _healthTimer.Stop(); _healthTimer.Dispose(); } } catch { }
            try { if (_monitor != null) { _monitor.Event -= OnMonitorEvent; _monitor.Stop(); } } catch { }
            ClosePopups();
            try { _cfg.Save(); } catch { }
            try { Log.Info("exit reason=" + (_exitReason.Length == 0 ? "unknown" : _exitReason)); } catch { }
            if (_tray != null)
            {
                try { _tray.Visible = false; } catch { }
                try { _tray.Dispose(); } catch { }
                _tray = null;
            }
            if (_trayMenu != null)
            {
                try { _trayMenu.Dispose(); } catch { }
                _trayMenu = null;
            }
            base.OnFormClosed(e);
        }

        static Icon LoadAppIcon()
        {
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.ico"))
                {
                    if (s != null) return new Icon(s);
                }
            }
            catch { }
            return SystemIcons.Application;
        }
    }
}
