using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace LiveMonitor
{
    public class AddRoomForm : Form
    {
        readonly AppConfig _cfg;
        ComboBox _platformBox;
        TextBox _inputBox, _remarkBox;
        Label _previewLabel, _checkLabel;
        FlatButton _btnAdd, _btnCancel;
        string _platform, _id, _url;
        CheckResult _lastResult;
        bool _checking;

        static readonly string[] PlatformValues = { "douyu", "huya", "bilibili", "douyin", "kuaishou", "auto" };

        public RoomItem ResultRoom;

        public AddRoomForm(AppConfig cfg)
        {
            _cfg = cfg;
            Text = "添加直播间";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            ClientSize = new Size(508, 408);
            BackColor = Ui.Bg;
            Font = Ui.F(9, FontStyle.Regular);
            BuildUi();
        }

        void BuildUi()
        {
            var lblPlatform = new Label();
            lblPlatform.Text = "平台";
            lblPlatform.Location = new Point(26, 26);
            lblPlatform.AutoSize = true;
            lblPlatform.ForeColor = Ui.Text;
            Controls.Add(lblPlatform);

            _platformBox = new ComboBox();
            _platformBox.DropDownStyle = ComboBoxStyle.DropDownList;
            _platformBox.Font = Ui.F(9, FontStyle.Regular);
            _platformBox.Items.AddRange(new object[] { "斗鱼", "虎牙", "B站(bilibili)", "抖音", "快手", "自动识别(推荐粘贴链接)" });
            _platformBox.SelectedIndex = 5;
            _platformBox.Bounds = new Rectangle(92, 22, 390, 28);
            _platformBox.SelectedIndexChanged += delegate { UpdatePreview(); };
            Controls.Add(_platformBox);

            var lblInput = new Label();
            lblInput.Text = "直播间链接或房间号";
            lblInput.Location = new Point(26, 68);
            lblInput.AutoSize = true;
            lblInput.ForeColor = Ui.Text;
            Controls.Add(lblInput);

            _inputBox = new TextBox();
            _inputBox.Bounds = new Rectangle(26, 92, 456, 28);
            _inputBox.Font = Ui.F(9, FontStyle.Regular);
            _inputBox.TextChanged += delegate { UpdatePreview(); };
            Controls.Add(_inputBox);

            var lblHint = new Label();
            lblHint.Text = "示例:https://www.douyu.com/9999 、 https://live.bilibili.com/6 、 虎牙房间号 660000";
            lblHint.Location = new Point(26, 124);
            lblHint.AutoSize = true;
            lblHint.ForeColor = Ui.SubText;
            lblHint.Font = Ui.F(8, FontStyle.Regular);
            Controls.Add(lblHint);

            var lblRemark = new Label();
            lblRemark.Text = "备注名称(可选,留空自动获取主播名)";
            lblRemark.Location = new Point(26, 162);
            lblRemark.AutoSize = true;
            lblRemark.ForeColor = Ui.Text;
            Controls.Add(lblRemark);

            _remarkBox = new TextBox();
            _remarkBox.Bounds = new Rectangle(26, 186, 456, 28);
            _remarkBox.Font = Ui.F(9, FontStyle.Regular);
            Controls.Add(_remarkBox);

            _previewLabel = new Label();
            _previewLabel.Bounds = new Rectangle(26, 226, 456, 20);
            _previewLabel.ForeColor = Ui.SubText;
            _previewLabel.Font = Ui.F(8.5f, FontStyle.Regular);
            _previewLabel.AutoEllipsis = true;
            Controls.Add(_previewLabel);

            _checkLabel = new Label();
            _checkLabel.Bounds = new Rectangle(26, 252, 456, 56);
            _checkLabel.ForeColor = Ui.Text;
            _checkLabel.Font = Ui.F(8.5f, FontStyle.Regular);
            Controls.Add(_checkLabel);

            _btnCancel = new FlatButton();
            _btnCancel.Text = "取消";
            _btnCancel.Back = Ui.PanelBg;
            _btnCancel.Hover = Ui.Border;
            _btnCancel.Fore = Ui.Text;
            _btnCancel.Bounds = new Rectangle(276, 344, 92, 34);
            _btnCancel.Click += delegate { DialogResult = DialogResult.Cancel; Close(); };
            Controls.Add(_btnCancel);

            _btnAdd = new FlatButton();
            _btnAdd.Text = "添加";
            _btnAdd.Bounds = new Rectangle(390, 344, 92, 34);
            _btnAdd.Click += AddClick;
            Controls.Add(_btnAdd);
        }

        void UpdatePreview()
        {
            string platform = PlatformValues[_platformBox.SelectedIndex];
            string p, id, url, msg;
            if (PlatformApi.ParseInput(_inputBox.Text, platform, out p, out id, out url, out msg))
            {
                _previewLabel.Text = "将监测: " + PlatformApi.PlatformName(p) + " · " + id + "   →  " + url;
            }
            else
            {
                _previewLabel.Text = msg;
            }
        }

        void AddClick(object sender, EventArgs e)
        {
            if (_checking) return;
            string platform = PlatformValues[_platformBox.SelectedIndex];
            string msg;
            if (!PlatformApi.ParseInput(_inputBox.Text, platform, out _platform, out _id, out _url, out msg))
            {
                MessageBox.Show(this, msg, "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            _btnAdd.Enabled = false;
            _checking = true;
            _checkLabel.Text = "正在检测直播间信息…";
            _checkLabel.ForeColor = Ui.SubText;

            string plat = _platform, rid = _id, rurl = _url;
            ThreadPool.QueueUserWorkItem(delegate
            {
                CheckResult res = PlatformApi.Check(plat, rid, rurl);
                if (IsDisposed) return;
                try { BeginInvoke((Action)(() => FinishCheck(res))); }
                catch { }
            });
        }

        void FinishCheck(CheckResult res)
        {
            _checking = false;
            _btnAdd.Enabled = true;
            _lastResult = res;

            string who = string.IsNullOrEmpty(res.Owner) ? "(未能获取主播名)" : res.Owner;
            switch (res.State)
            {
                case LiveState.Live:
                    _checkLabel.ForeColor = Ui.Green;
                    _checkLabel.Text = "已识别: " + (string.IsNullOrEmpty(res.Sub) ? "直播中" : res.Sub)
                        + " · 主播 " + who + (string.IsNullOrEmpty(res.Title) ? "" : "\r\n标题: " + res.Title);
                    break;
                case LiveState.Offline:
                    _checkLabel.ForeColor = Ui.Gray;
                    _checkLabel.Text = "已识别: 未开播 · 主播 " + who;
                    break;
                case LiveState.NotFound:
                    _checkLabel.ForeColor = Ui.Red;
                    _checkLabel.Text = "该房间不存在或ID有误,可强制添加";
                    break;
                default:
                    _checkLabel.ForeColor = Ui.Orange;
                    _checkLabel.Text = "检测失败: " + res.Error + "\r\n(网络异常也可强制添加,稍后软件会继续检测)";
                    break;
            }

            if (res.State == LiveState.NotFound || res.State == LiveState.Error)
            {
                var r = MessageBox.Show(this,
                    "未能正常识别该直播间,是否仍要添加?\r\n添加后软件会继续定时检测该房间。",
                    "确认添加", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (r != DialogResult.Yes) return;
            }

            lock (_cfg)
            {
                foreach (var item in _cfg.Rooms)
                {
                    if (item.Platform == _platform && item.Id == _id)
                    {
                        MessageBox.Show(this, "该直播间已在监测列表中。", "提示",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
                        return;
                    }
                }
            }

            string remark = _remarkBox.Text.Trim();
            ResultRoom = new RoomItem(_platform, _id, _url, remark);
            if (remark.Length == 0) ResultRoom.AutoName = res.Owner;
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
