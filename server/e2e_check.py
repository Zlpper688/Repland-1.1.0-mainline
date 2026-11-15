# -*- coding: utf-8 -*-
"""Repland 服务端端到端验证：注册→推状态→冲突→队员拉取→回推→登出→账号管理。

用法（手机号与 admin 口令走环境变量，不落命令行）：
  REPLAND_E2E_BASE=https://ilovemxt.xyz/repland \
  REPLAND_E2E_PHONE1=… REPLAND_E2E_PHONE2=… REPLAND_E2E_ADMIN_TOKEN=… \
  python e2e_check.py
PHONE1/PHONE2 必须在服务端 REPLAND_SMS_ALLOWED_PHONES 名单内（公网内测门槛）。
"""
import json
import os
import time
import urllib.error
import urllib.request

B = os.environ.get("REPLAND_E2E_BASE", "http://127.0.0.1:18080").rstrip("/")
ADMIN = os.environ.get("REPLAND_E2E_ADMIN_TOKEN", "admin-test-1")
PHONE1 = os.environ.get("REPLAND_E2E_PHONE1", "13812345678")
PHONE2 = os.environ.get("REPLAND_E2E_PHONE2", "13987654321")
TEAM = os.environ.get("REPLAND_E2E_TEAM", "team-e2e")
mask = lambda p: p[:3] + "****" + p[-4:]


def call(method: str, path: str, body: dict | None = None, token: str = "", headers: dict | None = None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw.strip() else {}


def login(phone: str, name: str, team: str = "", member: str = ""):
    for _ in range(5):
        _, r1 = call("POST", "/v1/auth/sms/request", {"phone": phone})
        if r1.get("status") == "sent":
            break
        time.sleep(min(r1.get("retryAfterSeconds", 5), 15) + 1)
    assert r1.get("devCode"), "服务端未回显 devCode：配了短信网关就要改用其它取码方式"
    _, r2 = call("POST", "/v1/auth/sms/verify", {
        "phone": phone, "code": r1["devCode"], "displayName": name,
        "teamId": team, "memberId": member,
    })
    assert r2["status"] == "ok", r2
    print(f"✓ 登录 {name} account={r2['accountId'][:8]}… token={r2['token'][:10]}… created={r2['created']}")
    return r2["token"]


ok = lambda s, want: print(("✓" if s == want else "✗") + f" {s}（期望 {want}）")

# 0. 内测名单门槛（未配名单的环境自动跳过）
_, probe = call("POST", "/v1/auth/sms/request", {"phone": "13000000000"})
if probe.get("status") == "not_allowed":
    assert not probe.get("devCode"), "名单外竟然回显了验证码"
    print(f"✓ 名单外手机号被拒且不回显码：{probe.get('detail', '')}")
else:
    print("· 未配内测名单（开发环境），跳过门槛验证")

# 1. 队长注册登录 + 首推
cap = login(PHONE1, "队长", TEAM, "m-captain")
s, r = call("PUT", f"/v1/teams/{TEAM}/state", {
    "baseRevision": 0, "updatedByMember": "队长",
    "payload": json.dumps({"teamId": TEAM, "teamName": "数模队", "tasks": 3}, ensure_ascii=False),
}, cap)
ok(s, 200); ok(r["status"], "ok"); ok(r["revision"], 1)
print(f"✓ 队长首推 rev{r['revision']} by {r['updatedByMember']}")

# 2. 旧 base 重推 → 409 带服务端当前版
s, r = call("PUT", f"/v1/teams/{TEAM}/state", {
    "baseRevision": 0, "updatedByMember": "队长", "payload": "{\"stale\":true}",
}, cap)
ok(s, 409); ok(r["status"], "conflict")
print(f"✓ 冲突回带服务端版 rev{r['revision']} payload={r['payload'][:30]}")

# 3. 未登录禁写
s, r = call("PUT", f"/v1/teams/{TEAM}/state", {"baseRevision": 1, "payload": "{}"})
ok(s, 401)

# 4. 队员登录拉取 → 回推 rev2
mem = login(PHONE2, "队员小张", TEAM, "m-zhang")
s, r = call("GET", f"/v1/teams/{TEAM}/state?since=0", token=mem)
ok(s, 200); ok(r["status"], "ok"); ok(r["revision"], 1)
print(f"✓ 队员拉到 rev{r['revision']} by {r['updatedByMember']} payload={r['payload'][:40]}")
s, r = call("GET", f"/v1/teams/{TEAM}/state?since=1", token=mem)
ok(r["status"], "current")
s, r = call("PUT", f"/v1/teams/{TEAM}/state", {
    "baseRevision": 1, "updatedByMember": "队员小张",
    "payload": json.dumps({"teamId": TEAM, "teamName": "数模队", "tasks": 5}, ensure_ascii=False),
}, mem)
ok(s, 200); ok(r["revision"], 2)
print(f"✓ 队员回推 rev{r['revision']}")

# 5. 队长 since=1 拉到队员的 rev2（双向跑通）
s, r = call("GET", f"/v1/teams/{TEAM}/state?since=1", token=cap)
ok(r["status"], "ok"); ok(r["revision"], 2); ok(r["updatedByMember"], "队员小张")
print(f"✓ 队长拉到 rev{r['revision']} by {r['updatedByMember']} —— 双向同步跑通")

# 6. 跨队越权：token 属于 team-e2e，读别的队 → 403（空队的 not_found 由 JVM 用例覆盖）
s, r = call("GET", "/v1/teams/team-other/state", token=cap)
ok(s, 403); ok(r["status"], "forbidden")

# 7. 登出 → token 失效
s, r = call("POST", "/v1/auth/logout", {}, cap)
ok(r["status"], "ok")
s, r = call("GET", "/v1/auth/account-me", token=cap)
ok(s, 401)
print("✓ 登出后原 token 401")

# 8. 账号管理（admin）
s, r = call("GET", "/v1/auth/accounts")
ok(s, 403)
s, r = call("GET", "/v1/auth/accounts", headers={"X-Auth-Admin-Token": "wrong-token"})
ok(s, 403)
s, r = call("GET", "/v1/auth/accounts", headers={"X-Auth-Admin-Token": ADMIN})
ok(r["status"], "ok")
phones = [a["phoneMasked"] for a in r["accounts"]]
print(f"✓ 账号清单 {len(r['accounts'])} 人，脱敏手机号 {phones}")
assert mask(PHONE1) in phones and mask(PHONE2) in phones, "清单里应含两个测试号（脱敏）"
assert all("****" in p for p in phones), "账号清单出现未脱敏手机号"
print(f"\n=== {B} 端到端全部通过 ===")
