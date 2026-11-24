-- 变更项目负责人仅老板（OWNER）可操作（2026-11 拍板）：收回 PM 的 mold:project:owner。
-- 目标库：mold_dev。幂等。执行后清理 Redis 缓存 gl:menu_details::* 与 gl:user_details::*。
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.`role_id` = rm.`role_id`
JOIN `sys_menu` m ON m.`menu_id` = rm.`menu_id`
WHERE r.`role_code` = 'PM' AND m.`permission` = 'mold:project:owner';
