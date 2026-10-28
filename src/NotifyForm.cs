using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace LiveMonitor
{
    /// <summary>
    /// Small top-most popup shown when a streamer goes live.
    /// It never steals focus (WS_EX_NOACTIVATE), so it won't disturb work or games.
    /// Stays open until the user acts; the chime may repeat gently while open.
    /// </summary>
    public class NotifyForm : Form
    {
        readonly AppConfig _cfg;
        readonly RoomItem _room;
        readonly CheckResult _res;
        readonly Icon _icon;
        readonly Action _onMute;
        FlatButton _btnOpen, _btnClose, _btnMute;
        Timer _fadeTimer, _autoCloseTimer, _loopSoundTimer, _autoOpenTimer;
        Label _autoHint;
        bool _userActed;
        int _autoOpenLeft;

        public string RoomKey { get { return _room.Key; } }

        public NotifyForm(AppConfig cfg, RoomItem room, CheckResult res, Icon icon, Action onMute)
        {
            _cfg = cfg;
            _room = room;
            _res = res;
            _icon = icon;
            _onMute = onMute;

            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Size = new Size(402, 180);
            BackColor = Ui.Bg;
            Font = Ui.F(9, FontStyle.Regular);
            DoubleBuffered = true;

            BuildUi();

            _fadeTimer = new Timer();
            _fadeTimer.Interval = 15;
            _fadeTimer.Tick += delegate
            {
                Opacity += 0.12;
                if (Opacity >= 1.0) { Opacity = 1.0; _fadeTimer.Stop(); }
            };
            _fadeTimer.Start();

            // stays open until the user acts (default 0); auto-close only if configured
            if (_cfg.PopupAutoCloseSec > 0)
            {
                _autoCloseTimer = new Timer();
                _autoCloseTimer.Interval = _cfg.PopupAutoCloseSec * 1000;
                _autoCloseTimer.Tick += delegate { Close(); };
                _autoCloseTimer.Start();
            }

            // repeat the chime gently while THIS popup stays open; closing the
            // window (X / 忽略 / 不再提醒 / 打开直播间 / auto-close) stops it
            if (_cfg.LoopSoundSec > 0)
            {
                _loopSoundTimer = new Timer();
                _loopSoundTimer.Interval = _cfg.LoopSoundSec * 1000;
                _loopSoundTimer.Tick += delegate { Sound.PlayChime(_cfg.SoundOn, _cfg.SoundFile); };
                _loopSoundTimer.Start();
            }

            // optional: notify first, then auto-open the room page and close
            if (_cfg.AutoOpenRoom)
            {
                _autoOpenLeft = 3;
                _autoHint = new Label();
                _autoHint.Text = "3 秒后自动打开直播间…";
                _autoHint.Font = Ui.F(8, FontStyle.Regular);
                _autoHint.ForeColor = Ui.SubText;
                _autoHint.Bounds = new Rectangle(20, 138, 80, 24);
                _autoHint.TextAlign = ContentAlignment.MiddleLeft;
                Controls.Add(_autoHint);
                _autoOpenTimer = new Timer();
                _autoOpenTimer.Interval = 1000;
                _autoOpenTimer.Tick += AutoOpenTick;
                _autoOpenTimer.Start();
            }

            Sound.PlayChime(_cfg.SoundOn, _cfg.SoundFile);
        }

        void AutoOpenTick(object sender, EventArgs e)
        {
            if (_userActed)
            {
                _autoOpenTimer.Stop();
                return;
            }
            _autoOpenLeft--;
            if (_autoHint != null)
            {
                _autoHint.Text = _autoOpenLeft > 0
                    ? _autoOpenLeft + " 秒后自动打开直播间…"
                    : "正在打开直播间…";
            }
            if (_autoOpenLeft <= 0)
            {
                _autoOpenTimer.Stop();
                OpenRoom(null, EventArgs.Empty);
            }
        }

        protected override bool ShowWithoutActivation
        {
            get { return true; }
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= 0x08000000 | 0x00000008; // WS_EX_NOACTIVATE | WS_EX_TOPMOST
                return cp;
            }
        }

        void BuildUi()
        {
            if (_icon != null)
            {
                var pic = new PictureBox();
                pic.Bounds = new Rectangle(18, 18, 40, 40);
                pic.SizeMode = PictureBoxSizeMode.Zoom;
                pic.Image = Ui.AppImage;
                Controls.Add(pic);
            }

            var lblTitle = new Label();
            lblTitle.Text = "主播开播啦!";
            lblTitle.Font = Ui.F(13, FontStyle.Bold);
            lblTitle.ForeColor = Ui.Text;
            lblTitle.AutoSize = true;
            lblTitle.Location = new Point(68, 16);
            Controls.Add(lblTitle);

            string who = _room.DisplayName;
            if (!string.IsNullOrEmpty(_res.Owner) && _res.Owner != who)
                who = who + "(" + _res.Owner + ")";
            var lblWho = new Label();
            lblWho.Text = who + " · " + PlatformApi.PlatformName(_room.Platform);
            lblWho.Font = Ui.F(10, FontStyle.Bold);
            lblWho.ForeColor = Ui.Green;
            lblWho.AutoSize = true;
            lblWho.Location = new Point(68, 42);
            Controls.Add(lblWho);

            var lblInfo = new Label();
            string info = string.IsNullOrEmpty(_res.Title) ? "正在直播" : _res.Title;
            if (!string.IsNullOrEmpty(_res.Sub) && _res.Sub != "网络异常")
                info = "[" + _res.Sub + "] " + info;
            lblInfo.Text = info;
            lblInfo.Font = Ui.F(9, FontStyle.Regular);
            lblInfo.ForeColor = Ui.SubText;
            lblInfo.AutoEllipsis = true;
            lblInfo.Location = new Point(20, 74);
            lblInfo.Size = new Size(362, 44);
            lblInfo.TextAlign = ContentAlignment.TopLeft;
            Controls.Add(lblInfo);

            _btnClose = new FlatButton();
            _btnClose.Text = "忽略";
            _btnClose.Back = Ui.PanelBg;
            _btnClose.Hover = Ui.Border;
            _btnClose.Fore = Ui.Text;
            _btnClose.Bounds = new Rectangle(100, 132, 72, 30);
            _btnClose.Click += delegate { _userActed = true; Close(); };
            Controls.Add(_btnClose);

            _btnMute = new FlatButton();
            _btnMute.Text = "不再提醒";
            _btnMute.Back = Ui.PanelBg;
            _btnMute.Hover = Ui.Border;
            _btnMute.Fore = Ui.Text;
            _btnMute.Bounds = new Rectangle(184, 132, 92, 30);
            _btnMute.Click += delegate
            {
                _userActed = true;
                try { if (_onMute != null) _onMute(); } catch { }
                Close();
            };
            Controls.Add(_btnMute);

            _btnOpen = new FlatButton();
            _btnOpen.Text = "打开直播间";
            _btnOpen.Bounds = new Rectangle(288, 132, 96, 30);
            _btnOpen.Click += OpenRoom;
            Controls.Add(_btnOpen);
        }

        void OpenRoom(object sender, EventArgs e)
        {
            _userActed = true;
            try
            {
                Process.Start(_room.Url);
            }
            catch (Exception ex)
            {
                Log.Exception(ex);
            }
            Close();
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            try { if (_autoCloseTimer != null) _autoCloseTimer.Stop(); } catch { }
            try { if (_loopSoundTimer != null) _loopSoundTimer.Stop(); } catch { }
            try { if (_autoOpenTimer != null) _autoOpenTimer.Stop(); } catch { }
            base.OnFormClosed(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var path = Ui.RoundRect(new RectangleF(1, 1, Width - 3, Height - 3), 12))
            using (var pen = new Pen(Ui.Green, 2))
                g.DrawPath(pen, path);
            base.OnPaint(e);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            using (var path = Ui.RoundRect(new Rectangle(0, 0, Width, Height), 12))
                Region = new Region(path);
        }

        public void PlaceBottomRight(int index)
        {
            var wa = Screen.PrimaryScreen.WorkingArea;
            int x = wa.Right - Width - 16 - index * 16;
            int y = wa.Bottom - Height - 16 - index * 24;
            if (y < wa.Top) y = wa.Top + 8;
            Location = new Point(x, y);
        }
    }
}
