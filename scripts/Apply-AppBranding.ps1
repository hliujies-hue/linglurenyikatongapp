param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$Work)
$ErrorActionPreference='Stop'
$configPath=Join-Path $Root 'app-branding.json'
if(-not (Test-Path -LiteralPath $configPath)){ return }
$config=Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$name=[string]$config.appName
if([string]::IsNullOrWhiteSpace($name) -or $name.Length -gt 30 -or $name -match '[\p{Cc}@?]'){throw 'APP名称必须为1至30字，不能含控制符、@或?'}
$manifest=Join-Path $Work 'app\src\main\AndroidManifest.xml'
$xml=New-Object System.Xml.XmlDocument
$xml.Load($manifest)
$android='http://schemas.android.com/apk/res/android'
[void]$xml.manifest.application.SetAttribute('label',$android,$name)
foreach($activity in @($xml.manifest.application.SelectNodes('activity'))){
 if($activity.GetAttribute('name',$android) -eq 'com.lingluren.yikatong.terminal.TerminalEntryActivity'){[void]$activity.SetAttribute('label',$android,$name)}
}
$icon=[string]$config.iconUrl
if($icon){
 $uri=[Uri]$icon
 if(-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Fragment){throw 'APP图标必须为HTTPS地址，不能包含账号或片段'}
 Add-Type -AssemblyName System.Net.Http
 Add-Type -AssemblyName System.Drawing
 $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false
 $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(30)
 $response=$null;$stream=$null;$memory=[IO.MemoryStream]::new();$source=$null;$bitmap=$null;$graphics=$null
 try {
  $response=$client.GetAsync($uri,[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
  if([int]$response.StatusCode -ne 200){throw "图标下载失败，HTTP $([int]$response.StatusCode)"}
  $stream=$response.Content.ReadAsStreamAsync().GetAwaiter().GetResult();$buffer=New-Object byte[] 8192
  while(($count=$stream.Read($buffer,0,$buffer.Length)) -gt 0){if($memory.Length+$count -gt 8MB){throw '图标超过8MB'};$memory.Write($buffer,0,$count)}
  $memory.Position=0;$source=[Drawing.Image]::FromStream($memory)
  if([long]$source.Width*$source.Height -gt 32000000){throw '图标超过3200万像素，请压缩后上传'}
  $bitmap=[Drawing.Bitmap]::new(512,512);$graphics=[Drawing.Graphics]::FromImage($bitmap);$graphics.Clear([Drawing.Color]::Transparent)
  $graphics.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $scale=[Math]::Min(512.0/$source.Width,512.0/$source.Height);$width=[int]($source.Width*$scale);$height=[int]($source.Height*$scale)
  $graphics.DrawImage($source,[int]((512-$width)/2),[int]((512-$height)/2),$width,$height)
  $folder=Join-Path $Work 'app\src\main\res\drawable-nodpi';New-Item -ItemType Directory -Force -Path $folder | Out-Null
  $bitmap.Save((Join-Path $folder 'terminal_brand_icon.png'),[Drawing.Imaging.ImageFormat]::Png)
  [void]$xml.manifest.application.SetAttribute('icon',$android,'@drawable/terminal_brand_icon')
  [void]$xml.manifest.application.SetAttribute('roundIcon',$android,'@drawable/terminal_brand_icon')
 } finally {foreach($resource in @($graphics,$bitmap,$source,$stream,$memory,$response,$client,$handler)){if($null -ne $resource){$resource.Dispose()}}}
}
$xml.Save($manifest)
Write-Host "桌面品牌配置已应用：$name；配置版本 $($config.revision)。包名与签名保持原构建配置。"
