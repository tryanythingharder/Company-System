<template>
	<el-tag :type="meta.type" :effect="effect" size="small" class="mold-status-tag">{{ meta.label }}</el-tag>
</template>

<script setup lang="ts" name="MoldStatusTag">
import { computed } from 'vue';

/**
 * 状态标签：文案与色板集中一处，页面不散写映射。
 * 取值口径来自契约各节状态机（采购 DRAFT→CONFIRMED→VOID、阶段四态、账号 ACTIVE/DISABLED 等）。
 */
type Kind =
	| 'purchase'
	| 'cash'
	| 'receipt'
	| 'invoice'
	| 'stage'
	| 'user'
	| 'counterparty'
	| 'payment'
	| 'progress'
	| 'reconcile'
	| 'sensitivity';

const props = withDefaults(defineProps<{ kind: Kind; value?: string | boolean | null; effect?: 'light' | 'plain' | 'dark' }>(), {
	effect: 'light',
});

const MAPS: Record<Kind, Record<string, { label: string; type: any }>> = {
	purchase: {
		DRAFT: { label: '草稿', type: 'warning' },
		CONFIRMED: { label: '已确认', type: 'success' },
		VOID: { label: '已作废', type: 'info' },
	},
	cash: {
		CONFIRMED: { label: '已确认', type: 'success' },
		VOID: { label: '已作废', type: 'info' },
	},
	receipt: {
		ACTIVE: { label: '有效', type: 'success' },
		VOID: { label: '已作废', type: 'info' },
	},
	invoice: {
		ACTIVE: { label: '有效', type: 'success' },
		VOID: { label: '已作废', type: 'info' },
	},
	stage: {
		NOT_STARTED: { label: '未开始', type: 'info' },
		IN_PROGRESS: { label: '进行中', type: 'primary' },
		BLOCKED: { label: '受阻', type: 'danger' },
		COMPLETED: { label: '已完成', type: 'success' },
	},
	user: {
		ACTIVE: { label: '启用', type: 'success' },
		DISABLED: { label: '停用', type: 'info' },
	},
	counterparty: {
		ACTIVE: { label: '合作中', type: 'success' },
		DISABLED: { label: '停用', type: 'info' },
		INACTIVE: { label: '停用', type: 'info' },
	},
	payment: {
		UNPAID: { label: '未付款', type: 'warning' },
		PARTIAL: { label: '部分付款', type: 'primary' },
		PAID: { label: '已付清', type: 'success' },
		OVERPAID: { label: '超付', type: 'danger' },
	},
	progress: {
		NONE: { label: '未开始', type: 'info' },
		PARTIAL: { label: '部分', type: 'warning' },
		FULL: { label: '已完成', type: 'success' },
	},
	reconcile: {
		true: { label: '已对账', type: 'success' },
		false: { label: '未对账', type: 'info' },
	},
	sensitivity: {
		NORMAL: { label: '普通', type: 'info' },
		FINANCIAL: { label: '财务', type: 'danger' },
	},
};

const meta = computed(() => {
	const key = typeof props.value === 'boolean' ? String(props.value) : String(props.value ?? '');
	return MAPS[props.kind]?.[key] || { label: key || '—', type: 'info' };
});
</script>

<style scoped lang="scss">
.mold-status-tag {
	white-space: nowrap;
}
</style>
