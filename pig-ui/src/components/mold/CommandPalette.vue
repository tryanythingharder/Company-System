<template>
	<el-dialog
		v-model="visible"
		:width="dialogWidth"
		:show-close="false"
		:close-on-click-modal="true"
		append-to-body
		class="mold-cmd-dialog"
		aria-label="命令面板"
		@opened="onOpened"
		@closed="onClosed"
	>
		<div class="mold-cmd">
			<div class="mold-cmd-input">
				<el-icon><Search /></el-icon>
				<input
					ref="inputRef"
					v-model="query"
					type="text"
					role="combobox"
					aria-expanded="true"
					aria-controls="mold-cmd-list"
					aria-autocomplete="list"
					:aria-activedescendant="activeId"
					placeholder="搜索页面或项目名称…（↑↓ 选择，Enter 打开，Esc 关闭）"
					data-testid="cmd-input"
					@keydown="onKeydown"
				/>
				<kbd>Esc</kbd>
			</div>

			<div id="mold-cmd-list" class="mold-cmd-list" role="listbox" aria-label="命令结果">
				<template v-for="group in groups">
					<div v-if="group.items.length" :key="group.key" class="mold-cmd-group">
						<div class="mold-cmd-group-title">{{ group.title }}</div>
						<button
							v-for="item in group.items"
							:id="`mold-cmd-${item.id}`"
							:key="item.id"
							type="button"
							role="option"
							:aria-selected="item.index === activeIndex"
							class="mold-cmd-item"
							:class="{ 'is-active': item.index === activeIndex }"
							@mouseenter="activeIndex = item.index"
							@click="select(item)"
						>
							<el-icon class="mold-cmd-item-icon"><component :is="item.icon" /></el-icon>
							<span class="mold-cmd-item-main">
								<span class="mold-cmd-item-title">{{ item.title }}</span>
								<span v-if="item.subtitle" class="mold-cmd-item-sub">{{ item.subtitle }}</span>
							</span>
							<span v-if="item.badge" class="mold-cmd-item-badge">{{ item.badge }}</span>
						</button>
					</div>
				</template>

				<div v-if="!flatItems.length" class="mold-cmd-empty" role="status">
					<template v-if="loading">正在搜索…</template>
					<template v-else-if="searchError">{{ searchError }}</template>
					<template v-else>没有匹配的页面或项目</template>
				</div>
			</div>

			<div class="mold-cmd-foot">
				<span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
				<span><kbd>Enter</kbd> 打开</span>
				<span><kbd>Esc</kbd> 关闭</span>
				<span class="mold-cmd-foot-right">⌘/Ctrl + K 随时唤起</span>
			</div>
		</div>
	</el-dialog>
</template>

<script setup lang="ts" name="moldCommandPalette">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Coin, DataLine, Document, House, List, Search, Tickets, User, Clock, Refresh } from '@element-plus/icons-vue';
import { fetchProjectPage, type MoldProjectRow } from '/@/api/mold/projects';
import { describeMoldError } from '/@/api/mold/errors';
import { canAccessRoute } from '/@/router/menuPermission';

interface CmdItem {
	id: string;
	index: number;
	title: string;
	subtitle?: string;
	badge?: string;
	icon: any;
	group: 'page' | 'project';
	path?: string;
	row?: MoldProjectRow;
}

const router = useRouter();
const visible = ref(false);
const query = ref('');
const inputRef = ref<HTMLInputElement>();
const activeIndex = ref(0);
const loading = ref(false);
const searchError = ref('');
const projects = ref<MoldProjectRow[]>([]);
let returnFocus: HTMLElement | null = null;
let debounceTimer: ReturnType<typeof setTimeout> | null = null;
let searchSeq = 0;

const dialogWidth = computed(() => (typeof window !== 'undefined' && window.innerWidth <= 900 ? '94%' : '560px'));

const PAGES = [
	{ id: 'page-home', title: '首页', subtitle: '工作台', path: '/home', icon: House },
	{ id: 'page-projects', title: '公司进度', subtitle: '全部项目列表', path: '/mold/projects', icon: Tickets, permissions: ['mold:project:list'] },
	{ id: 'page-my-stages', title: '我的工作', subtitle: '我负责 / 参与的阶段', path: '/mold/my-stages', icon: List },
	// 采购台账入口按用户决策「菜单里藏起来」于 2026-11-15 移除（路由与页面保留，直连可进）
	{ id: 'page-cash', title: '财务收支', subtitle: '收支流水与报表', path: '/mold/cash', icon: Coin, permissions: ['mold:cash:list'] },
	{ id: 'page-corrections', title: '更正记录', subtitle: '预览影响、提交更正、更正链', path: '/mold/corrections', icon: Refresh, permissions: ['mold:correction:preview'] },
	{ id: 'page-stages', title: '阶段历史', subtitle: '阶段时间线与附件', path: '/mold/stages', icon: Clock, permissions: ['mold:stage:timeline'] },
	{ id: 'page-accounts', title: '账号管理', subtitle: '账号、角色与重置口令', path: '/mold/accounts', icon: User, permissions: ['mold:user:list'] },
	{ id: 'page-audit', title: '审计日志', subtitle: '业务写操作前后快照', path: '/mold/audit', icon: DataLine, permissions: ['mold:audit:list'] },
	{ id: 'page-password', title: '修改密码', subtitle: '账号安全', path: '/change-password', icon: Document },
];

/** 与导航同一套判定：权限没取到就放行，页面自身 403 兜底 */
function pageAllowed(caps?: string[]): boolean {
	return canAccessRoute({ permissions: caps });
}

const pageItems = computed<CmdItem[]>(() => {
	const q = query.value.trim().toLowerCase();
	const list = PAGES.filter(
		(p) => pageAllowed((p as any).permissions) && (!q || p.title.toLowerCase().includes(q) || (p.subtitle || '').toLowerCase().includes(q))
	);
	return list.map((p, i) => ({
		id: p.id,
		index: i,
		title: p.title,
		subtitle: p.subtitle,
		icon: p.icon,
		group: 'page' as const,
		path: p.path,
	}));
});

const projectItems = computed<CmdItem[]>(() => {
	const offset = pageItems.value.length;
	return projects.value.map((row, i) => ({
		id: `project-${row.id}`,
		index: offset + i,
		title: row.name,
		subtitle: `${row.code} · ${row.owner?.displayName || '—'}`,
		badge: row.status === 'COMPLETED' ? '已完工' : row.status === 'ON_HOLD' ? '已暂停' : '进行中',
		icon: Tickets,
		group: 'project' as const,
		row,
	}));
});

const flatItems = computed<CmdItem[]>(() => [...pageItems.value, ...projectItems.value]);
const groups = computed(() => [
	{ key: 'page', title: '页面', items: pageItems.value },
	{ key: 'project', title: query.value.trim() ? '项目' : '项目（输入名称搜索）', items: projectItems.value },
]);
const activeId = computed(() => {
	const item = flatItems.value[activeIndex.value];
	return item ? `mold-cmd-${item.id}` : undefined;
});

watch(flatItems, (list) => {
	if (activeIndex.value >= list.length) activeIndex.value = 0;
});

watch(query, () => {
	if (debounceTimer) clearTimeout(debounceTimer);
	const q = query.value.trim();
	if (!q) {
		projects.value = [];
		loading.value = false;
		searchError.value = '';
		activeIndex.value = 0;
		return;
	}
	loading.value = true;
	debounceTimer = setTimeout(() => searchProjects(q), 220);
});

async function searchProjects(q: string) {
	const seq = ++searchSeq;
	try {
		const page = await fetchProjectPage({ pageNum: 1, pageSize: 8, q });
		if (seq !== searchSeq) return;
		projects.value = page.rows;
		searchError.value = '';
	} catch (err) {
		if (seq !== searchSeq) return;
		projects.value = [];
		// 网络故障/403 只在面板内提示，不跳登录、不清列表
		searchError.value = describeMoldError(err, '项目搜索失败').message;
	} finally {
		if (seq === searchSeq) loading.value = false;
	}
}

function onKeydown(e: KeyboardEvent) {
	if (e.key === 'ArrowDown') {
		e.preventDefault();
		if (!flatItems.value.length) return;
		activeIndex.value = (activeIndex.value + 1) % flatItems.value.length;
		scrollActiveIntoView();
	} else if (e.key === 'ArrowUp') {
		e.preventDefault();
		if (!flatItems.value.length) return;
		activeIndex.value = (activeIndex.value - 1 + flatItems.value.length) % flatItems.value.length;
		scrollActiveIntoView();
	} else if (e.key === 'Home') {
		e.preventDefault();
		activeIndex.value = 0;
		scrollActiveIntoView();
	} else if (e.key === 'End') {
		e.preventDefault();
		activeIndex.value = Math.max(0, flatItems.value.length - 1);
		scrollActiveIntoView();
	} else if (e.key === 'Enter') {
		e.preventDefault();
		const item = flatItems.value[activeIndex.value];
		if (item) select(item);
	} else if (e.key === 'Escape') {
		e.preventDefault();
		visible.value = false;
	}
}

function scrollActiveIntoView() {
	nextTick(() => {
		const el = document.getElementById(activeId.value || '');
		el?.scrollIntoView({ block: 'nearest' });
	});
}

function select(item: CmdItem) {
	visible.value = false;
	if (item.group === 'page' && item.path) {
		router.push(item.path);
		return;
	}
	if (item.row) router.push(`/mold/project/${encodeURIComponent(item.row.id)}`);
}

function onOpened() {
	nextTick(() => inputRef.value?.focus());
}

function onClosed() {
	query.value = '';
	projects.value = [];
	activeIndex.value = 0;
	searchError.value = '';
	if (returnFocus && returnFocus.isConnected) returnFocus.focus();
	else (document.getElementById('mold-main-content') as HTMLElement | null)?.focus();
	returnFocus = null;
}

function open() {
	if (visible.value) return;
	// 在打开前记录触发元素，关闭后焦点回到它（G-13 键盘闭环）
	returnFocus = (document.activeElement as HTMLElement) || null;
	visible.value = true;
}

onBeforeUnmount(() => {
	if (debounceTimer) clearTimeout(debounceTimer);
});

defineExpose({ open });
</script>

<style scoped lang="scss">
.mold-cmd {
	display: flex;
	flex-direction: column;
	margin: -12px;
}
.mold-cmd-input {
	display: flex;
	align-items: center;
	gap: 8px;
	padding: 12px 14px;
	border-bottom: 1px solid var(--el-border-color-lighter);
	color: var(--el-text-color-secondary);
	input {
		flex: 1;
		border: none;
		outline: none;
		background: transparent;
		font-size: 15px;
		line-height: 24px;
		color: var(--el-text-color-primary);
	}
	kbd {
		font-family: inherit;
		font-size: 11px;
		padding: 1px 5px;
		border: 1px solid var(--el-border-color);
		border-radius: 4px;
		color: var(--el-text-color-secondary);
	}
}
.mold-cmd-list {
	max-height: 360px;
	overflow-y: auto;
	padding: 6px;
}
.mold-cmd-group-title {
	font-size: 11px;
	line-height: 18px;
	padding: 6px 10px 2px;
	color: var(--el-text-color-secondary);
}
.mold-cmd-item {
	display: flex;
	align-items: center;
	gap: 10px;
	width: 100%;
	padding: 8px 10px;
	border: none;
	background: transparent;
	border-radius: 6px;
	cursor: pointer;
	text-align: left;
	color: var(--el-text-color-primary);
	&.is-active {
		background: var(--el-color-primary-light-9);
		outline: 2px solid var(--el-color-primary);
		outline-offset: -2px;
	}
}
.mold-cmd-item-icon {
	color: var(--el-color-primary);
	flex: none;
}
.mold-cmd-item-main {
	display: flex;
	flex-direction: column;
	min-width: 0;
	flex: 1;
}
.mold-cmd-item-title {
	font-size: 14px;
	line-height: 20px;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.mold-cmd-item-sub {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.mold-cmd-item-badge {
	font-size: 11px;
	color: var(--el-text-color-secondary);
	flex: none;
}
.mold-cmd-empty {
	padding: 24px 12px;
	text-align: center;
	font-size: 13px;
	color: var(--el-text-color-secondary);
}
.mold-cmd-foot {
	display: flex;
	gap: 14px;
	align-items: center;
	padding: 8px 14px;
	border-top: 1px solid var(--el-border-color-lighter);
	font-size: 11px;
	color: var(--el-text-color-secondary);
	kbd {
		font-family: inherit;
		padding: 0 4px;
		border: 1px solid var(--el-border-color);
		border-radius: 3px;
		margin-right: 2px;
	}
}
.mold-cmd-foot-right {
	margin-left: auto;
}
</style>
