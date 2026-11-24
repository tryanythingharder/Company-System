-- 只读角色（VIEWER）收回异常写权限：issue:create / issue:update / issue:cancel / issue:handle
-- 目标库：mold_dev。幂等，可重复执行。
-- 执行后必须清理 Redis 菜单缓存 menu_details::*，否则接口仍读到旧授权。
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`role_id` = rm.`role_id`
JOIN `sys_menu` m ON m.`menu_id` = rm.`menu_id`
WHERE r.`role_code` = 'VIEWER'
  AND m.`permission` IN ('mold:issue:create', 'mold:issue:update', 'mold:issue:cancel', 'mold:issue:handle');
