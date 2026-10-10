<div align="center">

<img src="docs/assets/brand-banner.svg" width="100%" alt="SIM Hub · 私有 SMS / SIM 管理中心" />

# SIM Hub

**将 Android 手机与受支持的蜂窝 Modem 变成私有、自托管的短信和 SIM 管理中心。**

多 SIM 收发短信 · 验证码提取 · 加密中继 · Web / PWA 管理

[**简体中文**](README.zh-CN.md) · [English](README.md)

[![CI](https://github.com/blackkcold/simhub/actions/workflows/ci.yml/badge.svg)](https://github.com/blackkcold/simhub/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/blackkcold/simhub)](https://github.com/blackkcold/simhub/releases/latest)
[![License](https://img.shields.io/github/license/blackkcold/simhub)](LICENSE)

**[下载 Android APK](https://github.com/blackkcold/simhub/releases/latest)** · **[部署教程](docs/QUICKSTART.zh-CN.md)** · [安装说明](docs/INSTALLATION.md)

</div>

## 界面预览

**Web / PWA · 短信会话与直接回复**

![桌面端短信会话与回复界面示意](docs/assets/ui-desktop.svg)

**Web / PWA · 设备、SIM 与诊断管理**

![设备和 SIM 状态、短信同步与诊断界面示意](docs/assets/ui-devices.svg)

<table>
<tr><th>手机端 Web / PWA</th><th>Android SIM Node</th></tr>
<tr><td width="50%"><img src="docs/assets/ui-mobile.svg" alt="移动端短信会话界面示意" width="100%"></td><td width="50%"><img src="docs/assets/ui-android.svg" alt="Android 设备节点界面示意" width="100%"></td></tr>
</table>

**Android 折叠屏展开态 · 双栏短信工作区**

![折叠屏 Android 双栏短信界面示意](docs/assets/ui-android-fold.svg)

<sub>均为依据当前 UI 绘制的模拟数据示意图，非真实短信或已登录设备截图。</sub>

## Android 双角色与分阶段登录

Android APK 同时提供 **SIM 节点（原生 Compose）** 和 **管理控制台（受限 HTTPS WebView）**。首次安装选择节点、控制台或双角色；控制台不需要短信权限，节点的后台同步不依赖控制台登录。管理控制台顶部可配置多个 HTTPS 管理空间，各空间的登录 Cookie、Vault 和 IndexedDB 以站点 Origin 隔离。Passkey 在部分 Android WebView 中可能不可用，此时使用账号密码 + TOTP，或选择在系统浏览器打开管理域名。

Web/PWA 使用 **账号密码（Argon2id）→ TOTP 弹窗 → 按需 Vault 解锁** 的独立阶段。旧部署的 Admin Token 仍然保留作为应急登录/初始化方式；管理员登录后可在「设置 → 账户与安全」设置新密码，原始 Admin Token 不会被自动删除。密码修改会撤销所有管理员会话。管理员会话凭据仍使用 Secure/HttpOnly Cookie。

Vault 保持原本的端到端加密。可选「在本设备有效期内免重复输入 Vault 密码」，仅通过受保护的本地 WebCrypto Key 解开缓存中的主密钥；不会发送给服务器。手动锁定、退出、会话失效或清除凭据时必须销毁缓存。**该缓存不等于硬件 Keystore，也不具备抵御恶意同源脚本读取已解锁数据的能力。**新设备必须用合法的 Vault 恢复密钥导入旧 Vault；新建 Vault 不能解密原有密文。

首次配置独立登录密码可在服务器运行 `python3 scripts/hash_admin_password.py` 生成 Argon2id PHC Hash，并写入私有的 `SIMHUB_ADMIN_PASSWORD_HASH` 环境变量；也可先使用原 Admin Token 登录后在 UI 中设置。更新后旧 Node Key、SMS 密文和设备配对协议不变。

## 在线更新

Android v0.12.2 起可直接从 GitHub 正式 Release 检查、下载及安装更新，**无需先配对或连接 Relay**。更新仍核验版本号、SHA-256 与原 APK 签名。远程解绑期间会显示独立的待确认/异常恢复状态，可重试确认或经明确风险提示后仅清理本机配对；原始系统短信不受影响。


Web「设置 → 系统版本」支持 GitHub Release 检测、忽略及一键部署**经 Sigstore 签名验证、Digest 固定的 GHCR 镜像**。须先迁移至 **Rootless Docker**，安装可信 Cosign，并由专用非 root 用户执行 `bash scripts/install-updater.sh`。旧 root 更新服务须停用。Web 容器不接触 Docker Socket；升级前备份 SQLite，健康检查失败回滚上一镜像。详情见[迁移说明](docs/DEPLOYMENT.md)。Android「设置 → 高级工具」支持检查更新、忽略版本、自动下载与通过系统确认安装签名 APK。Android 16+ 可选择开启服务器远程 APK 更新，低版本仅可从开发者选项强制开启测试，不保证免确认安装；更新后自动尝试恢复原有 Relay。详见 [远程 OTA](docs/REMOTE_OTA.md)。参见 [部署](docs/DEPLOYMENT.md) 与 [Android](docs/ANDROID_SETUP.md) 指南。

## 核心功能

| 能力 | 说明 |
|---|---|
| **短信与验证码** | 无需接管系统信息的伴随模式，或可选完整默认短信模式；多 SIM 收发与 OTP 识别（Android 17 非默认模式可能延迟验证码） |
| **Android 原生界面** | 唯一 Material 3 自适应四 Tab 界面，折叠屏双栏、SIM 标签、完整高级工具与脱敏诊断 |
| **设备与 SIM** | Android / Linux 蜂窝 Modem 节点；SIM 号码、信号、充电、Wi-Fi 与在线状态 |
| **共享短信池（主动开启）** | 已授权的 Android 设备通过加密共享池同步短信，最近 100 条、分页及来源 SIM 尾号 |
| **历史与诊断** | 最近 100 条重扫、更早 100 条分批同步、Web 滚动自动分页、脱敏健康诊断 |
| **设备配对** | 设备页三步向导支持「扫码或复制注册链接」及「复制节点地址 + 八位配对码」，核验指纹后授权 |
| **隐私与登录** | Node Key 端到端加密、用户名 / TOTP / Passkey、Vault 活动会话刷新恢复 |
| **离线恢复** | 本地队列、断网后重试、常驻中继、后台任务恢复 |
| **Android 能耗管理** | 极致省电 / 智能均衡 / 实时优先；短信事件触发、命令检查与低频维护分离（[说明](docs/ENERGY_POLICY.md)） |

Android 矢量图标原始 SVG 位于 [design/icons](design/icons)，APK 使用同路径的 Android VectorDrawable，并在 CI 中校验一致性。

## 工作原理

```mermaid
flowchart LR
  A["SIM / eSIM\nAndroid 或 Modem"] -->|"加密事件"| B["自托管 Relay\n仅存储密文"]
  B -->|"HTTPS"| C["Web / PWA\n本地解密与回复"]
  C -->|"加密短信指令"| B
  B --> A
```

## 一键部署

需要 **Linux + Docker Compose + Python 3**、两个指向服务器的 DNS 域名（例如 `admin.example.com`、`node.example.com`）及 TCP **80/443**。不向公网开放 **8787**。

```bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
```

向导检查 DNS / Docker / 端口，生成管理员 Token、TOTP、私有 `.env`，部署 Relay 与 Caddy HTTPS；**DNS 记录需自行在域名服务商处设置**。已有 Nginx / Caddy / Traefik 时，给命令增加 `--mode external` 并自行配置双域名 HTTPS 反代。

然后打开管理域名，登录并创建/导入本地 Vault；下载正式签名 APK，通过扫码或 Android 发码完成配对并授权读取、接收和发送短信；**不必将 SIM Hub 设置为默认短信应用**，完整接管模式为可选项。

## Android 后台能耗管理（v0.13.0）

Android「设置 → 运行与短信同步」现以三张大卡片切换能耗模式。v0.13.1 智能均衡在前台中继正常运行时，屏幕活动期约 45 秒、待机期约 2 分钟检查远程命令；健康维护独立保持低频。短信入库优先于网络上传，弱网及退避期间仍尽可能安全进入本机加密队列。新增“立即检查命令并同步短信”和命令拉取失败诊断。极致省电模式下远程指令可能延迟，Web 发送短信时会提示。后台调度仍受系统休眠、OEM 限制及 Android 17 OTP 可见性限制，**不保证秒级送达**。参见[能耗与可靠性说明](docs/ENERGY_POLICY.md)。

## 系统兼容实验室（v0.12.0）

Android App 的「设置 → 系统兼容实验室」是**独立的高级可选页面**：支持官方 Shizuku SDK 分步授权和服务状态检测、Android 13+ 自管理 Companion Device 系统确认关联、机型及系统 SDK 检测、短信权限和只读 OTP AppOp 查询、单次 SMS Provider 探测、近期加密补扫，以及针对 vivo/OPPO 等设备的电池优化设置入口。高级操作记录为独立的脱敏审计日志，可在诊断 ZIP 中导出。**不自动停用系统短信、关闭验证码安全保护或修改敏感 AppOps。Shizuku/CDM 不保证实时 OTP。**详见[系统兼容实验室](docs/COMPATIBILITY_LAB.md)。

## 短信同步故障诊断（v0.12.1）

Android「设置 → 运行与短信同步」显示待上传数、已上传回执、上次上传数量和失败原因；「系统兼容实验室」提供短信广播、Provider 变更、滚动补扫及 Relay ACK 的时间点。PWA 会提示无法解密的具体类别，并修复了旧时间短信新上传后被隐藏的问题。**队列 0 可能代表已经上传并确认；原 Vault / Node Key 丢失时，不能靠服务器还原密文。请勿为了排障删除原始短信或重置配对。**详情：[排障手册](docs/COMPATIBILITY_LAB.md)。

## 非默认短信模式

在系统允许授权 `READ_SMS`、`RECEIVE_SMS` 与 `SEND_SMS` 的情况下，SIM Hub 可以保持 vivo 等原厂「信息」为默认应用，通过 `SMS_RECEIVED` 广播触发系统短信数据库的加密同步，并通过 6 小时滚动补扫恢复延迟可见的短信。**Android 17 对部分非默认应用的受保护验证码有约 3 小时访问延迟**，本项目不绕过系统保护；厂商 ROM 和安装来源也可能限制短信权限。需要实时获取所有验证码时，此模式不能保证满足要求。详见 [Android 设置](docs/ANDROID_SETUP.md)。

## 使用边界

Relay 无法读取短信和 Vault 明文；Passkey 用于认证，不代替 Vault 恢复密钥。活动会话默认 **8 小时无操作过期、24 小时绝对到期**，同一标签页刷新可恢复解锁状态。移动数据回退由 Android 使用**系统预设的默认数据 SIM**，普通 APK 无法强制切换数据卡。**不提供电话功能；MMS 媒体收发不完整。**

## 文档

[一键部署](docs/QUICKSTART.zh-CN.md) · [完整安装](docs/INSTALLATION.md) · [Android 设置](docs/ANDROID_SETUP.md) · [安全设计](docs/SECURITY_ARCHITECTURE.md) · [共享短信](docs/SHARED_SMS.md) · [架构与协议](docs/ARCHITECTURE.md) · [开源协议](LICENSE)
