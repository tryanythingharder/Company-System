import { RouteRecordRaw } from 'vue-router';

/**
 * 建议：路由 path 路径与文件夹名称相同，找文件可浏览器地址找，方便定位文件位置
 *
 * 路由meta对象参数说明
 * meta: {
 *      title:          菜单栏及 tagsView 栏、菜单搜索名称（国际化）
 *      isLink：        是否超链接菜单，开启外链条件，`1、isLink: 链接地址不为空 2、isIframe:false`
 *      isHide：        是否隐藏此路由
 *      isKeepAlive：   是否缓存组件状态
 *      isAuth: 	    是否需要认证才能进入的页面
 *      isAffix：       是否固定在 tagsView 栏上
 *      isIframe：      是否内嵌窗口，开启条件，`1、isIframe:true 2、isLink：链接地址不为空`
 *      roles：         当前路由权限标识，取角色管理。控制路由显示、隐藏。超级管理员：admin 普通角色：common
 *      permissions：   功能权限串（mold:<资源>:<动作>），任一命中才在导航/命令面板中显示；
 *                      为空表示所有登录角色可见。仅影响导航展示，路由仍注册，直连由页面自身 403 兜底。
 *      icon：          菜单、tagsView 图标，阿里：加 `iconfont xxx`，fontawesome：加 `fa xxx`
 * }
 */

// 扩展 RouteMeta 接口
declare module 'vue-router' {
	interface RouteMeta {
		isLink?: string;
		isHide?: boolean;
		isAuth?: boolean;
		isKeepAlive?: boolean;
		isAffix?: boolean;
		isIframe?: boolean;
		roles?: string[];
		permissions?: string[];
		icon?: string;
	}
}

/**
 * 定义静态路由（默认路由）
 * 前端添加路由，请在此处加
 */
export const dynamicRoutes: Array<RouteRecordRaw> = [
	{
		path: '/home',
		name: 'router.home',
		component: () => import('/@/views/home/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isAffix: true,
			isIframe: false,
			icon: 'iconfont icon-shouye',
		},
	},
	{
		path: '/mold/projects',
		name: 'moldProjects',
		component: () => import('/@/views/mold/projects/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isIframe: false,
			title: '公司进度',
			permissions: ['mold:project:list'],
			icon: 'iconfont icon-shuju',
		},
	},
	{
		// 项目详情：深链直达（M3 第二切片 P1-01/P1-07），不在菜单中显示
		path: '/mold/project/:id',
		name: 'moldProjectDetail',
		component: () => import('/@/views/mold/project/detail.vue'),
		meta: {
			isHide: true,
			isKeepAlive: false,
			title: '项目详情',
			permissions: ['mold:project:list'],
		},
	},
	{
		// 我的工作（契约 5.5 / 6.6）：责任人 / 成员的阶段
		path: '/mold/my-stages',
		name: 'moldMyStages',
		component: () => import('/@/views/mold/my-stages/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isIframe: false,
			title: '我的工作',
			icon: 'iconfont icon-geren',
		},
	},
	{
		// 采购台账（契约 6.8）：采购单 + 往来单位
		// 用户决策 2026-11-15「菜单里藏起来」：仅从前端导航/命令面板隐藏，路由与页面保留，
		// 后端接口照旧，直连 /mold/purchases 仍可打开（有意保留，以后要恢复只需把 isHide 改回 false）。
		path: '/mold/purchases',
		name: 'moldPurchases',
		component: () => import('/@/views/mold/purchases/index.vue'),
		meta: {
			isLink: '',
			isHide: true,
			isKeepAlive: true,
			isIframe: false,
			title: '采购台账',
			permissions: ['mold:purchase:list'],
			icon: 'iconfont icon-gouwuche',
		},
	},
	{
		// 财务收支（契约 6.9）：流水 + 报表
		path: '/mold/cash',
		name: 'moldCash',
		component: () => import('/@/views/mold/cash/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isIframe: false,
			title: '财务收支',
			permissions: ['mold:cash:list'],
			icon: 'iconfont icon-qian',
		},
	},
	{
		// 更正记录（契约 6.9）：预览 → 提交 → 更正链
		path: '/mold/corrections',
		name: 'moldCorrections',
		component: () => import('/@/views/mold/corrections/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: false,
			isIframe: false,
			title: '更正记录',
			permissions: ['mold:correction:preview'],
			icon: 'iconfont icon-shuaxin',
		},
	},
	{
		// 阶段历史（契约 6.6）：深链 /mold/stages/:id? 与选择器并存
		path: '/mold/stages/:id?',
		name: 'moldStageHistory',
		component: () => import('/@/views/mold/stage-history/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: false,
			isIframe: false,
			title: '阶段历史',
			permissions: ['mold:stage:timeline'],
			icon: 'iconfont icon-shijian',
		},
	},
	{
		// 账号管理（契约 6.4）：仅 OWNER
		path: '/mold/accounts',
		name: 'moldAccounts',
		component: () => import('/@/views/mold/accounts/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isIframe: false,
			title: '账号管理',
			permissions: ['mold:user:list'],
			icon: 'iconfont icon-yonghu',
		},
	},
	{
		// 审计日志（契约 6.10）：仅 OWNER，独立路由（权限与账号管理不同）
		path: '/mold/audit',
		name: 'moldAudit',
		component: () => import('/@/views/mold/audit/index.vue'),
		meta: {
			isLink: '',
			isHide: false,
			isKeepAlive: true,
			isIframe: false,
			title: '审计日志',
			permissions: ['mold:audit:list'],
			icon: 'iconfont icon-rizhi',
		},
	},
	{
		path: '/personal',
		name: 'router.personal',
		component: () => import('/@/views/admin/system/user/components/personal.vue'),
		meta: {
			isHide: true,
		},
	},
];

/**
 * 定义静态路由（默认路由）
 */
export const staticRoutes: Array<RouteRecordRaw> = [
	{
		path: '/login',
		name: 'staticRoutes.login',
		component: () => import('/@/views/login/index.vue'),
		meta: {
			isAuth: false,
		},
	},
	{
		path: '/authredirect',
		name: 'staticRoutes.authredirect',
		component: () => import('/@/views/login/component/authredirect.vue'),
		meta: {
			isAuth: false,
		},
	},
	{
		// 强制改密（G-04）：全屏页，不进菜单；登录后 mustChangePassword=true 时只能停在这里
		path: '/change-password',
		name: 'staticRoutes.changePassword',
		component: () => import('/@/views/login/changePassword.vue'),
		meta: {
			isAuth: true,
		},
	},
];

/**
 * 定义404、401界面
 */
export const notFoundAndNoPower = [
	{
		path: '/:path(.*)*',
		name: 'staticRoutes.notFound',
		component: () => import('/@/views/error/404.vue'),
		meta: {
			isHide: true,
		},
	},
	{
		path: '/401',
		name: 'staticRoutes.noPower',
		component: () => import('/@/views/error/401.vue'),
		meta: {
			isHide: true,
		},
	},
];

/**
 *  基础性路由
 *
 * 所有节点都是挂载此节点下
 */
export const baseRoutes: Array<RouteRecordRaw> = [
	{
		path: '/',
		name: '/',
		component: () => import('/@/layout/index.vue'),
		redirect: '/home',
		meta: {
			isKeepAlive: true,
		},
		children: [],
	},

];
