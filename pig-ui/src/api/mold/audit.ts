import { getPage, type MoldPage } from '/@/api/mold/paging';

/**
 * 业务审计（契约 6.10：GET /audit-logs）
 * append-only；before/after/diff 是本表存在的理由（契约 7.4）。
 */

export interface MoldAuditRow {
	id: string;
	entityType: string;
	entityId: string;
	action: string;
	actorId?: string | null;
	requestId?: string | null;
	beforeData?: any;
	afterData?: any;
	diff?: any;
	ip?: string | null;
	userAgent?: string | null;
	createdAt: string;
}

export const AUDIT_SORTABLE = ['id', 'createdAt', 'entityType', 'action'] as const;

export function fetchAuditLogs(query: {
	pageNum: number;
	pageSize: number;
	entityType?: string;
	entityId?: string;
	actorId?: string;
	action?: string;
	dateFrom?: string;
	dateTo?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldAuditRow>> {
	return getPage<MoldAuditRow>('/admin/mold/audit-logs', query);
}
