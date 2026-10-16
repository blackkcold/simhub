#!/usr/bin/env bash
# Deploy a pinned, Cosign-verified image without compiling code on the host.
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in docker cosign python3; do command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }; done
[[ -f deployment.json && -f .env ]] || { echo "deployment.json and an operator-configured .env are required" >&2; exit 1; }
docker compose version >/dev/null
read -r VERSION CHANNEL IMAGE IDENTITY ISSUER < <(python3 - <<'PY'
import json,re
d=json.load(open("deployment.json",encoding="utf-8"))
ch=d.get("channel")
ver=d.get("version","")
img=d.get("image","")
ident=d.get("cosignIdentity","")
issu=d.get("cosignIssuer","")
assert d.get("schemaVersion")==1 and ch in ("stable","dev")
pattern=r"\d+\.\d+\.\d+" + (r"-dev\.[1-9]\d*" if ch=="dev" else "")
assert re.fullmatch(pattern,ver) is not None
assert re.fullmatch(r"ghcr\.io/blackkcold/simhub-relay@sha256:[a-f0-9]{64}",img)
expected="https://github.com/blackkcold/simhub/.github/workflows/"+("release-dev.yml@refs/heads/develop" if ch=="dev" else "release.yml@refs/heads/main")
assert ident==expected and issu=="https://token.actions.githubusercontent.com"
print(ver,ch,img,ident,issu)
PY
)
[[ -n "$IMAGE" ]] || { echo "Invalid deployment metadata" >&2; exit 1; }
docker info --format '{{json .SecurityOptions}}' | python3 -c 'import json,sys; assert any("rootless" in str(x).lower() for x in json.load(sys.stdin)), "Rootless Docker required"'
python3 - "$CHANNEL" <<'PY'
import pathlib,sys
channel=sys.argv[1]
env={}
for line in pathlib.Path(".env").read_text().splitlines():
    if line and not line.lstrip().startswith("#") and "=" in line:
        k,v=line.split("=",1); env[k.strip()]=v.strip().strip('"').strip("'")
for key,expected in (("SIMHUB_CHANNEL",channel),("COMPOSE_PROJECT_NAME","simhub-"+channel),
                     ("SIMHUB_HOST_PORT","8788" if channel=="dev" else "8787")):
    if env.get(key)!=expected: raise SystemExit("REFUSED: mismatched "+key)
for key in ("SIMHUB_ADMIN_TOKEN","SIMHUB_TOTP_SECRET","SIMHUB_PUBLIC_BASE_URL","SIMHUB_MANAGEMENT_ORIGIN"):
    if not env.get(key) or any(x in env[key].lower() for x in ("change_me","your_","example.com")):
        raise SystemExit("REFUSED: missing or placeholder configuration "+key)
PY
# Verify the exact workflow identity, not just a mutable tag or release-file hash.
cosign verify --certificate-identity="$IDENTITY" --certificate-oidc-issuer="$ISSUER" "$IMAGE" >/dev/null
docker pull "$IMAGE"
cosign verify --certificate-identity="$IDENTITY" --certificate-oidc-issuer="$ISSUER" "$IMAGE" >/dev/null
umask 077
cp -p .env .env.before-deploy
export SIMHUB_VERIFIED_IMAGE="$IMAGE" SIMHUB_VERIFIED_VERSION="$VERSION"
python3 - <<'PY'
import os,pathlib
file=pathlib.Path(".env")
lines=file.read_text().splitlines()
pairs={"SIMHUB_IMAGE_REF":os.environ["SIMHUB_VERIFIED_IMAGE"],
       "SIMHUB_INSTALLED_VERSION":os.environ["SIMHUB_VERIFIED_VERSION"]}
lines=[x for x in lines if not any(x.startswith(k+"=") for k in pairs)]
lines.extend(k+"="+v for k,v in pairs.items())
temp=file.with_name(".env.deployment-tmp")
temp.write_text("\n".join(lines)+"\n")
temp.chmod(0o600)
temp.replace(file)
PY
mkdir -p .simhub-updater
if ! docker compose up -d --no-build simhub; then
  cp -p .env.before-deploy .env
  echo "Deploy failed; original environment restored. Verify running container manually." >&2
  exit 1
fi
echo "Deployed SIM Hub $CHANNEL $VERSION using verified image $IMAGE"
echo "Check HTTPS and /readyz before enabling the channel-specific rootless updater."
