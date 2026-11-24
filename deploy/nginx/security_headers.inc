# 安全响应头（TLS 在上游负载均衡/网关终止时，由那一层补 HSTS）
# nginx 的 add_header 不会从外层继承到「自己也写了 add_header」的 location，所以抽成片段到处 include。
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
add_header X-Request-Id $request_id always;
