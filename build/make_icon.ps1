# Generate app icon: white rounded square, black text "ZhiBoJianCe" (U+76F4 U+64AD U+76D1 U+6D4B)
# arranged in a 2x2 tian-zi grid, reading left-to-right then top-to-bottom.
# ASCII-only script (PS 5.1 compatible). Chinese chars embedded via code points.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $root 'res'
if (-not (Test-Path $resDir)) { New-Item -ItemType Directory -Path $resDir | Out-Null }

# 直(0x76F4) 播(0x64AD) 监(0x76D1) 测(0x6D4B)
$chars = @([char]0x76F4, [char]0x64AD, [char]0x76D1, [char]0x6D4B)

function New-IconBitmap([int]$size, [bool]$withGrid) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    $pad = [Math]::Max(1, [int]($size * 0.02))
    $rad = [int]($size * 0.17)
    $rect = New-Object System.Drawing.Rectangle($pad, $pad, ($size - 2 * $pad), ($size - 2 * $pad))
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $rad * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillPath($white, $path)
    $border = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 224, 228, 235), [Math]::Max(1.0, $size / 128.0))
    $g.DrawPath($border, $path)

    if ($withGrid) {
        $inner = [int]($size * 0.08)
        $gridRect = New-Object System.Drawing.Rectangle($inner, $inner, ($size - 2 * $inner), ($size - 2 * $inner))
        $gridPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 201, 210, 220), [Math]::Max(1.0, $size / 96.0))
        $g.DrawRectangle($gridPen, $gridRect)
        $cx = $gridRect.X + $gridRect.Width / 2
        $cy = $gridRect.Y + $gridRect.Height / 2
        $g.DrawLine($gridPen, $cx, $gridRect.Y, $cx, $gridRect.Bottom)
        $g.DrawLine($gridPen, $gridRect.X, $cy, $gridRect.Right, $cy)
        $gridPen.Dispose()
    }

    $black = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 20, 24, 33))
    $fontSize = [int]($size * 0.34)
    $font = New-Object System.Drawing.Font('Microsoft YaHei', $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = [System.Drawing.StringAlignment]::Center
    $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center

    $inner2 = if ($withGrid) { [int]($size * 0.08) } else { [int]($size * 0.03) }
    $cellW = ($size - 2 * $inner2) / 2.0
    $cellH = ($size - 2 * $inner2) / 2.0
    for ($i = 0; $i -lt 4; $i++) {
        $col = $i % 2
        $row = [int][Math]::Floor($i / 2)
        $x = $inner2 + $col * $cellW
        $y = $inner2 + $row * $cellH
        $cell = New-Object System.Drawing.RectangleF($x, $y, $cellW, $cellH)
        $g.DrawString([string]$chars[$i], $font, $black, $cell, $fmt)
    }

    $fmt.Dispose(); $font.Dispose(); $black.Dispose(); $white.Dispose()
    $border.Dispose(); $path.Dispose(); $g.Dispose()
    return $bmp
}

function Get-PngBytes([int]$size) {
    $withGrid = $size -ge 40
    $bmp = New-IconBitmap $size $withGrid
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $ms.ToArray()
    $ms.Dispose(); $bmp.Dispose()
    return ,$bytes
}

$sizes = @(256, 128, 64, 48, 40, 32, 24, 20, 16)
$pngs = New-Object System.Collections.ArrayList
foreach ($s in $sizes) {
    $data = Get-PngBytes $s
    [void]$pngs.Add($data)
}

# save 256 preview png
$preview = Get-PngBytes 256
[System.IO.File]::WriteAllBytes((Join-Path $resDir 'app_preview.png'), $preview)

# assemble multi-size ICO (PNG-compressed entries)
$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0)   # reserved
$bw.Write([UInt16]1)   # type: icon
$bw.Write([UInt16]$pngs.Count)
$offset = 6 + 16 * $pngs.Count
for ($i = 0; $i -lt $pngs.Count; $i++) {
    $s = $sizes[$i]
    $data = $pngs[$i]
    $dim = if ($s -eq 256) { 0 } else { $s }
    $bw.Write([byte]$dim)   # width
    $bw.Write([byte]$dim)   # height
    $bw.Write([byte]0)      # colors
    $bw.Write([byte]0)      # reserved
    $bw.Write([UInt16]1)    # planes
    $bw.Write([UInt16]32)   # bpp
    $bw.Write([UInt32]$data.Length)
    $bw.Write([UInt32]$offset)
    $offset += $data.Length
}
foreach ($p in $pngs) { $bw.Write([byte[]]$p) }
$bw.Flush()
$icoPath = Join-Path $resDir 'app.ico'
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Dispose(); $ms.Dispose()
Write-Output ("icon written: " + $icoPath + " (" + (Get-Item $icoPath).Length + " bytes)")
