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

import us.bringardner.parley.mail.admin.suite.ServiceId;

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
 * ({@code parley.*}) can be set.
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
	private final Map<File, long[]> stamps = new HashMap<>();
	private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
	private boolean loaded;

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
		File file = f.getFile();
		stamps.put(file, new long[] { file.lastModified(), file.length() });
	}

	/** Read the files again if one of them changed (edited outside this application); true if one did. */
	public boolean reloadIfChanged() throws IOException {
		synchronized (this) {
			boolean changed = false;
			for (PropertiesFile f : files) {
				long[] s = stamps.get(f.getFile());
				File file = f.getFile();
				if (s == null || s[0] != file.lastModified() || s[1] != file.length()) {
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
		return f == null ? null : f.get(key);
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

	/** Every setting in the files (each with the value {@link #get} gives), the shared file's first. */
	public synchronized Map<String, String> getAll() {
		Map<String, String> ret = new LinkedHashMap<>();
		for (PropertiesFile f : files) {
			for (String k : f.getValues().keySet()) {
				if (!ret.containsKey(k)) {
					ret.put(k, get(k));
				}
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
				if (!key.matches("parley\\.[A-Za-z0-9_.\\-]+")) {
					errors.add("Invalid setting name '" + e.getKey() + "' (a Parley setting, parley.*)");
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
			Set<PropertiesFile> dirty = new LinkedHashSet<>();
			for (Map.Entry<String, String> e : clean.entrySet()) {
				String key = e.getKey();
				String value = e.getValue();
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
