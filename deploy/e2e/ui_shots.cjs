// 界面改版截图：各角色登录后对首页和主要业务页截图（1440 宽桌面 + 390 宽手机），用于肉眼核对视觉。
// 用法：node deploy/e2e/ui_shots.cjs   截图输出到 deploy/e2e/ui_shots_out/（已被 git 忽略）
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.HOME, '.npm/_npx/e41f203b7505f1fb/node_modules/playwright'));

const BASE = process.env.E2E_WEB || 'http://127.0.0.1:8080';
const ACC = JSON.parse(fs.readFileSync(path.join(__dirname, '.accounts.json'), 'utf8'));
const OUT = path.join(__dirname, 'ui_shots_out');
fs.mkdirSync(OUT, { recursive: true });

const PLAN = {
	OWNER: ['/home', '/mold/projects', 'DETAIL', '/mold/cash', '/mold/accounts', '/mold/audit'],
	PM: ['/home', '/mold/projects', 'DETAIL'],
	PROGRAMMER: ['/home', '/mold/my-stages'],
	FINANCE: ['/home', '/mold/cash', '/mold/corrections'],
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

(async () => {
	const browser = await chromium.launch({ channel: 'chrome', headless: true });
	const problems = [];
	for (const [role, pages] of Object.entries(PLAN)) {
		for (const vp of [{ name: 'pc', width: 1440, height: 900 }, { name: 'm', width: 390, height: 844 }]) {
			if (vp.name === 'm' && role !== 'PROGRAMMER' && role !== 'OWNER') continue;
			const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN', deviceScaleFactor: 1 });
			const page = await ctx.newPage();
			const errs = [];
			page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 160)));
			page.on('response', (r) => {
				if (r.url().includes('/api/') && r.status() >= 400) errs.push(`${r.status()} ${r.url().replace(BASE, '').split('?')[0]}`);
			});
			await login(page, role);
			for (const p of pages) {
				let target = p;
				if (p === 'DETAIL') {
					await page.goto(BASE + '/#/mold/projects', { waitUntil: 'networkidle' });
					await page.waitForTimeout(800);
					const link = page.locator('.el-table__body a, .el-table__body .el-link, .el-table__body .el-button.is-link').first();
					if (!(await link.count())) continue;
					await link.click();
					await page.waitForLoadState('networkidle').catch(() => {});
					target = '详情';
				} else {
					await page.goto(BASE + '/#' + p, { waitUntil: 'networkidle' });
				}
				await page.waitForTimeout(1500);
				const name = `${role}_${vp.name}_${String(target).replace(/\//g, '_').replace(/^_/, '')}.png`;
				await page.screenshot({ path: path.join(OUT, name), fullPage: false });
			}
			if (errs.length) problems.push(`${role}/${vp.name}: ${[...new Set(errs)].join('; ')}`);
			await ctx.close();
		}
	}
	await browser.close();
	console.log('截图：', fs.readdirSync(OUT).filter((f) => f.endsWith('.png')).length, '张 →', OUT);
	console.log('问题：', problems.length ? problems : '无');
})().catch((e) => {
	console.error('脚本异常：' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e));
	process.exit(2);
});
