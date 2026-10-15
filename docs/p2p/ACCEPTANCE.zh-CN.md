# 验收矩阵逐项状态（诚实版）

对象：本分支上的 P2P 安全整改（原生 RTC + 中心辅助信令 + 共享 pinning 客户端 + 受控配置注入）。
基线：`5a84ab79bd0d7c887fe920096839bb4865447f65`。设计文档：`output/v0.1_p2p-security-design.md` 第 14 节。

状态口径：

- `通过`：本会话内运行过真实命令并看到原始输出。
- `已实现·待 CI/真机`：代码与断言已就位，但需要模拟器/真机/远端 CI 才能产生证据。
- `未运行`：没有执行任何验证。
- `gated`：需要用户单独授权（生产、Secrets、部署、推送）才能执行。

| 编号 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| A1 无 WebView 的 data-only RTC，无新增音视频权限 | 部分通过 | `PeerNativeRtcTransport`（`org.webrtc`）；WebView 类、资产与 JS 桥已删除；`scripts/test_p2p_sandbox.py` 断言无 WebView 标记、资产不存在、依赖精确固定；仪器测试断言 `engineReady()` 并检查清单无 `RECORD_AUDIO`。**已在本机 APK 上核对**：release APK 内 `.js`/`.html` 资产为 0 个，`libjingle_peerconnection_so.so` 覆盖 4 个 ABI，清单无 `RECORD_AUDIO`/`RECORD_VIDEO`；`CAMERA` 是本次变更之前就存在的扫码配对权限，本轮未改 Manifest。未在真机运行。 |
| A2 LAN/DIRECT 来自真实 selected candidate pair；未授权 TURN 不建立通路 | 已实现·待 CI/真机 | 引擎以统计中的 selected candidate pair 判路，未知路由不视为 DIRECT；无 TURN 授权时拒绝中继候选并在开启前拆除。`test_p2p_sandbox.py` 断言 ICE 默认仅 STUN、无 `turn:`/`turns:`。真实公网/强制 relay 未运行。 |
| A3 init/close/restart/网切竞态无悬挂 future、重复 RPC、native 泄漏 | 已实现·待 CI/真机 | 单一串行执行域 + epoch 校验；close/超时完成所有 pending。压力与故障注入测试未运行。 |
| B1 Cookie 不能当 Device token；Device token 不能调用管理路由 | 通过 | `server/test_peer_signaling_http.py` 覆盖双表面鉴权分离、CSRF、跨域读取被拒。 |
| B2 无授权/伪造身份/跨 node·pair·session/撤销修订/重复 nonce 失败 | 通过 | `server/test_peer_signaling.py` + `server/test_peer_signaling_http.py`（本会话 27/27 OK）。发现并修复：node 方向操作此前未校验设备归属，现失败关闭（`session_unavailable`）。 |
| B3 信令 payload 不能进入 `PeerRpc.dispatch`；业务密文不能当信令 | 通过 | 严格 parser 只接受 OFFER/ANSWER/ICE/END_OF_CANDIDATES/CLOSE；独立 domain/子密钥；跨语言向量逐字节比对（`server.test_peer_signaling` 内）。 |
| B4 开启信令前后 policy/fallbackConsent/relayBindingState 不变 | 已实现·待 CI/真机 | 信令字段与业务策略字段完全分离；`setSignaling` 只写 `signalingConsent/Binding/Revision`。仪器断言 `policy == P2P_ONLY`。 |
| B5 TTL、ACK/去重、速率/容量、重启/注销/撤销清理 | 通过 | 服务端测试覆盖会话 300s、消息 120s、帧 64KiB、32 未 ACK、2MiB/方向、64MiB 全局、2 会话/节点、60 帧/分、10 会话/分、8 绑定/设备；设备撤销与维护任务清理。 |
| C1 正确 pin 通过；同主机有效 CA 但错误公钥失败 | 通过 | `SecureHttpTrustTest` 以真实 TLS 握手运行：正确 pin→200；有效链+错误 pin→`CENTER_TLS_PIN_MISMATCH` 且服务端未收到任何请求。信任锚为测试注入（自签测试证书），生产不放宽平台 trust。另有**变异检验**：摘掉 `certificatePinner(...)` 后该用例失败（见下方「变异检验」）。 |
| C2 空 pin、无匹配 host、跨 origin 重定向失败，且失败前无凭据外泄 | 通过 | 同测试：空/单 pin→`PinMissing`；未覆盖 origin→`OriginRejected`；3xx 不被跟随（返回 302，仍只有一次请求，未携带凭据跳到新 host）。 |
| C2a 双角色原 HTTP 合同不回归，携带同中心凭据的请求受 pin 约束 | 已实现·待 CI | `ApiClient.raw/request/requestWithToken/pairingRequest/enroll` 与 `ControllerSecureClient.request` 全部改走 `SecureHttp`，上层方法签名、token/cookie/CSRF、超时、响应大小上限、错误码与轮换语义保持不变。完整双角色端到端回归未运行。 |
| C3 current/backup 按轮换计划连接；退役 key 更新后拒绝 | 未运行 | 需要双证书换钥演练与客户端版本矩阵；不在本机范围。 |
| D1 缺 Secrets/占位值/非法 origin 时非零退出且不回显输入 | 已实现·待 CI | `scripts/test_center_config_packaging.py` 用合成 canary 运行真实生成器：缺值/非法/重复分别以 `CENTER_CONFIG_PIN_MISSING`/`_PIN_INVALID`/`_PIN_DUPLICATE` 失败，且断言 canary 未出现在输出中。本机未执行（需 Gradle + SDK 的作业）。 |
| D2 公开作业无真实 Secret；固定审核 SHA 不受 branch 移动影响 | 已实现·未运行 | `p2p-private-packaging.yml`：预检作业无 Secrets，校验 40 位 SHA 且等于该 PR 的 head，作业绑定受保护 Environment，checkout 固定 SHA，禁缓存；`p2p-preview.yml` 不再引用任何新域名 Secret。未在远端运行。 |
| D3 源码包/日志/报告/缓存/非 APK 工件无 canary | 已实现·待 CI | `scripts/test_center_config_leak.py` 静态扫描：禁止提交生成的描述、pin 字面量、非保留域名；`--canary-absent` 供作业校验任意目录树。本地静态扫描可运行。 |
| D4 专用 APK 恰含预期配置且不含 token/私钥；普通 APK 不含专用配置 | gated | 需要专用打包作业与真实 Secrets（用户单独授权）。 |
| D5 Gradle/OkHttp/异常链/截图/崩溃诊断不打印 host/pin | 已实现·待 CI | 运行期固定错误码 `CENTER_TLS_PIN_MISMATCH`/`CENTER_AUTH_REQUIRED`/`CENTER_SETUP_REQUIRED`；`ApiClient` 日志改为固定状态码，不再打印 path 与原始异常；`CenterTrust.toString()` 不输出 origin/pin；敏感打包作业把原始 stdout/stderr 收进本地受限文件。故障注入测试未运行。 |
| E1 标准 CI、OTA、预览、仪器与 GHAS 聚合全部通过 | 未运行 | 未推送，因此没有同 SHA 的远端 checks 与分析实例。 |
| E2 双真机跨运营商 NAT、休眠/唤醒、网切、中心离线、重新登录 | 未运行 | 需要真机；与模拟器结果须分别标注。 |
| E3 短信/Vault/独立配对/发送权限/撤销/重复命令幂等不回归 | 部分通过 | 本会话运行的服务端套件全部 OK：`peer_signaling(+http)` 27、`peer_connect` 10、`v040/v050/pairing` 12、`sms_companion_state/passkeys_e2e/hardening/controller_auth/controller_http/global_vault` 24、`updates/remote_ota` 13、`test_auth_stages` 2、`test_simhub` 28、`test_surfaces` 1（详见下方命令表）。这些是本仓库文档化的主要套件，不等同于「全部服务端测试」。Android 侧回归（仪器 12 项）未在本机运行（无模拟器/真机）。 |

## 本会话已运行的命令与原始结果

工具链：`gradle 9.6.0`（腾讯云镜像）+ Android SDK `platform-37.0` / `build-tools 37.0.0 + 36.0.0`，Maven 走本地 `GRADLE_USER_HOME/init.d` 镜像脚本（不入库），JDK 17.0.20。以下命令均在本会话执行并读取原始输出。

| 命令 | 原始结果 |
| --- | --- |
| `gradle -p android :app:testDebugUnitTest`（全量单元测试，非筛选） | `BUILD SUCCESSFUL`，EXIT=0；21 个测试类，**81 tests / 0 failures / 0 errors**，含新增 `CenterTrustTest`、`SecureHttpTrustTest` |
| `gradle -p android -Pp2pAndroidSandbox=true :app:compileP2pPreviewAndroidTestJavaWithJavac :app:compileP2pPreviewJavaWithJavac` | `BUILD SUCCESSFUL in 50s`，EXIT=0（p2pPreview 主源集与仪器源集编译通过） |
| `python3 scripts/test_p2p_sandbox.py` | exit=0；结构不变量 + 服务端 37 项 + JVM 沙盒 20 项断言全部通过 |
| `python3 scripts/test_center_config_leak.py` | `center-config leak checks passed`，exit=0 |
| `python3 scripts/test_docs.py` | exit=0 |
| `PYTHONPATH=server python -m unittest server.test_peer_signaling server.test_peer_signaling_http` | `Ran 27 tests ... OK` |
| `PYTHONPATH=server python -m unittest server.test_peer_connect` | `Ran 10 tests ... OK` |
| `PYTHONPATH=server python -m unittest server.test_v040 server.test_v050 server.test_pairing` | `Ran 12 tests ... OK` |
| `PYTHONPATH=server python -m unittest server.test_sms_companion_state server.test_passkeys_e2e server.test_hardening server.test_controller_auth server.test_controller_http server.test_global_vault` | `Ran 24 tests ... OK` |
| `PYTHONPATH=server python -m unittest server.test_updates server.test_remote_ota` | `Ran 13 tests ... OK` |
| `PYTHONPATH=server python server/test_auth_stages.py` | `Ran 2 tests ... OK` |
| `PYTHONPATH=server python server/test_simhub.py` | `Ran 28 tests ... OK` |
| `PYTHONPATH=server python server/test_surfaces.py` | `Ran 1 test ... OK` |

JVM 报告落在 `android/app/build/test-results/testDebugUnitTest/`；完整 gradle 日志留在本地（不随仓库提交）。

## 产物级证据（在本机 APK 上核对，不是只读源码）

| 检查 | 结果 |
| --- | --- |
| 假配置构建（`-PcenterConfigFake=true`，release + R8） | `BUILD SUCCESSFUL in 6m 56s`，EXIT=0；`app-release-unsigned.apk` 53.6 MB |
| 该 APK 内含 `assets/center-config.json` | 是（426 字节），内容仅保留域名 `node.relay.invalid` / `admin.relay.invalid` 与两枚**测试证书** pin，`configId=fakepublicci…` |
| 上一个假配置构建之后**不带 flag**的普通构建 | `BUILD SUCCESSFUL`，EXIT=0；`app-debug.apk` 内 `center-config.json` 条目 **0** 个（生成任务在非注入模式会删除残留文件，`build.gradle.kts:194-197`，且 `fake`/`packaging` 参与任务输入声明） |
| release APK 内 `.js` / `.html` 资产 | 0 个（WebView 资产删除落到产物上） |
| release APK 内原生 RTC 库 | `libjingle_peerconnection_so.so` 覆盖 arm64-v8a / armeabi-v7a / x86 / x86_64 |
| release APK 清单权限 | 无 `RECORD_AUDIO`、无 `RECORD_VIDEO`；`CAMERA` 属本次变更之前既有的扫码配对权限，本轮未改 Manifest |
| 泄漏扫描 | `center-config leak checks passed`（exit=0）；同时修正了扫描器把 `android/app/build/**` 构建产物误判为「已提交生成配置」的假阳性 |

## 变异检验（证明测试不是装饰）

按设计 §9.4「移除/绕过 pinner 的变异应使测试失败」执行，改动后按 md5 逐字节还原：

| 变异 | 结果 |
| --- | --- |
| 把 `SecureHttp` 的 pinned client 缓存键从 `role‖origin‖pins` 改为 `role‖origin` | `keysThePinnedClientOnThePinSetSoRotationCannotReuseTheOldPinner` **FAILED**（8 tests, 1 failed）；还原后 md5 一致 |
| 删掉 `.certificatePinner(pinner.build())`（pinner 不再挂到 client） | `rejectsAValidChainWithTheWrongPinBeforeSendingAnything` 与上述缓存键用例 **同时 FAILED**（8 tests, 2 failed）；还原后 md5 一致 |
| 还原后复跑全量单测 | `BUILD SUCCESSFUL`，82 tests / 0 failures / 0 errors |

## 自审发现并修复的缺陷

1. `SecureHttp.timed()` 原先以 `System.identityHashCode(client)` 作为派生 client 的缓存键。哈希碰撞会把**另一套 pin** 的 client 交给调用方；而 OkHttp 对 pinner 中不存在的 host 不做任何 pin 校验，等于静默放行。已改为按对象身份索引（`IdentityHashMap`），并用新增的缓存键用例守住。
2. 用 `javap` 核对本仓实际解析到的 `okhttp 5.3.2`（`okhttp-android` AAR）：`Address.equalsNonHost` 的字节码在 offset 119–126 比较 `certificatePinner` 字段。因此「共享连接池 + 派生 client」不会让 pinned 请求复用未 pin 的连接——这条从假设升级为反编译证据。
3. 泄漏扫描器的构建产物假阳性（见上）。

## 未覆盖 / 需另行授权

1. 真实 Secrets 的读取、设置与专用 APK 打包（D4、P5）。
2. 生产部署、数据库迁移、备份/回滚演练（P6）。
3. 真机与跨运营商网络验收（E2）。
4. 推送、远端 CI 与聚合 CodeQL 复扫（E1、P7）。
5. 完整的用户侧「辅助建连授权」UX 流程（当前为显式按钮 + 状态提示，未做引导式向导）。
