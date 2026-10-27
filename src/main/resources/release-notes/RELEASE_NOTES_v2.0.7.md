# 百目 JArgus v2.0.7

新增企业统一认证，改造「检查更新」，并完成 9 项安全与稳定性修复。

![统一认证配置](https://gitee.com/super_rgh/jargus/raw/master/images/jargus_renzheng.png)

## 新增：企业统一认证（SSO）

- **两种接入方式**：票据回调（企业登录后回调 `/sso/entry?ticket=…`，校验接口返回用户名与管理员标记）或受信网关头（`X-Auth-User` / `X-Auth-Admin`，可附加共享密钥）。
- **角色规则**：管理员白名单或认证源的管理员标记为 ADMIN，其余为 VIEWER（只读）。
- **本地登录可关闭**：「系统管理 → 统一认证」可只保留统一认证入口；配置不可用时自动恢复本地登录，另有 `--app.sso.reset-local-login=true` 破窗参数。
- **安全**：只信任 TCP 直连的受信代理（IP / CIDR，默认仅本机），不采信 `X-Forwarded-For`；登录失败限流 20 次 / 15 分钟；共享密钥 AES 加密落库。

## 改进：检查更新

- **双源兜底**：GitHub 不可达时自动改查 Gitee，设置页标注来源。
- **同版本安装包重发提醒**：版本号相同但 jar 不同（SHA-256 → 附件大小 → `Content-Length`）时提示，可忽略，对方再次上传后重新提醒。
- 管理员导航圆点提示；远端发布说明只保留安全协议链接。

## 安全加固

- 快照复制与扫描不再跟随符号链接，避免仓库内链接把本机敏感文件读入快照。
- 问题上下文只允许读取快照目录内的文件，`../` 与绝对路径不再生效。
- CI Webhook 验签直接对请求体原始字节计算 HMAC。
- `/api/**` 不再因后缀与静态资源相同而免登录。
- 登录回跳地址按 URL 解析比对同源，只回跳 path / query / hash。

## 稳定性

- 分页页码下限 1、每页上限 200，避免超大 `size` 全表加载。
- 单元测试超时结束整棵进程树，不再残留 surefire JVM / Gradle daemon。
- 驱动上传显式关闭文件流；远端登录 `configId` 非法返回 400。

## 升级须知

- 新增 `sso_config`、`update_artifact_dismissal` 两张表，旧库首次启动自动补建，覆盖 jar 即可。
- 网关头模式需把网关出口地址加入 `app.security.trusted-proxies`（默认 `127.0.0.1,::1`）。
- 检查更新开关：`app.update-check.enabled` / `timeout-ms` / `match-artifact`。
- 加密密钥、Cookie、CI Token 与 v2.0.0 起一致。
