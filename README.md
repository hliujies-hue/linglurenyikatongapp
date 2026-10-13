# 一卡通景区刷脸核验端

基于已提供的 FaceSDKAndroid(2).zip 完整 Demo 二次开发，包名 `com.lingluren.yikatong.terminal`，版本 `0.3.1-presence`（104）。实际 APP 仓库为 `hliujies-hue/linglurenyikatongapp`。

`demo-local` 是原版，`demo-work` 是本地工作副本；`overlays` 保存维护代码。已有工作副本直接拉取并执行 `scripts/Build-Demo.ps1`，不要重新导入或清除签名、授权、应用数据。构建时自动应用覆盖文件。

本轮实现设备激活后的子账号登录、V2 自动登记、真实 SDK 图片转特征与本地检索、增量增删人脸、活体核验、平台业务判定、SQLite 事件和回执、断网失败补传、同步完成后退出，以及日期筛选的景区共享人数。硬件不固定绑定账号，子账号归属景区主账号，单点规则取子账号快照。

配套平台使用 `hliujies-hue/linglurenyikatong` 的 `codex/terminal-session-v2-20261013` 分支。本轮配套平台提交：`25972f1bdf0002d76cd2301a205b21e71e4e6d22`。先更新平台，再构建和覆盖安装 APP。Git 使用 SSH，不自动运行 GitHub Actions。

已完成 Java 编译检查（Android 29 与所提供百度 SDK 的实际类型）、HTTPS 目标校验及连续人脸去重测试。尚未在本执行环境完成完整 Gradle APK 构建、签名校验和真机业务验收；不能把源代码编译检查当成 APK 已安装或已通过验票验收。

Windows 已连接真机时：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Build-Demo.ps1
if ($LASTEXITCODE -ne 0) { throw 'APP 构建失败，停止安装' }
powershell -ExecutionPolicy Bypass -File .\scripts\Test-Device.ps1 -Install
if ($LASTEXITCODE -ne 0) { throw '真机检查失败' }
```

`-VerifyJournal` 在独立测试数据库验证重启恢复、回执匹配、退出屏障和七天清理，不改当前账号的记录库。随后按 `docs/BINDING-TEST.md` 完成真人识别、活体、断网与换号验收。原 SDK、签名与授权文件不提交到仓库。

2026-10-13 通信修复：APP启动或保存HTTPS地址后独立上报设备通信，每15秒续报，无需先登录或完成SDK激活；原会话补传规则不变。后台展示登记授权码、APP上报授权码、SDK版本与授权结果。APP每页展示通信状态；只有收到平台响应才显示在线。手机进程停止/网络断开后，后台90秒超时显示离线。通信凭据持久保存，不随退出账号清空。

日常安装使用 `Test-Device.ps1 -Install`。`-VerifyJournal` 是可选的测试APK检查；手机需要允许该测试APK安装。用户提供的上轮日志显示正式APK已安装成功，测试APK被手机以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，不能据此认定业务测试通过。即使可选测试失败，脚本也先启动正式APP，再返回测试失败。
