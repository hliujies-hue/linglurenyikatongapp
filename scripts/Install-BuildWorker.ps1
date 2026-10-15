param([Parameter(Mandatory=$true)][string]$ConfigPath,[Parameter(Mandatory=$true)][string]$PythonPath)
$ErrorActionPreference='Stop'
$ConfigPath=(Resolve-Path -LiteralPath $ConfigPath).Path
$PythonPath=(Resolve-Path -LiteralPath $PythonPath).Path
$config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
if($config.enabled -ne $true) { throw 'Worker configuration is disabled; finish setup before scheduling' }
$worker=Join-Path ([string]$config.checkout) 'scripts\build-worker.py'
if(-not (Test-Path -LiteralPath $worker -PathType Leaf)) { throw 'Worker source missing' }
if($ConfigPath.Contains('"') -or $worker.Contains('"')) { throw 'Invalid path' }
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$action=New-ScheduledTaskAction -Execute $PythonPath -Argument ('"'+$worker+'" --config "'+$ConfigPath+'" --run')
$trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 115) -StartWhenAvailable
$principal=New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
# Intentionally no -Force: an existing task must be reviewed, not overwritten.
Register-ScheduledTask -TaskName 'Yikatong-AppBuildWorker' -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Write-Host 'Worker scheduled every minute while this Windows user is logged in. No administrator privileges requested.'
