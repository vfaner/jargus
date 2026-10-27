# 百目 JArgus · Java 代码评审平台

[中文](README.md) | [English](README_EN.md) | [官网 jargus.qqmu.com](https://jargus.qqmu.com/)

[![Gitee Stars](https://gitee.com/super_rgh/jargus/badge/star.svg?theme=dark)](https://gitee.com/super_rgh/jargus)
[![GitHub Stars](https://img.shields.io/github/stars/vfaner/jargus?style=flat)](https://github.com/vfaner/jargus)
[![AtomGit Mirror](https://img.shields.io/badge/AtomGit-mirror-1f6feb)](https://gitcode.com/vfaner/jargus)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/vfaner/jargus?label=release)](https://github.com/vfaner/jargus/releases)

> 开箱即用的 Java 代码质量评审平台：本地静态分析引擎 + 可选 AI 深度评审 + SonarQube 式五级评分与质量门禁 + CI/CD 全链路联动 + 扫描报告邮件推送。
> 单个 JAR / 单个 Docker 镜像交付，内嵌数据库零外部依赖，完全离线可用，界面、规则、报告原生中文。

![百目 JArgus · 一个 JAR 跑通 Java 代码评审全闭环](images/zhutu.png)

> 🎬 **部署视频**：[B 站约 21 分钟完整部署演示](https://www.bilibili.com/video/BV1aMav6iEgd/)——从下载启动到跑通第一个评审，跟着点一遍即可上手。

> ⭐ 如果 JArgus 对你有帮助，欢迎在 [Gitee](https://gitee.com/super_rgh/jargus)、[GitHub](https://github.com/vfaner/jargus) 或 [AtomGit](https://gitcode.com/vfaner/jargus) 点一个 Star，这是对项目持续维护最大的支持。

## 📖 开发背景

- **企业级平台门槛高**：功能完备的代码质量平台通常需要独立服务器与数据库、并配套运维体系，试用与小团队引入成本高；
- **扫描与治理脱节**：相当一部分静态分析工具止步于输出问题清单，缺少评分、门禁、可视化报告与后续治理的闭环机制；
- **AI 与静态分析未打通**：编码助手各自使用自有模型，难以统一管理，模型能力也不与静态分析结果结合；
- **国内环境额外诉求**：内网隔离、信创数据库、国产大模型、中文汇报材料。

JArgus 据此以**一个 JAR / 一个容器**交付「扫描 → 评分 → 门禁 → 报告 → CI 阻断 → 邮件推送」完整闭环：内嵌 H2 开箱即用、19 个检查器、可选接入大模型、全流程中文。

## ✨ 核心特性

- **多种接入方式**：ZIP 上传、代码粘贴、CI 自动 Git 克隆（GitHub / GitLab / Gitee / 自建平台，支持私有仓库凭据）；代码快照留存，问题可回溯到带行号高亮的源码上下文。
- **本地静态分析**：完全离线，19 个检查器覆盖六大质量域——缺陷（空指针 / 资源泄露 / 异常处理）、安全（SQL 注入 / 命令注入 / 反序列化 / 硬编码密钥 / XXE / SSRF 等）、架构分层、并发、风格冗余（含 CPD 式重复代码）、依赖 CVE 漏洞。
- **AI 深度评审（可选）**：OpenAI 兼容 / Anthropic 双协议，内置 12 个厂商模板（百炼 / 方舟 / DeepSeek / Kimi / 智谱 / 千帆 / Gemini / Claude / Ollama 等），API Key AES 加密；单条问题「AI 增强建议」（问题分析 / 修复方案 / 修复代码），支持批量深度评审与实时进度。
- **五级评分与质量门禁**：BLOCKER / CRITICAL / MAJOR / MINOR / INFO 五级，每级扣分、通过线、评级分界页面自定义、保存即时生效；附技术债估算。
- **问题治理**：同文件同规则问题自动聚合；行级 / 规则级忽略并留痕；检查器启停与规则阈值页面可配。
- **报告与邮件推送**：HTML / PDF 中文报告一键导出（内嵌中文字体）；多 SMTP 发件配置（SSL / STARTTLS、测试发信、授权码 AES 加密）与收件人管理；扫描或 CI 触发器勾选「邮件通知」后，扫描完成自动推送 **HTML 摘要正文 + PDF 报告附件**，失败也发通知，送达状态在扫描历史可见。
- **CI/CD 集成**：Webhook 触发扫描（按平台约定校验 HMAC 签名），完成后自动回写 commit status 与 MR/PR 评论，流水线按门禁结论阻断合并；详见下文指南。
- **认证与安全**：JWT 本地账号 + 可选 OAuth2 单点登录，管理员 / 只读双角色；数据库密码、API Key、仓库令牌、SMTP 授权码全部 AES 加密落库；SQL 全参数化，ZIP 解压含 Zip Slip 防护。
- **界面与国际化**：Thymeleaf 服务端渲染，无 Vue / npm 构建链，零 CDN；中英双语、深色 / 浅色主题、响应式布局。
- **多数据库**：内嵌 H2 默认零安装，另支持 MySQL / PostgreSQL / Oracle / SQL Server / 达梦 / 金仓 / openGauss / OceanBase 等 18 类数据库，驱动随包内置，页面可视化切换与自动建表迁移。

## 🧰 技术栈

| 层次 | 选型 |
|------|------|
| 后端 | Spring Boot 3.2.5 · Java 17 · MyBatis-Plus 3.5.5 · H2 内嵌 + 17 种驱动内置 · 动态多数据源 |
| 静态分析 | JavaParser 3.25（AST + 符号求解）· ASM 9.6 · 自研 CPD 式重复代码指纹 |
| AI 接入 | OpenAI 兼容 / Anthropic 双协议适配层 |
| 报告与邮件 | OpenPDF（矢量中文 PDF）· Thymeleaf HTML 报告 · Spring Mail |
| 安全 | JWT · BCrypt · AES 配置加密 · OAuth2 远端认证 |
| 前端 | Thymeleaf SSR · 原生 JavaScript · 零 CDN |
| 部署 | 单 JAR · Docker 多阶段（内置中文字体、非 root、HEALTHCHECK） |

## 🖼️ 效果预览

截图均取自真实运行页面，图片资产位于仓库 [`images/`](images) 目录。

<table>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_kanban.png" width="100%"><br><sub>仪表盘</sub></td>
    <td width="33%" align="center"><img src="images/jargus_kanban_anye.png" width="100%"><br><sub>暗色主题</sub></td>
    <td width="33%" align="center"><img src="images/jargus_kanban_en.png" width="100%"><br><sub>英文界面</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_saomiao.png" width="100%"><br><sub>新建扫描</sub></td>
    <td width="33%" align="center"><img src="images/jargus_lishi.png" width="100%"><br><sub>扫描历史</sub></td>
    <td width="33%" align="center"><img src="images/jargus_result.png" width="100%"><br><sub>扫描结果</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_result_ai.png" width="100%"><br><sub>问题详情与 AI 建议</sub></td>
    <td width="33%" align="center"><img src="images/jargus_baobiao.png" width="100%"><br><sub>导出报表</sub></td>
    <td width="33%" align="center"><img src="images/jargus_jianchaqi.png" width="100%"><br><sub>检查器配置</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_guize.png" width="100%"><br><sub>评审规则</sub></td>
    <td width="33%" align="center"><img src="images/jargus_hulve.png" width="100%"><br><sub>忽略规则</sub></td>
    <td width="33%" align="center"><img src="images/jargus_menjin.png" width="100%"><br><sub>质量门禁</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_fajianpeizhi.png" width="100%"><br><sub>发件配置</sub></td>
    <td width="33%" align="center"><img src="images/jargus_shoujianren.png" width="100%"><br><sub>邮件收件人</sub></td>
    <td width="33%" align="center"><img src="images/jargus_mail.png" width="100%"><br><sub>报告邮件送达</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_cicd.png" width="100%"><br><sub>CI/CD 触发器</sub></td>
    <td width="33%" align="center"><img src="images/jargus_cicd_add.png" width="100%"><br><sub>新建触发器</sub></td>
    <td width="33%" align="center"><img src="images/jargus_cicd_jilu.png" width="100%"><br><sub>触发扫描记录</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_db.png" width="100%"><br><sub>数据库配置</sub></td>
    <td width="33%" align="center"><img src="images/jargus_oa.png" width="100%"><br><sub>远端认证</sub></td>
    <td width="33%" align="center"><img src="images/jargus_oa_add.png" width="100%"><br><sub>新增远端认证</sub></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="images/jargus_ai.png" width="100%"><br><sub>AI 厂商配置</sub></td>
    <td width="33%" align="center"><img src="images/jargus_xitongxinxi.png" width="100%"><br><sub>系统信息</sub></td>
    <td width="33%"></td>
  </tr>
</table>

## 📊 功能对比

| 维度 | **百目 JArgus** | SonarQube（社区版） | PMD / SpotBugs / Checkstyle | CodeQL |
|------|------------------|---------------------|------------------------------|--------|
| 部署 | 单 JAR / 单容器，内嵌数据库 | 服务器 / 数据库 / 计算引擎分离 | 仅 CLI / IDE 插件 | 专用 CLI，托管于 GitHub |
| 中文 | 界面 / 规则 / 报告原生中文 | 英文为主 | 英文 | 英文 |
| AI 评审 | 多厂商大模型 + 问题级修复建议 | 社区版不含 | 不含 | 不含 |
| 评分与门禁 | 五级评分，扣分 / 阈值页面自定义即时生效 | 有评分配置，评级模型官方定义 | 仅问题清单 | 无内置评分 |
| 重复代码 | CPD 式指纹，排除样板代码 | 内置 CPD（跨项目付费） | PMD 含 CPD | 无 |
| 依赖漏洞 | 内置 CVE 库 + 可选 OSV 增强 | 需插件 / 付费 | 无 | 无 |
| 架构分层 | 内置 | 付费规则引擎 | 不含 | 需自写 QL |
| CI/CD | Webhook + 状态回写 + MR/PR 评论 | 需插件对接 | 需自写脚本 | 限 GitHub 生态 |
| 报告邮件 | 自动发 HTML 摘要 + PDF 附件 | 需自行集成 | 不含 | 不含 |
| 信创数据库 | 达梦 / 金仓 / openGauss 等驱动内置 | 仅主流数据库 | 不适用 | 不适用 |
| 授权 | MIT | 社区免费、企业付费 | 免费 | 私有仓库需付费 GHAS |
| 语言覆盖 | Java | 多语言 | Java 为主 | 多语言 |

> 对比基于 2026 年 9 月各产品官方公开文档整理，仅供选型参考，不构成对任何产品的评价或背书；如有疏漏欢迎 Issue / PR 指正。

**适用场景**：多语言混合仓库与长期趋势治理选 SonarQube / CodeQL 覆盖更广；JArgus 以单 JAR 部署、中文原生、AI 可选、仅聚焦 Java 为特点，适合中小 Java 团队、内网隔离、信创项目与教学演示。

## 🚀 部署教程

### 环境要求

| 部署方式 | 要求 |
|----------|------|
| Release JAR | JDK / JRE 17+ |
| 源码构建 | JDK 17+ · Maven 3.9+ |
| Docker | Docker 20.10+ / Docker Compose v2 |

### 方式一：Release JAR（无需源码，最快）

从 Release 直接下载**可运行 Jar**（GitHub 与 Gitee 为同一个包，约 86MB，含 17 种内置数据库驱动）：

- GitHub Releases：<https://github.com/vfaner/jargus/releases>
- Gitee Releases：<https://gitee.com/super_rgh/jargus/releases>

```bash
java -jar jargus.jar
```

> 今后 Release 附件统一用固定名 `jargus.jar`（不带版本号），下载下来直接 `java -jar jargus.jar` 就能起，升级时在系统信息页点「一键更新」自动替换并重启。旧版本历史附件仍带版本号。

- 首次启动自动在当前目录初始化内嵌 H2 数据库（`data/`）、扫描快照与报告（`work/`）、日志（`logs/`），无需外接数据库；
- 访问 <http://localhost:8080>，默认账号 `admin / 123456`（登录后请尽快修改密码）；
- 换端口：`java -jar jargus.jar --server.port=9090`；
- 登录态为 24 小时 Cookie（`app.jwt-expire-hours` 可调）。JWT 签名密钥**未配置时每次启动随机生成**：重启 / 重新部署后需重新登录，也不存在可被伪造的公开默认密钥；仅当需要跨重启保留登录态（如长期持 Bearer 的脚本）时显式配置 `--app.jwt-secret=<密钥>`；
- 生产环境建议覆盖 AES 密钥：`--app.crypto-key=<新AES密钥>`（库内密码等敏感字段的静态加密）。

### 方式二：源码构建

```bash
git clone https://gitee.com/super_rgh/jargus.git   # 或 github.com/vfaner/jargus、gitcode.com/vfaner/jargus（AtomGit 镜像）
cd jargus
mvn package -DskipTests

# 启动（工作目录下自动生成 data/ 数据库、work/ 快照与报告）
java -jar target/jargus.jar
```

开发模式：`mvn spring-boot:run`（模板已关闭缓存，改完刷新即可）。

默认账号（首次启动自动创建，**请立即修改密码**）：

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | 123456 | 管理员（全部功能） |
| view | 123456 | 只读用户 |

### 方式三：Docker（推荐生产环境）

**docker compose 一键起：**

```bash
# 可选：复制 .env.example 为 .env，修改 JWT 与 AES 密钥
docker compose up -d --build

docker compose ps          # 查看状态
docker compose logs -f     # 跟踪日志
```

**docker 命令方式：**

```bash
# 构建镜像（多阶段：Maven 打包 → JRE 运行时）
./scripts/docker-build.sh 2.0.6
# 国内网络环境可用镜像站加速构建：
./scripts/docker-build-cn.sh 2.0.6

# 运行（数据卷持久化）
docker run -d --name jargus \
  -p 8080:8080 \
  -v jargus-data:/app/data \
  -v jargus-work:/app/work \
  -v jargus-logs:/app/logs \
  -v jargus-lib:/app/lib \
  -e APP_CRYPTO_KEY="your-16-char-key" \
  --restart unless-stopped \
  jargus:2.0.6
```

健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

**镜像特性**：内置 Noto CJK / 文泉驿中文字体（PDF 报告中文正常）、非 root 用户运行、自带 HEALTHCHECK、优雅停机。

**主要环境变量：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | 生产配置 |
| `APP_AUTH_ENABLED` | `true` | 是否开启登录鉴权 |
| `APP_JWT_SECRET` | 空（每次启动随机密钥） | JWT 签名密钥；留空时重启 / 重建后需重新登录（推荐），仅跨重启保留登录态才配置 |
| `APP_JWT_EXPIRE_HOURS` | `24` | Token 有效期（小时） |
| `APP_CRYPTO_KEY` | 内置占位值 | 敏感配置 AES 密钥（16 字符），**生产必须修改** |
| `APP_WORK_DIR` | `/app/work` | 代码快照 / 报告工作目录 |
| `APP_DRIVER_DIR` | `/app/lib/custom` | 自定义 JDBC 驱动目录 |
| `DATASOURCE_URL` | 容器内 H2 文件库 | 可覆盖为外部 MySQL / PostgreSQL 等 |
| `TZ` | `Asia/Shanghai` | 时区 |

数据持久化目录：`/app/data`（数据库）、`/app/work`（快照 / 报告）、`/app/logs`（日志）、`/app/lib`（驱动 JAR）。

### 快速上手

1. 登录后进入「新建扫描」，上传项目 ZIP 或直接粘贴代码（可用 `samples/SampleBadCode.java` 快速体验）；
2. 扫描完成自动跳转结果页：评分环、五级问题分布、门禁结论、逐条问题（可展开代码上下文），一键导出 HTML / PDF 报告；
3. 「质量门禁」页查看评分趋势与门禁结果，管理员可「自定义分值」调整每级扣分与阈值；
4. 可选增强：「AI 配置」接入大模型开启深度评审；「邮件管理」配置发件邮箱与收件人，扫描时勾选「邮件通知」自动推送报告；
5. CI 联动：在「CI/CD 集成」页创建触发器与令牌，流水线推送 Webhook 即可自动扫描、回写状态与评论，见下文详细指南。

## 🔌 CI/CD 集成指南

无需改源码、无需装插件：在平台侧建一个触发器，push / MR 事件即自动触发扫描；扫描完成按质量门禁结论回写 commit status 与 MR/PR 评论，流水线据此阻断合并。支持 GitHub Actions、GitLab CI、Gitee Go 与任意通用 CI。

### 第一步：新建触发器，拿到 Webhook 地址与密钥

「CI/CD 集成」页 → 「触发器」页签 → 「新建触发器」：

| 字段 | 填写说明 |
|------|----------|
| 名称 | 便于识别即可，如「核心服务-主分支扫描」 |
| 平台 | GitHub Actions / GitLab CI / Gitee Go / 通用，决定事件解析格式与回写 API |
| 平台地址 | 自动填官方云地址；企业自建版改成内网地址（如 `https://gitlab.company.com`） |
| 分支过滤 | glob 模式逗号分隔，如 `develop,release/**`；留空 = 所有分支；不匹配的事件直接跳过不扫描 |
| 仓库范围（可选） | `owner/repo`（GitLab 子组 `group/project` 亦可）；填写后仅接受该仓库的事件，防止同一 Webhook 地址被其他仓库误触发；留空 = 不限制 |
| 仓库账号 / 仓库令牌 | 仅**私有仓库** HTTPS 克隆需要（GitHub 账号可填 `x-access-token`、GitLab 可填 `oauth2`，令牌填 Personal Access Token）；**公开仓库留空**；令牌 AES 加密落库，编辑时留空表示不修改 |
| 跳过单元测试 / 分析测试代码 / 启用 AI 评审 / 扫描完成回评 MR/PR | 扫描行为开关：AI 评审消耗模型额度、回评会向平台写评论，按需开启 |
| 开启发信 / 通知收件人 | 勾选后每次触发扫描完成自动向所选收件人发送报告邮件（HTML 摘要 + PDF 附件）；需先在「邮件管理」启用一个发件邮箱并维护收件人 |

创建成功的弹窗会给出该触发器的 **Webhook 地址**与 **Webhook 密钥**：**密钥仅完整展示这一次**（库中加密存储），请立即复制保存；错过可在触发器行「重置密钥」重新生成（重置后需同步更新平台侧配置）。

### 第二步：在代码平台配置推送（二选一）

**方式 A：平台侧配置 Webhook（推荐，push / MR 自动触发）**

| 平台 | 配置路径 |
|------|----------|
| GitHub | 仓库 `Settings → Webhooks → Add webhook`：Payload URL = Webhook 地址，Content type = `application/json`，Secret = Webhook 密钥，勾选 `Pushes` 与 `Pull requests` |
| GitLab | 项目 `Settings → Webhooks`：URL = Webhook 地址，Secret token = Webhook 密钥，勾选 Push / Merge request events |
| Gitee | 仓库 `管理 → WebHooks → 添加 webhook`：URL = Webhook 地址，密码 = Webhook 密钥 |

GitHub 投递带 `X-Hub-Signature-256` HMAC-SHA256 签名，GitLab 带 `X-Gitlab-Token`，Gitee 带 `X-Gitee-Token`；本平台按平台约定自动校验，签名不匹配的请求直接拒绝（401）。

**方式 B：CI 脚本主动调用（任意流水线适用，含自托管 runner）**

推送 JSON 事件（以 GitHub 签名为例）：

```bash
BODY='{"ref":"refs/heads/master","head_commit":{"id":"<commit-sha>"},"repository":{"clone_url":"https://github.com/owner/repo.git"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "<Webhook密钥>" -hex | awk '{print $2}')
curl -X POST "<Webhook地址>?platform=GITHUB" \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: push" \
  -H "X-Hub-Signature-256: sha256=$SIG" \
  --data-binary "$BODY"
```

或跳过 Git 克隆、直接上传源码 ZIP（流水线已有产物、或 runner 访问不到 Git 仓库时适用）：

```bash
curl -X POST "<Webhook地址>/upload" \
  -H "Authorization: Bearer <Webhook密钥或访问令牌>" \
  -F "file=@source.zip" -F "branch=master" -F "commitId=<commit-sha>"
```

`Authorization: Bearer` 既可填 Webhook 密钥，也可填「访问令牌」页签创建的系统访问令牌（`X-Ci-Token` 头同样支持）；访问令牌可设过期时间、可单独吊销，适合分发给多条流水线。

### 第三步：查看扫描记录与结果回写

1. 事件到达后按「签名 → 仓库范围 → 分支过滤」顺序校验，通过才创建扫描记录；随后**异步克隆仓库**（私有库自动使用所配凭据，按记录隔离工作目录），打包 ZIP 走与页面扫描完全相同的流程；
2. 「扫描记录」页签查看每次触发的状态流转（PENDING → RUNNING → SUCCESS / FAILED），可按触发器筛选，点击跳转扫描结果页；
3. 扫描完成自动向对应 commit 回写 **commit status**：状态取质量门禁结论（success / failure，描述含评分与五级问题计数），扫描失败回写 error；平台侧配置分支保护「状态检查必须通过」后，门禁不达标的提交将无法合并；
4. 勾选「扫描完成回评 MR/PR」且事件携带 MR/PR 号时，额外在该 MR/PR 下评论：评分、门禁结论、五级计数、技术债与 Top 问题清单，附完整报告链接（链接域名取自 `app.webhook-base-url`）；
5. 勾选「开启发信」的触发器，扫描结束后同步向所选收件人推送报告邮件，送达状态在「扫描历史」页可见。

> 注意：方式 A 要求 Webhook 地址能被代码平台直接访问（内网部署需公网映射或内网穿透）；方式 B 的 ZIP 上传只要求 runner 能访问本服务，全内网环境亦可使用。

## 🤝 反馈

欢迎 Issue / Pull Request；QQ：817094 / 2912167928；QQ 群：426669837；微信：qqmu66。

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源，可自由使用、修改与商用。
