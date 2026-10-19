# 手机SDK特征制作与分档容量测试

## 当前状态
正式APP仍136。用户确认6人实际核验正常，c0e9f26版测试APK在Windows编译成功（16秒），没有数据所以没有真机容量结果。本批新增图片提取、续做、显式synthetic/real模式和1000/10000/50000/100000/200000档位，仅通过Java语法解析；新APK尚未编译或运行。没有合成图片语料；工具不会生成照片，也不复制6人或拼随机特征。

## 数据与报告含义
synthetic：合法可用的合成人脸图片，经真实SDK提取，结果只能代表该合成数据分布下的容量和延迟。real：提供方确认使用权限和一人一张，显式-AttestDistinctPeople。不同特征不等于不同身份，照片模式/身份声明不是独立验证。不要把图片/特征提交Git或发到聊天。

features.bin为连续512字节特征，无文件头，行号作为测试ID。旁边manifest.json包含sdkVersion=8.5、featureType=LIVE_PHOTO、有效count、synthetic布尔值、genuineDistinctIdentities声明、sha256；提取工具自动生成。不能只修改声明冒充兼容数据。识别准确率需要注册照以外的查询照片和未注册人员，当前原特征自回查不是此项测试。

## 只编译
```powershell
& .\scripts\Test-NativeScale.ps1 -PrepareOnly
```
不操作手机，保持原正式签名。

## 图片提取
准备无子目录的jpg/jpeg/png/webp图片文件夹，单张1字节至16MiB，总数不超过20万。连接一部闲置、已授权且版本136的手机，待确认记录0。路径必须是实际存在的语料文件夹：
```powershell
& .\scripts\Extract-NativeFeatures.ps1 -SourceFolder 'C:\Yikatong-TestData\synthetic-images' -DatasetMode synthetic -MaxPhotos 1000 -AllowPause
```
安装独立测试APK，暂停核验。通过生产TerminalFaceEngine.upsert质量检测和特征提取；使用native-feature-extract-test.db，不打开生产terminal-v2.db、不启动生产Runtime/同步线程。每张提取后删除临时索引人员。重复特征/质量失败写rejections.jsonl；其他SDK/数据库错误停止。每张输出及checkpoint落盘；已有输出尾部若未完成checkpoint，续做时截去。

成功输出在桌面Yikatong-FeatureData时间目录，包括features.bin、manifest.json、checkpoint.json、rejections.jsonl和仪器日志。有效数可能少于照片数，不足对应档位会拒绝，不补随机字节。测试过程中只读现有成功授权码用于SDK初始化，不在报告中导出。结束重新打开APP，人工确认原6人和授权核验正常。

## 续做
照片/进度保留在测试包专属files/feature-extract，首次上传逐图片执行，数量大时会慢。Resume不再次上传；必须使用原来的手机语料和SDK，重新核对全部图片哈希：
```powershell
& .\scripts\Extract-NativeFeatures.ps1 -Resume -DatasetMode synthetic -MaxPhotos 10000 -AllowPause
```
MaxPhotos是累计处理上限，必须首次已有足量图片。更换语料前先导出上一批，再仅清理测试包feature-extract目录，不能复用不同语料checkpoint。首次目录已存在时脚本停止，不覆盖。

## 分档压测
Dataset替换为实际导出路径：
```powershell
& .\scripts\Test-NativeScale.ps1 -Dataset 'C:\Yikatong-TestData\features.bin' -DatasetMode synthetic -Count 1000 -AllowPause
```
随后人工核对每档结果，再依次Count=10000/50000/100000/200000。有效count必须足够。完整包哈希、全零和重复字节校验后装载前Count条，报告记录全包count/哈希和本档count，不能混淆。固定阈值80，抽取分布于全库的1000条原特征回查，记录加载时间、P50/P95/P99、命中数、周期采样PSS和原生堆。采样峰值不是精确峰值，加载包含采样落盘，重复查询存在缓存效应；不代表持续吞吐、冷启动SQLite、活体或真人准确率。

报告在桌面Yikatong-NativeScale时间目录。PASS必须仪器测试OK(1 test)、报告PASS、哈希/模式/档位匹配、自回查1000次ID匹配且native清理成功。测试脚本结束尝试删索引测试副本features.bin/manifest.json并重新打开APP。

## 中止与残留
检查点遇到STOP、系统lowMemory或可用内存低于384MiB、运行超过30分钟会停止。提取时限不包含开始前全语料哈希扫描；native单次卡住时检查不能及时执行，仍可能OOM。多设备时以下adb命令需加-s指定手机：
```powershell
# 中止提取
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell touch /sdcard/Android/data/com.lingluren.yikatong.terminal/files/ykt-test-v2/feature-extract/STOP
# 中止索引测试
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell touch /sdcard/Android/data/com.lingluren.yikatong.terminal/files/ykt-test-v2/native-scale/STOP
```
PAUSED/FAIL/RUNNING/缺报告不能算通过。断开USB/关闭Windows进程时finally不保证执行，先拉报告再恢复APP。图片提取目录为续做保留原图/特征（不是后台自动备份），确认电脑导出校验成功后仅清理该测试目录。不要卸载或清空正式APP数据，不要将这些测试文件作为公共下载。

## 待完成
实际图片语料、新版APK编译、提取续做/容量真机报告、长时内存温升、不同照片准确率和完整入园流程。目前不能报告20万手机测试通过。


## 2026-10-19 Android 14 测试目录修复

- 现场证据：小米 M2011K2C 已上传700张 SFHQ 合成图片，测试在读取目录时失败，未产生特征或容量结果。
- 修复：instrumentation 使用 targetContext 的 externalFilesDir，脚本上传/导出同步改为正式包 files/ykt-test 下的专用测试目录；独立测试数据库保持不变，不清理正式业务数据。
- 原测试包目录保留，不自动迁移或删除；首次用原电脑图片目录重新上传，后续断点续跑使用新目录。
- instrumentation 失败时先报告原始测试失败，不再用缺少 manifest/report 的错误掩盖原因。
- 本次静态检查不代表真机通过；需重编测试 APK 后验证700张提取。1000至200000条原生索引容量测试仍待执行，700张不足首档。


### R4：目录修复未通过后的应用内预检
- 05fed5a现场已编译安装，但目标目录仍 exists=false/readable=false，未提取特征。上一轮修改未完成真机修复。
- 新增 testPrepareStorage：由目标应用进程创建专用 ykt-test-v2 子目录并实际写读探针；上传后 testValidateImages 逐张打开文件，检查失败即停，不进入SDK。
- 保留旧目录，不通过放宽全局权限或清理应用数据处理。新路径首次重新上传，后续 Resume 使用新路径。Java语法检查不等于Android编译或真机通过，后两项待现场验证。


## 2026-10-19 本地 560 条基线测试

新增 `-Count 560`，使用已导出的 SFHQ 合成人脸 SDK 特征，不复制或随机伪造模板。手机加载 560 条，执行 1000 次同模板自查询；报告 testTier=baseline-560。此结果仅作为小样本 SDK 索引基线，不代表 20 万容量或真实人脸准确率。

已知提取结果：700 张处理完成，接受 560、拒绝 140、重复 0；features.bin 长度 286720 字节，SHA256 `68a7d3a47db820957cd612be49514ac8f97fcb72f352d75ad204d9a6037b2cac`。本次手机基线尚待用户执行；未宣称通过。保持 manifest、哈希、重复特征检查和原业务数据隔离。


## 2026-10-19 560 条测试崩溃修复（待真机复测）

a80fde4 真机编译成功，但 native self-query 进程 SIGSEGV。用户 crash.txt 中本次调用链为 TerminalNativeScaleTest → FaceSearch.search → nativeSearch → bdface_percent_map，fault address 0xc0，null pointer dereference。此前报告未落盘，不能报告耗时或通过，也没有内存容量不足证据。

测试代码遗漏生产 TerminalFaceEngine 使用的 FaceFeature.initModel。补齐相同识别模型及成功回调等待；保留模型对象；检索前落盘阶段及 featureModelInitialized，异常退出自动导出 crash.txt/logcat.txt。只修改测试工具，不改生产业务或阈值。Java语法检查通过，不等于Android编译或真机验证；560和20万测试均尚未通过。本次日志含其他应用历史记录，不将原始整机日志提交仓库。


## 2026-10-19 15:41 真机基线已通过及下一步

用户执行 e7c7cee 并提供 report.json 全文，按原字段归档于 `docs/testing/native-baseline-560-20261019.json`（由用户粘贴内容转存）。560 合成特征加载3ms，1000次同模板查询全部匹配；P50/P95/P99=0.025782/0.033802/0.114635ms，采样PSS峰值116367KiB，原生堆53119528字节，清理返回0。此结果不含相机/活体/提取/平台核验，不可外推20万容量或真人准确率。原有6人业务恢复尚待用户确认。

新增提取参数 `-DatasetId sfhq-batch-002`：新批次使用独立 `ykt-test-v2/feature-batches/<id>`，准备、读取检查、提取及导出使用同一路径；同ID已存在拒绝覆盖，续传必须 `-Resume -DatasetId <原ID>`。默认legacy保留原700张提取目录。禁止往旧批次追加照片。新批次机制Java语法已检查，Windows/手机验证待新图片到位。下一阶段需要至少1000条有效不重复特征；不复用复制模板冒充容量。暂不再要求Kaggle Notebook整库准备。
