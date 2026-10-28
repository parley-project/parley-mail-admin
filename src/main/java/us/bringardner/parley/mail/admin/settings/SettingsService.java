package us.bringardner.parley.mail.admin.settings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.mail.store.MaildropRoot;

/**
 * The mail suite's settings: the files the servers read, in the settings folder (the folder of
 * {@code ServerMain.confDir()}, /etc/parley say). {@code parley-mail.properties} has what every
 * server shares; {@code parley-smtp.properties}, {@code parley-imap.properties} and
 * {@code parley-pop3.properties} have each protocol's own (they win over the shared file for
 * their servers). The admin application's own settings ({@code parley.mail.admin.*}) are in the
 * shared file too: the servers don't read them.
 * <p>
 * A setting is changed in the file that has it; a new one goes to its protocol's file
 * ({@code parley.mail.smtp.*}, {@code parley.mail.submission.*} ... to parley-smtp.properties),
 * anything else to the shared file. Nothing here sets system properties: the servers read the files
 * themselves (in this process, through parley-net's launcher). Only Parley settings
 * ({@code parley.*}) can be set there.
 * <p>
 * The mail store and the SMTP queue carry their own settings, in their own files: the store's
 * {@code .parley-store.properties} in the mail root ({@code store.NAME} here), the queue's
 * {@code queue.properties} in the relay's queue folder ({@code queue.NAME}), on the mail root's file
 * system. Setting one makes its file when there's none (the administrator's job, done here). The
 * host name ({@link SettingsCatalog#QUEUE_HOSTNAME}) is one value for the queue and every SMTP server.
 */
public final class SettingsService {

	/** The settings every mail server reads (parley-mail's MailServerProvider.SHARED_SETTINGS_FILE). */
	public static final String SHARED_FILE = "parley-mail.properties";

	private final File directory;
	private final SettingsCatalog catalog;
	/** parley-mail.properties, then the protocols' files in ServiceId.Protocol order */
	private final List<PropertiesFile> files = new ArrayList<>();
	/** The Parley settings this JVM had when the application started (-D): they win over the files. */
	private final Map<String, String> jvm = new HashMap<>();
	private final Map<String, long[]> stamps = new HashMap<>();
	private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
	private boolean loaded;
	/** The store's and the queue's settings files (null while there's no mail root), and what they were made from */
	private PropertiesFile storeFile;
	private PropertiesFile queueFile;
	private String assetsFrom;
	private String assetError;
	/** Settings a new queue file gets besides those set (the aliases file the Aliases page edits) */
	private volatile Map<String, String> newQueueDefaults = Collections.emptyMap();

	public SettingsService(File directory, SettingsCatalog catalog) {
		this.directory = directory;
		this.catalog = catalog;
		files.add(new PropertiesFile(new File(directory, SHARED_FILE)));
		for (ServiceId.Protocol p : ServiceId.Protocol.values()) {
			files.add(new PropertiesFile(new File(directory, p.settingsFile)));
		}
		for (String k : System.getProperties().stringPropertyNames()) {
			if (k.startsWith("parley.")) {
				jvm.put(k, System.getProperty(k));
			}
		}
	}

	public SettingsCatalog getCatalog() {
		return catalog;
	}

	/** The settings folder. */
	public File getDirectory() {
		return directory;
	}

	/** The files, the shared one first. */
	public List<PropertiesFile> getFiles() {
		return Collections.unmodifiableList(files);
	}

	/** The shared file, parley-mail.properties. */
	public PropertiesFile getSharedFile() {
		return files.get(0);
	}

	/** A protocol's own file. */
	public PropertiesFile getFile(ServiceId.Protocol protocol) {
		return files.get(1 + protocol.ordinal());
	}

	/** Read the files (again); the listeners hear of settings whose values changed. */
	public void load() throws IOException {
		Set<String> changed = new LinkedHashSet<>();
		boolean first;
		synchronized (this) {
			Map<String, String> before = getAll();
			for (PropertiesFile f : files) {
				f.load();
				stamp(f);
			}
			resolveAssets(true);
			Map<String, String> now = getAll();
			for (String k : before.keySet()) {
				if (!now.containsKey(k)) {
					changed.add(k);
				}
			}
			for (Map.Entry<String, String> e : now.entrySet()) {
				if (!e.getValue().equals(before.get(e.getKey()))) {
					changed.add(e.getKey());
				}
			}
			first = !loaded;
			loaded = true;
		}
		if (!first && !changed.isEmpty()) {
			fire(changed);
		}
	}

	private void stamp(PropertiesFile f) {
		stamps.put(f.getPath(), new long[] { f.lastModified(), f.length() });
	}

	/** Read the files again if one of them changed (edited outside this application); true if one did. */
	public boolean reloadIfChanged() throws IOException {
		synchronized (this) {
			boolean changed = false;
			for (PropertiesFile f : allFiles()) {
				long[] s = stamps.get(f.getPath());
				if (s == null || s[0] != f.lastModified() || s[1] != f.length()) {
					changed = true;
				}
			}
			if (!changed) {
				return false;
			}
		}
		load();
		return true;
	}

	// ------------------------------------------------------------------ the store's and the queue's files

	/** The settings files and the store's and queue's, those there are */
	private List<PropertiesFile> allFiles() {
		List<PropertiesFile> ret = new ArrayList<>(files);
		if (storeFile != null) {
			ret.add(storeFile);
		}
		if (queueFile != null) {
			ret.add(queueFile);
		}
		return ret;
	}

	/**
	 * Find the store's and the queue's files from the mail root, its file system and the relay's
	 * queue folder (again when one of those changed), and read them.
	 */
	private void resolveAssets(boolean reload) throws IOException {
		String root = trim(getEffective(SettingsCatalog.ROOT));
		String fs = trim(getEffective(SettingsCatalog.FILE_SOURCE));
		String queue = trim(getEffective(SettingsCatalog.SMTP_QUEUE));
		if (queue == null) {
			queue = SettingsCatalog.DEFAULT_QUEUE;
		}
		String from = root + "\u0000" + fs + "\u0000" + queue + "\u0000" + new java.util.TreeMap<>(connectionSettings());
		if (!from.equals(assetsFrom)) {
			assetsFrom = from;
			storeFile = null;
			queueFile = null;
			assetError = null;
			reload = true;
			if (root == null) {
				assetError = "The mail root isn't set (" + SettingsCatalog.ROOT + ")";
			} else {
				try {
					String[] segs = queueSegments(queue);
					if (isLocalMailRoot()) {
						File r = new File(root);
						storeFile = new PropertiesFile(new File(r, us.bringardner.parley.mail.store.MailLocks.STORE_SETTINGS_FILE));
						File q = r;
						for (String seg : segs) {
							q = new File(q, seg);
						}
						queueFile = new PropertiesFile(new File(q, us.bringardner.parley.smtp.queue.MailQueue.SETTINGS_FILE));
					} else {
						// another file system: opened as the servers open it (MaildropRoot), with the
						// parley.mail.fileSource.* connection settings, or a filesource: URL
						FileSource r = getMailRoot();
						storeFile = new PropertiesFile(r.getChild(us.bringardner.parley.mail.store.MailLocks.STORE_SETTINGS_FILE));
						FileSource q = r;
						for (String seg : segs) {
							q = q.getChild(seg);
						}
						queueFile = new PropertiesFile(q.getChild(us.bringardner.parley.smtp.queue.MailQueue.SETTINGS_FILE));
					}
				} catch (IOException | RuntimeException e) {
					storeFile = null;
					queueFile = null;
					// (getMailRoot's message names the root without a URL's query)
					assetError = e.getMessage() != null && e.getMessage().startsWith("Can't reach the mail root") ? e.getMessage()
							: "Can't reach the mail root " + MaildropRoot.describe(root, fs) + ": " + e.getMessage();
					// try again next time: the store may be reachable then
					assetsFrom = null;
				}
			}
		}
		if (reload) {
			for (PropertiesFile f : new PropertiesFile[] { storeFile, queueFile }) {
				if (f != null) {
					try {
						f.load();
					} catch (IOException e) {
						assetError = "Can't read " + f.getPath() + ": " + e.getMessage();
					}
					stamp(f);
				}
			}
		}
	}

	// ------------------------------------------------------------------ the mail root

	/** The mail root as last opened, and the settings it was opened with */
	private FileSource mailRoot;
	private String mailRootFrom;

	/**
	 * The file system's connection settings the servers get: every {@code parley.mail.fileSource.KEY}
	 * the servers see (the files', or -D), as KEY.
	 */
	public synchronized java.util.Properties connectionSettings() {
		Map<String, String> all = new LinkedHashMap<>();
		for (PropertiesFile f : files) {
			all.putAll(f.getValues());
		}
		all.putAll(jvm);
		java.util.Properties ret = new java.util.Properties();
		String prefix = SettingsCatalog.FILE_SOURCE + ".";
		for (String k : all.keySet()) {
			if (k.startsWith(prefix) && k.length() > prefix.length()) {
				String v = getEffective(k);
				if (v != null) {
					ret.setProperty(k.substring(prefix.length()), v.trim());
				}
			}
		}
		return ret;
	}

	/**
	 * True if the mail root is a folder on this machine's disk: a path, with no
	 * {@value SettingsCatalog#FILE_SOURCE}.
	 */
	public boolean isLocalMailRoot() {
		String root = trim(getEffective(SettingsCatalog.ROOT));
		return root != null && !MaildropRoot.isUrl(root) && trim(getEffective(SettingsCatalog.FILE_SOURCE)) == null;
	}

	/**
	 * The mail root, on whatever file system it is, opened as the servers open it (parley-mail's
	 * MaildropRoot): {@value SettingsCatalog#ROOT} on the file system {@value SettingsCatalog#FILE_SOURCE}
	 * names with its {@code parley.mail.fileSource.*} connection settings, or a {@code filesource:}
	 * URL. Kept open until one of those settings changes.
	 *
	 * @throws IOException if it isn't set or can't be reached (the message never holds a URL's query)
	 */
	public synchronized FileSource getMailRoot() throws IOException {
		String root = trim(getEffective(SettingsCatalog.ROOT));
		String fs = trim(getEffective(SettingsCatalog.FILE_SOURCE));
		java.util.Properties connection = connectionSettings();
		if (root == null) {
			throw new IOException("The mail root isn't set (" + SettingsCatalog.ROOT + ")");
		}
		String from = root + "\u0000" + fs + "\u0000" + new java.util.TreeMap<>(connection);
		if (mailRoot == null || !from.equals(mailRootFrom)) {
			try {
				mailRoot = MaildropRoot.open(root, fs, connection, SettingsCatalog.FILE_SOURCE);
			} catch (IOException e) {
				mailRoot = null;
				throw new IOException("Can't reach the mail root " + MaildropRoot.describe(root, fs) + ": " + e.getMessage(), e);
			}
			mailRootFrom = from;
		}
		return mailRoot;
	}

	/** The queue folder's names under the mail root, as parley-smtp's SmtpServer takes them */
	private static String[] queueSegments(String queue) throws IOException {
		List<String> ret = new ArrayList<>();
		for (String seg : queue.replace('\\', '/').split("/")) {
			if (seg.isEmpty() || seg.equals(".")) {
				continue;
			}
			if (seg.equals("..")) {
				throw new IOException("The queue (" + SettingsCatalog.SMTP_QUEUE + "=" + queue + ") must be under the mail root");
			}
			ret.add(seg);
		}
		if (ret.isEmpty()) {
			throw new IOException("The queue (" + SettingsCatalog.SMTP_QUEUE + ") can't be the mail root itself");
		}
		return ret.toArray(new String[0]);
	}

	private static String trim(String s) {
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}

	/** The mail store's own settings file, or null while there's no mail root (see {@link #getAssetError}). */
	public synchronized PropertiesFile getStoreFile() {
		return storeFile;
	}

	/** The SMTP queue's own settings file, or null while there's no mail root. */
	public synchronized PropertiesFile getQueueFile() {
		return queueFile;
	}

	/** Why the store's and queue's files can't be found or read, or null. */
	public synchronized String getAssetError() {
		return assetError;
	}

	/** Settings a queue file made here gets besides those set, unless they are set (the aliases file). */
	public void setNewQueueDefaults(Map<String, String> defaults) {
		newQueueDefaults = new LinkedHashMap<>(defaults);
	}

	/** The file of a store or queue setting (null while there's none), else null */
	private PropertiesFile assetFile(String key) {
		return key.startsWith(SettingsCatalog.STORE_PREFIX) ? storeFile : key.startsWith(SettingsCatalog.QUEUE_PREFIX) ? queueFile : null;
	}

	/** A store or queue setting's name in its own file */
	private static String assetName(String key) {
		return key.substring(key.indexOf('.') + 1);
	}

	/** The protocol whose file a setting belongs in, or null for the shared file. */
	public static ServiceId.Protocol protocolOf(String key) {
		for (ServiceId s : ServiceId.values()) {
			if (key.startsWith(s.prefix + ".") || key.startsWith(s.protocol.prefix + ".")) {
				return s.protocol;
			}
		}
		return null;
	}

	/**
	 * The file a setting is in: its protocol's file, else the shared file, else (a setting a person
	 * put there) another protocol's file; or null if it isn't set.
	 */
	public synchronized PropertiesFile findFile(String key) {
		if (SettingsCatalog.isAssetKey(key)) {
			PropertiesFile f = assetFile(key);
			return f != null && f.get(assetName(key)) != null ? f : null;
		}
		ServiceId.Protocol p = protocolOf(key);
		if (p != null && getFile(p).get(key) != null) {
			return getFile(p);
		}
		for (PropertiesFile f : files) {
			if (f.get(key) != null) {
				return f;
			}
		}
		return null;
	}

	/** The file a setting is in, or the one a new value goes to. */
	public synchronized PropertiesFile fileFor(String key) {
		if (SettingsCatalog.isAssetKey(key)) {
			return assetFile(key);
		}
		PropertiesFile f = findFile(key);
		if (f != null) {
			return f;
		}
		ServiceId.Protocol p = protocolOf(key);
		return p == null ? getSharedFile() : getFile(p);
	}

	/** The value in the files (see {@link #findFile}), or null. */
	public synchronized String get(String key) {
		PropertiesFile f = findFile(key);
		return f == null ? null : f.get(SettingsCatalog.isAssetKey(key) ? assetName(key) : key);
	}

	/**
	 * The value the servers see: one set with java -D when this application started, else the
	 * files'; or null.
	 */
	public String getEffective(String key) {
		String v = jvm.get(key);
		return v != null ? v : get(key);
	}

	/** True if the JVM set this setting (-D) when the application started: it wins over the files. */
	public boolean isSetByJvm(String key) {
		return jvm.containsKey(key);
	}

	public String get(String key, String defaultValue) {
		String v = getEffective(key);
		return v == null || v.trim().isEmpty() ? defaultValue : v.trim();
	}

	public boolean getBoolean(String key, boolean defaultValue) {
		String v = getEffective(key);
		return v == null || v.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(v.trim());
	}

	public long getLong(String key, long defaultValue) {
		String v = getEffective(key);
		if (v == null || v.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(v.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Every setting in the files (each with the value {@link #get} gives), the shared file's first,
	 * then the store's and the queue's ({@code store.NAME}, {@code queue.NAME}).
	 */
	public synchronized Map<String, String> getAll() {
		Map<String, String> ret = new LinkedHashMap<>();
		for (PropertiesFile f : files) {
			for (String k : f.getValues().keySet()) {
				if (!ret.containsKey(k)) {
					ret.put(k, get(k));
				}
			}
		}
		if (storeFile != null) {
			for (Map.Entry<String, String> e : storeFile.getValues().entrySet()) {
				ret.put(SettingsCatalog.STORE_PREFIX + e.getKey(), e.getValue());
			}
		}
		if (queueFile != null) {
			for (Map.Entry<String, String> e : queueFile.getValues().entrySet()) {
				ret.put(SettingsCatalog.QUEUE_PREFIX + e.getKey(), e.getValue());
			}
		}
		return ret;
	}

	/**
	 * The settings a server of this protocol reads from the files: the shared file's, then its
	 * protocol's file's, which win (as parley-net's launcher reads them).
	 */
	public synchronized Map<String, String> readBy(ServiceId.Protocol protocol) {
		Map<String, String> ret = new LinkedHashMap<>(getSharedFile().getValues());
		ret.putAll(getFile(protocol).getValues());
		return ret;
	}

	/**
	 * Change settings: a null or empty value removes the setting (from every file that has it). The
	 * values are checked first (nothing is changed if one is wrong), then the files are written.
	 *
	 * @return the settings whose values changed
	 * @throws IllegalArgumentException if a value isn't acceptable
	 */
	public Set<String> update(Map<String, String> changes) throws IOException {
		Set<String> changed = new LinkedHashSet<>();
		synchronized (this) {
			List<String> errors = new ArrayList<>();
			Map<String, String> clean = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : changes.entrySet()) {
				String key = e.getKey() == null ? "" : e.getKey().trim();
				if (!key.matches("(parley|store|queue)\\.[A-Za-z0-9_.\\-]+")) {
					errors.add("Invalid setting name '" + e.getKey() + "' (a Parley setting, parley.*, or the store's or queue's, store.* or queue.*)");
					continue;
				}
				String value = e.getValue() == null ? null : e.getValue().trim();
				if (value != null && value.isEmpty()) {
					value = null;
				}
				SettingDef d = catalog.get(key);
				String error = d == null ? (value != null && (value.contains("\n") || value.contains("\r")) ? key + " must be one line" : null)
						: d.validate(value);
				if (error != null) {
					errors.add(error);
				}
				clean.put(key, value);
			}
			if (!errors.isEmpty()) {
				throw new IllegalArgumentException(String.join("; ", errors));
			}
			// the host name is one for the queue and every SMTP server
			if (clean.containsKey(SettingsCatalog.QUEUE_HOSTNAME)) {
				for (String k : SettingsCatalog.SMTP_HOSTNAMES) {
					clean.put(k, clean.get(SettingsCatalog.QUEUE_HOSTNAME));
				}
			}
			Set<PropertiesFile> dirty = new LinkedHashSet<>();
			for (Map.Entry<String, String> e : clean.entrySet()) {
				String key = e.getKey();
				String value = e.getValue();
				if (SettingsCatalog.isAssetKey(key)) {
					continue; // after the files that say where they are
				}
				if (value == null) {
					for (PropertiesFile f : files) {
						if (f.get(key) != null) {
							f.set(key, null);
							dirty.add(f);
							changed.add(key);
						}
					}
				} else if (!value.equals(get(key))) {
					PropertiesFile f = fileFor(key);
					f.set(key, value);
					dirty.add(f);
					changed.add(key);
				}
			}
			for (PropertiesFile f : dirty) {
				f.save();
				stamp(f);
			}
			resolveAssets(false);
			Set<PropertiesFile> made = new LinkedHashSet<>();
			dirty.clear();
			for (Map.Entry<String, String> e : clean.entrySet()) {
				String key = e.getKey();
				if (!SettingsCatalog.isAssetKey(key)) {
					continue;
				}
				PropertiesFile f = assetFile(key);
				if (f == null) {
					throw new IllegalArgumentException("Can't set " + key + ": " + (assetError != null ? assetError : "there's no mail root"));
				}
				String name = assetName(key);
				String value = e.getValue();
				if (value == null ? f.get(name) == null : value.equals(f.get(name))) {
					continue;
				}
				if (!f.exists()) {
					made.add(f);
				}
				f.set(name, value);
				dirty.add(f);
				changed.add(key);
			}
			for (PropertiesFile f : made) {
				if (f == queueFile) {
					for (Map.Entry<String, String> d : newQueueDefaults.entrySet()) {
						if (f.get(d.getKey()) == null && d.getValue() != null) {
							f.set(d.getKey(), d.getValue());
							changed.add(SettingsCatalog.QUEUE_PREFIX + d.getKey());
						}
					}
				}
			}
			for (PropertiesFile f : dirty) {
				f.save();
				stamp(f);
			}
		}
		if (!changed.isEmpty()) {
			fire(changed);
		}
		return changed;
	}

	public Set<String> set(String key, String value) throws IOException {
		return update(Collections.singletonMap(key, value));
	}

	/** Be told which settings changed (after the files are written or read again). */
	public void addListener(Consumer<Set<String>> listener) {
		listeners.add(listener);
	}

	private void fire(Set<String> changed) {
		for (Consumer<Set<String>> l : listeners) {
			try {
				l.accept(changed);
			} catch (RuntimeException e) {
				System.getLogger(SettingsService.class.getName()).log(System.Logger.Level.WARNING, "A settings listener failed", e);
			}
		}
	}
}
