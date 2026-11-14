# Technical pixel export only. The card artwork is the saved built-in ImageGen result.
param([string]$CardSource = (Join-Path $PSScriptRoot 'monitor-source.png'), [switch]$BadgesOnly)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetRoot = Join-Path $PSScriptRoot '../../src/main/resources/assets/gtnhmelogisticsenhancement/textures'
if (-not $BadgesOnly) {
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
}
# Code-native recessed metal brackets and ME circuit lamps keep the native EU port unobscured.
function Add-PixelRect($bitmap, $rects, [int]$x, [int]$y, [int]$width, [int]$height, [string]$hex) {
    $color = [Drawing.ColorTranslator]::FromHtml($hex)
    for ($py=$y; $py -lt ($y+$height); $py++) {
        for ($px=$x; $px -lt ($x+$width); $px++) { $bitmap.SetPixel($px,$py,$color) }
    }
    $rects.Add(('<rect x="{0}" y="{1}" width="{2}" height="{3}" fill="{4}"/>' -f $x,$y,$width,$height,$hex))
}
foreach ($high in @($false,$true)) {
  foreach ($active in @($false,$true)) {
    $badge = New-Object Drawing.Bitmap 16,16
    $rects = New-Object 'System.Collections.Generic.List[string]'
    $trace = if ($active) { '#269eae' } else { '#69478d' }
    $lamp = if ($active) { '#94f4ee' } else { '#ba90e5' }
    # Mirrored metal corner brackets, with a dark recessed circuit channel on the inside.
    foreach ($flipX in @($false,$true)) {
        foreach ($flipY in @($false,$true)) {
            for ($y=1; $y -le 4; $y++) {
                for ($x=1; $x -le 4; $x++) {
                    if ($x -eq 4 -and $y -eq 4) { continue }
                    $hex = if ($x -eq 1 -or $y -eq 1) { '#333c45' }
                        elseif ($x -eq 2 -and $y -eq 2) { '#d1d8dc' }
                        elseif ($x -eq 2 -or $y -eq 2) { '#89969f' }
                        elseif ($x -eq 3 -and $y -eq 3) { $lamp }
                        else { $trace }
                    $px = if ($flipX) { 15-$x } else { $x }
                    $py = if ($flipY) { 15-$y } else { $y }
                    Add-PixelRect $badge $rects $px $py 1 1 $hex
                }
            }
        }
    }
    # Four inset ME status displays. The 8x8 EU port area remains entirely transparent.
    Add-PixelRect $badge $rects 6 1 4 2 '#202931'
    Add-PixelRect $badge $rects 6 1 4 1 $trace
    Add-PixelRect $badge $rects 7 2 2 1 $lamp
    Add-PixelRect $badge $rects 1 6 2 4 '#202931'
    Add-PixelRect $badge $rects 1 6 1 4 $trace
    Add-PixelRect $badge $rects 2 7 1 2 $lamp
    Add-PixelRect $badge $rects 13 6 2 4 '#202931'
    Add-PixelRect $badge $rects 14 6 1 4 $trace
    Add-PixelRect $badge $rects 13 7 1 2 $lamp
    Add-PixelRect $badge $rects 6 13 4 2 '#202931'
    Add-PixelRect $badge $rects 6 14 4 1 $trace
    Add-PixelRect $badge $rects 7 13 2 1 $lamp
    if ($high) {
        foreach ($x in @(6,9)) {
            Add-PixelRect $badge $rects $x 13 1 1 '#ffe39b'
            Add-PixelRect $badge $rects $x 14 1 1 '#e7a339'
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
# A separate paint channel, so group colour never replaces the ME activity lamps or native EU port.
# The dark/light keyline keeps black and white paint readable against every multiblock casing.
foreach ($mask in @('frame','stripe')) {
    $bitmap = New-Object Drawing.Bitmap 16,16
    $rects = New-Object 'System.Collections.Generic.List[string]'
    if ($mask -eq 'frame') {
        Add-PixelRect $bitmap $rects 1 0 14 1 '#232b32'
        Add-PixelRect $bitmap $rects 1 15 14 1 '#232b32'
        Add-PixelRect $bitmap $rects 0 1 1 14 '#232b32'
        Add-PixelRect $bitmap $rects 15 1 1 14 '#232b32'
        foreach ($x in @(1,14)) {
            foreach ($y in @(1,14)) { Add-PixelRect $bitmap $rects $x $y 1 1 '#cdd5da' }
        }
    } else {
        Add-PixelRect $bitmap $rects 3 0 10 1 '#ffffff'
        Add-PixelRect $bitmap $rects 3 15 10 1 '#ffffff'
        Add-PixelRect $bitmap $rects 0 3 1 10 '#ffffff'
        Add-PixelRect $bitmap $rects 15 3 1 10 '#ffffff'
    }
    $name = "me_eu_group_$mask"
    $bitmap.Save((Join-Path $assetRoot "blocks/$name.png"),[Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    $svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" width="16" height="16" shape-rendering="crispEdges">' + [string]::Join('', $rects) + '</svg>'
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$name.svg"), $svg, (New-Object Text.UTF8Encoding $false))
}
