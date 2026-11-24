// 界面结构检测：截图在当前通道看不到画面，改为用页面结构判断常见视觉问题。
// 检查项：横向滚动条、元素超出视口右边、按钮/标签/表头文字被截断、顶栏折叠按钮与面包屑、Logo 与顶栏颜色、页面报错和接口错误。
// 用法：node deploy/e2e/ui_layout_check.cjs
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.HOME, '.npm/_npx/e41f203b7505f1fb/node_modules/playwright'));

const BASE = process.env.E2E_WEB || 'http://127.0.0.1:8080';
const ACC = JSON.parse(fs.readFileSync(path.join(__dirname, '.accounts.json'), 'utf8'));

const PLAN = {
	OWNER: ['/home', '/mold/projects', 'DETAIL', '/mold/cash', '/mold/corrections', '/mold/accounts', '/mold/audit', '/mold/purchases'],
	PM: ['/home', '/mold/projects', 'DETAIL', '/mold/my-stages'],
	PROGRAMMER: ['/home', '/mold/my-stages'],
	FINANCE: ['/home', '/mold/cash', '/mold/corrections', '/mold/purchases'],
	VIEWER: ['/home', '/mold/projects'],
};

async function login(page, role) {
	const a = ACC[role];
	await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' });
	await page.locator('.login-content-form input').nth(0).fill(a.loginName);
	await page.locator('.login-content-form input[type="password"]').first().fill(a.password);
	const btn = page.locator('.login-content-form button').filter({ hasText: /登\s*录/ }).first();
	if (await btn.count()) await btn.click();
	else await page.keyboard.press('Enter');
	await page.waitForFunction(() => !location.hash.startsWith('#/login'), null, { timeout: 20000 }).catch(() => {});
	await page.waitForLoadState('networkidle').catch(() => {});
}

function inspect() {
	const vw = window.innerWidth;
	const out = { hscroll: document.documentElement.scrollWidth - vw, overflowRight: [], clipped: [], shell: {} };
	const label = (el) => {
		const cls = (el.className && typeof el.className === 'string' ? el.className.split(' ').filter(Boolean).slice(0, 2).join('.') : '');
		return `${el.tagName.toLowerCase()}${cls ? '.' + cls : ''}「${(el.innerText || '').trim().slice(0, 18)}」`;
	};
	const visible = (el) => {
		const r = el.getBoundingClientRect();
		const cs = getComputedStyle(el);
		return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
	};
	// 超出视口右边（排除表格内部：表格自身可横向滚动；排除弹出层）
	for (const el of document.querySelectorAll('.layout-main *, .layout-navbars-container *')) {
		if (el.closest('.el-table__body-wrapper, .el-table__header-wrapper, .el-scrollbar__wrap--hidden-default .el-table, .el-popper, .el-overlay, .el-tabs__nav-scroll, .layout-navbars-tagsview')) continue;
		if (!visible(el)) continue;
		const r = el.getBoundingClientRect();
		if (r.right > vw + 2 && r.left < vw) out.overflowRight.push(`${label(el)} 右边 ${Math.round(r.right)}>${vw}`);
		if (out.overflowRight.length >= 6) break;
	}
	// 文字被截断（未使用省略号的情况更值得关注）
	for (const el of document.querySelectorAll('.el-button > span, .el-tag__content, .el-table th .cell, .mold-stat-label, .mold-card-title, .mold-page-head h1, .el-form-item__label, .el-menu-item span')) {
		if (!visible(el)) continue;
		if (el.scrollWidth > el.clientWidth + 1 && el.clientWidth > 0) {
			const cs = getComputedStyle(el);
			out.clipped.push(`${label(el)}${cs.textOverflow === 'ellipsis' ? '（省略号）' : ''} ${el.scrollWidth}/${el.clientWidth}`);
		}
		if (out.clipped.length >= 8) break;
	}
	const q = (s) => document.querySelector(s);
	const topbar = q('.layout-navbars-breadcrumb-index');
	const logo = q('.layout-logo-medium-img, .layout-logo span, .layout-logo');
	out.shell = {
		collapseBtn: !!q('.layout-navbars-breadcrumb-icon') && visible(q('.layout-navbars-breadcrumb-icon')),
		breadcrumb: !!q('.layout-navbars-breadcrumb') && visible(q('.layout-navbars-breadcrumb')),
		aside: !!q('.layout-aside') && visible(q('.layout-aside')),
		topbarBg: topbar ? getComputedStyle(topbar).backgroundImage !== 'none' ? 'gradient' : getComputedStyle(topbar).backgroundColor : '-',
		logoColor: logo ? getComputedStyle(logo).color : '-',
		mainBg: q('.layout-main') ? getComputedStyle(q('.layout-main')).backgroundColor : '-',
		techWords: (document.body.innerText.match(/If-Match|数据版本|D1 三要素|幂等|服务端|契约|OVER_[A-Z]+|GET \/admin|mock/g) || []).slice(0, 5),
		empty: !!q('.mold-state') && visible(q('.mold-state')),
	};
	return out;
}

(async () => {
	const browser = await chromium.launch({ channel: 'chrome', headless: true });
	const report = [];
	for (const [role, pages] of Object.entries(PLAN)) {
		for (const vp of [{ name: '电脑', width: 1440, height: 900 }, { name: '笔记本', width: 1280, height: 720 }, { name: '手机', width: 390, height: 844 }]) {
			const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN' });
			const page = await ctx.newPage();
			const errs = [];
			page.on('pageerror', (e) => errs.push('JS ' + String(e.message).slice(0, 120)));
			page.on('response', (r) => {
				if (r.url().includes('/api/') && r.status() >= 400) errs.push(`${r.status()} ${r.url().replace(BASE, '').split('?')[0]}`);
			});
			await login(page, role);
			for (const p of pages) {
				let name = p;
				if (p === 'DETAIL') {
					await page.goto(BASE + '/#/mold/projects', { waitUntil: 'networkidle' });
					await page.waitForTimeout(800);
					const link = page.locator('.el-table__body a, .el-table__body .el-link, .el-table__body .el-button.is-link').first();
					if (!(await link.count())) { report.push(`${role}/${vp.name} 详情：列表里没有可点的项目`); continue; }
					await link.click();
					await page.waitForLoadState('networkidle').catch(() => {});
					name = '项目详情';
				} else {
					await page.goto(BASE + '/#' + p, { waitUntil: 'networkidle' });
				}
				await page.waitForTimeout(1200);
				const r = await page.evaluate(inspect);
				const issues = [];
				if (r.hscroll > 2) issues.push(`横向滚动 ${r.hscroll}px`);
				if (r.overflowRight.length) issues.push('超出右边: ' + r.overflowRight.join(' | '));
				if (r.clipped.length) issues.push('文字截断: ' + r.clipped.join(' | '));
				if (r.shell.techWords.length) issues.push('技术用语: ' + r.shell.techWords.join(','));
				if (vp.name !== '手机' && !r.shell.collapseBtn) issues.push('缺折叠菜单按钮');
				if (vp.name !== '手机' && !r.shell.breadcrumb) issues.push('缺面包屑');
				if (r.shell.topbarBg === 'gradient') issues.push('顶栏仍是渐变');
				const shell = `顶栏=${r.shell.topbarBg} Logo=${r.shell.logoColor} 主区=${r.shell.mainBg}${r.shell.empty ? ' [空状态]' : ''}`;
				report.push(`${role}/${vp.name} ${name}: ${issues.length ? issues.join('；') : '正常'}${p === '/home' ? '  (' + shell + ')' : ''}`);
			}
			if (errs.length) report.push(`${role}/${vp.name} 报错: ${[...new Set(errs)].join('; ')}`);
			await ctx.close();
		}
	}
	await browser.close();
	console.log(report.join('\n'));
})().catch((e) => {
	console.error('脚本异常：' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e));
	process.exit(2);
});
