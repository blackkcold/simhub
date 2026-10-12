# Android 原生管理中心 · v0.15.5

## 架构

Android 管理端使用独立的 Jetpack Compose UI 与 Android Keystore 终端身份，
不再通过 WebView 承载短信、设备或设置页面。SIM 节点和管理端独立配置，
共用 Material 3 视觉组件与各自的单层导航。

- 管理中心：短信、设备、设置底部导航；窄屏单列、无重复顶部栏。
- 通信：同源 HTTPS Relay API，禁止跳转到其他 origin。
- 登录：Android Keystore P-256 ECDSA 签名 challenge，一次性挑战会话；
  服务器签发 HttpOnly、Secure、SameSite=Strict 的管理员 Session Cookie。
- Vault：通过已解锁网页管理员以 P-256 ECDH / HKDF-SHA256 / AES-256-GCM
  加密封装至新终端，不经 Relay 明文传送；手机仅持久保存 Android Keystore
  AES-GCM 保护的 Vault 封装。仍严格按密文 kid 获取当前、待轮换与历史 Node Key。
- 控制指令：由终端本地构建、加密，再提交现有命令与 ACK 状态机。
- 兼容：保留网页 PWA 与原有 SIM 节点，不覆盖节点的配对密钥。

## 首次连接

1. 在 Android 管理中心扫描网页的「连接管理手机」二维码或输入 HTTPS 域名。
   配置二维码仅保存公开地址，不包含管理员密码或 Vault Key。
2. Android 点击「申请管理终端授权」，生成随机单次 ECDH 公钥、
   Keystore 签名身份公钥与 6 位配对码。
3. 在已登录、已解锁 Vault 的网页管理端，打开「连接管理手机」，
   刷新待授权列表；比对手机和网页显示的 6 位配对码及终端指纹。
4. 完成原有管理员二次验证并批准。网页端通过 ECDH 加密 Vault，
   服务端仅转发密文和登记经管理员批准的公钥身份。
5. Android 收到加密封装，解密并由 Keystore 保护，再通过签名挑战建立会话。
   后续正常打开无需输入 Vault 密码。

## 安全边界

- 不得将 Master Vault 明文、Node Key 或管理员 Token 发给服务器。
- 只能在明确管理员批准时增加可信终端；扫码域名本身不授予任何权限。
- 管理端被盗、Keystore 损毁后应从网页撤销终端并重新配对。
- 撤销立即使该终端无法重新认证，且删除其会话；已经下载到终端的旧明文无法远程擦除。
- 短信明文仅用于当前应用内存渲染，不写入系统短信数据库或诊断日志。
- 密码轮换将使旧 controller 的管理员指纹失效，需要重新授权。
- 删除本机管理授权不影响 SIM 节点的短信或节点配对，但必须另在网页撤销服务器权限。
- 短信发送、Node Key 轮换、删除设备等敏感操作继续由 Relay 强制二次验证。

## 密钥与历史短信

显示已解密与解密失败条数，禁止将失败密文计入成功解密。
旧 v1 密文沿用原 Master Vault；v2 按 kid 匹配当前、待轮换及历史
Node Key，校验 AES-GCM AAD 与历史 SIM 的 Channel ID、Revision。
不存在历史密钥时不可能重新生成明文，需原 Vault 的恢复密钥和历史节点封装。

## 验收

- 检查 server/test_controller_auth.py（配对码、签名、挑战重放、撤销）。
- CI 编译 Android debug/release 并运行 JVM 测试。
- 手动测试 Android 10–17、折叠屏、不同网络、锁屏恢复、密钥删除、
  管理终端撤销、TOTP step-up、服务端升级和 145 条历史短信。
- 不得将尚未完成的设备端到端测试描述成已通过。
