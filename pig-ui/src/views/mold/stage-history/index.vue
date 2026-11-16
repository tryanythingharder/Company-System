<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>阶段历史</h1>
							<p>阶段时间线只追加不修改；每条记录含动作、状态变化、原因与操作人。</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button circle :icon="Refresh" aria-label="刷新阶段历史" :loading="loading" @click="reloadEvents" />
						</div>
					</div>

					<!-- 选择器：项目 → 阶段；深链 /mold/stages/{id} 直接进入时间线 -->
					<div class="mold-filter-bar is-open">
						<el-form :inline="true" @submit.prevent>
							<el-form-item label="项目">
								<el-select
									v-model="projectId"
									filterable
									placeholder="选择项目"
									style="width: 240px"
									:loading="projectsLoading"
									@change="onProjectChange"
								>
									<el-option v-for="p in projects" :key="p.id" :label="`${p.code} · ${p.name}`" :value="p.id" />
								</el-select>
							</el-form-item>
							<el-form-item label="阶段">
								<el-select
									v-model="stageId"
									filterable
									placeholder="选择阶段"
									style="width: 220px"
									:disabled="!projectId"
									:loading="stagesLoading"
									@change="onStageChange"
								>
									<el-option v-for="s in stages" :key="s.id" :label="`${s.sortOrder}. ${s.name}`" :value="s.id" />
								</el-select>
							</el-form-item>
						</el-form>
					</div>

					<StatePanel
						v-if="!stageId"
						kind="empty"
						title="先选择一个阶段"
						description="选择项目和阶段后显示时间线；也可以从「我的工作」或项目详情点「历史」直接进入。"
					/>

					<template v-else>
						<div class="mold-history-head">
							<div>
								<h2 class="mold-history-title">{{ stageLabel }}</h2>
								<div class="mold-history-sub">
									<span v-if="currentStage">
										<StatusTag kind="stage" :value="currentStage.status" />
										<span class="mold-history-dates">
											计划 {{ formatDate(currentStage.plannedStart) }} ~ {{ formatDate(currentStage.plannedEnd) }}
										</span>
									</span>
									<span v-else>阶段 {{ stageId.slice(0, 8) }}</span>
									<span class="mold-history-count">共 {{ eventTotal }} 条事件</span>
								</div>
							</div>
							<div class="mold-history-actions">
								<el-button link type="primary" @click="goProject">打开项目</el-button>
								<el-button v-if="currentStage" link type="primary" @click="goMyWork">去推进</el-button>
							</div>
						</div>

						<div class="mold-history-body">
							<div class="mold-history-timeline">
								<StatePanel
									v-if="eventState === 'forbidden'"
									kind="forbidden"
									title="无阶段历史查看权限"
									description="当前账号没有 mold:project:list 权限。"
									action-text="返回首页"
									@action="goHome"
								/>
								<StatePanel
									v-else-if="eventState === 'error'"
									kind="error"
									:title="eventError.title"
									:description="eventError.message"
									:request-id="eventError.requestId"
									action-text="重试"
									@action="reloadEvents"
								/>
								<template v-else>
									<TimelineList :events="events" :loading="loading" :actor-names="actorNames" />
									<div v-if="eventTotal > pageSize" class="mold-pager">
										<div class="mold-pager-total">共 {{ eventTotal }} 条</div>
										<el-pagination
											class="mold-pager-desktop"
											:current-page="pageNum"
											:page-size="pageSize"
											:total="eventTotal"
											layout="prev, pager, next"
											background
											@current-change="goPage"
										/>
										<div class="mold-pager-mobile">
											<el-button :icon="ArrowLeft" :disabled="pageNum <= 1" @click="goPage(pageNum - 1)">上一页</el-button>
											<span class="mold-pager-info">第 {{ pageNum }} / {{ pageCount }} 页</span>
											<el-button :disabled="pageNum >= pageCount" @click="goPage(pageNum + 1)">
												下一页<el-icon class="el-icon--right"><ArrowRight /></el-icon>
											</el-button>
										</div>
									</div>
								</template>
							</div>

							<aside class="mold-history-side">
								<dl class="mold-kv mold-kv-tight">
									<div><dt>阶段</dt><dd>{{ stageLabel }}</dd></div>
									<div v-if="currentStage"><dt>责任人</dt><dd>{{ actorNames[currentStage.responsibleUserId || ''] || shortId(currentStage.responsibleUserId) }}</dd></div>
									<div v-if="currentStage"><dt>版本</dt><dd>v{{ currentStage.version }}</dd></div>
									<div><dt>事件数</dt><dd>{{ eventTotal }}</dd></div>
								</dl>
								<AttachmentPanel owner-type="STAGE" :owner-id="stageId" title="阶段附件" :subject="stageSubject" />
							</aside>
						</div>
					</template>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="moldStageHistory">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, ArrowRight, Refresh } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import TimelineList from '/@/components/mold/TimelineList.vue';
import AttachmentPanel from '/@/components/mold/AttachmentPanel.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { fetchProjectPage, fetchProjectStages, MoldApiError, type MoldProjectRow } from '/@/api/mold/projects';
import { fetchMyStages, fetchStageEvents, type MoldStageEvent, type MoldStageRow } from '/@/api/mold/stages';
import { fetchDirectory } from '/@/api/mold/users';
import { describeMoldError } from '/@/api/mold/errors';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { formatDate } from '/@/utils/moldFormat';

const route = useRoute();
const router = useRouter();
const { ensureLoaded } = useMoldPermissions();

const projects = ref<MoldProjectRow[]>([]);
const projectsLoading = ref(false);
const stages = ref<MoldStageRow[]>([]);
const stagesLoading = ref(false);
const projectId = ref(String(route.query.projectId || ''));
const stageId = ref(String(route.params.id || route.query.stageId || ''));
const stageNameFromQuery = ref(String(route.query.name || ''));

const events = ref<MoldStageEvent[]>([]);
const eventTotal = ref(0);
const pageNum = ref(1);
const pageSize = ref(20);
const loading = ref(false);
const eventState = ref<'loading' | 'ready' | 'empty' | 'error' | 'forbidden'>('loading');
const eventError = reactive({ title: '加载失败', message: '', requestId: '' });
const actorNames = ref<Record<string, string>>({});
let seq = 0;

const pageCount = computed(() => Math.max(1, Math.ceil(eventTotal.value / pageSize.value)));
const currentStage = computed(() => stages.value.find((s) => s.id === stageId.value) || null);
/** 附件面板的对象级写权限：阶段负责人 + 所属项目负责人（服务端口径） */
const stageSubject = computed(() => ({
	ownerUserId: projects.value.find((p) => p.id === projectId.value)?.ownerUserId || null,
	responsibleUserId: currentStage.value?.responsibleUserId || null,
}));
const stageLabel = computed(() => {
	if (currentStage.value) return `${currentStage.value.sortOrder}. ${currentStage.value.name}`;
	if (stageNameFromQuery.value) return stageNameFromQuery.value;
	return stageId.value ? `阶段 ${stageId.value.slice(0, 8)}` : '';
});

function shortId(id?: string | null) {
	return id ? id.slice(0, 8) : '—';
}

async function loadProjects() {
	projectsLoading.value = true;
	try {
		const page = await fetchProjectPage({ pageNum: 1, pageSize: 100, sort: 'code', order: 'asc' });
		projects.value = page.rows;
	} catch {
		projects.value = [];
	} finally {
		projectsLoading.value = false;
	}
}

async function loadStages() {
	stages.value = [];
	if (!projectId.value) return;
	stagesLoading.value = true;
	try {
		stages.value = (await fetchProjectStages(projectId.value)) as MoldStageRow[];
		if (stageId.value && !stages.value.some((s) => s.id === stageId.value)) {
			// 深链进入时可能只带 stageId：保留，时间线仍可读
		}
	} catch {
		stages.value = [];
	} finally {
		stagesLoading.value = false;
	}
}

/**
 * 由 stageId 反查 projectId（后端没有 GET /stages/{id}）
 * 先查「我的阶段」（单次请求，覆盖「我的工作 → 历史」入口），
 * 再按项目逐个拉阶段短路匹配。只在深链缺少 projectId 时执行。
 */
async function resolveProjectIdByStage(id: string): Promise<string> {
	if (!id) return '';
	try {
		const mine = await fetchMyStages({ pageNum: 1, pageSize: 100 });
		const hit = mine.rows.find((s) => s.id === id);
		if (hit?.projectId) return hit.projectId;
	} catch {
		/* 我的阶段不可用时继续按项目扫描 */
	}
	for (const project of projects.value) {
		try {
			const rows = (await fetchProjectStages(project.id)) as MoldStageRow[];
			if (rows.some((s) => s.id === id)) return project.id;
		} catch {
			/* 单个项目失败不影响其它项目 */
		}
	}
	return '';
}

/** 深链 /mold/stages/:id 缺少 projectId 时补齐，保证阶段下拉与标题可读 */
async function ensureProjectForStage() {
	if (projectId.value || !stageId.value) return;
	const resolved = await resolveProjectIdByStage(stageId.value);
	if (!resolved) return;
	projectId.value = resolved;
	// 把反查结果写回地址栏，刷新后无需再次扫描
	router.replace({ path: route.path, query: { ...route.query, projectId: resolved } });
}

async function loadEvents() {
	if (!stageId.value) return;
	const current = ++seq;
	loading.value = true;
	eventState.value = events.value.length ? 'ready' : 'loading';
	eventError.title = '加载失败';
	eventError.message = '';
	eventError.requestId = '';
	try {
		const page = await fetchStageEvents(stageId.value, {
			pageNum: pageNum.value,
			pageSize: pageSize.value,
			sort: 'id',
			order: 'desc',
		});
		if (current !== seq) return;
		events.value = page.rows;
		eventTotal.value = page.total;
		eventState.value = page.rows.length ? 'ready' : 'empty';
	} catch (err: any) {
		if (current !== seq) return;
		if (err instanceof MoldApiError && err.status === 403) {
			eventState.value = 'forbidden';
			return;
		}
		const view = describeMoldError(err, '阶段历史加载失败');
		eventError.title = view.title;
		eventError.message = view.message;
		eventError.requestId = view.requestId;
		eventState.value = 'error';
	} finally {
		if (current === seq) loading.value = false;
	}
}

async function loadActors() {
	try {
		const { rows } = await fetchDirectory();
		const map: Record<string, string> = {};
		rows.forEach((u) => (map[u.id] = u.displayName));
		actorNames.value = map;
	} catch {
		actorNames.value = {};
	}
}

function reloadEvents() {
	loadEvents();
}

function goPage(next: number) {
	if (next < 1 || next > pageCount.value) return;
	pageNum.value = next;
	loadEvents();
}

function onProjectChange(id: string) {
	stageId.value = '';
	stageNameFromQuery.value = '';
	events.value = [];
	eventTotal.value = 0;
	router.replace({ path: '/mold/stages', query: { projectId: id } });
	loadStages();
}

function onStageChange(id: string) {
	stageNameFromQuery.value = stages.value.find((s) => s.id === id)?.name || '';
	pageNum.value = 1;
	events.value = [];
	eventTotal.value = 0;
	router.replace({ path: `/mold/stages/${encodeURIComponent(id)}`, query: { projectId: projectId.value } });
}

function goProject() {
	if (projectId.value) router.push(`/mold/project/${encodeURIComponent(projectId.value)}`);
	else if (currentStage.value) router.push(`/mold/project/${encodeURIComponent(currentStage.value.projectId)}`);
	else router.push('/mold/projects');
}

function goMyWork() {
	router.push('/mold/my-stages');
}

function goHome() {
	router.push('/home');
}

watch(
	() => [route.params.id, route.query.projectId],
	async ([rawId, rawProject]) => {
		const nextId = String(rawId || '');
		const nextProject = String(rawProject || '');
		if (nextProject && nextProject !== projectId.value) {
			projectId.value = nextProject;
			await loadStages();
		}
		if (nextId && nextId !== stageId.value) {
			stageId.value = nextId;
			stageNameFromQuery.value = String(route.query.name || '');
			pageNum.value = 1;
			events.value = [];
			const hadProject = !!projectId.value;
			await ensureProjectForStage();
			if (!hadProject && projectId.value) await loadStages();
			await loadEvents();
		}
	}
);

onMounted(async () => {
	ensureLoaded();
	await Promise.all([loadProjects(), loadActors()]);
	await ensureProjectForStage();
	if (projectId.value) await loadStages();
	if (stageId.value) await loadEvents();
});

/* SSE：当前阶段的流转/异常/附件变更只刷事件时间线 */
const refreshHistoryOnEvent = debounceRefresh(() => {
	if (stageId.value) loadEvents();
}, 800);
useMoldEvent(['stage.created', 'stage.updated', 'stage.transitioned', 'issue.created', 'issue.updated', 'attachment.created', 'attachment.deleted'], refreshHistoryOnEvent);
</script>

<style scoped lang="scss">
.mold-history-head {
	display: flex;
	flex-wrap: wrap;
	align-items: flex-start;
	justify-content: space-between;
	gap: 8px;
	margin: 4px 0 12px;
}
.mold-history-title {
	margin: 0;
	font-size: 18px;
	line-height: 26px;
}
.mold-history-sub {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 10px;
	margin-top: 4px;
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-history-dates {
	margin-left: 8px;
}
.mold-history-count {
	margin-left: auto;
}
.mold-history-actions {
	display: flex;
	gap: 8px;
}
.mold-history-body {
	display: grid;
	grid-template-columns: minmax(0, 1fr) 320px;
	gap: 16px;
	align-items: start;
}
.mold-history-timeline {
	min-width: 0;
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	padding: 14px;
	background: var(--el-bg-color);
}
.mold-history-side {
	display: flex;
	flex-direction: column;
	gap: 12px;
	min-width: 0;
}
@media (max-width: 900px) {
	.mold-history-body {
		grid-template-columns: minmax(0, 1fr);
	}
}
</style>
