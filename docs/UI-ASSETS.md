# 核验端UI素材与布局依据

参考：用户2026-10-14提供“核验流程”“登录与维护”两张设计稿。

资源：overlays/app/src/main/assets/terminal-landscape.png；本地打包，绘制时按1/2采样解码，缓存单张Bitmap。制作工具：内置imagegen。

生成提示词：Wide 3:2 Chinese watercolor landscape in luminous azure blue, pale cyan and white; misty layered mountains, lake reflections, tiny pagoda on the right; upper 45 percent calm sky for white title; details lower half. No text, phones, UI, people or watermark.

交互实现：TerminalEntryActivity；纯绘制取景辅助框TerminalFaceGuide不是检测结果；TerminalProgressView显示实际加载数量。首页、登录、同步、维护按卡片分组；相机与结果为独立页面。小屏内容允许滚动，底部导航固定；不硬填示例统计、回执状态或不存在的分类计数。

验收待执行：小米11、字体放大、横竖屏切换、无相机权限、断网、等待同步、密码错误与改正、后台返回重新锁定、连续核验、退出前补传。未提供真机截图，不标记视觉验收完成。
