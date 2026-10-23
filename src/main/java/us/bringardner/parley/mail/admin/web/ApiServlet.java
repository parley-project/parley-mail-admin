package us.bringardner.parley.mail.admin.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import us.bringardner.parley.mail.admin.AdminApp;
import us.bringardner.parley.mail.admin.domains.DomainService;
import us.bringardner.parley.mail.admin.log.LogBuffer;
import us.bringardner.parley.mail.admin.settings.SettingDef;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.smtp.queue.MailQueue;
import us.bringardner.parley.smtp.queue.QueueEntry;
import us.bringardner.parley.smtp.queue.QueuedRecipient;

/**
 * The JSON API the pages use, under {@code /api}. Every call but session, login, logout and setup
 * needs a logged-in administrator (see {@link AdminFilter}).
 */
public class ApiServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final System.Logger LOG = System.getLogger(ApiServlet.class.getName());

	/** Failed logins from one address before it must wait. */
	static final int MAX_FAILURES = 10;
	static final long FAILURE_WINDOW = 15 * 60 * 1000L;

	private final Router router = new Router();
	private transient AdminApp app;
	private transient ObjectMapper json;
	private final Map<String, long[]> failures = new ConcurrentHashMap<>();

	@Override
	public void init() throws ServletException {
		app = (AdminApp) getServletContext().getAttribute(AdminContextListener.APP);
		if (app == null) {
			throw new ServletException("The mail suite didn't start; see the log");
		}
		json = app.getJson();
		routes();
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String path = req.getRequestURI().substring(req.getContextPath().length() + req.getServletPath().length());
		if (path.isEmpty()) {
			path = "/";
		}
		try {
			Object[] found = router.find(req.getMethod(), path);
			if (found == null) {
				throw ApiException.notFound(path);
			}
			String user = AdminFilter.user(req);
			if (user != null) {
				UserRecord u = app.getUsers().get(user);
				if (u == null || !u.hasPermission(UserRecord.ADMIN)) {
					AdminFilter.logout(req);
					if (!isOpen(path)) {
						throw new ApiException(401, "Please log in");
					}
				}
			}
			@SuppressWarnings("unchecked")
			Object result = ((Router.Handler) found[0]).handle(new Router.Request(req, res, (List<String>) found[1], json));
			write(res, result);
		} catch (ApiException e) {
			error(res, e.getStatus(), e.getMessage());
		} catch (IllegalArgumentException e) {
			error(res, 400, e.getMessage());
		} catch (IllegalStateException e) {
			error(res, 409, e.getMessage());
		} catch (Exception e) {
			LOG.log(System.Logger.Level.ERROR, req.getMethod() + " " + path + " failed", e);
			error(res, 500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	private static boolean isOpen(String path) {
		return path.equals("/session") || path.equals("/login") || path.equals("/setup") || path.equals("/logout");
	}

	private void write(HttpServletResponse res, Object result) throws IOException {
		if (res.isCommitted()) {
			return;
		}
		if (result == null) {
			res.setStatus(204);
			return;
		}
		if (result instanceof Router.Text) {
			res.setContentType("text/plain; charset=UTF-8");
			res.getOutputStream().write(((Router.Text) result).text.getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (result instanceof Router.Download) {
			Router.Download d = (Router.Download) result;
			res.setContentType(d.contentType);
			res.setHeader("Content-Disposition", "attachment; filename=\"" + d.name + "\"");
			res.setContentLengthLong(d.file.length());
			try (InputStream in = Files.newInputStream(d.file.toPath()); OutputStream out = res.getOutputStream()) {
				in.transferTo(out);
			}
			return;
		}
		res.setContentType("application/json; charset=UTF-8");
		json.writeValue(res.getOutputStream(), result);
	}

	static void error(HttpServletResponse res, int status, String message) throws IOException {
		if (res.isCommitted()) {
			return;
		}
		res.setStatus(status);
		res.setContentType("application/json; charset=UTF-8");
		res.setHeader("Cache-Control", "no-store");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("error", message == null ? "Error" : message);
		new ObjectMapper().writeValue(res.getOutputStream(), m);
	}

	// ------------------------------------------------------------------ routes

	private void routes() {
		Router r = router;
		// session
		r.get("/session", this::session);
		r.post("/login", this::login);
		r.post("/logout", q -> {
			AdminFilter.logout(q.http);
			return null;
		});
		r.post("/setup", this::setup);

		// status and services
		r.get("/status", q -> status());
		r.get("/services", q -> services());
		r.post("/services/all/{action}", this::allServices);
		r.post("/services/{id}/{action}", this::serviceAction);
		r.get("/services/{id}/connections", q -> app.getSuite().connections(service(q.param(0))));

		// settings
		r.get("/settings", q -> settings());
		r.put("/settings", this::updateSettings);

		// domains
		r.get("/domains", q -> domains());
		r.post("/domains", q -> {
			app.getDomains().add(q.requiredText("domain"));
			return domains();
		});
		r.delete("/domains/{domain}", q -> {
			app.getDomains().remove(q.param(0));
			return domains();
		});
		r.post("/domains/{domain}/dkim", q -> {
			app.getDomains().generateDkim(q.param(0), q.requiredText("selector"), q.text("type"));
			return app.getDomains().describe(q.param(0));
		});

		// users
		r.get("/users", q -> users());
		r.post("/users", q -> {
			app.getUsers().add(q.requiredText("name"), required(q.password("password"), "password"), permissions(q.list("permissions")),
					q.text("maildrop"));
			LOG.log(System.Logger.Level.INFO, q.user() + " added the user " + q.text("name"));
			return users();
		});
		r.put("/users/{name}", q -> {
			List<String> perms = q.list("permissions");
			app.getUsers().update(q.param(0), q.password("password"), perms == null ? null : permissions(perms), q.text("maildrop"));
			LOG.log(System.Logger.Level.INFO, q.user() + " changed the user " + q.param(0));
			return users();
		});
		r.delete("/users/{name}", q -> {
			if (q.param(0).equalsIgnoreCase(q.user())) {
				throw ApiException.badRequest("You can't delete yourself");
			}
			app.getUsers().delete(q.param(0));
			LOG.log(System.Logger.Level.INFO, q.user() + " deleted the user " + q.param(0));
			return users();
		});
		r.get("/users/{name}/usage", this::usage);

		// aliases
		r.get("/aliases", q -> aliases());
		r.put("/aliases/{name}", q -> {
			app.getAliases().put(q.param(0), required(q.list("members"), "members"));
			app.getSuite().markChanged(ServiceId.Protocol.SMTP);
			return aliases();
		});
		r.delete("/aliases/{name}", q -> {
			if (!app.getAliases().delete(q.param(0))) {
				throw ApiException.notFound("The alias " + q.param(0));
			}
			app.getSuite().markChanged(ServiceId.Protocol.SMTP);
			return aliases();
		});

		// logs
		r.get("/logs", this::logs);
		r.get("/logs/levels", q -> logLevels());
		r.put("/logs/levels", q -> {
			LogBuffer.Source s = parseEnum(LogBuffer.Source.class, q.requiredText("source"));
			String level = q.text("level");
			app.setLogLevel(s, level == null || level.isEmpty() ? null : parseEnum(LogBuffer.Level.class, level));
			return logLevels();
		});
		r.post("/logs/clear", q -> {
			app.getLogs().clear();
			return null;
		});
		r.get("/logs/download", q -> {
			File f = app.getLogs().getFile();
			if (f == null || !f.isFile()) {
				throw ApiException.notFound("The log file");
			}
			return new Router.Download(f, "parley-mail.log", "text/plain; charset=UTF-8");
		});

		// SMTP queue
		r.get("/queue", q -> queue());
		r.post("/queue/flush", q -> {
			requireQueue().flush();
			LOG.log(System.Logger.Level.INFO, q.user() + " flushed the SMTP queue");
			return queue();
		});
		r.post("/queue/{id}/retry", q -> {
			if (!requireQueue().retry(q.param(0))) {
				throw ApiException.notFound("The message " + q.param(0));
			}
			return queue();
		});
		r.delete("/queue/{id}", q -> {
			MailQueue mq = requireQueue();
			if (mq.getEntry(q.param(0)) == null) {
				throw ApiException.notFound("The message " + q.param(0));
			}
			if (!mq.delete(q.param(0))) {
				throw new ApiException(409, "The message is being delivered right now; try again in a moment");
			}
			LOG.log(System.Logger.Level.INFO, q.user() + " deleted " + q.param(0) + " from the SMTP queue");
			return queue();
		});
		r.get("/queue/{id}/message", q -> {
			String text = app.readQueuedMessage(q.param(0));
			if (text == null) {
				throw ApiException.notFound("The message " + q.param(0));
			}
			return new Router.Text(text);
		});

		// SMTP history
		r.get("/history", this::history);
		r.get("/history/{id}", q -> {
			LocalDate to = LocalDate.now();
			LocalDate from = to.minusDays(q.queryLong("days", 30));
			List<Map<String, Object>> ret = new ArrayList<>();
			for (Map<String, Object> m : app.getHistory().query(from, to, q.param(0), null, 10000)) {
				if (q.param(0).equals(m.get("id"))) {
					ret.add(m);
				}
			}
			java.util.Collections.reverse(ret); // oldest first: the order they happened in
			return ret;
		});
	}

	private static <T> T required(T value, String name) {
		if (value == null) {
			throw ApiException.badRequest(name + " is required");
		}
		return value;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Unknown " + type.getSimpleName().toLowerCase(Locale.ROOT) + " '" + value + "'");
		}
	}

	private static Collection<String> permissions(List<String> list) {
		return list == null ? Arrays.asList(UserRecord.READ, UserRecord.WRITE) : list;
	}

	// ------------------------------------------------------------------ session

	private Object session(Router.Request q) {
		Map<String, Object> m = new LinkedHashMap<>();
		String user = q.user();
		m.put("authenticated", user != null);
		m.put("user", user);
		m.put("csrf", user == null ? null : AdminFilter.csrf(q.http));
		m.put("setupRequired", !app.getUsers().hasAdmin());
		m.put("version", AdminApp.version());
		m.put("domains", app.getDomains().list());
		return m;
	}

	private Object login(Router.Request q) throws IOException {
		String ip = q.http.getRemoteAddr();
		long now = System.currentTimeMillis();
		long[] f = failures.get(ip);
		if (f != null && now - f[1] > FAILURE_WINDOW) {
			failures.remove(ip);
			f = null;
		}
		if (f != null && f[0] >= MAX_FAILURES) {
			throw new ApiException(429, "Too many failed logins; try again later");
		}
		String name = q.requiredText("user");
		char[] password = q.password("password");
		UserRecord u = app.getUsers().authenticate(name, password);
		if (u == null || !u.hasPermission(UserRecord.ADMIN)) {
			failures.compute(ip, (k, v) -> v == null || now - v[1] > FAILURE_WINDOW ? new long[] { 1, now } : new long[] { v[0] + 1, v[1] });
			LOG.log(System.Logger.Level.WARNING, "Failed admin login for '" + name + "' from " + ip);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new ApiException(401, u == null ? "Wrong user name or password" : "That user isn't an administrator (needs ADMIN)");
		}
		failures.remove(ip);
		AdminFilter.login(q.http, u.getName());
		LOG.log(System.Logger.Level.INFO, u.getName() + " logged in to the admin pages from " + ip);
		return session(q);
	}

	/** Make the first administrator (only while there is none). */
	private Object setup(Router.Request q) throws IOException {
		synchronized (this) {
			if (app.getUsers().hasAdmin()) {
				throw new ApiException(409, "An administrator already exists; log in");
			}
			String name = q.requiredText("user");
			app.getUsers().add(name, required(q.password("password"), "password"),
					Arrays.asList(UserRecord.READ, UserRecord.WRITE, UserRecord.ADMIN), q.text("maildrop"));
			UserRecord u = app.getUsers().get(name.trim());
			AdminFilter.login(q.http, u.getName());
			LOG.log(System.Logger.Level.INFO, "Created the first administrator " + u.getName() + " from " + q.http.getRemoteAddr());
		}
		return session(q);
	}

	// ------------------------------------------------------------------ status and services

	private Object status() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", AdminApp.version());
		m.put("startedAt", app.getStartedAt());
		m.put("now", System.currentTimeMillis());
		m.put("home", app.getHome().getDirectory().getPath());
		m.put("mailRoot", app.getSettings().getEffective(SettingsCatalog.ROOT));
		m.put("hostname", app.getSettings().getEffective(SettingsCatalog.SMTP_HOSTNAME));
		m.put("services", services());
		MailQueue q = app.getSuite().getQueue();
		Map<String, Object> queue = new LinkedHashMap<>();
		queue.put("running", q != null && q.isRunning());
		queue.put("messages", q == null ? 0 : q.getEntries().size());
		m.put("queue", queue);
		m.put("domains", app.getDomains().list());
		m.put("users", app.getUsers().list().size());
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		m.put("heapUsed", heap.getUsed());
		m.put("heapMax", heap.getMax());
		m.put("java", System.getProperty("java.version"));
		m.put("problems", app.getLogs().query(0, LogBuffer.Level.WARN, null, null, 8));
		return m;
	}

	private List<Map<String, Object>> services() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (ServiceId id : ServiceId.values()) {
			list.add(app.getSuite().status(id));
		}
		return list;
	}

	private static ServiceId service(String id) {
		ServiceId s = ServiceId.parse(id);
		if (s == null) {
			throw ApiException.notFound("The service " + id);
		}
		return s;
	}

	private Object serviceAction(Router.Request q) throws IOException {
		ServiceId id = service(q.param(0));
		MailSuite suite = app.getSuite();
		String action = q.param(1);
		LOG.log(System.Logger.Level.INFO, q.user() + ": " + action + " " + id.label);
		switch (action) {
		case "start":
			suite.start(id);
			break;
		case "stop":
			suite.stop(id);
			break;
		case "restart":
			suite.restart(id);
			break;
		default:
			throw ApiException.notFound("The action " + action);
		}
		return services();
	}

	private Object allServices(Router.Request q) throws IOException {
		MailSuite suite = app.getSuite();
		String action = q.param(0);
		LOG.log(System.Logger.Level.INFO, q.user() + ": " + action + " all services");
		switch (action) {
		case "start":
			suite.startAll();
			break;
		case "stop":
			suite.stopAll();
			break;
		case "restart":
			suite.stopAll();
			suite.startAll();
			break;
		case "restart-changed":
			suite.restartChanged();
			break;
		default:
			throw ApiException.notFound("The action " + action);
		}
		return services();
	}

	// ------------------------------------------------------------------ settings

	private Object settings() {
		SettingsService s = app.getSettings();
		SettingsCatalog c = s.getCatalog();
		Map<String, String> inFile = s.getAll();
		List<Object> groups = new ArrayList<>();
		Set<String> known = new HashSet<>();
		for (SettingsCatalog.Group g : c.getGroups()) {
			Map<String, Object> gm = new LinkedHashMap<>();
			gm.put("id", g.id);
			gm.put("label", g.label);
			gm.put("description", g.description);
			List<Object> list = new ArrayList<>();
			for (SettingDef d : g.settings) {
				known.add(d.getKey());
				list.add(setting(d.getKey(), d, inFile.get(d.getKey()), s.getEffective(d.getKey()), c.isSecret(d.getKey())));
			}
			gm.put("settings", list);
			groups.add(gm);
		}
		List<Object> other = new ArrayList<>();
		for (Map.Entry<String, String> e : inFile.entrySet()) {
			if (!known.contains(e.getKey()) && !e.getKey().startsWith(AdminApp.LOG_LEVEL_PREFIX)) {
				other.add(setting(e.getKey(), null, e.getValue(), s.getEffective(e.getKey()), c.isSecret(e.getKey())));
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("file", s.getFile().getFile().getPath());
		m.put("groups", groups);
		m.put("other", other);
		return m;
	}

	private static Map<String, Object> setting(String key, SettingDef d, String value, String effective, boolean secret) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", key);
		if (d != null) {
			m.put("label", d.getLabel());
			m.put("type", d.getType().name());
			m.put("default", d.getDefaultValue());
			m.put("description", d.getDescription());
			if (!d.getOptions().isEmpty()) {
				m.put("options", d.getOptions());
			}
		} else {
			m.put("type", secret ? "SECRET" : "TEXT");
		}
		if (secret) {
			m.put("set", value != null && !value.isEmpty());
		} else {
			m.put("value", value);
			if (effective != null && !effective.equals(value == null ? null : value.trim())) {
				m.put("effective", effective); // set outside the file (-D)
			}
		}
		List<String> services = new ArrayList<>();
		for (ServiceId id : ServiceId.values()) {
			if (id.isAffectedBy(key)) {
				services.add(id.id());
			}
		}
		m.put("services", services);
		return m;
	}

	private Object updateSettings(Router.Request q) throws IOException {
		JsonNode changes = q.body().get("changes");
		if (changes == null || !changes.isObject()) {
			throw ApiException.badRequest("changes is required");
		}
		Map<String, String> map = new LinkedHashMap<>();
		changes.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
		Set<String> changed = app.getSettings().update(map);
		LOG.log(System.Logger.Level.INFO, q.user() + " changed the settings " + changed);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("changed", changed);
		List<String> restart = new ArrayList<>();
		for (ServiceId id : ServiceId.values()) {
			if (app.getSuite().isRestartNeeded(id)) {
				restart.add(id.id());
			}
		}
		m.put("restartNeeded", restart);
		return m;
	}

	// ------------------------------------------------------------------ domains, users, aliases

	private Object domains() {
		List<Object> list = new ArrayList<>();
		DomainService d = app.getDomains();
		for (String domain : d.list()) {
			list.add(d.describe(domain));
		}
		return list;
	}

	private Object users() {
		List<Object> list = new ArrayList<>();
		for (UserRecord u : app.getUsers().list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", u.getName());
			m.put("domain", u.getDomain());
			m.put("permissions", u.getPermissions());
			m.put("maildrop", u.getMaildrop());
			m.put("hashed", u.isHashed());
			m.put("scram", u.hasScram());
			list.add(m);
		}
		return list;
	}

	private Object usage(Router.Request q) throws IOException {
		UserRecord u = app.getUsers().get(q.param(0));
		if (u == null) {
			throw ApiException.notFound("The user " + q.param(0));
		}
		long[] usage = app.mailboxUsage(u.getMaildrop());
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("exists", usage != null);
		m.put("bytes", usage == null ? 0 : usage[0]);
		m.put("files", usage == null ? 0 : usage[1]);
		return m;
	}

	private Object aliases() {
		List<Object> list = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : app.getAliases().list().entrySet()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", e.getKey());
			int at = e.getKey().lastIndexOf('@');
			m.put("domain", at < 0 ? null : e.getKey().substring(at + 1));
			m.put("members", e.getValue());
			list.add(m);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("file", app.getAliases().getFile().getPath());
		m.put("aliases", list);
		m.put("restartNeeded", app.getSuite().isRestartNeeded(ServiceId.SMTP) || app.getSuite().isRestartNeeded(ServiceId.SUBMISSION)
				|| app.getSuite().isRestartNeeded(ServiceId.SUBMISSIONS));
		return m;
	}

	// ------------------------------------------------------------------ logs

	private Object logs(Router.Request q) {
		long after = q.queryLong("after", 0);
		LogBuffer.Level level = LogBuffer.Level.parse(q.query("level", null), LogBuffer.Level.TRACE);
		String src = q.query("source", null);
		LogBuffer.Source source = src == null ? null : parseEnum(LogBuffer.Source.class, src);
		int limit = (int) Math.max(1, Math.min(5000, q.queryLong("limit", 500)));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("last", app.getLogs().lastSeq());
		m.put("entries", app.getLogs().query(after, level, source, q.query("q", null), limit));
		return m;
	}

	private Object logLevels() {
		Map<String, Object> m = new LinkedHashMap<>();
		for (Map.Entry<LogBuffer.Source, LogBuffer.Level> e : app.getLogLevels().entrySet()) {
			m.put(e.getKey().name(), e.getValue() == null ? null : e.getValue().name());
		}
		return m;
	}

	// ------------------------------------------------------------------ queue and history

	private MailQueue requireQueue() {
		MailQueue q = app.getSuite().getQueue();
		if (q == null) {
			throw new ApiException(409, "The SMTP queue runs only while an SMTP service runs");
		}
		return q;
	}

	private Object queue() {
		Map<String, Object> m = new LinkedHashMap<>();
		MailQueue q = app.getSuite().getQueue();
		m.put("running", q != null && q.isRunning());
		List<Map<String, Object>> list = new ArrayList<>();
		if (q != null) {
			for (QueueEntry e : q.getEntries()) {
				list.add(entry(e));
			}
		}
		list.sort(Comparator.comparingLong(e -> (Long) e.get("created")));
		m.put("entries", list);
		return m;
	}

	private static Map<String, Object> entry(QueueEntry e) {
		Map<String, Object> m = new LinkedHashMap<>();
		synchronized (e) {
			m.put("id", e.getId());
			m.put("created", e.getCreated());
			m.put("from", e.getFrom() == null ? "" : e.getFrom().toString());
			m.put("size", e.getSize());
			m.put("submitter", e.getSubmitter());
			m.put("inbound", e.isInbound());
			m.put("quarantine", e.isQuarantine());
			m.put("pending", e.isPending());
			long next = e.nextAttempt();
			m.put("nextAttempt", next == Long.MAX_VALUE ? 0 : next);
			List<Map<String, Object>> rcpts = new ArrayList<>();
			for (QueuedRecipient r : e.getRecipients()) {
				Map<String, Object> rm = new LinkedHashMap<>();
				rm.put("address", r.getAddress().toString());
				rm.put("status", r.getStatus().name());
				rm.put("attempts", r.getAttempts());
				rm.put("lastStatus", r.getLastStatus());
				rm.put("lastResult", r.getLastResult());
				rm.put("remoteMta", r.getRemoteMta());
				rm.put("nextAttempt", r.getStatus() == QueuedRecipient.Status.PENDING ? r.getNextAttempt() : 0);
				rcpts.add(rm);
			}
			m.put("recipients", rcpts);
		}
		return m;
	}

	private Object history(Router.Request q) throws IOException {
		LocalDate to;
		LocalDate from;
		try {
			to = LocalDate.parse(q.query("to", LocalDate.now().toString()));
			from = LocalDate.parse(q.query("from", to.toString()));
		} catch (DateTimeParseException e) {
			throw ApiException.badRequest("Dates are YYYY-MM-DD");
		}
		Set<String> types = null;
		String t = q.query("type", null);
		if (t != null) {
			types = new HashSet<>(Arrays.asList(t.toUpperCase(Locale.ROOT).split("\\s*,\\s*")));
		}
		int limit = (int) Math.max(1, Math.min(10000, q.queryLong("limit", 1000)));
		return app.getHistory().query(from, to, q.query("q", null), types, limit);
	}
}
