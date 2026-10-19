# SFHQ Part4 整批提取与索引测试


## 2026-10-19 SFHQ Part4 整批测试（待真机验收）

用户确认本轮只使用 `D:\\SHUJU\\sfhq-part4-originals` 中125754张原图，不再追求20万、不混入之前1077条。原图已全部解压。新增 `scripts/Run-PhotoScale.ps1`，PowerShell7，每批2000张，独立runId目录；源照片SHA256固定清单、上传后corpus校验、完整checkpoint及导出SHA校验后才登记完成和移除该批临时图片。失败保留当前批次，重复同一命令跳过已校验完成批，当前批按SDK checkpoint继续；修改图片或批大小拒绝续跑。每批特征、拒绝原因及checkpoint保存在WorkFolder；手机只清理本次已验证批次images，保留特征/checkpoint。最后仅合并本轮、跨批精确模板去重并核对照片计数，自动按全部有效数量运行原生索引测试（1..200000）。不复制模板扩容、不写生产人员库、不代表相机或端到端核验。该任务为手工命令，无定时任务。

新增批量脚本PowerShell语法树检查通过；Java改动语法检查通过。当前环境无Windows PowerShell/Android SDK，未完成实际Windows执行或本轮手机测试，不标记大库PASS。中断后保留WorkFolder，重复原命令续跑；建立WorkFolder/STOP会在下一批前停止，删除STOP后续跑。每批运行期间请不要使用核验功能。源图片读取校验也需要时间。用户要求手工摄像头体验的可视化测试模式尚未开发；业务原6人恢复仍待确认。

已归档用户上传的1000条真机报告 `docs/testing/native-scale-1000-20261019.json`：1077条数据中加载1000条，加载32ms，1000次self-query全部匹配，P50/P95/P99为0.059687/0.123334/0.19151ms，采样PSS128164KiB，清理码0。不能外推125754或20万。

## Windows PowerShell 7

```powershell
& .\scripts\Run-PhotoScale.ps1 -SourceFolder 'D:\SHUJU\sfhq-part4-originals' -WorkFolder 'D:\SHUJU\sfhq-part4-test' -Serial '8c552b70' -ExpectedPhotos 125754 -BatchSize 2000 -AllowPause
```

本地最终数据：WorkFolder/merged/features.bin 与manifest.json；索引报告：桌面Yikatong-NativeScale下本次目录/report.json。实际人数由质量过滤及去重决定，125754是输入图片数。
