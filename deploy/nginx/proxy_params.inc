proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
# 在右侧追加真实对端；后端只信任本反代，并从右往左取第一个不可信地址
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Request-Id $request_id;
# 丢弃上游 pig 遗留的 from 内部调用标记，防外部伪造
proxy_set_header from "";
proxy_connect_timeout 5s;
proxy_send_timeout 60s;
# proxy_read_timeout 不在这里设置（默认 60s）：SSE 的 location 需要单独设为 1h，nginx 不允许同级重复定义
