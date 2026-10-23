$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root 'demo-work'
if(-not (Test-Path (Join-Path $work 'app\build.gradle'))) { throw 'Prepare demo-work first. Do not reimport over your working copy.' }
$gradle = [IO.File]::ReadAllText((Join-Path $work 'app\build.gradle'))
if($gradle -notmatch 'com\.lingluren\.yikatong\.terminal') { throw 'Application ID mismatch' }
$settingsGradlePath = Join-Path $work 'settinglibrary\build.gradle'
if(-not (Test-Path -LiteralPath $settingsGradlePath -PathType Leaf)) { throw 'settinglibrary/build.gradle 缺失；请恢复完整 Demo，不能在缺少原厂模块配置时继续构建。' }
# Restore the vendor's complete parameter UI from the untouched local Demo.
$originalSettings = Join-Path $root 'demo-local\settinglibrary\src\main'
if(Test-Path $originalSettings) {
 $settingsTarget = Join-Path $work 'settinglibrary\src\main'
 New-Item -ItemType Directory -Force -Path $settingsTarget | Out-Null
 Copy-Item -Path (Join-Path $originalSettings '*') -Destination $settingsTarget -Recurse -Force
}
$overlays = Join-Path $root 'overlays'
# Only replace maintained overlay files. Preserve the remainder of the complete Demo.
Get-ChildItem -LiteralPath $overlays -File -Recurse | ForEach-Object {
 $relative = $_.FullName.Substring($overlays.Length + 1)
 $target = Join-Path $work $relative
 New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
 Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}
# The maintained settings screen uses Java 8 lambdas. Set the module language
# level without rewriting vendor dependencies or any unmarked Gradle content.
$settingsGradle = [IO.File]::ReadAllText($settingsGradlePath)
$java8Begin = '// BEGIN YKT SETTINGLIBRARY JAVA8'
$java8End = '// END YKT SETTINGLIBRARY JAVA8'
$beginMatches = [regex]::Matches($settingsGradle, '(?m)^[ \t]*' + [regex]::Escape($java8Begin) + '[ \t]*\r?$')
$endMatches = [regex]::Matches($settingsGradle, '(?m)^[ \t]*' + [regex]::Escape($java8End) + '[ \t]*\r?$')
if($beginMatches.Count -ne $endMatches.Count -or $beginMatches.Count -gt 1) { throw 'settinglibrary/build.gradle 中 Java 8 维护标记不完整或重复；未覆盖原文件，请检查标记。' }
$newline = if($settingsGradle.Contains("`r`n")) { "`r`n" } else { "`n" }
$java8Block = @'
// BEGIN YKT SETTINGLIBRARY JAVA8
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}
// END YKT SETTINGLIBRARY JAVA8
'@
$java8Block = $java8Block -replace '\r?\n', $newline
if($beginMatches.Count -eq 1) {
 $start = $beginMatches[0].Index
 $end = $endMatches[0].Index + $endMatches[0].Length
 if($endMatches[0].Index -le $start) { throw 'settinglibrary/build.gradle 的 Java 8 维护标记顺序错误；未覆盖原文件。' }
 # Match includes a CR on CRLF files; retain it with the original trailing LF.
 if($end -gt 0 -and $settingsGradle[$end - 1] -eq "`r") { $end-- }
 $settingsGradle = $settingsGradle.Substring(0,$start) + $java8Block + $settingsGradle.Substring($end)
} else {
 if($settingsGradle.Length -gt 0 -and -not $settingsGradle.EndsWith("`n")) { $settingsGradle += $newline }
 $settingsGradle += $java8Block + $newline
}
[IO.File]::WriteAllText($settingsGradlePath,$settingsGradle,(New-Object Text.UTF8Encoding($false)))
Write-Host 'settinglibrary Java 8 compileOptions applied; vendor dependencies retained.'

$path = Join-Path $work 'app\src\main\AndroidManifest.xml'
$xml = New-Object System.Xml.XmlDocument
$xml.Load($path)
$android = 'http://schemas.android.com/apk/res/android'
$app = $xml.manifest.application
# Baidu Android FAQ: READ_PHONE_STATE can prevent fingerprint acquisition on some Android 10+ ROMs.
# Remove the unused phone permission across manifest merging; do not wipe licenses or change SDK fingerprint mode.
$toolsNs = 'http://schemas.android.com/tools'
foreach($permission in @($xml.manifest.SelectNodes('uses-permission'))) {
 if($permission.GetAttribute('name',$android) -eq 'android.permission.READ_PHONE_STATE') { [void]$xml.manifest.RemoveChild($permission) }
}
$removePhone = $xml.CreateElement('uses-permission')
[void]$removePhone.SetAttribute('name',$android,'android.permission.READ_PHONE_STATE')
$removeAttr = $xml.CreateAttribute('tools','node',$toolsNs);$removeAttr.Value='remove'
[void]$removePhone.Attributes.Append($removeAttr)
[void]$xml.manifest.InsertBefore($removePhone,$app)
# Camera choice is runtime-dependent; front/autofocus hardware must not block installation on other terminals.
foreach($feature in @($xml.manifest.SelectNodes('uses-feature'))) {
 if($feature.GetAttribute('name',$android) -like 'android.hardware.camera*') { [void]$feature.SetAttribute('required',$android,'false') }
}
# The vendor fingerprint Activity must remain present; fail build instead of silently shipping incomplete auth.
$lhFound = $false
foreach($screen in @($app.SelectNodes('activity'))) {
 if($screen.GetAttribute('name',$android) -eq 'com.baidu.liantian.LiantianActivity') { $lhFound=$true }
}
if(-not $lhFound) { throw 'Baidu fingerprint Activity missing. Restore the complete original Demo manifest before building.' }
# Android 11+ hides TTS services unless explicitly declared (targetSdk 31).
$queries = $xml.manifest.SelectSingleNode('queries')
if($null -eq $queries) {
 $queries = $xml.CreateElement('queries')
 [void]$xml.manifest.InsertBefore($queries,$app)
}
$hasTtsQuery = $false
foreach($queryAction in @($queries.SelectNodes('intent/action'))) {
 if($queryAction.GetAttribute('name',$android) -eq 'android.intent.action.TTS_SERVICE') { $hasTtsQuery = $true }
}
if(-not $hasTtsQuery) {
 $ttsIntent = $xml.CreateElement('intent')
 $ttsAction = $xml.CreateElement('action')
 [void]$ttsAction.SetAttribute('name',$android,'android.intent.action.TTS_SERVICE')
 [void]$ttsIntent.AppendChild($ttsAction)
 [void]$queries.AppendChild($ttsIntent)
}
[void]$app.SetAttribute('name',$android,'com.lingluren.yikatong.terminal.TerminalApplication')
[void]$app.SetAttribute('allowBackup',$android,'false')
[void]$app.SetAttribute('fullBackupContent',$android,'false')
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
[void]$entry.SetAttribute('theme',$android,'@style/YktTerminalTheme')
[void]$entry.SetAttribute('windowSoftInputMode',$android,'adjustResize')
# Self-update only: private APK cache exposed through a read-granted FileProvider.
$installPermission = $false
foreach($permission in @($xml.manifest.SelectNodes('uses-permission'))) {
 if($permission.GetAttribute('name',$android) -eq 'android.permission.REQUEST_INSTALL_PACKAGES') { $installPermission=$true }
}
if(-not $installPermission) {
 $permission=$xml.CreateElement('uses-permission')
 [void]$permission.SetAttribute('name',$android,'android.permission.REQUEST_INSTALL_PACKAGES')
 [void]$xml.manifest.InsertBefore($permission,$app)
}
$updateProvider=$null
foreach($provider in @($app.SelectNodes('provider'))) {
 if($provider.GetAttribute('authorities',$android) -eq 'com.lingluren.yikatong.terminal.updates') { $updateProvider=$provider;break }
}
if($null -eq $updateProvider) { $updateProvider=$xml.CreateElement('provider');[void]$app.AppendChild($updateProvider) }
[void]$updateProvider.SetAttribute('name',$android,'androidx.core.content.FileProvider')
[void]$updateProvider.SetAttribute('authorities',$android,'com.lingluren.yikatong.terminal.updates')
[void]$updateProvider.SetAttribute('exported',$android,'false')
[void]$updateProvider.SetAttribute('grantUriPermissions',$android,'true')
foreach($metadata in @($updateProvider.SelectNodes('meta-data'))) { [void]$updateProvider.RemoveChild($metadata) }
$metadata=$xml.CreateElement('meta-data')
[void]$metadata.SetAttribute('name',$android,'android.support.FILE_PROVIDER_PATHS')
[void]$metadata.SetAttribute('resource',$android,'@xml/terminal_update_paths')
[void]$updateProvider.AppendChild($metadata)
# Private shell for signed instrumentation only; no launcher and no intent/file enabling switch.
$manual=$null
foreach($screen in @($app.SelectNodes('activity'))) {
 if($screen.GetAttribute('name',$android) -eq 'com.lingluren.yikatong.terminal.TerminalManualTestActivity') { $manual=$screen;break }
}
if($null -eq $manual) { $manual=$xml.CreateElement('activity');[void]$app.AppendChild($manual) }
[void]$manual.SetAttribute('name',$android,'com.lingluren.yikatong.terminal.TerminalManualTestActivity')
[void]$manual.SetAttribute('exported',$android,'false')
[void]$manual.SetAttribute('screenOrientation',$android,'portrait')
[void]$manual.SetAttribute('theme',$android,'@android:style/Theme.Material.NoActionBar')
[void]$manual.SetAttribute('excludeFromRecents',$android,'true')
$xml.Save($path)
Write-Host 'Demo overlay applied: real online activation, guarded terminal entry, private Demo screens.'
Write-Host 'Session V2: SDK face sync, durable events, receipt confirmation and guarded account exit. Validate on the real phone before operational use.'


# Close external entry into ALL original parameter screens after manifest merge.
$settingsManifest = Join-Path $work 'settinglibrary\src\main\AndroidManifest.xml'
$settingsXml = New-Object System.Xml.XmlDocument
$settingsXml.Load($settingsManifest)
foreach($screen in @($settingsXml.manifest.application.SelectNodes('activity'))) {
 [void]$screen.SetAttribute('exported',$android,'false')
}
$settingsXml.Save($settingsManifest)
$requiredScreens = @('SettingActivity','GateMinFaceActivity','GateConfigQualtifyActivity','FaceLivinessTypeActivity','GateFaceDetectActivity','GateLensSettingsActivity','FaceDetectAngleActivity','CameraDisplayAngleActivity','MirrorSettingActivity','PictureOptimizationActivity','LogSettingActivity','VersionMessageActivity')
foreach($screen in $requiredScreens) {
 $source = Join-Path $work ('settinglibrary\src\main\java\com\example\settinglibrary\' + $screen + '.java')
 if(-not (Test-Path $source)) { throw "Original Demo parameter screen missing: $screen. Restore the complete Demo before building." }
}
# Product terminology only. Keep resource identifiers, vendor classes and license checks intact.
$settingsStrings = Join-Path $work 'settinglibrary\src\main\res\values\strings.xml'
$stringsXml = New-Object System.Xml.XmlDocument
$stringsXml.PreserveWhitespace = $true
$stringsXml.Load($settingsStrings)
foreach($label in @($stringsXml.resources.SelectNodes('string'))) {
 if($label.GetAttribute('name') -eq 'footer_baidu_brain_support') { $label.InnerText='河北领路人科技有限公司 提供技术支持' }
 elseif($label.GetAttribute('name') -eq 'label_sdk_version') { $label.InnerText='算法版本' }
}
$stringsXml.Save($settingsStrings)
Write-Host 'Administrator settings: online password gate, private Demo screens, foreground-only 10 minute session.'

# Optional export from the hidden platform APP maintenance page.
& (Join-Path $PSScriptRoot 'Apply-AppBranding.ps1') -Root $root -Work $work

