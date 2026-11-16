import { onActivated, onDeactivated, onMounted, onUnmounted } from 'vue';
import { Session } from '/@/utils/storage';
import { redirectToLogin } from '/@/api/mold/authEvents';

/**
 * 模具业务事件流（契约 6.10.4）
 *
 * 为什么不用 EventSource：
 *  - 需要 `Authorization: Bearer` 与 `Last-Event-ID` 请求头，EventSource 不支持自定义头；
 *  - 需要区分 422/409/429 与断线，自行控制退避。
 * 因此用 fetch + ReadableStream 解析 SSE 帧。
 *
 * 口径（12.1）：
 *  - 信封不带 name/code，页面按 resourceId 回查；
 *  - payload 只有 status/previousStatus/changedFields；
 *  - 游标非法 → 422，越界/过期 → 409 + resync_required。
 */

export type MoldEventVisibility = 'BASIC' | 'FINANCE';

export interface MoldEventEnvelope {
	eventId: string;
	eventType: string;
	resourceType: string;
	resourceId: string;
	projectId?: string | null;
	stageId?: string | null;
	resourceVersion?: number;
	visibility?: MoldEventVisibility;
	actorId?: string | null;
	occurredAt?: string;
	status?: string | null;
	previousStatus?: string | null;
	changedFields?: string[];
}

export type MoldEventStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'unavailable' | 'stopped';

type Handler = (event: MoldEventEnvelope) => void;

const STREAM_PATH = '/admin/mold/events/stream';
const MAX_BACKOFF_MS = 30000;
const BASE_BACKOFF_MS = 1000;

function tokenValue(): string {
	const token = Session.getToken();
	return typeof token === 'string' ? token : '';
}

function sleep(ms: number, signal: AbortSignal) {
	return new Promise<void>((resolve) => {
		if (signal.aborted) return resolve();
		const timer = window.setTimeout(done, ms);
		function done() {
			window.clearTimeout(timer);
			signal.removeEventListener('abort', done);
			resolve();
		}
		signal.addEventListener('abort', done, { once: true });
	});
}

class MoldEventClient {
	private handlers = new Map<string, Set<Handler>>();
	private controller: AbortController | null = null;
	private loopToken = 0;
	private lastEventId = '';
	private dataVersion = '';
	private backoff = BASE_BACKOFF_MS;
	private scope: MoldEventVisibility = 'BASIC';
	private started = false;
	private onlineBound = false;
	private visibilityBound = false;
	private statusValue: MoldEventStatus = 'idle';

	get status(): MoldEventStatus {
		return this.statusValue;
	}

	get cursor(): string {
		return this.lastEventId;
	}

	get version(): string {
		return this.dataVersion;
	}

	on(type: string, handler: Handler): void {
		const key = type || '*';
		if (!this.handlers.has(key)) this.handlers.set(key, new Set());
		this.handlers.get(key)!.add(handler);
	}

	off(type: string, handler: Handler): void {
		this.handlers.get(type || '*')?.delete(handler);
	}

	/** 启动唯一连接；重复调用无副作用 */
	start(scope: MoldEventVisibility = 'BASIC'): void {
		if (this.started) return;
		this.started = true;
		this.scope = scope;
		this.bindBrowserEvents();
		void this.run();
	}

	/** 主动断开（退出登录/布局卸载）；不会自动重连 */
	stop(): void {
		this.started = false;
		this.loopToken += 1;
		this.controller?.abort();
		this.controller = null;
		this.statusValue = 'stopped';
	}

	/** 页面需要立即补齐时使用：清游标并按新事件重连 */
	resync(): void {
		this.lastEventId = '';
		this.restart();
	}

	private restart(): void {
		if (!this.started) return;
		this.loopToken += 1;
		this.controller?.abort();
		this.controller = null;
		void this.run();
	}

	private bindBrowserEvents() {
		if (!this.onlineBound) {
			this.onlineBound = true;
			window.addEventListener('online', () => {
				if (this.started) this.restart();
			});
		}
		if (!this.visibilityBound) {
			this.visibilityBound = true;
			document.addEventListener('visibilitychange', () => {
				if (document.visibilityState === 'visible' && this.started && this.statusValue !== 'open') this.restart();
			});
		}
	}

	private setStatus(next: MoldEventStatus) {
		this.statusValue = next;
	}

	private async run(): Promise<void> {
		const token = this.loopToken;
		while (this.started && token === this.loopToken) {
			this.setStatus(this.backoff > BASE_BACKOFF_MS ? 'reconnecting' : 'connecting');
			try {
				await this.connectOnce(token);
			} catch {
				/* 连接失败走统一退避；不向控制台抛未捕获错误 */
			}
			if (!this.started || token !== this.loopToken) return;
			await sleep(this.nextBackoff(), this.abortSignal());
		}
	}

	private abortSignal(): AbortSignal {
		this.controller = this.controller || new AbortController();
		return this.controller.signal;
	}

	private nextBackoff(): number {
		const base = Math.min(this.backoff, MAX_BACKOFF_MS);
		this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
		return base + Math.floor(Math.random() * 250);
	}

	private async connectOnce(token: number): Promise<void> {
		const controller = new AbortController();
		this.controller = controller;
		const headers: Record<string, string> = { Accept: 'text/event-stream' };
		const auth = tokenValue();
		if (auth) headers.Authorization = `Bearer ${auth}`;
		if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId;

		const base = import.meta.env.VITE_API_URL || '/api';
		const url = `${base}${STREAM_PATH}?scope=${encodeURIComponent(this.scope)}`;
		const res = await fetch(url, { headers, signal: controller.signal, cache: 'no-store' });

		if (res.status === 401) {
			this.emitControl('session_expired', {});
			this.stop();
			redirectToLogin();
			return;
		}
		if (res.status === 422) {
			// 游标非法：丢弃本地游标，通知页面整页重取后重连（G-8）
			this.lastEventId = '';
			this.emitControl('resync_required', { reason: 'INVALID_CURSOR' });
			return;
		}
		if (res.status === 409) {
			this.lastEventId = '';
			this.emitControl('resync_required', { reason: 'CURSOR_OUTSIDE_WINDOW' });
			return;
		}
		if (res.status === 429) {
			this.emitControl('stream_error', { code: 'TOO_MANY_STREAMS' });
			this.backoff = MAX_BACKOFF_MS;
			return;
		}
		if (res.status === 404) {
			// 事件流端点不可用（404）：安静退避，不刷控制台
			this.setStatus('unavailable');
			return;
		}
		if (!res.ok || !res.body) {
			throw new Error(`SSE ${res.status}`);
		}

		this.setStatus('open');
		this.backoff = BASE_BACKOFF_MS;
		await this.readStream(res.body, token);
	}

	private async readStream(body: ReadableStream<Uint8Array>, token: number): Promise<void> {
		const reader = body.getReader();
		const decoder = new TextDecoder();
		let buffer = '';
		let frameId = '';
		let frameEvent = '';
		let frameData: string[] = [];

		const dispatch = () => {
			if (!frameEvent) {
				frameId = '';
				frameData = [];
				return;
			}
			const raw = frameData.join('\n');
			const payload = this.parseData(raw);
			if (frameId) this.lastEventId = frameId;
			if (frameEvent === 'ready') {
				this.dataVersion = String(payload?.dataVersion ?? this.dataVersion);
				if (payload?.cursor) this.lastEventId = String(payload.cursor);
				this.backoff = BASE_BACKOFF_MS;
			} else if (frameEvent === 'caught_up') {
				this.dataVersion = String(payload?.dataVersion ?? this.dataVersion);
				if (payload?.cursor) this.lastEventId = String(payload.cursor);
			} else if (frameEvent === 'resync_required') {
				this.lastEventId = '';
				this.emitControl('resync_required', payload || {});
			} else if (frameEvent === 'session_expired') {
				this.emitControl('session_expired', payload || {});
				this.stop();
				// 服务端主动踢下线（账号被停用/删除）：与 401 同一处理，清会话回登录页
				redirectToLogin();
			} else if (frameEvent === 'stream_error') {
				this.emitControl('stream_error', payload || {});
			} else {
				const envelope = payload as MoldEventEnvelope | null;
				if (envelope && envelope.resourceId) {
					this.emit(frameEvent, envelope);
					this.emit('*', envelope);
				}
			}
			frameId = '';
			frameEvent = '';
			frameData = [];
		};

		try {
			while (this.started && token === this.loopToken) {
				const { done, value } = await reader.read();
				if (done) break;
				buffer += decoder.decode(value, { stream: true });
				let index = buffer.indexOf('\n');
				while (index >= 0) {
					let line = buffer.slice(0, index);
					buffer = buffer.slice(index + 1);
					if (line.endsWith('\r')) line = line.slice(0, -1);
					if (line === '') {
						dispatch();
					} else if (line.startsWith(':')) {
						// 心跳注释，忽略
					} else {
						const colon = line.indexOf(':');
						const field = colon >= 0 ? line.slice(0, colon) : line;
						let valueText = colon >= 0 ? line.slice(colon + 1) : '';
						if (valueText.startsWith(' ')) valueText = valueText.slice(1);
						if (field === 'id') frameId = valueText;
						else if (field === 'event') frameEvent = valueText;
						else if (field === 'data') frameData.push(valueText);
					}
					index = buffer.indexOf('\n');
				}
			}
		} finally {
			reader.releaseLock();
		}
	}

	private parseData(raw: string): any {
		if (!raw) return null;
		try {
			return JSON.parse(raw);
		} catch {
			return null;
		}
	}

	private emitControl(type: string, payload: any) {
		this.emit(type, payload);
		this.emit('*', payload);
	}

	private emit(type: string, payload: any) {
		this.handlers.get(type)?.forEach((handler) => {
			try {
				handler(payload);
			} catch {
				/* 单个订阅者异常不影响连接 */
			}
		});
	}
}

export const moldEvents = new MoldEventClient();

/** 页面订阅：仅在本页激活时挂载，keep-alive 切走自动解绑，避免后台反复刷新 */
export function useMoldEvent(type: string | string[], handler: Handler): void {
	const types = Array.isArray(type) ? type : [type];
	let bound = false;
	const bind = () => {
		if (bound) return;
		bound = true;
		types.forEach((t) => moldEvents.on(t, handler));
	};
	const unbind = () => {
		if (!bound) return;
		bound = false;
		types.forEach((t) => moldEvents.off(t, handler));
	};
	onMounted(bind);
	onActivated(bind);
	onDeactivated(unbind);
	onUnmounted(unbind);
}

/** 合并短时间内的多次事件，避免一次批量写入触发 N 次列表请求 */
export function debounceRefresh(fn: () => void, wait = 600) {
	let timer: number | undefined;
	return () => {
		window.clearTimeout(timer);
		timer = window.setTimeout(fn, wait);
	};
}
