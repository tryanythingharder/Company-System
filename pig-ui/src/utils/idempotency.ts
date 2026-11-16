/**
 * 写操作幂等键（Idempotency-Key）
 *
 * 约束：每次用户动作生成一次并持久化（sessionStorage），
 * 同一动作的重试复用同一个键，成功后清除；换动作/换参数生成新键。
 *
 * 键只保存在当前标签页，不含任何账号或口令信息。
 */

const PREFIX = 'mold.idem.';

function randomKey(): string {
	const uuid =
		typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
			? crypto.randomUUID()
			: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
	return `idem_${uuid.replace(/-/g, '')}`;
}

function storageKey(scope: string) {
	return PREFIX + scope;
}

/**
 * 取得（或创建）某个用户动作的幂等键。
 * @param scope 动作标识，例如 `project.complete:<id>:<version>:<noteHash>`
 */
export function beginIdempotentAction(scope: string): string {
	const key = storageKey(scope);
	try {
		const existing = sessionStorage.getItem(key);
		if (existing) return existing;
		const created = randomKey();
		sessionStorage.setItem(key, created);
		return created;
	} catch {
		// 隐私模式：退化为内存内稳定值（同一页面生命周期内重试仍复用）
		const holder = memory as Record<string, string>;
		if (!holder[key]) holder[key] = randomKey();
		return holder[key];
	}
}

/** 动作成功：清除键，下一次是全新动作 */
export function finishIdempotentAction(scope: string) {
	try {
		sessionStorage.removeItem(storageKey(scope));
	} catch {
		/* 忽略 */
	}
	delete (memory as Record<string, string>)[storageKey(scope)];
}

/** 清空某类动作的残留键（例如对话框关闭后放弃） */
export function clearIdempotentActions(prefix: string) {
	try {
		const full = storageKey(prefix);
		const keys: string[] = [];
		for (let i = 0; i < sessionStorage.length; i += 1) {
			const k = sessionStorage.key(i);
			if (k && k.startsWith(full)) keys.push(k);
		}
		keys.forEach((k) => sessionStorage.removeItem(k));
	} catch {
		/* 忽略 */
	}
}

/** 稳定短哈希：把提交内容折叠进 scope，内容变了就换新键 */
export function stableHash(input: string): string {
	let hash = 5381;
	for (let i = 0; i < input.length; i += 1) {
		hash = ((hash << 5) + hash + input.charCodeAt(i)) >>> 0;
	}
	return hash.toString(36);
}

const memory: Record<string, string> = {};
