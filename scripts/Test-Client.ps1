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
 $schedule = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalSyncSchedule.java'
 $scheduleTest = Join-Path $root 'tests\TerminalSyncScheduleTest.java'
 $decision = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalDecisionText.java'
 $decisionTest = Join-Path $root 'tests\TerminalDecisionTextTest.java'
 $resultType = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalResultType.java'
 $resultTest = Join-Path $root 'tests\java\TerminalResultTypeTest.java'
 $ledger = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalIndexLedger.java'
 $ledgerTest = Join-Path $root 'tests\java\TerminalIndexLedgerTest.java'
 & javac -encoding UTF-8 -d $out $policy $test $gate $gateTest $drain $drainTest $sdk $sdkTest $schedule $scheduleTest $decision $decisionTest $resultType $resultTest $ledger $ledgerTest
 if($LASTEXITCODE -ne 0) { throw 'Client security test compilation failed' }
 & java -cp $out TerminalEndpointPolicyTest
 if($LASTEXITCODE -ne 0) { throw 'Client security tests failed' }
 & java -cp $out TerminalAttemptGateTest
 if($LASTEXITCODE -ne 0) { throw 'Face attempt deduplication tests failed' }
 & java -cp $out TerminalDrainCoordinatorTest
 if($LASTEXITCODE -ne 0) { throw 'Session drain regression tests failed' }
 & java -cp $out TerminalSdkContractTest
 if($LASTEXITCODE -ne 0) { throw 'SDK contract and readiness tests failed' }
 & java -cp $out TerminalSyncScheduleTest
 if($LASTEXITCODE -ne 0) { throw 'Sync scheduling tests failed' }
 & java -cp $out TerminalDecisionTextTest
 if($LASTEXITCODE -ne 0) { throw 'Decision feedback tests failed' }
 & java -cp $out TerminalResultTypeTest
 if($LASTEXITCODE -ne 0) { throw 'Result category tests failed' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalIndexLedgerTest
 if($LASTEXITCODE -ne 0) { throw 'Native feature replay tests failed' }
} finally { Remove-Item -LiteralPath $out -Recurse -Force }

