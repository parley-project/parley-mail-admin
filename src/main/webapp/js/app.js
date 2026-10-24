/*
 * Parley Mail Admin: the pages. Plain JavaScript, no libraries; it talks to the JSON API under
 * api/ (ApiServlet). Every text from the server is put in the page with textContent, never as HTML.
 */
(function () {
	'use strict';

	const state = { user: null, csrf: null, setupRequired: false, version: '', domains: [], timers: [], page: null };

	// ------------------------------------------------------------------ themes

	/** swing-widgets' built-in themes, as in parley-files-viewer (the colors are in app.css). */
	const THEMES = ['Gold', 'Dark', 'Light', 'Copper', 'Sage', 'Ocean', 'Slate', 'Plum'];

	function savedTheme() {
		try {
			const t = localStorage.getItem('parley.mail.admin.theme');
			return THEMES.includes(t) ? t : 'Gold';
		} catch (e) {
			return 'Gold';
		}
	}

	function applyTheme(name) {
		document.documentElement.dataset.theme = name.toLowerCase();
		try { localStorage.setItem('parley.mail.admin.theme', name); } catch (e) { /* this browser keeps none */ }
	}

	applyTheme(savedTheme());

	// ------------------------------------------------------------------ DOM helpers

	/** h('div.cls#id', {attrs}, children...) */
	function h(spec, attrs, ...kids) {
		const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(spec) || [];
		const el = document.createElement(m[1] || 'div');
		(m[2] || '').replace(/([.#])([\w-]+)/g, (_, k, v) => { if (k === '.') el.classList.add(v); else el.id = v; });
		if (attrs !== null && attrs !== undefined && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
			kids.unshift(attrs);
			attrs = null;
		}
		for (const [k, v] of Object.entries(attrs || {})) {
			if (v === null || v === undefined || v === false) continue;
			if (k.startsWith('on')) el.addEventListener(k.substring(2), v);
			else if (k === 'class') el.className += ' ' + v;
			else if (k === 'value') el.value = v;
			else if (k === 'checked') el.checked = !!v;
			else el.setAttribute(k, v === true ? '' : v);
		}
		add(el, kids);
		return el;
	}

	function add(el, kids) {
		for (const k of kids) {
			if (k === null || k === undefined || k === false) continue;
			if (Array.isArray(k)) add(el, k);
			else el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
		}
	}

	function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

	function fmtTime(ms) { return ms ? new Date(ms).toLocaleString() : ''; }
	function fmtClock(ms) { return new Date(ms).toLocaleTimeString([], { hour12: false }) + '.' + String(ms % 1000).padStart(3, '0'); }
	function fmtBytes(n) {
		if (n === null || n === undefined) return '';
		const u = ['B', 'KB', 'MB', 'GB', 'TB'];
		let i = 0;
		while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
		return (i === 0 ? n : n.toFixed(1)) + ' ' + u[i];
	}
	function ago(ms) {
		if (!ms) return '';
		let s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		if (s < 60) return s + 's';
		const m = Math.floor(s / 60); if (m < 60) return m + 'm';
		const hr = Math.floor(m / 60); if (hr < 48) return hr + 'h ' + (m % 60) + 'm';
		return Math.floor(hr / 24) + 'd ' + (hr % 24) + 'h';
	}
	function until(ms) {
		if (!ms) return '';
		const s = Math.round((ms - Date.now()) / 1000);
		return s <= 0 ? 'now' : 'in ' + ago(Date.now() - s * 1000);
	}
	function today() { const d = new Date(); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().substring(0, 10); }

	// ------------------------------------------------------------------ API

	async function api(method, path, body) {
		const opts = { method, headers: { 'X-CSRF-Token': state.csrf || 'none', 'Accept': 'application/json' }, credentials: 'same-origin' };
		if (body !== undefined) {
			opts.headers['Content-Type'] = 'application/json';
			opts.body = JSON.stringify(body);
		}
		const res = await fetch('api' + path, opts);
		if (res.status === 204) return null;
		const type = res.headers.get('Content-Type') || '';
		const data = type.includes('json') ? await res.json() : await res.text();
		if (!res.ok) {
			if (res.status === 401 && path !== '/login' && path !== '/session') {
				state.user = null;
				showLogin();
			}
			throw new Error((data && data.error) || ('HTTP ' + res.status));
		}
		return data;
	}

	const enc = encodeURIComponent;

	// ------------------------------------------------------------------ toasts and dialogs

	function toast(text, kind) {
		let box = document.querySelector('.toasts');
		if (!box) { box = h('div.toasts'); document.body.appendChild(box); }
		const t = h('div.toast', { class: kind || '' }, text);
		box.appendChild(t);
		setTimeout(() => t.remove(), kind === 'bad' ? 9000 : 4500);
	}

	function fail(e) { toast(e.message || String(e), 'bad'); }

	/**
	 * A dialog. Resolves with what onOk returns (when it returns without throwing), or null when
	 * it's closed any other way (X, Escape, the backdrop, Cancel): that's always a cancel.
	 */
	function dialog(title, body, opts) {
		opts = opts || {};
		return new Promise(resolve => {
			const error = h('div.error');
			const ok = opts.okLabel === null ? null : h('button.primary', { type: 'submit' }, opts.okLabel || 'OK');
			const cancel = h('button', { type: 'button' }, opts.cancelLabel || (ok ? 'Cancel' : 'Close'));
			const form = h('form.modal', { class: opts.wide ? 'wide' : '' },
				h('header', h('h2', title), h('button.x', { type: 'button', title: 'Close', 'aria-label': 'Close', onclick: () => done(null) }, '×')),
				h('div.body', body, error),
				h('footer', cancel, ok));
			const back = h('div.backdrop', form);
			let finished = false;
			function done(v) {
				if (finished) return;
				finished = true;
				document.removeEventListener('keydown', onKey);
				back.remove();
				resolve(v);
			}
			function onKey(e) { if (e.key === 'Escape') done(null); }
			cancel.addEventListener('click', () => done(null));
			back.addEventListener('mousedown', e => { if (e.target === back) done(null); });
			form.addEventListener('submit', async e => {
				e.preventDefault();
				if (!ok) return done(null);
				error.textContent = '';
				ok.disabled = true;
				try {
					const v = opts.onOk ? await opts.onOk() : true;
					done(v === undefined ? true : v);
				} catch (ex) {
					error.textContent = ex.message || String(ex);
				} finally {
					ok.disabled = false;
				}
			});
			document.addEventListener('keydown', onKey);
			document.body.appendChild(back);
			const first = form.querySelector('.body input, .body select, .body textarea') || ok || cancel;
			first.focus();
		});
	}

	function confirmDialog(title, text, okLabel) {
		return dialog(title, h('p', text), { okLabel: okLabel || 'OK' });
	}

	// ------------------------------------------------------------------ login and setup

	async function boot() {
		try {
			const s = await api('GET', '/session');
			Object.assign(state, { user: s.user, csrf: s.csrf, setupRequired: s.setupRequired, version: s.version, domains: s.domains || [] });
			if (s.authenticated) showShell(); else showLogin();
		} catch (e) {
			clear(document.getElementById('app')).appendChild(h('div.boot', 'Can\'t reach the server: ' + e.message));
		}
	}

	function showLogin() {
		stopTimers();
		const app = clear(document.getElementById('app'));
		const setup = state.setupRequired;
		const user = h('input', { type: 'text', autocomplete: 'username', required: true, placeholder: setup ? 'admin or admin@' + (state.domains[0] || 'example.com') : '' });
		const pw = h('input', { type: 'password', autocomplete: setup ? 'new-password' : 'current-password', required: true });
		const pw2 = h('input', { type: 'password', autocomplete: 'new-password', required: true });
		const err = h('div.error');
		const btn = h('button.primary', { type: 'submit' }, setup ? 'Create administrator' : 'Log in');
		const form = h('form.box',
			h('div.logo', h('img', { src: 'brand/parley-icon.svg', alt: '' }), h('div', h('b', 'Parley Mail'), h('span.muted', 'Administration'))),
			setup ? h('p.muted', 'No administrator exists yet. Create the first one: a mail user with the READ, WRITE and ADMIN permissions.') : null,
			h('div.form',
				h('label.row', h('span', 'User'), user),
				h('label.row', h('span', 'Password'), pw),
				setup ? h('label.row', h('span', 'Password again'), pw2) : null),
			err, btn,
			h('div.foot', h('span.faint.small', state.version), themePicker()));
		form.addEventListener('submit', async e => {
			e.preventDefault();
			err.textContent = '';
			if (setup && pw.value !== pw2.value) { err.textContent = 'The passwords are different'; return; }
			btn.disabled = true;
			try {
				const s = await api('POST', setup ? '/setup' : '/login', { user: user.value, password: pw.value });
				Object.assign(state, { user: s.user, csrf: s.csrf, setupRequired: s.setupRequired, domains: s.domains || [] });
				showShell();
			} catch (ex) {
				err.textContent = ex.message;
			} finally {
				btn.disabled = false;
			}
		});
		app.appendChild(h('div.login', form));
		user.focus();
	}

	// ------------------------------------------------------------------ the shell

	const PAGES = [
		{ id: 'dashboard', label: 'Dashboard', render: dashboardPage },
		{ sep: 'Mail' },
		{ id: 'domains', label: 'Domains', render: domainsPage },
		{ id: 'users', label: 'Users', render: usersPage },
		{ id: 'aliases', label: 'Aliases', render: aliasesPage },
		{ sep: 'SMTP' },
		{ id: 'queue', label: 'Queue', render: queuePage },
		{ id: 'history', label: 'History', render: historyPage },
		{ sep: 'System' },
		{ id: 'logs', label: 'Logs', render: logsPage },
		{ id: 'settings', label: 'Settings', render: settingsPage }
	];

	let shell = null;

	function showShell() {
		const app = clear(document.getElementById('app'));
		const nav = h('nav.nav', PAGES.map(p => p.sep ? h('div.sep', p.sep) : h('a', { href: '#/' + p.id, 'data-page': p.id }, p.label)));
		const title = h('h1');
		const banner = h('div.banner.hidden');
		const content = h('div.content');
		const logout = h('button.small', { onclick: async () => { try { await api('POST', '/logout'); } catch (e) { /* gone anyway */ } state.user = null; state.csrf = null; showLogin(); } }, 'Log out');
		app.appendChild(h('div.shell',
			h('aside.side',
				h('div.brand', h('img', { src: 'brand/parley-icon.svg', alt: '' }), h('div', h('b', 'Parley Mail'), h('span', 'Admin'))),
				nav,
				h('div.foot', themePicker(), h('div', 'Logged in as ', h('b', state.user)), logout)),
			h('main.main', h('div.topbar', title), h('div.banner-wrap', banner), content)));
		shell = { nav, title, banner, content };
		window.onhashchange = route;
		route();
		checkRestart();
		const t = setInterval(checkRestart, 10000);
		shell.restartTimer = t;
	}

	function themePicker() {
		const sel = h('select', { 'aria-label': 'Theme' }, THEMES.map(t => h('option', { value: t }, t)));
		sel.value = savedTheme();
		sel.addEventListener('change', () => applyTheme(sel.value));
		return h('label', 'Theme', sel);
	}

	/** The banner offering to restart services whose settings changed. */
	async function checkRestart() {
		if (!shell || !state.user) return;
		let list;
		try { list = await api('GET', '/services'); } catch (e) { return; }
		const need = list.filter(s => s.restartNeeded);
		const b = clear(shell.banner);
		b.classList.toggle('hidden', need.length === 0);
		if (need.length) {
			b.appendChild(h('span.grow', 'Changed settings take effect when these services restart: ', need.map(s => s.label).join(', ') + '.'));
			b.appendChild(h('button.primary.small', {
				onclick: async ev => {
					ev.target.disabled = true;
					try { await api('POST', '/services/all/restart-changed'); toast('Restarted', 'ok'); } catch (e) { fail(e); }
					checkRestart();
					if (state.page && state.page.refresh) state.page.refresh();
				}
			}, 'Restart them now'));
		}
	}

	function stopTimers() {
		for (const t of state.timers) clearInterval(t);
		state.timers = [];
		if (shell && shell.restartTimer && !state.user) clearInterval(shell.restartTimer);
	}

	function every(ms, fn) { state.timers.push(setInterval(fn, ms)); }

	function route() {
		if (!state.user) return showLogin();
		const id = (location.hash.replace(/^#\/?/, '') || 'dashboard').split('?')[0];
		const page = PAGES.find(p => p.id === id) || PAGES[0];
		for (const t of state.timers) clearInterval(t);
		state.timers = [];
		for (const a of shell.nav.querySelectorAll('a')) a.classList.toggle('active', a.dataset.page === page.id);
		shell.title.textContent = page.label;
		clear(shell.content);
		state.page = page.render(shell.content) || {};
	}

	function panel(title, tools, ...body) {
		return h('section.panel', h('header', h('h2', title), tools), h('div.body', body));
	}

	// ------------------------------------------------------------------ dashboard

	function stateBadge(s) {
		const cls = s.state === 'RUNNING' ? 'ok' : s.state === 'FAILED' ? 'bad' : '';
		return h('span.badge', { class: cls }, s.state === 'STOPPED' && !s.enabled ? 'off' : s.state.toLowerCase());
	}

	function dashboardPage(root) {
		const tiles = h('div.tiles');
		const cards = h('div.cards');
		const problems = h('div');
		const tools = h('div.toolbar',
			h('button', { onclick: () => all('start') }, 'Start all'),
			h('button', { onclick: () => all('stop') }, 'Stop all'));
		root.appendChild(tiles);
		root.appendChild(panel('Services', tools, cards));
		root.appendChild(panel('Recent warnings and errors', h('a', { href: '#/logs' }, 'Logs'), problems));

		async function all(action) {
			if (action === 'stop' && !await confirmDialog('Stop all services', 'Stop every mail service? Connected clients are disconnected.', 'Stop all')) return;
			try { await api('POST', '/services/all/' + action); toast(action === 'start' ? 'Started' : 'Stopped', 'ok'); } catch (e) { fail(e); }
			refresh();
		}

		async function act(s, action) {
			if (action === 'stop' && s.connections > 0
				&& !await confirmDialog('Stop ' + s.label, s.connections + ' client(s) are connected and will be disconnected.', 'Stop')) return;
			try { await api('POST', '/services/' + s.id + '/' + action); toast(s.label + ': ' + action + ' done', 'ok'); } catch (e) { fail(e); }
			refresh();
			checkRestart();
		}

		async function connections(s) {
			let list = [];
			try { list = await api('GET', '/services/' + s.id + '/connections'); } catch (e) { return fail(e); }
			dialog(s.label + ' connections', list.length === 0 ? h('div.empty', 'No connections') :
				h('table', h('thead', h('tr', h('th', 'Client'), h('th', 'User'), h('th', 'TLS'))),
					h('tbody', list.map(c => h('tr', h('td.mono', c.remote), h('td', c.user || h('span.faint', 'not logged in')), h('td', c.tls ? 'yes' : ''))))),
			{ okLabel: null });
		}

		async function refresh() {
			let st;
			try { st = await api('GET', '/status'); } catch (e) { return; }
			clear(tiles);
			const running = st.services.filter(s => s.state === 'RUNNING').length;
			const tile = (n, l, href) => h(href ? 'a.tile' : 'div.tile', href ? { href } : null, h('div.n', n), h('div.l', l));
			add(tiles, [
				tile(running + ' / ' + st.services.filter(s => s.enabled).length, 'Services running'),
				tile(st.domains.length, 'Domains', '#/domains'),
				tile(st.users, 'Users', '#/users'),
				tile(st.queue.running ? st.queue.messages : '–', 'Messages in queue', '#/queue'),
				tile(fmtBytes(st.heapUsed), 'Memory of ' + fmtBytes(st.heapMax)),
				tile(ago(st.startedAt), 'Up (Java ' + st.java + ')')
			]);
			clear(cards);
			for (const s of st.services) {
				const proto = s.protocol.toLowerCase();
				cards.appendChild(h('div.card', { class: s.state.toLowerCase() },
					h('div.top', h('img', { src: 'brand/margot-' + proto + '.svg', alt: '' }),
						h('div.grow', h('h3', s.label, ' ', stateBadge(s), ' ', s.restartNeeded ? h('span.badge.warn', 'restart') : null),
							h('div.desc', s.description))),
					h('dl.facts',
						h('dt', 'Port'), h('dd', s.port > 0 ? String(s.port) : 'off'),
						h('dt', 'TLS'), h('dd', s.secure ? (s.tls ? 'implicit' : h('span.warn-text', 'implicit, no key store')) : (s.tls ? 'STARTTLS offered' : 'no key store')),
						s.state === 'RUNNING' ? [h('dt', 'Up'), h('dd', ago(s.started))] : null,
						s.state === 'RUNNING' ? [h('dt', 'Connections'), h('dd', h('button.link', { type: 'button', onclick: () => connections(s) }, String(s.connections)))] : null),
					s.error ? h('div.err', s.error) : null,
					h('div.actions',
						s.state !== 'RUNNING' ? h('button.small', { disabled: !s.enabled, title: s.enabled ? '' : 'Port is 0 (Settings > Listeners)', onclick: () => act(s, 'start') }, 'Start') : null,
						s.state === 'RUNNING' ? h('button.small', { onclick: () => act(s, 'stop') }, 'Stop') : null,
						s.state === 'RUNNING' ? h('button.small', { class: s.restartNeeded ? 'primary' : '', onclick: () => act(s, 'restart') }, 'Restart') : null,
						h('a.btn.small', { href: '#/logs?source=' + s.protocol, onclick: () => { state.logSource = s.protocol; } }, 'Logs'))));
			}
			clear(problems);
			if (!st.problems.length) problems.appendChild(h('div.empty', 'Nothing to report'));
			for (const p of st.problems.slice().reverse()) {
				problems.appendChild(h('div.problem',
					h('span.badge', { class: p.level === 'ERROR' ? 'bad' : 'warn' }, p.level), ' ',
					h('span.faint', fmtTime(p.time) + ' ' + p.source + ' '), p.message));
			}
		}
		refresh();
		every(5000, refresh);
		return { refresh };
	}

	// ------------------------------------------------------------------ domains

	function domainsPage(root) {
		const list = h('div');
		const input = h('input.domain-input', { type: 'text', placeholder: 'example.com' });
		const addForm = h('form.toolbar', input, h('button.primary', { type: 'submit' }, 'Add domain'));
		addForm.addEventListener('submit', async e => {
			e.preventDefault();
			try {
				await api('POST', '/domains', { domain: input.value });
				toast(input.value + ' added. Restart the SMTP services to receive its mail.', 'ok');
				input.value = '';
				refresh();
				checkRestart();
			} catch (ex) { fail(ex); }
		});
		root.appendChild(h('p.muted', 'Mail for these domains is delivered here. Users and aliases can belong to one domain (tony@example.com) or, without a domain, to all of them.'));
		root.appendChild(panel('Local domains', addForm, list));

		async function refresh() {
			let domains;
			try { domains = await api('GET', '/domains'); } catch (e) { return fail(e); }
			state.domains = domains.map(d => d.domain);
			clear(list).appendChild(h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'Domain'), h('th', 'Users'), h('th', 'Aliases'), h('th', 'DKIM'), h('th', ''))),
				h('tbody', domains.map(d => h('tr',
					h('td', h('b', d.domain), d.unicode ? h('span.muted', ' (' + d.unicode + ')') : null),
					h('td', h('a', { href: '#/users', onclick: () => { state.userDomain = d.domain; } }, String(d.users))),
					h('td', String(d.aliases)),
					h('td', d.dkim ? h('span', h('span.badge.ok', d.dkim.selector), d.dkim.keyFileExists ? null : h('span.badge.bad', ' key file missing')) : h('span.badge.warn', 'no key')),
					h('td.actions',
						h('button.small', { onclick: () => dns(d) }, 'DNS records'),
						h('button.small', { onclick: () => dkim(d) }, d.dkim ? 'New DKIM key' : 'Make DKIM key'),
						h('button.small.danger', { onclick: () => remove(d) }, 'Remove'))))))));
		}

		function dns(d) {
			dialog('DNS records for ' + d.domain, [
				h('p.muted', 'Publish these at your DNS provider. The MX host must have a PTR record matching the SMTP host name. Start DMARC with p=none and tighten it once reports look right.'),
				h('table.dns', h('thead', h('tr', h('th', 'Type'), h('th', 'Name'), h('th', 'Value'), h('th', ''))),
					h('tbody', d.dns.map(r => h('tr', h('td', r.type), h('td', r.name), h('td', r.value),
						h('td', h('button.small', { type: 'button', onclick: () => navigator.clipboard.writeText(r.value).then(() => toast('Copied')) }, 'Copy'))))))
			], { okLabel: null, wide: true });
		}

		async function dkim(d) {
			const y = new Date().getFullYear();
			const sel = h('input', { type: 'text', value: 'mail' + y });
			const type = h('select', h('option', { value: 'rsa' }, 'RSA 2048 (works everywhere)'), h('option', { value: 'ed25519' }, 'Ed25519 (RFC 8463)'));
			const r = await dialog('DKIM key for ' + d.domain, [
				h('p.muted', 'Makes a signing key, saves it in the admin home\'s dkim folder and puts it in the DKIM keys setting.'
					+ (d.dkim ? ' It replaces the key with selector ' + d.dkim.selector + ': publish the new record before restarting SMTP.' : '')),
				h('div.form', h('label.row', h('span', 'Selector'), sel), h('label.row', h('span', 'Key type'), type))
			], { okLabel: 'Make key', onOk: () => api('POST', '/domains/' + enc(d.domain) + '/dkim', { selector: sel.value, type: type.value }) });
			if (r) {
				toast('Key made. Publish its TXT record, then restart the SMTP services.', 'ok');
				refresh();
				dns(r);
				checkRestart();
			}
		}

		async function remove(d) {
			const ok = await confirmDialog('Remove ' + d.domain, 'Mail for ' + d.domain + ' will be refused. Its ' + d.users + ' user(s) and ' + d.aliases
				+ ' alias(es) are kept, and its DKIM key file too.', 'Remove');
			if (!ok) return;
			try { await api('DELETE', '/domains/' + enc(d.domain)); toast('Removed ' + d.domain, 'ok'); refresh(); checkRestart(); } catch (e) { fail(e); }
		}

		refresh();
		return { refresh };
	}

	// ------------------------------------------------------------------ users

	const PERMS = ['READ', 'WRITE', 'ADMIN'];

	function domainSelect(selected, allowAll) {
		return h('select',
			allowAll ? h('option', { value: '*' }, 'All domains') : null,
			h('option', { value: '' }, allowAll ? 'No domain (every domain)' : '(no domain: every domain)'),
			state.domains.map(d => h('option', { value: d }, '@' + d)));
	}

	function permChecks(selected) {
		const boxes = PERMS.map(p => h('input', { type: 'checkbox', value: p, checked: selected.includes(p) }));
		const el = h('div.checks', PERMS.map((p, i) => h('label', boxes[i], p)));
		el.values = () => boxes.filter(b => b.checked).map(b => b.value);
		return el;
	}

	function usersPage(root) {
		const filter = domainSelect(null, true);
		filter.value = state.userDomain !== undefined ? state.userDomain : '*';
		state.userDomain = undefined;
		const search = h('input', { type: 'search', placeholder: 'Search' });
		const list = h('div');
		let users = [];
		filter.addEventListener('change', draw);
		search.addEventListener('input', draw);
		root.appendChild(h('p.muted', 'READ: log in and read mail (IMAP, POP3). WRITE: change mailboxes and send mail. ADMIN: use these pages. Changes take effect at once.'));
		root.appendChild(panel('Users', h('div.toolbar', filter, search, h('button.primary', { onclick: addUser }, 'Add user')), list));

		async function refresh() {
			try { users = await api('GET', '/users'); } catch (e) { return fail(e); }
			draw();
		}

		function draw() {
			const d = filter.value, q = search.value.trim().toLowerCase();
			const shown = users.filter(u => (d === '*' || (d === '' ? !u.domain : u.domain === d)) && (!q || u.name.toLowerCase().includes(q)));
			clear(list).appendChild(shown.length === 0 ? h('div.empty', 'No users') : h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'User'), h('th', 'Domain'), h('th', 'Permissions'), h('th', 'Maildrop'), h('th', 'Storage'), h('th', ''))),
				h('tbody', shown.map(u => {
					const usage = h('button.link', { type: 'button', onclick: () => showUsage(u, usage) }, 'show');
					return h('tr',
						h('td', h('b', u.name), u.hashed ? null : h('span.badge.warn', { title: 'The password is stored in plain text; set it again to hash it' }, ' plain')),
						h('td', u.domain || h('span.faint', 'every domain')),
						h('td', h('div.chips', u.permissions.map(p => h('span.badge', { class: p === 'ADMIN' ? 'gold' : '' }, p)))),
						h('td.mono', u.maildrop),
						h('td', usage),
						h('td.actions',
							h('button.small', { onclick: () => editUser(u) }, 'Edit'),
							h('button.small.danger', { onclick: () => deleteUser(u), disabled: u.name === state.user }, 'Delete')));
				})))));
		}

		async function showUsage(u, el) {
			try {
				const r = await api('GET', '/users/' + enc(u.name) + '/usage');
				el.replaceWith(h('span', r.exists ? fmtBytes(r.bytes) + ' in ' + r.files + ' messages' : h('span.faint', 'no mail yet')));
			} catch (e) { fail(e); }
		}

		async function addUser() {
			const local = h('input', { type: 'text', placeholder: 'tony', required: true });
			const dom = domainSelect(null, false);
			const dflt = filter.value !== '*' ? filter.value : (state.domains[0] || '');
			dom.value = dflt;
			const pw = h('input', { type: 'password', autocomplete: 'new-password', required: true, minlength: 8 });
			const pw2 = h('input', { type: 'password', autocomplete: 'new-password', required: true });
			const perms = permChecks(['READ', 'WRITE']);
			const drop = h('input', { type: 'text', placeholder: 'domain/user (default)' });
			const r = await dialog('Add user', h('div.form',
				h('div.row', h('span', 'User name'), h('div.inline', local, dom)),
				h('label.row', h('span', 'Password (at least 8 characters)'), pw),
				h('label.row', h('span', 'Password again'), pw2),
				h('div.row', h('span', 'Permissions'), perms),
				h('label.row', h('span', 'Maildrop (directory under the mail root; empty for the default)'), drop)), {
				okLabel: 'Add', onOk: async () => {
					if (pw.value !== pw2.value) throw new Error('The passwords are different');
					const name = local.value.trim() + (dom.value ? '@' + dom.value : '');
					return api('POST', '/users', { name, password: pw.value, permissions: perms.values(), maildrop: drop.value || null });
				}
			});
			if (r) { users = r; draw(); toast('User added', 'ok'); }
		}

		async function editUser(u) {
			const pw = h('input', { type: 'password', autocomplete: 'new-password', placeholder: 'unchanged' });
			const pw2 = h('input', { type: 'password', autocomplete: 'new-password' });
			const perms = permChecks(u.permissions);
			const drop = h('input', { type: 'text', value: u.maildrop });
			const r = await dialog('Edit ' + u.name, h('div.form',
				h('label.row', h('span', 'New password (empty: keep the current one)'), pw),
				h('label.row', h('span', 'New password again'), pw2),
				h('div.row', h('span', 'Permissions'), perms),
				h('label.row', h('span', 'Maildrop (empty: the default)'), drop),
				h('p.faint', 'Changing the maildrop doesn\'t move existing mail.')), {
				okLabel: 'Save', onOk: async () => {
					if (pw.value !== pw2.value) throw new Error('The passwords are different');
					const md = drop.value.trim() === u.maildrop ? null : drop.value.trim();
					return api('PUT', '/users/' + enc(u.name), { password: pw.value || null, permissions: perms.values(), maildrop: md });
				}
			});
			if (r) { users = r; draw(); toast('Saved', 'ok'); }
		}

		async function deleteUser(u) {
			if (!await confirmDialog('Delete ' + u.name, u.name + ' can no longer log in or receive mail. The mail in ' + u.maildrop + ' is kept.', 'Delete')) return;
			try { users = await api('DELETE', '/users/' + enc(u.name)); draw(); toast('Deleted', 'ok'); } catch (e) { fail(e); }
		}

		refresh();
		return { refresh };
	}

	// ------------------------------------------------------------------ aliases

	function aliasesPage(root) {
		const list = h('div');
		const note = h('div');
		root.appendChild(h('p.muted', 'An alias with a domain (sales@example.com) is for that domain; one without (postmaster) is for every local domain. Members are users or addresses; a member without @ takes the alias\'s domain. The SMTP services read the aliases when they start.'));
		root.appendChild(note);
		root.appendChild(panel('Aliases', h('button.primary', { onclick: () => edit(null) }, 'Add alias'), list));
		let data;

		async function refresh(d) {
			try { data = d || await api('GET', '/aliases'); } catch (e) { return fail(e); }
			clear(note);
			const a = data.aliases;
			clear(list).appendChild(a.length === 0 ? h('div.empty', 'No aliases') : h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'Alias'), h('th', 'Members'), h('th', ''))),
				h('tbody', a.map(x => h('tr', h('td', h('b', x.name), x.domain ? null : h('span.faint', ' (every domain)')),
					h('td', x.members.join(', ')),
					h('td.actions', h('button.small', { onclick: () => edit(x) }, 'Edit'), h('button.small.danger', { onclick: () => remove(x) }, 'Delete'))))))));
		}

		async function edit(x) {
			let local, dom;
			const at = x ? x.name.lastIndexOf('@') : -1;
			local = h('input', { type: 'text', value: x ? (at < 0 ? x.name : x.name.substring(0, at)) : '', disabled: !!x, required: true });
			dom = domainSelect(null, false);
			dom.value = x ? (at < 0 ? '' : x.name.substring(at + 1)) : (state.domains[0] || '');
			dom.disabled = !!x;
			const members = h('textarea', { placeholder: 'tony\njose@example.org' }, x ? x.members.join('\n') : '');
			const r = await dialog(x ? 'Edit ' + x.name : 'Add alias', h('div.form',
				h('div.row', h('span', 'Alias'), h('div.inline', local, dom)),
				h('label.row', h('span', 'Members (one per line or comma separated)'), members)), {
				okLabel: 'Save', onOk: () => {
					const name = x ? x.name : local.value.trim() + (dom.value ? '@' + dom.value : '');
					const m = members.value.split(/[\n,]/).map(s => s.trim()).filter(s => s);
					return api('PUT', '/aliases/' + enc(name), { members: m });
				}
			});
			if (r) { refresh(r); toast('Saved. Restart the SMTP services to use it.', 'ok'); checkRestart(); }
		}

		async function remove(x) {
			if (!await confirmDialog('Delete ' + x.name, 'Mail to ' + x.name + ' will be refused (unless a user has that name).', 'Delete')) return;
			try { refresh(await api('DELETE', '/aliases/' + enc(x.name))); checkRestart(); } catch (e) { fail(e); }
		}

		refresh();
		return { refresh };
	}

	// ------------------------------------------------------------------ SMTP queue

	function rcptBadge(status) {
		return h('span.badge', { class: status === 'DELIVERED' ? 'ok' : status === 'FAILED' ? 'bad' : status === 'EXPANDED' ? 'info' : 'warn' }, status.toLowerCase());
	}

	function queuePage(root) {
		const list = h('div');
		const auto = h('input', { type: 'checkbox', checked: true });
		root.appendChild(panel('Messages waiting for delivery', h('div.toolbar',
			h('label.checks', h('label', auto, 'Refresh every 5 s')),
			h('button', { onclick: refresh }, 'Refresh'),
			h('button.primary', { onclick: flush }, 'Retry all now')), list));

		async function refresh() {
			let q;
			try { q = await api('GET', '/queue'); } catch (e) { return fail(e); }
			clear(list);
			if (!q.running) list.appendChild(h('div.banner', 'The queue runs while an SMTP service runs; start one on the Dashboard.'));
			if (!q.entries.length) { list.appendChild(h('div.empty', 'The queue is empty')); return; }
			list.appendChild(h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'Queued'), h('th', 'From'), h('th', 'Recipients'), h('th', 'Size'), h('th', 'Next try'), h('th', ''))),
				h('tbody', q.entries.map(e => h('tr',
					h('td', h('div', ago(e.created) + ' ago'), h('div.mono.faint', e.id)),
					h('td', e.from || h('span.faint', '<> (bounce)'), e.submitter ? h('div.faint', 'by ' + e.submitter) : null),
					h('td', e.recipients.map(r => h('div.rcpt', rcptBadge(r.status), h('span', r.address),
						r.attempts ? h('span.faint', r.attempts + (r.attempts === 1 ? ' try' : ' tries')) : null,
						r.lastResult ? h('div.result', (r.lastStatus ? r.lastStatus + ' ' : '') + r.lastResult) : null))),
					h('td.nowrap', fmtBytes(e.size)),
					h('td', e.pending ? until(e.nextAttempt) : ''),
					h('td.actions',
						h('button.small', { onclick: () => view(e) }, 'View'),
						h('button.small', { onclick: () => retry(e) }, 'Retry'),
						h('button.small.danger', { onclick: () => remove(e) }, 'Delete'))))))));
		}

		async function flush() {
			try { await api('POST', '/queue/flush'); toast('Every message is being tried now', 'ok'); setTimeout(refresh, 1500); } catch (e) { fail(e); }
		}
		async function retry(e) {
			try { await api('POST', '/queue/' + enc(e.id) + '/retry'); toast('Trying ' + e.id + ' now', 'ok'); setTimeout(refresh, 1500); } catch (ex) { fail(ex); }
		}
		async function remove(e) {
			if (!await confirmDialog('Delete ' + e.id, 'The message is removed without being delivered, and the sender isn\'t told.', 'Delete')) return;
			try { await api('DELETE', '/queue/' + enc(e.id)); toast('Deleted', 'ok'); refresh(); } catch (ex) { fail(ex); }
		}
		async function view(e) {
			let text;
			try { text = await api('GET', '/queue/' + enc(e.id) + '/message'); } catch (ex) { return fail(ex); }
			dialog(e.id, h('pre.message', text), { okLabel: null, wide: true });
		}

		refresh();
		every(5000, () => { if (auto.checked) refresh(); });
		return { refresh };
	}

	// ------------------------------------------------------------------ SMTP history

	const EVENT_TYPES = ['QUEUED', 'DELIVERED', 'DEFERRED', 'FAILED', 'EXPANDED', 'COMPLETED', 'DELETED'];

	function eventBadge(t) {
		const cls = { DELIVERED: 'ok', COMPLETED: 'ok', FAILED: 'bad', DELETED: 'bad', DEFERRED: 'warn', EXPANDED: 'info', QUEUED: 'gold' }[t] || '';
		return h('span.badge', { class: cls }, t.toLowerCase());
	}

	function historyPage(root) {
		const from = h('input', { type: 'date', value: today() });
		const to = h('input', { type: 'date', value: today() });
		const type = h('select', h('option', { value: '' }, 'Every event'),
			h('option', { value: 'DELIVERED,FAILED,DEFERRED,EXPANDED' }, 'Delivery attempts'),
			EVENT_TYPES.map(t => h('option', { value: t }, t.charAt(0) + t.substring(1).toLowerCase())));
		const q = h('input', { type: 'search', placeholder: 'Address, id, status or text' });
		const list = h('div');
		const form = h('form.toolbar', h('span.muted', 'From'), from, h('span.muted', 'to'), to, type, q, h('button.primary', { type: 'submit' }, 'Search'));
		form.addEventListener('submit', e => { e.preventDefault(); refresh(); });
		root.appendChild(h('p.muted', 'Every message the SMTP queue took, each delivery attempt and its result, kept for the days set in Settings > Admin.'));
		root.appendChild(panel('History', null, form, list));

		async function refresh() {
			const params = new URLSearchParams({ from: from.value, to: to.value, q: q.value, type: type.value, limit: 1000 });
			let rows;
			try { rows = await api('GET', '/history?' + params); } catch (e) { return fail(e); }
			clear(list).appendChild(rows.length === 0 ? h('div.empty', 'Nothing found') : h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'Time'), h('th', 'Event'), h('th', 'Message'), h('th', 'From'), h('th', 'To'), h('th', 'Result'))),
				h('tbody', rows.map(r => h('tr',
					h('td.nowrap', fmtTime(r.time)),
					h('td', eventBadge(r.type)),
					h('td', h('button.link.mono', { type: 'button', onclick: () => timeline(r.id) }, r.id)),
					h('td', r.from || h('span.faint', '<>')),
					h('td', r.recipient || (r.recipients || []).join(', ')),
					h('td.result', r.status ? r.status + ' ' : '', r.result || '', r.remoteMta ? h('div.faint', 'via ' + r.remoteMta) : null)))))));
			if (rows.length === 1000) list.appendChild(h('p.faint', 'Showing the newest 1000; narrow the search to see more.'));
		}

		async function timeline(id) {
			let rows;
			try { rows = await api('GET', '/history/' + enc(id)); } catch (e) { return fail(e); }
			dialog('Message ' + id, h('table',
				h('thead', h('tr', h('th', 'Time'), h('th', 'Event'), h('th', 'Recipient'), h('th', 'Result'))),
				h('tbody', rows.map(r => h('tr', h('td', fmtTime(r.time)), h('td', eventBadge(r.type)),
					h('td', r.recipient || (r.recipients || []).join(', ')),
					h('td.result', r.status ? r.status + ' ' : '', r.result || '', r.attempts ? h('div.faint', 'attempt ' + r.attempts) : null))))),
			{ okLabel: null, wide: true });
		}

		refresh();
		return { refresh };
	}

	// ------------------------------------------------------------------ logs

	const SOURCES = ['SMTP', 'IMAP', 'POP3', 'MAIL', 'NET', 'ADMIN', 'OTHER'];
	const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];

	function logsPage(root) {
		const level = h('select', LEVELS.map(l => h('option', { value: l }, l === 'TRACE' ? 'All levels' : l + ' and up')));
		const source = h('select', h('option', { value: '' }, 'Every source'), SOURCES.map(s => h('option', { value: s }, s)));
		const fromHash = /source=(\w+)/.exec(location.hash);
		source.value = state.logSource || (fromHash ? fromHash[1] : '');
		state.logSource = undefined;
		const q = h('input', { type: 'search', placeholder: 'Filter' });
		const pause = h('button', 'Pause');
		const view = h('div.log');
		const levels = h('div.levels');
		let last = 0, paused = false, timer = null;

		pause.addEventListener('click', () => { paused = !paused; pause.textContent = paused ? 'Resume' : 'Pause'; });
		for (const el of [level, source]) el.addEventListener('change', reload);
		q.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(reload, 300); });

		root.appendChild(panel('Log', h('div.toolbar', level, source, q, pause,
			h('button', { onclick: async () => { try { await api('POST', '/logs/clear'); reload(); } catch (e) { fail(e); } } }, 'Clear'),
			h('a.btn', { href: 'api/logs/download' }, 'Download file')), view));
		root.appendChild(panel('Log levels', null, h('p.muted', 'How much each part of the suite logs. Kept in the settings; "default" leaves the servers\' own level (INFO).'), levels));

		function line(e) {
			return h('div.e', { class: e.level },
				h('span.t', fmtClock(e.time)), h('span.lv', e.level), h('span.src', e.source),
				h('span', e.message, ' ', h('span.lg', '(' + e.thread + ' ' + e.logger.replace(/^us\.bringardner\.parley\./, '') + ')')),
				e.error ? h('span.st', e.error) : null);
		}

		async function poll(reset) {
			if (paused && !reset) return;
			const params = new URLSearchParams({ after: reset ? 0 : last, level: level.value, source: source.value, q: q.value, limit: reset ? 1000 : 500 });
			let r;
			try { r = await api('GET', '/logs?' + params); } catch (e) { return; }
			if (reset) clear(view);
			const atBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 30;
			for (const e of r.entries) view.appendChild(line(e));
			while (view.childNodes.length > 3000) view.removeChild(view.firstChild);
			last = r.last;
			if (reset && !r.entries.length) view.appendChild(h('div.empty', 'No entries'));
			if (atBottom || reset) view.scrollTop = view.scrollHeight;
		}

		function reload() { last = 0; poll(true); }

		async function drawLevels() {
			let lv;
			try { lv = await api('GET', '/logs/levels'); } catch (e) { return; }
			clear(levels);
			for (const s of SOURCES.filter(x => x !== 'OTHER')) {
				const sel = h('select', h('option', { value: '' }, 'default'), ['ERROR', 'WARN', 'INFO', 'DEBUG'].map(l => h('option', { value: l }, l)));
				sel.value = lv[s] || '';
				sel.addEventListener('change', async () => {
					try { await api('PUT', '/logs/levels', { source: s, level: sel.value }); toast(s + ' logs at ' + (sel.value || 'the default level'), 'ok'); } catch (e) { fail(e); }
				});
				levels.appendChild(h('label', s, sel));
			}
		}

		reload();
		drawLevels();
		every(2000, () => poll(false));
		return { refresh: reload };
	}

	// ------------------------------------------------------------------ settings

	function settingsPage(root) {
		const tabs = h('div.tabs');
		const body = h('div');
		const save = h('button.primary', 'Save');
		const discard = h('button', 'Discard');
		const count = h('span.grow');
		const bar = h('div.savebar.hidden', count, discard, save);
		const where = h('p.muted');
		root.appendChild(where);
		root.appendChild(h('section.panel', h('div.tabs-wrap', tabs), h('div.body', body)));
		root.appendChild(bar);

		let data = null, current = null;
		const changes = new Map();

		save.addEventListener('click', doSave);
		discard.addEventListener('click', () => { changes.clear(); draw(); });

		async function load() {
			try { data = await api('GET', '/settings'); } catch (e) { return fail(e); }
			where.textContent = 'Saved in ' + data.file + '. Each tab says which services need a restart after a change.';
			if (!current) current = data.groups[0].id;
			draw();
		}

		function groupsWithOther() {
			return data.groups.concat([{ id: 'other', label: 'Other', description: 'Settings that aren\'t on the other tabs: any parley.* system property from the Parley READMEs.', settings: data.other }]);
		}

		function draw() {
			clear(tabs);
			for (const g of groupsWithOther()) {
				const dirty = g.settings.some(s => changes.has(s.key)) || (g.id === 'other' && [...changes.keys()].some(k => !known(k)));
				tabs.appendChild(h('button', { class: g.id === current ? 'active' : '', onclick: () => { current = g.id; draw(); } }, g.label, dirty ? h('span.dot') : null));
			}
			const g = groupsWithOther().find(x => x.id === current);
			clear(body);
			const common = commonServices(g.settings);
			body.appendChild(h('p.muted', g.description, common ? ' ' + restartText(common) : ''));
			for (const s of g.settings) body.appendChild(row(s, common));
			if (g.id === 'other') body.appendChild(otherAdder());
			count.textContent = changes.size ? changes.size + ' unsaved change' + (changes.size > 1 ? 's' : '') : '';
			bar.classList.toggle('hidden', changes.size === 0);
		}

		function known(key) { return data.groups.some(g => g.settings.some(s => s.key === key)) || data.other.some(s => s.key === key); }

		/** The services most of a group's settings restart, as a key ("smtp,submission"), or null. */
		function commonServices(list) {
			const counts = new Map();
			for (const s of list) {
				const k = (s.services || []).join(',');
				counts.set(k, (counts.get(k) || 0) + 1);
			}
			let best = null, n = 0;
			for (const [k, c] of counts) if (c > n) { best = k; n = c; }
			return n > 1 ? best : null;
		}

		function restartText(key) {
			if (!key) return 'Changes need no restart.';
			const ids = key.split(',');
			return ids.length === 7 ? 'Changes need every service restarted.' : 'Changes need a restart of: ' + ids.join(', ') + '.';
		}

		function row(s, common) {
			const has = changes.has(s.key);
			const val = has ? changes.get(s.key) : s.value;
			let input;
			const set = v => {
				const orig = s.type === 'SECRET' ? undefined : (s.value === undefined ? null : s.value);
				if (s.type !== 'SECRET' && (v === orig || (v === '' && orig === null))) changes.delete(s.key); else changes.set(s.key, v);
				r.classList.toggle('dirty', changes.has(s.key));
				count.textContent = changes.size ? changes.size + ' unsaved change' + (changes.size > 1 ? 's' : '') : '';
				bar.classList.toggle('hidden', changes.size === 0);
			};
			switch (s.type) {
				case 'BOOLEAN':
					input = h('select', h('option', { value: '' }, 'default (' + (s.default || 'false') + ')'), h('option', { value: 'true' }, 'true'), h('option', { value: 'false' }, 'false'));
					input.value = val || '';
					input.addEventListener('change', () => set(input.value || null));
					break;
				case 'CHOICE':
					input = h('select', h('option', { value: '' }, 'default (' + (s.default || '') + ')'), (s.options || []).map(o => h('option', { value: o }, o)));
					input.value = val || '';
					input.addEventListener('change', () => set(input.value || null));
					break;
				case 'SECRET': {
					input = h('input', { type: 'password', autocomplete: 'new-password', placeholder: s.set ? '(set; type to change)' : '(not set)' });
					if (has && changes.get(s.key)) input.value = changes.get(s.key);
					input.addEventListener('input', () => { if (input.value) changes.set(s.key, input.value); else changes.delete(s.key); set(input.value || undefined); });
					const clr = s.set ? h('label.checks', h('label', h('input', { type: 'checkbox', checked: has && changes.get(s.key) === null, onchange: e => { if (e.target.checked) { input.value = ''; changes.set(s.key, null); } else changes.delete(s.key); set(e.target.checked ? null : undefined); } }), 'Remove it')) : null;
					input = h('div', input, clr);
					break;
				}
				default:
					input = h('input', { type: 'text', value: val || '', placeholder: s.default ? 'default: ' + s.default : '', spellcheck: 'false' });
					input.addEventListener('input', () => set(input.value.trim() === '' ? null : input.value));
			}
			const r = h('div.setting', { class: has ? 'dirty' : '' },
				h('div.name', h('b', s.label || s.key), h('code', s.key)),
				h('div.edit', input,
					s.description ? h('div.hint', s.description) : null,
					s.effective !== undefined ? h('div.hint.warn-text', 'Set outside the file (-D) to: ' + s.effective) : null,
					s.services && s.services.join(',') !== common ? h('div.hint', restartText(s.services.join(','))) : null,
					!s.label ? h('div', h('button.small.danger', { onclick: () => { changes.set(s.key, null); r.classList.add('dirty'); set(null); } }, 'Remove')) : null));
			return r;
		}

		function otherAdder() {
			const k = h('input', { type: 'text', placeholder: 'parley.mail.smtp.something', spellcheck: 'false' });
			const v = h('input', { type: 'text', placeholder: 'value', spellcheck: 'false' });
			const f = h('form.toolbar.spaced', k, v, h('button', { type: 'submit' }, 'Add'));
			f.addEventListener('submit', e => {
				e.preventDefault();
				if (!k.value.trim()) return;
				changes.set(k.value.trim(), v.value);
				data.other.push({ key: k.value.trim(), value: null, type: 'TEXT', services: [] });
				draw();
			});
			return f;
		}

		async function doSave() {
			const obj = {};
			for (const [k, v] of changes) if (v !== undefined) obj[k] = v;
			save.disabled = true;
			try {
				const r = await api('PUT', '/settings', { changes: obj });
				changes.clear();
				await load();
				toast(r.changed.length ? 'Saved ' + r.changed.length + ' setting(s)' : 'Nothing changed', 'ok');
				checkRestart();
				if (r.restartNeeded.length) {
					const ok = await confirmDialog('Restart services', 'These services use the changed settings after a restart: ' + r.restartNeeded.join(', ') + '. Restart them now? Their clients are disconnected.', 'Restart now');
					if (ok) {
						try { await api('POST', '/services/all/restart-changed'); toast('Restarted', 'ok'); } catch (e) { fail(e); }
						checkRestart();
					}
				}
			} catch (e) {
				fail(e);
			} finally {
				save.disabled = false;
			}
		}

		load();
		return { refresh: load };
	}

	boot();
})();
