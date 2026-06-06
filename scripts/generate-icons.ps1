#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$SourceImage = (Join-Path (Split-Path -Parent $PSScriptRoot) 'frontend\src\assets\hero-pigeon.png'),
    [int]$CropX = 750,
    [int]$CropY = 0,
    [int]$CropSize = 860
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$WebPublicDir = Join-Path $Root 'frontend\public'
$AndroidResDir = Join-Path $Root 'frontend\android\app\src\main\res'
$PreviewPath = Join-Path $Root 'frontend\src\assets\vlugboek-icon-preview.png'

Add-Type -AssemblyName System.Drawing

function New-Directory {
    param([string]$Path)
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function New-Color {
    param(
        [string]$Hex,
        [int]$Alpha = 255
    )

    $value = $Hex.TrimStart('#')
    return [System.Drawing.Color]::FromArgb(
        $Alpha,
        [Convert]::ToInt32($value.Substring(0, 2), 16),
        [Convert]::ToInt32($value.Substring(2, 2), 16),
        [Convert]::ToInt32($value.Substring(4, 2), 16)
    )
}

function Set-Quality {
    param([System.Drawing.Graphics]$Graphics)

    $Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $Graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $Graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
}

function New-RoundedRectanglePath {
    param(
        [System.Drawing.Rectangle]$Rect,
        [int]$Radius
    )

    $diameter = $Radius * 2
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($Rect.X, $Rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($Rect.X, $Rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function Save-Png {
    param(
        [System.Drawing.Bitmap]$Bitmap,
        [string]$Path
    )

    New-Directory (Split-Path -Parent $Path)
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function New-IconBitmap {
    param(
        [System.Drawing.Image]$Source,
        [int]$Size,
        [double]$ContentScale = 1.0,
        [string]$BackgroundHex = '',
        [switch]$Round
    )

    $bitmap = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)

    try {
        Set-Quality $graphics
        if ($BackgroundHex) {
            $graphics.Clear((New-Color $BackgroundHex))
        } else {
            $graphics.Clear([System.Drawing.Color]::Transparent)
        }

        $clipPath = $null
        if ($Round) {
            $clipPath = New-Object System.Drawing.Drawing2D.GraphicsPath
            $clipPath.AddEllipse(0, 0, $Size, $Size)
            $graphics.SetClip($clipPath)
        }

        $safeCropSize = [Math]::Min($CropSize, [Math]::Min($Source.Width - $CropX, $Source.Height - $CropY))
        if ($safeCropSize -le 0) {
            throw 'Crop values fall outside the source image.'
        }

        $sourceRect = New-Object -TypeName System.Drawing.Rectangle -ArgumentList $CropX, $CropY, $safeCropSize, $safeCropSize
        $contentSize = [Math]::Max(1, [Math]::Min($Size, [int][Math]::Round($Size * $ContentScale)))
        $contentOffset = [int][Math]::Floor(($Size - $contentSize) / 2)
        $destRect = New-Object -TypeName System.Drawing.Rectangle -ArgumentList $contentOffset, $contentOffset, $contentSize, $contentSize
        $graphics.DrawImage($Source, $destRect, $sourceRect.X, $sourceRect.Y, $sourceRect.Width, $sourceRect.Height, [System.Drawing.GraphicsUnit]::Pixel)

        $navyOverlay = New-Object -TypeName System.Drawing.SolidBrush -ArgumentList (New-Color '#0B1623' 44)
        $graphics.FillRectangle($navyOverlay, $destRect)
        $navyOverlay.Dispose()

        $gold = New-Color '#C79A47'
        $ringWidth = [Math]::Max(2, [int]($contentSize * 0.032))
        $ringInset = [Math]::Max(4, [int]($contentSize * 0.055))
        $pen = New-Object -TypeName System.Drawing.Pen -ArgumentList $gold, $ringWidth
        $pen.Alignment = [System.Drawing.Drawing2D.PenAlignment]::Inset
        $ringRect = New-Object -TypeName System.Drawing.Rectangle -ArgumentList ($contentOffset + $ringInset), ($contentOffset + $ringInset), ($contentSize - ($ringInset * 2)), ($contentSize - ($ringInset * 2))

        if ($Round) {
            $graphics.DrawEllipse($pen, $ringRect)
        } else {
            $radius = [Math]::Max(8, [int]($contentSize * 0.16))
            $ringPath = New-RoundedRectanglePath $ringRect $radius
            try {
                $graphics.DrawPath($pen, $ringPath)
            } finally {
                $ringPath.Dispose()
            }
        }

        $pen.Dispose()
        if ($clipPath) {
            $graphics.ResetClip()
            $clipPath.Dispose()
        }
    } finally {
        $graphics.Dispose()
    }

    return $bitmap
}

function Export-IconSet {
    param([System.Drawing.Image]$Source)

    New-Directory $WebPublicDir

    $webSizes = @{
        'favicon-16x16.png' = 16
        'favicon-32x32.png' = 32
        'favicon-48x48.png' = 48
        'apple-touch-icon.png' = 180
        'android-chrome-192x192.png' = 192
        'android-chrome-512x512.png' = 512
    }

    foreach ($entry in $webSizes.GetEnumerator()) {
        $background = if ($entry.Key -eq 'apple-touch-icon.png') { '#F8F6F1' } else { '' }
        $bmp = New-IconBitmap -Source $Source -Size $entry.Value -BackgroundHex $background
        try {
            Save-Png $bmp (Join-Path $WebPublicDir $entry.Key)
        } finally {
            $bmp.Dispose()
        }
    }

    $icoFrames = @()
    foreach ($size in @(16, 32, 48)) {
        $bmp = New-IconBitmap -Source $Source -Size $size
        $stream = New-Object System.IO.MemoryStream
        try {
            $bmp.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
            $icoFrames += [pscustomobject]@{ Size = $size; Bytes = $stream.ToArray() }
        } finally {
            $stream.Dispose()
            $bmp.Dispose()
        }
    }
    Write-Ico -Frames $icoFrames -Path (Join-Path $WebPublicDir 'favicon.ico')

    $manifest = @'
{
  "name": "Vlugboek",
  "short_name": "Vlugboek",
  "description": "Suid-Afrikaanse wedvlug uitslae, ranglyste en amptelike PDF verslae.",
  "id": "/",
  "start_url": "/",
  "scope": "/",
  "lang": "af-ZA",
  "display": "standalone",
  "orientation": "portrait",
  "theme_color": "#0B1623",
  "background_color": "#F8F6F1",
  "categories": ["sports", "utilities"],
  "icons": [
    {
      "src": "/android-chrome-192x192.png",
      "sizes": "192x192",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/android-chrome-512x512.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "any maskable"
    }
  ]
}
'@
    Set-Content -LiteralPath (Join-Path $WebPublicDir 'site.webmanifest') -Value $manifest -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $WebPublicDir 'manifest.webmanifest') -Value $manifest -Encoding UTF8
}

function Write-Ico {
    param(
        [array]$Frames,
        [string]$Path
    )

    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    $writer = New-Object System.IO.BinaryWriter($stream)

    try {
        $writer.Write([UInt16]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]$Frames.Count)

        $offset = 6 + ($Frames.Count * 16)
        foreach ($frame in $Frames) {
            $writer.Write([byte]$(if ($frame.Size -ge 256) { 0 } else { $frame.Size }))
            $writer.Write([byte]$(if ($frame.Size -ge 256) { 0 } else { $frame.Size }))
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]32)
            $writer.Write([UInt32]$frame.Bytes.Length)
            $writer.Write([UInt32]$offset)
            $offset += $frame.Bytes.Length
        }

        foreach ($frame in $Frames) {
            $writer.Write($frame.Bytes)
        }
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Export-AndroidIcons {
    param([System.Drawing.Image]$Source)

    $legacySizes = @{
        'mipmap-mdpi' = 48
        'mipmap-hdpi' = 72
        'mipmap-xhdpi' = 96
        'mipmap-xxhdpi' = 144
        'mipmap-xxxhdpi' = 192
    }
    $foregroundSizes = @{
        'mipmap-mdpi' = 108
        'mipmap-hdpi' = 162
        'mipmap-xhdpi' = 216
        'mipmap-xxhdpi' = 324
        'mipmap-xxxhdpi' = 432
    }

    foreach ($entry in $legacySizes.GetEnumerator()) {
        $dir = Join-Path $AndroidResDir $entry.Key

        $square = New-IconBitmap -Source $Source -Size $entry.Value
        try {
            Save-Png $square (Join-Path $dir 'ic_launcher.png')
        } finally {
            $square.Dispose()
        }

        $round = New-IconBitmap -Source $Source -Size $entry.Value -Round
        try {
            Save-Png $round (Join-Path $dir 'ic_launcher_round.png')
        } finally {
            $round.Dispose()
        }
    }

    foreach ($entry in $foregroundSizes.GetEnumerator()) {
        $dir = Join-Path $AndroidResDir $entry.Key
        $foreground = New-IconBitmap -Source $Source -Size $entry.Value -ContentScale 0.72
        try {
            Save-Png $foreground (Join-Path $dir 'ic_launcher_foreground.png')
        } finally {
            $foreground.Dispose()
        }
    }
}

function Export-Preview {
    param([System.Drawing.Image]$Source)

    $preview = New-Object -TypeName System.Drawing.Bitmap -ArgumentList 1024, 360, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($preview)

    try {
        Set-Quality $graphics
        $graphics.Clear((New-Color '#F8F6F1'))
        $sizes = @(256, 128, 64, 32)
        $x = 40
        foreach ($size in $sizes) {
            $icon = New-IconBitmap -Source $Source -Size $size
            try {
                $y = [int]((360 - $size) / 2)
                $graphics.DrawImage($icon, $x, $y, $size, $size)
                $x += $size + 48
            } finally {
                $icon.Dispose()
            }
        }
    } finally {
        $graphics.Dispose()
    }

    try {
        Save-Png $preview $PreviewPath
    } finally {
        $preview.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $SourceImage)) {
    throw "Source image not found at $SourceImage"
}
if (-not (Test-Path -LiteralPath $AndroidResDir)) {
    throw "Android resource directory not found at $AndroidResDir"
}

$source = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $SourceImage).Path)
try {
    Export-IconSet $source
    Export-AndroidIcons $source
    Export-Preview $source
} finally {
    $source.Dispose()
}

Write-Host "Generated web icons in $WebPublicDir"
Write-Host "Generated Android icons in $AndroidResDir"
Write-Host "Preview: $PreviewPath"
