package us.bringardner.parley.mail.admin.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
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
import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.util.LogHelper;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.mail.admin.AdminApp;
import us.bringardner.parley.mail.admin.domains.DomainService;
import us.bringardner.parley.mail.admin.history.HistoryStore;
import us.bringardner.parley.mail.admin.log.LogBuffer;
import us.bringardner.parley.mail.admin.log.LogFiles;
import us.bringardner.parley.mail.admin.settings.SettingDef;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.mail.admin.users.AdminScope;
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.smtp.queue.MailQueueMXBean;
import us.bringardner.parley.smtp.queue.QueuedMessageInfo;
import us.bringardner.parley.smtp.queue.QueuedRecipientInfo;
import us.bringardner.parley.smtp.spam.SpamProvider;
import us.bringardner.parley.smtp.spam.SpamProviders;
import us.bringardner.parley.smtp.spam.SpamStatus;

/**
 * The JSON API the pages use, under {@code /api}. Every call but session, login, logout and setup
 * needs a logged-in administrator (see {@link AdminFilter}).
 */
public class ApiServlet extends HttpServlet {

	/** Logs for this class (it isn't a BaseObject) */
	private static final LogHelper LOG = new LogHelper(ApiServlet.class);

	private static final long serialVersionUID = 1L;

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
				AdminScope scope = app.scopeOf(user);
				if (scope == null) {
					AdminFilter.logout(req);
					if (!isOpen(path)) {
						throw new ApiException(401, "Please log in");
					}
				} else {
					req.setAttribute(SCOPE, scope);
					if (!scope.isPrimary() && !isOpen(path) && !isDomainCall(req.getMethod(), path)) {
						throw new ApiException(403, "Only an administrator of the primary domain can do this");
					}
				}
			}
			@SuppressWarnings("unchecked")
			Object result = ((Router.Handler) found[0]).handle(new Router.Request(req, res, (List<String>) found[1], json));
			write(res, result);
		} catch (ApiException e) {
			error(res, e.getStatus(), e.getMessage());
		} catch (us.bringardner.parley.iam.UserRegistryUnavailableException e) {
			unavailable(res, path, e);
		} catch (java.io.UncheckedIOException e) {
			if (e.getCause() instanceof us.bringardner.parley.iam.UserRegistryUnavailableException) {
				unavailable(res, path, e.getCause());
			} else {
				LOG.logError(req.getMethod() + " " + path + " failed", e);
				error(res, 500, e.getMessage());
			}
		} catch (IllegalArgumentException e) {
			error(res, 400, e.getMessage());
		} catch (IllegalStateException e) {
			error(res, 409, e.getMessage());
		} catch (Exception e) {
			LOG.logError(req.getMethod() + " " + path + " failed", e);
			error(res, 500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	/** The user registry can't answer now: not the request's fault, and never "not allowed". */
	private void unavailable(HttpServletResponse res, String path, Throwable e) throws IOException {
		LOG.logError(path + ": the user registry can't answer now: " + e.getMessage());
		error(res, 503, "The users can't be read or changed now: " + e.getMessage());
	}

	/** The request attribute holding the logged-in administrator's {@link AdminScope}. */
	static final String SCOPE = "parley.admin.scope";

	/**
	 * The calls an administrator of a domain other than the primary may make: its domain's users,
	 * aliases, DKIM keys and DNS zone, and the queue and history of its mail. Everything else (services,
	 * settings, the store, logs, adding or removing domains, flushing the queue) is the primary
	 * domain's administrators' only.
	 */
	private static final String[][] DOMAIN_CALLS = {
			{ "GET", "/domains" }, { "POST", "/domains/[^/]+/dkim" }, { "POST", "/domains/[^/]+/spam" },
			{ "GET", "/users" }, { "POST", "/users" }, { "PUT", "/users/[^/]+" }, { "DELETE", "/users/[^/]+" }, { "GET", "/users/[^/]+/usage" },
			{ "GET", "/users/[^/]+/mailboxes" }, { "POST", "/users/[^/]+/mailboxes/acl" }, { "POST", "/users/[^/]+/mailboxes/acl/delete" },
			{ "GET", "/aliases" }, { "PUT", "/aliases/[^/]+" }, { "DELETE", "/aliases/[^/]+" },
			{ "GET", "/queue" }, { "POST", "/queue/[^/]+/retry" }, { "DELETE", "/queue/[^/]+" }, { "GET", "/queue/[^/]+/message" },
			{ "GET", "/history" }, { "GET", "/history/[^/]+" },
			{ "GET", "/modules" }, { "GET", "/dns/zones/[^/]+" }, { "POST", "/dns/zones/[^/]+" }, { "POST", "/dns/zones/[^/]+/records/(add|change)" },
			{ "POST", "/dns/zones/[^/]+/delete" }, { "POST", "/dns/zones/[^/]+/publish" } };

	/** The type-specific pages that apply: the bean types the launchers have, and the pages for them */
	private Map<String, Object> modules() {
		List<String> types = app.getSuite().types();
		List<String> modules = new ArrayList<>();
		if (types.contains(us.bringardner.parley.mail.admin.dns.DnsAdmin.TYPE)) {
			modules.add("dns");
		}
		if (types.contains("MailQueue")) {
			modules.add("queue");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("types", types);
		m.put("modules", modules);
		return m;
	}

	/** The domain of a DNS call, which must be the administrator's */
	private String dnsDomain(Router.Request q) {
		String d = q.param(0).trim().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
		if (!scope(q).allowsDomain(d)) {
			throw new ApiException(403, "The domain " + d + " isn't yours");
		}
		return d;
	}

	private static long ttl(Router.Request q) throws IOException {
		String t = q.text("ttl");
		if (t == null || t.trim().isEmpty()) {
			return 3600;
		}
		try {
			long v = Long.parseLong(t.trim());
			if (v < 0 || v > 0x7fffffffL) {
				throw new NumberFormatException();
			}
			return v;
		} catch (NumberFormatException e) {
			throw ApiException.badRequest("ttl must be a number of seconds");
		}
	}

	/** The mail records the Domains page recommends for a local domain (none for another domain) */
	@SuppressWarnings("unchecked")
	private List<Map<String, String>> mailRecords(String d) {
		if (!app.getDomains().isLocal(d)) {
			return new ArrayList<>();
		}
		Object dns = app.getDomains().describe(d).get("dns");
		return dns instanceof List ? (List<Map<String, String>>) dns : new ArrayList<>();
	}

	private Map<String, Object> dnsZone(Router.Request q) {
		String d = dnsDomain(q);
		return app.getDns().zone(d, app.getPrimaryDomain(), mailRecords(d));
	}

	static boolean isDomainCall(String method, String path) {
		for (String[] c : DOMAIN_CALLS) {
			if (c[0].equals(method) && path.matches(c[1])) {
				return true;
			}
		}
		return false;
	}

	/** The logged-in administrator's scope (set by service for every call but the open ones). */
	private static AdminScope scope(Router.Request q) {
		AdminScope s = (AdminScope) q.http.getAttribute(SCOPE);
		if (s == null) {
			throw new ApiException(401, "Please log in");
		}
		return s;
	}

	/** Refuse an address outside the administrator's domains. */
	private static void requireAddress(Router.Request q, String address, String what) {
		if (!scope(q).allowsAddress(address)) {
			throw new ApiException(403, what + " " + address + " isn't in your domain");
		}
	}

	private static void requireMaildrop(Router.Request q, String maildrop) {
		if (!scope(q).allowsMaildrop(maildrop)) {
			throw new ApiException(403, "A maildrop must be under your domain's folder (" + scope(q).getDomain() + "/...)");
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
			try (InputStream in = d.file.getInputStream(); OutputStream out = res.getOutputStream()) {
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

		// any Parley server: what parley-net's launchers show over JMX, whatever the server's type
		r.get("/servers", q -> app.getSuite().servers());
		r.post("/servers/{id}/{action}", q -> {
			LOG.logInfo(q.user() + ": " + q.param(1) + " " + q.param(0));
			app.getSuite().serverAction(q.param(0), q.param(1));
			return app.getSuite().servers();
		});
		r.get("/servers/{id}/connections", q -> app.getSuite().serverConnections(q.param(0)));
		// the type-specific pages that apply (a page appears where its server's bean is)
		r.get("/modules", q -> modules());

		// DNS: the Parley DNS server's zones; a domain with no zone file is served from the shared zone
		r.get("/dns", q -> app.getDns().status());
		r.post("/dns/reload", q -> {
			LOG.logInfo(q.user() + ": reload the DNS zones");
			app.getDns().reload();
			return app.getDns().status();
		});
		// the shared zone every listed domain serves and $INHERIT zones inherit: the primary's administrators only
		r.get("/dns/shared", q -> app.getDns().sharedZone());
		r.post("/dns/shared/add", q -> {
			String name = q.text("name");
			String type = q.requiredText("type");
			List<String> values = q.list("values");
			LOG.logInfo(q.user() + ": DNS shared zone add " + name + " " + type + " " + values);
			app.getDns().addSharedRecords(name, type, ttl(q), values);
			return app.getDns().sharedZone();
		});
		r.post("/dns/shared/change", q -> {
			String name = q.text("name");
			String type = q.requiredText("type");
			String data = q.requiredText("data");
			String newType = q.requiredText("newType");
			String newData = q.requiredText("newData");
			LOG.logInfo(q.user() + ": DNS shared zone change " + name + " " + type + " " + data + " to "
					+ q.text("newName") + " " + newType + " " + newData);
			app.getDns().changeSharedRecord(name, type, data, q.text("newName"), newType, ttl(q), newData);
			return app.getDns().sharedZone();
		});
		r.post("/dns/shared/delete", q -> {
			String name = q.text("name");
			String type = q.requiredText("type");
			String data = q.requiredText("data");
			LOG.logInfo(q.user() + ": DNS shared zone delete " + name + " " + type + " " + data);
			app.getDns().deleteSharedRecord(name, type, data);
			return app.getDns().sharedZone();
		});
		r.get("/dns/zones/{domain}", q -> dnsZone(q));
		r.post("/dns/zones/{domain}", q -> {
			String d = dnsDomain(q);
			LOG.logInfo(q.user() + ": make the DNS zone of " + d);
			app.getDns().createZone(d, app.getPrimaryDomain());
			return dnsZone(q);
		});
		r.post("/dns/zones/{domain}/records/add", q -> {
			String d = dnsDomain(q);
			String name = q.text("name");
			String type = q.requiredText("type");
			List<String> values = q.list("values");
			LOG.logInfo(q.user() + ": DNS " + d + " add " + name + " " + type + " " + values);
			app.getDns().addRecords(d, name, type, ttl(q), values);
			return dnsZone(q);
		});
		r.post("/dns/zones/{domain}/records/change", q -> {
			String d = dnsDomain(q);
			String name = q.text("name");
			String type = q.requiredText("type");
			String data = q.requiredText("data");
			String newType = q.requiredText("newType");
			String newData = q.requiredText("newData");
			LOG.logInfo(q.user() + ": DNS " + d + " change " + name + " " + type + " " + data + " to "
					+ q.text("newName") + " " + newType + " " + newData);
			app.getDns().changeRecord(d, name, type, data, q.text("newName"), newType, ttl(q), newData);
			return dnsZone(q);
		});
		r.post("/dns/zones/{domain}/delete", q -> {
			String d = dnsDomain(q);
			String name = q.text("name");
			String type = q.requiredText("type");
			String data = q.requiredText("data");
			LOG.logInfo(q.user() + ": DNS " + d + " delete " + name + " " + type + " " + data);
			app.getDns().deleteRecord(d, name, type, data);
			return dnsZone(q);
		});
		r.post("/dns/zones/{domain}/publish", q -> {
			String d = dnsDomain(q);
			List<Map<String, String>> changed = app.getDns().publishMail(d, mailRecords(d));
			LOG.logInfo(q.user() + ": DNS " + d + " published the mail records " + changed);
			Map<String, Object> m = dnsZone(q);
			m.put("published", changed);
			return m;
		});

		// settings
		r.get("/settings", q -> settings());
		r.put("/settings", this::updateSettings);
		r.get("/spam/providers", q -> spamProviders());
		r.post("/spam/test", this::testSpam);

		// domains
		r.get("/domains", q -> domains(q));
		r.post("/domains", q -> {
			app.getDomains().add(q.requiredText("domain"));
			useNow(true);
			return domains(q);
		});
		r.delete("/domains/{domain}", q -> {
			app.getDomains().remove(q.param(0));
			useNow(true);
			return domains(q);
		});
		r.post("/domains/{domain}/dkim", q -> {
			if (!scope(q).allowsDomain(q.param(0))) {
				throw new ApiException(403, "The domain " + q.param(0) + " isn't yours");
			}
			DomainService.DkimEntry key = app.getDomains().generateDkim(q.param(0), q.requiredText("selector"), q.text("type"));
			ownKeyRecord(key);
			useNow(true);
			return withDns(app.getDomains().describe(q.param(0)), app.getDns().servedZones());
		});

		r.post("/domains/{domain}/spam", q -> {
			if (!scope(q).allowsDomain(q.param(0))) {
				throw new ApiException(403, "The domain " + q.param(0) + " isn't yours");
			}
			app.getDomains().setSpam(q.param(0), q.requiredText("filter"));
			useNow(true);
			return withDns(app.getDomains().describe(q.param(0)), app.getDns().servedZones());
		});

		// users
		r.get("/users", q -> users(q));
		r.post("/users", q -> {
			requireAddress(q, q.requiredText("name"), "The user");
			requireMaildrop(q, q.text("maildrop"));
			Boolean active = q.bool("active");
			app.getUsers().add(q.requiredText("name"), required(q.password("password"), "password"), permissions(q.list("permissions")),
					q.text("maildrop"), q.list("groups"), active == null || active);
			LOG.logInfo(q.user() + " added the user " + q.text("name"));
			return users(q);
		});
		r.put("/users/{name}", q -> {
			requireAddress(q, q.param(0), "The user");
			requireMaildrop(q, q.text("maildrop"));
			List<String> perms = q.list("permissions");
			Boolean active = q.bool("active");
			if (Boolean.FALSE.equals(active) && q.param(0).equalsIgnoreCase(q.user())) {
				throw ApiException.badRequest("You can't turn yourself off");
			}
			app.getUsers().update(q.param(0), q.password("password"), perms == null ? null : permissions(perms), q.text("maildrop"),
					q.list("groups"), active);
			LOG.logInfo(q.user() + " changed the user " + q.param(0));
			return users(q);
		});
		r.delete("/users/{name}", q -> {
			requireAddress(q, q.param(0), "The user");
			if (q.param(0).equalsIgnoreCase(q.user())) {
				throw ApiException.badRequest("You can't delete yourself");
			}
			us.bringardner.parley.mail.admin.users.UserRecord gone = app.getUsers().get(q.param(0));
			app.getUsers().delete(q.param(0));
			LOG.logInfo(q.user() + " deleted the user " + q.param(0));
			forgetInAcls(q, gone == null ? q.param(0) : gone.getName());
			return users(q);
		});
		r.get("/users/{name}/usage", this::usage);

		// mailbox ACLs (RFC 4314), through a running mail server
		r.get("/users/{name}/mailboxes", q -> {
			requireAddress(q, q.param(0), "The user");
			return app.getMailboxAcls().describe(q.param(0));
		});
		r.post("/users/{name}/mailboxes/acl", q -> {
			requireAddress(q, q.param(0), "The user");
			String mailbox = q.requiredText("mailbox");
			String id = q.requiredText("identifier");
			try {
				String now = app.getMailboxAcls().set(q.param(0), mailbox, id, q.flag("negative"), q.text("rights"));
				LOG.logInfo(q.user() + " set " + (q.flag("negative") ? "-" : "") + id + " " + now + " on " + q.param(0) + "'s " + mailbox);
			} catch (IOException e) {
				throw ApiException.badRequest(e.getMessage());
			}
			return app.getMailboxAcls().describe(q.param(0));
		});
		r.post("/users/{name}/mailboxes/acl/delete", q -> {
			requireAddress(q, q.param(0), "The user");
			String mailbox = q.requiredText("mailbox");
			String id = q.requiredText("identifier");
			try {
				if (app.getMailboxAcls().delete(q.param(0), mailbox, id, q.flag("negative"))) {
					LOG.logInfo(q.user() + " removed " + (q.flag("negative") ? "-" : "") + id + " from " + q.param(0) + "'s " + mailbox);
				}
			} catch (IOException e) {
				throw ApiException.badRequest(e.getMessage());
			}
			return app.getMailboxAcls().describe(q.param(0));
		});

		// aliases
		r.get("/aliases", q -> aliases(q));
		r.put("/aliases/{name}", q -> {
			requireAddress(q, q.param(0), "The alias");
			app.getAliases().put(q.param(0), required(q.list("members"), "members"));
			app.queueNamesAliases();
			useNow(false);
			return aliases(q);
		});
		r.delete("/aliases/{name}", q -> {
			requireAddress(q, q.param(0), "The alias");
			if (!app.getAliases().delete(q.param(0))) {
				throw ApiException.notFound("The alias " + q.param(0));
			}
			useNow(false);
			return aliases(q);
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
			us.bringardner.parley.files.FileSource f = app.getLogs().getFile();
			if (f == null || !f.isFile()) {
				throw ApiException.notFound("The log file");
			}
			return new Router.Download(f, "parley-mail.log", "text/plain; charset=UTF-8");
		});
		r.get("/logs/files", q -> app.getLogFiles().describe());
		r.get("/logs/files/read", q -> new Router.Text(LogFiles.tail(logFile(q), (int) q.queryLong("bytes", 256 * 1024))));
		r.get("/logs/files/download", q -> {
			us.bringardner.parley.files.FileSource f = logFile(q);
			return new Router.Download(f, f.getName().replaceAll("[^A-Za-z0-9._-]", "_"), "text/plain; charset=UTF-8");
		});

		// SMTP queue
		r.get("/queue", q -> queue(q));
		r.post("/queue/flush", q -> {
			requireQueue().flush();
			LOG.logInfo(q.user() + " flushed the SMTP queue");
			return queue(q);
		});
		r.post("/queue/{id}/retry", q -> {
			requireQueuedMessage(q, q.param(0));
			if (!requireQueue().retry(q.param(0))) {
				throw ApiException.notFound("The message " + q.param(0));
			}
			return queue(q);
		});
		r.delete("/queue/{id}", q -> {
			MailQueueMXBean mq = requireQueue();
			requireQueuedMessage(q, q.param(0));
			if (!mq.delete(q.param(0))) {
				throw new ApiException(409, "The message is being delivered right now; try again in a moment");
			}
			LOG.logInfo(q.user() + " deleted " + q.param(0) + " from the SMTP queue");
			return queue(q);
		});
		r.get("/queue/{id}/message", q -> {
			if (!scope(q).isPrimary()) {
				requireQueuedMessage(q, q.param(0));
			}
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
			LocalDate from = to.minusDays(Math.max(0, Math.min(HistoryStore.MAX_DAYS, q.queryLong("days", 30))));
			List<Map<String, Object>> ret = new ArrayList<>();
			AdminScope scope = scope(q);
			for (Map<String, Object> m : app.getHistory().query(from, to, q.param(0), null, 10000)) {
				if (q.param(0).equals(m.get("id")) && isMine(scope, m)) {
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
		AdminScope scope = user == null ? null : app.scopeOf(user);
		m.put("primary", scope != null && scope.isPrimary());
		m.put("adminDomain", scope == null ? null : scope.getDomain());
		m.put("setupRequired", !app.getUsers().hasAdmin());
		m.put("version", AdminApp.version());
		List<String> domains = new ArrayList<>();
		for (String d : app.getDomains().list()) {
			if (scope == null || scope.allowsDomain(d)) {
				domains.add(d);
			}
		}
		m.put("domains", domains);
		return m;
	}

	private Object login(Router.Request q) throws IOException {
		String ip = q.http.getRemoteAddr();
		long now = System.currentTimeMillis();
		if (failures.size() > 10000) {
			failures.values().removeIf(v -> now - v[1] > FAILURE_WINDOW);
		}
		// count the attempt before checking the password, so parallel requests can't get past the limit
		long[] f = failures.compute(ip, (k, v) -> v == null || now - v[1] > FAILURE_WINDOW ? new long[] { 1, now } : new long[] { v[0] + 1, v[1] });
		if (f[0] > MAX_FAILURES) {
			throw new ApiException(429, "Too many failed logins; try again later");
		}
		String name = q.requiredText("user");
		char[] password = q.password("password");
		// the users file's registry checks the password and answers whether the user holds admin
		AuthenticationResult result;
		try {
			result = app.authenticateAdmin(name, password, requester(q.http));
		} finally {
			if (password != null) {
				Arrays.fill(password, '\0');
			}
		}
		if (result.getStatus() == AuthenticationResult.Status.UNAVAILABLE) {
			// the registry couldn't answer: not a failed login, and never "denied"
			failures.computeIfPresent(ip, (k, v) -> v[0] <= 1 ? null : new long[] { v[0] - 1, v[1] });
			LOG.logError("Can't check the admin login of '" + printable(name) + "' from " + ip + ": " + result.getReason());
			throw new ApiException(503, "Logins can't be checked now; try again later");
		}
		UserRecord u = result.getStatus() == AuthenticationResult.Status.AUTHENTICATED
				? app.getUsers().get(result.getPrincipal().getName()) : null;
		if (u == null || AdminScope.of(u, app.getPrimaryDomain()) == null) {
			LOG.logWarn("Failed admin login for '" + printable(name) + "' from " + ip
					+ (result.getReason() == null ? "" : ": " + printable(result.getReason())));
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			// the same answer either way: this page doesn't confirm a mail user's password
			throw new ApiException(401, "Wrong user name or password, or not an administrator");
		}
		failures.remove(ip);
		AdminFilter.login(q.http, u.getName());
		LOG.logInfo(u.getName() + " logged in to the admin pages from " + ip);
		return session(q);
	}

	/** Who is logging in, for the user registry: the browser's address and whether the connection is secure */
	private static Requester requester(HttpServletRequest req) {
		String address = req.getRemoteAddr();
		try {
			if (address == null || address.isEmpty()) {
				throw new java.net.UnknownHostException("no address");
			}
			// an address literal: no name lookup
			java.net.InetAddress client = java.net.InetAddress.getByName(address);
			return Requester.connected(client, req.getLocalPort(), req.isSecure(), Requester.Transport.TCP, AdminApp.LOGIN_SERVER_NAME);
		} catch (java.net.UnknownHostException | RuntimeException e) {
			return Requester.withoutAddress(AdminApp.LOGIN_SERVER_NAME).withSecure(req.isSecure());
		}
	}

	/** Text for the log: no control characters (no forged log lines), at most 100 characters. */
	private static String printable(String s) {
		String t = s.replaceAll("\\p{Cntrl}", "?");
		return t.length() > 100 ? t.substring(0, 100) + "..." : t;
	}

	/**
	 * A domain's own DKIM key goes in its own zone when Parley DNS serves the domain: a record the
	 * shared zone doesn't give it, so the zone gets a file (the primary's key is the shared one, whose
	 * record the shared zone serves for the others).
	 *
	 * @throws ApiException if DNS refused it: the key is made, and its record is on the DNS records list
	 */
	private void ownKeyRecord(DomainService.DkimEntry key) {
		if (app.getDns().server() == null || !app.getDns().servedZones().contains(key.domain)) {
			return;
		}
		String txt = app.getDomains().dkimRecord(key);
		try {
			app.getDns().setRecords(key.domain, key.selector + "._domainkey", "TXT", 3600, java.util.Collections.singletonList(txt));
		} catch (Exception e) {
			throw new ApiException(500, "The key is made, but Parley DNS refused its record (" + MailSuite.message(e)
					+ "): add it on the DNS page");
		}
	}

	/**
	 * The running servers use a domain, alias or DKIM change at once, with no restart: the SMTP servers
	 * read their queue's settings again and, for a domain or DKIM change, the Parley DNS server (when
	 * one is managed here) its zones, so a mail domain and its DKIM record are served now.
	 *
	 * @throws ApiException if one couldn't: the change is saved, and a restart uses it
	 */
	private void useNow(boolean dns) {
		List<String> errors = new ArrayList<>();
		try {
			app.getSuite().reloadQueue();
		} catch (IOException | RuntimeException e) {
			errors.add("SMTP: " + MailSuite.message(e));
		}
		if (dns && app.getDns().server() != null) {
			try {
				app.getDns().reload();
			} catch (Exception e) {
				errors.add("DNS: " + MailSuite.message(e));
			}
		}
		if (!errors.isEmpty()) {
			LOG.logError("A change was saved but the running servers couldn't use it: " + String.join("; ", errors));
			throw new ApiException(500, "Saved, but the running servers couldn't use it yet (a restart will): " + String.join("; ", errors));
		}
	}

	/** Make the first administrator (only while there is none). */
	private Object setup(Router.Request q) throws IOException {
		synchronized (this) {
			if (app.getUsers().hasAdmin()) {
				throw new ApiException(409, "An administrator already exists; log in");
			}
			String name = q.requiredText("user").trim();
			char[] password = required(q.password("password"), "password");
			// the first administrator's domain becomes a local domain (there's no page to add it before logging in)
			int at = name.lastIndexOf('@');
			if (at <= 0 || at == name.length() - 1) {
				throw ApiException.badRequest("The first administrator's name needs a domain, like admin@example.com: that domain becomes the primary domain");
			}
			boolean added = false;
			if (at > 0 && !app.getDomains().isLocal(name.substring(at + 1))) {
				app.getDomains().add(name.substring(at + 1));
				added = true;
			}
			try {
				app.getUsers().add(name, password,
						Arrays.asList(UserRecord.READ, UserRecord.WRITE, UserRecord.ADMIN), q.text("maildrop"));
			} catch (RuntimeException e) {
				if (added) {
					app.getDomains().remove(name.substring(at + 1)); // as it was
				}
				throw e;
			}
			if (added) {
				LOG.logInfo("Added the local domain " + name.substring(at + 1) + " for the first administrator");
			}
			UserRecord u = app.getUsers().get(name);
			app.setPrimaryDomain(u.getDomain());
			AdminFilter.login(q.http, u.getName());
			LOG.logInfo("Created the first administrator " + u.getName() + " from " + q.http.getRemoteAddr());
		}
		return session(q);
	}

	// ------------------------------------------------------------------ status and services

	/** The canonical path of a folder, as the status shows it (its own path if that can't be worked out). */
	private static String canonical(us.bringardner.parley.files.FileSource dir) {
		try {
			return dir.getCanonicalPath();
		} catch (IOException e) {
			return dir.getPath();
		}
	}

	private Object status() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", AdminApp.version());
		m.put("startedAt", app.getStartedAt());
		m.put("now", System.currentTimeMillis());
		// (the canonical path has / on every platform, where getPath is the native one: \ on Windows)
		m.put("home", canonical(app.getHome().getDirectory()));
		m.put("settingsDirectory", canonical(app.getSettings().getDirectory()));
		m.put("mailRoot", app.getSettings().getEffective(SettingsCatalog.ROOT));
		m.put("hostname", app.getSettings().getEffective(SettingsCatalog.QUEUE_HOSTNAME));
		m.put("assets", assets(app.getSettings()));
		MailSuite suite = app.getSuite();
		m.put("local", suite.isLocal());
		m.put("launchers", suite.describeTargets());
		m.put("connectError", suite.getConnectError());
		m.put("services", services());
		MailQueueMXBean q = suite.getQueue();
		Map<String, Object> queue = new LinkedHashMap<>();
		try {
			queue.put("running", q != null && q.isRunning());
			queue.put("messages", q == null ? 0 : q.getMessageCount());
		} catch (RuntimeException e) {
			queue.put("running", false);
			queue.put("messages", 0);
		}
		m.put("queue", queue);
		us.bringardner.parley.files.FileSource history = app.historyDirectory();
		m.put("historyDirectory", history == null ? null : history.getAbsolutePath());
		m.put("domains", app.getDomains().list());
		m.put("users", app.getUsers().list().size());
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		m.put("heapUsed", heap.getUsed());
		m.put("heapMax", heap.getMax());
		m.put("java", System.getProperty("java.version"));
		m.put("problems", app.getLogs().query(0, LogBuffer.Level.WARN, null, null, 8));
		return m;
	}

	private us.bringardner.parley.files.FileSource logFile(Router.Request q) {
		us.bringardner.parley.files.FileSource f = app.getLogFiles().find(q.query("path", null));
		if (f == null) {
			throw ApiException.notFound("The log file " + q.query("path", ""));
		}
		return f;
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
		LOG.logInfo(q.user() + ": " + action + " " + id.label);
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
		case "on": // parley.servers lists it, and it is started
			suite.setListed(id, true);
			break;
		case "off": // it is stopped, and parley.servers doesn't list it
			suite.setListed(id, false);
			break;
		default:
			throw ApiException.notFound("The action " + action);
		}
		return services();
	}

	private Object allServices(Router.Request q) throws IOException {
		MailSuite suite = app.getSuite();
		String action = q.param(0);
		LOG.logInfo(q.user() + ": " + action + " all services");
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
			if (!g.servers.isEmpty()) {
				List<Object> servers = new ArrayList<>();
				for (ServiceId id : g.servers) {
					Map<String, Object> sm = new LinkedHashMap<>();
					sm.put("id", id.id());
					sm.put("label", id.label);
					sm.put("prefix", id.prefix);
					servers.add(sm);
				}
				gm.put("servers", servers);
			}
			if (g.id.equals("store") || g.id.equals("queue")) {
				gm.put("asset", g.id);
			}
			List<Object> list = new ArrayList<>();
			for (SettingDef d : g.settings) {
				known.add(d.getKey());
				list.add(setting(d.getKey(), d, inFile.get(d.getKey()), s, c.isSecret(d.getKey())));
			}
			gm.put("settings", list);
			groups.add(gm);
		}
		List<Object> other = new ArrayList<>();
		for (Map.Entry<String, String> e : inFile.entrySet()) {
			if (!known.contains(e.getKey()) && !e.getKey().startsWith(AdminApp.LOG_LEVEL_PREFIX)) {
				other.add(setting(e.getKey(), null, e.getValue(), s, c.isSecret(e.getKey())));
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("directory", s.getDirectory().getPath());
		List<String> files = new ArrayList<>();
		for (us.bringardner.parley.mail.admin.settings.PropertiesFile f : s.getFiles()) {
			files.add(f.getFile().getName());
		}
		m.put("files", files);
		m.put("assets", assets(s));
		m.put("groups", groups);
		m.put("other", other);
		return m;
	}

	private static Map<String, Object> setting(String key, SettingDef d, String value, SettingsService s, boolean secret) {
		String effective = s.isSetByJvm(key) ? s.getEffective(key) : null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", key);
		us.bringardner.parley.mail.admin.settings.PropertiesFile file = s.fileFor(key);
		if (SettingsCatalog.isAssetKey(key)) {
			m.put("name", key.substring(key.indexOf('.') + 1)); // its name in that file
			m.put("file", file == null ? null : file.getPath());
		} else {
			m.put("file", file.getName());
		}
		if (d != null && d.getServer() != null) {
			m.put("server", d.getServer());
		}
		if (d != null) {
			m.put("label", d.getLabel());
			m.put("type", d.getType().name());
			m.put("default", d.getDefaultValue());
			m.put("description", d.getDescription());
			if (!d.getOptions().isEmpty()) {
				m.put("options", d.getOptions());
				if (!d.getOptionLabels().isEmpty()) {
					m.put("optionLabels", d.getOptionLabels());
				}
				if (!d.getOptionDescriptions().isEmpty()) {
					m.put("optionDescriptions", d.getOptionDescriptions());
				}
			}
			if (d.getShowWhenKey() != null) {
				m.put("showWhenKey", d.getShowWhenKey());
				m.put("showWhenValue", d.getShowWhenValue());
			}
			if (d.getHideWhenKey() != null) {
				m.put("hideWhenKey", d.getHideWhenKey());
				m.put("hideWhenValue", d.getHideWhenValue());
			}
			if (d.isAdvanced()) {
				m.put("advanced", true);
			}
		} else {
			m.put("type", secret ? "SECRET" : "TEXT");
		}
		if (secret) {
			m.put("set", value != null && !value.isEmpty());
		} else {
			m.put("value", value);
			if (effective != null && !effective.equals(value == null ? null : value.trim())) {
				m.put("effective", effective); // set outside the files (-D)
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

	/** The store's and the queue's own settings files: where, and whether they are there */
	private static Map<String, Object> assets(SettingsService s) {
		Map<String, Object> m = new LinkedHashMap<>();
		us.bringardner.parley.mail.admin.settings.PropertiesFile[] files = { s.getStoreFile(), s.getQueueFile() };
		String[] ids = { "store", "queue" };
		for (int i = 0; i < 2; i++) {
			Map<String, Object> a = new LinkedHashMap<>();
			if (files[i] == null) {
				a.put("path", null);
				a.put("exists", false);
				a.put("error", s.getAssetError());
			} else {
				a.put("path", files[i].getPath());
				boolean exists;
				try {
					exists = files[i].exists();
				} catch (IOException e) {
					exists = false;
					a.put("error", e.getMessage());
				}
				a.put("exists", exists);
			}
			m.put(ids[i], a);
		}
		return m;
	}

	/** The spam providers this installation has (found at run time), what each can do and its settings. */
	private Object spamProviders() {
		List<Object> list = new ArrayList<>();
		for (SpamProvider p : SpamProviders.all()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.getId());
			m.put("title", p.getTitle());
			m.put("description", p.getDescription());
			List<String> features = new ArrayList<>();
			for (SpamProvider.Feature f : p.getFeatures()) {
				features.add(f.name());
			}
			m.put("features", features);
			List<Object> settings = new ArrayList<>();
			for (ConnectionSetting cs : p.getSettings()) {
				Map<String, Object> sm = new LinkedHashMap<>();
				sm.put("key", cs.key());
				sm.put("label", cs.label());
				sm.put("kind", cs.kind().name());
				sm.put("default", cs.defaultValue());
				sm.put("description", cs.description());
				sm.put("required", cs.required());
				sm.put("advanced", cs.advanced());
				settings.add(sm);
			}
			m.put("settings", settings);
			list.add(m);
		}
		Map<String, Object> ret = new LinkedHashMap<>();
		ret.put("providers", list);
		return ret;
	}

	/**
	 * Ask a spam provider's scanner whether it is there, with the settings of the form (saved or not). A
	 * secret the form leaves empty is the saved one, since the browser is never sent them.
	 */
	private Object testSpam(Router.Request q) throws IOException {
		String id = q.requiredText("provider");
		SpamProvider p = SpamProviders.get(id);
		if (p == null) {
			throw ApiException.badRequest("There is no spam provider " + id);
		}
		JsonNode given = q.body().get("settings");
		Map<String, String> settings = new LinkedHashMap<>();
		for (ConnectionSetting cs : p.getSettings()) {
			String v = given != null && given.has(cs.key()) && !given.get(cs.key()).isNull() ? given.get(cs.key()).asText() : null;
			if ((v == null || v.isEmpty()) && cs.isSecret()) {
				v = app.getSettings().getEffective(ServiceId.SMTP.prefix + ".spam." + p.getId() + "." + cs.key());
			}
			if (v != null && !v.trim().isEmpty()) {
				settings.put(cs.key(), v.trim());
			}
		}
		SpamStatus st = SpamProviders.test(id, settings, 5000);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("available", st.isAvailable());
		m.put("version", st.getVersion());
		m.put("message", st.getMessage());
		m.put("details", st.getDetails());
		return m;
	}

	private Object updateSettings(Router.Request q) throws IOException {
		JsonNode changes = q.body().get("changes");
		if (changes == null || !changes.isObject()) {
			throw ApiException.badRequest("changes is required");
		}
		Map<String, String> map = new LinkedHashMap<>();
		changes.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
		if (map.containsKey(SettingsCatalog.ADMIN_PRIMARY_DOMAIN)) {
			map.put(SettingsCatalog.ADMIN_PRIMARY_DOMAIN, checkPrimaryDomain(map.get(SettingsCatalog.ADMIN_PRIMARY_DOMAIN)));
		}
		Set<String> changed = app.getSettings().update(map);
		LOG.logInfo(q.user() + " changed the settings " + changed);
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

	/**
	 * A new primary domain (only the current primary domain's administrators reach the Settings): a
	 * local domain with an administrator, so someone can always manage every domain and change it again.
	 */
	private String checkPrimaryDomain(String value) {
		String d = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		if (d.isEmpty()) {
			throw ApiException.badRequest("The primary domain can't be empty: its administrators manage every domain");
		}
		if (!app.getDomains().isLocal(d)) {
			throw ApiException.badRequest("The primary domain must be a local domain (Domains page): " + d + " isn't");
		}
		for (UserRecord u : app.getUsers().list()) {
			if (d.equals(u.getDomain()) && u.hasPermission(UserRecord.ADMIN)) {
				return d;
			}
		}
		throw ApiException.badRequest("The primary domain needs an administrator: " + d + " has no user with ADMIN");
	}

	// ------------------------------------------------------------------ domains, users, aliases

	private Object domains(Router.Request q) {
		AdminScope scope = scope(q);
		List<Object> list = new ArrayList<>();
		DomainService d = app.getDomains();
		java.util.Set<String> served = app.getDns().servedZones();
		for (String domain : d.list()) {
			if (!scope.allowsDomain(domain)) {
				continue;
			}
			Map<String, Object> m = withDns(d.describe(domain), served);
			m.put("primaryDomain", domain.equals(app.getPrimaryDomain()));
			list.add(m);
		}
		return list;
	}

	/** A domain's description, with whether the Parley DNS server serves it (dnsServed) */
	static Map<String, Object> withDns(Map<String, Object> m, java.util.Set<String> served) {
		Object d = m.get("domain");
		m.put("dnsServed", d != null && served.contains(String.valueOf(d).toLowerCase(java.util.Locale.ROOT)));
		return m;
	}

	/**
	 * The users this administrator may see. With {@code limit}, one page of those matching
	 * {@code domain} ({@code *} every one, empty the users without a domain, else that domain) and
	 * {@code q} (part of the name, any case), from {@code offset}: {total, offset, limit, users}.
	 * Without it, every user (the list the changes answer with).
	 */
	private Object users(Router.Request q) {
		if (q.query("limit", null) == null) {
			return userList(scope(q), "*", null);
		}
		String domain = q.query("domain", "*");
		String text = q.query("q", null);
		List<Object> all = userList(scope(q), domain, text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT));
		int limit = (int) Math.max(1, Math.min(1000, q.queryLong("limit", 100)));
		int offset = (int) Math.max(0, Math.min(all.size(), q.queryLong("offset", 0)));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("total", all.size());
		m.put("offset", offset);
		m.put("limit", limit);
		m.put("users", all.subList(offset, Math.min(all.size(), offset + limit)));
		m.put("editable", app.getUsers().isEditable());
		return m;
	}

	private List<Object> userList(AdminScope scope, String domain, String text) {
		List<Object> list = new ArrayList<>();
		for (UserRecord u : app.getUsers().list()) {
			if (!scope.allowsDomain(u.getDomain())) {
				continue;
			}
			String d = u.getDomain();
			if (!"*".equals(domain) && !(domain.isEmpty() ? d == null || d.isEmpty() : domain.equalsIgnoreCase(d))) {
				continue;
			}
			if (text != null && !u.getName().toLowerCase(Locale.ROOT).contains(text)) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", u.getName());
			m.put("domain", u.getDomain());
			m.put("permissions", u.getPermissions());
			m.put("maildrop", u.getMaildrop());
			m.put("groups", u.getGroups());
			m.put("enabled", u.isEnabled());
			m.put("hashed", u.isHashed());
			m.put("scram", u.hasScram());
			list.add(m);
		}
		return list;
	}

	/**
	 * A deleted user is taken out of the other users' mailbox ACLs and the share index; when that
	 * can't be done (no mail server runs) the page is told (X-Parley-Warning).
	 */
	private void forgetInAcls(Router.Request q, String name) {
		String warning = null;
		try {
			int n = app.getMailboxAcls().forget(name);
			if (n < 0) {
				warning = name + " is still named in mailbox ACLs: no mail server runs to remove it";
			} else if (n > 0) {
				LOG.logInfo("Removed " + n + " mailbox ACL entries of " + name);
			}
		} catch (IOException | RuntimeException e) {
			warning = "Couldn't remove " + name + " from the mailbox ACLs: " + e.getMessage();
		}
		if (warning != null) {
			LOG.logWarn(warning);
			q.header("X-Parley-Warning", warning.replaceAll("[\\r\\n]", " "));
		}
	}

	private Object usage(Router.Request q) throws IOException {
		requireAddress(q, q.param(0), "The user");
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

	private Object aliases(Router.Request q) {
		AdminScope scope = scope(q);
		List<Object> list = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : app.getAliases().list().entrySet()) {
			if (!scope.allowsAddress(e.getKey())) {
				continue;
			}
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

	private MailQueueMXBean requireQueue() {
		MailQueueMXBean q = app.getSuite().getQueue();
		if (q == null) {
			throw new ApiException(409, "The SMTP queue runs only while an SMTP service runs");
		}
		return q;
	}

	/** Refuse a queued message that is neither from nor to the administrator's domain (404 if it isn't queued). */
	private void requireQueuedMessage(Router.Request q, String id) {
		QueuedMessageInfo e;
		try {
			e = requireQueue().getMessage(id);
		} catch (RuntimeException ex) {
			throw new ApiException(503, "Can't read the SMTP queue: " + MailSuite.message(ex));
		}
		if (e == null || !isMine(scope(q), e)) {
			throw ApiException.notFound("The message " + id);
		}
	}

	/** True if the queued message is from or to one of the administrator's domains. */
	private static boolean isMine(AdminScope scope, QueuedMessageInfo e) {
		if (scope.isPrimary()) {
			return true;
		}
		List<String> addresses = new ArrayList<>();
		addresses.add(e.getFrom());
		for (QueuedRecipientInfo r : e.getRecipients()) {
			addresses.add(r.getAddress());
		}
		return scope.allowsAny(addresses);
	}

	/** True if the history event is about mail from or to one of the administrator's domains. */
	@SuppressWarnings("unchecked")
	static boolean isMine(AdminScope scope, Map<String, Object> event) {
		if (scope.isPrimary()) {
			return true;
		}
		List<String> addresses = new ArrayList<>();
		for (String k : new String[] { "from", "recipient" }) {
			Object v = event.get(k);
			if (v != null) {
				addresses.add(v.toString());
			}
		}
		Object list = event.get("recipients");
		if (list instanceof List) {
			for (Object o : (List<Object>) list) {
				if (o != null) {
					addresses.add(o.toString());
				}
			}
		}
		return scope.allowsAny(addresses);
	}

	private Object queue(Router.Request q) {
		AdminScope scope = scope(q);
		Map<String, Object> m = new LinkedHashMap<>();
		MailQueueMXBean mq = app.getSuite().getQueue();
		List<Map<String, Object>> list = new ArrayList<>();
		boolean running = false;
		if (mq != null) {
			try {
				running = mq.isRunning();
				for (QueuedMessageInfo e : mq.getMessages()) {
					if (isMine(scope, e)) {
						list.add(entry(e));
					}
				}
			} catch (RuntimeException e) {
				throw new ApiException(503, "Can't read the SMTP queue: " + MailSuite.message(e));
			}
		}
		list.sort(Comparator.comparingLong(e -> (Long) e.get("created")));
		m.put("running", running);
		m.put("entries", list);
		return m;
	}

	private static Map<String, Object> entry(QueuedMessageInfo e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", e.getId());
		m.put("created", e.getCreated());
		m.put("from", e.getFrom());
		m.put("size", e.getSize());
		m.put("submitter", e.getSubmitter());
		m.put("inbound", e.isInbound());
		m.put("quarantine", e.isQuarantine());
		boolean pending = false;
		List<Map<String, Object>> rcpts = new ArrayList<>();
		for (QueuedRecipientInfo r : e.getRecipients()) {
			Map<String, Object> rm = new LinkedHashMap<>();
			boolean p = "PENDING".equals(r.getStatus());
			pending |= p;
			rm.put("address", r.getAddress());
			rm.put("status", r.getStatus());
			rm.put("attempts", r.getAttempts());
			rm.put("lastStatus", r.getLastStatus());
			rm.put("lastResult", r.getLastResult());
			rm.put("remoteMta", r.getRemoteMta());
			rm.put("nextAttempt", p ? r.getNextAttempt() : 0);
			rcpts.add(rm);
		}
		m.put("pending", pending);
		m.put("nextAttempt", e.getNextAttempt());
		m.put("recipients", rcpts);
		return m;
	}

	/**
	 * The history between two days (from, to: YYYY-MM-DD, inclusive) in the time zone {@code tz}
	 * (the viewer's, an IANA name; default this machine's), newest first. {@code latest=true}
	 * gives one row per message, its newest event.
	 */
	private Object history(Router.Request q) throws IOException {
		java.time.ZoneId zone;
		try {
			String tz = q.query("tz", null);
			zone = tz == null || tz.isEmpty() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(tz);
		} catch (java.time.DateTimeException e) {
			throw ApiException.badRequest("Unknown time zone " + q.query("tz", null));
		}
		LocalDate to;
		LocalDate from;
		try {
			to = LocalDate.parse(q.query("to", LocalDate.now(zone).toString()));
			from = LocalDate.parse(q.query("from", to.toString()));
		} catch (DateTimeParseException e) {
			throw ApiException.badRequest("Dates are YYYY-MM-DD");
		}
		if (to.isBefore(from)) {
			LocalDate t = from;
			from = to;
			to = t;
		}
		Set<String> types = null;
		String t = q.query("type", null);
		if (t != null && !t.isEmpty()) {
			types = new HashSet<>(Arrays.asList(t.toUpperCase(Locale.ROOT).split("\\s*,\\s*")));
		}
		int limit = (int) Math.max(1, Math.min(10000, q.queryLong("limit", 1000)));
		boolean latest = Boolean.parseBoolean(q.query("latest", "false"));
		AdminScope scope = scope(q);
		// another domain's administrator: only events about its mail
		return app.getHistory().query(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(),
				q.query("q", null), types, limit, latest, scope.isPrimary() ? null : m -> isMine(scope, m));
	}
}
