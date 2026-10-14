# 核验端UI素材与布局依据

参考：用户2026-10-14提供“核验流程”“登录与维护”两张设计稿。

资源：overlays/app/src/main/assets/terminal-landscape.png；本地打包，绘制时按1/2采样解码，按文件名缓存Bitmap。制作工具：内置imagegen。

生成提示词：Wide 3:2 Chinese watercolor landscape in luminous azure blue, pale cyan and white; misty layered mountains, lake reflections, tiny pagoda on the right; upper 45 percent calm sky for white title; details lower half. No text, phones, UI, people or watermark.

交互实现：TerminalEntryActivity；纯绘制取景辅助框TerminalFaceGuide不是检测结果；TerminalProgressView显示实际加载数量。首页、登录、同步、维护按卡片分组；相机与结果为独立页面。小屏内容允许滚动，底部导航固定；不硬填示例统计、回执状态或不存在的分类计数。

验收待执行：小米11、字体放大、横竖屏切换、无相机权限、断网、等待同步、密码错误与改正、后台返回重新锁定、连续核验、退出前补传。未提供真机截图，不标记视觉验收完成。


## 0.6.0-result-ui 素材与排版更新

2026-10-14参考图：059cf50e3d9d1350479feeb3e244bb72.png（核验流程）、0e590f24689287c8b7e8749cc2e3a41c.png（登录/同步/维护），以及统计卡片裁图image(20261014-013422).png。参考图本身不是应用运行截图。

| 本地打包资源 | 用途 | 生成方向 |
|---|---|---|
| terminal-header-blue.png | 登录及各业务页蓝色页头 | 蓝白水墨远山与湖面，下部山峦/小亭，上部保留文字空间；无字、无UI、无人物 |
| terminal-result-mint.png | 结果页祝福卡片、维护授权区 | 薄荷绿湖面水墨山水，左右远山小亭，中间留白；无字、无UI、无人物 |
| terminal-landscape.png | 首页相机无画面时的底图 | 保留上一版蓝色水墨素材 |

素材以imagegen生成，全部通过assets随包安装；按1/2采样解码，按文件名复用Bitmap，等比裁切；不依赖远程图片地址。图标由TerminalIconView绘制，结果圆环由TerminalResultBadge绘制。两张新增原始文件均约1.6–1.8MB，未对生成图做额外像素编辑。

| 页面 | 实现布局 |
|---|---|
| 登录 | 高页头、账号密码白色卡片、显示/隐藏密码、蓝色主按钮、平台连接卡片、人员库留存说明 |
| 首页 | 紧凑页头、三列状态、账号归属、同步入口、真实相机预览、主按钮、四列统计、最近记录、固定底部导航 |
| 核验 | 蓝色顶部、相机占剩余空间、浮层提示、引导文字、三列操作；无固定超高相机导致按钮被挤出 |
| 结果 | 圆环状态标志、分类标题与明细、真实时间/景区/回执、薄荷山水、返回/继续两个按钮 |
| 同步 | 真实加载圆环、已加载/总量、增量待处理和待回执；新增/更新/删除拆分仍待协议支持 |
| 维护 | 授权状态、连接与账号信息、同步状态、语音开关、维护列表及同步后退出 |

页头随放大文字自适应增高，内容可滚动，底部导航固定；统计等宽，较大数字自动缩小（Android8及以上）。数据指标按最新要求为四项，覆盖旧参考图的三项。小米11实际尺寸和系统字体放大仍待安装后截图核对；未进行像素差异检测，不登记虚假还原百分比。
