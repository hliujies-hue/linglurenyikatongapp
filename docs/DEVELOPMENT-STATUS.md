# 当前开发状态 · 设备绑定与通信阶段

实际开发工程为完整百度 Demo 二开。已实现真实在线激活、无滑块账号密码登录、新核验端绑定与30秒心跳；默认平台地址为 https://yktapi.qiliai.com，可在“平台连接设置”修改HTTPS域名及端口并持久化，无需重新编译。修改地址先退出旧平台会话，随后重新登录。登录仅接受后台绑定新核验端的工作人员账号。授权码与手机指纹由平台核对，APP不自行填写景区。

已本地运行：Java语法解析（3个维护类）；TerminalEndpointPolicyTest真实Java编译运行，覆盖HTTPS域名、第三方凭据目标、路径穿越和查询串拒绝。未运行：完整Android编译、release lint、正式签名APK验证、ADB真机及真实平台通信。当前执行环境没有Android SDK和Windows本机通道。

Build-Demo.ps1先运行Test-Client.ps1，再执行release构建、lint及Gradle单元测试任务，验证APK签名和SHA256；保留已有正式密钥，不上传签名配置。Test-Device.ps1可手动执行安装及启动存活测试。应用激活、账号登录和后台心跳需要在手机确认；启动存活不代表刷脸核验验收。

未完成：图片同步、本地特征库接入、撤权删脸、30万性能、正式平台核验、每次记录及7天确认清理、景区人数与后台提示配置。productionReady仍为false，没有现场放行入口。
