package us.bringardner.parley.mail.admin.suite;

import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.pop3.server.Pop3Server;
import us.bringardner.parley.smtp.queue.MailQueue;
import us.bringardner.parley.smtp.queue.QueueListener;
import us.bringardner.parley.smtp.server.SmtpServer;

/**
 * Runs the mail suite's servers in this process: each {@link ServiceId} can be started, stopped
 * and restarted on its own. A server reads its settings when it is made, so every start makes a
 * new one from the current settings; a running service whose settings changed since it started
 * says so ({@link #isRestartNeeded}).
 * <p>
 * The SMTP services share one queue, made by a server that never listens (the queue owner) when
 * the first SMTP service starts and dropped when the last one stops. The queue's settings
 * (domains, aliases, DKIM keys, routing) are those of that moment, so restarting one SMTP service
 * after an SMTP setting changed restarts all the running SMTP services.
 */
public final class MailSuite {

	private static final System.Logger LOG = System.getLogger(MailSuite.class.getName());
	/** How long a start waits for the server to accept connections. */
	static final long START_TIMEOUT = 15000;

	public enum State {
		STOPPED, RUNNING, FAILED
	}

	private static final class Slot {
		Server server;
		State state = State.STOPPED;
		long started;
		String error;
		String fingerprint;
	}

	private final Map<ServiceId, Slot> slots = new EnumMap<>(ServiceId.class);
	private final SettingsService settings;
	private final IAccessControlList acl;
	private final QueueListener queueListener;
	private Runnable serverCreated = () -> {
	};
	private SmtpServer queueOwner;
	private String queueFingerprint;

	public MailSuite(SettingsService settings, IAccessControlList acl, QueueListener queueListener) {
		this.settings = settings;
		this.acl = acl;
		this.queueListener = queueListener;
		for (ServiceId id : ServiceId.values()) {
			slots.put(id, new Slot());
		}
	}

	/** Called after each server is made (the Logs page's levels are applied again then). */
	public void setServerCreated(Runnable r) {
		this.serverCreated = r;
	}

	/** The service's port from the settings (0: turned off). */
	public int getConfiguredPort(ServiceId id) {
		return (int) settings.getLong(id.portProperty(), id.standardPort);
	}

	public synchronized void start(ServiceId id) throws IOException {
		Slot s = slots.get(id);
		if (isRunning(id)) {
			return;
		}
		int port = getConfiguredPort(id);
		if (port <= 0) {
			throw new IllegalStateException(id.label + " is turned off (its port, " + id.portProperty() + ", is 0)");
		}
		Server server = null;
		try {
			server = create(id, port);
			server.startAndWait(START_TIMEOUT);
		} catch (IOException | RuntimeException e) {
			String msg = message(e);
			s.state = State.FAILED;
			s.error = msg;
			s.server = null;
			if (server != null) {
				quietStop(server);
			}
			releaseQueueOwnerIfUnused();
			LOG.log(System.Logger.Level.ERROR, "Can't start " + id.label + " on port " + port + ": " + msg, e);
			throw new IOException("Can't start " + id.label + " on port " + port + ": " + msg, e);
		}
		s.server = server;
		s.state = State.RUNNING;
		s.started = System.currentTimeMillis();
		s.error = null;
		s.fingerprint = fingerprint(id);
		LOG.log(System.Logger.Level.INFO, id.label + " is listening on port " + server.getPort());
	}

	private static String message(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && (t.getMessage() == null || t instanceof java.io.UncheckedIOException)) {
			t = t.getCause();
		}
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}

	public synchronized void stop(ServiceId id) {
		Slot s = slots.get(id);
		Server server = s.server;
		s.server = null;
		s.state = State.STOPPED;
		s.error = null;
		if (server != null) {
			quietStop(server);
			LOG.log(System.Logger.Level.INFO, id.label + " stopped");
		}
		releaseQueueOwnerIfUnused();
	}

	/**
	 * Stop and start a service. For an SMTP service whose shared queue was made with older SMTP
	 * settings, every running SMTP service is restarted.
	 */
	public synchronized void restart(ServiceId id) throws IOException {
		if (id.protocol == ServiceId.Protocol.SMTP && queueOwner != null && !fingerprint(ServiceId.SMTP).equals(queueFingerprint)) {
			List<ServiceId> again = new ArrayList<>();
			for (ServiceId s : ServiceId.values()) {
				if (s.protocol == ServiceId.Protocol.SMTP && (s == id || isRunning(s))) {
					again.add(s);
				}
			}
			for (ServiceId s : again) {
				stop(s);
			}
			startEach(again);
			return;
		}
		stop(id);
		start(id);
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

	public synchronized void stopAll() {
		ServiceId[] ids = ServiceId.values();
		for (int i = ids.length - 1; i >= 0; i--) {
			stop(ids[i]);
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
			if (isRunning(id) && !isRestartNeeded(id)) {
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
		Slot s = slots.get(id);
		return s.state == State.RUNNING && s.server != null && s.server.isRunning();
	}

	public synchronized State getState(ServiceId id) {
		Slot s = slots.get(id);
		if (s.state == State.RUNNING && (s.server == null || !s.server.isRunning())) {
			s.state = State.FAILED;
			s.error = "Stopped unexpectedly" + (s.server != null && s.server.getStartupError() != null ? ": " + message(s.server.getStartupError()) : "");
		}
		return s.state;
	}

	/**
	 * Note that something a protocol's servers read when they start, other than the settings,
	 * changed (the aliases file, say): its running services then need a restart.
	 */
	public synchronized void markChanged(ServiceId.Protocol protocol) {
		for (ServiceId id : ServiceId.values()) {
			if (id.protocol == protocol && isRunning(id)) {
				slots.get(id).fingerprint = "changed";
			}
		}
		if (protocol == ServiceId.Protocol.SMTP && queueOwner != null) {
			queueFingerprint = "changed";
		}
	}

	/** True if the service runs with settings that changed since it started. */
	public synchronized boolean isRestartNeeded(ServiceId id) {
		Slot s = slots.get(id);
		if (!isRunning(id)) {
			return false;
		}
		if (!fingerprint(id).equals(s.fingerprint)) {
			return true;
		}
		return id.protocol == ServiceId.Protocol.SMTP && queueOwner != null && !fingerprint(ServiceId.SMTP).equals(queueFingerprint);
	}

	/** The service's status, for the dashboard. */
	public synchronized Map<String, Object> status(ServiceId id) {
		Slot s = slots.get(id);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id.id());
		m.put("label", id.label);
		m.put("description", id.description);
		m.put("protocol", id.protocol.name());
		m.put("secure", id.secure);
		m.put("state", getState(id).name());
		m.put("port", s.server != null && isRunning(id) ? s.server.getPort() : getConfiguredPort(id));
		m.put("enabled", getConfiguredPort(id) > 0);
		m.put("started", s.state == State.RUNNING ? s.started : 0);
		m.put("error", s.error);
		m.put("restartNeeded", isRestartNeeded(id));
		m.put("connections", isRunning(id) ? connectionCount(s.server) : 0);
		m.put("tls", tlsConfigured(id));
		return m;
	}

	private static int connectionCount(Server server) {
		try {
			return server.getActiveClients().size();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** True if the service has a key store (its own or the TLS page's). */
	public boolean tlsConfigured(ServiceId id) {
		return notBlank(settings.getEffective(id.prefix + ".keyStoreName")) || notBlank(settings.getEffective(SettingsCatalog.TLS_KEY_STORE));
	}

	/** The open connections of a service: remote address and, once logged in, the user. */
	public synchronized List<Map<String, Object>> connections(ServiceId id) {
		List<Map<String, Object>> ret = new ArrayList<>();
		Slot s = slots.get(id);
		if (!isRunning(id)) {
			return ret;
		}
		Map<Socket, IProcessor> clients;
		try {
			clients = new LinkedHashMap<>(s.server.getActiveClients());
		} catch (RuntimeException e) {
			return ret;
		}
		for (Map.Entry<Socket, IProcessor> e : clients.entrySet()) {
			Map<String, Object> m = new LinkedHashMap<>();
			Socket sock = e.getKey();
			m.put("remote", sock.getRemoteSocketAddress() == null ? "" : sock.getRemoteSocketAddress().toString().replaceFirst("^/", ""));
			m.put("tls", sock instanceof javax.net.ssl.SSLSocket);
			IPrincipal p = null;
			try {
				p = e.getValue() == null ? null : e.getValue().getPrincipal();
			} catch (RuntimeException ex) {
				// not known
			}
			m.put("user", p == null ? null : p.getName());
			ret.add(m);
		}
		return ret;
	}

	/** The SMTP queue, while an SMTP service runs; else null. */
	public synchronized MailQueue getQueue() {
		return queueOwner == null ? null : queueOwner.getQueue();
	}

	// ------------------------------------------------------------------ making servers

	private Server create(ServiceId id, int port) {
		Server server;
		switch (id.protocol) {
		case SMTP: {
			SmtpServer s = new SmtpServer(port, SmtpServer.SMTP_NAME, id.secure);
			if (id != ServiceId.SMTP) {
				s.setSubmission(true);
			}
			configure(s);
			s.setQueue(queueOwner().getQueue());
			server = s;
			break;
		}
		case IMAP:
			server = configure(new ImapServer(port, ImapServer.IMAP_NAME, id.secure));
			break;
		default:
			server = configure(new Pop3Server(port, Pop3Server.POP3_NAME, id.secure));
		}
		serverCreated.run();
		return server;
	}

	private SmtpServer queueOwner() {
		if (queueOwner == null) {
			SmtpServer owner = new SmtpServer(0, SmtpServer.SMTP_NAME, false);
			configure(owner);
			MailQueue q = owner.getQueue();
			if (queueListener != null) {
				q.addQueueListener(queueListener);
			}
			queueOwner = owner;
			queueFingerprint = fingerprint(ServiceId.SMTP);
		}
		return queueOwner;
	}

	private void releaseQueueOwnerIfUnused() {
		for (ServiceId id : ServiceId.values()) {
			if (id.protocol == ServiceId.Protocol.SMTP && slots.get(id).server != null) {
				return;
			}
		}
		if (queueOwner != null && queueListener != null) {
			queueOwner.getQueue().removeQueueListener(queueListener);
		}
		queueOwner = null;
		queueFingerprint = null;
	}

	private <T extends Server> T configure(T server) {
		server.setAccessControl(acl);
		String own = settings.getEffective(server.getPropertyPrefix() + ".keyStoreName");
		String keyStore = settings.getEffective(SettingsCatalog.TLS_KEY_STORE);
		if (!notBlank(own) && notBlank(keyStore)) {
			server.setKeyStoreFileName(keyStore.trim());
			server.setKeyStorePassword(settings.get(SettingsCatalog.TLS_KEY_STORE_PASSWORD, ""));
			server.setKeyStoreType(settings.get(SettingsCatalog.TLS_KEY_STORE_TYPE, "PKCS12"));
		}
		return server;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}

	private static void quietStop(Server server) {
		try {
			server.stop();
		} catch (RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Error stopping " + server.getName(), e);
		}
	}

	/** A hash of the settings a service reads (to tell when it needs a restart). */
	String fingerprint(ServiceId id) {
		Properties p = System.getProperties();
		TreeMap<String, String> relevant = new TreeMap<>();
		for (String k : p.stringPropertyNames()) {
			if (id.isAffectedBy(k)) {
				relevant.put(k, p.getProperty(k));
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
			return java.util.Base64.getEncoder().encodeToString(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
