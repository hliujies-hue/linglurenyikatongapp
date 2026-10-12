# 本机构建和真机检查

先部署匹配的平台代码，再在后台添加新核验端并创建绑定工作人员账号。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Demo.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Device.ps1 -Install
```

多手机连接时给Test-Device.ps1添加-Serial。脚本保留原签名和应用数据，不在Git保存密钥、签名密码、真实授权码或登录密码。

现场确认：SDK真实激活成功；账号验证并绑定成功；平台列表已绑定且出现最近通信；退出登录不再发心跳；错误授权码/未绑定账号/已停用账号应拒绝；平台重置后旧代次失效。

上述现场检查目前未执行，不是已通过记录。APK完整构建及签名验收仍待Windows本机执行。
