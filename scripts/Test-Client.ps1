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
 $backup = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalBackupCrypto.java'
 $messages = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalMessages.java'
 $backupTest = Join-Path $root 'tests\java\TerminalBackupCryptoTest.java'
 $restore = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalIndexRestore.java'
 $restoreTest = Join-Path $root 'tests\java\TerminalIndexRestoreTest.java'
 $activation = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalActivationGate.java'
 $activationTest = Join-Path $root 'tests\java\TerminalActivationGateTest.java'
 $envelope = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalStorageEnvelope.java'
 $envelopeTest = Join-Path $root 'tests\java\TerminalStorageEnvelopeTest.java'
 $algorithmQueue = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalAlgorithmQueue.java'
 $algorithmQueueTest = Join-Path $root 'tests\java\TerminalAlgorithmQueueTest.java'
 $appPolicy = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalAppPolicy.java'
 $appPolicyTest = Join-Path $root 'tests\java\TerminalAppPolicyTest.java'
 $range = Join-Path $root 'overlays\app\src\main\java\com\lingluren\yikatong\terminal\TerminalDownloadRange.java'
 $rangeTest = Join-Path $root 'tests\TerminalDownloadRangeTest.java'
 & javac -encoding UTF-8 -d $out $policy $test $gate $gateTest $drain $drainTest $sdk $sdkTest $schedule $scheduleTest $decision $decisionTest $resultType $resultTest $ledger $ledgerTest $backup $messages $backupTest $restore $restoreTest $activation $activationTest $envelope $envelopeTest $algorithmQueue $algorithmQueueTest $appPolicy $appPolicyTest $range $rangeTest
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
 & java -cp $out com.lingluren.yikatong.terminal.TerminalBackupCryptoTest
 if($LASTEXITCODE -ne 0) { throw 'Encrypted record backup tests failed' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalIndexRestoreTest
 if($LASTEXITCODE -ne 0) { throw 'Batched feature restore tests failed' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalActivationGateTest
 if($LASTEXITCODE -ne 0) { throw 'Activation single-flight regression tests failed' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalStorageEnvelopeTest
 if($LASTEXITCODE -ne 0) { throw '本机加密防篡改测试失败' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalAlgorithmQueueTest
 if($LASTEXITCODE -ne 0) { throw '算法队列隔离或容量测试失败' }
 & java -cp $out TerminalAppPolicyTest
 if($LASTEXITCODE -ne 0) { throw 'APP版本限制测试失败' }
 & java -cp $out com.lingluren.yikatong.terminal.TerminalDownloadRangeTest
 if($LASTEXITCODE -ne 0) { throw 'APK resume range tests failed' }
} finally { Remove-Item -LiteralPath $out -Recurse -Force }

