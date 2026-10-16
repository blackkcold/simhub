# P2P 独立测试包与验证指南

对象：`develop` Dev 实验发布线。独立测试包使用专属 applicationId 并禁用正式版自更新；Stable `main` 不得包含实验性 P2P。

## 本轮验收要点

本轮把公网 PeerJS 信令与 WebView 引擎整体退役，改为：

1. **原生 data-only RTC 引擎**：`PeerNativeRtcTransport` + 固定版本 `io.github.webrtc-sdk:android`；无 WebView、无内置脚本、无远程代码；不新增音视频采集权限。
2. **中心辅助信令**：复用已注册的管理中心交换端到端加密 SDP/ICE；信令有独立 domain 与子密钥，业务密文与信令密文互不可解；信令不进入 `PeerRpc.dispatch`。
3. **共享 pinning 客户端**：节点与控制器两条凭据链路的 JSON 请求都走同一个受测 OkHttp 工厂；精确 origin + ≥2 枚 SPKI pin，无匹配 pin 即失败关闭。
4. **受控配置注入**：可信中心描述由 Gradle 生成到构建目录，公开 CI 用测试证书派生的假配置走同一套生成/加载/校验代码。

信令就绪 ≠ 数据通道在线；路由以真实 selected candidate pair 判定；未授权时不使用 TURN。

## 本机可运行的验证命令

```bash
# 1) 服务端信令与既有连接服务（本地 venv）
PYTHONPATH=server python3 -m unittest -v server.test_peer_connect
PYTHONPATH=server python3 -m unittest -v server.test_peer_signaling server.test_peer_signaling_http

# 2) 结构不变量 + 服务端 + JVM 沙盒（一条命令覆盖主要不变量）
python3 scripts/test_p2p_sandbox.py          # 产出 p2p-sandbox-results.json

# 3) 配置泄漏静态扫描（禁止提交生成的描述、pin 字面量、非保留域名）
python3 scripts/test_center_config_leak.py

# 4) Android 单元测试（JVM：信任模型 + 真实 TLS pinning 负向测试）
gradle -p android -PcenterConfigFake=true :app:testDebugUnitTest
gradle -p android :app:testDebugUnitTest --tests '*CenterTrustTest' --tests '*SecureHttpTrustTest'

# 5) 受控打包负向测试（需要 Gradle；用合成 canary，不需要真实 Secrets）
python3 scripts/test_center_config_packaging.py
```

`scripts/test_p2p_sandbox.py` 会断言：WebView 资产与类不存在、`android.webkit` 未出现在应用源码、`0.peerjs.com` 未出现、依赖版本精确固定、`SecureHttp`/`CenterTrust`/`PeerSignalingProtocol` 的关键不变量存在、`test_vectors/signaling-v1.json` 存在且 domain 正确。

## 模拟器与 CI 验证

`.github/workflows/p2p-preview.yml`：

- `preview` 作业以 `-PcenterConfigFake=true` 构建预览包，运行沙盒脚本、泄漏扫描与受控打包负向测试，并启动真实 coturn 运行 TURN 沙盒。
- `android-sandbox` 作业在 API 35 模拟器安装预览包与仪器包，运行 `PeerInstrumentedTest`，要求 `OK (13 tests)`，并在通过后导出 APK、SHA-256 与测试记录。

仪器测试覆盖：生产 Keystore/双扫码/TCP、撤销、独立 Relay 授权、路由策略、重复短信回执幂等、1,205 行历史分页，以及本轮新增的「原生引擎就绪 + 未授权中心时辅助路径失败关闭 + 默认仅 STUN」与「节点中心绑定授权码签名、防篡改与明确同意」。

**操作补充（已有可信中心）**：两台设备先完成双向扫码，节点进入「连接 / 授权」明确同意信令辅助建连；节点绑定成功后生成 5 分钟有效的签名绑定二维码，管理端进入「连接详情」扫码确认，才会尝试 WebRTC 公网直连。同一局域网不需要此步骤。此步骤不是新的公共服务器，不启用数据 Relay，也不能保证受限 NAT 下成功；无已有可信中心仍只能尝试同网直连。

`.github/workflows/ci.yml` 的 Android 作业同样以 `-PcenterConfigFake=true` 构建，使公开 CI 走与私有构建相同的生成/加载/校验路径。

## 受控打包（需单独授权）

`.github/workflows/p2p-private-packaging.yml` 只接受「完整 40 位 SHA + 受审核的 `develop` 当前提交」的 `workflow_dispatch`，绑定受保护 Environment `p2p-private-packaging`，固定 checkout 该 SHA、禁用 Gradle 缓存，并把敏感工具输出收进 runner 本地受限文件（只打印固定错误码）。产物仅允许 APK 与 SHA-256。

首次使用前需要单独批准：创建受保护 Environment、写入 6 个 Secret（node/controller 的 origin 与 current/backup 两枚 pin）、确认该 workflow 位于受保护 ref。

## 隐私与状态

- 管理中心可见：注册关系、绑定公钥/配对关系、IP、时间、流量大小与投递元数据。中心**不**持有配对内容密钥。
- 观察者可经 DNS/TLS 元数据/证书透明度日志发现域名；本方案不承诺域名不可发现，也不把域名当访问控制。
- 真机、公网、证书轮换演练与桌面端行为需另行验收；`docs/p2p/ACCEPTANCE.zh-CN.md` 逐项列出当前状态（含 `未运行` 与 `gated` 项）。
- 短信实时投递受运营商与 OEM 后台策略影响，测试通过不代表送达保证。
