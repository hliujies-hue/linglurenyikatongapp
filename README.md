# 一卡通景区刷脸核验端

开发路线已纠正：直接基于上传的 FaceSDKAndroid(2).zip 完整 Demo 二次开发。

上一轮全新原生工程已从当前分支移除。历史提交保留供追溯，不作为构建入口。

## 当前准备步骤

在 Windows 拉取最新代码，运行 scripts/Import-Demo.ps1 -DemoZip <完整路径>，导入完整 Demo 到 demo-local。

此步骤仅准备原始基线，不自动修改包名、授权、签名或编译设置。不要构建或激活原版 Demo。原版仍使用厂商示例包名和示例签名。后续二开完成这些变更后再提供构建命令。

Demo、SDK、授权与签名文件留在本地，不上传到公开仓库。原版 Demo 保持完整，避免丢失内置相机、注册、活体、识别、人员库、维护功能。

已有需求见 docs/REQUIREMENTS.md。目标名称一卡通景区刷脸核验端，包名 com.lingluren.yikatong.terminal。平台联动和真机验证仍未完成。
