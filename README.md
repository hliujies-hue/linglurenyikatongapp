# 一卡通景区刷脸核验端

包名：`com.lingluren.yikatong.terminal`。基于用户提供的百度 Android 离线 SDK 接口开发。

## 当前状态（0.1.0-dev）

已编写：原生激活/登录/人数查询界面、相机算法检查、SDK 真实接口适配、加密特征与事件缓存、分页同步客户端、自动回传客户端、正式签名及 Windows 构建脚本。

**未完成：平台 `/api/terminal/v1/*` 接口实现及部署、后台配置页面、完整 Android 构建、真机激活/相机/活体联调、30 万底库性能验收。** 现有平台旧硬件接口不能替代新接口。

本版本是开发验证版本，`BuildConfig.ADMISSION_ENABLED=false`，不能用于营业通行。即使登录接口返回成功，也不能进入正式扫脸通行。可验证安装、激活、模型初始化和相机算法。不得在未完成平台联调前手动打开开关。

## Windows 本机构建

使用 Windows PowerShell，在已经配置 GitHub SSH 的电脑执行：

```powershell
$ErrorActionPreference = 'Stop'
Set-Location 'C:\Users\Administrator\Desktop\app'
git clone git@github.com:hliujies-hue/linglurenyikatongapp.git .
if ($LASTEXITCODE -ne 0) { throw 'Clone failed' }
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Import-Sdk.ps1
if ($LASTEXITCODE -ne 0) { throw 'SDK import failed' }
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Release.ps1
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
```

选择本次提供的 `sdk(4).zip`。首次需要联网下载 Gradle 8.9 和 AGP 8.7.3。Android Studio SDK Manager 安装 Android SDK Platform 35、Build Tools 35.0.0、Platform-Tools；JDK 使用 Android Studio 自带 JBR。

构建成功后：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Install.ps1
```

设备打开 USB 调试并允许电脑连接。不自动卸载旧同包名 APP，避免删除授权缓存、底库与待上传数据。若此前已安装其他签名的同包名 APP，先由平台确认数据保护与签名迁移方案。

输出：`app/build/outputs/apk/release/app-release.apk`。正式签名密钥首次生成在用户目录 `.yikatong-signing`，以后持续复用，需要自行安全备份。密钥、密码和签名配置不要发到聊天或提交 Git。

## SDK 文件

公开仓库不包含 SDK AAR、模型、Demo 签名、授权码、会员信息。导入脚本验证 AAR SHA-256：

`fc21b97152a37a9ffcd3329f4f94e561463d0e66f2ca6c30d31c363472cd651d`

该文件名标注 8.5（20251124），上传文档标注 8.6；不能把文档版本当作实际运行版本。模型/库升级要重新核对兼容性。

## 验证边界

- 纯 Java 核心测试在开发环境运行通过（记录保护、过期响应、状态文本）。
- AAR API 签名由实际 class 文件读取核对。
- 无 Android SDK/手机连接的开发环境不能证明 APK 构建或运行成功。
- 平台变更未提交，当前不需要宝塔更新命令，不应重启生产服务。

开发约定见 `docs/REQUIREMENTS.md`、`docs/API-CONTRACT.md`、`docs/ACCEPTANCE.md`。
