# 后端模块

基于 Spring Boot 3.5、JDK 21 和 Microsoft SQL Server JDBC 的业务服务。

## 运行

先按 [项目 README](../README.md) 准备数据库、模型和环境变量，再从仓库根目录执行：

```powershell
mvn.cmd -f .\backend\pom.xml spring-boot:run
```

在 IDEA 中也可导入根目录 `pom.xml`，使用 JDK 21 运行 `cn.rfoid.TrackGuardApplication` 的 `main` 方法。

健康检查：http://127.0.0.1:8080/api/health。

## 职责

| 包 | 职责 |
| --- | --- |
| api | 登录注册、头像、上传、查询、复核、报告和错误响应 |
| config | 运行参数、会话校验和请求防伪 |
| repository | 用户、任务、预警和日志的 SQL Server 持久化 |
| service | 密码哈希、头像处理、模型目录、任务队列、Python 进程管理和检测结果校验 |

数据库结构见 [schema.sql](src/main/resources/schema.sql)。旧数据库需由有权限的管理员执行升级脚本，补齐用户表、模型、目标类别和建议来源字段。

密码使用随机盐和 PBKDF2 哈希保存。登录后使用服务端会话；写请求校验防伪令牌。头像解码后裁剪为 256 × 256 并重新编码为 PNG。任务和预警在工作空间内共享。

后端只接受管理员登记的模型标识，将模型名称和类别选择随任务一起保存，再调用本地 AI 进程。重试保留原任务参数，报告与记录展示本次使用的模型。

Python 工作进程在首个检测任务到达时启动并加载模型，后续任务复用模型缓存。DeepSeek 的关键帧选择、提示词、HTTP 请求和响应解析均位于 `ai-core/vision_advice.py`。后端只把 `.env` 中允许的推理和 `DEEPSEEK_*` 配置注入私有 Python 工作进程，并校验、保存工作进程返回的结构化结果；API Key 不写入任务 JSON、结果 JSON 或数据库。超时、限额、无效 JSON 或接口错误不会使检测任务失败，系统保留本地规则建议并记录来源。

## 验证

```powershell
mvn.cmd -f .\backend\pom.xml -B test
mvn.cmd -f .\backend\pom.xml -B package
```
