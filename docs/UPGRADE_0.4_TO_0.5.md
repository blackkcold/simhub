# SIM Hub v0.4.0–v0.5.0：部署、界面及功能迁移指南 / Upgrade guide

适用范围：已有 SIM Hub 管理端、Android Node 与 Linux/DJI Modem Node 的滚动升级。Release：[`v0.4.0`](https://github.com/blackkcold/simhub/releases/tag/v0.4.0) → [`v0.5.0`](https://github.com/blackkcold/simhub/releases/tag/v0.5.0)。

## 1. 新增功能与入口 / What's new

| 功能 | v0.4.0 | v0.5.0 |
|---|---|---|
| 新装部署 | Linux `scripts/setup.py`，双域名、DNS 前置检查，Caddy 托管 HTTPS 或外部代理 | 沿用 v0.4.0 向导，无新增 DNS/端口要求 |
| 登录 | 管理员用户名、Admin Token/TOTP、Passkey 注册/登录/敏感操作验证 | 管理员 Session 与 Vault 默认 **8h 空闲 / 24h Session 最长期限**；同标签页刷新可恢复活动 Vault |
| 短信页面 | 会话收件箱、直接回复、右上角新建短信、响应式控件间距 | 刷新不会反复要求有效 Vault 密码；SIM 电话号码便于区分来源 |
| 设备页面 | 在线状态、SIM 通道选择与密钥维护 | SIM 电话号码（读不到可手填）、充电/网络、最近或更早 100 条独立同步、诊断结果 |
| 远程诊断 | Android 开发者模式日志与 ZIP 导出 | Web 诊断窗口展示 Node Key 加密的历史健康检查结果（非完整设备日志） |
| 网络接管 | 常规网络自动恢复、后台同步 | 系统默认数据 SIM 接管状态检查、Android 网络设置快捷入口；不强行切换默认数据卡 |

**两类凭据要分开：** Passkey 用于建立/提高管理员会话；Vault 主密钥与 SMS 内容不上传 Relay，Passkey 本身不能在其他浏览器恢复 Vault。活动刷新使用本标签页加密缓存，主动锁定、会话过期后失效。不要把浏览器端加密缓存当作抵御 XSS、被控制的浏览器或本机入侵的硬件隔离。

## 2. 新服务器部署 / Fresh install

Linux：Docker Engine + Compose plugin + Python 3、TCP 80/443、两个不同的可访问域名，`admin.example.com` 和 `node.example.com`。配置两个 DNS A 记录；AAAA 记录必须可访问。**不要向公网放行 8787**。

```bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
```

向导检查依赖、域名解析和端口，生成私有 `.env`、管理员高熵 Token 与 TOTP Secret、Caddyfile，启动 Compose/Caddy。DNS **需用户在自己的服务商控制台添加**，向导不会替你修改解析。已有反向代理改为：

```bash
python3 scripts/setup.py --mode external \
  --admin-domain admin.example.com --node-domain node.example.com
```

外部模式需要自行完成两个 HTTPS 域名的反代、`X-Forwarded-For` 防伪处理与内部健康端点隔离。切勿把“自动初始化”理解为自动注册域名或修改 DNS。详见 [部署指南](DEPLOYMENT.md) 与 [一键部署中文版](QUICKSTART.zh-CN.md)。

## 3. 旧环境升级 / Upgrade in place

1. **先备份**：`./scripts/backup.sh` 生成一致性 SQLite 备份；单独离线备份 Vault Recovery Key、TOTP 和管理员恢复 Token，记录 Android 原 APK 签名来源。
2. **核对**：保留既有管理域名、节点域名与对应证书。Passkey 注册绑定 RP ID；管理域名变更需迁移规划。旧版**单域名**部署不能盲目执行双域名 `--upgrade`：先完成 DNS/代理隔离迁移、检查 `.env`，再升级。
3. **先升 Relay/PWA**（已经按双域名初始化且使用托管 Caddy）：

```bash
git pull --ff-only
python3 scripts/setup.py --upgrade \
  --admin-domain admin.example.com \
  --node-domain node.example.com
```

使用现有 Nginx/Caddy/Traefik 时继续传入 `--mode external`；向导不会覆盖已有 `.env`、`Caddyfile`、数据库或 Node Key。迁移不应重新生成主 Vault。
4. **再升 Android**：从 [v0.5.0 Release](https://github.com/blackkcold/simhub/releases/tag/v0.5.0) 安装相同签名的 APK，保留设备注册和本地数据；默认短信应用、SMS/SIM 权限及后台保活在系统升级后需复核。
5. **Linux/DJI Modem 节点**：已有通道、Node Token、Node Key 可保留；ModemAgent 是否支持某操作取决于实际 Adapter 能力，Android 专属 SMS 历史分页和数据卡设置不在 Modem 上显示。

## 4. 首次使用新界面 / UI walkthrough

- **短信 / Inbox**：按照联系人形成会话；选择会话可直接回复，右上角“+ 新建短信”选择设备和 SIM 发送。
- **设备 / Devices**：SIM 号码自动识别失败时点击“设置号码”（仅当前浏览器以 Vault 加密保存）。可查看电量、充电、连接类型、队列状态。
- **同步策略**：点击“同步最近 100 条”重新扫描手机最新短信；“同步更早 100 条”沿历史游标前进；收件箱“加载更早短信”只读取已经同步的服务端历史。0 条、权限失败、命令排队与上传成功不是同一状态。
- **诊断**：设备按钮打开诊断结果窗口并发起检查；按“刷新诊断结果”获取新的加密结果。完整开发日志仍需在 Android 开发者模式下本地查看/导出。
- **网络**：如果 Wi-Fi 中断需要远程保持在线，先在 Android 本地设置中选择**默认数据 SIM、启用移动数据**；Web“备用数据 SIM”用于选择/验证期望卡片。普通第三方 APK 不能无特权地强制切换系统默认数据 SIM。
- **登录**：同一标签页中的活动登录刷新后可恢复，管理员 Session 默认 8h 无真实操作到期，绝对不超过 24h；退出或主动锁定应要求解锁。

## 5. 验收与回滚 / Acceptance and rollback

验收：两个域名 HTTPS 可用但 `/healthz` / `/readyz` 不公网暴露；管理员用户名/Passkey 登录与 Vault 刷新恢复；双卡实际号码或手动设置；最新/更早 100 条短信按批完成；诊断可返回；断 Wi-Fi 时如系统启用移动数据，网络按 Android 的默认数据卡回退。测试 SIM 换卡时的安全拒绝和旧短信归属标注。

回滚前备份最新 SQLite 与恢复材料。不要直接把已迁移数据库交给旧版本程序写入；优先部署先前保留的镜像/代码并恢复**与其版本对应**的数据库备份。Android APK 不能保证无损降级，必要时先保留应用数据并用同签名、兼容的升级版本修复。**CI 和模拟器/浏览器测试不代替真实双 SIM 硬件、Doze 与运营商短信验收。**

## English quick reference

For a new install use `python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com`. For an existing proxy add `--mode external`; you must configure TLS/Host routing yourself. Never publicly expose port 8787. Back up the SQLite relay database and Vault recovery materials before updates. Upgrade Relay/PWA first, followed by a consistently signed v0.5.0 Android APK. Keep management RP ID, Node Keys and enrollments unchanged. The web UI now includes direct SMS replies, Passkeys, same-tab active Vault recovery, encrypted SIM numbers, distinct recent/older 100-message sync controls, encrypted diagnostics and an Android **OS-managed** default-data-SIM fallback policy.
