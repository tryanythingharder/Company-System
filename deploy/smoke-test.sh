#!/usr/bin/env bash
# 部署后冒烟测试（只读、不改数据）：经 nginx 入口验证关键安全与功能路径。
# 用法：./deploy/smoke-test.sh [http://127.0.0.1:8080]
# 依赖：curl、python3。密钥只从 deploy/.env 读取，不回显。
set -uo pipefail
cd "$(dirname "$0")"
BASE="${1:-http://127.0.0.1:8080}"
set -a; . ./.env; set +a

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jget() { python3 -I -c 'import json,sys; d=json.load(sys.stdin); 
for k in sys.argv[1].split("."): d = d.get(k) if isinstance(d, dict) else None
print("" if d is None else d)' "$1"; }
expect() { local want="$1" got="$2" name="$3"; [ "$got" = "$want" ] && ok "$name ($got)" || bad "${name}：期望 ${want}，实际 $got"; }

echo "== 入口与探活"
expect 200 "$(code "$BASE/")" "首页"
expect 200 "$(code "$BASE/api/admin/healthz")" "存活探针 /healthz"

echo "== 安全响应头"
HDR=$(curl -s -D - -o /dev/null "$BASE/")
for h in 'X-Content-Type-Options: nosniff' 'X-Frame-Options: DENY' 'Content-Security-Policy:' 'X-Request-Id:'; do
  echo "$HDR" | grep -qi "^$h" && ok "响应头 $h" || bad "缺少响应头 $h"
done
echo "$HDR" | grep -qi '^Server: nginx/' && bad "Server 头暴露版本号" || ok "Server 头不暴露版本号"

echo "== 对外屏蔽的路径（应 404）"
for p in oauth2/token token/check_token actuator/health druid/index.html v3/api-docs doc.html swagger-ui/index.html; do
  expect 404 "$(code "$BASE/api/admin/$p")" "/api/admin/$p"
done
expect 404 "$(code -X POST "$BASE/api/admin/oauth2/token?grant_type=password&username=admin&password=x&scope=server" -u pig:pig)" "遗留客户端 pig:pig 直调 oauth2"

echo "== 未认证访问"
expect 401 "$(code "$BASE/api/admin/mold/projects")" "业务接口未带令牌"
expect 401 "$(code "$BASE/api/admin/user/info")" "上游接口未带令牌"

echo "== 默认管理员 admin 已锁定"
ST=$(code -H 'Content-Type: application/json' -X POST "$BASE/api/admin/auth/login" \
      --data '{"loginName":"admin","password":"123456"}')
[ "$ST" != "200" ] && ok "admin/123456 无法登录 ($ST)" || bad "admin/123456 仍可登录"

echo "== 首个 OWNER 登录与强制改密闸门"
if [ -n "${MOLD_BOOTSTRAP_OWNER_LOGIN:-}" ] && [ -n "${MOLD_BOOTSTRAP_OWNER_PASSWORD:-}" ]; then
  BODY=$(python3 -I -c 'import json,os; print(json.dumps({"loginName": os.environ["MOLD_BOOTSTRAP_OWNER_LOGIN"], "password": os.environ["MOLD_BOOTSTRAP_OWNER_PASSWORD"]}))')
  RESP=$(curl -s -H 'Content-Type: application/json' -X POST "$BASE/api/admin/auth/login" --data "$BODY")
  AT=$(echo "$RESP" | jget data.accessToken)
  RT=$(echo "$RESP" | jget data.token.refreshToken)
  MC=$(echo "$RESP" | jget data.mustChangePassword)
  if [ -n "$AT" ]; then
    ok "OWNER 登录成功"
    if [ "$MC" = "True" ]; then
      ok "首登 mustChangePassword (True)"
      expect 403 "$(code -H "Authorization: Bearer $AT" "$BASE/api/admin/mold/projects")" "改密前业务接口被拦"
      expect 403 "$(code -H "Authorization: Bearer $AT" "$BASE/api/admin/user/info")" "改密前上游接口也被拦（闸门全覆盖）"
    else
      # 已清除改密标志却仍能用 .env 里的初始密码登录：说明密码没有换成新值，属安全隐患
      bad "OWNER 仍可用 .env 中的初始密码登录（改密标志已清除）：请尽快修改老板密码"
    fi
    expect 200 "$(code -H "Authorization: Bearer $AT" "$BASE/api/admin/auth/me")" "改密前 /auth/me 可用"
    RB=$(python3 -I -c 'import json,sys; print(json.dumps({"refreshToken": sys.argv[1]}))' "$RT")
    expect 200 "$(code -H 'Content-Type: application/json' -X POST "$BASE/api/admin/auth/refresh" --data "$RB")" "刷新令牌走 /auth/refresh"
    expect 200 "$(code -H "Authorization: Bearer $AT" -X POST "$BASE/api/admin/auth/logout")" "注销"
    expect 401 "$(code -H "Authorization: Bearer $AT" "$BASE/api/admin/auth/me")" "注销后旧令牌失效"
  else
    echo "  （登录未成功：若已改密，这是预期的；HTTP 响应：$(echo "$RESP" | jget msg)）"
  fi
else
  echo "  （.env 未配置 MOLD_BOOTSTRAP_OWNER_*，跳过）"
fi

echo "== 前端产物不含 OAuth 客户端密钥"
JS=$(curl -s "$BASE/" | grep -o '/assets/[^"]*\.js' | head -1)
if [ -n "$JS" ] && [ -n "${MOLD_AUTH_CLIENT_SECRET:-}" ]; then
  ALL=$(curl -s "$BASE/" | grep -o '/assets/[^"]*\.js')
  LEAK=0; for f in $ALL; do curl -s "$BASE$f" | grep -qF "$MOLD_AUTH_CLIENT_SECRET" && LEAK=1; done
  [ $LEAK -eq 0 ] && ok "入口 JS 未包含客户端密钥" || bad "前端产物泄露客户端密钥"
fi

echo
echo "结果：$PASS 通过，$FAIL 失败"
[ $FAIL -eq 0 ]
