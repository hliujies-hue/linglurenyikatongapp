param([Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$actual = & git -C $root rev-parse HEAD
if($LASTEXITCODE -ne 0 -or ($actual | Out-String).Trim() -cne $Revision) { throw '源码版本不匹配，停止构建。' }
& git -C $root diff --quiet HEAD --
if($LASTEXITCODE -ne 0) { throw '存在已跟踪源码修改，请先核对；不覆盖本地修改。' }
$gradle = Get-Content -LiteralPath (Join-Path $root 'overlays/app/build.gradle') -Raw
$protocol = Get-Content -LiteralPath (Join-Path $root 'overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalProtocol.java') -Raw
if($gradle -notmatch 'versionCode\s+147\b' -or $gradle -notmatch 'versionName\s+"0\.7\.28-ui-recovery"' -or $protocol -notmatch 'VERSION="0\.7\.28-ui-recovery"') { throw 'APP版本与协议诊断版本不一致。' }
if(-not (Test-Path -LiteralPath (Join-Path $root 'signing.properties') -PathType Leaf)) { throw '缺少原签名配置；请恢复原文件，禁止重新生成签名。' }
if(-not (Test-Path -LiteralPath (Join-Path $root 'demo-work/gradlew.bat') -PathType Leaf)) { throw '缺少已有厂商Demo构建目录。' }
Write-Host "源码核对通过：$Revision / 0.7.28-ui-recovery / versionCode 147"
Write-Host '此结果仅核对源码与构建前提，不代表APK构建或真机验收通过。'
