# 真机20万特征索引测试（入口已实现，尚未实测）

## 范围与当前状态
正式APP仍为0.7.17-device-info / 136。本次新增独立androidTest APK及Windows手工入口，不增加日常APP菜单，不启用自动构建任务。现有手机已由用户确认授权、登录、6人同步和实际核验正常。数据库20万任务报告已在平台仓库e11a15fee7c69b6a59c4b26f098d4c09a97151b1归档。

测试直接调用真实SDK FaceSearch.pushPersonById/search，单线程装载200000条，再对分布于全库的1000条原始特征做同特征回查（固定阈值80）。这是索引容量/自检延迟测试，不是不同照片识别率、活体、完整入园、SQLite冷启动或长时温升测试。数据写入后缓存效应存在；加载耗时包含周期性采样和报告落盘。

## 测试数据前置条件
目前没有200000份有效SDK特征，不能开始正式容量验收。需数据提供方准备与当前百度SDK8.5及LIVE_PHOTO模型兼容的真实特征；不能随机生成、复制现有6人、将同一人不同照片冒充不同人或从业务库擅自导出。

features.bin为200000个连续的512字节特征，总长102400000字节。无文件头、无ID（行号1..200000是临时索引ID）。同目录manifest.json必须由数据提供方据实填写：

```json
{"sdkVersion":"8.5","featureType":"LIVE_PHOTO","count":200000,"genuineDistinctIdentities":true,"synthetic":false,"sha256":"features.bin的64位小写SHA256"}
```

长度、哈希、非全零、特征字节不重复均会检查。字节不同不能证明人物身份不同或算法模型兼容；来源声明不是独立验证。数据提供方需要核实合法使用范围、提取模型及不同身份，不能只修改manifest骗过校验。特征文件不要提交Git或发到聊天中。

## Windows操作
使用现有C:\Users\Administrator\Desktop\app及原正式签名，先准备测试APK（无需数据、不会操作手机）：

```powershell
& .\scripts\Test-NativeScale.ps1 -PrepareOnly
```

准备数据后，连接一部闲置且USB调试已授权的测试手机，正式APP版本码136、维护页待确认记录0。测试会暂时停止该手机核验，不用于正在接待游客的手机。先保持屏幕亮起，确保授权初始化所需网络正常。

```powershell
& .\scripts\Test-NativeScale.ps1 -Dataset 'C:\Yikatong-TestData\features.bin' -AllowPause
```

多部手机时必须加-Serial指定序列号。脚本只安装独立测试APK，Android签名匹配失败会停止，不卸载或清数据。仪器测试启动独立的一次性目标进程，不启动TerminalRuntime/同步线程，不读写生产TerminalStore；只读取现有成功授权码供SDK初始化，报告不导出授权码、指纹、人脸特征。

临时特征在测试APK的专属外部files/native-scale目录。完成或可捕获失败后拉取report.json/终端日志到桌面Yikatong-NativeScale时间目录，尝试移除手机测试特征，停止测试进程并重新打开原APP。重开后检查6人名单、登录、授权及核验仍正常；这一步不是脚本自动验收。

## 停止及故障
每1000次装载/50次查询检查系统lowMemory或可用内存低于384MiB，记录采样PSS峰值与原生堆。并检查30分钟总时限、STOP文件。不是硬性资源上限；单次native调用卡住时无法立即执行检查，仍可能发生OOM/崩溃。

需要中止时，在另一个PowerShell窗口执行（仅连接一部测试手机时）：
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell touch /sdcard/Android/data/com.lingluren.yikatong.terminal.test/files/native-scale/STOP
```
若native调用无响应，可force-stop正式包，结束本轮并保留失败状态，不能记为通过。断开USB/关电脑时finally未必执行；报告和特征可能留在测试专属目录，重连后先拉取报告，再仅删除该目录features.bin/manifest.json，不得清除正式应用数据。

PASS要求仪器测试OK(1 test)、报告PASS、数据哈希匹配、1000次自回查ID匹配、native清理返回0。报告RUNNING/缺失或没有仪器测试完成标志均不能当成功。PSS是周期采样峰值，不是精确全过程最大值；阈值80为本测试固定值，不修改生产识别设置。

## 验证与剩余
当前开发环境无Android SDK/百度二进制/Windows PowerShell/连接手机，未构建测试APK，未执行SDK。两份新增Java文件已通过JDK编译器语法解析（没有类型检查）；源码检查不等于厂商SDK编译通过。首次Windows PrepareOnly是实际编译验证入口。仍需真实数据集和手机运行日志；后续再单独开展SQLite加载、不同照片查询、稳定性/温升和端到端验收。
