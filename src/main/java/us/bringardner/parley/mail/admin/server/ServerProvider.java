package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * A server {@link ServerLauncher} can run: found at run time (Java's ServiceLoader, through
 * parley-core's plugins: {@code META-INF/services/us.bringardner.parley.net.server.ServerProvider}),
 * so a launcher runs any set of servers, alone or several in one JVM, without depending on their
 * modules. Each listener is one provider: SMTP's MX and submission servers are two, POP3 and POP3
 * with implicit TLS are two.
 * <p>
 * A provider makes its server from that server's own configuration (its settings files, the JVM's
 * settings and the command line, see {@link LaunchSettings#configuration(String)}): the server is
 * written as if it were alone, and two servers in one JVM can have different values for one name.
 * A provider written before configurations ({@link #usesConfiguration()} false) reads the system
 * properties instead, which the launcher then sets from the files.
 *
 * @since 2.0.0
 */
public interface ServerProvider {

	/** The id that names the server in {@code parley.servers}, in lower case, e.g. {@code pop3} */
	String id();

	/** For people, e.g. "POP3 (port 110)" */
	String description();

	/**
	 * Settings files (in the settings folder, {@link ServerMain#confDir()}) read before the server's
	 * own, for the settings a family of servers shares, e.g. {@code parley-mail.properties}.
	 */
	default List<String> sharedSettingsFiles() {
		return Collections.emptyList();
	}

	/** The server's own settings file in the settings folder (null: none); it wins over the shared ones */
	default String settingsFile() {
		return "parley-" + id() + ".properties";
	}

	/**
	 * The property naming a file to read instead of {@link #settingsFile()} (e.g.
	 * parley.mail.pop3.configFile), or null
	 */
	default String configProperty() {
		return null;
	}

	/**
	 * True if this provider makes its server from the configuration it is given
	 * ({@link #enabled(Properties)}, {@link #create(ServerContext, Properties)}); false (the
	 * default, for providers written before 2.0.0's configurations) if it reads the system
	 * properties ({@link #enabled()}, {@link #create(ServerContext)}).
	 *
	 * @since 2.0.0
	 */
	default boolean usesConfiguration() {
		return false;
	}

	/**
	 * Whether the server's configuration turns it on; a server that is off isn't started with the
	 * others, and can't be started until its settings change. Which servers run is what
	 * {@code parley.servers} lists; this is only for a provider with a setting of its own that turns
	 * it off (the mail servers have none: their port is only a port).
	 *
	 * @param configuration the server's own settings
	 */
	default boolean enabled(Properties configuration) {
		return enabled();
	}

	/** {@link #enabled(Properties)} from the system properties, for a provider that reads them */
	default boolean enabled() {
		return true;
	}

	/**
	 * Why this server can't run with these settings (a server with implicit TLS and no key store,
	 * say), or null when it can. A listed server that can't run is skipped with this as a warning:
	 * the launcher starts the others, and the server shows as off with this message, until its
	 * settings change.
	 *
	 * @param configuration the server's own settings
	 * @since 2.0.0
	 */
	default String unavailable(Properties configuration) {
		return null;
	}

	/**
	 * Warnings about these settings for people (a setting that is no longer read, say), printed when
	 * the server starts; they don't stop it.
	 *
	 * @param configuration the server's own settings
	 * @since 2.0.0
	 */
	default List<String> warnings(Properties configuration) {
		return Collections.emptyList();
	}

	/**
	 * Make the server from its own configuration; it is started by the launcher. The server reads
	 * only this configuration (and its classes' defaults), never the system properties.
	 *
	 * @param configuration the server's own settings (a copy: the server may keep it)
	 */
	default ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
		return create(context);
	}

	/** Make the server from the system properties, for a provider that reads them */
	default ServerMain.Service create(ServerContext context) throws Exception {
		throw new UnsupportedOperationException(id() + " is made from its configuration");
	}
}
