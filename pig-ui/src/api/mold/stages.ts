import { getPage, postJson, patchJson, ifMatch, type MoldPage } from '/@/api/mold/paging';
import { newIdempotencyKey } from '/@/api/mold/projects';

/**
 * 阶段与时间线（契约 6.6）
 *   GET  /me/stages                  我的阶段（责任人/成员）
 *   GET  /stages/{id}/events         阶段历史（append-only）
 *   PATCH /stages/{id}               编辑（If-Match 必填）
 *   POST /stages/{id}/transitions    推进（Idempotency-Key 必填）
 */

export type MoldStageStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'BLOCKED' | 'COMPLETED';

export interface MoldStageAction {
	action: 'START' | 'BLOCK' | 'RESUME' | 'COMPLETE';
	targetStatus: MoldStageStatus;
	reasonRequired?: boolean;
}

export interface MoldStageRow {
	id: string;
	projectId: string;
	name: string;
	sortOrder: number;
	responsibleUserId: string | null;
	plannedStart: string | null;
	plannedEnd: string | null;
	actualStart: string | null;
	actualEnd: string | null;
	status: MoldStageStatus;
	note: string | null;
	version: number;
	updatedAt: string;
	allowedActions?: MoldStageAction[];
}

export interface MoldStageEvent {
	id: string;
	stageId: string;
	action: string;
	previousStatus?: MoldStageStatus | null;
	status?: MoldStageStatus | null;
	reason?: string | null;
	issueId?: string | null;
	actorId?: string | null;
	occurredAt: string;
}

export const STAGE_SORTABLE = ['name', 'status', 'sortOrder', 'updatedAt'] as const;
export const EVENT_SORTABLE = ['id', 'action', 'occurredAt'] as const;

export function fetchMyStages(query: {
	pageNum: number;
	pageSize: number;
	status?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldStageRow>> {
	return getPage<MoldStageRow>('/admin/mold/me/stages', query);
}

export function fetchStageEvents(
	stageId: string,
	query: { pageNum: number; pageSize: number; sort?: string; order?: 'asc' | 'desc' }
): Promise<MoldPage<MoldStageEvent>> {
	return getPage<MoldStageEvent>(`/admin/mold/stages/${encodeURIComponent(stageId)}/events`, query);
}

export function transitionStage(
	stageId: string,
	body: { toStatus: MoldStageStatus; note?: string | null; issueId?: string | null },
	idempotencyKey?: string
): Promise<MoldStageRow> {
	return postJson<MoldStageRow>(`/admin/mold/stages/${encodeURIComponent(stageId)}/transitions`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function updateStage(
	stageId: string,
	body: {
		name?: string;
		sortOrder?: number;
		responsibleUserId?: string;
		plannedStart?: string | null;
		plannedEnd?: string | null;
		note?: string | null;
	},
	version: number
): Promise<MoldStageRow> {
	return patchJson<MoldStageRow>(`/admin/mold/stages/${encodeURIComponent(stageId)}`, body, ifMatch(version));
}
