package us.bringardner.parley.mail.admin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.ILogger;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.BasicParleyPermission;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.PasswordCredential;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StandardPermissions;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.domains.DomainService;
import us.bringardner.parley.mail.admin.history.HistoryStore;
import us.bringardner.parley.mail.admin.log.AdminLogger;
import us.bringardner.parley.mail.admin.log.JulCapture;
import us.bringardner.parley.mail.admin.log.LogBuffer;
import us.bringardner.parley.mail.admin.log.LogFiles;
import us.bringardner.parley.mail.admin.settings.PropertiesFile;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.UserService;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.smtp.queue.MailQueueMXBean;
import us.bringardner.parley.smtp.queue.QueueHistory;
import us.bringardner.parley.smtp.queue.QueuedMessageInfo;

/**
 * Everything that manages the mail suite: settings, users, aliases, domains, the servers (run in
 * this application or as services, managed with JMX: {@link MailSuite}), the logs and the SMTP
 * history. Made by the web application when it starts ({@code web.AdminContextListener}), and
 * usable without a servlet container (the tests do).
 */
public final class AdminApp implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(AdminApp.class.getName());
	/** The servers' log file in a settings file (parley-core's; 1.x's logFile) */
	static final String SERVERS_LOG_FILE = "parley.log.file";

	/** Settings that keep the Logs page's levels, e.g. parley.mail.admin.logLevel.SMTP=DEBUG. */
	public static final String LOG_LEVEL_PREFIX = "parley.mail.admin.logLevel.";
	/** The most of a queued message the Queue page shows. */
	public static final int MAX_MESSAGE_VIEW = 256 * 1024;

	private final AdminHome home;
	private final ObjectMapper json;
	private final SettingsService settings;
	private final LogBuffer logs;
	private final JulCapture jul = new JulCapture();
	private final UserService users;
	private final AliasStore aliases;
	private final DomainService domains;
	private final HistoryStore history;
	private final LogFiles logFiles;
	private final MailSuite suite;
	private final us.bringardner.parley.mail.admin.dns.DnsAdmin dns;
	private final ScheduledExecutorService timer;
	private final long startedAt = System.currentTimeMillis();
	/** The last history folder the SMTP queue named (it's known only while SMTP runs). */
	private volatile FileSource lastHistoryDirectory;

	private AdminApp(File homeDir, File settingsDir) throws IOException {
		// before any Parley object logs: the Parley loggers write to the Logs page too
		if (System.getProperty(BaseObject.PROPERTY_LOGGER) == null) {
			System.setProperty(BaseObject.PROPERTY_LOGGER, AdminLogger.class.getName());
		}
		home = new AdminHome(homeDir, settingsDir);
		home.createMissingFiles();
		json = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		logs = new LogBuffer(5000);
		LogBuffer.install(logs);
		jul.install();

		settings = new SettingsService(home.getSettingsDirectory(), new SettingsCatalog());
		settings.load();
		logs.setCapacity((int) settings.getLong(SettingsCatalog.ADMIN_LOG_BUFFER, 5000));
		logs.setFile(new File(home.logDirectory(), "parley-mail.log"), settings.getLong(SettingsCatalog.ADMIN_LOG_FILE_SIZE, 10L * 1024 * 1024));
		LOG.log(System.Logger.Level.INFO, "Parley mail admin starting; home " + home.getDirectory() + ", settings in " + home.getSettingsDirectory());

		// the pages edit these files, so the servers must read the same ones
		if (isBlank(settings.get(SettingsCatalog.USERS))) {
			settings.set(SettingsCatalog.USERS, path(home.defaultUsersFile()));
		}
		// a queue whose settings are made here names the aliases file the Aliases page edits
		settings.setNewQueueDefaults(java.util.Collections.singletonMap("aliases", path(home.defaultAliasesFile())));
		applyLogLevels();

		// the pages use the servers' registry of the users file when they run here, else one of their own
		users = new UserService(new File(settings.get(SettingsCatalog.USERS)), this::serversRegistry);
		aliases = new AliasStore(aliasesFile());
		aliases.load();
		domains = new DomainService(settings, users, aliases, home.dkimDirectory());
		users.setDomainCheck(domains::isLocal);

		history = new HistoryStore(this::historyDirectory, json);
		logFiles = new LogFiles(this::logFileSources);
		suite = new MailSuite(settings);
		dns = new us.bringardner.parley.mail.admin.dns.DnsAdmin(suite, settings);
		suite.setServerCreated(this::applyLogLevels);
		users.addListener(this::usersChanged);
		settings.addListener(this::settingsChanged);

		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "parley-mail-admin-timer");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleWithFixedDelay(this::checkFiles, 15, 15, TimeUnit.SECONDS);
		shareKeyOfPrimary();
	}

	/**
	 * Keep the shared DKIM key the primary domain's (DomainService.shareKeyOf): every domain without
	 * a key of its own signs with it.
	 */
	private void shareKeyOfPrimary() {
		try {
			if (settings.getQueueFile() != null && domains.shareKeyOf(getPrimaryDomain())) {
				LOG.log(System.Logger.Level.INFO, "The shared DKIM key is now " + (domains.sharedDomain() == null ? "none" : domains.sharedDomain() + "'s"));
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't set the shared DKIM key: " + e.getMessage());
		}
	}

	/**
	 * Start the application with its home directory, and the settings folder
	 * {@link AdminHome#defaultSettingsDirectory} picks.
	 */
	public static AdminApp start(File homeDir) throws IOException {
		return start(homeDir, AdminHome.defaultSettingsDirectory(homeDir, null));
	}

	/**
	 * Start the application with its home directory and settings folder; the servers start too when
	 * they run in this application and the settings say so.
	 */
	public static AdminApp start(File homeDir, File settingsDir) throws IOException {
		AdminApp app = new AdminApp(homeDir, settingsDir);
		app.suite.connect();
		if (app.suite.isLocal() && app.settings.getBoolean(SettingsCatalog.ADMIN_AUTO_START, true)) {
			try {
				app.suite.startAll();
			} catch (IOException | RuntimeException e) {
				LOG.log(System.Logger.Level.ERROR, "Some services didn't start: " + e.getMessage());
			}
		}
		return app;
	}

	/** The aliases file the queue names, else the one in the admin home (a new queue's) */
	private File aliasesFile() {
		String a = settings.get(SettingsCatalog.QUEUE_ALIASES);
		return isBlank(a) ? home.defaultAliasesFile() : new File(a.trim());
	}

	/**
	 * Make the queue name the aliases file the Aliases page edits, when it names none (so the SMTP
	 * servers read the aliases made here).
	 */
	public void queueNamesAliases() throws IOException {
		if (isBlank(settings.get(SettingsCatalog.QUEUE_ALIASES)) && settings.getQueueFile() != null) {
			settings.set(SettingsCatalog.QUEUE_ALIASES, path(aliases.getFile()));
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private static String path(File f) {
		return f.getAbsolutePath().replace('\\', '/');
	}

	/**
	 * The users file changed (from the Users page or outside): servers of this application read it
	 * now rather than at their next check (parley.net.acl.reloadInterval). Servers run as services
	 * read it at their next check.
	 */
	private void usersChanged() {
		try {
			UserRegistry registry = serversRegistry(users.getFile().getCanonicalFile());
			if (registry instanceof FileUserRegistry) {
				((FileUserRegistry) registry).reloadNow(); // a no-op when the pages just wrote through it
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "The servers can't read the users file " + users.getFile() + "; they keep the users they had", e);
		}
		FileUserRegistry own = users.ownRegistry();
		if (own != null) {
			own.reloadNow(); // the pages' own registry, when the change went through the servers'
		}
	}

	/**
	 * The registry the servers of this application share for this users file (its canonical path),
	 * or null when they run elsewhere or none of them has read it yet.
	 */
	private UserRegistry serversRegistry(File usersFile) {
		us.bringardner.parley.mail.admin.server.ServerContext context = suite != null && suite.isLocal() ? suite.localContext() : null;
		if (context == null) {
			return null;
		}
		try {
			return us.bringardner.parley.mail.admin.server.SharedUsers.find(context, usersFile);
		} catch (IOException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't find the servers' registry of the users file " + usersFile + ": " + e.getMessage());
			return null;
		}
	}

	/** The permission an administrator of the admin pages holds */
	public static final ParleyPermission ADMIN_PERMISSION = new BasicParleyPermission(StandardPermissions.ADMIN, null);

	/** The server name the admin pages' logins give the user registry ({@link Requester#getServerName()}) */
	public static final String LOGIN_SERVER_NAME = "parley-mail-admin";

	/**
	 * Log in to the admin pages: the users' registry (the one the pages use, {@link UserService#registry()})
	 * checks the password, then answers whether the user holds the {@code admin} permission. Mail
	 * users holding it are the administrators; a turned-off user can't log in.
	 *
	 * @param requester who is asking (the browser's address, whether the connection is secure)
	 * @return AUTHENTICATED with the registry's principal when the password is right and the user is
	 *         an administrator; REFUSED for a wrong password, an unknown user or one without
	 *         {@code admin}; UNAVAILABLE when the registry can't answer now (the file can't be read),
	 *         never a refusal
	 */
	public AuthenticationResult authenticateAdmin(String name, char[] password, Requester requester) {
		UserRegistry registry;
		try {
			registry = users.registry();
		} catch (IOException e) {
			return AuthenticationResult.unavailable("The users file " + users.getFile() + " can't be read: " + e.getMessage());
		}
		PasswordCredential credential = new PasswordCredential(name, password == null ? new char[0] : password, requester);
		AuthenticationResult result;
		try {
			result = registry.authenticate(credential);
		} finally {
			credential.clear();
		}
		if (result.getStatus() != AuthenticationResult.Status.AUTHENTICATED) {
			return result;
		}
		AuthorizationResult admin = registry.checkPermission(requester.loggedIn(result), ADMIN_PERMISSION, null);
		switch (admin.getStatus()) {
			case ALLOWED:
				return result;
			case UNAVAILABLE:
				return AuthenticationResult.unavailable(admin.getReason());
			default:
				return AuthenticationResult.refused(result.getPrincipal().getName() + " isn't an administrator: " + admin.getReason());
		}
	}

	/** Read the users and settings files again when they were changed outside this application. */
	private void checkFiles() {
		try {
			if (users.reloadIfChanged()) {
				LOG.log(System.Logger.Level.INFO, "Read the users file again: it was changed outside this application");
			}
		} catch (RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't read the users file " + users.getFile(), e);
		}
		try {
			if (settings.reloadIfChanged()) {
				LOG.log(System.Logger.Level.INFO, "Read the settings files again: one was changed outside this application");
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't read the settings files in " + settings.getDirectory(), e);
		}
	}

	private void settingsChanged(Set<String> changed) {
		try {
			if (changed.contains(SettingsCatalog.USERS) && !isBlank(settings.get(SettingsCatalog.USERS))) {
				users.setFile(new File(settings.get(SettingsCatalog.USERS)));
			}
			if (changed.contains(SettingsCatalog.ADMIN_JMX_URL) || changed.contains(SettingsCatalog.ADMIN_JMX_USER)
					|| changed.contains(SettingsCatalog.ADMIN_JMX_PASSWORD) || changed.contains(SettingsCatalog.ADMIN_JMX_PASSWORD_FILE)) {
				suite.connect(); // to the launchers named now (or the servers of this application)
			}
			if (changed.contains(SettingsCatalog.QUEUE_ALIASES) || changed.contains(SettingsCatalog.ROOT) || changed.contains(SettingsCatalog.FILE_SOURCE)
					|| changed.contains(SettingsCatalog.SMTP_QUEUE)) {
				File f = aliasesFile();
				if (!f.equals(aliases.getFile())) {
					aliases.setFile(f);
				}
			}
			if (changed.contains(SettingsCatalog.ADMIN_PRIMARY_DOMAIN) || changed.contains(SettingsCatalog.QUEUE_DKIM_KEYS)
					|| changed.contains(SettingsCatalog.QUEUE_DKIM_SHARED) || changed.contains(SettingsCatalog.QUEUE_DOMAINS)) {
				shareKeyOfPrimary();
			}
			if (changed.contains(SettingsCatalog.ADMIN_LOG_BUFFER)) {
				logs.setCapacity((int) settings.getLong(SettingsCatalog.ADMIN_LOG_BUFFER, 5000));
			}
			for (String k : changed) {
				if (k.startsWith(LOG_LEVEL_PREFIX)) {
					applyLogLevels();
					break;
				}
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.ERROR, "Can't apply the changed settings " + changed, e);
		}
	}

	// ------------------------------------------------------------------ log levels

	/** The level chosen for each source (null: the loggers' own). */
	public Map<LogBuffer.Source, LogBuffer.Level> getLogLevels() {
		Map<LogBuffer.Source, LogBuffer.Level> ret = new EnumMap<>(LogBuffer.Source.class);
		for (LogBuffer.Source s : LogBuffer.Source.values()) {
			ret.put(s, LogBuffer.Level.parse(settings.get(LOG_LEVEL_PREFIX + s.name()), null));
		}
		return ret;
	}

	/** Choose a source's level (null: back to the loggers' own); kept in the settings. */
	public void setLogLevel(LogBuffer.Source source, LogBuffer.Level level) throws IOException {
		settings.set(LOG_LEVEL_PREFIX + source.name(), level == null ? null : level.name());
		applyLogLevels();
	}

	private void applyLogLevels() {
		for (Map.Entry<LogBuffer.Source, LogBuffer.Level> e : getLogLevels().entrySet()) {
			LogBuffer.Level l = e.getValue();
			AdminLogger.setSourceLevel(e.getKey(), l == null ? null : toParley(l));
			JulCapture.setSourceLevel(e.getKey(), l);
		}
	}

	private static ILogger.Level toParley(LogBuffer.Level l) {
		switch (l) {
		case TRACE:
		case DEBUG:
			return ILogger.Level.DEBUG;
		case INFO:
			return ILogger.Level.INFO;
		case WARN:
			return ILogger.Level.WARN;
		default:
			return ILogger.Level.ERROR;
		}
	}

	// ------------------------------------------------------------------ queue and mailboxes

	/** The start of a message in the SMTP queue (at most {@link #MAX_MESSAGE_VIEW} bytes), or null. */
	public String readQueuedMessage(String id) throws IOException {
		MailQueueMXBean q = suite.getQueue();
		if (q == null) {
			return null;
		}
		QueuedMessageInfo info = q.getMessage(id);
		String text = info == null ? null : q.readContent(id, MAX_MESSAGE_VIEW);
		if (text == null) {
			return null;
		}
		if (info.getSize() > MAX_MESSAGE_VIEW) {
			text += "\r\n\r\n[... " + (info.getSize() - MAX_MESSAGE_VIEW) + " more bytes not shown]";
		}
		return text;
	}

	// ------------------------------------------------------------------ history and log files

	/**
	 * The SMTP history's folder: the one the queue names while an SMTP server runs (and the last one
	 * it named), else the one the queue's settings give (parley-smtp's QueueHistory: its history folder
	 * setting, else .smtp-history in the mail root); null when there's none. It is on the file system the
	 * queue's historyFileSource setting names, else on the mail root's, as the servers' is, so it works
	 * for a store on any file system and for a history kept on local disk beside a store in a database.
	 */
	public FileSource historyDirectory() {
		try {
			MailQueueMXBean q = suite.getQueue();
			String d = q == null ? null : q.getHistoryDirectory();
			if (d != null) {
				FileSource f = onMailRoot(d);
				if (f != null) {
					lastHistoryDirectory = f;
					return f;
				}
			}
		} catch (RuntimeException e) {
			// the launcher went away: the settings say where it is
		}
		if (lastHistoryDirectory != null) {
			return lastHistoryDirectory;
		}
		if (!settings.getBoolean(SettingsCatalog.QUEUE_HISTORY, true)) {
			return null;
		}
		// (no folder: the default, which is in the mail root; null when there's none or it can't be reached now)
		return onMailRoot(settings.get(SettingsCatalog.QUEUE_HISTORY_DIRECTORY, null));
	}

	/**
	 * A path on the history's file system (as QueueHistory takes its folder setting: the one
	 * historyFileSource names, else the mail root's), or null; no path is the default folder in the mail root
	 */
	private FileSource onMailRoot(String path) {
		try {
			return QueueHistory.folder(settings.getMailRoot(),
					settings.get(SettingsCatalog.QUEUE_HISTORY_FILE_SOURCE, null), path);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The log files the Logs page shows: this application's, the servers' ({@code parley.log.file} in a
	 * settings file, unless it's System.out or System.err) and those
	 * {@value SettingsCatalog#ADMIN_LOG_FILES} names.
	 */
	private List<File> logFileSources() {
		List<File> ret = new ArrayList<>();
		if (logs.getFile() != null) {
			ret.add(logs.getFile());
		}
		for (PropertiesFile f : settings.getFiles()) {
			String v = f.get(SERVERS_LOG_FILE);
			if (!isBlank(v) && !v.trim().equals("System.out") && !v.trim().equals("System.err")) {
				ret.add(new File(v.trim()));
			}
		}
		String more = settings.get(SettingsCatalog.ADMIN_LOG_FILES, null);
		if (more != null) {
			for (String p : more.split("\\s*,\\s*")) {
				if (!p.isEmpty()) {
					ret.add(new File(p));
				}
			}
		}
		return ret;
	}

	/**
	 * Bytes and files in a user's maildrop (every mailbox), or null if it doesn't exist yet or there
	 * is no mail root; on the mail root's file system, opened as the servers open it.
	 */
	public long[] mailboxUsage(String maildrop) throws IOException {
		if (isBlank(settings.getEffective(SettingsCatalog.ROOT))) {
			return null;
		}
		FileSource dir = settings.getMailRoot();
		for (String seg : maildrop.split("/")) {
			if (!seg.isEmpty() && !seg.equals(".") && !seg.equals("..")) {
				dir = dir.getChild(seg);
			}
		}
		if (!dir.exists()) {
			return null;
		}
		long[] total = new long[2];
		sum(dir, total, 0);
		return total;
	}

	private static void sum(FileSource dir, long[] total, int depth) throws IOException {
		FileSource[] kids = dir.listFiles();
		if (kids == null || depth > 20) {
			return;
		}
		for (FileSource f : kids) {
			if (f.isDirectory()) {
				sum(f, total, depth + 1);
			} else if (!f.getName().startsWith(".")) {
				total[0] += f.length();
				total[1]++;
			}
		}
	}

	// ------------------------------------------------------------------ accessors

	public AdminHome getHome() {
		return home;
	}

	public ObjectMapper getJson() {
		return json;
	}

	public SettingsService getSettings() {
		return settings;
	}

	public LogBuffer getLogs() {
		return logs;
	}

	/**
	 * The primary domain: the setting, or, when it is empty, the domain of the first administrator
	 * in the users file, which is then recorded in the setting (once, so it stays the same when
	 * administrators are added or removed). Null if there is neither.
	 */
	public synchronized String getPrimaryDomain() {
		String d = settings.get(SettingsCatalog.ADMIN_PRIMARY_DOMAIN);
		if (!isBlank(d)) {
			return d.trim().toLowerCase(java.util.Locale.ROOT);
		}
		for (us.bringardner.parley.mail.admin.users.UserRecord u : users.list()) {
			if (u.hasPermission(us.bringardner.parley.mail.admin.users.UserRecord.ADMIN) && u.getDomain() != null && !u.getDomain().isEmpty()) {
				setPrimaryDomain(u.getDomain());
				return u.getDomain();
			}
		}
		return null;
	}

	/** Record the primary domain (when the first administrator is made). */
	public synchronized void setPrimaryDomain(String domain) {
		try {
			settings.set(SettingsCatalog.ADMIN_PRIMARY_DOMAIN, domain); // the shared DKIM key follows (settingsChanged)
			LOG.log(System.Logger.Level.INFO, "The primary domain is " + domain + ": its administrators manage every domain");
		} catch (IOException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't record the primary domain " + domain + ": " + e.getMessage());
		}
	}

	/** What the user may manage, or null if the user administers no domain. */
	public us.bringardner.parley.mail.admin.users.AdminScope scopeOf(String user) {
		return us.bringardner.parley.mail.admin.users.AdminScope.of(user == null ? null : users.get(user), getPrimaryDomain());
	}

	public UserService getUsers() {
		return users;
	}

	public AliasStore getAliases() {
		return aliases;
	}

	public DomainService getDomains() {
		return domains;
	}

	public HistoryStore getHistory() {
		return history;
	}

	public LogFiles getLogFiles() {
		return logFiles;
	}

	/** The DNS page's work: the Parley DNS server a launcher runs, and the domains' zones */
	public us.bringardner.parley.mail.admin.dns.DnsAdmin getDns() {
		return dns;
	}

	public MailSuite getSuite() {
		return suite;
	}

	private volatile us.bringardner.parley.mail.admin.users.MailboxAcls mailboxAcls;

	/** The users' mailbox ACLs (RFC 4314), changed through a running mail server. @since 2.0.0 */
	public us.bringardner.parley.mail.admin.users.MailboxAcls getMailboxAcls() {
		us.bringardner.parley.mail.admin.users.MailboxAcls m = mailboxAcls;
		if (m == null) {
			m = new us.bringardner.parley.mail.admin.users.MailboxAcls(users, () -> suite.storeAdmin());
			mailboxAcls = m;
		}
		return m;
	}

	public long getStartedAt() {
		return startedAt;
	}

	/** The application's version (version.properties, written by the build). */
	public static String version() {
		try (InputStream in = AdminApp.class.getResourceAsStream("version.properties")) {
			java.util.Properties p = new java.util.Properties();
			if (in != null) {
				p.load(in);
			}
			String v = p.getProperty("version");
			return v == null || v.startsWith("${") ? "development" : v;
		} catch (IOException e) {
			return "development";
		}
	}

	/**
	 * Stop the services of this application (services run elsewhere keep running) and let go of
	 * everything (the web application is stopping).
	 */
	@Override
	public void close() {
		LOG.log(System.Logger.Level.INFO, "Parley mail admin stopping");
		try {
			suite.close();
		} finally {
			timer.shutdownNow();
			jul.uninstall();
			LogBuffer.install(null);
			logs.close();
		}
	}
}
