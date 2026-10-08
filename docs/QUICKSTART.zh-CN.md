# SIM Hub：一键初始化与部署

适用环境：Linux VPS、Docker Engine、Docker Compose Plugin，可供手机访问的 HTTPS 域名。

## 一、准备域名

需要两个子域名，建议配置以下 DNS 记录：

| 记录类型 | 主机记录 | 记录值 |
|---|---|---|
| A | admin | 服务器公网 IPv4 |
| A | node | 服务器公网 IPv4 |

管理域名示例：\`admin.example.com\`；设备域名示例：\`node.example.com\`。
如果启用了 IPv6，确保 AAAA 记录指向实际可访问的 IPv6 地址，否则删除错误记录。外部开放 TCP 80 和 443；**不要对公网开放 8787**。

## 二、推荐：托管模式

安装 Docker / Compose 后，执行：

\`\`\`bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
\`\`\`

安装向导依次检查 Docker、DNS、端口，自动生成高强度管理员恢复 Token 和 TOTP Secret，写入只有文件所有者可读的 \`.env\`，生成 \`Caddyfile\`，启动 Relay 与 Caddy，申请 HTTPS 证书。

注意：**管理凭据不会发送到 DNS 服务商或 GitHub**，也不会自动修改域名服务商的解析记录。请把 \`.env\` 中的 TOTP Secret 添加至身份验证器，并安全备份，不要将其提交到 GitHub。

浏览器打开管理域名，输入初始化用户名（默认 \`admin\`）、Admin Token、TOTP，创建或导入本地 Vault，然后到 **设置 → 通行密钥** 添加 Passkey。建议至少绑定两把不同设备上的通行密钥。

Passkey 只负责登录身份验证；本地 Vault 仍需用户持有的 Vault 密码或恢复密钥进行解密。服务器无法读取短信内容。

## 三、已有 Nginx / Caddy / Traefik

如果不希望安装程序管理反向代理：

\`\`\`bash
python3 scripts/setup.py --mode external \
  --admin-domain admin.example.com --node-domain node.example.com
\`\`\`

自行为两个域名配置有效的 HTTPS、代理到 \`127.0.0.1:8787\`，覆盖写入真实客户端 IP 的 \`X-Forwarded-For\`，参照 \`Caddyfile.example\` 禁止公开访问内部 \`/healthz\` 与 \`/readyz\` 接口。管理和设备 API 通过 Host 隔离。

如果 DNS 尚未生效，可先使用 \`--skip-dns-check --no-start\` 生成配置，解析正常后执行 \`--upgrade\` 启动。

## 四、升级

先执行 \`./scripts/backup.sh\` 备份密文数据库，并独立备份 Vault 恢复密钥；再执行：

\`\`\`bash
git pull --ff-only
python3 scripts/setup.py --upgrade --admin-domain admin.example.com \
  --node-domain node.example.com
\`\`\`

如果原有部署由其他反向代理管理，请保留 \`--mode external\`。

升级模式不覆盖 \`.env\`、\`Caddyfile\`、数据库和 Volume。管理域名与 Passkey 的 RP ID 绑定，**不要在升级时直接更换管理域名**。旧 Android / Modem 设备仍使用原来的 Node Token、Node Key 与加密协议，无需重新注册。

## 五、诊断

\`\`\`bash
docker compose ps
docker compose logs --tail=50 simhub
docker compose exec -T simhub python3 -c \
  "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8787/readyz').read().decode())"
\`\`\`

托管模式下检查 TLS 日志：

\`\`\`bash
docker compose -f docker-compose.yml -f compose.caddy.yml logs --tail=50 caddy
\`\`\`

如果浏览器证书申请失败，首先检查 DNS A / AAAA、80/443 入站规则和其他反向代理的端口占用。外网验证请使用另一网络中的浏览器访问管理及设备 HTTPS 域名。正式部署完成后，建议验证登录、Passkey、节点注册、短信收发和数据库恢复。
