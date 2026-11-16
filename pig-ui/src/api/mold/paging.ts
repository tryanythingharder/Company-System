import { moldClient, toApiError, unwrap, MoldApiError } from '/@/api/mold/projects';

/**
 * 通用列表读取（契约 6.1：offset 分页，pageNum/pageSize -> rows/total）。
 * 所有新增列表页共用，避免每页重复拼 envelope 与错误转换。
 */
export interface MoldPage<T> {
	rows: T[];
	total: number;
	pageNum: number;
	pageSize: number;
	pages: number;
	asOf?: string;
	dataVersion?: string | number;
}

export async function getPage<T>(path: string, params: Record<string, any> = {}): Promise<MoldPage<T>> {
	let res: any;
	try {
		res = await moldClient.get(path, { params: cleanParams(params) });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	const rows: T[] = Array.isArray(payload?.rows) ? payload.rows : Array.isArray(payload) ? payload : [];
	const total = Number(payload?.total ?? rows.length);
	const pageNum = Number(payload?.pageNum ?? params.pageNum ?? 1);
	const pageSize = Number(payload?.pageSize ?? params.pageSize ?? rows.length);
	return {
		rows,
		total: Number.isFinite(total) ? total : rows.length,
		pageNum: Number.isFinite(pageNum) ? pageNum : 1,
		pageSize: Number.isFinite(pageSize) ? pageSize : rows.length,
		pages: Number(payload?.pages ?? Math.max(1, Math.ceil(total / Math.max(1, pageSize)))),
		asOf: payload?.asOf,
		dataVersion: payload?.dataVersion,
	};
}

export async function getOne<T>(path: string, params: Record<string, any> = {}): Promise<T> {
	let res: any;
	try {
		res = await moldClient.get(path, { params: cleanParams(params) });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as T;
}

export async function postJson<T>(path: string, body: any, headers: Record<string, string> = {}): Promise<T> {
	let res: any;
	try {
		res = await moldClient.post(path, body, { headers });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as T;
}

export async function patchJson<T>(path: string, body: any, headers: Record<string, string> = {}): Promise<T> {
	let res: any;
	try {
		res = await moldClient.patch(path, body, { headers });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as T;
}

export async function putJson<T>(path: string, body: any, headers: Record<string, string> = {}): Promise<T> {
	let res: any;
	try {
		res = await moldClient.put(path, body, { headers });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as T;
}

export async function deleteJson<T>(
	path: string,
	options: { headers?: Record<string, string>; data?: any } = {}
): Promise<T> {
	let res: any;
	try {
		res = await moldClient.delete(path, options);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as T;
}

export function cleanParams(params: Record<string, any>): Record<string, any> {
	const out: Record<string, any> = {};
	for (const [key, value] of Object.entries(params)) {
		if (value === undefined || value === null || value === '') continue;
		out[key] = value;
	}
	return out;
}

/** If-Match 头（契约 6.1）：`If-Match: "<version>"` */
export function ifMatch(version: number | string | undefined | null): Record<string, string> {
	return version === undefined || version === null || version === '' ? {} : { 'If-Match': `"${version}"` };
}

/** 金额是字符串（契约 7.5-6），转数字只用于展示/比较，不参与写回 */
export function toNumber(value: unknown): number {
	const n = Number(value);
	return Number.isFinite(n) ? n : 0;
}
