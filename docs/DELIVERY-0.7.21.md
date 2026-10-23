# 0.7.21 不合格照片停止重传交付记录

日期：2026-10-23。版本：0.7.21-photo-rejection / versionCode140。

## 行为

- 首次下载后，算法判定无人脸、多脸、模糊、遮挡、角度或光照不合格：加密保存拒绝原因，删除旧特征，回报照片待更换，继续其他人员。
- 同图重复任务、重新登录或进程重启：读取已保存结果，不再下载、提取；必要时补清理及失败回执。
- 后台更换照片：新photoRevision允许重新下载和识别。网络超时、断网、SDK未就绪不写入照片拒绝记录。
- 平台按设备、人员和照片版本继承拒绝状态，阻止手动重发；不拦截DELETE撤权。
- 沿用现有数据库加密，无新表迁移，保留原签名、授权、人员和未确认记录。

## 验证记录

| 检查 | 结果及边界 |
|---|---|
| tests/test_photo_rejection.py | 通过；执行生产同步方法，网络/SDK/存储为替身 |
| tests/test_session_recovery.py | 通过；生产恢复方法与替身 |
| tests/test_camera_lifecycle.py | 通过；模拟Android |
| Test-Client.ps1内14组Java测试 | Linux按相同文件及入口运行通过；未执行PowerShell |
| 平台拒绝继承测试 | 生产函数及模型在独立MySQL8通过；使用Go1.25.7/GORM1.31.1隔离工程，未完成项目全量构建 |
| TerminalStoreInstrumentedTest新增照片用例 | 已编写，未运行 |
| Android Lint、release APK、原签名检查及覆盖安装 | 未运行；没有本批已构建APK |

平台和APP代码提交不代表已部署。此前后台登录超时尚无根因证据，不将本次照片修复作为该问题的解决。

## 更新顺序

1. 服务器使用本次平台固定提交，执行deploy/v4/update-platform.sh。既有脚本保留本地历史和环境配置，备份后构建升级。
2. 原Windows工程C:\Users\Administrator\Desktop\app使用本次APP固定提交快进更新。
3. scripts/Verify-Release.ps1 -Revision <本次完整SHA>核对版本及原签名/Demo；scripts/Export-Apk.ps1执行主机测试、构建、Lint和签名验证。
4. 只有上一步成功后才运行scripts/Test-Device.ps1 -Install。禁止卸载、清数据或使用-DeferTests绕过失败。

完整固定提交命令见交付消息。旧平台缺少photoRevision，APP只能退回按targetHash去重，所以必须先升级平台。

## 真机验收

1. 空闲测试设备覆盖安装，核对140版本及原授权、未确认记录保留。
2. 测试人员上传一张明确无人脸照片，等待拒绝；后台应提示换照并停用重新下发。
3. 等待多个同步周期、退出重新登录、重启APP：服务器照片下载日志不应重复出现该版本请求，其他人员继续同步。
4. 拒绝后制造回执断网再恢复：只补回执，不重新下载或提取该照片。
5. 更换为合格正面照片，应产生新版本、同步成功并允许正常核验。
6. 单独制造照片下载超时：恢复网络后可重试；撤权仍删除人脸。禁止把暂时网络故障永久认定为坏照片。
7. 可选scripts/Test-Device.ps1 -VerifyJournal运行独立数据库仪器测试；不等同业务验收。

后续依次核实会话恢复真机表现、退款/拉黑删脸、跨设备去重、单点规则及统计闭环；本批不宣称这些全部完成。
