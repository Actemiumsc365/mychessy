Add-Type -AssemblyName System.Drawing
$resourceRoot = Join-Path $PSScriptRoot '../DroidFishApp/src/main/res'
$sizes = @{ 'mipmap-mdpi' = 48; 'mipmap-hdpi' = 72; 'mipmap-xhdpi' = 96; 'mipmap-xxhdpi' = 144; 'mipmap-xxxhdpi' = 192; 'drawable-nodpi' = 192 }
foreach ($directory in $sizes.Keys) {
    $size = $sizes[$directory]
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#11665B'))
    $scale = $size / 108.0
    $graphics.ScaleTransform($scale, $scale)
    $points = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(25, 32), [System.Drawing.PointF]::new(40, 43),
        [System.Drawing.PointF]::new(54, 24), [System.Drawing.PointF]::new(68, 43),
        [System.Drawing.PointF]::new(83, 32), [System.Drawing.PointF]::new(74, 69),
        [System.Drawing.PointF]::new(34, 69))
    $graphics.FillPolygon([System.Drawing.Brushes]::White, $points)
    $graphics.FillRectangle([System.Drawing.Brushes]::White, 33, 74, 42, 9)
    $gold = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#F3C653'))
    foreach ($point in @(@(25, 28), @(54, 20), @(83, 28))) {
        $graphics.FillEllipse($gold, ($point[0] - 4), ($point[1] - 4), 8, 8)
    }
    $destination = Join-Path $resourceRoot $directory
    [System.IO.Directory]::CreateDirectory($destination) | Out-Null
    $names = if ($directory -eq 'drawable-nodpi') { @('mastermind_launcher') } else { @('ic_launcher', 'ic_launcher_round') }
    foreach ($name in $names) {
        $bitmap.Save((Join-Path $destination "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    }
    $gold.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}