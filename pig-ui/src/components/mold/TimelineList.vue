<template>
	<div class="mold-timeline" :aria-busy="loading ? 'true' : 'false'">
		<div v-if="loading" class="mold-timeline-loading">
			<div v-for="i in 3" :key="i" class="mold-skel-block" style="height: 56px; width: 100%; margin-bottom: 8px" />
		</div>
		<el-empty v-else-if="!events.length" :description="emptyText" :image-size="72" />
		<el-timeline v-else>
			<el-timeline-item
				v-for="e in events"
				:key="e.id"
				:timestamp="formatDateTime(e.occurredAt)"
				placement="top"
				:type="dotType(e)"
				:hollow="e.action === 'UPDATE'"
			>
				<div class="mold-timeline-item">
					<div class="mold-timeline-title">
						<span class="mold-timeline-action">{{ actionText(e.action) }}</span>
						<template v-if="e.previousStatus || e.status">
							<StatusTag v-if="e.previousStatus" kind="stage" :value="e.previousStatus" effect="plain" />
							<span class="mold-timeline-arrow" aria-label="变为">→</span>
							<StatusTag v-if="e.status" kind="stage" :value="e.status" effect="plain" />
						</template>
					</div>
					<p v-if="e.reason" class="mold-timeline-reason">{{ e.reason }}</p>
					<div class="mold-timeline-meta">
						<span v-if="e.actorId">操作人：{{ actorName(e.actorId) }}</span>
						<span v-if="e.issueId">关联异常：{{ e.issueId.slice(0, 8) }}</span>
					</div>
				</div>
			</el-timeline-item>
		</el-timeline>
	</div>
</template>

<script setup lang="ts" name="MoldTimelineList">
import StatusTag from '/@/components/mold/StatusTag.vue';
import { formatDateTime } from '/@/utils/moldFormat';
import type { MoldStageEvent } from '/@/api/mold/stages';

const props = withDefaults(
	defineProps<{
		events: MoldStageEvent[];
		loading?: boolean;
		emptyText?: string;
		/** actorId -> 显示名；缺省显示短 id */
		actorNames?: Record<string, string>;
	}>(),
	{ loading: false, emptyText: '还没有阶段事件', actorNames: () => ({}) }
);

const ACTION_TEXT: Record<string, string> = {
	CREATE: '创建阶段',
	UPDATE: '编辑阶段',
	START: '开始',
	BLOCK: '标记受阻',
	RESUME: '解除受阻',
	COMPLETE: '完成',
	TRANSITION: '状态流转',
};

const actionText = (action: string) => ACTION_TEXT[action] || action || '事件';

const dotType = (e: MoldStageEvent) => {
	if (e.action === 'BLOCK') return 'danger';
	if (e.action === 'COMPLETE') return 'success';
	if (e.action === 'START' || e.action === 'RESUME') return 'primary';
	return '';
};

const actorName = (id: string) => props.actorNames?.[id] || id.slice(0, 8);
</script>

<style scoped lang="scss">
.mold-timeline-item {
	min-width: 0;
}
.mold-timeline-title {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 6px;
	font-size: 14px;
	line-height: 22px;
}
.mold-timeline-action {
	font-weight: 600;
}
.mold-timeline-arrow {
	color: var(--el-text-color-secondary);
}
.mold-timeline-reason {
	margin: 6px 0 0;
	font-size: 13px;
	line-height: 20px;
	color: var(--el-text-color-regular);
	overflow-wrap: anywhere;
}
.mold-timeline-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 12px;
	margin-top: 4px;
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
}
.mold-timeline-loading {
	padding: 4px 0;
}
</style>
