import { getPage, getOne, postJson, patchJson, ifMatch, type MoldPage } from '/@/api/mold/paging';
import { newIdempotencyKey } from '/@/api/mold/projects';

/**
 * 财务台账与更正（契约 6.9，12 操作）
 *   GET/POST /cash-entries、GET/PATCH /cash-entries/{id}
 *   void / reverse / reconcile / unreconcile
 *   corrections/preview（只读）、corrections（提交）
 *   GET /reports/project-cash、/reports/cash-summary
 */

export type MoldCashDirection = 'IN' | 'OUT';
export type MoldCashCategory =
	| 'CUSTOMER_RECEIPT'
	| 'CUSTOMER_REFUND'
	| 'PURCHASE_PAYMENT'
	| 'PURCHASE_REFUND'
	| 'DAILY_EXPENSE'
	| 'EXPENSE_REFUND'
	| 'OTHER_INCOME'
	| 'INCOME_REFUND'
	| 'OTHER_EXPENSE';
export type MoldCashMethod = 'BANK' | 'ALIPAY' | 'WECHAT' | 'CASH' | 'OTHER';

export interface MoldCashRow {
	id: string;
	code: string;
	direction: MoldCashDirection;
	category: MoldCashCategory;
	occurredOn: string;
	amount: string;
	currency: string;
	counterpartyId?: string | null;
	projectId?: string | null;
	purchaseId?: string | null;
	allocationStatus: 'PROJECT' | 'PUBLIC' | 'UNASSIGNED';
	method: MoldCashMethod;
	voucherNo?: string | null;
	note?: string | null;
	status: 'CONFIRMED' | 'VOID';
	reversesEntryId?: string | null;
	reversedByEntryId?: string | null;
	reversalReason?: string | null;
	reconciledAt?: string | null;
	reconciledBy?: string | null;
	reconciliationRef?: string | null;
	voidedAt?: string | null;
	voidedBy?: string | null;
	voidedByName?: string | null;
	voidReason?: string | null;
	replacesEntryId?: string | null;
	replacedByEntryId?: string | null;
	replacesEntryCode?: string | null;
	replacedByEntryCode?: string | null;
	version: number;
	correctionChain?: MoldCorrectionChain;
}

export interface MoldCorrectionNode {
	id: string;
	code: string;
	status: string;
	direction: MoldCashDirection;
	occurredOn: string;
	amount: string;
	currency: string;
	voidedAt?: string | null;
	voidedByName?: string | null;
	voidReason?: string | null;
	replacesEntryId?: string | null;
	replacedByEntryId?: string | null;
	version: number;
}

export interface MoldCorrectionChain {
	nodes: MoldCorrectionNode[];
	truncated: boolean;
}

export interface MoldCorrectionImpact {
	mode: 'VOID' | 'REPLACE';
	currency: string;
	direction: MoldCashDirection;
	dateBefore: string;
	dateAfter: string | null;
	periodBefore: string;
	periodAfter: string | null;
	amountBefore: string;
	amountAfter: string | null;
	periodDeltas: { period: string; currency: string; amountDelta: string; netDelta: string }[];
	totalAmountDelta: string;
	totalNetDelta: string;
	totalUnchanged: boolean;
}

export interface MoldCorrectionPreview {
	entryId: string;
	version: number;
	impact: MoldCorrectionImpact;
	purchase?: {
		purchaseId: string;
		currency: string;
		amountTotal: string;
		paidOther: string;
		amountAfter: string | null;
		newPaidNet: string;
		overPayment: boolean;
		purchaseStatus: string;
	} | null;
}

export interface MoldCorrectionBody {
	reason: string;
	replacement?: {
		amount?: string;
		occurredOn?: string;
		method?: MoldCashMethod;
		voucherNo?: string | null;
		note?: string | null;
	} | null;
}

export interface MoldCashReport {
	projectId?: string;
	currencies: {
		currency: string;
		categories: { category: MoldCashCategory; direction: MoldCashDirection; amount: string; count: number }[];
		netCash: string;
		purchaseCommitted: string;
		paidNet: string;
		collectedNet: string;
		reconciledNet: string;
		conversion: string;
	}[];
	byAllocation: { allocationStatus: string; currency: string; netCash: string }[];
}

export const CASH_SORTABLE = ['code', 'occurredOn', 'amount', 'status', 'direction', 'category', 'currency'] as const;

export const CATEGORY_DIRECTION: Record<MoldCashCategory, MoldCashDirection> = {
	CUSTOMER_RECEIPT: 'IN',
	CUSTOMER_REFUND: 'OUT',
	PURCHASE_PAYMENT: 'OUT',
	PURCHASE_REFUND: 'IN',
	DAILY_EXPENSE: 'OUT',
	EXPENSE_REFUND: 'IN',
	OTHER_INCOME: 'IN',
	INCOME_REFUND: 'OUT',
	OTHER_EXPENSE: 'OUT',
};

export const CATEGORY_LABEL: Record<MoldCashCategory, string> = {
	CUSTOMER_RECEIPT: '客户收款',
	CUSTOMER_REFUND: '客户退款',
	PURCHASE_PAYMENT: '采购付款',
	PURCHASE_REFUND: '采购退款',
	DAILY_EXPENSE: '日常支出',
	EXPENSE_REFUND: '费用退回',
	OTHER_INCOME: '其他收入',
	INCOME_REFUND: '收入退回',
	OTHER_EXPENSE: '其他支出',
};

export const METHOD_LABEL: Record<string, string> = {
	BANK: '银行转账',
	ALIPAY: '支付宝',
	WECHAT: '微信',
	CASH: '现金',
	OTHER: '其他',
};

export const ALLOCATION_LABEL: Record<string, string> = {
	PROJECT: '归属项目',
	PUBLIC: '公共',
	UNASSIGNED: '未分配',
};

export function fetchCashEntries(query: {
	pageNum: number;
	pageSize: number;
	projectId?: string;
	purchaseId?: string;
	direction?: string;
	category?: string;
	currency?: string;
	allocationStatus?: string;
	status?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldCashRow>> {
	return getPage<MoldCashRow>('/admin/mold/cash-entries', query);
}

export function fetchCashEntry(id: string): Promise<MoldCashRow> {
	return getOne<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}`);
}

export interface MoldCashBody {
	code: string;
	direction: MoldCashDirection;
	category: MoldCashCategory;
	occurredOn: string;
	amount: string;
	currency: string;
	counterpartyId?: string | null;
	projectId?: string | null;
	allocationStatus: 'PROJECT' | 'PUBLIC' | 'UNASSIGNED';
	method: MoldCashMethod;
	voucherNo?: string | null;
	note?: string | null;
}

export function createCashEntry(body: MoldCashBody, idempotencyKey?: string): Promise<MoldCashRow> {
	return postJson<MoldCashRow>('/admin/mold/cash-entries', body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

/** 契约：已对账不可改；普通编辑只开放备注与凭证号（金额/归属走更正） */
export function updateCashEntry(
	id: string,
	body: { note?: string | null; voucherNo?: string | null },
	version: number
): Promise<MoldCashRow> {
	return patchJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}`, body, ifMatch(version));
}

export function voidCashEntry(id: string, reason: string, idempotencyKey?: string): Promise<MoldCashRow> {
	return postJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/void`, { reason }, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function reverseCashEntry(id: string, reason: string, idempotencyKey?: string): Promise<MoldCashRow> {
	return postJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/reverse`, { reason }, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function reconcileCashEntry(
	id: string,
	body: { reconciliationRef?: string | null },
	idempotencyKey?: string
): Promise<MoldCashRow> {
	return postJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/reconcile`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function unreconcileCashEntry(id: string, idempotencyKey?: string): Promise<MoldCashRow> {
	return postJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/unreconcile`, {}, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

/** 更正预览：只读，但按契约必须带 If-Match */
export function previewCorrection(id: string, body: MoldCorrectionBody, version: number): Promise<MoldCorrectionPreview> {
	return postJson<MoldCorrectionPreview>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/corrections/preview`, body, ifMatch(version));
}

export function applyCorrection(
	id: string,
	body: MoldCorrectionBody,
	version: number,
	idempotencyKey?: string
): Promise<MoldCashRow> {
	return postJson<MoldCashRow>(`/admin/mold/cash-entries/${encodeURIComponent(id)}/corrections`, body, {
		...ifMatch(version),
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function fetchProjectCashReport(query: {
	projectId: string;
	dateFrom?: string;
	dateTo?: string;
	currency?: string;
}): Promise<MoldCashReport> {
	return getOne<MoldCashReport>('/admin/mold/reports/project-cash', query);
}

export function fetchCashSummary(query: {
	dateFrom?: string;
	dateTo?: string;
	currency?: string;
	projectId?: string;
	allocationStatus?: string;
}): Promise<MoldCashReport> {
	return getOne<MoldCashReport>('/admin/mold/reports/cash-summary', query);
}
