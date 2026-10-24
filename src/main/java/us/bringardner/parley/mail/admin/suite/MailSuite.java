package us.bringardner.parley.mail.admin.suite;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import us.bringardner.parley.mail.admin.settings.PropertiesFile;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ParleyConnection;
import us.bringardner.parley.net.server.ParleyLauncherMBean;
import us.bringardner.parley.net.server.ParleyServerMBean;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;
import us.bringardner.parley.smtp.queue.MailQueueMXBean;

/**
 * Manages the mail suite's servers through JMX: parley-net's {@link ParleyServerMBean} for each
 * server (state, start, stop, restart, connections) and parley-smtp's {@link MailQueueMXBean} for
 * the SMTP queue. The servers run either
 * <ol>
 * <li>in this application (local): a parley-net {@link ServerLauncher} in this JVM runs every
 * {@link ServiceId}, reading the settings folder's files; or</li>
 * <li>in launcher processes run as services (remote): {@value SettingsCatalog#ADMIN_JMX_URL} lists
 * their JMX service URLs, and each server is managed where it runs.</li>
 * </ol>
 * The servers read the settings files themselves; a start (or restart) reads them again first. A
 * running server whose settings changed since it started says so ({@link #isRestartNeeded}). The
 * SMTP servers of a process share one queue, made with the SMTP settings of the moment the first
 * of them starts, so restarting an SMTP server after an SMTP setting changed restarts every running
 * SMTP server of that process.
 */
public final class MailSuite implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(MailSuite.class.getName());
	/** The local launcher's name (its JMX bean is us.bringardner.parley:type=Launcher,name=ParleyMailAdmin). */
	public static final String LOCAL_NAME = "ParleyMailAdmin";
	/** How long to wait for a remote launcher to answer when connecting. */
	static final long CONNECT_TIMEOUT = 10000;
	/** Least time between two attempts to reach remote launchers that couldn't be reached. */
	static final long RETRY_INTERVAL = 10000;

	public enum State {
		STOPPED, RUNNING, FAILED,
		/** turned off by its settings (its port is 0) */
		OFF,
		/** no launcher that could be reached runs it */
		UNAVAILABLE
	}

	/** Where some servers run: the local launcher, or one remote launcher process. */
	private static final class Target {
		/** the JMX service URL, or null for this application */
		final String url;
		final JMXConnector connector;
		final MBeanServerConnection mbeans;
		final ServerLauncher local;
		volatile boolean broken;

		Target(String url, JMXConnector connector, MBeanServerConnection mbeans, ServerLauncher local) {
			this.url = url;
			this.connector = connector;
			this.mbeans = mbeans;
			this.local = local;
		}

		String where() {
			return url == null ? "this application" : url;
		}

		ParleyLauncherMBean launcher(String serverId) throws IOException {
			if (local != null) {
				return local;
			}
			for (ObjectName n : mbeans.queryNames(name("Launcher", "*"), null)) {
				ParleyLauncherMBean l = JMX.newMBeanProxy(mbeans, n, ParleyLauncherMBean.class);
				if (serverId == null) {
					return l;
				}
				for (String s : l.getServers()) {
					if (s.equalsIgnoreCase(serverId)) {
						return l;
					}
				}
			}
			return null;
		}

		ParleyServerMBean server(String id) throws IOException {
			if (local != null) {
				for (String s : local.getServers()) {
					if (s.equals(id)) {
						return local.getServer(id);
					}
				}
				return null;
			}
			for (ObjectName n : mbeans.queryNames(name("Server", id), null)) {
				return JMX.newMBeanProxy(mbeans, n, ParleyServerMBean.class);
			}
			return null;
		}

		MailQueueMXBean queue() throws IOException {
			for (ObjectName n : mbeans.queryNames(name("MailQueue", "smtp"), null)) {
				return JMX.newMXBeanProxy(mbeans, n, MailQueueMXBean.class);
			}
			return null;
		}

		void close() {
			if (connector != null) {
				try {
					connector.close();
				} catch (IOException | RuntimeException e) {
					// gone anyway
				}
			}
		}
	}

	/** The beans' names, with or without the instance number parley-net adds when one is taken. */
	static ObjectName name(String type, String name) {
		try {
			return new ObjectName(ServerContext.JMX_DOMAIN + ":type=" + type + ",name=" + (name.equals("*") ? "*" : name) + (name.equals("*") ? "" : ",*"));
		} catch (javax.management.MalformedObjectNameException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/** What the admin knows of a running server: when it started and the settings it started with. */
	private static final class Seen {
		final long started;
		final String fingerprint;

		Seen(long started, String fingerprint) {
			this.started = started;
			this.fingerprint = fingerprint;
		}
	}

	private final SettingsService settings;
	private final List<Target> targets = new ArrayList<>();
	private final Map<ServiceId, Seen> seen = new EnumMap<>(ServiceId.class);
	/** the settings the targets were made from */
	private String targetKey;
	private String connectError;
	private long lastAttempt;
	private ServerLauncher local;
	/** the system properties there were before the local launcher read the settings files */
	private Set<String> propertiesBefore;
	private Runnable serverCreated = () -> {
	};
	private final ExecutorService connecting = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "parley-mail-admin-jmx");
		t.setDaemon(true);
		return t;
	});

	public MailSuite(SettingsService settings) {
		this.settings = settings;
	}

	/** Called after servers are started (the Logs page's levels are applied again then). */
	public void setServerCreated(Runnable r) {
		this.serverCreated = r;
	}

	// ------------------------------------------------------------------ where the servers run

	/** True when the servers run in this application (no JMX address in the settings). */
	public boolean isLocal() {
		return urls().isEmpty();
	}

	private List<String> urls() {
		List<String> ret = new ArrayList<>();
		String v = settings.getEffective(SettingsCatalog.ADMIN_JMX_URL);
		if (v != null) {
			for (String u : v.split("[,\\s]+")) {
				if (!u.isEmpty()) {
					ret.add(u);
				}
			}
		}
		return ret;
	}

	private String currentKey() {
		return String.join(",", urls()) + "\u0000" + settings.get(SettingsCatalog.ADMIN_JMX_USER, "") + "\u0000"
				+ settings.get(SettingsCatalog.ADMIN_JMX_PASSWORD, "").hashCode();
	}

	/**
	 * Make (or make again) the connections the settings ask for: the local launcher, or the remote
	 * launchers' JMX connections. Broken remote connections are made again, at most every
	 * {@value #RETRY_INTERVAL} ms.
	 */
	public synchronized void connect() {
		String key = currentKey();
		boolean broken = false;
		for (Target t : targets) {
			broken |= t.broken;
		}
		boolean same = key.equals(targetKey);
		if (same && !broken && connectError == null) {
			return;
		}
		if (same && System.currentTimeMillis() - lastAttempt < RETRY_INTERVAL) {
			return;
		}
		lastAttempt = System.currentTimeMillis();
		for (Target t : targets) {
			if (t.local == null) {
				t.close();
			}
		}
		targets.clear();
		connectError = null;
		List<String> urls = urls();
		if (urls.isEmpty()) {
			try {
				targets.add(new Target(null, null, localLauncher().getContext().getMBeanServer(), localLauncher()));
			} catch (Exception e) {
				connectError = "The servers can't run in this application: " + message(e);
				LOG.log(System.Logger.Level.ERROR, connectError, e);
			}
		} else {
			if (local != null) {
				LOG.log(System.Logger.Level.INFO, "The servers are managed over JMX now (" + String.join(", ", urls) + "); stopping those of this application");
				closeLocal();
			}
			List<String> errors = new ArrayList<>();
			for (String u : urls) {
				try {
					targets.add(remote(u));
				} catch (Exception e) {
					errors.add("Can't reach " + u + ": " + message(e));
				}
			}
			if (!errors.isEmpty()) {
				connectError = String.join("; ", errors);
				LOG.log(System.Logger.Level.WARNING, connectError);
			}
		}
		targetKey = key;
	}

	private ServerLauncher localLauncher() throws Exception {
		if (local == null) {
			propertiesBefore = new HashSet<>(System.getProperties().stringPropertyNames());
			List<String> ids = new ArrayList<>();
			for (ServiceId id : ServiceId.values()) {
				ids.add(id.id());
			}
			ServerLauncher l = new ServerLauncher(LOCAL_NAME, new LaunchSettings(new Properties()), ids, settings.getDirectory(), new ServerContext());
			try {
				l.init();
			} catch (Exception e) {
				l.close();
				clearLocalProperties();
				throw e;
			}
			local = l;
			LOG.log(System.Logger.Level.INFO, "The servers run in this application, with the settings in " + settings.getDirectory());
		}
		return local;
	}

	private Target remote(String url) throws Exception {
		JMXServiceURL u = new JMXServiceURL(url.trim());
		Map<String, Object> env = new HashMap<>();
		String user = settings.get(SettingsCatalog.ADMIN_JMX_USER, null);
		if (user != null) {
			env.put(JMXConnector.CREDENTIALS, new String[] { user, settings.get(SettingsCatalog.ADMIN_JMX_PASSWORD, "") });
		}
		Future<JMXConnector> f = connecting.submit(() -> JMXConnectorFactory.connect(u, env));
		JMXConnector c;
		try {
			c = f.get(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			f.cancel(true);
			throw new IOException("no answer in " + CONNECT_TIMEOUT / 1000 + " s");
		} catch (java.util.concurrent.ExecutionException e) {
			throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
		}
		Target t = new Target(url, c, c.getMBeanServerConnection(), null);
		if (t.launcher(null) == null) {
			t.close();
			throw new IOException("no Parley launcher there (us.bringardner.parley:type=Launcher)");
		}
		LOG.log(System.Logger.Level.INFO, "Connected to the launcher at " + url);
		return t;
	}

	private void closeLocal() {
		if (local != null) {
			try {
				local.close();
			} finally {
				local = null;
				clearLocalProperties();
				seen.clear();
			}
		}
	}

	/**
	 * Remove the system properties the local launcher set from the settings files, so a later
	 * launcher in this JVM (the application started again) doesn't take them for -D settings.
	 */
	private void clearLocalProperties() {
		if (propertiesBefore == null) {
			return;
		}
		Set<String> fromFiles = new HashSet<>();
		for (PropertiesFile f : settings.getFiles()) {
			fromFiles.addAll(f.getValues().keySet());
		}
		for (String k : System.getProperties().stringPropertyNames()) {
			if (!propertiesBefore.contains(k) && (fromFiles.contains(k) || k.startsWith("parley."))) {
				System.clearProperty(k);
			}
		}
		propertiesBefore = null;
	}

	/** Why the servers (or some of them) can't be reached, or null. */
	public synchronized String getConnectError() {
		connect();
		return connectError;
	}

	/** Where the servers run, for the dashboard: "this application" or the JMX addresses. */
	public synchronized List<Map<String, Object>> describeTargets() {
		connect();
		List<Map<String, Object>> ret = new ArrayList<>();
		for (Target t : targets) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("where", t.where());
			m.put("local", t.local != null);
			try {
				ParleyLauncherMBean l = t.launcher(null);
				List<String> files = new ArrayList<>();
				Collections.addAll(files, l.getSettingsFiles());
				m.put("settingsFiles", files);
				List<String> other = new ArrayList<>();
				for (String f : files) {
					File p = new File(f).getAbsoluteFile().getParentFile();
					if (p != null && !p.equals(settings.getDirectory().getAbsoluteFile())) {
						other.add(f);
					}
				}
				if (!other.isEmpty()) {
					m.put("warning", "It reads settings files outside " + settings.getDirectory() + ", which this application doesn't edit: " + other);
				}
			} catch (Exception e) {
				broken(t, e);
				m.put("error", message(e));
			}
			ret.add(m);
		}
		return ret;
	}

	private void broken(Target t, Exception e) {
		if (t.local == null && isConnectionProblem(e)) {
			t.broken = true;
		}
	}

	private static boolean isConnectionProblem(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof IOException || t instanceof java.rmi.RemoteException) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ the servers

	/** A server's bean and where it runs, or null when no launcher reached runs it. */
	private Object[] find(ServiceId id) {
		connect();
		for (Target t : targets) {
			if (t.broken) {
				continue;
			}
			try {
				ParleyServerMBean b = t.server(id.id());
				if (b != null) {
					return new Object[] { b, t };
				}
			} catch (Exception e) {
				broken(t, e);
			}
		}
		return null;
	}

	private ParleyServerMBean require(ServiceId id) {
		Object[] f = find(id);
		if (f == null) {
			throw new IllegalStateException(id.label + " isn't run by " + where() + (connectError != null ? " (" + connectError + ")" : ""));
		}
		return (ParleyServerMBean) f[0];
	}

	private String where() {
		List<String> u = urls();
		return u.isEmpty() ? "this application" : "the launchers at " + String.join(", ", u);
	}

	/** The service's port from the settings (0: turned off). */
	public int getConfiguredPort(ServiceId id) {
		return (int) settings.getLong(id.portProperty(), id.defaultPort);
	}

	public synchronized void start(ServiceId id) throws IOException {
		Object[] f = find(id);
		if (f == null) {
			require(id);
		}
		ParleyServerMBean b = (ParleyServerMBean) f[0];
		if (State.RUNNING.name().equals(b.getState())) {
			return;
		}
		if (getConfiguredPort(id) <= 0) {
			throw new IllegalStateException(id.label + " is turned off (its port, " + id.portProperty() + ", is 0)");
		}
		String fp = fingerprint(id);
		try {
			((Target) f[1]).launcher(id.id()).reloadSettings();
			b.start();
		} catch (Exception e) {
			String msg = message(e);
			LOG.log(System.Logger.Level.ERROR, "Can't start " + id.label + ": " + msg, e);
			throw new IOException("Can't start " + id.label + ": " + msg, e);
		} finally {
			serverCreated.run();
		}
		seen.put(id, new Seen(b.getStarted(), fp));
		LOG.log(System.Logger.Level.INFO, id.label + " is listening on port " + b.getPort());
	}

	public synchronized void stop(ServiceId id) throws IOException {
		ParleyServerMBean b = require(id);
		try {
			b.stop();
		} catch (Exception e) {
			throw new IOException("Can't stop " + id.label + ": " + message(e), e);
		}
		seen.remove(id);
		LOG.log(System.Logger.Level.INFO, id.label + " stopped");
	}

	/**
	 * Stop and start a service, with the settings as they are now. For an SMTP service whose
	 * process's queue was made with older SMTP settings, every running SMTP service of that process
	 * is restarted.
	 */
	public synchronized void restart(ServiceId id) throws IOException {
		Object[] f = find(id);
		if (f == null) {
			require(id);
		}
		Target t = (Target) f[1];
		List<ServiceId> again = new ArrayList<>();
		again.add(id);
		if (id.protocol == ServiceId.Protocol.SMTP && isRestartNeeded(id)) {
			for (ServiceId s : ServiceId.values()) {
				if (s != id && s.protocol == ServiceId.Protocol.SMTP && isRunning(s)) {
					Object[] o = find(s);
					if (o != null && o[1] == t) {
						again.add(s);
					}
				}
			}
		}
		if (again.size() == 1) {
			ParleyServerMBean b = (ParleyServerMBean) f[0];
			String fp = fingerprint(id);
			try {
				b.restart(); // reads the settings files again
			} catch (Exception e) {
				String msg = message(e);
				LOG.log(System.Logger.Level.ERROR, "Can't restart " + id.label + ": " + msg, e);
				throw new IOException("Can't restart " + id.label + ": " + msg, e);
			} finally {
				serverCreated.run();
			}
			seen.put(id, new Seen(b.getStarted(), fp));
			LOG.log(System.Logger.Level.INFO, id.label + " restarted");
			return;
		}
		// the queue goes with the last SMTP server: stop them all, then start them
		for (int i = again.size() - 1; i >= 0; i--) {
			stop(again.get(i));
		}
		startEach(again);
	}

	/** Start every service whose port isn't 0. */
	public synchronized void startAll() throws IOException {
		List<ServiceId> ids = new ArrayList<>();
		for (ServiceId id : ServiceId.values()) {
			if (getConfiguredPort(id) > 0 && !isRunning(id)) {
				ids.add(id);
			}
		}
		startEach(ids);
	}

	private void startEach(List<ServiceId> ids) throws IOException {
		List<String> errors = new ArrayList<>();
		for (ServiceId id : ids) {
			try {
				start(id);
			} catch (IOException | RuntimeException e) {
				errors.add(e.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			throw new IOException(String.join("; ", errors));
		}
	}

	/** Stop every running service, the last first. */
	public synchronized void stopAll() throws IOException {
		ServiceId[] ids = ServiceId.values();
		List<String> errors = new ArrayList<>();
		for (int i = ids.length - 1; i >= 0; i--) {
			if (isRunning(ids[i])) {
				try {
					stop(ids[i]);
				} catch (IOException | RuntimeException e) {
					errors.add(e.getMessage());
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new IOException(String.join("; ", errors));
		}
	}

	/** Restart the running services whose settings changed since they started. */
	public synchronized List<ServiceId> restartChanged() throws IOException {
		List<ServiceId> ids = new ArrayList<>();
		for (ServiceId id : ServiceId.values()) {
			if (isRestartNeeded(id)) {
				ids.add(id);
			}
		}
		List<String> errors = new ArrayList<>();
		for (ServiceId id : ids) {
			if (!isRestartNeeded(id)) {
				continue; // restarted with another SMTP service
			}
			try {
				restart(id);
			} catch (IOException | RuntimeException e) {
				errors.add(e.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			throw new IOException(String.join("; ", errors));
		}
		return ids;
	}

	public synchronized boolean isRunning(ServiceId id) {
		return getState(id) == State.RUNNING;
	}

	public synchronized State getState(ServiceId id) {
		Object[] f = find(id);
		if (f == null) {
			return State.UNAVAILABLE;
		}
		try {
			return State.valueOf(((ParleyServerMBean) f[0]).getState());
		} catch (RuntimeException e) {
			broken((Target) f[1], e);
			return State.UNAVAILABLE;
		}
	}

	/**
	 * True if the service runs with settings that changed since it started: those it reads from
	 * the settings files (and, for SMTP, the aliases file). A server started before this
	 * application saw it is taken to need a restart if one of those files changed after it started.
	 */
	public synchronized boolean isRestartNeeded(ServiceId id) {
		Object[] f = find(id);
		if (f == null) {
			return false;
		}
		ParleyServerMBean b = (ParleyServerMBean) f[0];
		long started;
		try {
			if (!State.RUNNING.name().equals(b.getState())) {
				seen.remove(id);
				return false;
			}
			started = b.getStarted();
		} catch (RuntimeException e) {
			broken((Target) f[1], e);
			return false;
		}
		String now = fingerprint(id);
		Seen s = seen.get(id);
		if (s == null || s.started != started) {
			s = new Seen(started, lastModified(id) > started ? "changed before it was seen" : now);
			seen.put(id, s);
		}
		return !now.equals(s.fingerprint);
	}

	/** The service's status, for the dashboard. */
	public synchronized Map<String, Object> status(ServiceId id) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id.id());
		m.put("label", id.label);
		m.put("description", id.description);
		m.put("protocol", id.protocol.name());
		m.put("secure", id.secure);
		Object[] f = find(id);
		State state = State.UNAVAILABLE;
		int port = getConfiguredPort(id);
		long started = 0;
		int connections = 0;
		String error = null;
		String where = null;
		if (f == null) {
			error = "Not run by " + where() + (connectError != null ? ": " + connectError : "");
		} else {
			ParleyServerMBean b = (ParleyServerMBean) f[0];
			where = ((Target) f[1]).where();
			try {
				state = State.valueOf(b.getState());
				if (state == State.RUNNING) {
					port = b.getPort();
					started = b.getStarted();
					connections = Math.max(0, b.getConnections());
				}
				error = b.getError();
			} catch (RuntimeException e) {
				broken((Target) f[1], e);
				state = State.UNAVAILABLE;
				error = message(e);
			}
		}
		m.put("state", state.name());
		m.put("port", port);
		m.put("enabled", getConfiguredPort(id) > 0);
		m.put("started", started);
		m.put("error", error);
		m.put("restartNeeded", state == State.RUNNING && isRestartNeeded(id));
		m.put("connections", connections);
		m.put("tls", tlsConfigured(id));
		m.put("where", where);
		return m;
	}

	/** The open connections of a service: the client's address, the user once logged in, and TLS. */
	public synchronized List<Map<String, Object>> connections(ServiceId id) {
		List<Map<String, Object>> ret = new ArrayList<>();
		Object[] f = find(id);
		if (f == null) {
			return ret;
		}
		ParleyConnection[] list;
		try {
			list = ((ParleyServerMBean) f[0]).getConnectionList();
		} catch (RuntimeException e) {
			broken((Target) f[1], e);
			throw new IllegalStateException("Can't read the connections of " + id.label + ": " + message(e), e);
		}
		for (ParleyConnection c : list == null ? new ParleyConnection[0] : list) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("remote", c.getRemote());
			m.put("user", c.getUser());
			m.put("tls", c.isSecure());
			ret.add(m);
		}
		return ret;
	}

	/** True if the service has a key store (its own or the TLS page's). */
	public boolean tlsConfigured(ServiceId id) {
		return notBlank(settings.getEffective(id.prefix + ".keyStoreName")) || notBlank(settings.getEffective(SettingsCatalog.TLS_KEY_STORE));
	}

	/** The SMTP queue's bean, while an SMTP service runs; else null. */
	public synchronized MailQueueMXBean getQueue() {
		connect();
		for (Target t : targets) {
			if (t.broken) {
				continue;
			}
			try {
				MailQueueMXBean q = t.queue();
				if (q != null) {
					return q;
				}
			} catch (Exception e) {
				broken(t, e);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ settings fingerprints

	/** When a file a service reads last changed. */
	private long lastModified(ServiceId id) {
		long t = Math.max(settings.getSharedFile().getFile().lastModified(), settings.getFile(id.protocol).getFile().lastModified());
		File aliases = aliasesFile(id);
		return aliases == null ? t : Math.max(t, aliases.lastModified());
	}

	private File aliasesFile(ServiceId id) {
		if (id.protocol != ServiceId.Protocol.SMTP) {
			return null;
		}
		String a = settings.readBy(ServiceId.Protocol.SMTP).get(SettingsCatalog.SMTP_ALIASES);
		return a == null || a.trim().isEmpty() ? null : new File(a.trim());
	}

	/** A hash of what a service reads when it starts (to tell when it needs a restart). */
	String fingerprint(ServiceId id) {
		TreeMap<String, String> relevant = new TreeMap<>();
		for (Map.Entry<String, String> e : settings.readBy(id.protocol).entrySet()) {
			if (id.isAffectedBy(e.getKey()) && !settings.isSetByJvm(e.getKey())) {
				relevant.put(e.getKey(), e.getValue().trim());
			}
		}
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (Map.Entry<String, String> e : relevant.entrySet()) {
				md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
				md.update((byte) 0);
				md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
				md.update((byte) 0);
			}
			File aliases = aliasesFile(id);
			if (aliases != null && aliases.isFile()) {
				try {
					md.update(Files.readAllBytes(aliases.toPath()));
				} catch (IOException e) {
					md.update((byte) 1);
				}
			}
			return java.util.Base64.getEncoder().encodeToString(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}

	/** The message of an error, from the cause that has one (JMX and RMI wrap them). */
	public static String message(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && (t.getMessage() == null || t instanceof UndeclaredThrowableException
				|| t instanceof java.io.UncheckedIOException || t instanceof javax.management.MBeanException
				|| t instanceof java.lang.reflect.InvocationTargetException)) {
			t = t.getCause();
		}
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}

	/** Stop the servers of this application (remote ones keep running) and close the connections. */
	@Override
	public synchronized void close() {
		for (Target t : targets) {
			if (t.local == null) {
				t.close();
			}
		}
		targets.clear();
		targetKey = null;
		closeLocal();
		connecting.shutdownNow();
	}
}
