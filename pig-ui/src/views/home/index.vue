<template>
	<Workbench />
</template>

<script setup lang="ts" name="dashboard">
// 首页 = 按角色显示的工作台：待办、需要关注的项目、本月收支、7 天内到期
const Workbench = defineAsyncComponent(() => import('./workbench.vue'));
</script>
