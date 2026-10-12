$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path ([IO.Path]::GetTempPath()) ('ykt-client-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
try {
 $policy = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalEndpointPolicy.java'
 $test = Join-Path $root 'tests\TerminalEndpointPolicyTest.java'
 & javac -encoding UTF-8 -d $out $policy $test
 if($LASTEXITCODE -ne 0) { throw 'Client security test compilation failed' }
 & java -cp $out TerminalEndpointPolicyTest
 if($LASTEXITCODE -ne 0) { throw 'Client security tests failed' }
} finally { Remove-Item -LiteralPath $out -Recurse -Force }
