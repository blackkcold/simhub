# Android 原生管理控制台与通行密钥

## 界面与短信来源

- APP 顶部切换「SIM 节点」与「管理中心」，两种模式均使用 Material 3。
- 管理中心使用同一条原生底栏：「短信」「设备」「设置」，不再显示原 PWA 悬浮导航。短信列表与会话由 Compose 绘制。设备/设置仍复用受限 HTTPS WebView，避免重写现有安全敏感操作。
- SMS 密文由已授权的 WebCrypto Vault 在管理端 WebView 内解密，再由带 allowlist 的 AndroidX WebMessageListener 传递最多 240 条短信的**已解密、无密钥**快照。Android 不保存到磁盘、系统短信数据库或日志。Vault 锁定会主动清空。
- 在原生短信页选择「发送」会打开原有 PWA 安全发送表单（保留节点/SIM 选择、确认、step-up），但仍只有 Android 底栏。暂不将远程发送权限移入原生短信 Provider。

## Android 系统通行密钥启用条件

1. 安装官方签名 APK 或自签名后单独配置证书关联。
2. 使用有效证书的 HTTPS 管理域名。管理域名必须能公开访问 `/.well-known/assetlinks.json`。
3. WebView 提供 `WEB_AUTHENTICATION` 功能，Android 上有可用的 Credential Manager/通行密钥提供者，并已设置设备屏幕锁。
4. 管理后台中已经注册可用的 Passkey。首次注册仍依照现有管理员 session 和 step-up 流程，Vault 密码仍是独立的。

默认官方 Release 签名证书由仓库 Release 工作流固定校验，服务端在 `/.well-known/assetlinks.json` 自动生成 `delegate_permission/common.get_login_creds` 关联。管理域名与 Android 应用必须完全对应。

自建 APK 改用其他签名时：执行 `apksigner verify --print-certs your.apk` 取 **Signer certificate SHA-256 digest**，将每两位 HEX 用冒号分隔，填入私有 `.env`：

```env
SIMHUB_ANDROID_CERT_SHA256=AA:BB:...:FF
```

支持以逗号分隔多个有效指纹以做签名轮换。空字符串将禁用动态关联端点。该值是公开签名证书指纹，**不是** APK 文件的 SHA-256、签名私钥或 Admin Token。

自查：

```bash
curl -fsS https://your-admin-domain/.well-known/assetlinks.json
```

应返回 application/json、HTTP 200、正确 package_name `com.blackkcold.simhub` 及签名指纹，不应从节点访问域名返回。使用修改后的 .env 需要重启 Relay 容器。

## 验收建议

- 本机纯控制台不请求 SMS 读取权限；节点常驻服务在管理界面切换时不受影响。
- Android 系统通行密钥（注册、登录、敏感操作确认）应弹出系统凭据选择器；没有支持则自动允许密码+TOTP 路径，而不是假称成功。
- 管理 SMS 分组、SIM 来源与时间、OTP 复制、滚动加载；锁定 Vault/退出后内存不再留明文。
- 管理端与节点端交替切换 20 次、横竖屏与折叠屏、断网/恢复、Android 16/17 上验证系统行为。
