package us.bringardner.parley.mail.admin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
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
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.admin.domains.DomainService;
import us.bringardner.parley.mail.admin.history.HistoryStore;
import us.bringardner.parley.mail.admin.log.AdminLogger;
import us.bringardner.parley.mail.admin.log.JulCapture;
import us.bringardner.parley.mail.admin.log.LogBuffer;
import us.bringardner.parley.mail.admin.settings.PropertiesFile;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.MailSuite;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.SuiteAcl;
import us.bringardner.parley.mail.admin.users.UserStore;
import us.bringardner.parley.smtp.queue.MailQueue;

/**
 * The mail suite and everything that manages it: settings, users, aliases, domains, the servers,
 * the logs and the SMTP history. Made by the web application when it starts ({@code
 * web.AdminContextListener}), and usable without a servlet container (the tests do).
 */
public final class AdminApp implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(AdminApp.class.getName());
	/** Settings that keep the Logs page's levels, e.g. parley.mail.admin.logLevel.SMTP=DEBUG. */
	public static final String LOG_LEVEL_PREFIX = "parley.mail.admin.logLevel.";
	/** The most of a queued message the Queue page shows. */
	public static final int MAX_MESSAGE_VIEW = 256 * 1024;

	private final AdminHome home;
	private final ObjectMapper json;
	private final SettingsService settings;
	private final LogBuffer logs;
	private final JulCapture jul = new JulCapture();
	private final UserStore users;
	private final SuiteAcl acl;
	private final AliasStore aliases;
	private final DomainService domains;
	private final HistoryStore history;
	private final MailSuite suite;
	private final ScheduledExecutorService timer;
	private final long startedAt = System.currentTimeMillis();

	private AdminApp(File homeDir) throws IOException {
		// before any Parley object logs: the Parley loggers write to the Logs page too
		if (System.getProperty(BaseObject.PROPERTY_LOGGER) == null) {
			System.setProperty(BaseObject.PROPERTY_LOGGER, AdminLogger.class.getName());
		}
		home = new AdminHome(homeDir);
		home.createMissingFiles();
		json = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		logs = new LogBuffer(5000);
		LogBuffer.install(logs);
		jul.install();

		settings = new SettingsService(new PropertiesFile(home.settingsFile()), new SettingsCatalog());
		settings.load();
		logs.setCapacity((int) settings.getLong(SettingsCatalog.ADMIN_LOG_BUFFER, 5000));
		logs.setFile(new File(home.logDirectory(), "parley-mail.log"), settings.getLong(SettingsCatalog.ADMIN_LOG_FILE_SIZE, 10L * 1024 * 1024));
		LOG.log(System.Logger.Level.INFO, "Parley mail admin starting; home " + home.getDirectory());

		// the pages edit these files, so the servers must read the same ones
		if (isBlank(settings.get(SettingsCatalog.USERS))) {
			settings.set(SettingsCatalog.USERS, path(home.defaultUsersFile()));
		}
		if (isBlank(settings.get(SettingsCatalog.SMTP_ALIASES))) {
			settings.set(SettingsCatalog.SMTP_ALIASES, path(home.defaultAliasesFile()));
		}
		applyLogLevels();

		users = new UserStore(new File(settings.get(SettingsCatalog.USERS)));
		users.load();
		acl = new SuiteAcl(users.getFile());
		users.addListener(this::reloadAcl);
		aliases = new AliasStore(new File(settings.get(SettingsCatalog.SMTP_ALIASES)));
		aliases.load();
		domains = new DomainService(settings, users, aliases, home.dkimDirectory());
		users.setDomainCheck(domains::isLocal);

		history = new HistoryStore(home.historyDirectory(), json);
		suite = new MailSuite(settings, acl, history);
		settings.addListener(this::settingsChanged);

		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "parley-mail-admin-timer");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleWithFixedDelay(this::checkUsersFile, 15, 15, TimeUnit.SECONDS);
		timer.scheduleWithFixedDelay(() -> history.purge((int) settings.getLong(SettingsCatalog.ADMIN_HISTORY_DAYS, 90)), 1, 24 * 60, TimeUnit.MINUTES);
	}

	/** Start the application with its home directory (and the services, if the settings say so). */
	public static AdminApp start(File homeDir) throws IOException {
		AdminApp app = new AdminApp(homeDir);
		if (app.settings.getBoolean(SettingsCatalog.ADMIN_AUTO_START, true)) {
			try {
				app.suite.startAll();
			} catch (IOException | RuntimeException e) {
				LOG.log(System.Logger.Level.ERROR, "Some services didn't start: " + e.getMessage());
			}
		}
		return app;
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private static String path(File f) {
		return f.getAbsolutePath().replace('\\', '/');
	}

	private void reloadAcl() {
		try {
			acl.reload(users.getFile());
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.ERROR, "Can't read the users file " + users.getFile() + "; the servers keep the previous list", e);
		}
	}

	private void checkUsersFile() {
		try {
			if (users.reloadIfChanged()) {
				LOG.log(System.Logger.Level.INFO, "Read the users file again: it was changed outside this application");
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't read the users file " + users.getFile(), e);
		}
	}

	private void settingsChanged(Set<String> changed) {
		try {
			if (changed.contains(SettingsCatalog.USERS) && !isBlank(settings.get(SettingsCatalog.USERS))) {
				users.setFile(new File(settings.get(SettingsCatalog.USERS)));
				reloadAcl();
			}
			if (changed.contains(SettingsCatalog.SMTP_ALIASES) && !isBlank(settings.get(SettingsCatalog.SMTP_ALIASES))) {
				aliases.setFile(new File(settings.get(SettingsCatalog.SMTP_ALIASES)));
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
		MailQueue q = suite.getQueue();
		FileSource f = q == null ? null : q.getContent(id);
		if (f == null) {
			return null;
		}
		try (InputStream in = f.getInputStream()) {
			byte[] buf = in.readNBytes(MAX_MESSAGE_VIEW);
			String text = new String(buf, StandardCharsets.UTF_8);
			if (f.length() > MAX_MESSAGE_VIEW) {
				text += "\r\n\r\n[... " + (f.length() - MAX_MESSAGE_VIEW) + " more bytes not shown]";
			}
			return text;
		}
	}

	/** Bytes and files in a user's maildrop (every mailbox), or null if it doesn't exist yet. */
	public long[] mailboxUsage(String maildrop) throws IOException {
		String root = settings.getEffective(SettingsCatalog.ROOT);
		if (isBlank(root)) {
			return null;
		}
		String factoryId = settings.getEffective(SettingsCatalog.FILE_SOURCE);
		FileSourceFactory factory = isBlank(factoryId) ? FileSourceFactory.getDefaultFactory()
				: FileSourceFactory.getFileSourceFactory(factoryId.trim().toLowerCase(Locale.ROOT));
		FileSource dir = factory.createFileSource(root.trim());
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

	public UserStore getUsers() {
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

	public MailSuite getSuite() {
		return suite;
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

	/** Stop the services and let go of everything (the web application is stopping). */
	@Override
	public void close() {
		LOG.log(System.Logger.Level.INFO, "Parley mail admin stopping");
		try {
			suite.stopAll();
		} finally {
			timer.shutdownNow();
			history.close();
			jul.uninstall();
			LogBuffer.install(null);
			logs.close();
			settings.clear();
		}
	}
}
