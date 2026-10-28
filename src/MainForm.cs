using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;

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
        Timer _uiTimer;
        readonly List<NotifyForm> _popups = new List<NotifyForm>();
        readonly HashSet<string> _knownKeys = new HashSet<string>();
        readonly HashSet<string> _mutedKeys = new HashSet<string>();
        readonly Dictionary<string, int> _rowByKey = new Dictionary<string, int>();
        bool _forceExit;
        bool _firstMinimizeHint;
        bool _checking;
        Icon _appIcon;

        public MainForm(bool minimized)
        {
            _cfg = AppConfig.Load();
            Log.Info("start: rooms=" + _cfg.Rooms.Count + " interval=" + _cfg.IntervalSec);

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

            // remove any auto-start entries left by earlier versions
            SettingsForm.CleanupAutostart();

            // start the worker thread shortly after the UI is up
            var bootTimer = new Timer();
            bootTimer.Interval = 3000;
            bootTimer.Tick += delegate
            {
                bootTimer.Stop();
                bootTimer.Dispose();
                try { _monitor.Start(); }
                catch (Exception ex) { Log.Exception(ex); }
            };
            bootTimer.Start();

            _uiTimer = new Timer();
            _uiTimer.Interval = 500;
            _uiTimer.Tick += delegate { UpdateStatus(); };
            _uiTimer.Start();

            LoadRoomsToGrid();

            if (minimized)
            {
                WindowState = FormWindowState.Minimized;
            }
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

            AddCol(g, "状态", 128);
            AddCol(g, "主播", 150);
            AddCol(g, "平台", 70);
            AddCol(g, "房间号", 100);
            AddCol(g, "直播标题", 200);
            AddCol(g, "提醒", 56);
            AddCol(g, "最后检测", 100);
            AddCol(g, "上次开播", 120);

            g.Columns[4].AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
            g.Columns[4].MinimumWidth = 140;

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
            string status = _grid.Rows[e.RowIndex].Cells[0].Value as string;
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

            if (e.ColumnIndex == 0)
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
            else if (e.ColumnIndex == 1)
            {
                e.CellStyle.Font = Ui.F(9, FontStyle.Bold);
            }
            else if (e.ColumnIndex == 5)
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
            string lastCheck = room.LastCheckAt == null ? "—" : ((DateTime)room.LastCheckAt).ToString("HH:mm:ss");
            string lastLive = room.LastLiveAt == null ? "—" : ((DateTime)room.LastLiveAt).ToString("MM-dd HH:mm");
            int idx = _grid.Rows.Add(InitialStatus(room.LastStatus), room.DisplayName,
                PlatformApi.PlatformName(room.Platform), room.Id, title,
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
            if (ev.BecameLive)
            {
                // prefer the REAL live-start time reported by the platform
                room.LastLiveAt = res.LiveStartAt ?? DateTime.Now;
                changed = true;
            }
            else if (res.State == LiveState.Live && res.LiveStartAt != null)
            {
                // while still live, correct the record with the real start time
                if (room.LastLiveAt == null
                    || Math.Abs((((DateTime)room.LastLiveAt) - ((DateTime)res.LiveStartAt)).TotalMinutes) > 1)
                {
                    room.LastLiveAt = res.LiveStartAt;
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
            if (changed) _cfg.Save();

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
            row.Cells[0].Value = StatusText(res);
            row.Cells[1].Value = room.DisplayName;
            row.Cells[4].Value = string.IsNullOrEmpty(res.Title) ? "—" : res.Title;
            row.Cells[5].Value = room.Notify ? "开" : "关";
            row.Cells[6].Value = DateTime.Now.ToString("HH:mm:ss");
            row.Cells[7].Value = room.LastLiveAt == null ? "—" : ((DateTime)room.LastLiveAt).ToString("MM-dd HH:mm");
            string tip = string.IsNullOrEmpty(res.Title) ? "" : res.Title;
            if (!string.IsNullOrEmpty(res.Error)) tip = (tip.Length > 0 ? tip + "\r\n" : "") + res.Error;
            row.Cells[4].ToolTipText = tip;
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
            if (_statusLabel == null || _monitor == null) return;
            string pfx;
            if (_monitor.Paused) pfx = "已暂停监测";
            else if (_checking) pfx = "正在检测…";
            else pfx = "监测运行中";

            string last = _monitor.LastCheckEnd == null
                ? "尚未完成检测"
                : "上次检测 " + ((DateTime)_monitor.LastCheckEnd).ToString("HH:mm:ss");

            _statusLabel.Text = string.Format(
                "{0} · 每 {1} 秒检测一次 · 共 {2} 个房间 · {3} 个正在直播 · {4}",
                pfx, _cfg.IntervalSec, _grid.Rows.Count, _monitor.LiveCount, last);
            _tray.Text = "直播监测" + (_monitor.LiveCount > 0 ? " · " + _monitor.LiveCount + " 个直播中" : "");
        }

        // ---------------- actions ----------------

        void AddRoom(object sender, EventArgs e)
        {
            using (var f = new AddRoomForm(_cfg))
            {
                if (f.ShowDialog(this) == DialogResult.OK && f.ResultRoom != null)
                {
                    lock (_cfg) { _cfg.Rooms.Add(f.ResultRoom); }
                    _cfg.Save();
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
            _cfg.Save();
            LoadRoomsToGrid();
        }

        void ToggleNotify(object sender, EventArgs e)
        {
            if (_grid.SelectedRows.Count != 1) return;
            var room = _grid.SelectedRows[0].Tag as RoomItem;
            if (room == null) return;
            room.Notify = !room.Notify;
            _cfg.Save();
            int idx;
            if (_rowByKey.TryGetValue(room.Key, out idx) && idx < _grid.Rows.Count)
                _grid.Rows[idx].Cells[5].Value = room.Notify ? "开" : "关";
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
            popup.FormClosed += delegate { _popups.Remove(popup); };
            _popups.Add(popup);
            popup.Show();
            if (_popups.Count > 4)
            {
                var oldest = _popups[0];
                _popups.RemoveAt(0);
                try { oldest.Close(); } catch { }
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
            Close();
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (_cfg != null && _cfg.CloseToTray && WindowState == FormWindowState.Minimized)
            {
                Hide();
                ShowInTaskbar = false;
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
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
            try { _monitor.Stop(); } catch { }
            try { _cfg.Save(); } catch { }
            try { Log.Info("exit"); } catch { }
            if (_tray != null)
            {
                _tray.Visible = false;
                _tray.Dispose();
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
