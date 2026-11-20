-- =============================================================================
-- mold_dev · Pig（开源版）桥接与权限种子 v1.0
-- =============================================================================
-- 前置：已执行 db/mysql/mold_schema.sql；同库已导入 Pig 的 db/pig.sql
--       （sys_user / sys_role / sys_menu / sys_role_menu 已存在）
-- 作用：
--   1. users 增加 sys_user_id，把业务身份挂到 Pig 的登录账号上；
--   2. 六个固定角色写入 sys_role（role_code 与 user_roles.role_code 同值）；
--   3. 8 个业务页 + 按钮写入 sys_menu（menu_id 3000–3199 段）；
--   4. 角色 → 菜单/按钮授权写入 sys_role_menu。
-- 说明：
--   · 本文件可重复执行（INSERT ... ON DUPLICATE KEY UPDATE / INSERT IGNORE）。
--   · 不做数据迁移（9 个真实账号 → sys_user 属于 M4）。
--   · 不设计多租户、部门数据权限：Pig 的 dept_id / data_scope 保持默认。
-- =============================================================================

USE `mold_dev`;

-- -----------------------------------------------------------------------------
-- 1. 身份桥接：业务 users ↔ Pig sys_user
-- -----------------------------------------------------------------------------
-- 刻意不把 users.id 改成 bigint：22 张业务表有 60 条外键指向 users.id，
-- 改主键类型会连带改 60 处；用一列可空外键挂接成本最低。
ALTER TABLE `users`
  ADD COLUMN `sys_user_id` bigint DEFAULT NULL COMMENT 'Pig sys_user.user_id，登录凭据在 Pig 侧' AFTER `id`,
  ADD UNIQUE KEY `users_sys_user_id_uq` (`sys_user_id`),
  ADD CONSTRAINT `users_sys_user_id_fkey` FOREIGN KEY (`sys_user_id`) REFERENCES `sys_user` (`user_id`);

-- -----------------------------------------------------------------------------
-- 2. 六个固定角色（role_code 与 user_roles.role_code 完全一致，不做二次映射）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_role` (`role_id`, `role_name`, `role_code`, `role_desc`, `create_by`, `create_time`, `del_flag`)
VALUES
  (10, '老板/管理员', 'OWNER',      '全部权限，含账号管理与审计',           'm1-seed', UTC_TIMESTAMP(), '0'),
  (11, '项目负责人', 'PM',         '项目与阶段全权，无财务/账号/审计',     'm1-seed', UTC_TIMESTAMP(), '0'),
  (12, '编程',       'PROGRAMMER', '看公司基础进度，更新自己负责的阶段',   'm1-seed', UTC_TIMESTAMP(), '0'),
  (13, '操机',       'MACHINIST',  '看公司基础进度，更新自己负责的阶段',   'm1-seed', UTC_TIMESTAMP(), '0'),
  (14, '财务',       'FINANCE',    '采购与收支台账全权，无项目编辑/账号/审计','m1-seed', UTC_TIMESTAMP(), '0'),
  (15, '只读',       'VIEWER',     '仅看基础进度与阶段历史',               'm1-seed', UTC_TIMESTAMP(), '0')
ON DUPLICATE KEY UPDATE
  `role_name` = VALUES(`role_name`),
  `role_desc` = VALUES(`role_desc`),
  `del_flag`  = '0';

-- -----------------------------------------------------------------------------
-- 3. 菜单与按钮（8 页 + 隐藏父节点 + 按钮）
--    menu_type: 0=菜单 1=按钮；visible: 1=显示 0=隐藏
-- -----------------------------------------------------------------------------
INSERT INTO `sys_menu`
  (`menu_id`, `name`, `permission`, `path`, `component`, `parent_id`, `icon`, `visible`, `sort_order`, `keep_alive`, `menu_type`, `create_by`, `del_flag`)
VALUES
  -- 3.1 八个业务页
  (3000, '公司进度', 'mold:project:list',  '/mold/projects',    'mold/projects/index',    0,    'iconfont icon-xiangmu',   '1', 10, '0', '0', 'm1-seed', '0'),
  (3001, '我的工作', 'mold:stage:mine',    '/mold/my-work',     'mold/my-work/index',     0,    'iconfont icon-renwu',     '1', 20, '0', '0', 'm1-seed', '0'),
  (3002, '采购台账', 'mold:purchase:list', '/mold/purchases',   'mold/purchases/index',   0,    'iconfont icon-caigou',    '1', 30, '0', '0', 'm1-seed', '0'),
  (3003, '财务收支', 'mold:cash:list',     '/mold/cash',        'mold/cash/index',        0,    'iconfont icon-zhangdan',  '1', 40, '0', '0', 'm1-seed', '0'),
  (3004, '更正记录', 'mold:correction:list','/mold/corrections','mold/corrections/index', 0,    'iconfont icon-gengzheng', '1', 50, '0', '0', 'm1-seed', '0'),
  (3005, '阶段历史', 'mold:stage:timeline','/mold/timeline',    'mold/timeline/index',    0,    'iconfont icon-shijian',   '1', 60, '0', '0', 'm1-seed', '0'),
  (3006, '账号管理', 'mold:user:list',     '/mold/users',       'mold/users/index',       0,    'iconfont icon-zhanghao',  '1', 70, '0', '0', 'm1-seed', '0'),
  (3007, '审计日志', 'mold:audit:list',    '/mold/audit',       'mold/audit/index',       0,    'iconfont icon-rizhi',     '1', 80, '0', '0', 'm1-seed', '0'),
  -- 3.2 隐藏父节点：承载跨页面的通用权限（附件、通讯录）
  (3099, '通用权限', NULL, NULL, NULL, 0, NULL, '0', 99, '0', '0', 'm1-seed', '0'),

  -- 3.3 公司进度（3000）按钮
  (3101, '新增项目',   'mold:project:create',   NULL, NULL, 3000, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3102, '编辑项目',   'mold:project:update',   NULL, NULL, 3000, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3103, '确认完工',   'mold:project:complete', NULL, NULL, 3000, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3104, '重新打开',   'mold:project:reopen',   NULL, NULL, 3000, NULL, '0', 4, '0', '1', 'm1-seed', '0'),
  (3105, '变更负责人', 'mold:project:owner',    NULL, NULL, 3000, NULL, '0', 5, '0', '1', 'm1-seed', '0'),
  (3106, '维护成员',   'mold:project:member',   NULL, NULL, 3000, NULL, '0', 6, '0', '1', 'm1-seed', '0'),
  (3107, '导出项目',   'mold:project:export',   NULL, NULL, 3000, NULL, '0', 7, '0', '1', 'm1-seed', '0'),
  (3108, '登记异常',   'mold:issue:create',     NULL, NULL, 3000, NULL, '0', 8, '0', '1', 'm1-seed', '0'),
  (3109, '处理异常',   'mold:issue:handle',     NULL, NULL, 3000, NULL, '0', 9, '0', '1', 'm1-seed', '0'),

  -- 3.4 我的工作（3001）按钮
  (3111, '更新阶段状态', 'mold:stage:transition', NULL, NULL, 3001, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3112, '编辑阶段',     'mold:stage:update',     NULL, NULL, 3001, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3113, '新增阶段',     'mold:stage:create',     NULL, NULL, 3001, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3114, '指派阶段人员', 'mold:stage:assign',     NULL, NULL, 3001, NULL, '0', 4, '0', '1', 'm1-seed', '0'),

  -- 3.5 采购台账（3002）按钮
  (3121, '新增采购',   'mold:purchase:create',  NULL, NULL, 3002, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3122, '编辑采购',   'mold:purchase:update',  NULL, NULL, 3002, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3123, '确认采购',   'mold:purchase:confirm', NULL, NULL, 3002, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3124, '作废采购',   'mold:purchase:void',    NULL, NULL, 3002, NULL, '0', 4, '0', '1', 'm1-seed', '0'),
  (3125, '登记收货',   'mold:receipt:create',   NULL, NULL, 3002, NULL, '0', 5, '0', '1', 'm1-seed', '0'),
  (3126, '作废收货',   'mold:receipt:void',     NULL, NULL, 3002, NULL, '0', 6, '0', '1', 'm1-seed', '0'),
  (3127, '登记发票',   'mold:invoice:create',   NULL, NULL, 3002, NULL, '0', 7, '0', '1', 'm1-seed', '0'),
  (3128, '作废发票',   'mold:invoice:void',     NULL, NULL, 3002, NULL, '0', 8, '0', '1', 'm1-seed', '0'),
  (3129, '导出采购',   'mold:purchase:export',  NULL, NULL, 3002, NULL, '0', 9, '0', '1', 'm1-seed', '0'),
  (3130, '新增往来单位','mold:counterparty:create', NULL, NULL, 3002, NULL, '0', 10, '0', '1', 'm1-seed', '0'),
  (3131, '编辑往来单位','mold:counterparty:update', NULL, NULL, 3002, NULL, '0', 11, '0', '1', 'm1-seed', '0'),

  -- 3.6 财务收支（3003）按钮
  (3141, '登记收支',   'mold:cash:create',      NULL, NULL, 3003, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3142, '编辑收支',   'mold:cash:update',      NULL, NULL, 3003, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3143, '作废收支',   'mold:cash:void',        NULL, NULL, 3003, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3144, '红冲收支',   'mold:cash:reverse',     NULL, NULL, 3003, NULL, '0', 4, '0', '1', 'm1-seed', '0'),
  (3145, '标记对账',   'mold:cash:reconcile',   NULL, NULL, 3003, NULL, '0', 5, '0', '1', 'm1-seed', '0'),
  (3146, '取消对账',   'mold:cash:unreconcile', NULL, NULL, 3003, NULL, '0', 6, '0', '1', 'm1-seed', '0'),
  (3147, '导出收支',   'mold:cash:export',      NULL, NULL, 3003, NULL, '0', 7, '0', '1', 'm1-seed', '0'),

  -- 3.7 更正记录（3004）按钮
  (3151, '更正预览',   'mold:correction:preview', NULL, NULL, 3004, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3152, '执行更正',   'mold:correction:apply',   NULL, NULL, 3004, NULL, '0', 2, '0', '1', 'm1-seed', '0'),

  -- 3.8 阶段历史（3005）按钮
  (3161, '导出时间线', 'mold:stage:export', NULL, NULL, 3005, NULL, '0', 1, '0', '1', 'm1-seed', '0'),

  -- 3.9 账号管理（3006）按钮
  (3171, '新增账号',   'mold:user:create',         NULL, NULL, 3006, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3172, '编辑账号',   'mold:user:update',         NULL, NULL, 3006, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3173, '启用/停用',  'mold:user:status',         NULL, NULL, 3006, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3174, '分配角色',   'mold:user:role',           NULL, NULL, 3006, NULL, '0', 4, '0', '1', 'm1-seed', '0'),
  (3175, '重置密码',   'mold:user:password-reset', NULL, NULL, 3006, NULL, '0', 5, '0', '1', 'm1-seed', '0'),

  -- 3.10 审计日志（3007）按钮
  (3181, '导出审计',   'mold:audit:export', NULL, NULL, 3007, NULL, '0', 1, '0', '1', 'm1-seed', '0'),

  -- 3.11 通用权限（3099）按钮
  (3191, '上传附件',   'mold:attachment:upload',   NULL, NULL, 3099, NULL, '0', 1, '0', '1', 'm1-seed', '0'),
  (3192, '下载附件',   'mold:attachment:download', NULL, NULL, 3099, NULL, '0', 2, '0', '1', 'm1-seed', '0'),
  (3193, '删除附件',   'mold:attachment:delete',   NULL, NULL, 3099, NULL, '0', 3, '0', '1', 'm1-seed', '0'),
  (3194, '通讯录',     'mold:directory:list',      NULL, NULL, 3099, NULL, '0', 4, '0', '1', 'm1-seed', '0')
ON DUPLICATE KEY UPDATE
  `name`       = VALUES(`name`),
  `permission` = VALUES(`permission`),
  `path`       = VALUES(`path`),
  `component`  = VALUES(`component`),
  `parent_id`  = VALUES(`parent_id`),
  `visible`    = VALUES(`visible`),
  `sort_order` = VALUES(`sort_order`),
  `menu_type`  = VALUES(`menu_type`),
  `del_flag`   = '0';

-- -----------------------------------------------------------------------------
-- 4. 角色 → 菜单/按钮授权
--    逐角色按“页面 ID + 权限串”授权，权限串是唯一判据（Pig 按 permission 校验）。
--    与旧系统 caps() 的对应关系见文档第 5.3 节，语义完全一致。
-- -----------------------------------------------------------------------------
-- 4.1 OWNER：全部（8 页 + 隐藏父节点 + 44 个按钮）
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 10, `menu_id` FROM `sys_menu`
WHERE `menu_id` BETWEEN 3000 AND 3199;

-- 4.2 PM：项目/阶段/异常/附件/通讯录，无采购/财务/账号/审计
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 11, `menu_id` FROM `sys_menu`
WHERE `menu_id` IN (3000,3001,3005,3099)
   OR `permission` IN (
        'mold:project:create','mold:project:update','mold:project:complete',
        'mold:project:reopen','mold:project:owner','mold:project:member',
        'mold:project:export','mold:issue:create','mold:issue:handle',
        'mold:stage:create','mold:stage:update','mold:stage:transition',
        'mold:stage:assign','mold:stage:export',
        'mold:attachment:upload','mold:attachment:download','mold:attachment:delete',
        'mold:directory:list');

-- 4.3 PROGRAMMER：看基础进度，只更新自己负责的阶段
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 12, `menu_id` FROM `sys_menu`
WHERE `menu_id` IN (3000,3001,3005,3099)
   OR `permission` IN (
        'mold:issue:create','mold:issue:handle',
        'mold:stage:update','mold:stage:transition',
        'mold:attachment:upload','mold:attachment:download',
        'mold:directory:list');

-- 4.4 MACHINIST：同 PROGRAMMER（数据范围不同，权限串相同）
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 13, `menu_id` FROM `sys_menu`
WHERE `menu_id` IN (3000,3001,3005,3099)
   OR `permission` IN (
        'mold:issue:create','mold:issue:handle',
        'mold:stage:update','mold:stage:transition',
        'mold:attachment:upload','mold:attachment:download',
        'mold:directory:list');

-- 4.5 FINANCE：采购 + 收支 + 更正，无项目编辑/阶段/账号/审计
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 14, `menu_id` FROM `sys_menu`
WHERE `menu_id` IN (3000,3002,3003,3004,3005,3099)
   OR `permission` IN (
        'mold:purchase:create','mold:purchase:update','mold:purchase:confirm',
        'mold:purchase:void','mold:purchase:export',
        'mold:receipt:create','mold:receipt:void',
        'mold:invoice:create','mold:invoice:void',
        'mold:counterparty:create','mold:counterparty:update',
        'mold:cash:create','mold:cash:update','mold:cash:void','mold:cash:reverse',
        'mold:cash:reconcile','mold:cash:unreconcile','mold:cash:export',
        'mold:correction:preview','mold:correction:apply',
        'mold:issue:create','mold:issue:handle',
        'mold:attachment:upload','mold:attachment:download',
        'mold:directory:list');

-- 4.6 VIEWER：只读
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 15, `menu_id` FROM `sys_menu`
WHERE `menu_id` IN (3000,3005,3099)
   OR `permission` IN (
        'mold:issue:create','mold:attachment:download','mold:directory:list');

-- -----------------------------------------------------------------------------
-- 5. 自检
-- -----------------------------------------------------------------------------
SELECT 'roles'   AS what, COUNT(*) AS n FROM `sys_role` WHERE `role_id` BETWEEN 10 AND 15
UNION ALL SELECT 'menus',   COUNT(*) FROM `sys_menu`     WHERE `menu_id` BETWEEN 3000 AND 3199
UNION ALL SELECT 'grants',  COUNT(*) FROM `sys_role_menu` WHERE `role_id` BETWEEN 10 AND 15;
