# Technical pixel export only. The card artwork is the saved built-in ImageGen result.
param([string]$CardSource = (Join-Path $PSScriptRoot 'monitor-source.png'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetRoot = Join-Path $PSScriptRoot '../../src/main/resources/assets/gtnhmelogisticsenhancement/textures'
$card = [Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $CardSource))
try {
    $small = New-Object Drawing.Bitmap 32,32
    for ($y=0; $y -lt 32; $y++) {
        for ($x=0; $x -lt 32; $x++) {
            $sx = [Math]::Min($card.Width-1, [int][Math]::Floor(($x+0.5)*$card.Width/32))
            $sy = [Math]::Min($card.Height-1, [int][Math]::Floor(($y+0.5)*$card.Height/32))
            $small.SetPixel($x,$y,$card.GetPixel($sx,$sy))
        }
    }
    $small.Save((Join-Path $assetRoot 'items/me_energy_monitor.png'),[Drawing.Imaging.ImageFormat]::Png)
    $small.Dispose()
} finally { $card.Dispose() }
# Code-native AE corner traces leave all native voltage/amperage ports unobscured.
foreach ($high in @($false,$true)) {
  foreach ($active in @($false,$true)) {
    $badge = New-Object Drawing.Bitmap 16,16
    $rects = New-Object 'System.Collections.Generic.List[string]'
    $hex = if ($active) { '#65e3ea' } else { '#b58bd7' }
    $color = [Drawing.ColorTranslator]::FromHtml($hex)
    for ($p=1; $p -le 4; $p++) {
        foreach ($point in @(@($p,1),@((15-$p),1),@($p,14),@((15-$p),14),@(1,$p),@(14,$p),@(1,(15-$p)),@(14,(15-$p)))) {
            $x=[int]$point[0]; $y=[int]$point[1]
            $badge.SetPixel($x,$y,$color)
            $rects.Add(('<rect x="{0}" y="{1}" width="1" height="1" fill="{2}"/>' -f $x,$y,$hex))
        }
    }
    if ($high) {
        foreach ($x in @(6,9)) {
            $badge.SetPixel($x,14,[Drawing.Color]::FromArgb(255,255,190,54))
            $badge.SetPixel($x,13,[Drawing.Color]::FromArgb(255,255,190,54))
            $rects.Add(('<rect x="{0}" y="13" width="1" height="2" fill="#ffbe36"/>' -f $x))
        }
    }
    $name = if ($high) { 'me_eu_badge_high' } else { 'me_eu_badge' }
    if ($active) { $name += '_active' }
    $badge.Save((Join-Path $assetRoot "blocks/$name.png"),[Drawing.Imaging.ImageFormat]::Png)
    $badge.Dispose()
    $svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" width="16" height="16" shape-rendering="crispEdges">' + [string]::Join('', $rects) + '</svg>'
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$name.svg"), $svg, (New-Object Text.UTF8Encoding $false))
  }
}
