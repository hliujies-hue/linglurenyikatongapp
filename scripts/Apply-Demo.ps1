$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root 'demo-work'
if(-not (Test-Path (Join-Path $work 'app\build.gradle'))) { throw 'Prepare demo-work first. Do not reimport over your working copy.' }
$gradle = [IO.File]::ReadAllText((Join-Path $work 'app\build.gradle'))
if($gradle -notmatch 'com\.lingluren\.yikatong\.terminal') { throw 'Application ID mismatch' }
$overlays = Join-Path $root 'overlays'
# Only replace maintained overlay files. Preserve the remainder of the complete Demo.
Get-ChildItem -LiteralPath $overlays -File -Recurse | ForEach-Object {
 $relative = $_.FullName.Substring($overlays.Length + 1)
 $target = Join-Path $work $relative
 New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
 Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
$path = Join-Path $work 'app\src\main\AndroidManifest.xml'
$xml = New-Object System.Xml.XmlDocument
$xml.Load($path)
$android = 'http://schemas.android.com/apk/res/android'
$app = $xml.manifest.application
# Keep the SDK's fingerprint component, but remove public entry into Demo maintenance screens.
foreach($activity in @($app.SelectNodes('activity'))) {
 $name = $activity.GetAttribute('name',$android)
 if($name -eq 'com.baidu.liantian.LiantianActivity') { continue }
 # Original app has a single launcher on StartActivity; remove all filters from that activity.
 if($name -eq 'com.baidu.idl.face.main.activity.start.StartActivity') {
  foreach($filter in @($activity.SelectNodes('intent-filter'))) { [void]$activity.RemoveChild($filter) }
 }
 [void]$activity.SetAttribute('exported',$android,'false')
}
$entry = $app.SelectSingleNode("activity[@name='unused']")
foreach($node in @($app.SelectNodes('activity'))) {
 if($node.GetAttribute('name',$android) -eq 'com.lingluren.yikatong.terminal.TerminalEntryActivity') { $entry=$node;break }
}
if($null -eq $entry) {
 $entry = $xml.CreateElement('activity')
 [void]$entry.SetAttribute('name',$android,'com.lingluren.yikatong.terminal.TerminalEntryActivity')
 [void]$entry.SetAttribute('exported',$android,'true')
 [void]$entry.SetAttribute('theme',$android,'@style/Theme_NoTitle')
 $filter = $xml.CreateElement('intent-filter')
 $action = $xml.CreateElement('action'); [void]$action.SetAttribute('name',$android,'android.intent.action.MAIN')
 $category = $xml.CreateElement('category'); [void]$category.SetAttribute('name',$android,'android.intent.category.LAUNCHER')
 [void]$filter.AppendChild($action); [void]$filter.AppendChild($category); [void]$entry.AppendChild($filter); [void]$app.AppendChild($entry)
}
[void]$entry.SetAttribute('exported',$android,'true')
$xml.Save($path)
Write-Host 'Demo overlay applied: real online activation, guarded terminal entry, private Demo screens.'
Write-Host 'Password-only login UI is implemented. Backend terminal login deployment and admission integration are pending.'
