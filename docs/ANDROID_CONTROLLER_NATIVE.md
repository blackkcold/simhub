## 管理中心短信与设备 UX 更新

- Android 首次载入最近 50 条 **事件**时初始化历史分页游标和最新事件序列水位；后续增量加载与历史翻页使用不同游标，历史受 Relay 保留数据、原手机历史同步范围和有限端侧内存窗口约束。
- Compose 管理短信页面移除重复大标题和常驻过滤层。全局管理短信顶栏滚动收起/向下出现；搜索、OTP 与 SIM 来源过滤按需展开，底栏保留。
- 短信来源展示友好 SIM 名称，不暴露 Channel ID/Revision，但加解密、唯一来源与会话键保持不变。
- Android 原生设备详情支持重命名、移动数据备用策略、Relay 短信清理、撤销授权及强制删除；危险操作依赖 Relay 管理员二次验证和确认。
- Android HttpURLConnection 使用 POST /api/v1/devices/{id}/manage（仅 rename/revoke，均身份验证，revoke 额外要求 step-up）；Web PATCH 接口未变。
- 原生设置展示共享池成员/密钥轮换状态、近期服务器审计元数据；初次节点加密注册、Passkey 和共享池密钥授权/轮换仍在系统 HTTPS 浏览器中执行。
- Relay `SIMHUB_EVENT_RETENTION_DAYS` 当前默认 `0`（保留已接收的加密历史，不按天自动删除）；设置正整数才启用按天清理。清理后的事件无法仅通过管理端分页恢复，需要原设备具备可恢复的短信历史，并经显式历史恢复操作。真机及 Android 10–17 OEM/折叠屏仍需额外验收。

# Android 原生工作区与同机授权（v0.15.8）

## 0.15.8 数据完整性与任务闭环

- 旧版 Android 配对请求走短期同 IP 协议 v1，新版明确要求独立随机凭证的 v2。兼容模式于 2027-02-11 UTC 结束；已授权设备无需重新配对。
- 增量事件按 seq 升序、每批最多 50 条，每轮最多 8 批；积压留待后续刷新。原生 Inbox 完整接收后才推进游标；失败可安全重读。编码 JSON 超限自动分片。
- 历史较旧页、返回最新页、增量刷新分离。深度历史采用可移动的 1200 条内存窗口；历史分页不受初始新消息截断影响。
- Relay 已持久化的最近 50 条命令记录用于任务历史；应用可持续查看排队、领取与最终 ACK，跨越 Eco 两分钟延迟。
- 管理中心使用 FLAG_SECURE 保护截图和系统最近任务预览。自动后台请求不再延长管理员闲置会话。
- SMS 发送改为全屏 Compose 编辑页；关闭编辑页后草稿、SIM 选择和幂等 ID 保留，避免未知状态重试被误认为新任务。
- 仍需真实 Android 设备验收；认证绑定的硬件 Keystore 密钥迁移、Passkey 真机自动化与服务端全面模块化尚不能认定完成。

## 0.15.7 审计修复

- 管理控制器配对轮询改为独立随机凭证（服务器只存 SHA-256），手机以 Keystore AES-GCM 封存；移动数据 / Wi-Fi 切换不再依赖固定来源 IP。旧的尚未完成配对请求沿用原 IP 限制直至过期。
- 原生管理短信使用服务端事件序号增量获取新加密事件，历史分页追加并按事件稳定 ID 去重，提供按 Node ID / Channel ID / Revision 来源过滤。已加载内容限定最多 1200 条于内存，不承诺服务器端可对密文全文检索。
- 原生发送根据服务器实际 TOTP 能力选择二次认证类型；敏感认证值在输入框中掩码显示。
- 命令状态通过 Relay 的持久 ACK 查询呈现 queued/dispatched/submitted/sent/delivered/succeeded/failed/rejected/expired，不把入队解释为完成。
- 本机授权可选择先在 Relay 撤销再清除 Keystore，或明确选择仅删除本地密钥；认证被撤销时清理当前原生短信视图。
- 可选系统 BiometricPrompt / 锁屏身份验证门禁：返回后台即锁定并擦除原生已展示短信列表，重新进入须由系统验证后重新读取数据。**这是 App 层访问门禁，不是每次 Vault 解密都经过用户认证绑定的 Keystore 密钥**；已有 AES wrapping Key 仍为 setUserAuthenticationRequired(false)，不能将此功能宣传为硬件密钥级保护。需要后续设计具备无损迁移和恢复流程的用户认证型密钥重封装。
- 进一步改进发送窗口、设备详情滚动空间和主要中英文 UI 标签。真实 OEM 生物识别提供方、折叠屏和电台短信收发必须真机测试。



## 单一 Compose App Shell

Android SIM 节点与原生管理中心共用 **HubApp** 顶部品牌栏、SIM 节点／管理中心
模式切换、四个标准底部导航入口及大屏 NavigationRail。两种模式共用
Material 3 的 HubCard、StatePill、InfoRow 及自适应布局：

| 位置 | SIM 节点 | 管理中心 |
| --- | --- | --- |
| 首页 | 本机节点状态 | 管理连接、节点统计与快捷操作 |
| 短信 | 本机短信 | 经客户端 E2EE 解密的远程会话 |
| 设备 | 本机 SIM | 已连接 SIM 节点 |
| 设置 | 节点配置 | 管理空间及授权、诊断 |

ControllerWorkspace 是**纯内容组件**，不再自行创建 TopAppBar、
NavigationBar、NavigationRail 或 WebView。手机只有一套底栏，大屏只有一套侧边导航；
切换模式保留各自的分页状态。远程历史短信来源继续使用 Channel ID 和 Revision，
并通过匹配 Node Key 的 kid 来判断能否解密。

## 首次授权：同一台手机即可完成

选择 **通过手机自带浏览器完成管理员验证**，而不是依赖 Android WebView
代理通行密钥，也不自建一个不完整的原生 Passkey 服务端验证协议。
这条路径是刻意选用用户要求的“跳转网页”模式，直接复用经过验证的网页
WebAuthn、TOTP、管理员会话及 Global Vault 授权逻辑。

1. Android 扫描配置二维码或输入可信 HTTPS Relay 地址。二维码**不含密钥**。
2. 点击「申请管理终端授权」，生成 Keystore P-256 身份、短期 ECDH 公钥和
   6 位配对码；临时 ECDH 私钥连同配对申请通过本机 Keystore AES-GCM
   加密封装，并设置 10 分钟过期时间，允许从浏览器回来时恢复流程。
3. 在同一台手机上点击「在本机浏览器认证并批准」，访问当前已验证的
   HTTPS 域名根路径，附带**只有界面导航作用**的 `?controller=local`。
   在浏览器登录管理员，可选用**已注册的 Passkey/系统生物识别**，
   或按服务端现有要求使用管理员密码及 TOTP。
4. 在本机浏览器解锁**现有唯一 Global Vault**，进入「连接管理手机」，
   粘贴本机显示的 6 位配对码，核对终端指纹并通过现有二次验证。
   浏览器对已有 Vault Master Key 做 ECDH/HKDF/AES-GCM 加密授权；
   Relay 只收发封装数据、不取得明文。
5. 手动点击「返回 SIM Hub 管理中心」（`simhub://controller?resume=1`），
   它**不包含账号、密码、密钥、配对码或会话 Token**；Android 检测到
   已保存的可信管理域名后只切换本机管理工作区，并通过原有
   `pair/{requestId}` 接口接收单次授权。
6. 后续使用 Keystore P-256 签名登录，验证 Global Vault ID/keyId，
   不再重复创建 Vault。服务器撤销或 Vault 身份不匹配时拒绝连接。

> 设备指纹/Passkey 的用户验证发生在**同一台手机的浏览器**中；
> Keystore ECDSA 是后续设备身份认证，两者不是一回事。
> **本版本不声称 Android 原生 Credential Manager 已集成，也不声称
> 每次打开 App 均需生物识别**。这需要独立的 Keystore 用户认证绑定与
> 真机兼容性验证，不可以只显示一个假指纹按钮。

## 安全边界

- 不允许跳转 HTTP 或非当前可信 HTTPS Origin；管理地址严格规范化。
- 浏览器 Passkey 由实际部署域名的 WebAuthn RP 验证，不绕过 Origin / RP ID 校验；
  不要求额外的 Android Asset Links，因认证发生在正常 HTTPS 浏览器。
- 未创建 Passkey 的管理员必须使用已有合规认证方式；单独指纹解锁
  **不能代替**初次管理员身份确认或恢复丢失的 Vault 密码。
- 配对码须经服务器匹配且请求必须仍有效，服务端依赖登录会话及敏感操作
  二次验证。浏览器和手机分开持有临时 ECDH 交换私钥。
- Global Vault 仍是唯一的 Master Key；每个 SIM Node 的独立 Key 和历史
  `v1/v2` 消息密文不变。不迁移、覆盖或重新初始化原有 Vault。
- 本地重置不等于撤销服务端授权；用户应在网页后台撤销管理设备。
  撤销无法远程擦除已下载的旧明文。
- 本机控制器敏感操作仍由 Relay 执行 TOTP / step-up 验证。
- 当前本地 Vault 封装仍采用 Android Keystore AES-GCM；它**尚未**
  改为对每次解密强制要求生物识别的 Keystore Key。不能把首次网页指纹验证
  误当成持续的 Vault 本地生物识别保护。

## 验收要求

CI 必须通过 Android 编译与 JVM 测试、服务器和浏览器回归，以及
`scripts/test_controller_same_phone.py` 架构与同机路径安全检查。
上线前还需实机验证 Android 10–17、多供应商浏览器／Passkey 提供方、
竖屏／折叠屏、后台进程重建、配对过期、管理员 TOTP 和 Vault 密码恢复、
已授权终端删除和服务端撤销。未验证项目不得宣称已测试成功。
