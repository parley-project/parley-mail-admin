/*
 * Parley Mail Admin: the pages. Plain JavaScript, no libraries; it talks to the JSON API under
 * api/ (ApiServlet). Every text from the server is put in the page with textContent, never as HTML.
 */
(function () {
	'use strict';

	const state = { user: null, csrf: null, primary: false, adminDomain: null, setupRequired: false, version: '', domains: [], timers: [], page: null };

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
			Object.assign(state, { user: s.user, csrf: s.csrf, primary: !!s.primary, adminDomain: s.adminDomain || null, setupRequired: s.setupRequired, version: s.version, domains: s.domains || [] });
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
			setup ? h('p.muted', 'No administrator exists yet. Create the first one: a mail user with the READ, WRITE and ADMIN permissions. A name like admin@example.com also makes example.com a local domain.') : null,
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
				Object.assign(state, { user: s.user, csrf: s.csrf, primary: !!s.primary, adminDomain: s.adminDomain || null, setupRequired: s.setupRequired, domains: s.domains || [] });
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
		{ id: 'dashboard', label: 'Dashboard', render: dashboardPage, primaryOnly: true },
		{ sep: 'Mail' },
		{ id: 'domains', label: 'Domains', render: domainsPage },
		{ id: 'users', label: 'Users', render: usersPage },
		{ id: 'aliases', label: 'Aliases', render: aliasesPage },
		{ id: 'dns', label: 'DNS', render: dnsPage, module: 'dns' },
		{ sep: 'SMTP' },
		{ id: 'queue', label: 'Queue', render: queuePage },
		{ id: 'history', label: 'History', render: historyPage },
		{ sep: 'System', primaryOnly: true },
		{ id: 'servers', label: 'Servers', render: serversPage, primaryOnly: true },
		{ id: 'logs', label: 'Logs', render: logsPage, primaryOnly: true },
		{ id: 'settings', label: 'Settings', render: settingsPage, primaryOnly: true }
	];

	/** The pages this administrator may use: an administrator of a domain other than the primary one manages only that domain. */
	function pages() { return PAGES.filter(p => (state.primary || !p.primaryOnly) && (!p.module || (state.modules || []).includes(p.module))); }

	let shell = null;

	/** The type-specific pages that apply: a page appears where the launchers run its kind of server. */
	async function loadModules() {
		try { state.modules = (await api('GET', '/modules')).modules || []; } catch (e) { state.modules = state.modules || []; }
	}

	async function showShell() {
		await loadModules();
		const app = clear(document.getElementById('app'));
		const nav = h('nav.nav', pages().map(p => p.sep ? h('div.sep', p.sep) : h('a', { href: '#/' + p.id, 'data-page': p.id }, p.label)));
		const title = h('h1');
		const banner = h('div.banner.hidden');
		const content = h('div.content');
		const logout = h('button.small', { onclick: async () => { try { await api('POST', '/logout'); } catch (e) { /* gone anyway */ } state.user = null; state.csrf = null; showLogin(); } }, 'Log out');
		app.appendChild(h('div.shell',
			h('aside.side',
				h('div.brand', h('img', { src: 'brand/parley-icon.svg', alt: '' }), h('div', h('b', 'Parley Mail'), h('span', 'Admin'))),
				nav),
			h('main.main',
				h('div.topbar', title, h('div.session', themePicker(), h('span.who', 'Logged in as ', h('b', state.user)), logout)),
				h('div.banner-wrap', banner), content)));
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
		if (!shell || !state.user || !state.primary) return;
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
		const visible = pages().filter(p => !p.sep);
		const id = (location.hash.replace(/^#\/?/, '') || visible[0].id).split('?')[0];
		const page = visible.find(p => p.id === id) || visible[0];
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
		const cls = s.state === 'RUNNING' ? 'ok' : s.state === 'FAILED' ? 'bad' : s.state === 'UNAVAILABLE' ? 'warn' : '';
		return h('span.badge', { class: cls }, (s.state === 'STOPPED' && !s.enabled) || s.state === 'OFF' ? 'off' : s.state.toLowerCase());
	}

	function dashboardPage(root) {
		const tiles = h('div.tiles');
		const cards = h('div.cards');
		const problems = h('div');
		const where = h('div');
		const tools = h('div.toolbar',
			h('button', { onclick: () => all('start') }, 'Start all'),
			h('button', { onclick: () => all('stop') }, 'Stop all'));
		root.appendChild(tiles);
		root.appendChild(where);
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
			clear(where);
			where.appendChild(h('p.muted', st.local
				? 'The servers run in this application, with the settings in ' + st.settingsDirectory + '.'
				: 'The servers run as services, managed over JMX at ' + st.launchers.map(l => l.where).join(', ') + '. Their settings are in ' + st.settingsDirectory + '.'));
			if (st.connectError) where.appendChild(h('div.banner', st.connectError));
			for (const [id, what] of [['store', 'The mail store'], ['queue', 'The SMTP queue']]) {
				const a = st.assets && st.assets[id];
				if (a && !a.exists) where.appendChild(h('div.banner', a.path ? what + ' has no settings (' + a.path + '), so the servers refuse to start with it. ' : what + '\'s settings can\'t be found: ' + (a.error || '') + '. ',
					h('a', { href: '#/settings?tab=' + id }, 'Settings > ' + (id === 'store' ? 'Store' : 'Queue'))));
			}
			if (!st.hostname) where.appendChild(h('div.banner', 'The host name isn\'t set, so the SMTP servers refuse to start. ', h('a', { href: '#/settings?tab=smtp' }, 'Settings > SMTP')));
			for (const l of st.launchers) {
				if (l.warning) where.appendChild(h('div.banner', l.where + ': ' + l.warning));
				if (l.error) where.appendChild(h('div.banner', l.where + ': ' + l.error));
			}
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
						s.state === 'RUNNING' ? [h('dt', 'Connections'), h('dd', h('button.link', { type: 'button', onclick: () => connections(s) }, String(s.connections)))] : null,
						s.where && !st.local ? [h('dt', 'Runs at'), h('dd.mono', s.where)] : null),
					s.error ? h('div.err', s.error) : null,
					h('div.actions',
						s.state !== 'RUNNING' ? h('button.small', { disabled: !s.enabled || s.state === 'UNAVAILABLE', title: !s.enabled ? 'Port is 0 (Settings > Listeners)' : s.state === 'UNAVAILABLE' ? 'No launcher reached runs it' : '', onclick: () => act(s, 'start') }, 'Start') : null,
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
				toast(input.value + ' added. Its mail is received now.', 'ok');
				input.value = '';
				refresh();
				checkRestart();
			} catch (ex) { fail(ex); refresh(); checkRestart(); }
		});
		root.appendChild(h('p.muted', 'Mail for these domains is delivered here. Users and aliases can belong to one domain (tony@example.com) or, without a domain, to all of them.'));
		root.appendChild(state.primary ? panel('Local domains', addForm, list) : panel('Your domain', list));

		async function refresh() {
			let domains;
			try { domains = await api('GET', '/domains'); } catch (e) { return fail(e); }
			state.domains = domains.map(d => d.domain);
			clear(list).appendChild(h('div.table-wrap', h('table',
				h('thead', h('tr', h('th', 'Domain'), h('th', 'Users'), h('th', 'Aliases'), h('th', 'DKIM'), h('th', 'Spam filter'), h('th', ''))),
				h('tbody', domains.map(d => h('tr',
					h('td', h('b', d.domain), d.unicode ? h('span.muted', ' (' + d.unicode + ')') : null),
					h('td', h('a', { href: '#/users', onclick: () => { state.userDomain = d.domain; } }, String(d.users))),
					h('td', String(d.aliases)),
					h('td', d.dkim ? h('span', h('span.badge.ok', d.dkim.selector), d.dkim.own ? null : h('span.muted', ' shared (' + d.dkim.domain + ')'),
						d.dkim.keyFileExists ? null : h('span.badge.bad', ' key file missing')) : h('span.badge.warn', 'no key')),
					h('td', d.spam.effective === 'off' ? h('span.badge', 'off') : h('span.badge.ok', d.spam.effective),
						d.spam.override === 'default' ? h('span.muted', ' (default)') : null),
					h('td.actions',
						h('button.small', { onclick: () => dns(d) }, 'DNS records'),
						h('button.small', { onclick: () => spam(d) }, 'Spam filter'),
						h('button.small', { onclick: () => dkim(d) }, d.dkim && d.dkim.own ? 'New DKIM key' : 'Make DKIM key'),
						state.primary ? h('button.small.danger', { onclick: () => remove(d) }, 'Remove') : null)))))));
		}

		function dns(d) {
			const lines = d.dns.map(r => r.name + '\tIN\t' + r.type + '\t' + zoneValue(r)).join('\n') + '\n';
			dialog('DNS records for ' + d.domain, [
				d.dnsServed
					? h('p.muted', 'Parley DNS serves ' + d.domain + ': it answers from this domain\'s zone, or from the shared zone if it has no zone file, '
						+ 'so there is nothing to publish (its records are on the DNS page). Use these only for a DNS provider elsewhere, '
						+ 'if the domain\'s name servers are there.')
					: h('p.muted', 'Publish these at your DNS provider. The MX host must have a PTR record matching the SMTP host name. Start DMARC with p=none and tighten it once reports look right.'),
				h('p', h('button.small', { type: 'button', onclick: () => navigator.clipboard.writeText(lines).then(() => toast('Copied ' + d.dns.length + ' records')) }, 'Copy all records')),
				h('table.dns', h('thead', h('tr', h('th', 'Type'), h('th', 'Name'), h('th', 'Value'), h('th', ''))),
					h('tbody', d.dns.map(r => h('tr', h('td', r.type), h('td', r.name), h('td', r.value),
						h('td', h('button.small', { type: 'button', onclick: () => navigator.clipboard.writeText(r.value).then(() => toast('Copied')) }, 'Copy'))))))
			], { okLabel: null, wide: true });
		}

		/** A record's value as a zone file writes it: a TXT value quoted, in strings of at most 255 characters */
		function zoneValue(r) {
			if (r.type !== 'TXT') return r.value;
			const parts = [];
			for (let i = 0; i < r.value.length; i += 255) {
				parts.push('"' + r.value.substring(i, i + 255).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"');
			}
			return parts.join(' ');
		}

		async function spam(d) {
			const choice = h('select',
				h('option', { value: 'default' }, 'Use the default for all domains'),
				h('option', { value: 'off' }, 'Off: don\'t check ' + d.domain + '\'s mail'),
				d.spam.filters.map(f => h('option', { value: f.id }, f.label)));
			choice.value = d.spam.override;
			const r = await dialog('Spam filter for ' + d.domain, [
				h('p.muted', 'Mail from other servers to ' + d.domain + ' is checked by this filter. The default is the Spam tab of Settings (a filter and on or off for every domain); '
					+ 'a choice here replaces it for this domain only, even when checking is off for the others. A filter that is down never stops mail.'),
				h('div.form', h('label.row', h('span', 'Filter'), choice))
			], { okLabel: 'Save', onOk: () => api('POST', '/domains/' + enc(d.domain) + '/spam', { filter: choice.value }) });
			if (r) {
				toast('Spam filter of ' + d.domain + ' saved', 'ok');
				refresh();
				checkRestart();
			}
		}

		async function dkim(d) {
			const y = new Date().getFullYear();
			const sel = h('input', { type: 'text', value: 'mail' + y });
			const type = h('select', h('option', { value: 'rsa' }, 'RSA 2048 (works everywhere)'), h('option', { value: 'ed25519' }, 'Ed25519 (RFC 8463)'));
			const r = await dialog('DKIM key for ' + d.domain, [
				h('p.muted', (d.primaryDomain
					? 'Makes the primary domain\'s signing key: it is the shared key, which every domain without a key of its own signs with (with Parley DNS, each gets its record from the shared zone).'
					: 'Makes a signing key of ' + d.domain + '\'s own: its mail is signed with it instead of the shared key, and its record goes in its own zone, which then has a zone file.')
					+ ' The key is saved in the admin home\'s dkim folder and put in the DKIM keys setting.'
					+ (d.dkim && d.dkim.own ? ' It replaces the key with selector ' + d.dkim.selector + ' and mail is signed with it at once: publish the new record first if another DNS provider serves the domain.' : '')),
				h('div.form', h('label.row', h('span', 'Selector'), sel), h('label.row', h('span', 'Key type'), type))
			], { okLabel: 'Make key', onOk: () => api('POST', '/domains/' + enc(d.domain) + '/dkim', { selector: sel.value, type: type.value }) });
			if (r) {
				toast(r.dnsServed ? 'Key made. Parley DNS serves its record and mail is signed with it now.'
					: 'Key made and mail is signed with it now. Publish its TXT record.', 'ok');
				refresh();
				dns(r);
				checkRestart();
			}
		}

		async function remove(d) {
			const ok = await confirmDialog('Remove ' + d.domain, 'Mail for ' + d.domain + ' will be refused. Its ' + d.users + ' user(s) and ' + d.aliases
				+ ' alias(es) are kept, and its DKIM key file too.', 'Remove');
			if (!ok) return;
			try { await api('DELETE', '/domains/' + enc(d.domain)); toast('Removed ' + d.domain, 'ok'); refresh(); checkRestart(); } catch (e) { fail(e); refresh(); checkRestart(); }
		}

		refresh();
		return { refresh };
	}

	// ------------------------------------------------------------------ servers (any kind)

	/** The pages of the kinds of server this application knows, by the bean type a server has. */
	const TYPE_PAGES = { Dns: '#/dns', MailQueue: '#/queue' };

	function serversPage(root) {
		const list = h('div');
		root.appendChild(h('p.muted', 'Every Parley server the launchers run, whatever its kind: the mail servers, DNS, FTP, SSH, and any other. '
			+ 'Start, stop or restart one here; its kind\'s own page (if this application has one) is linked.'));
		root.appendChild(list);

		async function act(s, action) {
			if (action === 'stop' && s.connections > 0
				&& !await confirmDialog('Stop ' + s.id, s.connections + ' client(s) are connected and will be disconnected.', 'Stop')) return;
			try { await api('POST', '/servers/' + enc(s.id) + '/' + action); toast(s.id + ': ' + action + ' done', 'ok'); } catch (e) { fail(e); }
			refresh();
			checkRestart();
		}

		async function connections(s) {
			let l = [];
			try { l = await api('GET', '/servers/' + enc(s.id) + '/connections'); } catch (e) { return fail(e); }
			dialog(s.id + ' connections', l.length === 0 ? h('div.empty', 'No connections') :
				h('table', h('thead', h('tr', h('th', 'Client'), h('th', 'User'), h('th', 'TLS'))),
					h('tbody', l.map(c => h('tr', h('td.mono', c.remote), h('td', c.user || h('span.faint', 'not logged in')), h('td', c.tls ? 'yes' : ''))))),
				{ okLabel: null });
		}

		async function refresh() {
			let servers;
			try { servers = await api('GET', '/servers'); } catch (e) { return; }
			clear(list);
			const groups = new Map();
			for (const s of servers) {
				const k = s.where + (s.launcher ? ' (' + s.launcher + ')' : '');
				if (!groups.has(k)) groups.set(k, []);
				groups.get(k).push(s);
			}
			if (!servers.length) list.appendChild(h('div.empty', 'No launcher reached runs a server'));
			for (const [where, group] of groups) {
				const cards = h('div.cards');
				for (const s of group) {
					if (!s.id) { cards.appendChild(h('div.banner', s.error || 'Can\'t be read')); continue; }
					cards.appendChild(h('div.card', { class: String(s.state).toLowerCase() },
						h('div.top', h('div.grow',
							h('h3', s.id, ' ', stateBadge({ state: s.state, enabled: true }), ' ', s.restartNeeded ? h('span.badge.warn', 'restart') : null),
							h('div.desc', s.description || ''))),
						h('dl.facts',
							h('dt', 'Port'), h('dd', s.port > 0 ? String(s.port) : 'off'),
							s.state === 'RUNNING' ? [h('dt', 'Up'), h('dd', ago(s.started))] : null,
							s.state === 'RUNNING' ? [h('dt', 'Connections'), h('dd', h('button.link', { type: 'button', onclick: () => connections(s) }, String(s.connections)))] : null,
							s.types && s.types.length ? [h('dt', 'Pages'), h('dd', s.types.map(t => TYPE_PAGES[t] ? h('a', { href: TYPE_PAGES[t] }, t + ' ') : h('span.faint', t + ' ')))] : null),
						s.error ? h('div.err', s.error) : null,
						h('div.actions',
							s.state !== 'RUNNING' ? h('button.small', { disabled: s.state === 'OFF', title: s.state === 'OFF' ? 'Turned off by its settings' : '', onclick: () => act(s, 'start') }, 'Start') : null,
							s.state === 'RUNNING' ? h('button.small', { onclick: () => act(s, 'stop') }, 'Stop') : null,
							s.state === 'RUNNING' ? h('button.small', { class: s.restartNeeded ? 'primary' : '', onclick: () => act(s, 'restart') }, 'Restart') : null)));
				}
				list.appendChild(panel(where, null, cards));
			}
		}
		refresh();
		every(5000, refresh);
		return { refresh };
	}

	// ------------------------------------------------------------------ DNS

	function relName(name, domain) {
		const n = String(name).toLowerCase().replace(/\.+$/, ''), d = String(domain).toLowerCase().replace(/\.+$/, '');
		if (n === d) return '@';
		return n.endsWith('.' + d) ? n.substring(0, n.length - d.length - 1) : n + '.';
	}

	function dnsPage(root) {
		const SHARED = '\u0000shared';
		const status = h('div');
		const tabs = h('div.tabs');
		const body = h('div');
		root.appendChild(h('p.muted', 'A domain with no zone file is served from the shared zone; a domain\'s own records add to the shared zone\'s '
			+ 'or override them, by name and type. Changes are made on the DNS server at once.'));
		root.appendChild(status);
		root.appendChild(tabs);
		root.appendChild(body);
		let current = state.dnsDomain || null;
		let zone = null;
		let st = null;

		async function load() {
			let domains = [];
			try { domains = (await api('GET', '/domains')).map(d => d.domain); } catch (e) { return fail(e); }
			st = null;
			if (state.primary) {
				try { st = await api('GET', '/dns'); drawStatus(st); } catch (e) { clear(status).appendChild(h('div.banner', e.message)); }
			}
			const first = state.adminDomain && domains.includes(state.adminDomain) ? [state.adminDomain] : [];
			const order = first.concat(domains.filter(d => d !== state.adminDomain).sort());
			if (st && st.sharedZone) order.push(SHARED);
			clear(tabs);
			for (const d of order) {
				tabs.appendChild(h('button.tab', { type: 'button', 'data-domain': d, onclick: () => show(d) }, d === SHARED ? 'Shared zone' : d));
			}
			if (!order.length) { clear(body).appendChild(h('div.empty', 'No domains')); return; }
			show(order.includes(current) ? current : order[0]);
		}

		function drawStatus(s) {
			clear(status);
			if (!s.available) { status.appendChild(h('div.banner', 'No Parley DNS server is running where the servers are managed.')); return; }
			const reload = h('button.small', { onclick: async () => { try { st = await api('POST', '/dns/reload'); drawStatus(st); toast('Zones read again', 'ok'); if (current) show(current); } catch (e) { fail(e); } } }, 'Reload zones');
			status.appendChild(panel('DNS server', reload,
				h('dl.facts',
					h('dt', 'State'), h('dd', stateBadge({ state: s.state, enabled: true })),
					h('dt', 'Runs at'), h('dd.mono', s.where + ', port ' + s.port),
					h('dt', 'Recursion'), h('dd', s.recursion ? h('span.warn-text', 'on (it answers for any name)') : 'off'),
					h('dt', 'Zones'), h('dd', String(s.zones.length)),
					h('dt', 'Zone folder'), h('dd.mono', s.zoneDirectory || ''),
					s.sharedZone ? [h('dt', 'Shared zone'), h('dd.mono', s.sharedZone)] : null),
				s.keyProblem ? h('div.banner', s.keyProblem) : null,
				(s.errors || []).map(e => h('div.err', e))));
		}

		const shared = () => current === SHARED;
		const asShared = m => Object.assign(m, { shared: true, exists: true, domain: 'the shared zone' });

		async function show(d) {
			current = d;
			state.dnsDomain = d;
			for (const b of tabs.querySelectorAll('button')) b.classList.toggle('active', b.dataset.domain === d);
			try { zone = shared() ? asShared(await api('GET', '/dns/shared')) : await api('GET', '/dns/zones/' + enc(d)); }
			catch (e) { clear(body).appendChild(h('div.banner', e.message)); return; }
			draw();
		}

		/** The path of a record call: add, change or delete, for the shared zone or the current domain */
		function path(op) {
			if (shared()) return '/dns/shared/' + op;
			return '/dns/zones/' + enc(current) + (op === 'delete' ? '/delete' : '/records/' + op);
		}

		async function call(method, p, data, done) {
			try {
				const r = await api(method, p, data);
				zone = shared() ? asShared(r) : r;
				if (done) toast(done, 'ok');
				draw();
				return true;
			} catch (e) { fail(e); return false; }
		}

		/** A record's name as the table shows it: relative to the domain (the shared zone's are already) */
		const nameOf = r => zone.shared ? r.name : relName(r.name, zone.domain);
		const typeNames = () => (zone.types || []).map(t => t.name);

		function draw() {
			const z = zone;
			clear(body);
			if (!z.exists) {
				body.appendChild(panel(z.domain, null,
					h('p', z.isPrimary
						? 'The primary domain has no zone yet. Its zone (' + z.domain + '.txt in the DNS server\'s zone folder) is made by hand.'
						: 'The DNS server has no zone for ' + z.domain + '. Make one: it serves every record of the shared zone under ' + z.domain + ', and you add or override records here.'),
					!z.isPrimary ? h('button.primary', { onclick: () => call('POST', '/dns/zones/' + enc(current), null, 'Zone made') }, 'Make the zone') : null));
				return;
			}
			body.appendChild(h('p.muted', z.shared
				? 'Every listed domain with no zone file serves these records under its own name, and every zone with $INHERIT inherits them. '
					+ 'Names are relative to each domain: @ is the domain, www is www.<domain>; a name ending in a dot is the same for every domain. '
					+ 'Each change raises the serial and is served at once; the file before it is kept as ' + z.file + '.previous.'
				: z.inheritFrom
					? 'Inherits the records of ' + (z.inheritFrom === 'shared' ? 'the shared zone' : z.inheritFrom) + '. Serial ' + z.serial + ' (own ' + z.ownSerial + '), ' + z.ownRecords + ' own record(s) of ' + z.records + '.'
					: 'Serial ' + z.serial + '.'));
			if (z.mail && z.mail.length) {
				const needed = z.mail.filter(m => m.status !== 'served').length;
				const badge = s => h('span.badge', { class: s === 'served' ? 'ok' : s === 'different' ? 'warn' : 'bad' }, s);
				body.appendChild(panel('Mail records', h('button.primary.small', { disabled: needed === 0, onclick: publish }, needed ? 'Publish ' + needed : 'All served'),
					h('div.table-wrap', h('table.dns',
						h('thead', h('tr', h('th', 'Type'), h('th', 'Name'), h('th', 'Value'), h('th', ''))),
						h('tbody', z.mail.map(m => h('tr', h('td', m.type), h('td.mono', relName(m.name, z.domain)), h('td.mono', m.value), h('td', badge(m.status)))))))));
			}
			const recs = z.records || [];
			const editable = r => typeNames().includes(r.type);
			body.appendChild(panel('Records', h('button.small', { onclick: () => edit(null) }, 'Add record'),
				h('div.table-wrap', h('table.dns',
					h('thead', h('tr', h('th', 'Name'), h('th', 'Type'), h('th', 'TTL'), h('th', 'Value'), z.shared ? null : h('th', ''), h('th', ''))),
					h('tbody', recs.map(r => h('tr', { class: r.inherited ? 'inherited' : '' },
						h('td.mono', nameOf(r)), h('td', r.type), h('td', String(r.ttl)), h('td.mono', r.data),
						z.shared ? null : h('td', r.inherited ? h('span.badge', 'inherited') : h('span.badge.ok', 'own')),
						h('td.actions', !editable(r) ? null : r.inherited
							? h('button.small', { onclick: () => edit(r) }, 'Override')
							: [h('button.small', { onclick: () => edit(r) }, 'Edit'),
								r.type === 'SOA' ? null : h('button.small.danger', { onclick: () => remove(r) }, 'Delete')]))))))));
		}

		async function publish() {
			try {
				zone = await api('POST', '/dns/zones/' + enc(current) + '/publish');
				toast((zone.published || []).length + ' record(s) published', 'ok');
				draw();
			} catch (e) { fail(e); }
		}

		/**
		 * The record form. Add (r null): records of a type at a name, one per value line. Override (r
		 * inherited): the domain's own set of that name and type, which replaces the inherited one.
		 * Edit (r own): any of its name, type, TTL and value. The types and their examples come from
		 * the DNS server.
		 */
		async function edit(r) {
			const z = zone;
			const changing = r && !r.inherited;
			const soa = r && r.type === 'SOA';
			const types = (z.types || []).filter(t => soa || t.name !== 'SOA');
			const name = h('input', { type: 'text', value: r ? nameOf(r) : '', placeholder: '@ or www' });
			const type = h('select', types.map(t => h('option', { value: t.name }, t.name)));
			type.value = r ? r.type : (types.find(t => t.name === 'A') ? 'A' : (types[0] || {}).name);
			if (soa) { name.disabled = true; type.disabled = true; }
			const ttl = h('input', { type: 'number', min: '0', value: r ? String(r.ttl) : '3600' });
			const values = h('textarea', { rows: changing ? '2' : '4', spellcheck: 'false' });
			const same = r && r.inherited ? z.records.filter(x => x.name === r.name && x.type === r.type && x.inherited) : [];
			values.value = changing ? r.data : same.map(x => x.data).join('\n');
			const example = h('div.muted.mono');
			const hint = () => {
				const t = types.find(x => x.name === type.value);
				const ex = t && t.example ? t.example : '';
				values.placeholder = (changing ? '' : 'One per line, ') + 'e.g. ' + ex;
				example.textContent = 'Example: ' + ex;
			};
			type.addEventListener('change', hint);
			hint();
			const title = !r ? 'Add records to ' + z.domain : r.inherited ? 'Override ' + nameOf(r) + ' ' + r.type : 'Edit ' + nameOf(r) + ' ' + r.type;
			const note = !r ? 'The values are added to the records of this name and type (one per line); the TTL becomes the TTL of the whole set.'
				: r.inherited ? 'These values become ' + z.domain + '\'s own ' + r.type + ' records at this name, in place of every inherited ' + r.type + ' there.'
				: soa ? 'The serial is raised by itself if you don\'t give a higher one.'
				: 'Change any field. The TTL becomes the TTL of every record of the new name and type.';
			const ok = await dialog(title, [
				h('p.muted', note),
				h('div.form', h('label.row', h('span', 'Name'), name), h('label.row', h('span', 'Type'), type),
					h('label.row', h('span', 'TTL (s)'), ttl), h('label.row', h('span', changing ? 'Value' : 'Values'), values), example)
			], { okLabel: 'Save', onOk: () => {
				if (changing) {
					const v = values.value.split('\n').map(x => x.trim()).filter(x => x).join(' ');
					return api('POST', path('change'), { name: r.name, type: r.type, data: r.data, newName: name.value, newType: type.value, ttl: ttl.value, newData: v });
				}
				return api('POST', path('add'), { name: name.value, type: type.value, ttl: ttl.value, values: values.value.split('\n').map(x => x.trim()).filter(x => x) });
			} });
			if (ok) { zone = z.shared ? asShared(ok) : ok; toast('Saved', 'ok'); draw(); }
		}

		async function remove(r) {
			if (!await confirmDialog('Delete ' + nameOf(r) + ' ' + r.type, r.data + (zone.inheritFrom ? ' (an inherited record of this name and type shows through again if it was the last)' : ''), 'Delete')) return;
			call('POST', path('delete'), { name: r.name, type: r.type, data: r.data }, 'Deleted');
		}

		load();
		return { refresh: () => current && show(current) };
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
		root.appendChild(h('p.muted', 'READ: log in and read mail (IMAP, POP3) or files (FTP). WRITE: change mailboxes, send mail and change files. ADMIN: use these pages. Changes take effect at once.'));
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
			if (r) { refresh(r); toast('Saved. It is used now.', 'ok'); checkRestart(); }
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
			state.primary ? h('button.primary', { onclick: flush }, 'Retry all now') : null), list));

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
		root.appendChild(h('p.muted', 'Every message the SMTP queue took, each delivery attempt and its result, kept for the days set in Settings > Queue.'));
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

	const SOURCES = ['SMTP', 'IMAP', 'POP3', 'FTP', 'MAIL', 'NET', 'ADMIN', 'OTHER'];
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
		const fileSel = h('select');
		const fileView = h('pre.message.logfile');
		const fileDl = h('a.btn', { href: '#' }, 'Download');
		root.appendChild(panel('Log files', h('div.toolbar', fileSel, h('button', { onclick: () => readFile() }, 'Refresh'), fileDl),
			h('p.muted', 'This application\'s log file and the servers\' (logFile in a settings file), and those named in Settings > Admin. Servers run as services log here, not to the log above.'),
			fileView));
		fileSel.addEventListener('change', () => readFile());

		async function drawFiles() {
			let files;
			try { files = await api('GET', '/logs/files'); } catch (e) { return; }
			clear(fileSel);
			if (!files.length) {
				fileSel.appendChild(h('option', { value: '' }, 'No log files'));
				clear(fileView).appendChild(document.createTextNode('No log files yet.'));
				return;
			}
			for (const f of files) fileSel.appendChild(h('option', { value: f.path }, f.path + ' (' + fmtBytes(f.size) + ', ' + fmtTime(f.modified) + ')'));
			readFile();
		}

		async function readFile() {
			if (!fileSel.value) return;
			fileDl.href = 'api/logs/files/download?' + new URLSearchParams({ path: fileSel.value });
			let text;
			try { text = await api('GET', '/logs/files/read?' + new URLSearchParams({ path: fileSel.value, bytes: 262144 })); } catch (e) { return fail(e); }
			clear(fileView).appendChild(document.createTextNode(text || '(empty)'));
			fileView.scrollTop = fileView.scrollHeight;
		}

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
		drawFiles();
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
		/** the server chosen on each tab whose settings are each server's own */
		const chosen = new Map();
		const wanted = /[?&]tab=([\w-]+)/.exec(location.hash);
		if (wanted) current = wanted[1];

		save.addEventListener('click', doSave);
		discard.addEventListener('click', () => { changes.clear(); draw(); });

		async function load() {
			try { data = await api('GET', '/settings'); } catch (e) { return fail(e); }
			where.textContent = 'Saved in ' + data.directory + ' (' + data.files.join(', ') + '), and the store\'s and queue\'s own files (Store and Queue tabs): the files the servers read. Each tab says which services need a restart after a change.';
			if (!current || !groupsWithOther().some(g => g.id === current)) current = data.groups[0].id;
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
			let shown = g.settings;
			let picker = null;
			if (g.servers) {
				if (!chosen.has(g.id)) chosen.set(g.id, g.servers[0].id);
				const sel = chosen.get(g.id);
				shown = g.settings.filter(s => !s.server || s.server === sel);
				picker = h('div.toolbar.servers', h('span.muted', 'Server'), g.servers.map(sv => {
					const dirty = g.settings.some(s => s.server === sv.id && changes.has(s.key));
					return h('button.small', { class: sv.id === sel ? 'primary' : '', title: sv.prefix + '.*', onclick: () => { chosen.set(g.id, sv.id); draw(); } }, sv.label, dirty ? h('span.dot') : null);
				}));
			}
			// a provider's own settings appear while that provider is chosen; the rarely needed ones come last
			shown = shown.filter(visible);
			shown = shown.filter(x => !x.advanced).concat(shown.filter(x => x.advanced));
			const common = commonServices(shown);
			body.appendChild(h('p.muted', g.description, common ? ' ' + restartText(common) : ''));
			if (g.asset) body.appendChild(assetNote(g.asset));
			// the settings of every server of the tab, then the chosen server's own
			for (const s of shown) if (!s.server) body.appendChild(row(s, common, g.asset));
			if (picker) body.appendChild(picker);
			// what depends on a choice is set apart under it: a heading naming the choice, and indented
			let under = null;
			for (const s of shown) if (s.server) {
				if (s.showWhenKey && under !== s.showWhenValue) body.appendChild(h('div.subhead', choiceTitle(s.showWhenKey, s.showWhenValue) + ' settings'));
				under = s.showWhenKey ? s.showWhenValue : null;
				body.appendChild(row(s, common, g.asset));
			}
			if (g.id === 'other') body.appendChild(otherAdder());
			count.textContent = changes.size ? changes.size + ' unsaved change' + (changes.size > 1 ? 's' : '') : '';
			bar.classList.toggle('hidden', changes.size === 0);
		}

		/** Where the store's or queue's own file is, and what saving does when it isn't there yet */
		function assetNote(id) {
			const a = data.assets[id];
			const what = id === 'store' ? 'The mail store' : 'The SMTP queue';
			if (!a.path) return h('div.banner', what + '\'s settings can\'t be edited: ' + (a.error || 'the mail root isn\'t set (General tab)'));
			if (!a.exists) return h('div.banner', what + ' has no settings file yet (' + a.path + '), so the servers refuse to start with it. Saving a setting here makes the file.');
			return h('p.muted', 'In ' + a.path + '.');
		}

		function allSettings() { return data.groups.flatMap(x => x.settings); }

		/** The value a setting has now: unsaved, saved, or its default. */
		function curVal(key) {
			if (changes.has(key)) return changes.get(key);
			const d = allSettings().find(x => x.key === key);
			return d ? (d.value || d.default) : null;
		}

		function visible(s) {
			if (s.hideWhenKey && String(curVal(s.hideWhenKey) || '').toLowerCase() === s.hideWhenValue) return false;
			return !s.showWhenKey || String(curVal(s.showWhenKey) || '').toLowerCase() === s.showWhenValue;
		}

		/** The name for people of the choice a dependent setting is shown for (a provider's title). */
		function choiceTitle(key, value) {
			const d = allSettings().find(x => x.key === key);
			const i = d && d.options ? d.options.indexOf(value) : -1;
			return i >= 0 && d.optionLabels && d.optionLabels[i] ? d.optionLabels[i] : value;
		}

		function hasDependents(key) { return allSettings().some(x => x.showWhenKey === key); }

		/** Ask the chosen spam provider's scanner whether it answers, with the form's settings (saved or not). */
		async function testSpam(s) {
			const id = String(curVal(s.key) || s.default || '').toLowerCase();
			const pre = s.key.replace(/provider$/, '') + id + '.';
			const vals = {};
			for (const x of allSettings()) {
				if (!x.key.startsWith(pre)) continue;
				const v = changes.has(x.key) ? changes.get(x.key) : x.value;
				if (v) vals[x.key.slice(pre.length)] = v;
			}
			try {
				const r = await api('POST', '/spam/test', { provider: id, settings: vals });
				toast(r.message || (r.available ? 'Answers' : 'Does not answer'), r.available ? 'ok' : 'bad');
			} catch (e) { fail(e); }
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

		function row(s, common, inAsset) {
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
				case 'CHOICE': {
					const labels = s.optionLabels || [];
					const nice = o => { const i = (s.options || []).indexOf(o); return i >= 0 && labels[i] ? labels[i] : o; };
					// a default that is one of the choices is that choice (choosing it clears the setting): no second entry for it
					const defIsChoice = !!s.default && (s.options || []).includes(s.default);
					const optText = (o, i) => (labels[i] && labels[i] !== o ? labels[i] + ' (' + o + ')' : o) + (defIsChoice && o === s.default ? ' - default' : '');
					const sel = h('select', defIsChoice ? null : h('option', { value: '' }, s.default ? 'default (' + nice(s.default) + ')' : 'not set'),
						(s.options || []).map((o, i) => h('option', { value: o }, optText(o, i))));
					sel.value = val || (defIsChoice ? s.default : '');
					sel.addEventListener('change', () => { set(sel.value === '' || (defIsChoice && sel.value === s.default) ? null : sel.value); if (hasDependents(s.key)) { const y = window.scrollY; draw(); window.scrollTo(0, y); } });
					const chosenId = (val || s.default || '');
					const oi = (s.options || []).indexOf(chosenId);
					const about = oi >= 0 && s.optionDescriptions && s.optionDescriptions[oi] ? h('div.hint', s.optionDescriptions[oi]) : null;
					const test = /\.spam\.provider$/.test(s.key) ? h('div', h('button.small', { type: 'button', onclick: () => testSpam(s) }, 'Test connection'),
						h('span.muted', ' Asks the scanner with the settings below, saved or not.')) : null;
					input = h('div', sel, about, test);
					break;
				}
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
			const r = h('div.setting', { class: (has ? 'dirty' : '') + (s.showWhenKey || s.hideWhenKey ? ' sub' : '') },
				h('div.name', h('b', s.label || s.key), h('code', s.key === 'queue.hostname' ? 'hostname' : s.name || s.key)),
				h('div.edit', input,
					s.description ? h('div.hint', s.description) : null,
					s.effective !== undefined ? h('div.hint.warn-text', 'Set outside the files (-D) to: ' + s.effective) : null,
					s.key === 'queue.hostname' ? h('div.hint', 'In ' + (s.file || 'the queue\'s queue.properties') + ', and parley-smtp.properties for each SMTP server') :
						s.file && s.file !== 'parley-mail.properties' && !inAsset ? h('div.hint', 'In ' + s.file) : null,
					s.services && s.services.join(',') !== common ? h('div.hint', restartText(s.services.join(','))) : null,
					!s.label ? h('div', h('button.small.danger', { onclick: () => { changes.set(s.key, null); r.classList.add('dirty'); set(null); } }, 'Remove')) : null));
			return r;
		}

		function otherAdder() {
			const k = h('input', { type: 'text', placeholder: 'parley.mail.smtp.something, or queue.name / store.name', spellcheck: 'false' });
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
