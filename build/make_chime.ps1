# Generate a pleasant three-note chime (C6-E6-G6 arpeggio) as chime.wav
# 44100 Hz, 16-bit, mono PCM. ASCII-only script.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $root 'res'
if (-not (Test-Path $resDir)) { New-Item -ItemType Directory -Path $resDir | Out-Null }
$out = Join-Path $resDir 'chime.wav'

$sampleRate = 44100
$noteStart = @(0.0, 0.26, 0.50)
$noteFreq  = @(1046.50, 1318.51, 1567.98)
$noteDur   = @(0.34, 0.34, 0.55)
$total = 0.50 + 0.55 + 0.12
$count = [int]($total * $sampleRate)
$samples = New-Object double[] $count

for ($i = 0; $i -lt $count; $i++) {
    $t = $i / $sampleRate
    $v = 0.0
    for ($n = 0; $n -lt 3; $n++) {
        $tn = $t - $noteStart[$n]
        if ($tn -ge 0 -and $tn -lt $noteDur[$n]) {
            $env = [Math]::Min(1.0, $tn / 0.012) * [Math]::Exp(-2.6 * $tn)
            $left = $noteDur[$n] - $tn
            if ($left -lt 0.045) { $env *= $left / 0.045 }
            $v += [Math]::Sin(2 * [Math]::PI * $noteFreq[$n] * $tn) * $env
        }
    }
    $samples[$i] = $v * 0.38
}

$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$dataSize = $count * 2
# RIFF header
$bw.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
$bw.Write([UInt32]($dataSize + 36))
$bw.Write([System.Text.Encoding]::ASCII.GetBytes('WAVE'))
# fmt chunk
$bw.Write([System.Text.Encoding]::ASCII.GetBytes('fmt '))
$bw.Write([UInt32]16)
$bw.Write([UInt16]1)                 # PCM
$bw.Write([UInt16]1)                 # mono
$bw.Write([UInt32]$sampleRate)
$bw.Write([UInt32]($sampleRate * 2)) # byte rate
$bw.Write([UInt16]2)                 # block align
$bw.Write([UInt16]16)                # bits
# data chunk
$bw.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
$bw.Write([UInt32]$dataSize)
foreach ($s in $samples) {
    $v = [int][Math]::Max(-32767, [Math]::Min(32767, [Math]::Round($s * 32767)))
    $bw.Write([Int16]$v)
}
$bw.Flush()
[System.IO.File]::WriteAllBytes($out, $ms.ToArray())
$bw.Dispose(); $ms.Dispose()
Write-Output ("chime written: " + $out + " (" + (Get-Item $out).Length + " bytes)")
