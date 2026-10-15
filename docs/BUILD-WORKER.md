# 专用 Windows 自动构建执行器

2026-10-15；此批只交付代码，未安装执行器、未打包、未测试、未迁移签名。

## 一次性接入

使用独立Windows用户和专用源码副本，不能让日常手工打包与执行器共享demo-work。保留完整许可Demo、既有signing.properties及其指向的原正式密钥；不要生成新签名。Python 3.9+、JDK、Android SDK沿用现有Build-Demo环境。默认SDK路径仍是该Windows用户的LOCALAPPDATA/Android/Sdk。

源码必须为本仓库固定SSH地址，干净工作区，HEAD与平台任务及本机配置中的sourceCommit完全一致。执行器不自动拉取或执行任意远端代码；换源代码版本时先停用任务，更新专用副本，再调整平台与本机可信提交。只改名称/图标时不需要人工放品牌文件或执行打包命令。

首次在Git目录外建立例如 `C:\ProgramData\YikatongBuild\state`。把scripts/worker-config.example.json复制为 `C:\ProgramData\YikatongBuild\config.json`，补齐可信提交、原签名证书SHA256、API/上传/下载地址和图标主机白名单。平台凭据和上传凭据是两个不同的随机值，分别放到配置指定文件；与服务器对应值一致。示例没有真实凭据，enabled默认false。

配置目录、state、签名目录必须仅构建账号和SYSTEM可读写；不要对普通Users或Everyone开放。可用以下PowerShell设置配置目录权限（先确认该路径专用于本执行器）：

```powershell
$workerDir='C:\ProgramData\YikatongBuild'
New-Item -ItemType Directory -Force -Path (Join-Path $workerDir 'state') | Out-Null
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $workerDir /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F'
if($LASTEXITCODE -ne 0) { throw '无法保护构建凭据目录' }
```

所有存储与平台配置就绪后才将enabled改为true，并调用 `scripts/Install-BuildWorker.ps1 -ConfigPath <实际配置路径> -PythonPath <实际python.exe路径>`。脚本注册一分钟一次任务，不覆盖已有同名任务；仅当前用户登录时运行，电脑关机或用户退出后不会构建。需要无人登录持续运行时，另配置专用Windows服务账号和任务登录凭据，不在Git或后台填写密码。暂停：`Disable-ScheduledTask -TaskName Yikatong-AppBuildWorker`。

## 执行行为

- 每次调用只处理一个任务，Windows文件锁防止并发。整个执行器及子进程归入kill-on-close Job Object，进程退出/超时/被终止时清理其Gradle后代；不能建立作业约束则不开始。
- 任务与租约先持久化到受保护state；网络回报失败保留待回执文件。下次调度先查询任务状态；取消/过期/已发布任务结束，不再上传。领取响应丢失仍只能等待平台2小时超时，不会冒领同一任务。
- 图标仅从白名单HTTPS主机下载，不跟随重定向、不使用全局代理；最多8MB。首次领取后固化字节和SHA256，打包脚本只读该快照。快照时间是领取后，不保证等于创建任务瞬间URL的内容；媒体地址应保持不可变。
- 自动构建拒绝创建新签名；检查源提交和工作区，任务版本同时写入实际Gradle和TerminalProtocol上报常量。普通手工构建默认版本不变。
- 使用Build-Demo -DeferTests打包；执行原签名、包名、版本、权限、不可调试检查。明确不运行Lint/业务/压测，APK构建成功也不等于验收。
- 独立任务目录保存APK及校验回执；上传不可变路径，再下载公开HTTPS文件核对SHA256。平台同时校对服务器落盘文件摘要；只有全部完成才回报READY，管理员仍需确认发布。
- 构建90分钟超时强制结束整个执行器；下次调度将中断任务报失败，避免复用不完整APK。脚本异常、签名不符、容量不足、上传失败都保留旧发布。原始构建输出只在受保护本地build.log，不上报平台。
- 默认构建机至少10GB空闲（不可低于5GB），state达到20GB停止新任务；不自动删除历史安装包、日志或签名。需要归档清理已结束任务，绝不能删除active.json对应目录。

## 尚待现场验证

未运行Python/PowerShell执行器、任务计划、Windows Job Object、SDK构建、签名或网络传输。需实际验证任务取消、断电、超时杀进程、换版本、图标、签名不符、磁盘阈值、断网回报重试与公开下载摘要。APK内下载校验/安装流程属于下一批；本执行器不控制手机安装。
