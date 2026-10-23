# 核验端四页UI还原与验收记录

依据：用户2026-10-23上传的工作人员登录、首页、核验记录、设备维护四张单页开发效果图。用户指出0.7.22只做相似风格，本批纠正为依据图中的结构、间距、素材与交互重新实现。

## 布局基准

按约360dp手机逻辑宽度实现，保留系统真实状态栏，不把设计图的手机壳、9:41假时间或标注线放进应用。

|区域|实现基准|
|---|---|
|页边距与卡片|左右14dp，卡片圆角16dp、内边距16dp、纵向间隔12dp|
|登录头部|244dp最小高度，左对齐64dp图标、27sp产品名、16sp副标题|
|首页/其他页头|124/100dp最小高度，白色标题，内容卡片向上重叠20dp|
|摄像头|内容宽度的0.65倍高度，实际TextureView，蓝色四角与白色人脸定位引导|
|统计|正常字体4列，小屏或辅助大字体2列；图标22dp，数值22sp|
|记录|通用头像44×48dp、时间/脱敏姓名手机号/景区设备、结果色块、详情入口|
|维护|设备图、四项同步统计、单一服务列表、三项操作及固定底部导航|

这些是代码尺寸规范，并非已完成真机像素测量。尚无本批APK运行截图，不能将布局规范当成一比一验收证据。

## 素材与来源

使用内置 image_gen 工具生成，并复制到工程内，由Apply-Demo.ps1随overlays一起打包；不依赖临时下载地址。

- `overlays/app/src/main/assets/terminal-scenic-20261023.png`：1536×1024，蓝色山景背景。
- `overlays/app/src/main/assets/terminal-device-20261023.png`：1024×1536，透明核验设备缩略图。
- `TerminalMarkDrawable.java`：代码绘制默认核验图标；品牌图标在线配置优先。列表通用头像由TerminalIconView绘制，不显示用户照片。

生成提示词（内置工具，无CLI/API密钥）：

1. Scenic: production-ready Chinese scenic annual-pass Android app background; azure sky, soft white clouds, layered misty mountains, small Chinese pavilion on rocky green pine-covered peak at far right, bottom fading to pale icy blue; left65% and upper half spacious; polished realistic digital landscape painting; no UI, text, icons, border, phone frame or watermark.
2. Device: single black rugged handheld Android face-verification terminal, front-facing upright, charcoal rounded casing, circular camera, blue screen with cyan face-scan outline, small unmarked home button; transparent product cutout; no words, brand, humans, hands or environment; whole device centered with transparent padding.

保留平台配置的品牌名称、图标、背景和技术支持信息；配置了自定义背景时该图优先于内置默认素材。

## 真机验收清单（未执行）

- 原签名构建通过、版本142；覆盖安装，不卸载、不清数据。
- 登录/首页/记录/维护各截完整页面，另在字体放大条件下检查点击区域、换行和滚动。
- 相机真实预览、开始核验、语音播报、联网核验、照片拒绝不重复重传。
- 记录日期/结果/回执/景区/精确搜索，翻页沿用已查询条件；切换页面后旧响应不能覆盖新页面。
- 维护待同步与待确认数字来自真实状态；同步后退出不能跳过未确认记录。
- 与用户参考图逐页叠图核对后，才标记视觉验收通过。
