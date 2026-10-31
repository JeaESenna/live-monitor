using System;
using System.Drawing;
using System.Windows.Forms;
using Microsoft.Win32;

namespace LiveMonitor
{
    public class SettingsForm : Form
    {
        readonly AppConfig _cfg;
        NumericUpDown _interval, _autoClose, _loopSec;
        CheckBox _chkSound, _chkNotifyLive, _chkAutoOpen;
        RadioButton _rbTray, _rbExit;
        Label _soundFileLabel;
        string _soundPath;
        public bool Saved;

        public SettingsForm(AppConfig cfg)
        {
            _cfg = cfg;
            _soundPath = cfg.SoundFile;
            Text = "设置";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            ClientSize = new Size(470, 494);
            BackColor = Ui.Bg;
            Font = Ui.F(9, FontStyle.Regular);
            BuildUi();
        }

        void SectionLabel(string text, int y)
        {
            var lbl = new Label();
            lbl.Text = text;
            lbl.Font = Ui.F(10, FontStyle.Bold);
            lbl.ForeColor = Ui.Text;
            lbl.AutoSize = true;
            lbl.Location = new Point(26, y);
            Controls.Add(lbl);
        }

        void HintLabel(string text, int y)
        {
            var lbl = new Label();
            lbl.Text = text;
            lbl.ForeColor = Ui.SubText;
            lbl.Font = Ui.F(8, FontStyle.Regular);
            lbl.AutoSize = true;
            lbl.Location = new Point(38, y);
            Controls.Add(lbl);
        }

        void BuildUi()
        {
            // ---- 检测 ----
            SectionLabel("检测", 20);
            var lblInterval = new Label();
            lblInterval.Text = "检测间隔";
            lblInterval.AutoSize = true;
            lblInterval.Location = new Point(38, 48);
            Controls.Add(lblInterval);

            _interval = new NumericUpDown();
            _interval.Minimum = 15;
            _interval.Maximum = 3600;
            _interval.Value = _cfg.IntervalSec;
            _interval.Bounds = new Rectangle(110, 44, 80, 26);
            Controls.Add(_interval);

            var lblSec = new Label();
            lblSec.Text = "秒";
            lblSec.AutoSize = true;
            lblSec.Location = new Point(198, 49);
            Controls.Add(lblSec);

            HintLabel("默认30秒,最短15秒;间隔太短可能触发平台风控", 72);

            // ---- 开播提醒 ----
            SectionLabel("开播提醒", 100);
            _chkSound = new CheckBox();
            _chkSound.Text = "开播时播放提示音";
            _chkSound.AutoSize = true;
            _chkSound.Location = new Point(38, 126);
            _chkSound.Checked = _cfg.SoundOn;
            _chkSound.CheckedChanged += delegate { _loopSec.Enabled = _chkSound.Checked; };
            Controls.Add(_chkSound);

            var btnTest = new FlatButton();
            btnTest.Text = "试听提示音";
            btnTest.Back = Ui.PanelBg;
            btnTest.Hover = Ui.Border;
            btnTest.Fore = Ui.Text;
            btnTest.Bounds = new Rectangle(330, 120, 110, 30);
            btnTest.Click += delegate { Sound.PlayChime(true, _soundPath); };
            Controls.Add(btnTest);

            var lblLoop = new Label();
            lblLoop.Text = "循环提示间隔";
            lblLoop.AutoSize = true;
            lblLoop.Location = new Point(38, 158);
            Controls.Add(lblLoop);

            _loopSec = new NumericUpDown();
            _loopSec.Minimum = 0;
            _loopSec.Maximum = 3600;
            _loopSec.Value = _cfg.LoopSoundSec;
            _loopSec.Bounds = new Rectangle(150, 154, 80, 26);
            _loopSec.Enabled = _cfg.SoundOn;
            Controls.Add(_loopSec);

            var lblLoopSec = new Label();
            lblLoopSec.Text = "秒";
            lblLoopSec.AutoSize = true;
            lblLoopSec.Location = new Point(238, 159);
            Controls.Add(lblLoopSec);

            HintLabel("0=不循环;弹窗显示期间每隔N秒重复提醒一次,关闭弹窗即停止", 182);

            var lblAutoClose = new Label();
            lblAutoClose.Text = "弹窗自动关闭";
            lblAutoClose.AutoSize = true;
            lblAutoClose.Location = new Point(38, 208);
            Controls.Add(lblAutoClose);

            _autoClose = new NumericUpDown();
            _autoClose.Minimum = 0;
            _autoClose.Maximum = 3600;
            _autoClose.Value = _cfg.PopupAutoCloseSec;
            _autoClose.Bounds = new Rectangle(150, 204, 80, 26);
            Controls.Add(_autoClose);

            var lblSec2 = new Label();
            lblSec2.Text = "秒";
            lblSec2.AutoSize = true;
            lblSec2.Location = new Point(238, 209);
            Controls.Add(lblSec2);

            HintLabel("0=常驻显示直到手动操作(推荐)", 232);

            _chkNotifyLive = new CheckBox();
            _chkNotifyLive.Text = "启动时对已经在直播的房间也弹窗提醒";
            _chkNotifyLive.AutoSize = true;
            _chkNotifyLive.Location = new Point(38, 254);
            _chkNotifyLive.Checked = _cfg.NotifyAlreadyLive;
            Controls.Add(_chkNotifyLive);

            _chkAutoOpen = new CheckBox();
            _chkAutoOpen.Text = "开播时自动打开直播间网页(提示3秒后自动打开并关闭弹窗)";
            _chkAutoOpen.AutoSize = true;
            _chkAutoOpen.Location = new Point(38, 280);
            _chkAutoOpen.Checked = _cfg.AutoOpenRoom;
            Controls.Add(_chkAutoOpen);

            // ---- 自定义提示音 ----
            var lblSoundFile = new Label();
            lblSoundFile.Text = "提示音文件";
            lblSoundFile.AutoSize = true;
            lblSoundFile.Location = new Point(38, 312);
            Controls.Add(lblSoundFile);

            var btnPick = new FlatButton();
            btnPick.Text = "选择音频文件…";
            btnPick.Back = Ui.PanelBg;
            btnPick.Hover = Ui.Border;
            btnPick.Fore = Ui.Text;
            btnPick.Bounds = new Rectangle(120, 306, 118, 28);
            btnPick.Click += PickSoundFile;
            Controls.Add(btnPick);

            var btnReset = new FlatButton();
            btnReset.Text = "恢复默认";
            btnReset.Back = Ui.PanelBg;
            btnReset.Hover = Ui.Border;
            btnReset.Fore = Ui.Text;
            btnReset.Bounds = new Rectangle(244, 306, 88, 28);
            btnReset.Click += delegate { _soundPath = ""; UpdateSoundLabel(); };
            Controls.Add(btnReset);

            _soundFileLabel = new Label();
            _soundFileLabel.Bounds = new Rectangle(338, 302, 110, 36);
            _soundFileLabel.ForeColor = Ui.SubText;
            _soundFileLabel.Font = Ui.F(8, FontStyle.Regular);
            _soundFileLabel.TextAlign = ContentAlignment.MiddleLeft;
            _soundFileLabel.AutoEllipsis = true;
            Controls.Add(_soundFileLabel);

            HintLabel("支持 wav / mp3;留空使用内置提示音(试听按钮预览当前选择)", 338);

            // ---- 其他 ----
            SectionLabel("其他", 366);
            var lblClose = new Label();
            lblClose.Text = "关闭主窗口时";
            lblClose.AutoSize = true;
            lblClose.Location = new Point(38, 392);
            Controls.Add(lblClose);

            _rbTray = new RadioButton();
            _rbTray.Text = "最小化到托盘,继续后台监测(推荐)";
            _rbTray.AutoSize = true;
            _rbTray.Location = new Point(150, 388);
            _rbTray.Checked = _cfg.CloseToTray;
            Controls.Add(_rbTray);

            _rbExit = new RadioButton();
            _rbExit.Text = "直接退出程序";
            _rbExit.AutoSize = true;
            _rbExit.Location = new Point(150, 414);
            _rbExit.Checked = !_cfg.CloseToTray;
            Controls.Add(_rbExit);

            var btnCancel = new FlatButton();
            btnCancel.Text = "取消";
            btnCancel.Back = Ui.PanelBg;
            btnCancel.Hover = Ui.Border;
            btnCancel.Fore = Ui.Text;
            btnCancel.Bounds = new Rectangle(252, 446, 92, 34);
            btnCancel.Click += delegate { DialogResult = DialogResult.Cancel; Close(); };
            Controls.Add(btnCancel);

            var btnSave = new FlatButton();
            btnSave.Text = "保存";
            btnSave.Bounds = new Rectangle(360, 446, 92, 34);
            btnSave.Click += SaveClick;
            Controls.Add(btnSave);

            UpdateSoundLabel();
        }

        void PickSoundFile(object sender, EventArgs e)
        {
            using (var dlg = new OpenFileDialog())
            {
                dlg.Title = "选择提示音文件";
                dlg.Filter = "音频文件 (*.wav;*.mp3)|*.wav;*.mp3|所有文件 (*.*)|*.*";
                if (dlg.ShowDialog(this) == DialogResult.OK)
                {
                    _soundPath = dlg.FileName;
                    UpdateSoundLabel();
                }
            }
        }

        void UpdateSoundLabel()
        {
            _soundFileLabel.Text = string.IsNullOrEmpty(_soundPath)
                ? "当前:内置提示音"
                : "当前:" + System.IO.Path.GetFileName(_soundPath);
            if (_soundPath.Length > 0)
            {
                _soundFileLabel.ForeColor = Ui.Text;
                _soundFileLabel.Font = Ui.F(8, FontStyle.Bold);
            }
            else
            {
                _soundFileLabel.ForeColor = Ui.SubText;
                _soundFileLabel.Font = Ui.F(8, FontStyle.Regular);
            }
        }

        void SaveClick(object sender, EventArgs e)
        {
            try
            {
                _cfg.IntervalSec = Clamp((int)_interval.Value, 15, 3600, 30);
                _cfg.SoundOn = _chkSound.Checked;
                _cfg.LoopSoundSec = Clamp((int)_loopSec.Value, 0, 3600, 60);
                _cfg.PopupAutoCloseSec = Clamp((int)_autoClose.Value, 0, 3600, 0);
                _cfg.NotifyAlreadyLive = _chkNotifyLive.Checked;
                _cfg.CloseToTray = _rbTray.Checked;
                _cfg.SoundFile = _soundPath == null ? "" : _soundPath;
                _cfg.AutoOpenRoom = _chkAutoOpen.Checked;
            }
            catch (Exception ex)
            {
                Log.Exception(ex);
                MessageBox.Show(this, "保存设置失败: " + ex.Message, "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            if (!_cfg.Save())
            {
                MessageBox.Show(this, "设置写入配置文件失败,重启后可能丢失。\r\n请检查程序目录是否可写或磁盘是否已满。",
                    "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            Saved = true;
            DialogResult = DialogResult.OK;
            Close();
        }

        static int Clamp(int v, int min, int max, int fallback)
        {
            if (v < min || v > max) return fallback;
            return v;
        }

        /// <summary>
        /// Removes ALL auto-start entries left by earlier versions: the Run key,
        /// the scheduled task, and the launcher files. Runs once (the result is
        /// remembered in the config) so old entries can never disturb the system
        /// again without spawning schtasks.exe on every startup.
        /// </summary>
        public static void CleanupAutostart()
        {
            bool removedAny = false;

            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Run", true))
                {
                    if (key != null && key.GetValue("LiveMonitor") != null)
                    {
                        key.DeleteValue("LiveMonitor", false);
                        removedAny = true;
                    }
                }
            }
            catch { }

            try
            {
                string dir = System.IO.Path.GetDirectoryName(Application.ExecutablePath);
                if (dir != null)
                {
                    string p = System.IO.Path.Combine(dir, "LiveMonitor_delayed.vbs");
                    if (System.IO.File.Exists(p)) { System.IO.File.Delete(p); removedAny = true; }
                }
            }
            catch { }

            try
            {
                string dir = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
                if (!string.IsNullOrEmpty(dir))
                {
                    string p = System.IO.Path.Combine(dir, "LiveMonitor_autostart.vbs");
                    if (System.IO.File.Exists(p)) { System.IO.File.Delete(p); removedAny = true; }
                }
            }
            catch { }

            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo("schtasks.exe", "/Delete /F /TN LiveMonitorAutoStart");
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                using (var proc = System.Diagnostics.Process.Start(psi))
                {
                    if (proc != null)
                    {
                        proc.WaitForExit(5000);
                        try { if (proc.HasExited) proc.Dispose(); } catch { }
                    }
                }
            }
            catch { }

            Log.Info("autostart cleanup done (removedAny=" + removedAny + ")");
        }
    }
}

