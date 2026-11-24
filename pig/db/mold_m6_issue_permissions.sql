-- M6 前置：补 mold:issue:update / mold:issue:cancel 菜单种子与角色绑定
-- 目标库：mold_dev（不要在生产切换前对 pig 库执行）
-- 依据：契约 6.7 权限表——issue:create 行包含 create/update/cancel，绑定全部 6 个固定角色。
-- 幂等：菜单按 permission 判重；角色绑定按 (role_id, menu_id) 判重；可重复执行。
-- 影响：mold 菜单行 53 → 55（含隐藏父节点 3099），mold 授权行 134 → 146（6 角色各 +2）。
-- 不改动任何既有菜单或授权行。

-- 1) 按钮：编辑异常 / 作废异常（menu_type=1，挂在「公司进度」3000 下）
INSERT INTO `sys_menu` (`menu_id`, `name`, `permission`, `path`, `component`, `parent_id`, `icon`, `visible`,
    `sort_order`, `keep_alive`, `embedded`, `menu_type`, `create_by`, `create_time`, `update_by`, `update_time`,
    `del_flag`)
SELECT 3195, '编辑异常', 'mold:issue:update', NULL, NULL, 3000, NULL, '0', 10, '0', NULL, '1', 'm6prep',
       UTC_TIMESTAMP(), NULL, NULL, '0'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `permission` = 'mold:issue:update');

INSERT INTO `sys_menu` (`menu_id`, `name`, `permission`, `path`, `component`, `parent_id`, `icon`, `visible`,
    `sort_order`, `keep_alive`, `embedded`, `menu_type`, `create_by`, `create_time`, `update_by`, `update_time`,
    `del_flag`)
SELECT 3196, '作废异常', 'mold:issue:cancel', NULL, NULL, 3000, NULL, '0', 11, '0', NULL, '1', 'm6prep',
       UTC_TIMESTAMP(), NULL, NULL, '0'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `permission` = 'mold:issue:cancel');

-- 2) 绑定：OWNER / PM / PROGRAMMER / MACHINIST / FINANCE（VIEWER 为只读，不授异常写权限）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`role_id`, m.`menu_id`
FROM `sys_role` r
JOIN `sys_menu` m ON m.`permission` IN ('mold:issue:update', 'mold:issue:cancel')
WHERE r.`role_code` IN ('OWNER', 'PM', 'PROGRAMMER', 'MACHINIST', 'FINANCE')
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` rm
                  WHERE rm.`role_id` = r.`role_id` AND rm.`menu_id` = m.`menu_id`);

-- 3) 校验（执行后可单独跑）
-- SELECT m.permission, GROUP_CONCAT(r.role_code ORDER BY r.role_id) AS roles
--   FROM sys_role_menu rm
--   JOIN sys_menu m ON m.menu_id = rm.menu_id
--   JOIN sys_role r ON r.role_id = rm.role_id
--  WHERE m.permission IN ('mold:issue:update','mold:issue:cancel')
--  GROUP BY m.permission;
