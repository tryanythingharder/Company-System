/** 业务页共用格式化（M3） */

/** 日期：YYYY-MM-DD（G-18，本地时区） */
export function formatDate(value?: string | null): string {
	if (!value) return '—';
	const d = value.length <= 10 ? new Date(`${value}T00:00:00`) : new Date(value);
	if (Number.isNaN(d.getTime())) return value;
	const y = d.getFullYear();
	const m = `${d.getMonth() + 1}`.padStart(2, '0');
	const day = `${d.getDate()}`.padStart(2, '0');
	return `${y}-${m}-${day}`;
}

/** 时间戳：YYYY-MM-DD HH:mm（G-18，本地时区） */
export function formatDateTime(value?: string | null): string {
	if (!value) return '—';
	const d = new Date(value);
	if (Number.isNaN(d.getTime())) return value;
	const y = d.getFullYear();
	const m = `${d.getMonth() + 1}`.padStart(2, '0');
	const day = `${d.getDate()}`.padStart(2, '0');
	const hh = `${d.getHours()}`.padStart(2, '0');
	const mm = `${d.getMinutes()}`.padStart(2, '0');
	return `${y}-${m}-${day} ${hh}:${mm}`;
}

/** 列表用短时间戳：MM-DD HH:mm（完整值在预览/详情中显示，G-18 完整格式由 formatDateTime 提供） */
export function formatShortDateTime(value?: string | null): string {
	if (!value) return '—';
	const d = new Date(value);
	if (Number.isNaN(d.getTime())) return value;
	const m = `${d.getMonth() + 1}`.padStart(2, '0');
	const day = `${d.getDate()}`.padStart(2, '0');
	const hh = `${d.getHours()}`.padStart(2, '0');
	const mm = `${d.getMinutes()}`.padStart(2, '0');
	return `${m}-${day} ${hh}:${mm}`;
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

/** 文件名日期后缀 YYYY-MM-DD */
export function todayStamp(): string {
	const d = new Date();
	const m = `${d.getMonth() + 1}`.padStart(2, '0');
	const day = `${d.getDate()}`.padStart(2, '0');
	return `${d.getFullYear()}-${m}-${day}`;
}
