import { getPage, getOne, postJson, patchJson, ifMatch, type MoldPage } from '/@/api/mold/paging';
import { newIdempotencyKey } from '/@/api/mold/projects';

/**
 * 采购三段式（契约 6.8，19 操作）
 *   GET/POST /counterparties、PATCH /counterparties/{id}
 *   GET/POST /purchases、GET/PATCH /purchases/{id}、confirm、void、summary
 *   receipts / invoices / payments / refunds
 * 金额一律按字符串传输（契约 7.5-6）。
 */

export type MoldCounterpartyType = 'CUSTOMER' | 'SUPPLIER' | 'OTHER';
/** 07 边界值：数据库 INACTIVE 对外映射为 DISABLED（契约 6.8 出参口径） */
export type MoldCounterpartyStatus = 'ACTIVE' | 'DISABLED';
export type MoldPurchaseStatus = 'DRAFT' | 'CONFIRMED' | 'VOID';
export type MoldAllocationStatus = 'PROJECT' | 'PUBLIC' | 'UNASSIGNED';

export interface MoldCounterpartyRow {
	id: string;
	name: string;
	type: MoldCounterpartyType;
	/** 07 出参字段（旧 Node DTO 同名字段 contact） */
	contact?: string | null;
	/** 兼容早期前端命名的只读别名 */
	contactName?: string | null;
	phone?: string | null;
	taxNo?: string | null;
	note?: string | null;
	status: MoldCounterpartyStatus;
	version: number;
}

export interface MoldPurchaseItem {
	id?: string;
	lineNo: number;
	name: string;
	spec?: string | null;
	qty: string;
	unit?: string | null;
	unitPrice: string;
	amount?: string;
	note?: string | null;
}

export interface MoldPurchaseRow {
	id: string;
	code: string;
	counterpartyId: string;
	purchaseDate: string;
	projectId?: string | null;
	allocationStatus: MoldAllocationStatus;
	currency: string;
	amountTotal: string;
	taxIncluded?: boolean | null;
	status: MoldPurchaseStatus;
	items?: MoldPurchaseItem[];
	receiptStatus?: 'NONE' | 'PARTIAL' | 'FULL';
	invoiceStatus?: 'NONE' | 'PARTIAL' | 'FULL';
	paymentStatus?: 'UNPAID' | 'PARTIAL' | 'PAID' | 'OVERPAID';
	version: number;
	note?: string | null;
	voidReason?: string | null;
}

export interface MoldPurchaseSummary {
	purchaseId: string;
	currency: string;
	amountTotal: string;
	paidAmount: string;
	invoicedAmount: string;
	receiptStatus: 'NONE' | 'PARTIAL' | 'FULL';
	invoiceStatus: 'NONE' | 'PARTIAL' | 'FULL';
	paymentStatus: 'UNPAID' | 'PARTIAL' | 'PAID' | 'OVERPAID';
	overpaid?: boolean;
	calculatedAt?: string;
}

export interface MoldReceiptRow {
	id: string;
	purchaseId: string;
	receivedOn: string;
	receivedBy?: string;
	status: 'ACTIVE' | 'VOID';
	items: { purchaseItemId: string; qtyReceived: string }[];
	version: number;
	note?: string | null;
	voidReason?: string | null;
}

export interface MoldInvoiceRow {
	id: string;
	purchaseId: string;
	counterpartyId: string;
	invoiceNo: string;
	invoiceType: 'VAT_SPECIAL' | 'VAT_NORMAL' | 'OTHER';
	issueDate: string;
	amountTotal: string;
	taxAmount?: string | null;
	currency: string;
	status: 'ACTIVE' | 'VOID';
	version: number;
	voidReason?: string | null;
}

export interface MoldPurchasePayment {
	id: string;
	code: string;
	occurredOn: string;
	amount: string;
	currency: string;
	category: 'PURCHASE_PAYMENT' | 'PURCHASE_REFUND';
	method: string;
	status: string;
	voucherNo?: string | null;
	note?: string | null;
}

export const PURCHASE_SORTABLE = ['code', 'purchaseDate', 'amountTotal', 'status', 'currency'] as const;
export const COUNTERPARTY_SORTABLE = ['name', 'type', 'status'] as const;

/* ------------------------------- 往来单位 ------------------------------- */

export function fetchCounterparties(query: {
	pageNum: number;
	pageSize: number;
	type?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldCounterpartyRow>> {
	return getPage<MoldCounterpartyRow>('/admin/mold/counterparties', query);
}

export function createCounterparty(
	body: { name: string; type: MoldCounterpartyType; contact?: string | null; phone?: string | null; taxNo?: string | null; note?: string | null },
	idempotencyKey?: string
): Promise<MoldCounterpartyRow> {
	return postJson<MoldCounterpartyRow>('/admin/mold/counterparties', body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function updateCounterparty(
	id: string,
	body: { name?: string; type?: MoldCounterpartyType; contact?: string | null; phone?: string | null; taxNo?: string | null; note?: string | null; status?: MoldCounterpartyStatus },
	version: number
): Promise<MoldCounterpartyRow> {
	return patchJson<MoldCounterpartyRow>(`/admin/mold/counterparties/${encodeURIComponent(id)}`, body, ifMatch(version));
}

/* ------------------------------- 采购单 ------------------------------- */

export function fetchPurchases(query: {
	pageNum: number;
	pageSize: number;
	/** 07 服务端单号模糊匹配参数名是 q（只匹配 code） */
	q?: string;
	status?: string;
	counterpartyId?: string;
	projectId?: string;
	allocationStatus?: string;
	currency?: string;
	dateFrom?: string;
	dateTo?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldPurchaseRow>> {
	return getPage<MoldPurchaseRow>('/admin/mold/purchases', query);
}

export function fetchPurchase(id: string): Promise<MoldPurchaseRow> {
	return getOne<MoldPurchaseRow>(`/admin/mold/purchases/${encodeURIComponent(id)}`);
}

export function fetchPurchaseSummary(id: string): Promise<MoldPurchaseSummary> {
	return getOne<MoldPurchaseSummary>(`/admin/mold/purchases/${encodeURIComponent(id)}/summary`);
}

export interface MoldPurchaseBody {
	code: string;
	counterpartyId: string;
	purchaseDate: string;
	projectId?: string | null;
	allocationStatus: MoldAllocationStatus;
	currency: string;
	taxIncluded?: boolean | null;
	note?: string | null;
	items: MoldPurchaseItem[];
}

export function createPurchase(body: MoldPurchaseBody, idempotencyKey?: string): Promise<MoldPurchaseRow> {
	return postJson<MoldPurchaseRow>('/admin/mold/purchases', body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function updatePurchase(id: string, body: Partial<MoldPurchaseBody>, version: number): Promise<MoldPurchaseRow> {
	return patchJson<MoldPurchaseRow>(`/admin/mold/purchases/${encodeURIComponent(id)}`, body, ifMatch(version));
}

export function confirmPurchase(id: string, idempotencyKey?: string): Promise<MoldPurchaseRow> {
	return postJson<MoldPurchaseRow>(`/admin/mold/purchases/${encodeURIComponent(id)}/confirm`, {}, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function voidPurchase(id: string, reason: string, idempotencyKey?: string): Promise<MoldPurchaseRow> {
	return postJson<MoldPurchaseRow>(`/admin/mold/purchases/${encodeURIComponent(id)}/void`, { reason }, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

/* ------------------------------- 收货 ------------------------------- */

export function fetchReceipts(purchaseId: string): Promise<MoldPage<MoldReceiptRow>> {
	return getPage<MoldReceiptRow>(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/receipts`, {
		pageNum: 1,
		pageSize: 100,
	});
}

export function createReceipt(
	purchaseId: string,
	body: { receivedOn: string; note?: string | null; items: { purchaseItemId: string; qtyReceived: string }[] },
	idempotencyKey?: string
): Promise<MoldReceiptRow> {
	return postJson<MoldReceiptRow>(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/receipts`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function voidReceipt(id: string, reason: string, idempotencyKey?: string): Promise<MoldReceiptRow> {
	return postJson<MoldReceiptRow>(`/admin/mold/receipts/${encodeURIComponent(id)}/void`, { reason }, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

/* ------------------------------- 发票 ------------------------------- */

export function fetchInvoices(purchaseId: string): Promise<MoldPage<MoldInvoiceRow>> {
	return getPage<MoldInvoiceRow>(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/invoices`, {
		pageNum: 1,
		pageSize: 100,
	});
}

export function createInvoice(
	purchaseId: string,
	body: {
		counterpartyId: string;
		invoiceNo: string;
		invoiceType: MoldInvoiceRow['invoiceType'];
		issueDate: string;
		amountTotal: string;
		taxAmount?: string | null;
		currency: string;
	},
	idempotencyKey?: string
): Promise<MoldInvoiceRow> {
	return postJson<MoldInvoiceRow>(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/invoices`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function voidInvoice(id: string, reason: string, idempotencyKey?: string): Promise<MoldInvoiceRow> {
	return postJson<MoldInvoiceRow>(`/admin/mold/invoices/${encodeURIComponent(id)}/void`, { reason }, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

/* ------------------------------- 付款 / 退款 ------------------------------- */

export interface MoldPaymentBody {
	occurredOn: string;
	amount: string;
	currency: string;
	method: string;
	voucherNo?: string | null;
	note?: string | null;
}

export function fetchPurchasePayments(purchaseId: string): Promise<MoldPage<MoldPurchasePayment>> {
	return getPage<MoldPurchasePayment>(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/payments`, {
		pageNum: 1,
		pageSize: 100,
	});
}

export function createPurchasePayment(
	purchaseId: string,
	body: MoldPaymentBody,
	idempotencyKey?: string
): Promise<{ cashEntryId: string; cashImpact: string; paymentStatus: string; paidAmount: string }> {
	return postJson(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/payments`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function createPurchaseRefund(
	purchaseId: string,
	body: MoldPaymentBody,
	idempotencyKey?: string
): Promise<{ cashEntryId: string; cashImpact: string; paymentStatus: string; paidAmount: string }> {
	return postJson(`/admin/mold/purchases/${encodeURIComponent(purchaseId)}/refunds`, body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}
