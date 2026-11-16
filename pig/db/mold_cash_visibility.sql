-- 流水可见性决策 B（2026-11-15）
-- 规则：OWNER/FINANCE 看全部；其余角色只看自己录入的（cash_entries.created_by = 本人）。
-- 新增 mold:cash:viewAll 仅授 OWNER/FINANCE；cash:list/create 授 PM/PROGRAMMER/MACHINIST。
-- VIEWER 保持只读，不授任何 cash 权限。
-- 改/作废/红冲仍归财务：11/12/13 不授 3142(update) / 3143(void)。
-- 幂等：可重复执行。
--
-- 2026-11-15 收窄（01 派单 b7aad7a0 §3「本轮不改 update/void」）：
--   首版曾给 11/12/13 授 update+void，与派单边界不符，已 DELETE 收回；
--   下面第二条 DELETE 保留为幂等收口，重复执行无副作用。

USE mold_dev;

INSERT IGNORE INTO sys_menu
	(menu_id, name, permission, parent_id, visible, sort_order, keep_alive, embedded, menu_type, del_flag, create_by)
VALUES
	(3148, '查看全部流水', 'mold:cash:viewAll', 3003, '0', 8, '0', NULL, '1', '0', '07-a7');

-- viewAll：仅 OWNER(10) / FINANCE(14)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
	(10, 3148),
	(14, 3148);

-- 记账 + 看自己：PM(11) / PROGRAMMER(12) / MACHINIST(13)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
	(11, 3003), (11, 3141),
	(12, 3003), (12, 3141),
	(13, 3003), (13, 3141);

-- 收口：非财务不得改/作废（3142 update、3143 void）
DELETE FROM sys_role_menu WHERE role_id IN (11, 12, 13) AND menu_id IN (3142, 3143);
