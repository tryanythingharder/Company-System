-- 模具加工管理系统：站点品牌与登录页清理（可重复执行）
-- 背景：pig.sql 自带的站点参数是上游演示值——标题 PIG ADMIN、ICP 占位号、指向 "#" 的协议链接、
-- 以及上游作者的 Microsoft Clarity 追踪 ID（开启后会把页面内容上报第三方）。内部系统不需要这些。
-- 执行后需清 Redis 缓存 site_config_details::*（部署初始化在应用启动前执行，无需清理）。
-- 注意：必须 SET NAMES utf8mb4，否则 mysql 客户端默认按 latin1 读取本文件，中文会被双重编码成乱码。
SET NAMES utf8mb4;
USE `mold_dev`;

UPDATE sys_public_param SET public_value = '模具加工管理系统' WHERE public_key = 'SITE_TITLE';
UPDATE sys_public_param SET public_value = '' WHERE public_key IN ('SITE_FOOTER', 'SITE_PRIVACY_TIP', 'SITE_CLARITY_ID', 'SITE_ANTI_DEBUG_KEY');
UPDATE sys_public_param SET public_value = '0' WHERE public_key IN ('SITE_ANTI_DEBUG_ENABLE', 'SITE_I18N_ENABLE',
  'SITE_SMS_LOGIN_ENABLE', 'SITE_SOCIAL_LOGIN_ENABLE', 'SITE_REGISTER_ENABLE', 'SITE_RESET_PASSWORD');

-- 菜单路径与前端页面对齐（早期种子写的是 my-work / timeline / users，前端实际目录是
-- my-stages / stage-history / accounts；一旦启用后端菜单，这三项会打开成空白页）
UPDATE sys_menu SET path = '/mold/my-stages', component = 'mold/my-stages/index' WHERE menu_id = 3001;
UPDATE sys_menu SET path = '/mold/stages', component = 'mold/stage-history/index' WHERE menu_id = 3005;
UPDATE sys_menu SET path = '/mold/accounts', component = 'mold/accounts/index' WHERE menu_id = 3006;
