# 前端模块

基于 Vue 3、TypeScript、Vite 和 ECharts 的巡检工作空间。采用响应式布局，包含登录注册、个人头像、巡检总览、检测工作台、预警记录和统计分析。

## 运行

从仓库根目录执行：

```powershell
npm.cmd --prefix .\frontend ci
npm.cmd --prefix .\frontend run dev
```

访问 http://127.0.0.1:5173。开发服务器将 `/api` 请求转发至 http://127.0.0.1:8080，请同时运行 Spring Boot 后端。

```powershell
npm.cmd --prefix .\frontend test
npm.cmd --prefix .\frontend run build
```

## 源码职责

| 路径 | 职责 |
| --- | --- |
| src/views/ | 登录注册、总览、检测、记录和统计页面 |
| src/components/ | 个人资料、图表、任务详情和预警复核 |
| src/api.ts | 会话、业务接口、上传与报告下载 |
| src/types.ts | 接口数据类型 |
| src/format.ts | 展示格式和标定区域校验 |
| src/style.css | 公共样式与响应式布局 |
| src/workspace.css | 账号界面、模型选择、多选目标和可读性样式 |
| public/ | 静态图标 |

检测请求包含模型标识、所选类别和滑动条的抽帧间隔；页面展示真实后端数据。注册时支持头像预览，个人资料支持后续更换，页面刷新后会恢复有效登录会话。

完整部署步骤见 [项目 README](../README.md)。

视频预览通过后端 OpenCV 提取 JPEG 单帧，自动模式、手动标定和任务详情均使用图片与时间滑条，不依赖浏览器视频解码。快速拖动时只展示最新时间点的结果；切换文件会丢弃旧请求结果并清理临时预览。

视频手动标定支持四点圈定钢轨范围、撤销和重置。切换画面保留区域，换文件清空区域；画面加载期间暂停标定和手动检测提交。同一区域应用于整段视频，适用于固定机位。
