package us.bringardner.parley.mail.admin.settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The suite's settings: one properties file whose every setting is also a system property, which
 * is where the Parley servers read their settings when they are created. Settings in the file win
 * over the same system properties set otherwise (-D), so what the Settings page shows is what
 * the servers get; a setting removed from the file gets its -D value back. Only Parley settings
 * ({@code parley.*}) can be set.
 */
public final class SettingsService {

	private final PropertiesFile file;
	private final SettingsCatalog catalog;
	/** The settings this service put into the system properties. */
	private final Set<String> applied = new HashSet<>();
	private final Map<String, Optional<String>> original = new HashMap<>();
	private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();

	public SettingsService(PropertiesFile file, SettingsCatalog catalog) {
		this.file = file;
		this.catalog = catalog;
	}

	public SettingsCatalog getCatalog() {
		return catalog;
	}

	public PropertiesFile getFile() {
		return file;
	}

	/** Read the file and apply its settings. */
	public synchronized void load() throws IOException {
		Map<String, String> before = file.getValues();
		file.load();
		Map<String, String> now = file.getValues();
		Set<String> changed = new HashSet<>();
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
		apply();
		if (!before.isEmpty() && !changed.isEmpty()) {
			fire(changed);
		}
	}

	private void apply() {
		Map<String, String> values = file.getValues();
		for (String k : new ArrayList<>(applied)) {
			if (!values.containsKey(k)) {
				restore(k);
				applied.remove(k);
			}
		}
		for (Map.Entry<String, String> e : values.entrySet()) {
			if (!applied.contains(e.getKey())) {
				// the value it had before the file set it (-D), for when it leaves the file
				original.put(e.getKey(), Optional.ofNullable(System.getProperty(e.getKey())));
			}
			System.setProperty(e.getKey(), e.getValue().trim());
			applied.add(e.getKey());
		}
	}

	private void restore(String key) {
		Optional<String> o = original.remove(key);
		if (o != null && o.isPresent()) {
			System.setProperty(key, o.get());
		} else {
			System.clearProperty(key);
		}
	}

	/** The value in the file, or null. */
	public String get(String key) {
		return file.get(key);
	}

	/** The value the servers see: the system property (set from the file or otherwise), or null. */
	public String getEffective(String key) {
		return System.getProperty(key);
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

	/** Every setting in the file, in file order. */
	public Map<String, String> getAll() {
		return file.getValues();
	}

	/**
	 * Change settings: a null or empty value removes the setting. The values are checked first
	 * (nothing is changed if one is wrong), then the file is written and the system properties set.
	 *
	 * @return the settings whose values changed
	 * @throws IllegalArgumentException if a value isn't acceptable
	 */
	public Set<String> update(Map<String, String> changes) throws IOException {
		Set<String> changed = new HashSet<>();
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
			for (Map.Entry<String, String> e : clean.entrySet()) {
				String old = file.get(e.getKey());
				String value = e.getValue();
				if (value == null ? old != null : !value.equals(old)) {
					file.set(e.getKey(), value);
					changed.add(e.getKey());
				}
			}
			if (changed.isEmpty()) {
				return changed;
			}
			file.save();
			apply();
		}
		fire(changed);
		return changed;
	}

	public Set<String> set(String key, String value) throws IOException {
		return update(Collections.singletonMap(key, value));
	}

	/** Be told which settings changed (after they are applied). */
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

	/** Remove the system properties this service set (when the application stops). */
	public synchronized void clear() {
		for (String k : applied) {
			restore(k);
		}
		applied.clear();
	}
}
