-- 清理端到端测试与界面实操留下的测试数据（2026-11-24，执行前已备份 deploy/backups/20261124T071437Z）。
-- 原则：
--   * 审计日志与阶段时间线是只追加表（触发器禁止删改），保留；它们引用的项目/阶段/账号因此只能软删除。
--   * 项目、阶段、异常、附件：软删除（deleted_at），历史可追溯。
--   * 流水、采购（含明细/收货/发票）、往来单位：没有软删除字段，且全部由 e2e_ 测试账号创建，物理删除。
--   * 测试账号由脚本经接口停用（会同时吊销会话），不在这里处理。
START TRANSACTION;

SELECT id INTO @boss FROM users WHERE login_name = 'owner';

SELECT COUNT(*) INTO @real_projects FROM projects
 WHERE deleted_at IS NULL AND code NOT LIKE 'E2E-%' AND code NOT LIKE 'UI-%';

-- 1) 流水：只删 e2e_ 账号录入的；互相引用（冲正/更正链）所以临时关闭外键检查
SET FOREIGN_KEY_CHECKS = 0;
DELETE c FROM cash_entries c JOIN users u ON u.id = c.created_by WHERE u.login_name LIKE 'e2e%';
SET FOREIGN_KEY_CHECKS = 1;

-- 2) 采购及其明细、收货、发票
DELETE ri FROM purchase_receipt_items ri
  JOIN purchase_receipts r ON r.id = ri.receipt_id
  JOIN purchases p ON p.id = r.purchase_id
  JOIN users u ON u.id = p.created_by WHERE u.login_name LIKE 'e2e%';
DELETE r FROM purchase_receipts r JOIN purchases p ON p.id = r.purchase_id
  JOIN users u ON u.id = p.created_by WHERE u.login_name LIKE 'e2e%';
DELETE i FROM purchase_invoices i JOIN purchases p ON p.id = i.purchase_id
  JOIN users u ON u.id = p.created_by WHERE u.login_name LIKE 'e2e%';
DELETE it FROM purchase_items it JOIN purchases p ON p.id = it.purchase_id
  JOIN users u ON u.id = p.created_by WHERE u.login_name LIKE 'e2e%';
DELETE p FROM purchases p JOIN users u ON u.id = p.created_by WHERE u.login_name LIKE 'e2e%';

-- 3) 测试项目：解除客户引用后软删除；其阶段、异常、附件一并软删除
UPDATE projects SET customer_id = NULL WHERE code LIKE 'E2E-%' OR code LIKE 'UI-%';
UPDATE project_stages s JOIN projects p ON p.id = s.project_id
   SET s.deleted_at = UTC_TIMESTAMP(3)
 WHERE (p.code LIKE 'E2E-%' OR p.code LIKE 'UI-%') AND s.deleted_at IS NULL;
UPDATE issues i JOIN projects p ON p.id = i.project_id
   SET i.deleted_at = UTC_TIMESTAMP(3)
 WHERE (p.code LIKE 'E2E-%' OR p.code LIKE 'UI-%') AND i.deleted_at IS NULL;
UPDATE attachments a JOIN users u ON u.id = a.uploaded_by
   SET a.status = 'DELETED', a.deleted_at = UTC_TIMESTAMP(3), a.deleted_by = @boss,
       a.deleted_reason = '清理测试数据'
 WHERE u.login_name LIKE 'e2e%' AND a.deleted_at IS NULL;
UPDATE projects SET deleted_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3)
 WHERE (code LIKE 'E2E-%' OR code LIKE 'UI-%') AND deleted_at IS NULL;

-- 4) 往来单位：只删 e2e_ 账号创建的
DELETE c FROM counterparties c JOIN users u ON u.id = c.created_by WHERE u.login_name LIKE 'e2e%';

COMMIT;

SELECT @real_projects AS real_projects_untouched,
       (SELECT COUNT(*) FROM projects WHERE deleted_at IS NULL) AS live_projects,
       (SELECT COUNT(*) FROM project_stages WHERE deleted_at IS NULL) AS live_stages,
       (SELECT COUNT(*) FROM issues WHERE deleted_at IS NULL) AS live_issues,
       (SELECT COUNT(*) FROM attachments WHERE deleted_at IS NULL) AS live_attachments,
       (SELECT COUNT(*) FROM cash_entries) AS cash_entries,
       (SELECT COUNT(*) FROM purchases) AS purchases,
       (SELECT COUNT(*) FROM counterparties) AS counterparties,
       (SELECT COUNT(*) FROM audit_logs) AS audit_logs_kept;
