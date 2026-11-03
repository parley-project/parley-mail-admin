package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.mail.server.AbstractMailServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.core.SecureBaseObject;

/**
 * A mail server's {@link ServerProvider}: the server is made from its own configuration, read from
 * the suite's settings file, {@code parley-mail.properties} (the mail root, the users, the TLS key
 * store ...), then its own. Whether it runs is what {@code parley.servers} lists; its port is only a
 * port ({@code <prefix>.port}, its protocol's standard port when it isn't set). A server with
 * implicit TLS that has no key store can't run: it is skipped with a warning.
 *
 * @since 2.0.0
 */
public abstract class MailServerProvider implements ServerProvider {

	/** The settings every mail server shares, read before each server's own file */
	public static final String SHARED_SETTINGS_FILE = "parley-mail.properties";

	private final String id;
	private final String description;
	private final String settingsFile;
	private final String configProperty;
	private final String prefix;
	private final int standardPort;
	private final boolean implicitTls;

	/**
	 * @param settingsFile   the server's own file, e.g. parley-pop3.properties (POP3 and POP3S share it)
	 * @param configProperty the property naming a file to read instead, e.g. parley.mail.pop3.configFile
	 * @param prefix         the prefix of the server's own settings, e.g. parley.mail.pop3s
	 * @param standardPort   the port when {@code <prefix>.port} isn't set, e.g. 995
	 * @param implicitTls    true for TLS from the first byte (it needs a key store)
	 * @since 2.0.0
	 */
	protected MailServerProvider(String id, String description, String settingsFile, String configProperty, String prefix,
			int standardPort, boolean implicitTls) {
		this.id = id;
		this.description = description;
		this.settingsFile = settingsFile;
		this.configProperty = configProperty;
		this.prefix = prefix;
		this.standardPort = standardPort;
		this.implicitTls = implicitTls;
	}

	/** The prefix of the server's own settings, e.g. parley.mail.pop3s */
	public String prefix() {
		return prefix;
	}

	/** The protocol's standard port: the port when {@code <prefix>.port} isn't set */
	public int standardPort() {
		return standardPort;
	}

	/** True for implicit TLS: TLS from the first byte */
	public boolean implicitTls() {
		return implicitTls;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public List<String> sharedSettingsFiles() {
		return Collections.singletonList(SHARED_SETTINGS_FILE);
	}

	@Override
	public String settingsFile() {
		return settingsFile;
	}

	@Override
	public String configProperty() {
		return configProperty;
	}

	/** Mail servers are made from their own configuration */
	@Override
	public boolean usesConfiguration() {
		return true;
	}

	/** The port to listen on: {@code <prefix>.port}, else {@link #standardPort()} */
	public int port(Properties configuration) {
		return intProperty(configuration, prefix + ".port", standardPort);
	}

	/**
	 * Can't run: a port that isn't 1 to 65535 (port 0 no longer turns a server off: it is left out of
	 * parley.servers instead), or implicit TLS with no key store ({@link #hasKeyStore(Properties)}).
	 */
	@Override
	public String unavailable(Properties configuration) {
		int port = port(configuration);
		if (port == 0) {
			return prefix + ".port is 0, which no longer turns a server off: leave " + id + " out of parley.servers instead";
		}
		if (port < 0 || port > 65535) {
			return prefix + ".port is " + port + ": a port is 1 to 65535";
		}
		if (implicitTls && !hasKeyStore(configuration)) {
			return "No TLS key store: set " + AbstractMailServer.MAIL_TLS_KEY_STORE_PROP + " (or " + prefix + "."
					+ SecureBaseObject.PROPERTY_KEY_STORE_NAME + " for this server alone)";
		}
		return null;
	}

	/**
	 * True if the server has a TLS key store: the suite's ({@value AbstractMailServer#MAIL_TLS_KEY_STORE_PROP})
	 * or its own ({@code <prefix>.keyStoreName})
	 */
	public boolean hasKeyStore(Properties configuration) {
		return isSet(configuration, AbstractMailServer.MAIL_TLS_KEY_STORE_PROP)
				|| isSet(configuration, prefix + "." + SecureBaseObject.PROPERTY_KEY_STORE_NAME);
	}

	/**
	 * Settings this server no longer reads, each with what to do instead: a warning when it starts
	 * if one is set
	 */
	protected Map<String, String> obsoleteSettings() {
		return Collections.emptyMap();
	}

	/** A warning for each setting of {@link #obsoleteSettings()} that is set */
	@Override
	public List<String> warnings(Properties configuration) {
		List<String> ret = new ArrayList<>();
		for (Map.Entry<String, String> e : obsoleteSettings().entrySet()) {
			if (isSet(configuration, e.getKey())) {
				ret.add(e.getKey() + " is no longer read: " + e.getValue());
			}
		}
		return ret;
	}

	private static boolean isSet(Properties configuration, String name) {
		String v = configuration.getProperty(name);
		return v != null && !v.trim().isEmpty();
	}

	/** An integer setting, or the default when it isn't set or isn't a number */
	protected static int intProperty(Properties configuration, String name, int defaultValue) {
		String v = configuration.getProperty(name);
		if (v == null || v.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/** A true/false setting, or the default when it isn't set */
	protected static boolean booleanProperty(Properties configuration, String name, boolean defaultValue) {
		String v = configuration.getProperty(name);
		return v == null || v.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(v.trim());
	}
}
