package us.bringardner.parley.mail.admin.server;

import java.util.Properties;

import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The FTP servers the mail suite can run beside its mail servers ({@code parley.servers=...,ftp,ftps}):
 * parley-ftp's FtpServer with the suite's domains, users and store. Each is made from its own
 * configuration, read from {@code parley-mail.properties}, then {@code parley-mail-ftp.properties}
 * (or the file {@code parley.mail.ftp.configFile} names).
 * <ul>
 * <li>The users are the mail users ({@code parley.mail.users}, the list the mail servers share):
 * a login is {@code name@domain}, the domain must be one of the suite's local domains
 * ({@code domains} in the mail queue's {@code queue.properties}), and the user's READ, WRITE and
 * ADMIN permissions apply.</li>
 * <li>The files are in the mail store ({@code parley.mail.root}, on whatever file system
 * {@code parley.mail.fileSource} names), in the folder {@code <prefix>.root} (default
 * {@code .ftp}) under the store's root: each user's own folder is {@code <domain>/<name>} there, and
 * is all they see. The store's sharing ({@code .parley-store.properties}) lets the FTP servers in.</li>
 * <li>{@code requireTls} (default true): no login before AUTH TLS (or on implicit TLS), so the mail
 * passwords never cross the network in the clear. Needs {@code parley.mail.tls.keyStore}.</li>
 * </ul>
 * Each runs when parley.servers lists it; ftps is skipped with a warning while it has no TLS key store.
 * Settings (after {@code parley.mail.ftp} or {@code parley.mail.ftps}): port (21, 990),
 * root, requireTls, serverId, externalAddress (what passive replies name, behind address
 * translation), bufferSize, dataTimeout, connectTimeout, uploadMode, and passivePort,
 * passiveMinPort, passiveMaxPort.
 *
 * @since 2.0.0
 */
public final class FtpProviders {

	/** The prefix of the settings of the FTP server (explicit TLS) */
	public static final String FTP_PREFIX = "parley.mail.ftp";
	/** The prefix of the settings of the FTP server with implicit TLS */
	public static final String FTPS_PREFIX = "parley.mail.ftps";
	/** The settings file of both */
	public static final String SETTINGS_FILE = "parley-mail-ftp.properties";
	/** The setting naming a file to read instead of {@link #SETTINGS_FILE} */
	public static final String CONFIG_PROP = FTP_PREFIX + ".configFile";
	/** The folder in the store under which every user's folder is (setting after the prefix) */
	public static final String ROOT = "root";
	/** The default of {@link #ROOT}: hidden, so it is no maildrop and no mailbox */
	public static final String DEFAULT_ROOT = ".ftp";
	/** The standard port of FTP */
	public static final int FTP_PORT = 21;
	/** The standard port of FTP with implicit TLS */
	public static final int FTPS_PORT = 990;

	private FtpProviders() {
	}

	/** FTP (port 21, AUTH TLS): {@code ftp} */
	public static final class Ftp extends FtpProvider {
		public Ftp() {
			super("ftp", "FTP", FTP_PREFIX, false, FTP_PORT);
		}
	}

	/** FTP with implicit TLS (port 990): {@code ftps}; it needs a TLS key store */
	public static final class Ftps extends FtpProvider {
		public Ftps() {
			super("ftps", "FTP with implicit TLS", FTPS_PREFIX, true, FTPS_PORT);
		}
	}

	/** One FTP server of the suite */
	abstract static class FtpProvider extends MailServerProvider {

		private final String prefix;
		private final boolean secure;

		FtpProvider(String id, String description, String prefix, boolean secure, int standardPort) {
			super(id, description, SETTINGS_FILE, CONFIG_PROP, prefix, standardPort, secure);
			this.prefix = prefix;
			this.secure = secure;
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties c) throws Exception {
			return SuiteFtp.create(context, c, prefix, secure, port(c));
		}
	}
}
