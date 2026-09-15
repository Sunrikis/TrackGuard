# TrackGuard 轨道异物检测与预警系统

TrackGuard 是一个可本地部署的铁路轨道影像检测项目，采用 Vue 3、Spring Boot、SQL Server 和 YOLO-World。系统接收图片或短视频，检测指定目标，判断目标是否进入轨道危险区，并保存预警、截图和人工复核记录。配置 DeepSeek API 后，可结合完整任务检测结果和关键证据帧生成现场处置建议。

模型权重、数据集、上传素材和运行记录不随仓库发布。检测结果仅用于辅助巡检，使用者仍需结合现场信息复核。

## 功能

- 检测 JPG、PNG 图片和 MP4、MOV、M4V 短视频。
- 从视频中提取静态帧预览，可拖动进度条选择标定画面。
- 自动估计直线与平滑弯道区域，也可用 4–12 个边界点手动标定复杂轨道。
- 按人员、车辆、摩托车、动物、岩石 / 障碍物五类目标筛选检测结果。
- 异步执行任务，支持进度查看、取消和失败重试。
- 保存预警截图、置信度、处理状态和复核备注。
- 可选 DeepSeek 图像理解；图片按整项结果分析，视频按完整检测时间线和变化关键帧生成处置建议。
- 任务与预警支持分页、排序、单条删除和当前页批量删除。
- 提供趋势、类别和处理状态统计，可导出 HTML 报告与 CSV 记录。

## 技术结构

| 模块 | 技术 | 作用 |
| --- | --- | --- |
| 前端 | Vue 3、TypeScript、Vite、ECharts | 页面、表格、图表和单帧预览 |
| 后端 | Spring Boot、Java 21、JDBC | 账号、任务队列、记录、复核和报告 |
| 数据库 | SQL Server | 用户、任务、预警和日志 |
| 检测模块 | Python、PyTorch、Ultralytics、OpenCV | 目标检测、轨道区域估计和视频抽帧 |
| 图像理解（可选） | Python、DeepSeek `deepseek-flash` | `ai-core` 结合完整检测结果和关键证据帧生成处置建议 |

Spring Boot 按需启动本地 Python 工作进程。首个检测任务加载模型，后续任务复用同一进程和模型缓存。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Java | JDK 21 |
| Maven | 3.6.3 或更高 |
| Node.js | 22.x |
| Python | 3.9–3.11 |
| 数据库 | SQL Server 2022 或兼容实例 |

以下命令使用 Windows PowerShell，并从仓库根目录运行。

## 快速开始

### 1. 安装依赖

先激活用于检测的 Python 环境：

```powershell
python -m pip install -r .\ai-core\requirements.txt
npm.cmd --prefix .\frontend ci
```

PyTorch 应按本机 CPU 或 CUDA 环境单独选择合适版本。

### 2. 初始化数据库

首次部署时，使用具有 SQL Server 建库权限的 Windows 账号执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\init-database.ps1 `
  -Server 'localhost' `
  -Python (Get-Command python.exe).Source
```

脚本会创建 `RFOID_TrackGuard` 数据库、读写账号和本机 `.env`。如果发现同名数据库、账号或已有 `.env`，脚本会停止，不覆盖现有配置。

升级已有数据库时，由有结构修改权限的账号执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-database.ps1 `
  -Server 'localhost' -Database 'RFOID_TrackGuard'
```

结构更新在事务中完成，不删除已有用户和检测记录。数据库定义见 [schema.sql](backend/src/main/resources/schema.sql)。

### 3. 准备模型

下载 `yolov8l-worldv2.pt`，并生成包含本项目目标词的本地权重：

```powershell
python .\ai-core\prepare_model.py `
  --source 'C:\TrackGuardAssets\yolov8l-worldv2.pt' `
  --output 'C:\TrackGuardAssets\trackguard-world.pt'
```

首次运行可能下载文本编码权重。脚本不会覆盖已存在的输出文件。

如需提供多个模型，将 [catalog.example.json](ai-core/catalog.example.json) 复制到权重目录并改名为 `catalog.json`。每个 `id` 必须与同目录中的 `.pt` 文件名一致。

### 4. 配置环境

数据库初始化脚本生成 `.env` 后，只需在当前终端指定 Python 和模型路径：

```powershell
$env:RFOID_PYTHON = (Get-Command python.exe).Source
$env:RFOID_MODEL = 'C:\TrackGuardAssets\trackguard-world.pt'
```

手动配置时可复制 [.env.example](.env.example)，填写数据库账号、Python、模型和存储目录。`.env` 已被 Git 忽略。

需要启用图像理解时，在 `.env` 中填写 DeepSeek API Key：

```dotenv
DEEPSEEK_VISION_ENABLED=true
DEEPSEEK_API_KEY=你的_API_Key
DEEPSEEK_VISION_MODEL=deepseek-flash
DEEPSEEK_TIMEOUT_SECONDS=15
DEEPSEEK_MAX_IMAGES=8
```

`DEEPSEEK_API_URL` 可以填写官方基础地址 `https://api.deepseek.com`，也可以填写完整地址 `https://api.deepseek.com/chat/completions`；AI 核心会把基础地址规范化为 Chat Completions 端点。修改 `.env` 后需要重启服务，已经运行的 Python 工作进程不会动态读取新配置。

本地目标检测和轨道区域判断不依赖 DeepSeek。DeepSeek 请求由 `ai-core/vision_advice.py` 在 Python 工作进程中完成，Spring Boot 只管理工作进程并持久化其结构化结果。图片任务在检测结束后发起一次分析；视频任务也只发起一次分析，请求包含整段视频经目标关联后的完整结构化检测结果，以及按目标类别、风险变化、最高风险和时间阶段挑选的少量 JPEG 关键帧，不会逐帧上传，也不会上传原视频。`DEEPSEEK_MAX_IMAGES` 控制每个任务最多采用的关键帧数量。接口使用低细节图片和 JSON 输出，返回一条整项建议及必要的事件级建议；没有 Key、没有可用证据、返回无效内容或调用失败时保留内置规则。API Key 仅通过工作进程环境传递，不写入任务文件或数据库。请求格式参考 [DeepSeek 图像理解文档](https://api-docs.deepseek.com/zh-cn/guides/vision/) 和 [JSON Output 指南](https://api-docs.deepseek.com/zh-cn/guides/json_mode/)。

### 5. 启动项目

```powershell
python .\scripts\dev.py --check
python .\scripts\dev.py
```

打开 <http://127.0.0.1:5173>。保持终端运行，按 `Ctrl+C` 停止服务。

开发启动会在后端代码变化时重新打包，但跳过测试；前端使用 Vite 开发服务器。测试和生产构建需单独运行：

```powershell
python .\scripts\dev.py --build
```

也可以分别启动：

```powershell
mvn.cmd -f .\backend\pom.xml spring-boot:run
npm.cmd --prefix .\frontend run dev
```

| 服务 | 地址 |
| --- | --- |
| 前端 | <http://127.0.0.1:5173> |
| 后端 | <http://127.0.0.1:8080> |
| 健康检查 | <http://127.0.0.1:8080/api/health> |

## 使用说明

上传视频后，页面不会播放视频，而是由后端提取指定时间点的 JPEG 帧。拖动进度条可选择画面，手动标定的多边形会用于整段视频，因此该模式适合固定机位。移动、转向或变焦视频应先拆分为视角稳定的片段。

自动轨道定位先尝试边缘与直线特征，再通过水平分段和曲线拟合处理清晰的平滑弯道；视频中某一帧证据不足时复用最近一次有效区域。道岔、严重遮挡、移动机位或自动定位不稳时，应沿危险区边界手动标定 4–12 个点并检查结果。铁路专用轨道分割模型仍是复杂场景下更可靠的长期方案。

视频默认每 1 秒检测一帧，可在检测设置中调整为 0.5–5 秒或逐帧检测。单个视频最长 120 秒，单任务最多处理 14400 帧。默认输入尺寸为 640，按需在 `.env` 中通过 `RFOID_IMAGE_SIZE` 调整。

## 数据整理与模型训练

数据格式和均衡整理方法见 [数据集说明](ai-core/datasets/README.md)。默认整理结果为：

| 数据划分 | 每类图片 | 五类合计 |
| --- | ---: | ---: |
| 训练集 | 1000 | 5000 |
| 验证集 | 200 | 1000 |
| 测试集 | 100 | 500 |

检查数据并开始微调：

```powershell
python .\ai-core\train\train.py --mode check --split test
python .\ai-core\train\train.py --epochs 100 --batch 4 --device 0
```

测试集只用于模型和参数确定后的最终评估。训练、续训、评估和权重接入方法见 [训练说明](ai-core/train/README.md)。

## 测试与构建

```powershell
npm.cmd --prefix .\frontend test
npm.cmd --prefix .\frontend run build

mvn.cmd -f .\backend\pom.xml -B test
mvn.cmd -f .\backend\pom.xml -B package

python -m unittest discover -s .\ai-core\tests -v
python -m unittest discover -s .\scripts\tests -v
```

单元测试保留在仓库中。会创建真实账号、数据库记录或截图的一次性本地验收脚本不随公开仓库发布。

## 目录

```text
RFOID/
├── frontend/             # Vue 页面与组件
├── backend/              # Spring Boot API 与 SQL Server 数据访问
├── ai-core/              # 检测、轨道几何、图像理解、数据整理和模型训练
├── scripts/              # 启动、停止和数据库脚本
├── .env.example          # 环境变量模板
└── README.md
```

模块说明：[前端](frontend/README.md) · [后端](backend/README.md) · [检测模块](ai-core/README.md)

## 数据与使用边界

- `.env`、模型权重、数据集、上传素材、截图、日志和训练输出均在 `.gitignore` 中排除。
- 账号和检测记录属于同一部署实例的共享工作空间，不提供多租户隔离。
- 删除数据库记录不会删除磁盘上的原始素材或截图。
- 模型可选类别不代表权重已经完成铁路场景微调，应使用独立标注测试集评估实际效果。
- 启用 DeepSeek 图像理解会把结构化检测结果和选中的关键帧发送到所配置的外部 API；部署方应根据数据管理要求决定是否启用，并妥善保管 API Key。
- 本项目不是铁路安全认证系统，不应作为现场处置的唯一依据。
