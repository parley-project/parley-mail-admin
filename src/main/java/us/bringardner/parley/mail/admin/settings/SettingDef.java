package us.bringardner.parley.mail.admin.settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A setting the Settings page knows: its property name, how to edit it and what it does. */
public final class SettingDef {

	public enum Type {
		/** Free text. */
		TEXT,
		/** A whole number. */
		NUMBER,
		/** true or false. */
		BOOLEAN,
		/** One of {@link SettingDef#getOptions()}. */
		CHOICE,
		/** Never sent back to the browser; shown as set or not set. */
		SECRET,
		/** A comma separated list. */
		LIST,
		/** A file or directory. */
		PATH,
		/** A TCP port; 0 turns the service off. */
		PORT
	}

	private final String key;
	private final String group;
	private final String label;
	private final Type type;
	private final String defaultValue;
	private final String description;
	private final List<String> options;
	/** The server whose setting this is (its id: smtp, submission, ...), or null for one every server of the group reads */
	private final String server;
	/** A choice's names for people and explanations, by option (empty: the options themselves) */
	private volatile List<String> optionLabels = Collections.emptyList();
	private volatile List<String> optionDescriptions = Collections.emptyList();
	/** Shown only while the setting showWhenKey has the value showWhenValue (a provider's own settings), or null */
	private volatile String showWhenKey;
	private volatile String showWhenValue;
	/** Hidden while the setting hideWhenKey has the value hideWhenValue (settings that mean nothing for the choice "none"), or null */
	private volatile String hideWhenKey;
	private volatile String hideWhenValue;
	private volatile boolean advanced;

	public SettingDef(String key, String group, String label, Type type, String defaultValue, String description, String... options) {
		this(key, group, null, label, type, defaultValue, description, options);
	}

	public SettingDef(String key, String group, String server, String label, Type type, String defaultValue, String description, String... options) {
		this.key = key;
		this.server = server;
		this.group = group;
		this.label = label;
		this.type = type;
		this.defaultValue = defaultValue;
		this.description = description;
		this.options = options.length == 0 ? Collections.emptyList() : Collections.unmodifiableList(Arrays.asList(options));
	}

	public String getKey() {
		return key;
	}

	/** The server whose setting this is (smtp, submission, ...), or null. */
	public String getServer() {
		return server;
	}

	public String getGroup() {
		return group;
	}

	public String getLabel() {
		return label;
	}

	public Type getType() {
		return type;
	}

	/** The value used when the setting isn't set (as text, for display), or null. */
	public String getDefaultValue() {
		return defaultValue;
	}

	public String getDescription() {
		return description;
	}

	public List<String> getOptions() {
		return options;
	}

	/** The names of the options for people, in the order of {@link #getOptions()}; empty if they are the options. */
	public List<String> getOptionLabels() {
		return optionLabels;
	}

	/** What each option is, in the order of {@link #getOptions()}; empty if there is no text. */
	public List<String> getOptionDescriptions() {
		return optionDescriptions;
	}

	public SettingDef withOptionInfo(List<String> labels, List<String> descriptions) {
		this.optionLabels = Collections.unmodifiableList(new java.util.ArrayList<>(labels));
		this.optionDescriptions = Collections.unmodifiableList(new java.util.ArrayList<>(descriptions));
		return this;
	}

	/** Show this setting only while the setting {@code key} has the value {@code value}. */
	public SettingDef showWhen(String key, String value) {
		this.showWhenKey = key;
		this.showWhenValue = value;
		return this;
	}

	/** Hide this setting while the setting {@code key} has the value {@code value}. */
	public SettingDef hideWhen(String key, String value) {
		this.hideWhenKey = key;
		this.hideWhenValue = value;
		return this;
	}

	public String getHideWhenKey() {
		return hideWhenKey;
	}

	public String getHideWhenValue() {
		return hideWhenValue;
	}

	public String getShowWhenKey() {
		return showWhenKey;
	}

	public String getShowWhenValue() {
		return showWhenValue;
	}

	/** An advanced setting is rarely needed and is shown after the others. */
	public SettingDef asAdvanced() {
		this.advanced = true;
		return this;
	}

	public boolean isAdvanced() {
		return advanced;
	}

	/** Why a value is not acceptable, or null if it is. Empty means "not set" and is always fine. */
	public String validate(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		switch (type) {
		case NUMBER:
		case PORT:
			try {
				long n = Long.parseLong(value.trim());
				if (n < 0) {
					return label + " can't be negative";
				}
				if (type == Type.PORT && n > 65535) {
					return label + " must be a port number (0 to 65535)";
				}
			} catch (NumberFormatException e) {
				// some numbers are decimals (hours)
				try {
					Double.parseDouble(value.trim());
				} catch (NumberFormatException e2) {
					return label + " must be a number";
				}
				if (type == Type.PORT) {
					return label + " must be a whole number";
				}
			}
			return null;
		case BOOLEAN:
			return value.equals("true") || value.equals("false") ? null : label + " must be true or false";
		case CHOICE:
			for (String o : options) {
				if (o.equalsIgnoreCase(value)) {
					return null;
				}
			}
			return label + " must be one of " + options;
		default:
			return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ? label + " must be one line" : null;
		}
	}
}
