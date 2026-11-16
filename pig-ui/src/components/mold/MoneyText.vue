<template>
	<span class="mold-money" :class="{ 'is-in': direction === 'IN', 'is-out': direction === 'OUT', 'is-muted': muted }">
		<span v-if="direction === 'IN'" class="mold-money-sign">+</span>
		<span v-else-if="direction === 'OUT'" class="mold-money-sign">−</span>
		{{ text }}
	</span>
</template>

<script setup lang="ts" name="MoldMoneyText">
import { computed } from 'vue';

/**
 * 金额列（契约 7.5-6：decimal 以字符串传输，展示前 Number()，不参与写回）。
 * 方向前缀只做视觉提示，符号本身不改变服务端记账。
 */
const props = defineProps<{
	value?: string | number | null;
	currency?: string;
	direction?: 'IN' | 'OUT' | '';
	muted?: boolean;
}>();

const SYMBOL: Record<string, string> = { CNY: '¥', USD: '$', EUR: '€', HKD: 'HK$' };

const text = computed(() => {
	if (props.value === null || props.value === undefined || props.value === '') return '—';
	const n = Number(props.value);
	if (!Number.isFinite(n)) return String(props.value);
	const body = n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	const symbol = SYMBOL[props.currency || 'CNY'] || `${props.currency || ''} `;
	return `${symbol}${body}`;
});
</script>

<style scoped lang="scss">
.mold-money {
	font-variant-numeric: tabular-nums;
	white-space: nowrap;
}
.mold-money-sign {
	margin-right: 1px;
}
.is-in {
	color: var(--el-color-success);
}
.is-out {
	color: var(--el-color-danger);
}
.is-muted {
	color: var(--el-text-color-secondary);
}
</style>
