param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$Work)
$ErrorActionPreference='Stop'
$configPath=Join-Path $Root 'app-branding.json'
# Missing/empty configuration means restore defaults, including a previously
# generated icon. Never retain a prior release's branding in incremental builds.
$config = $null
if(Test-Path -LiteralPath $configPath) {
 $raw = Get-Content -LiteralPath $configPath -Raw
 if(-not [string]::IsNullOrWhiteSpace($raw)) {
  $config = $raw | ConvertFrom-Json
  if($null -ne $config -and $config -isnot [PSCustomObject]) { throw 'app-branding.json 必须为 JSON 对象。' }
 }
}
$name=[string]$config.appName
if([string]::IsNullOrWhiteSpace($name)) { $name='一卡通核验端' }
if([string]::IsNullOrWhiteSpace($name) -or $name.Length -gt 30 -or $name -match '[\p{Cc}@?]'){throw 'APP名称必须为1至30字，不能含控制符、@或?'}
$manifest=Join-Path $Work 'app\src\main\AndroidManifest.xml'
$xml=New-Object System.Xml.XmlDocument
$xml.Load($manifest)
$android='http://schemas.android.com/apk/res/android'
# Branding is owned by the main application, not by vendor library manifests.
# Merge only these attribute markers; preserve unrelated tools:replace entries.
function Save-BrandManifest {
 $toolsNamespace='http://schemas.android.com/tools'
 $namespace=$xml.CreateAttribute('xmlns','tools','http://www.w3.org/2000/xmlns/')
 $namespace.Value=$toolsNamespace
 [void]$xml.manifest.Attributes.SetNamedItem($namespace)
 $application=$xml.manifest.application
 $replacements=New-Object 'System.Collections.Generic.List[string]'
 foreach($entry in ($application.GetAttribute('replace',$toolsNamespace) -split ',')) {
  $entry=$entry.Trim()
  if($entry -and -not $replacements.Contains($entry)) { [void]$replacements.Add($entry) }
 }
 foreach($attribute in @('label','icon','roundIcon')) {
  $entry='android:'+$attribute
  if($application.HasAttribute($attribute,$android)) {
   if(-not $replacements.Contains($entry)) { [void]$replacements.Add($entry) }
  } else {
   # tools:replace requires an actual higher-priority value. Restoring defaults
   # can remove roundIcon, so its old replacement marker must also be removed.
   [void]$replacements.Remove($entry)
  }
 }
 $replace=$xml.CreateAttribute('tools','replace',$toolsNamespace)
 $replace.Value=$replacements -join ','
 [void]$application.Attributes.SetNamedItem($replace)
 $xml.Save($manifest)
}
[void]$xml.manifest.application.SetAttribute('label',$android,$name)
foreach($activity in @($xml.manifest.application.SelectNodes('activity'))){
 if($activity.GetAttribute('name',$android) -eq 'com.lingluren.yikatong.terminal.TerminalEntryActivity'){[void]$activity.SetAttribute('label',$android,$name)}
}
$icon=([string]$config.iconUrl).Trim()
$generatedIcon=Join-Path $Work 'app\src\main\res\drawable-nodpi\terminal_brand_icon.png'
# A finite cleanup allowlist: only this script's generated icon may be removed.
# Vendor resources, package name, signing files and authorization stay untouched.
$workFull=[IO.Path]::GetFullPath($Work).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
if(((Get-Item -LiteralPath $workFull -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Demo 工作目录不能为符号链接或目录联接。' }
if((Test-Path -LiteralPath $generatedIcon) -and -not (Test-Path -LiteralPath $generatedIcon -PathType Leaf)) { throw '生成图标路径被目录占用，未删除或覆盖该目录。' }
$cursor=[IO.Path]::GetFullPath($generatedIcon)
while($cursor -and $cursor -ne $workFull) {
 if(Test-Path -LiteralPath $cursor) {
  if(((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw '品牌生成资源路径不能包含符号链接或目录联接。' }
 }
 $cursor=Split-Path $cursor -Parent
}
if($cursor -ne $workFull) { throw '品牌资源路径不在 Demo 工作目录内。' }
if(-not $icon) {
 $originalManifest=Join-Path $Root 'demo-local\app\src\main\AndroidManifest.xml'
 if(-not (Test-Path -LiteralPath $originalManifest)) { throw '恢复默认图标需要完整 demo-local 原始清单；未删除任何资源，请先恢复原始 Demo。' }
 $original=New-Object System.Xml.XmlDocument
 $original.Load($originalManifest)
 foreach($attribute in @('icon','roundIcon')) {
  $value=$original.manifest.application.GetAttribute($attribute,$android)
  if($value -eq '@drawable/terminal_brand_icon') { throw 'demo-local 包含增量生成图标，无法作为默认图标来源；请恢复原始 Demo。' }
  if($value) { [void]$xml.manifest.application.SetAttribute($attribute,$android,$value) }
  else { [void]$xml.manifest.application.RemoveAttribute($attribute,$android) }
 }
 # Save the restored references first; a failure never leaves a manifest
 # referencing a deleted icon. A leftover unreferenced file can be retried.
 Save-BrandManifest
 if(Test-Path -LiteralPath $generatedIcon) { Remove-Item -LiteralPath $generatedIcon -Force }
 Write-Host "桌面品牌已恢复默认图标，应用名称：$name。"
 return
}
if($icon){
 $uri=[Uri]$icon
 if(-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Fragment){throw 'APP图标必须为HTTPS地址，不能包含账号或片段'}
 Add-Type -AssemblyName System.Net.Http
 Add-Type -AssemblyName System.Drawing
 $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false
 $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(30)
 $response=$null;$stream=$null;$memory=[IO.MemoryStream]::new();$source=$null;$bitmap=$null;$graphics=$null;$temporaryIcon=$null
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
  $temporaryIcon=Join-Path $folder ('terminal_brand_icon.'+[Guid]::NewGuid().ToString('N')+'.tmp')
  $bitmap.Save($temporaryIcon,[Drawing.Imaging.ImageFormat]::Png)
  Move-Item -LiteralPath $temporaryIcon -Destination $generatedIcon -Force
  $temporaryIcon=$null
  [void]$xml.manifest.application.SetAttribute('icon',$android,'@drawable/terminal_brand_icon')
  [void]$xml.manifest.application.SetAttribute('roundIcon',$android,'@drawable/terminal_brand_icon')
 } finally {
  foreach($resource in @($graphics,$bitmap,$source,$stream,$memory,$response,$client,$handler)){if($null -ne $resource){$resource.Dispose()}}
  if($temporaryIcon -and (Test-Path -LiteralPath $temporaryIcon)) { Remove-Item -LiteralPath $temporaryIcon -Force }
 }
}
Save-BrandManifest
Write-Host "桌面品牌配置已应用：$name；配置版本 $($config.revision)。包名与签名保持原构建配置。"
