package us.bringardner.parley.mail.admin.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.management.ObjectName;

import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ParleyConnection;
import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.plugin.Plugins;

/**
 * Runs a set of servers in this JVM, any set: one server alone, or several (the mail suite, say)
 * together. The servers are {@link ServerProvider}s found at run time, chosen by id; each is made
 * from a configuration of its own ({@link LaunchSettings#configuration(String)}), read from the
 * settings folder ({@link ServerMain#confDir()}): the launcher's file, the provider's shared files
 * ({@code parley-mail.properties}), then its own ({@code parley-pop3.properties}).
 * <p>
 * Each server can be started, stopped and restarted on its own, from code or with JMX (a
 * {@link ParleyServerMBean} for each server and a {@link ParleyLauncherMBean} for the launcher, on
 * the platform MBean server; remote access is the JVM's usual
 * {@code -Dcom.sun.management.jmxremote.*} settings). A restart reads the settings files again and
 * makes a new server from them.
 * <p>
 * One server never stops another: a server that can't start, or stops by itself, is FAILED with
 * its error, and the others go on.
 * <p>
 * {@link LauncherMain#launch} runs a launcher as a process; an application (an admin tool) can run
 * one in its own JVM.
 *
 * @since 2.0.0
 */
public final class ServerLauncher implements ParleyLauncherMBean, AutoCloseable {

	/** The ids of the servers to run, comma separated: {@code parley.servers=smtp,submission,imap,pop3} */
	public static final String SERVERS_PROPERTY = "parley.servers";

	public enum State {
		STOPPED, RUNNING, FAILED,
		/**
		 * not run: not listed in {@code parley.servers} (any more), turned off by its settings
		 * ({@link ServerProvider#enabled()}), or skipped because it can't run with them
		 * ({@link ServerProvider#unavailable(Properties)}); its error says which
		 */
		OFF
	}

	/** One server: its provider and, while it runs, the server */
	private final class Entry implements ParleyServerMBean {
		final ServerProvider provider;
		volatile ServerMain.Service service;
		volatile State state = State.STOPPED;
		volatile long started;
		volatile String error;
		/** stopped on request: not a failure */
		volatile boolean stopRequested;
		/** listed in parley.servers (always, when the launcher was given its ids) */
		volatile boolean listed = true;
		ObjectName name;

		Entry(ServerProvider provider) {
			this.provider = provider;
		}

		@Override
		public String getId() {
			return provider.id();
		}

		@Override
		public String getDescription() {
			return provider.description();
		}

		@Override
		public String getState() {
			return state.name();
		}

		@Override
		public int getPort() {
			ServerMain.Service s = service;
			return s == null ? 0 : s.port();
		}

		@Override
		public long getStarted() {
			return state == State.RUNNING ? started : 0;
		}

		@Override
		public String getError() {
			return error;
		}

		@Override
		public int getConnections() {
			ServerMain.Service s = service;
			return s == null || state != State.RUNNING ? 0 : s.connections();
		}

		@Override
		public ParleyConnection[] getConnectionList() {
			ServerMain.Service s = service;
			if (s == null || state != State.RUNNING) {
				return new ParleyConnection[0];
			}
			List<ParleyConnection> list = s.connectionList();
			return list == null ? new ParleyConnection[0] : list.toArray(new ParleyConnection[0]);
		}

		@Override
		public void start() throws Exception {
			control(() -> {
				ServerLauncher.this.start(provider.id());
				return null;
			});
		}

		@Override
		public void stop() throws Exception {
			control(() -> {
				ServerLauncher.this.stop(provider.id());
				return null;
			});
		}

		@Override
		public void restart() throws Exception {
			control(() -> {
				ServerLauncher.this.restart(provider.id());
				return null;
			});
		}
	}

	private final String name;
	private final LaunchSettings settings;
	private final ServerContext context;
	/** the servers, in the order they start (copied on write: a reload can add servers) */
	private volatile Map<String, Entry> entries = new LinkedHashMap<>();
	private final File confDir;
	/** reads parley.servers again on a reload, or null when the ids were given */
	private volatile Callable<List<String>> serverList;
	private final long createdAt = System.currentTimeMillis();
	private volatile Consumer<String> onFailure = id -> {
	};
	private volatile Runnable onShutdown = () -> {
	};
	private ObjectName launcherName;
	/** true if a provider reads the system properties, not its configuration */
	private boolean legacy;
	/**
	 * Where the beans' operations run: a thread of the launcher's own, made with it. A server
	 * started in a JMX call would otherwise make its threads in the caller's security context (an
	 * authenticated JMX client's Subject), which on JDK 21 can fail as the threads are made
	 * (Subject's ResourcesMgr: "Recursive update").
	 */
	private final ThreadPoolExecutor control;
	private volatile Thread controlThread;

	/**
	 * A launcher for these servers.
	 *
	 * @param name     for the messages and the JMX name, e.g. "Pop3Server" or "ParleyMail"
	 * @param settings the command line and the files to read (the providers' files are added)
	 * @param ids      the servers' ids, in the order they start
	 * @param confDir  the settings folder (null: no default files)
	 * @throws IllegalArgumentException if a server isn't known (no provider has that id)
	 */
	public ServerLauncher(String name, LaunchSettings settings, List<String> ids, File confDir, ServerContext context) {
		this.name = name;
		this.settings = settings;
		this.context = context;
		this.confDir = confDir;
		this.control = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
			Thread t = new Thread(r, name + "-control");
			t.setDaemon(true);
			controlThread = t;
			return t;
		});
		control.prestartAllCoreThreads(); // now, in the context of the code making the launcher
		Map<String, Entry> map = new LinkedHashMap<>();
		for (ServerProvider p : known(ids)) {
			if (!map.containsKey(p.id())) {
				Entry e = new Entry(p);
				map.put(p.id(), e);
				addFiles(e);
			}
		}
		entries = map;
	}

	/** The providers of these ids, in order; an unknown id is an error that lists the known ones */
	private static List<ServerProvider> known(List<String> ids) {
		Map<String, ServerProvider> known = providers();
		List<String> unknown = new ArrayList<>();
		List<ServerProvider> ret = new ArrayList<>();
		for (String id : ids) {
			ServerProvider p = known.get(id.trim().toLowerCase(Locale.ROOT));
			if (p == null) {
				unknown.add(id);
			} else {
				ret.add(p);
			}
		}
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown server" + (unknown.size() > 1 ? "s " : " ") + unknown + "; known: " + known.keySet());
		}
		return ret;
	}

	/** The server's settings files: the shared ones, then its own */
	private void addFiles(Entry e) {
		String id = e.provider.id();
		if (confDir != null) {
			for (String f : e.provider.sharedSettingsFiles()) {
				settings.addFile(id, new File(confDir, f), false);
			}
		}
		String named = e.provider.configProperty() == null ? null : settings.early(e.provider.configProperty());
		if (named != null) {
			settings.addFile(id, new File(named), true);
		} else if (confDir != null && e.provider.settingsFile() != null) {
			settings.addFile(id, new File(confDir, e.provider.settingsFile()), false);
		}
		if (!e.provider.usesConfiguration()) {
			legacy = true;
		}
	}

	/**
	 * Read the list of servers to run ({@code parley.servers}) again with each reload of the
	 * settings ({@link #reloadSettings()}, a restart): a server added to it can then be started
	 * (it isn't started by the reload), and one taken out of it is off once it stops. Without this,
	 * the launcher runs the servers it was made with. {@link LauncherMain} sets it when the list comes
	 * from the launcher's settings file.
	 *
	 * @param serverList the ids parley.servers lists now
	 * @since 2.0.0
	 */
	public void setServerList(Callable<List<String>> serverList) {
		this.serverList = serverList;
	}

	/** Read parley.servers again (before the settings files are read: a new server's files are added) */
	private void refreshList() throws Exception {
		Callable<List<String>> list = serverList;
		if (list == null) {
			return;
		}
		List<ServerProvider> now;
		try {
			now = known(list.call());
		} catch (IllegalArgumentException e) {
			throw new IOException(SERVERS_PROPERTY + ": " + e.getMessage(), e);
		}
		Map<String, Entry> map = new LinkedHashMap<>(entries);
		List<String> ids = new ArrayList<>();
		for (ServerProvider p : now) {
			ids.add(p.id());
			if (!map.containsKey(p.id())) {
				Entry e = new Entry(p);
				addFiles(e);
				if (launcherName != null) {
					e.name = register(e);
				}
				map.put(p.id(), e);
			}
		}
		for (Entry e : map.values()) {
			e.listed = ids.contains(e.provider.id());
		}
		entries = map;
	}

	private ObjectName register(Entry e) throws javax.management.JMException {
		// an MXBean: every attribute is an open type (the connection list is CompositeData), so
		// JConsole or a client without Parley's classes can read it
		return context.register("Server", e.provider.id(), new javax.management.StandardMBean(e, ParleyServerMBean.class, true));
	}

	/**
	 * Warn about each setting nothing will read (To-Do 2.16): a parley.* name no module on the
	 * class path lists, or a 1.x name. Only a warning: the servers start as they would.
	 */
	private void warnUnreadSettings() {
		KnownSettings.warn(name, settings.sources());
	}

	/** Read the settings files again (and, for a provider that reads them, set the system properties) */
	private void readSettings() throws Exception {
		refreshList();
		if (legacy) {
			settings.applyToSystem();
		} else {
			settings.apply();
		}
		warnUnreadSettings();
		if (launcherName != null) {
			// a server that isn't running is off, or can be started, as its settings are now
			for (Entry e : entries.values()) {
				synchronized (e) {
					if (e.state == State.RUNNING) {
						continue;
					}
					String reason = offReason(e);
					if (reason != null) {
						e.state = State.OFF;
						e.error = reason;
					} else if (e.state == State.OFF) {
						e.state = State.STOPPED;
						e.error = null;
					}
				}
			}
		}
	}

	/** The server's configuration, as the files were last read */
	public Properties getConfiguration(String id) {
		return settings.configuration(entry(id).provider.id());
	}

	/** Why the server can't be run now, or null: not listed, turned off, or can't run with its settings */
	private String offReason(Entry e) {
		if (!e.listed) {
			return "Not listed in " + SERVERS_PROPERTY;
		}
		if (!e.provider.usesConfiguration()) {
			return e.provider.enabled() ? null : "Turned off by its settings";
		}
		Properties c = settings.configuration(e.provider.id());
		if (!e.provider.enabled(c)) {
			return "Turned off by its settings";
		}
		return e.provider.unavailable(c);
	}

	/** Mark a server that isn't running off, with the reason */
	private void off(Entry e, String reason) {
		synchronized (e) {
			if (e.state != State.RUNNING) {
				e.state = State.OFF;
				e.error = reason;
			}
		}
	}

	private ServerMain.Service create(Entry e) throws Exception {
		return e.provider.usesConfiguration() ? e.provider.create(context, settings.configuration(e.provider.id()))
				: e.provider.create(context);
	}

	/** Run on the launcher's control thread (at once when already on it, or after close) */
	private <T> T control(Callable<T> task) throws Exception {
		if (Thread.currentThread() == controlThread || control.isShutdown()) {
			return task.call();
		}
		Future<T> f;
		try {
			f = control.submit(task);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			return task.call(); // closed meanwhile
		}
		try {
			return f.get();
		} catch (ExecutionException e) {
			Throwable t = e.getCause();
			if (t instanceof Exception) {
				throw (Exception) t;
			}
			if (t instanceof Error) {
				throw (Error) t;
			}
			throw e;
		}
	}

	/** Every provider found (the class path and the plugins), by id */
	public static Map<String, ServerProvider> providers() {
		Map<String, ServerProvider> ret = new LinkedHashMap<>();
		for (ServerProvider p : Plugins.providers(ServerProvider.class, e -> System.err.println("A server provider can't be loaded: " + e))) {
			ret.putIfAbsent(p.id().toLowerCase(Locale.ROOT), p);
		}
		return ret;
	}

	/** Called with a server's id when it stops by itself (not on request) */
	public void setOnFailure(Consumer<String> onFailure) {
		this.onFailure = onFailure;
	}

	/** Called by {@link #shutdown()} after the servers stopped */
	public void setOnShutdown(Runnable onShutdown) {
		this.onShutdown = onShutdown;
	}

	public ServerContext getContext() {
		return context;
	}

	public LaunchSettings getSettings() {
		return settings;
	}

	/** Read the settings and register the management beans (once, before the first start). */
	public synchronized void init() throws Exception {
		if (launcherName != null) {
			return;
		}
		readSettings();
		for (Entry e : entries.values()) {
			String reason = offReason(e);
			if (reason != null) {
				off(e, reason);
			}
		}
		launcherName = context.register("Launcher", name, this);
		for (Entry e : entries.values()) {
			e.name = register(e);
		}
	}

	// ------------------------------------------------------------------ life cycle

	private Entry entry(String id) {
		Entry e = entries.get(id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
		if (e == null) {
			throw new IllegalArgumentException("No server " + id + " here; there are " + entries.keySet());
		}
		return e;
	}

	/** Start one server, made from the settings as they are now; nothing happens if it runs. */
	public void start(String id) throws Exception {
		Entry e = entry(id);
		synchronized (e) {
			if (e.state == State.RUNNING) {
				return;
			}
			String reason = offReason(e);
			if (reason != null) {
				e.state = State.OFF;
				e.error = reason;
				throw new IOException(e.provider.description() + " (" + e.provider.id() + ") can't be started: " + reason);
			}
			if (e.provider.usesConfiguration()) {
				for (String w : e.provider.warnings(settings.configuration(e.provider.id()))) {
					System.err.println(e.provider.id() + ": " + w);
				}
			}
			ServerMain.Service s;
			try {
				s = create(e);
				s.startAndWait(ServerMain.START_TIMEOUT);
			} catch (Exception ex) {
				e.state = State.FAILED;
				e.error = ex.getMessage() != null ? ex.getMessage() : ex.toString();
				e.service = null;
				throw ex;
			}
			e.service = s;
			e.stopRequested = false;
			e.error = null;
			e.started = System.currentTimeMillis();
			e.state = State.RUNNING;
			watch(e, s);
		}
	}

	/** Notice when a server stops by itself */
	private void watch(Entry e, ServerMain.Service s) {
		Thread t = new Thread(() -> {
			try {
				s.awaitStop();
			} catch (InterruptedException ex) {
				return;
			}
			boolean failed;
			synchronized (e) {
				if (e.service != s) {
					return; // replaced (restarted)
				}
				failed = !e.stopRequested;
				if (failed) {
					e.state = State.FAILED;
					e.error = "Stopped by itself";
				} else if (e.state != State.OFF) {
					e.state = State.STOPPED; // (stop has set OFF, with the reason, for one that is off now)
				}
			}
			if (failed) {
				System.err.println(s.name() + " stopped by itself");
				onFailure.accept(e.provider.id());
			}
		}, name + "-" + e.provider.id() + "-watch");
		t.setDaemon(true);
		t.start();
	}

	/** Stop one server (on request: not a failure). */
	public void stop(String id) throws InterruptedException {
		Entry e = entry(id);
		synchronized (e) {
			ServerMain.Service s = e.service;
			e.stopRequested = true;
			if (s != null && e.state == State.RUNNING) {
				s.stop(ServerMain.STOP_TIMEOUT);
			}
			String reason = offReason(e);
			e.state = reason == null ? State.STOPPED : State.OFF;
			e.error = reason;
		}
	}

	/** Read the settings files again, then stop the server and start a new one. */
	public void restart(String id) throws Exception {
		Entry e = entry(id);
		synchronized (e) {
			readSettings();
			stop(id);
			start(id);
		}
	}

	/**
	 * Start the servers in order, except those that are off: those that can't run with their
	 * settings (a server with implicit TLS and no key store, say) are skipped with a warning. Each
	 * server stands alone: one that can't start is FAILED, with its error (its bean shows both), the
	 * error is printed, and the others are started and keep running. Nothing is thrown for a server
	 * that can't start; {@link #isAnyRunning()} says whether one runs.
	 */
	public void startAll() throws Exception {
		control(() -> {
			startAllNow();
			return null;
		});
	}

	private void startAllNow() throws Exception {
		for (Entry e : entries.values()) {
			String reason = offReason(e);
			if (reason != null) {
				if (e.state != State.RUNNING) {
					off(e, reason);
					System.err.println("Warning: " + e.provider.description() + " (" + e.provider.id() + ") is skipped: " + reason);
				}
				continue;
			}
			try {
				start(e.provider.id());
			} catch (Exception ex) {
				// (start has marked it FAILED, with the error): the others are not affected
				System.err.println(e.provider.description() + " (" + e.provider.id() + ") can't start: "
						+ (ex.getMessage() != null ? ex.getMessage() : ex));
				ex.printStackTrace();
			}
		}
	}

	/** Stop the servers, the last started first. */
	@Override
	public void stopAll() throws InterruptedException {
		try {
			control(() -> {
				stopAllNow();
				return null;
			});
		} catch (InterruptedException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void stopAllNow() throws InterruptedException {
		List<Entry> list = new ArrayList<>(entries.values());
		Collections.reverse(list);
		for (Entry e : list) {
			try {
				stop(e.provider.id());
			} catch (RuntimeException ex) {
				System.err.println("Can't stop " + e.provider.id() + ": " + ex);
			}
		}
	}

	@Override
	public void reloadSettings() throws IOException {
		// (a server added to parley.servers is known from here on, stopped: start it to run it)
		try {
			control(() -> {
				readSettings();
				return null;
			});
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	@Override
	public void shutdown() {
		try {
			control(() -> {
				try {
					stopAllNow();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				onShutdown.run();
				return null;
			});
		} catch (Exception e) {
			System.err.println("Can't shut down " + name + ": " + e);
		}
	}

	/** True while one of the servers runs */
	public boolean isAnyRunning() {
		for (Entry e : entries.values()) {
			if (e.state == State.RUNNING) {
				return true;
			}
		}
		return false;
	}

	public State getState(String id) {
		return entry(id).state;
	}

	/** The server's bean (its status), for an application in this JVM */
	public ParleyServerMBean getServer(String id) {
		return entry(id);
	}

	/** Stop the servers and let go of the beans and shared objects. */
	@Override
	public void close() {
		try {
			stopAll();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		context.close();
		launcherName = null;
		control.shutdown();
	}

	// ------------------------------------------------------------------ the launcher's bean

	@Override
	public String[] getServers() {
		return entries.keySet().toArray(new String[0]);
	}

	@Override
	public String[] getSettingsFiles() {
		List<String> ret = new ArrayList<>();
		for (File f : settings.getFiles()) {
			ret.add(f.getPath());
		}
		return ret.toArray(new String[0]);
	}

	@Override
	public long getStarted() {
		return createdAt;
	}
}
