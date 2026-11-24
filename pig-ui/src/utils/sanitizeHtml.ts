/**
 * 站点配置里的富文本（页脚、隐私提示）白名单净化，防存储型 XSS。
 * 只保留少量排版标签与安全链接；脚本类标签整段丢弃，其余未知标签降级为纯文本。
 * template.innerHTML 解析不会执行脚本，净化在惰性 DOM 上完成。
 */
const ALLOWED_TAGS = new Set(['A', 'B', 'STRONG', 'I', 'EM', 'U', 'SPAN', 'SMALL', 'BR', 'P']);
const DROP_TAGS = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'LINK', 'META', 'FORM', 'INPUT', 'SVG', 'MATH']);
const ALLOWED_ATTRS = new Set(['href', 'target', 'rel', 'class', 'title']);
const SAFE_HREF = /^(https?:|mailto:|\/(?!\/)|#)/i;

function clean(parent: Element | DocumentFragment) {
	Array.from(parent.children).forEach((el) => {
		if (DROP_TAGS.has(el.tagName)) {
			el.remove();
			return;
		}
		if (!ALLOWED_TAGS.has(el.tagName)) {
			el.replaceWith(document.createTextNode(el.textContent || ''));
			return;
		}
		Array.from(el.attributes).forEach((attr) => {
			const name = attr.name.toLowerCase();
			if (!ALLOWED_ATTRS.has(name) || (name === 'href' && !SAFE_HREF.test(attr.value.trim()))) {
				el.removeAttribute(attr.name);
			}
		});
		if (el.tagName === 'A') el.setAttribute('rel', 'noopener noreferrer');
		clean(el);
	});
}

export function sanitizeHtml(html?: string | null): string {
	if (!html) return '';
	const template = document.createElement('template');
	template.innerHTML = html;
	clean(template.content);
	return template.innerHTML;
}
