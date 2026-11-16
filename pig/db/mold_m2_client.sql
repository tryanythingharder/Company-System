-- M2 认证薄适配层专用 OAuth 客户端（仅写入 mold_dev，不改动 pig 库）
-- 原因：
--   1) 上游内置 pig 客户端 enc_flag=1 / captcha_flag=1，/oauth2/token 要求 AES 密文与图形验证码；
--      M2 的 /auth/login 是服务端到服务端的薄适配层，不应把验证码转嫁给前端。
--   2) 新建 mold 客户端把“是否加密 / 是否需要验证码”显式关闭，后续 M3 前端仍只调 /auth/login。
-- 安全边界：该客户端仅授予 password + refresh_token，scope=server；生产环境应改为 TLS + 前端加密。
INSERT INTO `sys_oauth_client_details` (`id`, `client_id`, `resource_ids`, `client_secret`, `scope`,
    `authorized_grant_types`, `web_server_redirect_uri`, `authorities`, `access_token_validity`,
    `refresh_token_validity`, `additional_information`, `autoapprove`, `del_flag`)
SELECT COALESCE(MAX(`id`), 0) + 1, 'mold', NULL, 'mold', 'server', 'password,refresh_token', NULL, NULL,
    43200, 2592001, '{"enc_flag":"0","captcha_flag":"0"}', 'false', '0'
FROM `sys_oauth_client_details`
ON DUPLICATE KEY UPDATE `additional_information` = VALUES(`additional_information`),
    `authorized_grant_types` = VALUES(`authorized_grant_types`), `del_flag` = '0';
