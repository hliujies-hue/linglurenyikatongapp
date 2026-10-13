$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path ([IO.Path]::GetTempPath()) ('ykt-client-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
try {
 $policy = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalEndpointPolicy.java'
 $gate = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalAttemptGate.java'
 $gateTest = Join-Path $root 'tests\TerminalAttemptGateTest.java'
 $test = Join-Path $root 'tests\TerminalEndpointPolicyTest.java'
 $drain = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalDrainCoordinator.java'
 $drainTest = Join-Path $root 'tests\TerminalDrainCoordinatorTest.java'
 $sdk = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalSdkContract.java'
 $sdkTest = Join-Path $root 'tests\TerminalSdkContractTest.java'
 & javac -encoding UTF-8 -d $out $policy $test $gate $gateTest $drain $drainTest $sdk $sdkTest
 if($LASTEXITCODE -ne 0) { throw 'Client security test compilation failed' }
 & java -cp $out TerminalEndpointPolicyTest
 if($LASTEXITCODE -ne 0) { throw 'Client security tests failed' }
 & java -cp $out TerminalAttemptGateTest
 if($LASTEXITCODE -ne 0) { throw 'Face attempt deduplication tests failed' }
 & java -cp $out TerminalDrainCoordinatorTest
 if($LASTEXITCODE -ne 0) { throw 'Session drain regression tests failed' }
 & java -cp $out TerminalSdkContractTest
 if($LASTEXITCODE -ne 0) { throw 'SDK contract and readiness tests failed' }
} finally { Remove-Item -LiteralPath $out -Recurse -Force }

