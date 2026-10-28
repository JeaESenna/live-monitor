using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

namespace LiveMonitor
{
    public static class Ui
    {
        public static readonly Color Bg = Color.FromArgb(255, 255, 255);
        public static readonly Color PanelBg = Color.FromArgb(247, 249, 252);
        public static readonly Color Border = Color.FromArgb(227, 232, 239);
        public static readonly Color Text = Color.FromArgb(31, 45, 61);
        public static readonly Color SubText = Color.FromArgb(138, 148, 166);
        public static readonly Color Accent = Color.FromArgb(59, 130, 246);
        public static readonly Color AccentDark = Color.FromArgb(37, 99, 235);
        public static readonly Color Green = Color.FromArgb(34, 197, 94);
        public static readonly Color Orange = Color.FromArgb(245, 158, 11);
        public static readonly Color Red = Color.FromArgb(239, 68, 68);
        public static readonly Color Gray = Color.FromArgb(154, 165, 181);
        public static readonly Color RowHover = Color.FromArgb(238, 245, 255);
        public static readonly Color GridLine = Color.FromArgb(238, 242, 247);

        /// <summary>App icon as a displayable bitmap (PNG resource; Icon.ToBitmap fails on PNG icons in .NET 4.x).</summary>
        public static Image AppImage
        {
            get
            {
                try
                {
                    using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.png"))
                    {
                        if (s != null)
                        {
                            using (var tmp = Image.FromStream(s))
                                return new Bitmap(tmp);
                        }
                    }
                }
                catch { }
                return null;
            }
        }

        public static Font F(float size, FontStyle style)
        {
            return new Font("Microsoft YaHei UI", size, style, GraphicsUnit.Point);
        }

        public static void EnableDoubleBuffer(Control c)
        {
            var pi = typeof(Control).GetProperty("DoubleBuffered",
                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic);
            if (pi != null) pi.SetValue(c, true, null);
        }

        public static GraphicsPath RoundRect(RectangleF r, float radius)
        {
            var path = new GraphicsPath();
            float d = radius * 2;
            if (d > r.Width) d = r.Width;
            if (d > r.Height) d = r.Height;
            path.AddArc(r.X, r.Y, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        public static Color Darken(Color c, float f)
        {
            return Color.FromArgb(c.A, (int)(c.R * f), (int)(c.G * f), (int)(c.B * f));
        }
    }

    /// <summary>Flat, rounded, self-painted button.</summary>
    public class FlatButton : Control
    {
        bool _hover, _down;
        public Color Back = Ui.Accent;
        public Color Hover = Ui.AccentDark;
        public Color Fore = Color.White;
        public bool UseBorder = false;

        public FlatButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
                     | ControlStyles.UserPaint | ControlStyles.ResizeRedraw
                     | ControlStyles.SupportsTransparentBackColor, true);
            Height = 32;
            Cursor = Cursors.Hand;
            Font = Ui.F(9, FontStyle.Regular);
            TabStop = true;
        }

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { _down = true; Invalidate(); } base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { _down = false; Invalidate(); base.OnMouseUp(e); }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Space || e.KeyCode == Keys.Enter)
            {
                _down = true; Invalidate();
                OnClick(EventArgs.Empty);
                _down = false; Invalidate();
            }
            base.OnKeyDown(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = _down ? Ui.Darken(Back, 0.9f) : (_hover ? Hover : Back);
            using (var path = Ui.RoundRect(new RectangleF(0.5f, 0.5f, Width - 1, Height - 1), 6))
            {
                using (var b = new SolidBrush(Enabled ? fill : Color.FromArgb(229, 232, 238)))
                    g.FillPath(b, path);
                if (UseBorder)
                {
                    using (var p = new Pen(Enabled ? Ui.Border : Color.FromArgb(229, 232, 238), 1))
                        g.DrawPath(p, path);
                }
            }
            Color fc = Enabled ? Fore : Ui.Gray;
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), fc,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        }
    }
}
