<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div class="page-scroll">
				<div class="mold-page wb">
					<!-- 问候 + 快捷操作 -->
					<header class="wb-head">
						<div>
							<h1>{{ greeting }}，{{ meName || '同事' }}</h1>
							<p>{{ todayText }} · {{ roleText }}</p>
						</div>
						<div class="wb-actions">
							<el-button v-if="can('mold:project:create')" type="primary" :icon="Plus" data-testid="wb-new-project" @click="go('/mold/projects', { create: '1' })">新建项目</el-button>
							<el-button v-if="can('mold:cash:create')" :icon="Wallet" @click="go('/mold/cash', { create: '1' })">记一笔流水</el-button>
							<el-button v-if="can('mold:stage:mine')" :icon="Finished" @click="go('/mold/my-stages')">我的工作</el-button>
						</div>
					</header>

					<el-alert v-if="loadError" class="wb-alert" type="error" :closable="false" show-icon :title="loadError">
						<template #default>
							<el-button link type="primary" @click="load">重新加载</el-button>
						</template>
					</el-alert>

					<!-- 关键数字：只显示当前账号有权限看的 -->
					<section class="wb-kpis" aria-label="关键数字">
						<button
							v-for="k in kpis"
							:key="k.key"
							type="button"
							class="wb-kpi"
							:class="`is-${k.tone}`"
							:aria-label="`${k.label} ${k.value}，查看详情`"
							:data-testid="`wb-kpi-${k.key}`"
							@click="k.to && go(k.to, k.query)"
						>
							<span class="wb-kpi-label">{{ k.label }}</span>
							<span class="wb-kpi-value">
								<span v-if="loading" class="mold-skel-block wb-skel" />
								<template v-else>{{ k.value }}</template>
							</span>
							<span class="wb-kpi-foot">{{ k.foot }}</span>
						</button>
					</section>

					<div class="wb-grid">
						<!-- 我的待办阶段 -->
						<section v-if="can('mold:stage:mine')" class="mold-card wb-panel" aria-labelledby="wb-todo-title">
							<div class="mold-card-title">
								<span id="wb-todo-title">我的待办</span>
								<el-button link type="primary" @click="go('/mold/my-stages')">全部 {{ myOpen.length }} 项</el-button>
							</div>
							<el-skeleton v-if="loading" :rows="4" animated />
							<div v-else-if="!myOpen.length" class="wb-empty">
								<el-icon><CircleCheck /></el-icon>
								<span>没有待办阶段。项目负责人把阶段指派给你后，会出现在这里。</span>
							</div>
							<ul v-else class="wb-list">
								<li v-for="s in myOpen.slice(0, 8)" :key="s.id">
									<button type="button" class="wb-row" @click="go(`/mold/project/${s.projectId}`)">
										<span class="wb-row-main">
											<span class="wb-row-title">{{ s.name }}</span>
											<span class="wb-row-sub">{{ projectLabel(s.projectId) }}</span>
										</span>
										<span class="wb-row-side">
											<span v-if="s.plannedEnd" class="wb-due" :class="dueTone(s.plannedEnd)">{{ dueText(s.plannedEnd) }}</span>
											<StatusTag kind="stage" :value="s.status" />
										</span>
									</button>
								</li>
							</ul>
						</section>

						<!-- 需要关注的项目 -->
						<section v-if="can('mold:project:list')" class="mold-card wb-panel" aria-labelledby="wb-watch-title">
							<div class="mold-card-title">
								<span id="wb-watch-title">需要关注的项目</span>
								<el-button link type="primary" @click="go('/mold/projects')">公司进度</el-button>
							</div>
							<el-skeleton v-if="loading" :rows="4" animated />
							<div v-else-if="!watchList.length" class="wb-empty">
								<el-icon><CircleCheck /></el-icon>
								<span>{{ activeProjects.length ? '进行中的项目都很顺利：没有受阻、异常或逾期。' : '还没有进行中的项目。' }}</span>
							</div>
							<ul v-else class="wb-list">
								<li v-for="p in watchList.slice(0, 8)" :key="p.id">
									<button type="button" class="wb-row" @click="go(`/mold/project/${p.id}`)">
										<span class="wb-row-main">
											<span class="wb-row-title">{{ p.name }}</span>
											<span class="wb-row-sub">{{ p.code }} · 负责人 {{ p.owner?.displayName || '—' }}</span>
										</span>
										<span class="wb-row-side">
											<el-tag v-if="overdue(p)" size="small" type="danger" effect="plain">逾期 {{ overdueDays(p) }} 天</el-tag>
											<el-tag v-if="blockedOf(p)" size="small" type="danger">受阻 {{ blockedOf(p) }}</el-tag>
											<el-tag v-if="p.openIssueCount" size="small" type="warning">异常 {{ p.openIssueCount }}</el-tag>
										</span>
									</button>
								</li>
							</ul>
						</section>

						<!-- 本月收支（仅能看全部流水的账号） -->
						<section v-if="can('mold:cash:viewAll')" class="mold-card wb-panel" aria-labelledby="wb-cash-title">
							<div class="mold-card-title">
								<span id="wb-cash-title">本月收支</span>
								<el-button link type="primary" @click="go('/mold/cash')">财务收支</el-button>
							</div>
							<el-skeleton v-if="loading" :rows="3" animated />
							<div v-else-if="cashError" class="wb-empty is-error">{{ cashError }}</div>
							<dl v-else class="wb-cash">
								<div>
									<dt>收入</dt>
									<dd class="is-in"><MoneyText :value="cash.in" /></dd>
								</div>
								<div>
									<dt>支出</dt>
									<dd class="is-out"><MoneyText :value="cash.out" /></dd>
								</div>
								<div>
									<dt>净额</dt>
									<dd><MoneyText :value="cash.net" /></dd>
								</div>
							</dl>
							<p class="mold-form-hint">{{ monthText }}，人民币；已作废的流水不计入。</p>
						</section>

						<!-- 即将到期 -->
						<section v-if="can('mold:project:list')" class="mold-card wb-panel" aria-labelledby="wb-due-title">
							<div class="mold-card-title">
								<span id="wb-due-title">7 天内到期</span>
								<span class="mold-card-note">按计划交期</span>
							</div>
							<el-skeleton v-if="loading" :rows="3" animated />
							<div v-else-if="!dueSoon.length" class="wb-empty">
								<el-icon><Calendar /></el-icon>
								<span>未来 7 天没有到期的项目。</span>
							</div>
							<ul v-else class="wb-list">
								<li v-for="p in dueSoon.slice(0, 6)" :key="p.id">
									<button type="button" class="wb-row" @click="go(`/mold/project/${p.id}`)">
										<span class="wb-row-main">
											<span class="wb-row-title">{{ p.name }}</span>
											<span class="wb-row-sub">已完成 {{ p.stageCounts.completed }}/{{ p.stageCounts.total }} 个阶段</span>
										</span>
										<span class="wb-row-side">
											<span class="wb-due" :class="dueTone(p.dueDate!)">{{ dueText(p.dueDate!) }}</span>
										</span>
									</button>
								</li>
							</ul>
						</section>
					</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="moldWorkbench">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Calendar, CircleCheck, Finished, Plus, Wallet } from '@element-plus/icons-vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import { fetchProjectPage, type MoldProjectRow } from '/@/api/mold/projects';
import { fetchMyStages, type MoldStageRow } from '/@/api/mold/stages';
import { fetchCashSummary } from '/@/api/mold/cash';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { describeMoldError } from '/@/api/mold/errors';
import { todayStamp } from '/@/utils/moldFormat';

const router = useRouter();
const { can, ensureLoaded, meName, capabilities } = useMoldPermissions();

const loading = ref(true);
const loadError = ref('');
const cashError = ref('');
const activeProjects = ref<MoldProjectRow[]>([]);
const myStages = ref<MoldStageRow[]>([]);
const projectNames = reactive<Record<string, string>>({});
const cash = reactive({ in: '0', out: '0', net: '0' });

const today = todayStamp();
const monthStart = `${today.slice(0, 8)}01`;

const hour = Number(new Intl.DateTimeFormat('en-US', { hour: 'numeric', hour12: false, timeZone: 'Asia/Shanghai' }).format(new Date()));
const greeting = hour < 6 ? '夜深了' : hour < 11 ? '早上好' : hour < 13 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
const todayText = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' }).format(new Date());
const monthText = `${Number(today.slice(5, 7))} 月 1 日至今天`;

const roleText = computed(() => {
	if (can('mold:user:list')) return '老板';
	if (can('mold:cash:viewAll')) return '财务';
	if (can('mold:project:create')) return '项目负责人';
	if (can('mold:stage:mine')) return '一线员工';
	return '只读查看';
});

function daysBetween(from: string, to: string): number {
	return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000);
}
const blockedOf = (p: MoldProjectRow) => p.blockedStageCount ?? p.stageCounts?.blocked ?? 0;
const overdue = (p: MoldProjectRow) => !!p.dueDate && p.dueDate < today;
const overdueDays = (p: MoldProjectRow) => (p.dueDate ? daysBetween(p.dueDate, today) : 0);
function dueText(date: string): string {
	const d = daysBetween(today, date);
	if (d < 0) return `逾期 ${-d} 天`;
	if (d === 0) return '今天到期';
	if (d === 1) return '明天到期';
	return `${d} 天后到期`;
}
const dueTone = (date: string) => {
	const d = daysBetween(today, date);
	return d < 0 ? 'is-bad' : d <= 2 ? 'is-warn' : '';
};
const projectLabel = (id: string) => projectNames[id] || '项目';

const myOpen = computed(() => {
	const rank: Record<string, number> = { BLOCKED: 0, IN_PROGRESS: 1, NOT_STARTED: 2 };
	return myStages.value
		.filter((s) => s.status !== 'COMPLETED')
		.sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9) || (a.plannedEnd || '9').localeCompare(b.plannedEnd || '9'));
});
const watchList = computed(() =>
	activeProjects.value
		.filter((p) => overdue(p) || blockedOf(p) > 0 || p.openIssueCount > 0)
		.sort((a, b) => Number(overdue(b)) - Number(overdue(a)) || blockedOf(b) - blockedOf(a) || b.openIssueCount - a.openIssueCount)
);
const dueSoon = computed(() =>
	activeProjects.value
		.filter((p) => p.dueDate && p.dueDate >= today && daysBetween(today, p.dueDate) <= 7)
		.sort((a, b) => a.dueDate!.localeCompare(b.dueDate!))
);

type Kpi = { key: string; label: string; value: number | string; foot: string; tone: string; to?: string; query?: Record<string, string> };
const kpis = computed<Kpi[]>(() => {
	const list: Kpi[] = [];
	if (can('mold:stage:mine')) {
		const blocked = myStages.value.filter((s) => s.status === 'BLOCKED').length;
		list.push({ key: 'mine', label: '我的待办阶段', value: myOpen.value.length, foot: blocked ? `其中 ${blocked} 个受阻` : '未开始 + 进行中', tone: blocked ? 'bad' : 'primary', to: '/mold/my-stages' });
	}
	if (can('mold:project:list')) {
		const blockedProjects = activeProjects.value.filter((p) => blockedOf(p) > 0).length;
		const overdueCount = activeProjects.value.filter(overdue).length;
		const issueCount = activeProjects.value.reduce((n, p) => n + (p.openIssueCount || 0), 0);
		list.push({ key: 'active', label: '进行中的项目', value: activeProjects.value.length, foot: '不含已暂停', tone: 'primary', to: '/mold/projects', query: { status: 'ACTIVE' } });
		list.push({ key: 'blocked', label: '有受阻阶段', value: blockedProjects, foot: '个项目需要协调', tone: blockedProjects ? 'bad' : 'ok', to: '/mold/projects' });
		list.push({ key: 'overdue', label: '已逾期', value: overdueCount, foot: '个项目超过计划交期', tone: overdueCount ? 'bad' : 'ok', to: '/mold/projects' });
		if (!can('mold:stage:mine')) list.push({ key: 'issues', label: '未解决异常', value: issueCount, foot: '分布在进行中的项目', tone: issueCount ? 'warn' : 'ok', to: '/mold/projects' });
	}
	return list.slice(0, 4);
});

function go(path: string, query?: Record<string, string>) {
	router.push({ path, query });
}

async function load() {
	loading.value = true;
	loadError.value = '';
	cashError.value = '';
	await ensureLoaded();
	const tasks: Promise<void>[] = [];
	if (can('mold:project:list')) {
		tasks.push(
			fetchProjectPage({ pageNum: 1, pageSize: 100, status: 'ACTIVE', sort: 'lastUpdatedAt', order: 'desc' }).then((page) => {
				activeProjects.value = page.rows;
				page.rows.forEach((p) => (projectNames[p.id] = `${p.code} · ${p.name}`));
			})
		);
	}
	if (can('mold:stage:mine')) {
		tasks.push(
			fetchMyStages({ pageNum: 1, pageSize: 100, sort: 'updatedAt', order: 'desc' }).then((page) => {
				myStages.value = page.rows;
			})
		);
	}
	if (can('mold:cash:viewAll')) {
		tasks.push(
			fetchCashSummary({ dateFrom: monthStart, dateTo: today, currency: 'CNY' })
				.then((r) => {
					const cny = r.currencies.find((c) => c.currency === 'CNY');
					let inCents = 0;
					let outCents = 0;
					(cny?.categories || []).forEach((c) => {
						const cents = Math.round(Number(c.amount) * 100);
						if (c.direction === 'IN') inCents += cents;
						else outCents += cents;
					});
					cash.in = (inCents / 100).toFixed(2);
					cash.out = (outCents / 100).toFixed(2);
					cash.net = cny?.netCash ?? ((inCents - outCents) / 100).toFixed(2);
				})
				.catch((err) => {
					cashError.value = describeMoldError(err, '本月收支加载失败').message;
				})
		);
	}
	const results = await Promise.allSettled(tasks);
	const failed = results.find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined;
	if (failed) loadError.value = describeMoldError(failed.reason, '工作台数据加载失败').message;
	// 我的阶段可能属于已暂停或不在第一页的项目：补齐名称
	const missing = [...new Set(myStages.value.map((s) => s.projectId))].filter((id) => !projectNames[id]);
	if (missing.length && can('mold:project:list')) {
		try {
			const page = await fetchProjectPage({ pageNum: 1, pageSize: 100, sort: 'lastUpdatedAt', order: 'desc' });
			page.rows.forEach((p) => (projectNames[p.id] = `${p.code} · ${p.name}`));
		} catch {
			/* 名称缺失只影响副标题，不打断工作台 */
		}
	}
	loading.value = false;
}

onMounted(load);
// 权限在登录后才确定：能力串变化时重新加载一次
let lastCaps = '';
watch(capabilities, (v) => {
	const key = (v || []).join(',');
	if (lastCaps && key !== lastCaps) load();
	lastCaps = key;
});
</script>

<style scoped lang="scss">
.wb-head {
	display: flex;
	align-items: flex-end;
	justify-content: space-between;
	gap: 16px;
	flex-wrap: wrap;
	margin-bottom: 20px;
	h1 {
		margin: 0;
		font-size: 22px;
		line-height: 30px;
		font-weight: 600;
		color: var(--el-text-color-primary);
	}
	p {
		margin: 4px 0 0;
		font-size: 13px;
		color: var(--el-text-color-secondary);
	}
}
.wb-actions {
	display: flex;
	gap: 8px;
	flex-wrap: wrap;
	.el-button + .el-button {
		margin-left: 0;
	}
}
.wb-alert {
	margin-bottom: 16px;
}
.wb-kpis {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
	gap: 12px;
	margin-bottom: 16px;
}
.wb-kpi {
	display: flex;
	flex-direction: column;
	align-items: flex-start;
	gap: 2px;
	padding: 16px 18px;
	text-align: left;
	font: inherit;
	background: var(--el-bg-color);
	border: 1px solid var(--el-border-color-lighter);
	border-left: 3px solid var(--wb-tone, var(--el-color-primary));
	border-radius: var(--mold-radius-card);
	cursor: pointer;
	transition: box-shadow 0.15s ease, transform 0.15s ease;
	&:hover {
		box-shadow: var(--el-box-shadow-light);
		transform: translateY(-1px);
	}
	&.is-primary {
		--wb-tone: var(--el-color-primary);
	}
	&.is-ok {
		--wb-tone: var(--el-color-success);
	}
	&.is-warn {
		--wb-tone: var(--el-color-warning);
	}
	&.is-bad {
		--wb-tone: var(--el-color-danger);
		.wb-kpi-value {
			color: var(--el-color-danger);
		}
	}
}
.wb-kpi-label {
	font-size: 13px;
	color: var(--el-text-color-secondary);
}
.wb-kpi-value {
	font-size: 30px;
	line-height: 38px;
	font-weight: 600;
	font-variant-numeric: tabular-nums;
	color: var(--el-text-color-primary);
}
.wb-skel {
	display: inline-block;
	width: 48px;
	height: 28px;
}
.wb-kpi-foot {
	font-size: 12px;
	color: var(--el-text-color-placeholder);
}
.wb-grid {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 16px;
	align-items: start;
}
.wb-panel {
	padding: 16px 18px;
	min-height: 160px;
}
.wb-list {
	list-style: none;
	margin: 0;
	padding: 0;
	li + li {
		border-top: 1px dashed var(--el-border-color-lighter);
	}
}
.wb-row {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	width: 100%;
	padding: 10px 6px;
	font: inherit;
	text-align: left;
	background: none;
	border: 0;
	border-radius: 6px;
	cursor: pointer;
	&:hover {
		background: var(--el-fill-color-light);
	}
}
.wb-row-main {
	display: flex;
	flex-direction: column;
	min-width: 0;
}
.wb-row-title {
	font-size: 14px;
	line-height: 22px;
	color: var(--el-text-color-primary);
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.wb-row-sub {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.wb-row-side {
	display: flex;
	align-items: center;
	gap: 6px;
	flex-shrink: 0;
}
.wb-due {
	font-size: 12px;
	color: var(--el-text-color-secondary);
	white-space: nowrap;
	&.is-warn {
		color: var(--el-color-warning-dark-2);
	}
	&.is-bad {
		color: var(--el-color-danger);
		font-weight: 600;
	}
}
.wb-empty {
	display: flex;
	align-items: center;
	gap: 8px;
	padding: 20px 4px;
	font-size: 13px;
	color: var(--el-text-color-secondary);
	.el-icon {
		font-size: 18px;
		color: var(--el-color-success);
	}
	&.is-error {
		color: var(--el-color-danger);
	}
}
.wb-cash {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 12px;
	margin: 4px 0 8px;
	dt {
		font-size: 12px;
		color: var(--el-text-color-secondary);
	}
	dd {
		margin: 4px 0 0;
		font-size: 20px;
		font-weight: 600;
		font-variant-numeric: tabular-nums;
	}
	.is-in {
		color: var(--el-color-success);
	}
	.is-out {
		color: var(--el-color-danger);
	}
}
@media (max-width: 900px) {
	.wb-grid {
		grid-template-columns: minmax(0, 1fr);
	}
	.wb-cash dd {
		font-size: 16px;
	}
}
</style>
