-- =============================================================================
-- 模具加工管理系统 · 业务库 DDL v1.0（M1 交付物）
-- =============================================================================
-- 源库      : PostgreSQL 16 / mold_collaboration（22 表 / 262 字段 / 60 外键）
-- 目标      : MySQL 8.0.32 / InnoDB / utf8mb4 / utf8mb4_general_ci
-- 生成日期  : 2026-11-12
-- 配套文档  : coordination/pig/architecture/phase1-model-contract-20261112.md
--
-- 类型映射约定
--   uuid                     -> char(36)                （应用生成 UUIDv4；DB 默认值见下）
--   timestamptz              -> datetime(3)，一律存 UTC（MySQL DATETIME 不做时区换算）
--   numeric(p,s)             -> decimal(14,2|4|6)       （金额 14,2 / 数量 14,4 / 单价 14,6）
--   boolean                  -> tinyint(1)
--   jsonb                    -> json
--   inet                     -> varchar(45)             （IPv6 最长 45 字符）
--   text（需索引/唯一键）    -> varchar(n)               （MySQL 不能对 TEXT 直接建唯一索引）
--   text（自由文本）         -> text
--   PG ENUM 语义（CHECK ... = ANY(ARRAY[...])） -> varchar(n) + CHECK (... IN (...))
--
-- 与 PG 的刻意差异（详见文档第 4 节）
--   1. lower(trim(x)) 表达式唯一索引（*_code_ci / users_login_ci）不再单独建：
--      目标库排序规则为 utf8mb4_general_ci，普通 UNIQUE 已大小写不敏感，再配
--      CHECK (x = TRIM(x)) 即可等价，避免 MySQL 函数索引带来的隐式列。
--   2. PG 部分唯一索引（WHERE ...）改为“生成列 + 唯一索引”：
--        project_stages.alive_key
--        purchase_invoices.active_invoice_no
--      这两个生成列是系统辅助列，不计入 262 个业务字段。
--   3. 时间默认值用 UTC_TIMESTAMP(3) 而不是 CURRENT_TIMESTAMP(3)：
--      避免连接会话时区影响写入值，保证 datetime(3) 一律为 UTC。
--   4. 外键全部保留（60 条），仅 purchase_items -> purchases 为 ON DELETE CASCADE，
--      其余 ON DELETE RESTRICT / ON UPDATE RESTRICT（与 PG 的 NO ACTION 等价）。
--   5. append-only 与事件游标改为 MySQL 触发器（见文件末尾第 8 节）。
--
-- 执行方式（空库）
--   docker exec -i pig-spike-mysql mysql -uroot -proot < db/mysql/mold_schema.sql
-- 若目标库名不是 mold_dev，删除文件顶部 CREATE DATABASE / USE 两行后自行指定库。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `mold_dev`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `mold_dev`;

SET NAMES utf8mb4;

-- =============================================================================
-- 1. 账号与会话
-- =============================================================================

-- 1.1 users（12 字段）
-- Pig 的 sys_user 负责登录凭据与菜单权限；本表保留业务身份、审计归属与版本号。
-- 两表的关联在 db/mysql/mold_pig_bridge.sql 中建立（users.sys_user_id）。
CREATE TABLE `users` (
  `id`                   char(36)     NOT NULL DEFAULT (uuid()),
  `login_name`           varchar(64)  NOT NULL COMMENT '登录名，大小写不敏感（ci 排序规则）',
  `display_name`         varchar(128) NOT NULL,
  `phone`                varchar(32)  DEFAULT NULL,
  `password_hash`        varchar(255) NOT NULL COMMENT 'bcrypt/argon2 串，长度上限 255',
  `status`               varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  `last_login_at`        datetime(3)  DEFAULT NULL,
  `created_at`           datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_at`           datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `version`              int          NOT NULL DEFAULT 1,
  `password_changed_at`  datetime(3)  DEFAULT NULL,
  `must_change_password` tinyint(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `users_login_name_key` (`login_name`),
  KEY `users_display_sort_idx` (`display_name`, `id`),
  KEY `users_login_sort_idx` (`login_name`, `id`),
  CONSTRAINT `users_status_check` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT `users_login_name_trim_chk` CHECK (`login_name` = TRIM(`login_name`) AND `login_name` <> ''),
  CONSTRAINT `users_version_chk` CHECK (`version` >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '业务用户（身份/审计归属）；登录凭据见 Pig sys_user';

-- 1.2 sessions（10 字段）
-- 切换后若改用 Pig 的 Redis token 存储，本表降级为“登录设备审计”，结构保留。
CREATE TABLE `sessions` (
  `id`              char(36)     NOT NULL DEFAULT (uuid()),
  `user_id`         char(36)     NOT NULL,
  `token_hash`      varchar(128) NOT NULL,
  `csrf_token_hash` varchar(128) NOT NULL,
  `created_at`      datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `last_seen_at`    datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `expires_at`      datetime(3)  NOT NULL,
  `revoked_at`      datetime(3)  DEFAULT NULL,
  `ip`              varchar(45)  DEFAULT NULL,
  `user_agent`      text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `sessions_token_hash_key` (`token_hash`),
  KEY `sessions_user_idx` (`user_id`),
  KEY `sessions_exp_idx` (`expires_at`),
  CONSTRAINT `sessions_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '登录会话（token 哈希 + CSRF 哈希）';

-- 1.3 user_roles（4 字段）
-- 六固定角色落在 sys_role（role_code 同值），本表保留“谁在何时被谁授予”，
-- 是审计与越权排查的事实来源。
CREATE TABLE `user_roles` (
  `user_id`    char(36)    NOT NULL,
  `role_code`  varchar(32) NOT NULL,
  `granted_by` char(36)    DEFAULT NULL,
  `granted_at` datetime(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (`user_id`, `role_code`),
  KEY `user_roles_granted_by_idx` (`granted_by`),
  CONSTRAINT `user_roles_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `user_roles_granted_by_fkey` FOREIGN KEY (`granted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `user_roles_role_code_check`
    CHECK (`role_code` IN ('OWNER', 'PM', 'PROGRAMMER', 'MACHINIST', 'FINANCE', 'VIEWER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '角色授予记录（六固定角色）';

-- =============================================================================
-- 2. 主数据
-- =============================================================================

-- 2.1 counterparties（12 字段）
CREATE TABLE `counterparties` (
  `id`           char(36)     NOT NULL DEFAULT (uuid()),
  `type`         varchar(16)  NOT NULL,
  `name`         varchar(200) NOT NULL,
  `contact_name` varchar(64)  DEFAULT NULL,
  `phone`        varchar(32)  DEFAULT NULL,
  `tax_no`       varchar(64)  DEFAULT NULL,
  `note`         text,
  `status`       varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  `created_by`   char(36)     DEFAULT NULL,
  `created_at`   datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_at`   datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `version`      int          NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `counterparties_name_sort_idx` (`name`, `id`),
  CONSTRAINT `counterparties_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `counterparties_type_check` CHECK (`type` IN ('CUSTOMER', 'SUPPLIER', 'OTHER')),
  CONSTRAINT `counterparties_status_check` CHECK (`status` IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT `counterparties_version_chk` CHECK (`version` >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '客户 / 供应商 / 其他往来单位';

-- =============================================================================
-- 3. 项目与阶段
-- =============================================================================

-- 3.1 projects（21 字段）
CREATE TABLE `projects` (
  `id`              char(36)     NOT NULL DEFAULT (uuid()),
  `code`            varchar(64)  NOT NULL,
  `name`            varchar(200) NOT NULL,
  `business_type`   varchar(16)  NOT NULL,
  `customer_id`     char(36)     DEFAULT NULL,
  `owner_user_id`   char(36)     NOT NULL COMMENT '项目负责人，完工由该人手工确认',
  `due_date`        date         DEFAULT NULL,
  `status`          varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  `completed_at`    datetime(3)  DEFAULT NULL,
  `completed_by`    char(36)     DEFAULT NULL,
  `completion_note` text,
  `reopened_at`     datetime(3)  DEFAULT NULL,
  `reopened_by`     char(36)     DEFAULT NULL,
  `reopen_reason`   text,
  `note`            text,
  `version`         int          NOT NULL DEFAULT 1,
  `created_by`      char(36)     NOT NULL,
  `created_at`      datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_by`      char(36)     DEFAULT NULL,
  `updated_at`      datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `deleted_at`      datetime(3)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `projects_code_key` (`code`),
  KEY `projects_code_sort_idx` (`code`, `id`),
  KEY `projects_status_idx` (`status`, `due_date`),
  KEY `projects_updated_idx` (`updated_at`),
  KEY `projects_updated_sort_idx` (`updated_at`, `id`),
  KEY `projects_customer_idx` (`customer_id`),
  KEY `projects_owner_idx` (`owner_user_id`),
  CONSTRAINT `projects_customer_id_fkey` FOREIGN KEY (`customer_id`) REFERENCES `counterparties` (`id`),
  CONSTRAINT `projects_owner_user_id_fkey` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `projects_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `projects_updated_by_fkey` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `projects_completed_by_fkey` FOREIGN KEY (`completed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `projects_reopened_by_fkey` FOREIGN KEY (`reopened_by`) REFERENCES `users` (`id`),
  CONSTRAINT `projects_business_type_check` CHECK (`business_type` IN ('MOLD_SET', 'PART_PROCESS')),
  CONSTRAINT `projects_status_check` CHECK (`status` IN ('ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT `projects_code_trim_chk` CHECK (`code` = TRIM(`code`) AND `code` <> ''),
  CONSTRAINT `projects_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `completed_fields` CHECK (
    `status` <> 'COMPLETED'
    OR (`completed_at` IS NOT NULL
        AND `completed_by` IS NOT NULL
        AND CHAR_LENGTH(TRIM(`completion_note`)) > 0)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '项目（模具套 / 零件工序）';

-- 3.2 project_members（5 字段）
CREATE TABLE `project_members` (
  `project_id`   char(36)    NOT NULL,
  `user_id`      char(36)    NOT NULL,
  `access_level` varchar(8)  NOT NULL DEFAULT 'VIEW',
  `added_by`     char(36)    DEFAULT NULL,
  `added_at`     datetime(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (`project_id`, `user_id`),
  KEY `project_members_user_idx` (`user_id`),
  CONSTRAINT `project_members_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `project_members_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `project_members_added_by_fkey` FOREIGN KEY (`added_by`) REFERENCES `users` (`id`),
  CONSTRAINT `project_members_access_level_check` CHECK (`access_level` IN ('VIEW', 'EDIT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '项目成员（项目级可见/可编辑）';

-- 3.3 project_stages（17 字段 + 1 生成列）
CREATE TABLE `project_stages` (
  `id`                  char(36)     NOT NULL DEFAULT (uuid()),
  `project_id`          char(36)     NOT NULL,
  `name`                varchar(200) NOT NULL,
  `sort_order`          int          NOT NULL DEFAULT 0,
  `responsible_user_id` char(36)     DEFAULT NULL,
  `planned_start`       date         DEFAULT NULL,
  `planned_end`         date         DEFAULT NULL,
  `actual_start`        date         DEFAULT NULL,
  `actual_end`          date         DEFAULT NULL,
  `status`              varchar(16)  NOT NULL DEFAULT 'NOT_STARTED',
  `note`                text,
  `version`             int          NOT NULL DEFAULT 1,
  `created_by`          char(36)     NOT NULL,
  `created_at`          datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_by`          char(36)     DEFAULT NULL,
  `updated_at`          datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `deleted_at`          datetime(3)  DEFAULT NULL,
  `alive_key`           tinyint GENERATED ALWAYS AS (IF(`deleted_at` IS NULL, 1, NULL)) STORED
                        COMMENT '系统辅助列：替代 PG 部分唯一索引 WHERE deleted_at IS NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `project_stages_name_uq` (`project_id`, `name`, `alive_key`),
  KEY `project_stages_project_idx` (`project_id`, `status`),
  KEY `project_stages_responsible_idx` (`responsible_user_id`),
  CONSTRAINT `project_stages_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `project_stages_responsible_user_id_fkey` FOREIGN KEY (`responsible_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `project_stages_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `project_stages_updated_by_fkey` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `project_stages_status_check`
    CHECK (`status` IN ('NOT_STARTED', 'IN_PROGRESS', 'BLOCKED', 'COMPLETED')),
  CONSTRAINT `project_stages_name_trim_chk` CHECK (`name` = TRIM(`name`) AND `name` <> ''),
  CONSTRAINT `project_stages_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `project_stages_dates_chk`
    CHECK (`planned_end` IS NULL OR `planned_start` IS NULL OR `planned_end` >= `planned_start`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '项目阶段（进度跟踪的最小单位）';

-- 3.4 stage_members（3 字段）
CREATE TABLE `stage_members` (
  `stage_id` char(36)    NOT NULL,
  `user_id`  char(36)    NOT NULL,
  `duty`     varchar(32) DEFAULT NULL,
  PRIMARY KEY (`stage_id`, `user_id`),
  KEY `stage_members_user_idx` (`user_id`),
  CONSTRAINT `stage_members_stage_id_fkey` FOREIGN KEY (`stage_id`) REFERENCES `project_stages` (`id`),
  CONSTRAINT `stage_members_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '阶段参与人（编程 / 操机）';

-- 3.5 stage_events（11 字段，append-only）
CREATE TABLE `stage_events` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `stage_id`    char(36)     NOT NULL,
  `action`      varchar(64)  NOT NULL,
  `from_status` varchar(16)  DEFAULT NULL,
  `to_status`   varchar(16)  DEFAULT NULL,
  `note`        text,
  `issue_id`    char(36)     DEFAULT NULL,
  `actor_id`    char(36)     NOT NULL,
  `request_id`  varchar(64)  DEFAULT NULL,
  `created_at`  datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `public_id`   char(36)     NOT NULL DEFAULT (uuid()) COMMENT '对外暴露的时间线 ID，避免泄露自增序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `stage_events_public_id_key` (`public_id`),
  KEY `timeline_idx` (`stage_id`, `id`),
  KEY `stage_events_issue_idx` (`issue_id`),
  CONSTRAINT `stage_events_stage_id_fkey` FOREIGN KEY (`stage_id`) REFERENCES `project_stages` (`id`),
  CONSTRAINT `stage_events_actor_id_fkey` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '阶段时间线事件（只允许 INSERT）';

-- 3.6 issues（16 字段）
CREATE TABLE `issues` (
  `id`              char(36)     NOT NULL DEFAULT (uuid()),
  `project_id`      char(36)     NOT NULL,
  `stage_id`        char(36)     DEFAULT NULL,
  `title`           varchar(200) NOT NULL,
  `description`     text         NOT NULL,
  `severity`        varchar(8)   NOT NULL DEFAULT 'MEDIUM',
  `status`          varchar(16)  NOT NULL DEFAULT 'OPEN',
  `reporter_id`     char(36)     NOT NULL,
  `handler_id`      char(36)     DEFAULT NULL,
  `occurred_at`     datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `resolved_at`     datetime(3)  DEFAULT NULL,
  `resolution_note` text,
  `version`         int          NOT NULL DEFAULT 1,
  `created_at`      datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_at`      datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `deleted_at`      datetime(3)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `issues_project_idx` (`project_id`, `status`),
  KEY `issues_stage_idx` (`stage_id`),
  KEY `issues_reporter_idx` (`reporter_id`),
  KEY `issues_handler_idx` (`handler_id`),
  CONSTRAINT `issues_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `issues_stage_id_fkey` FOREIGN KEY (`stage_id`) REFERENCES `project_stages` (`id`),
  CONSTRAINT `issues_reporter_id_fkey` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`),
  CONSTRAINT `issues_handler_id_fkey` FOREIGN KEY (`handler_id`) REFERENCES `users` (`id`),
  CONSTRAINT `issues_severity_check` CHECK (`severity` IN ('LOW', 'MEDIUM', 'HIGH')),
  CONSTRAINT `issues_status_check` CHECK (`status` IN ('OPEN', 'RESOLVED', 'CANCELLED')),
  CONSTRAINT `issues_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `issues_resolved_chk` CHECK (
    `status` <> 'RESOLVED'
    OR (`resolved_at` IS NOT NULL AND CHAR_LENGTH(TRIM(`resolution_note`)) > 0)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '项目异常事项（阶段阻塞的正式记录）';

-- =============================================================================
-- 4. 采购三段式：采购单 → 收货 → 发票
-- =============================================================================

-- 4.1 purchases（21 字段）
CREATE TABLE `purchases` (
  `id`                char(36)     NOT NULL DEFAULT (uuid()),
  `code`              varchar(64)  NOT NULL,
  `counterparty_id`   char(36)     NOT NULL,
  `purchase_date`     date         NOT NULL,
  `project_id`        char(36)     DEFAULT NULL,
  `allocation_status` varchar(16)  NOT NULL DEFAULT 'UNASSIGNED',
  `currency`          char(3)      NOT NULL DEFAULT 'CNY',
  `amount_total`      decimal(14,2) NOT NULL DEFAULT 0.00,
  `tax_included`      tinyint(1)   DEFAULT NULL,
  `status`            varchar(16)  NOT NULL DEFAULT 'DRAFT',
  `note`              text,
  `version`           int          NOT NULL DEFAULT 1,
  `created_by`        char(36)     NOT NULL,
  `created_at`        datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_by`        char(36)     DEFAULT NULL,
  `updated_at`        datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `confirmed_at`      datetime(3)  DEFAULT NULL,
  `confirmed_by`      char(36)     DEFAULT NULL,
  `voided_at`         datetime(3)  DEFAULT NULL,
  `voided_by`         char(36)     DEFAULT NULL,
  `void_reason`       text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `purchases_code_key` (`code`),
  KEY `purchases_code_sort_idx` (`code`, `id`),
  KEY `purchases_date_sort_idx` (`purchase_date`, `id`),
  KEY `purchases_amount_sort_idx` (`amount_total`, `id`),
  KEY `purchases_project_idx` (`project_id`, `status`, `purchase_date`),
  KEY `purchases_counterparty_idx` (`counterparty_id`),
  CONSTRAINT `purchases_counterparty_id_fkey` FOREIGN KEY (`counterparty_id`) REFERENCES `counterparties` (`id`),
  CONSTRAINT `purchases_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `purchases_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchases_updated_by_fkey` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchases_confirmed_by_fkey` FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchases_voided_by_fkey` FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchases_allocation_status_check`
    CHECK (`allocation_status` IN ('PROJECT', 'PUBLIC', 'UNASSIGNED')),
  CONSTRAINT `purchases_status_check` CHECK (`status` IN ('DRAFT', 'CONFIRMED', 'VOID')),
  CONSTRAINT `purchases_amount_total_check` CHECK (`amount_total` >= 0),
  CONSTRAINT `purchases_code_trim_chk` CHECK (`code` = TRIM(`code`) AND `code` <> ''),
  CONSTRAINT `purchases_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `purchases_check` CHECK (
    (`allocation_status` = 'PROJECT' AND `project_id` IS NOT NULL)
    OR (`allocation_status` IN ('PUBLIC', 'UNASSIGNED') AND `project_id` IS NULL)
  ),
  CONSTRAINT `purchases_void_chk` CHECK (
    `status` <> 'VOID' OR (`voided_at` IS NOT NULL AND `voided_by` IS NOT NULL)
  ),
  CONSTRAINT `purchases_confirm_chk` CHECK (
    `status` = 'DRAFT' OR (`confirmed_at` IS NOT NULL AND `confirmed_by` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '采购单（DRAFT → CONFIRMED / VOID）';

-- 4.2 purchase_items（10 字段）
CREATE TABLE `purchase_items` (
  `id`          char(36)      NOT NULL DEFAULT (uuid()),
  `purchase_id` char(36)      NOT NULL,
  `line_no`     int           NOT NULL,
  `name`        varchar(200)  NOT NULL,
  `spec`        varchar(255)  DEFAULT NULL,
  `qty`         decimal(14,4) NOT NULL,
  `unit`        varchar(32)   DEFAULT NULL,
  `unit_price`  decimal(14,6) NOT NULL,
  `amount`      decimal(14,2) NOT NULL,
  `note`        text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `purchase_items_purchase_id_line_no_key` (`purchase_id`, `line_no`),
  CONSTRAINT `purchase_items_purchase_id_fkey` FOREIGN KEY (`purchase_id`)
    REFERENCES `purchases` (`id`) ON DELETE CASCADE,
  CONSTRAINT `purchase_items_qty_check` CHECK (`qty` > 0),
  CONSTRAINT `purchase_items_unit_price_check` CHECK (`unit_price` >= 0),
  CONSTRAINT `purchase_items_amount_check` CHECK (`amount` >= 0),
  CONSTRAINT `purchase_items_line_no_chk` CHECK (`line_no` > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '采购明细行';

-- 4.3 purchase_receipts（11 字段）
CREATE TABLE `purchase_receipts` (
  `id`          char(36)    NOT NULL DEFAULT (uuid()),
  `purchase_id` char(36)    NOT NULL,
  `received_on` date        NOT NULL,
  `received_by` char(36)    NOT NULL,
  `note`        text,
  `status`      varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at`  datetime(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `voided_at`   datetime(3) DEFAULT NULL,
  `voided_by`   char(36)    DEFAULT NULL,
  `void_reason` text,
  `version`     int         NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `purchase_receipts_purchase_idx` (`purchase_id`, `status`),
  CONSTRAINT `purchase_receipts_purchase_id_fkey` FOREIGN KEY (`purchase_id`) REFERENCES `purchases` (`id`),
  CONSTRAINT `purchase_receipts_received_by_fkey` FOREIGN KEY (`received_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchase_receipts_voided_by_fkey` FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchase_receipts_status_check` CHECK (`status` IN ('ACTIVE', 'VOID')),
  CONSTRAINT `purchase_receipts_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `purchase_receipts_void_chk` CHECK (
    `status` <> 'VOID' OR (`voided_at` IS NOT NULL AND `voided_by` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '采购收货单';

-- 4.4 purchase_receipt_items（4 字段）
CREATE TABLE `purchase_receipt_items` (
  `id`               char(36)      NOT NULL DEFAULT (uuid()),
  `receipt_id`       char(36)      NOT NULL,
  `purchase_item_id` char(36)      NOT NULL,
  `qty_received`     decimal(14,4) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `purchase_receipt_items_receipt_id_purchase_item_id_key` (`receipt_id`, `purchase_item_id`),
  KEY `purchase_receipt_items_item_idx` (`purchase_item_id`),
  CONSTRAINT `purchase_receipt_items_receipt_id_fkey` FOREIGN KEY (`receipt_id`) REFERENCES `purchase_receipts` (`id`),
  CONSTRAINT `purchase_receipt_items_purchase_item_id_fkey` FOREIGN KEY (`purchase_item_id`) REFERENCES `purchase_items` (`id`),
  CONSTRAINT `purchase_receipt_items_qty_received_check` CHECK (`qty_received` > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '收货明细（按采购行登记到货数量）';

-- 4.5 purchase_invoices（16 字段 + 1 生成列）
CREATE TABLE `purchase_invoices` (
  `id`              char(36)      NOT NULL DEFAULT (uuid()),
  `purchase_id`     char(36)      NOT NULL,
  `counterparty_id` char(36)      NOT NULL,
  `invoice_no`      varchar(64)   NOT NULL,
  `invoice_type`    varchar(16)   NOT NULL DEFAULT 'OTHER',
  `issue_date`      date          NOT NULL,
  `amount_total`    decimal(14,2) NOT NULL,
  `tax_amount`      decimal(14,2) DEFAULT NULL,
  `currency`        char(3)       NOT NULL DEFAULT 'CNY',
  `status`          varchar(16)   NOT NULL DEFAULT 'ACTIVE',
  `created_by`      char(36)      NOT NULL,
  `created_at`      datetime(3)   NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `voided_at`       datetime(3)   DEFAULT NULL,
  `voided_by`       char(36)      DEFAULT NULL,
  `void_reason`     text,
  `version`         int           NOT NULL DEFAULT 1,
  `active_invoice_no` varchar(64)
    GENERATED ALWAYS AS (IF(`status` = 'ACTIVE' AND `invoice_no` <> '', `invoice_no`, NULL)) STORED
    COMMENT '系统辅助列：替代 PG 部分唯一索引 WHERE status=ACTIVE AND invoice_no<>''',
  PRIMARY KEY (`id`),
  UNIQUE KEY `purchase_invoice_no_uq` (`counterparty_id`, `active_invoice_no`),
  KEY `purchase_invoices_purchase_idx` (`purchase_id`, `status`),
  CONSTRAINT `purchase_invoices_purchase_id_fkey` FOREIGN KEY (`purchase_id`) REFERENCES `purchases` (`id`),
  CONSTRAINT `purchase_invoices_counterparty_id_fkey` FOREIGN KEY (`counterparty_id`) REFERENCES `counterparties` (`id`),
  CONSTRAINT `purchase_invoices_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchase_invoices_voided_by_fkey` FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `purchase_invoices_invoice_type_check`
    CHECK (`invoice_type` IN ('VAT_SPECIAL', 'VAT_NORMAL', 'OTHER')),
  CONSTRAINT `purchase_invoices_status_check` CHECK (`status` IN ('ACTIVE', 'VOID')),
  CONSTRAINT `purchase_invoices_amount_total_check` CHECK (`amount_total` >= 0),
  CONSTRAINT `purchase_invoices_tax_amount_check` CHECK (`tax_amount` >= 0),
  CONSTRAINT `purchase_invoices_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `purchase_invoices_void_chk` CHECK (
    `status` <> 'VOID' OR (`voided_at` IS NOT NULL AND `voided_by` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '采购发票（同一供应商下 ACTIVE 发票号唯一）';

-- =============================================================================
-- 5. 财务台账（含更正链与反冲链）
-- =============================================================================

-- 5.1 cash_entries（31 字段）
CREATE TABLE `cash_entries` (
  `id`                   char(36)      NOT NULL DEFAULT (uuid()),
  `code`                 varchar(64)   NOT NULL,
  `direction`            varchar(8)    NOT NULL,
  `category`             varchar(32)   NOT NULL,
  `occurred_on`          date          NOT NULL,
  `amount`               decimal(14,2) NOT NULL,
  `currency`             char(3)       NOT NULL DEFAULT 'CNY',
  `counterparty_id`      char(36)      DEFAULT NULL,
  `project_id`           char(36)      DEFAULT NULL,
  `purchase_id`          char(36)      DEFAULT NULL,
  `allocation_status`    varchar(16)   NOT NULL DEFAULT 'UNASSIGNED',
  `method`               varchar(16)   NOT NULL DEFAULT 'BANK',
  `voucher_no`           varchar(64)   DEFAULT NULL,
  `note`                 text,
  `status`               varchar(16)   NOT NULL DEFAULT 'CONFIRMED',
  `reverses_entry_id`    char(36)      DEFAULT NULL,
  `reversed_by_entry_id` char(36)      DEFAULT NULL,
  `reversal_reason`      text,
  `reconciled_at`        datetime(3)   DEFAULT NULL,
  `reconciled_by`        char(36)      DEFAULT NULL,
  `reconciliation_ref`   varchar(128)  DEFAULT NULL,
  `voided_at`            datetime(3)   DEFAULT NULL,
  `voided_by`            char(36)      DEFAULT NULL,
  `void_reason`          text,
  `version`              int           NOT NULL DEFAULT 1,
  `created_by`           char(36)      NOT NULL,
  `created_at`           datetime(3)   NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `updated_by`           char(36)      DEFAULT NULL,
  `updated_at`           datetime(3)   NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `replaces_entry_id`    char(36)      DEFAULT NULL,
  `replaced_by_entry_id` char(36)      DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `cash_entries_code_key` (`code`),
  UNIQUE KEY `cash_entries_reverses_entry_id_key` (`reverses_entry_id`),
  UNIQUE KEY `cash_entries_replaces_uq` (`replaces_entry_id`),
  UNIQUE KEY `cash_entries_replaced_by_uq` (`replaced_by_entry_id`),
  KEY `cash_code_sort_idx` (`code`, `id`),
  KEY `cash_occurred_sort_idx` (`occurred_on`, `id`),
  KEY `cash_amount_sort_idx` (`amount`, `id`),
  KEY `cash_entries_project_idx` (`project_id`, `occurred_on`),
  KEY `cash_entries_purchase_idx` (`purchase_id`),
  KEY `cash_entries_counterparty_idx` (`counterparty_id`),
  KEY `cash_entries_reversed_by_idx` (`reversed_by_entry_id`),
  CONSTRAINT `cash_entries_counterparty_id_fkey` FOREIGN KEY (`counterparty_id`) REFERENCES `counterparties` (`id`),
  CONSTRAINT `cash_entries_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `cash_entries_purchase_id_fkey` FOREIGN KEY (`purchase_id`) REFERENCES `purchases` (`id`),
  CONSTRAINT `cash_entries_created_by_fkey` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `cash_entries_updated_by_fkey` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `cash_entries_voided_by_fkey` FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `cash_entries_reconciled_by_fkey` FOREIGN KEY (`reconciled_by`) REFERENCES `users` (`id`),
  CONSTRAINT `cash_entries_reverses_entry_id_fkey` FOREIGN KEY (`reverses_entry_id`) REFERENCES `cash_entries` (`id`),
  CONSTRAINT `cash_entries_reversed_by_entry_id_fkey` FOREIGN KEY (`reversed_by_entry_id`) REFERENCES `cash_entries` (`id`),
  CONSTRAINT `cash_entries_replaces_entry_id_fkey` FOREIGN KEY (`replaces_entry_id`) REFERENCES `cash_entries` (`id`),
  CONSTRAINT `cash_entries_replaced_by_entry_id_fkey` FOREIGN KEY (`replaced_by_entry_id`) REFERENCES `cash_entries` (`id`),
  CONSTRAINT `cash_entries_direction_check` CHECK (`direction` IN ('IN', 'OUT')),
  CONSTRAINT `cash_entries_category_check` CHECK (`category` IN (
    'CUSTOMER_RECEIPT', 'CUSTOMER_REFUND', 'PURCHASE_PAYMENT', 'PURCHASE_REFUND',
    'DAILY_EXPENSE', 'EXPENSE_REFUND', 'OTHER_INCOME', 'INCOME_REFUND', 'OTHER_EXPENSE')),
  CONSTRAINT `cash_entries_status_check` CHECK (`status` IN ('CONFIRMED', 'VOID')),
  CONSTRAINT `cash_entries_method_check` CHECK (`method` IN ('BANK', 'ALIPAY', 'WECHAT', 'CASH', 'OTHER')),
  CONSTRAINT `cash_entries_allocation_status_check`
    CHECK (`allocation_status` IN ('PROJECT', 'PUBLIC', 'UNASSIGNED')),
  CONSTRAINT `cash_entries_amount_check` CHECK (`amount` > 0),
  CONSTRAINT `cash_entries_code_trim_chk` CHECK (`code` = TRIM(`code`) AND `code` <> ''),
  CONSTRAINT `cash_entries_version_chk` CHECK (`version` >= 1),
  CONSTRAINT `allocation_matches` CHECK ((`allocation_status` = 'PROJECT') = (`project_id` IS NOT NULL)),
  CONSTRAINT `direction_category_matches` CHECK (
    (`direction` = 'IN' AND `category` IN ('CUSTOMER_RECEIPT', 'PURCHASE_REFUND', 'EXPENSE_REFUND', 'OTHER_INCOME'))
    OR (`direction` = 'OUT' AND `category` IN ('CUSTOMER_REFUND', 'PURCHASE_PAYMENT', 'DAILY_EXPENSE', 'INCOME_REFUND', 'OTHER_EXPENSE'))
  ),
  CONSTRAINT `purchase_payment_link` CHECK (
    `category` NOT IN ('PURCHASE_PAYMENT', 'PURCHASE_REFUND') OR `purchase_id` IS NOT NULL
  ),
  CONSTRAINT `cash_entries_correction_no_self` CHECK (
    (`replaces_entry_id` IS NULL OR `replaces_entry_id` <> `id`)
    AND (`replaced_by_entry_id` IS NULL OR `replaced_by_entry_id` <> `id`)
  ),
  CONSTRAINT `cash_entries_correction_distinct_links` CHECK (
    `replaces_entry_id` IS NULL OR `replaced_by_entry_id` IS NULL
    OR `replaces_entry_id` <> `replaced_by_entry_id`
  ),
  CONSTRAINT `cash_entries_correction_not_reversal` CHECK (
    (`replaces_entry_id` IS NULL AND `replaced_by_entry_id` IS NULL)
    OR (`reverses_entry_id` IS NULL AND `reversed_by_entry_id` IS NULL AND `reversal_reason` IS NULL)
  ),
  CONSTRAINT `cash_entries_void_chk` CHECK (
    `status` <> 'VOID' OR (`voided_at` IS NOT NULL AND `voided_by` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '收支台账（更正链 replaces/replaced_by + 反冲链 reverses/reversed_by）';

-- =============================================================================
-- 6. 附件、审计、幂等、事件
-- =============================================================================

-- 6.1 attachments（17 字段）
CREATE TABLE `attachments` (
  `id`             char(36)     NOT NULL DEFAULT (uuid()),
  `owner_type`     varchar(32)  NOT NULL,
  `owner_id`       char(36)     NOT NULL,
  `project_id`     char(36)     DEFAULT NULL,
  `category`       varchar(32)  NOT NULL,
  `sensitivity`    varchar(16)  NOT NULL DEFAULT 'NORMAL',
  `file_name`      varchar(255) NOT NULL,
  `mime_type`      varchar(128) NOT NULL,
  `size_bytes`     bigint       NOT NULL,
  `sha256`         char(64)     NOT NULL,
  `storage_key`    varchar(512) NOT NULL,
  `status`         varchar(16)  NOT NULL DEFAULT 'READY',
  `uploaded_by`    char(36)     NOT NULL,
  `uploaded_at`    datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `deleted_at`     datetime(3)  DEFAULT NULL,
  `deleted_by`     char(36)     DEFAULT NULL,
  `deleted_reason` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `attachments_storage_key_key` (`storage_key`),
  KEY `attachments_owner_idx` (`owner_type`, `owner_id`),
  KEY `attachments_project_idx` (`project_id`, `uploaded_at`),
  KEY `attachments_uploader_idx` (`uploaded_by`),
  CONSTRAINT `attachments_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `attachments_uploaded_by_fkey` FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`),
  CONSTRAINT `attachments_deleted_by_fkey` FOREIGN KEY (`deleted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `attachments_owner_type_check` CHECK (`owner_type` IN (
    'PROJECT', 'STAGE', 'ISSUE', 'PURCHASE', 'PURCHASE_RECEIPT', 'PURCHASE_INVOICE', 'CASH_ENTRY')),
  CONSTRAINT `attachments_category_check` CHECK (`category` IN (
    'PURCHASE_ORDER', 'DELIVERY_NOTE', 'INVOICE', 'PAYMENT_VOUCHER', 'CONTRACT', 'OTHER')),
  CONSTRAINT `attachments_sensitivity_check` CHECK (`sensitivity` IN ('NORMAL', 'FINANCIAL')),
  CONSTRAINT `attachments_status_check` CHECK (`status` IN ('READY', 'DELETED')),
  CONSTRAINT `attachments_size_bytes_check` CHECK (`size_bytes` > 0),
  CONSTRAINT `attachments_sha256_chk` CHECK (CHAR_LENGTH(`sha256`) = 64),
  CONSTRAINT `financial_floor` CHECK (
    `sensitivity` = 'FINANCIAL'
    OR (
      `owner_type` NOT IN ('PURCHASE', 'PURCHASE_RECEIPT', 'PURCHASE_INVOICE', 'CASH_ENTRY')
      AND `category` NOT IN ('INVOICE', 'PAYMENT_VOUCHER', 'CONTRACT', 'PURCHASE_ORDER', 'DELIVERY_NOTE')
    )
  ),
  CONSTRAINT `attachments_deleted_fields_chk` CHECK (
    `status` <> 'DELETED' OR (`deleted_at` IS NOT NULL AND `deleted_by` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '附件元数据（文件本体在对象存储；FINANCIAL 级需单独授权）';

-- 6.2 audit_logs（12 字段，append-only）
-- 业务审计表：保留 before/after/diff，Pig 的 sys_log 只记“谁调了哪个接口”。
CREATE TABLE `audit_logs` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `entity_type` varchar(64)  NOT NULL,
  `entity_id`   varchar(64)  NOT NULL,
  `action`      varchar(64)  NOT NULL,
  `actor_id`    char(36)     DEFAULT NULL,
  `request_id`  varchar(64)  DEFAULT NULL,
  `before_data` json         DEFAULT NULL,
  `after_data`  json         DEFAULT NULL,
  `diff`        json         DEFAULT NULL,
  `ip`          varchar(45)  DEFAULT NULL,
  `user_agent`  text,
  `created_at`  datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (`id`),
  KEY `audit_actor_created_idx` (`actor_id`, `created_at`, `id`),
  KEY `audit_created_sort_idx` (`created_at`, `id`),
  KEY `audit_entity_created_idx` (`entity_type`, `entity_id`, `created_at`, `id`),
  KEY `audit_request_idx` (`request_id`),
  CONSTRAINT `audit_logs_actor_id_fkey` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '业务审计（before/after/diff，只允许 INSERT）';

-- 6.3 idempotency_keys（11 字段，复合主键）
CREATE TABLE `idempotency_keys` (
  `user_id`            char(36)    NOT NULL,
  `scope`              varchar(64) NOT NULL,
  `key`                varchar(255) NOT NULL,
  `request_hash`       char(64)    NOT NULL,
  `target_resource_id` char(36)    DEFAULT NULL,
  `state`              varchar(16) NOT NULL DEFAULT 'PENDING',
  `response_status`    int         DEFAULT NULL,
  `response_body`      json        DEFAULT NULL,
  `response_expires_at` datetime(3) DEFAULT NULL,
  `created_at`         datetime(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `completed_at`       datetime(3) DEFAULT NULL,
  PRIMARY KEY (`user_id`, `scope`, `key`),
  KEY `idempotency_expiry` (`response_expires_at`),
  KEY `idempotency_target_idx` (`target_resource_id`),
  CONSTRAINT `idempotency_keys_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `idempotency_keys_state_check` CHECK (`state` IN ('PENDING', 'COMPLETED', 'EXPIRED')),
  CONSTRAINT `idempotency_request_hash_chk` CHECK (CHAR_LENGTH(`request_hash`) = 64),
  CONSTRAINT `idempotency_completed_chk` CHECK (
    `state` <> 'COMPLETED' OR (`response_status` IS NOT NULL AND `completed_at` IS NOT NULL)
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '写接口幂等键（Idempotency-Key），同一键重放返回首次响应';

-- 6.4 event_clock（3 字段，单行）
CREATE TABLE `event_clock` (
  `singleton`     tinyint(1) NOT NULL DEFAULT 1,
  `version`       bigint     NOT NULL DEFAULT 0,
  `retained_from` bigint     NOT NULL DEFAULT 0,
  PRIMARY KEY (`singleton`),
  CONSTRAINT `event_clock_singleton_check` CHECK (`singleton` = 1),
  CONSTRAINT `event_clock_version_chk` CHECK (`version` >= 0 AND `retained_from` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '事件游标单行表（version 即 event_outbox.id 分配器）';

INSERT INTO `event_clock` (`singleton`, `version`, `retained_from`)
VALUES (1, 0, 0)
ON DUPLICATE KEY UPDATE `singleton` = `singleton`;

-- 6.5 event_outbox（12 字段，id 由触发器分配）
CREATE TABLE `event_outbox` (
  `id`               bigint      NOT NULL DEFAULT 0 COMMENT '由 event_outbox_cursor 触发器从 event_clock.version 分配',
  `event_type`       varchar(64) NOT NULL,
  `resource_type`    varchar(64) NOT NULL,
  `resource_id`      char(36)    NOT NULL,
  `project_id`       char(36)    DEFAULT NULL,
  `stage_id`         char(36)    DEFAULT NULL,
  `actor_id`         char(36)    DEFAULT NULL,
  `resource_version` int         DEFAULT NULL,
  `visibility`       varchar(16) NOT NULL DEFAULT 'BASIC',
  `payload`          json        NOT NULL DEFAULT (JSON_OBJECT()),
  `created_at`       datetime(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  `published_at`     datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `event_outbox_created_idx` (`created_at`),
  KEY `event_outbox_visibility_idx` (`visibility`, `id`),
  KEY `event_outbox_project_idx` (`project_id`, `id`),
  CONSTRAINT `event_outbox_project_id_fkey` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `event_outbox_stage_id_fkey` FOREIGN KEY (`stage_id`) REFERENCES `project_stages` (`id`),
  CONSTRAINT `event_outbox_actor_id_fkey` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`),
  CONSTRAINT `event_outbox_visibility_check` CHECK (`visibility` IN ('BASIC', 'FINANCE')),
  CONSTRAINT `safe_event_payload` CHECK (JSON_SCHEMA_VALID(
    '{"type":"object","additionalProperties":false,"properties":{
        "status":{"type":"string"},
        "previousStatus":{"type":"string"},
        "changedFields":{"type":"array","items":{"type":"string"}}}}',
    `payload`))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '事件发件箱（SSE 数据源；payload 只允许 status/previousStatus/changedFields）';

-- 6.6 schema_migrations（3 字段）
CREATE TABLE `schema_migrations` (
  `version`    varchar(64)  NOT NULL,
  `checksum`   varchar(128) NOT NULL,
  `applied_at` datetime(3)  NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (`version`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '迁移版本记录';

-- =============================================================================
-- 7. 业务外键补充索引
-- =============================================================================
-- MySQL 会为每条外键自动建索引；此处只补“外键列 + 常用排序/过滤列”的组合索引，
-- 避免列表页 order by 走 filesort。

CREATE INDEX `cash_entries_created_sort_idx` ON `cash_entries` (`created_at`, `id`);
CREATE INDEX `attachments_deleted_idx` ON `attachments` (`deleted_at`, `id`);
CREATE INDEX `issues_occurred_idx` ON `issues` (`occurred_at`, `id`);

-- =============================================================================
-- 8. 触发器：append-only 与事件游标
-- =============================================================================

DELIMITER $$

-- 8.1 audit_logs 只允许 INSERT
CREATE TRIGGER `audit_append_only_upd` BEFORE UPDATE ON `audit_logs`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'append-only table: audit_logs';
END$$

CREATE TRIGGER `audit_append_only_del` BEFORE DELETE ON `audit_logs`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'append-only table: audit_logs';
END$$

-- 8.2 stage_events 只允许 INSERT
CREATE TRIGGER `timeline_append_only_upd` BEFORE UPDATE ON `stage_events`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'append-only table: stage_events';
END$$

CREATE TRIGGER `timeline_append_only_del` BEFORE DELETE ON `stage_events`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'append-only table: stage_events';
END$$

-- 8.3 event_outbox.id = 全局单调事件游标
-- 与原 PG 的 assign_event_cursor() 等价：先自增 event_clock.version，再取该值。
CREATE TRIGGER `event_outbox_cursor` BEFORE INSERT ON `event_outbox`
FOR EACH ROW
BEGIN
  UPDATE `event_clock` SET `version` = `version` + 1 WHERE `singleton` = 1;
  SET NEW.`id` = (SELECT `version` FROM `event_clock` WHERE `singleton` = 1);
END$$

DELIMITER ;

-- =============================================================================
-- 9. 迁移自检
-- =============================================================================

INSERT INTO `schema_migrations` (`version`, `checksum`)
VALUES ('m1-mysql-baseline-20261112', 'sha256:pending')
ON DUPLICATE KEY UPDATE `version` = `version`;
