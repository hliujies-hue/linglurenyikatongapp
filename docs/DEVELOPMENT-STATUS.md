# 当前开发状态

直接二开完整 FaceSDKAndroid Demo，保留相机、授权、识别、注册、人员库模块。

已编写：简洁授权码激活页，调用 Demo BdFaceAuth 在线验证及缓存；激活成功后景区登录界面；Demo 工作副本覆盖更新脚本；独立正式签名及构建脚本。移除原 StartActivity 的 launcher，旧 app 维护页面不再公开导出。

未完成：景区账号登录 API 接通、手机设备绑定、会员图片同步、扫码/刷脸平台规则核验、语音码表、人数查询、7天日志回传、后台配置。登录按钮目前显式提示未接通，不返回虚假成功。

本环境已完成 Java 语法检查及 Demo 授权接口核对；无 Android SDK，完整 APK 编译、lint、签名检查待 Windows 执行。未完成真实 SDK 激活和真机测试；不能用于营业。

执行 scripts/Build-Demo.ps1，自动应用受维护 overlays，复用签名，不重新复制 Demo。不运行 Prepare-Demo.ps1 覆盖已存在工作副本。不卸载手机旧应用，不上传签名或授权文件。APK 仅用于验证阶段；平台未变更，暂不提供生产更新命令。
