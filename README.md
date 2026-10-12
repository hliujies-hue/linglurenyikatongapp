# 一卡通景区刷脸核验端

基于 FaceSDKAndroid(2).zip 完整 Demo 二次开发，包名 com.lingluren.yikatong.terminal。

原版导入 demo-local；工作副本 demo-work 保留原版模块，通过 overlays 与 scripts/Apply-Demo.ps1 应用修改。已准备工作副本后，运行 scripts/Build-Demo.ps1 构建正式签名 APK；不要重复导入或删除签名、授权、应用数据。

目前已实现 SDK 在线激活入口与账号密码登录页面。APP 不显示滑动验证码，使用 POST /api/terminal/login。配套平台实现位于 hliujies-hue/linglurenyikatong 的 feat/terminal-password-login 分支，尚未编译验证、合并或部署。平台未更新前不能完成登录联调。浏览器登录接口仍保留现有验证码流程。

设备绑定、人员增量同步、刷脸业务核验、人数查询尚未完成，当前版本不能用于通行。

Demo、SDK、授权与签名文件留在本地，不上传到公开仓库。需求见 docs/REQUIREMENTS.md。
