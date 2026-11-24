/** 业务页共用格式化（M3） */

/**
 * 展示时区固定为公司所在地（北京时间），不随浏览器时区变化：
 * 后端时间戳一律是 UTC ISO 串；不带偏移的按 UTC 解释，避免差 8 小时。
 * 纯日期（YYYY-MM-DD，业务日期）原样展示，不做时区换算。
 */
export const DISPLAY_TIME_ZONE = 'Asia/Shanghai';

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

const zoneFormatter = new Intl.DateTimeFormat('en-CA', {
	timeZone: DISPLAY_TIME_ZONE,
	year: 'numeric',
	month: '2-digit',
	day: '2-digit',
	hour: '2-digit',
	minute: '2-digit',
	hourCycle: 'h23',
});

function zoned(d: Date): Record<string, string> {
	const parts: Record<string, string> = {};
	for (const p of zoneFormatter.formatToParts(d)) parts[p.type] = p.value;
	return parts;
}

function parseInstant(value: string): Date {
	const text = value.trim().replace(' ', 'T');
	const hasOffset = /([zZ]|[+-]\d{2}:?\d{2})$/.test(text);
	return new Date(hasOffset ? text : `${text}Z`);
}

/** 日期：YYYY-MM-DD（G-18，北京时间） */
export function formatDate(value?: string | null): string {
	if (!value) return '—';
	if (DATE_ONLY.test(value)) return value;
	const d = parseInstant(value);
	if (Number.isNaN(d.getTime())) return value;
	const p = zoned(d);
	return `${p.year}-${p.month}-${p.day}`;
}

/** 时间戳：YYYY-MM-DD HH:mm（G-18，北京时间） */
export function formatDateTime(value?: string | null): string {
	if (!value) return '—';
	if (DATE_ONLY.test(value)) return value;
	const d = parseInstant(value);
	if (Number.isNaN(d.getTime())) return value;
	const p = zoned(d);
	return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`;
}

/** 列表用短时间戳：MM-DD HH:mm（完整值在预览/详情中显示，G-18 完整格式由 formatDateTime 提供） */
export function formatShortDateTime(value?: string | null): string {
	if (!value) return '—';
	if (DATE_ONLY.test(value)) return value.slice(5);
	const d = parseInstant(value);
	if (Number.isNaN(d.getTime())) return value;
	const p = zoned(d);
	return `${p.month}-${p.day} ${p.hour}:${p.minute}`;
}

/** CSV 单元格转义（G-12：公式注入防护 + RFC4180 引号） */
export function csvCell(value: unknown): string {
	let text = value === null || value === undefined ? '' : String(value);
	if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
	if (/[",\r\n]/.test(text)) text = `"${text.replace(/"/g, '""')}"`;
	return text;
}

/** 生成 CSV 文本：UTF-8 BOM + CRLF（G-12） */
export function buildCsv(headers: string[], rows: unknown[][]): string {
	const lines = [headers.map(csvCell).join(','), ...rows.map((row) => row.map(csvCell).join(','))];
	return `\uFEFF${lines.join('\r\n')}\r\n`;
}

/** 触发浏览器下载 */
export function downloadCsv(filename: string, content: string): void {
	const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
	downloadBlob(filename, blob);
}

/** 下载已有 Blob（后端 CSV 流直接落盘，不二次拼接） */
export function downloadBlob(filename: string, blob: Blob): void {
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

const secondFormatter = new Intl.DateTimeFormat('en-CA', {
	timeZone: DISPLAY_TIME_ZONE,
	year: 'numeric',
	month: '2-digit',
	day: '2-digit',
	hour: '2-digit',
	minute: '2-digit',
	second: '2-digit',
	hourCycle: 'h23',
});

/** 编号用时间戳 yyyyMMdd-HHmmss（北京时间，不随浏览器时区变化） */
export function beijingCodeStamp(d: Date = new Date()): string {
	const p: Record<string, string> = {};
	for (const part of secondFormatter.formatToParts(d)) p[part.type] = part.value;
	return `${p.year}${p.month}${p.day}-${p.hour}${p.minute}${p.second}`;
}

const DECIMAL = /^\s*(-?)(\d*)(?:\.(\d*))?\s*$/;

function toScaled(value: unknown): { n: bigint; scale: number } | null {
	const m = DECIMAL.exec(String(value ?? ''));
	if (!m || (!m[2] && !m[3])) return null;
	const frac = m[3] || '';
	const n = BigInt(`${m[2] || '0'}${frac}`);
	return { n: m[1] ? -n : n, scale: frac.length };
}

/**
 * 数量 × 单价 → 金额（2 位小数，HALF_UP），与后端 BigDecimal.setScale(2, HALF_UP) 一致。
 * 不用浮点：1.005 × 1 这类值用 toFixed 会差 1 分。非法输入返回 null。
 */
export function mulMoneyHalfUp(qty: unknown, unitPrice: unknown): string | null {
	const a = toScaled(qty);
	const b = toScaled(unitPrice);
	if (!a || !b) return null;
	let prod = a.n * b.n;
	const scale = a.scale + b.scale;
	const neg = prod < 0n;
	if (neg) prod = -prod;
	let cents: bigint;
	if (scale <= 2) cents = prod * 10n ** BigInt(2 - scale);
	else {
		const div = 10n ** BigInt(scale - 2);
		cents = prod / div;
		if ((prod % div) * 2n >= div) cents += 1n;
	}
	return formatCents(neg ? -cents : cents);
}

/** 金额字符串求和（按分整数累加，避免浮点误差） */
export function sumMoney(values: (string | null | undefined)[]): string {
	let total = 0n;
	for (const v of values) {
		const s = toScaled(v ?? '0');
		if (!s) continue;
		total += s.scale <= 2 ? s.n * 10n ** BigInt(2 - s.scale) : s.n / 10n ** BigInt(s.scale - 2);
	}
	return formatCents(total);
}

function formatCents(cents: bigint): string {
	const neg = cents < 0n;
	const abs = (neg ? -cents : cents).toString().padStart(3, '0');
	return `${neg ? '-' : ''}${abs.slice(0, -2)}.${abs.slice(-2)}`;
}

/** 文件名日期后缀 YYYY-MM-DD（北京时间） */
export function todayStamp(): string {
	const p = zoned(new Date());
	return `${p.year}-${p.month}-${p.day}`;
}
