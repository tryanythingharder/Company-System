import axios from 'axios';
import { moldClient, toApiError, unwrap, MoldApiError, newIdempotencyKey } from '/@/api/mold/projects';
import { deleteJson, getOne } from '/@/api/mold/paging';

/**
 * 附件（契约 6.10，8 操作）
 *   POST   /attachments               multipart：file + ownerType/ownerId/category/sensitivity
 *   GET    /attachments?ownerType&ownerId
 *   GET    /attachments/{id}
 *   GET    /attachments/{id}/download
 *   DELETE /attachments/{id}
 * FINANCIAL 级附件下载额外要求 mold:cash:list。
 */

export type MoldAttachmentOwner =
	| 'PROJECT'
	| 'STAGE'
	| 'ISSUE'
	| 'PURCHASE'
	| 'PURCHASE_RECEIPT'
	| 'PURCHASE_INVOICE'
	| 'CASH_ENTRY';

export type MoldAttachmentCategory =
	| 'PURCHASE_ORDER'
	| 'DELIVERY_NOTE'
	| 'INVOICE'
	| 'PAYMENT_VOUCHER'
	| 'CONTRACT'
	| 'OTHER';

export interface MoldAttachmentRow {
	id: string;
	ownerType: MoldAttachmentOwner;
	ownerId: string;
	projectId?: string | null;
	category: MoldAttachmentCategory;
	sensitivity: 'NORMAL' | 'FINANCIAL';
	fileName: string;
	mimeType: string;
	sizeBytes: number;
	sha256: string;
	uploadedBy: string;
	uploadedAt: string;
	status?: string;
}

export const ATTACHMENT_ACCEPT = '.jpg,.jpeg,.png,.heic,.pdf';
export const ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024;
export const ATTACHMENT_MAX_PER_OWNER = 20;
export const ATTACHMENT_MIME = ['image/jpeg', 'image/png', 'image/heic', 'application/pdf'];

export interface MoldAttachmentList {
	rows: MoldAttachmentRow[];
	total: number;
	asOf?: string;
	dataVersion?: string | number;
}

export function fetchAttachments(ownerType: MoldAttachmentOwner, ownerId: string): Promise<MoldAttachmentRow[]> {
	// 契约 6.10.1：子资源列表不分页，返回 {rows,total,asOf,dataVersion}
	return getOne<MoldAttachmentList>('/admin/mold/attachments', { ownerType, ownerId }).then((page) =>
		Array.isArray(page?.rows) ? page.rows : []
	);
}

export async function uploadAttachment(
	ownerType: MoldAttachmentOwner,
	ownerId: string,
	file: File,
	category: MoldAttachmentCategory,
	sensitivity: 'NORMAL' | 'FINANCIAL' = 'NORMAL',
	idempotencyKey?: string,
	onProgress?: (percent: number) => void
): Promise<MoldAttachmentRow> {
	const form = new FormData();
	form.append('file', file);
	form.append('ownerType', ownerType);
	form.append('ownerId', ownerId);
	form.append('category', category);
	form.append('sensitivity', sensitivity);
	let res: any;
	try {
		res = await moldClient.post('/admin/mold/attachments', form, {
			headers: {
				'Content-Type': 'multipart/form-data',
				'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
			},
			timeout: 60000,
			onUploadProgress: (event) => {
				if (!onProgress) return;
				const total = Number(event.total || 0);
				if (!total) return;
				onProgress(Math.min(100, Math.round((Number(event.loaded || 0) / total) * 100)));
			},
		});
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldAttachmentRow;
}

/** 从 Content-Disposition 取服务端文件名；兼容 filename*=UTF-8'' 与普通 filename */
function fileNameFromDisposition(header: unknown, fallback: string): string {
	if (typeof header !== 'string' || !header) return fallback;
	const utf8 = header.match(/filename\*=UTF-8''([^;]+)/i);
	if (utf8?.[1]) {
		try {
			return decodeURIComponent(utf8[1].trim());
		} catch {
			return fallback;
		}
	}
	const plain = header.match(/filename="?([^";]+)"?/i);
	return plain?.[1]?.trim() || fallback;
}

export async function downloadAttachment(row: MoldAttachmentRow): Promise<void> {
	let res: any;
	try {
		res = await moldClient.get(`/admin/mold/attachments/${encodeURIComponent(row.id)}/download`, {
			responseType: 'blob',
			timeout: 60000,
		});
	} catch (err) {
		if (axios.isAxiosError(err) && err.response?.data instanceof Blob) {
			try {
				err.response.data = JSON.parse(await err.response.data.text());
			} catch {
				/* 保持原样 */
			}
		}
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const blob = res.data as Blob;
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = fileNameFromDisposition(res.headers?.['content-disposition'], row.fileName || 'attachment');
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	// 部分浏览器异步读取 blob，延后释放更稳
	window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 软删；reason 为 UI 层财务附件必填，服务端可选（契约 6.10.1 / G-4） */
export function deleteAttachment(id: string, reason?: string): Promise<void> {
	const body = reason && reason.trim() ? { reason: reason.trim() } : undefined;
	return deleteJson<void>(`/admin/mold/attachments/${encodeURIComponent(id)}`, body ? { data: body } : {});
}

/**
 * 附件错误文案（G-3）：
 *  - 413 UPLOAD_TOO_LARGE：单文件体积超限
 *  - 422 ATTACHMENT_LIMIT：该对象附件数量/配额已满
 * 两者不得混用；其余走通用错误描述。
 */
export function describeAttachmentError(err: any, fallback = '附件操作失败'): string {
	const status = Number(err?.status) || 0;
	const code = String(err?.code || '');
	if (status === 413 || code === 'UPLOAD_TOO_LARGE') {
		return `文件过大：单个附件不能超过 ${ATTACHMENT_MAX_BYTES / 1024 / 1024} MiB（服务端返回 413 UPLOAD_TOO_LARGE）。`;
	}
	if (status === 422 && code === 'ATTACHMENT_LIMIT') {
		return `该对象附件已满 ${ATTACHMENT_MAX_PER_OWNER} 个，请先删除不再需要的附件（服务端返回 422 ATTACHMENT_LIMIT）。`;
	}
	if (status === 403 && code === 'ATTACHMENT_ACCESS_DENIED') {
		return '没有该附件的操作权限；财务附件下载还需要财务查看权限。';
	}
	return err?.message || fallback;
}

/** 文件类型/大小前置校验，错误文案与后端 422 对齐 */
export function validateAttachmentFile(file: File): string {
	if (!file) return '请选择文件';
	if (file.size <= 0) return '文件不能为空';
	if (file.size > ATTACHMENT_MAX_BYTES) return `文件不能超过 ${ATTACHMENT_MAX_BYTES / 1024 / 1024} MB`;
	if (!ATTACHMENT_MIME.includes(file.type)) return '只允许 JPG/PNG/HEIC/PDF';
	return '';
}
