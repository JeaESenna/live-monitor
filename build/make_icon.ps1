# Generate app icon: white rounded square, black text "ZhiBoJianCe" (U+76F4 U+64AD U+76D1 U+6D4B)
# arranged in a 2x2 tian-zi grid, reading left-to-right then top-to-bottom.
#
# ICO format note: Windows only officially supports PNG-compressed entries for
# the 256x256 size. Small sizes MUST be classic 32bpp BMP/DIB entries, otherwise
# legacy GDI/shell icon code paths (and .NET's Icon.ToBitmap) can misbehave.
# We therefore emit 16/20/24/32/40/48/64 as DIB entries and only 256 as PNG.
#
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

# ---- 32bpp BMP/DIB ICO entry: BITMAPINFOHEADER + bottom-up BGRA + AND mask ----
function Get-DibEntry([System.Drawing.Bitmap]$bmp) {
    $size = $bmp.Width
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $raw = New-Object byte[] ($stride * $size)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $raw, 0, $raw.Length)
    $bmp.UnlockBits($data)

    $maskRow = [int]([Math]::Floor(($size + 31) / 32)) * 4
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    # BITMAPINFOHEADER (40 bytes), biHeight = 2 * size (XOR bitmap + AND mask)
    $bw.Write([UInt32]40)
    $bw.Write([Int32]$size)
    $bw.Write([Int32]($size * 2))
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]0)                        # BI_RGB
    $bw.Write([UInt32]($size * $size * 4))      # biSizeImage
    $bw.Write([Int32]0)                         # biXPelsPerMeter
    $bw.Write([Int32]0)                         # biYPelsPerMeter
    $bw.Write([UInt32]0)                        # biClrUsed
    $bw.Write([UInt32]0)                        # biClrImportant
    # XOR bitmap: bottom-up rows, BGRA
    for ($y = $size - 1; $y -ge 0; $y--) {
        $rowStart = $y * $stride
        for ($x = 0; $x -lt ($size * 4); $x++) {
            $bw.Write([byte]$raw[$rowStart + $x])
        }
    }
    # AND mask (1 = transparent), bottom-up; Windows ignores it for 32bpp icons
    # but legacy code paths still use it, so derive it from the alpha channel.
    for ($y = $size - 1; $y -ge 0; $y--) {
        $rowStart = $y * $stride
        $mrow = New-Object byte[] $maskRow
        for ($x = 0; $x -lt $size; $x++) {
            if ($raw[$rowStart + $x * 4 + 3] -lt 128) {
                $byteIndex = [int][Math]::Floor($x / 8)
                $bit = 7 - ($x % 8)
                $mrow[$byteIndex] = [byte]($mrow[$byteIndex] -bor (1 -shl $bit))
            }
        }
        $bw.Write([byte[]]$mrow)
    }
    $bw.Flush()
    $arr = $ms.ToArray()
    $bw.Dispose()
    $ms.Dispose()
    return , $arr
}

function Get-PngBytes([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $ms.ToArray()
    $ms.Dispose()
    return , $bytes
}

# ---- 256 preview png (also used as the in-app image resource) ----
$bmp256 = New-IconBitmap 256 $true
[System.IO.File]::WriteAllBytes((Join-Path $resDir 'app_preview.png'), (Get-PngBytes $bmp256))

# ---- assemble a spec-compliant multi-size ICO (small = DIB, 256 = PNG) ----
$entries = New-Object System.Collections.ArrayList
foreach ($size in @(16, 20, 24, 32, 40, 48, 64)) {
    $bmp = New-IconBitmap $size ($size -ge 40)
    $dib = Get-DibEntry $bmp
    $bmp.Dispose()
    [void]$entries.Add(@{ Size = $size; Data = [byte[]]$dib })
    Write-Output ("icon size " + $size + ": DIB " + $dib.Length + " bytes")
}
[void]$entries.Add(@{ Size = 256; Data = [byte[]](Get-PngBytes $bmp256) })
$bmp256.Dispose()
Write-Output "icon size 256: PNG"

$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0)   # reserved
$bw.Write([UInt16]1)   # type: icon
$bw.Write([UInt16]$entries.Count)
$offset = 6 + 16 * $entries.Count
foreach ($e in $entries) {
    $dim = if ($e.Size -eq 256) { 0 } else { $e.Size }
    $bw.Write([byte]$dim)                       # width (0 = 256)
    $bw.Write([byte]$dim)                       # height (0 = 256)
    $bw.Write([byte]0)                          # color count
    $bw.Write([byte]0)                          # reserved
    $bw.Write([UInt16]1)                        # planes
    $bw.Write([UInt16]32)                       # bpp
    $bw.Write([UInt32]$e.Data.Length)           # bytes in resource
    $bw.Write([UInt32]$offset)                  # image offset
    $offset += $e.Data.Length
}
foreach ($e in $entries) { $bw.Write([byte[]]$e.Data) }
$bw.Flush()
$icoPath = Join-Path $resDir 'app.ico'
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Dispose()
$ms.Dispose()
Write-Output ("icon written: " + $icoPath + " (" + (Get-Item $icoPath).Length + " bytes)")
