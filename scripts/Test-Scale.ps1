param([string]$ReportDirectory = (Join-Path $env:USERPROFILE 'Desktop\Yikatong-Test-Reports'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path ([IO.Path]::GetTempPath()) ('ykt-scale-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out,$ReportDirectory | Out-Null
try {
 $source = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal'
 & javac -encoding UTF-8 -d $out (Join-Path $source 'TerminalIndexLedger.java') (Join-Path $source 'TerminalSdkContract.java') (Join-Path $root 'tests\java\TerminalIndexScaleTest.java')
 if($LASTEXITCODE -ne 0) { throw '规模回归编译失败' }
 $report = Join-Path $ReportDirectory ('index-200k-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
 & java -Xmx512m -cp $out com.lingluren.yikatong.terminal.TerminalIndexScaleTest 200000 | Tee-Object -FilePath $report
 if($LASTEXITCODE -ne 0) { throw '20万条主机索引回归失败' }
 Write-Host "报告：$report"
 Write-Host '仅验证主机索引管理逻辑。未运行手机、相机、照片提特征或原生算法检索性能测试。'
} finally { Remove-Item -LiteralPath $out -Recurse -Force }
