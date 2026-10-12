# Android 统一工作区与同机授权 · v0.15.6

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
