# 大库摄像头手动测试

## 已有证据

用户2026-10-20提供2026-10-19完成的报告，原字段转存 `native-scale-107228-20261019.json` 与 `sfhq-part4-107228-manifest.json`。125754张SFHQ原图，拒绝18526，得到107228条SDK特征，精确字节重复0。手机M2011K2C/Android14，加载2692ms，1000次分布抽样同模板查询1000匹配，P50/P95/P99为4.652604/4.986667/7.322083ms，采样PSS峰值217970KiB，清理码0。两份报告SHA256一致。是原生索引自查询通过，不是20万、全库逐条查询、真人准确率、相机或平台端到端通过。

## 本次入口

APP版本137 / 0.7.18-manual-test新增私有TerminalManualTestActivity。没有桌面入口、非exported；只有同进程的签名instrumentation注入Session后才能打开，普通启动立即关闭。算法、载库及人脸测试逻辑仅在androidTest测试APK。没有触发TerminalRuntime、业务Store或平台同步。

首次需要电脑构建并以原签名`adb install -r`覆盖安装APP和测试APK，保留正式数据。Test-ManualCamera.ps1执行构建、签名验证、覆盖安装、准备应用目录、上传已有features.bin（约52MiB）和manifest、启动测试，结束后导出报告并恢复正常APP。不会再次解压125754张图片或调用批量提取。构建使用-DeferTests，不表示Lint/单元测试通过。

在PowerShell7、已连接8c552b70且待确认记录为0的闲置测试手机执行：

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'C:\Users\Administrator\Desktop\app\scripts\Test-ManualCamera.ps1' `
  -Dataset 'D:\SHUJU\sfhq-part4-test\merged\features.bin' `
  -Serial '8c552b70' -AllowPause
```

1. 手机保持解锁、USB连接，等待模型和背景库加载，显示107228条。默认前摄，可切换。
2. 未录入本人时观察结果。合成样本匹配单独提示，不能当作正确身份。
3. 点击“录入/替换本人”，本人同意后等待质量和RGB活体通过。录入特征仅在RAM，ID为背景数+1，不保存照片、不写数据库。
4. 移开再返回、改变正常距离或光照，观察“匹配本次测试者”。注册帧不作为一次识别统计；后续每帧重新提特征查询。
5. 点击移除本人，观察未录入场景；也可由另一位同意的测试者替换后测试。一次最多一名测试者。
6. 点击结束，等待电脑导出report.json并重新打开APP。检查原账号、6人库、待确认数及日常核验。手工测试数据不会出现在正式人员页。

显示纯检索耗时与相机帧回调至结果耗时（含排队、检测、质量、活体、提特征和检索；不含传感器曝光、UI绘制、网络和平台授权）。每250ms最多收一帧，队列最多一帧，不是最大吞吐压测。阈值来自SDK的SingleBaseConfig并记录在报告，未降低质量/活体门槛。没有人工真值标签，因此不计算准确率或误识率；报告SESSION_ENDED仅表示会话正常结束，不自动判验收PASS。

报告在桌面Yikatong-ManualScale本次目录，含查询数量、测试者/合成样本/未匹配数量、质量与活体未通过帧数、查询及帧到结果P50/P95/P99、清理码。拒绝帧不纳入成功执行检索的耗时百分位。未采样到查询就没有耗时百分位。只发report.json和现场观察即可，crash.txt可能包含其他应用记录，不上传Git。

按Home、返回或关闭Activity会结束会话；30分钟、低可用内存或目录STOP在检查点停止，原生调用卡住不能保证即时停止。断线/电脑关闭后请重新连接并force-stop再打开正常APP，临时数据保留但不会启动业务同步。下次执行脚本覆盖同一专用测试上传文件。

正常结束只移除手机files/ykt-test-v2/manual-scale/features.bin和manifest.json，保留report；电脑原数据和63批特征不删除。SDK RAM索引清空后进程停止，正常APP重新恢复自身6人库。不要卸载APP或清除应用数据。

本批代码仅完成静态语法和边界审查；当前执行环境没有完整厂商Demo、Android SDK和Windows构建环境，尚未编译APK或完成真机手动验收。独立数据库冷启动、长时稳定性、多机并发、完整平台核验仍未覆盖。
