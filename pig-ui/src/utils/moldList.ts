import { computed, reactive, ref } from 'vue';
import { describeMoldError, isApiError } from '/@/api/mold/errors';

/**
 * 列表页共用状态机（契约 6.1 offset 分页 + 四态）。
 * 新页面统一用它，保证 1440/1024/390 三档与错误态行为一致。
 */
export type MoldListState = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden';

export interface MoldListOptions<T> {
	fetch: (query: Record<string, any>) => Promise<{ rows: T[]; total: number }>;
	pageSize?: number;
	/** 页面的筛选条件（不含 pageNum/pageSize/sort/order） */
	filters?: () => Record<string, any>;
	sortable?: readonly string[];
	defaultSort?: { key: string; order: 'asc' | 'desc' };
	/** 空列表是否算 empty（默认 true） */
	emptyState?: boolean;
}

export function useMoldList<T>(options: MoldListOptions<T>) {
	const rows = ref<T[]>([]) as { value: T[] };
	const total = ref(0);
	const pageNum = ref(1);
	const pageSize = ref(options.pageSize ?? 20);
	const sortKey = ref(options.defaultSort?.key ?? '');
	const sortOrder = ref<'asc' | 'desc'>(options.defaultSort?.order ?? 'asc');
	const state = ref<MoldListState>('loading');
	const loading = ref(false);
	const errorAction = ref<'retry' | 'login'>('retry');
	const errorInfo = reactive({ title: '加载失败', message: '', requestId: '' });

	let seq = 0;

	const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
	const hasFilters = computed(() => {
		const f = options.filters?.() || {};
		return Object.values(f).some((v) => v !== undefined && v !== null && v !== '');
	});

	function buildQuery(): Record<string, any> {
		const query: Record<string, any> = {
			pageNum: pageNum.value,
			pageSize: pageSize.value,
			...(options.filters?.() || {}),
		};
		if (sortKey.value && (!options.sortable || options.sortable.includes(sortKey.value))) {
			query.sort = sortKey.value;
			query.order = sortOrder.value;
		}
		return query;
	}

	async function load(firstLoad = false) {
		const current = ++seq;
		if (firstLoad) state.value = 'loading';
		else loading.value = true;
		errorInfo.title = '加载失败';
		errorInfo.message = '';
		errorInfo.requestId = '';
		errorAction.value = 'retry';
		try {
			const page = await options.fetch(buildQuery());
			if (current !== seq) return;
			rows.value = page.rows;
			total.value = page.total;
			state.value = page.rows.length > 0 ? 'ready' : options.emptyState === false ? 'ready' : 'empty';
			// 越界页：服务端返回空 rows + 正确 total，回到最后一页
			if (page.rows.length === 0 && page.total > 0 && pageNum.value > pageCount.value) {
				pageNum.value = pageCount.value;
				if (current === seq) void load(false);
			}
		} catch (err: any) {
			if (current !== seq) return;
			if (isApiError(err) && err.status === 403 && err.code !== 'PASSWORD_CHANGE_REQUIRED') {
				state.value = 'forbidden';
				return;
			}
			const view = describeMoldError(err, '服务暂时不可用，请稍后重试。');
			state.value = 'error';
			errorAction.value = view.action === 'login' ? 'login' : 'retry';
			errorInfo.title = view.title;
			errorInfo.message = view.message;
			errorInfo.requestId = view.requestId;
		} finally {
			if (current === seq) loading.value = false;
		}
	}

	function reload() {
		void load(false);
	}

	function query() {
		pageNum.value = 1;
		void load(false);
	}

	function reset() {
		pageNum.value = 1;
		void load(false);
	}

	function goPage(next: number) {
		if (next < 1 || next > pageCount.value) return;
		pageNum.value = next;
		void load(false);
	}

	function onSortChange({ prop, order }: { prop: string; order: string | null }) {
		if (!order) {
			sortKey.value = options.defaultSort?.key ?? '';
			sortOrder.value = options.defaultSort?.order ?? 'asc';
		} else {
			sortKey.value = prop;
			sortOrder.value = order === 'descending' ? 'desc' : 'asc';
		}
		pageNum.value = 1;
		void load(false);
	}

	return {
		rows,
		total,
		pageNum,
		pageSize,
		sortKey,
		sortOrder,
		state,
		loading,
		errorInfo,
		errorAction,
		pageCount,
		hasFilters,
		load,
		reload,
		query,
		reset,
		goPage,
		onSortChange,
	};
}
