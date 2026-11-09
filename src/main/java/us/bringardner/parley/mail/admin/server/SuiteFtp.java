package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.util.Properties;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.net.server.ServerMain;

/**
 * Makes one FTP server of the suite ({@link FtpProviders}).
 *
 * @since 2.0.0
 */
final class SuiteFtp {

	private SuiteFtp() {
	}

	/** An FTP server of the suite: the store's sharing is read when it starts, as the mail servers read it */
	private static final class SuiteFtpServer extends FtpServer {

		private final FileSource mailRoot;

		SuiteFtpServer(int port, boolean secure, String prefix, FileSource folder, FileSource mailRoot) {
			super(port, FTP_NAME, secure, prefix, folder);
			this.mailRoot = mailRoot;
		}

		@Override
		public synchronized void start() {
			try {
				setStoreSharing(MailLocks.sharing(mailRoot));
			} catch (IOException e) {
				logError("Can't start " + getName() + ": " + e.getMessage(), e);
				throw new java.io.UncheckedIOException(e);
			}
			super.start();
		}
	}

	static ServerMain.Service create(ServerContext context, Properties c, String prefix, boolean secure, int port) throws Exception {
		String root = required(c, AbstractMailServer.MAIL_ROOT_PROP);
		// like the mail servers: without a users file no one can log in
		String users = c.getProperty(AbstractMailServer.MAIL_USERS_PROP);
		String source = c.getProperty(AbstractMailServer.MAIL_FILE_SOURCE_PROP);
		Properties connection = MaildropRoot.isUrl(root) ? null
				: MaildropRoot.connectionSettings(c, AbstractMailServer.MAIL_FILE_SOURCE_PROP + ".");
		FileSource mailRoot = MaildropRoot.open(root, source, connection, AbstractMailServer.MAIL_FILE_SOURCE_PROP);
		String queue = c.getProperty("parley.mail.smtp.queue", "").trim();
		StoreDomains domains = new StoreDomains(MaildropRoot.child(mailRoot, queue.isEmpty() ? ".smtp-queue" : queue));
		FileUserRegistry list = users == null || users.trim().isEmpty() ? null : SharedUsers.list(context, FileSource.of(users.trim()).getCanonicalFile(), c.getProperty(FileUserRegistry.PROP_RELOAD_INTERVAL), c);
		FtpUserRegistry registry = new FtpUserRegistry(list, domains, c, prefix);

		FileSource folder;
		if (registry.usesServerDisk()) {
			// the users' roots are folders on this machine's disk, all in the one FtpUserRegistry names
			folder = FileSourceFactory.getDefaultFactory().createFileSource(registry.serverDiskBase());
		} else {
			String folderName = c.getProperty(prefix + "." + FtpProviders.ROOT, FtpProviders.DEFAULT_ROOT).trim();
			folder = MaildropRoot.child(mailRoot, folderName.isEmpty() ? FtpProviders.DEFAULT_ROOT : folderName);
			if (folder.getAbsolutePath().equals(mailRoot.getAbsolutePath())) {
				throw new IOException(prefix + "." + FtpProviders.ROOT + " must be a folder under the mail root, not the root itself");
			}
		}

		FtpServer server = BaseObject.makeWith(c, () -> new SuiteFtpServer(port, secure, prefix, folder, mailRoot));
		server.setUserRegistry(registry);
		configure(server, c, secure);
		if (registry.usesServerDisk() && registry.primaryDomain() == null) {
			server.logInfo("No primary domain (" + FtpUserRegistry.PRIMARY_DOMAIN + "): every user of " + server.getName()
					+ " gets " + prefix + "." + FtpUserRegistry.DOMAIN_ROOT);
		}
		if (list == null) {
			server.logInfo("No users file (" + AbstractMailServer.MAIL_USERS_PROP + "): no one can log in to " + server.getName());
		}
		return ServerMain.service(server, secure ? "FTP server (implicit TLS)" : "FTP server");
	}

	private static void configure(FtpServer server, Properties c, boolean secure) {
		server.setRequireTls(server.getBooleanProperty(server.propertyName("requireTls"), true));
		server.setServerId(server.getProperty(server.propertyName("serverId")));
		String keyStore = c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PROP);
		if (keyStore != null && !keyStore.trim().isEmpty() && server.getProperty(server.propertyName(Server.PROPERTY_KEY_STORE_NAME)) == null) {
			server.setKeyStoreFileName(keyStore.trim());
			String password = c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PASSWORD_PROP);
			if (password != null) {
				server.setKeyStorePassword(password);
			}
			server.setKeyStoreType(c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_TYPE_PROP, "PKCS12"));
		}
		if (server.isRequireTls() && !secure && !server.isTlsAvailable()) {
			server.logInfo("No TLS key store (" + AbstractMailServer.MAIL_TLS_KEY_STORE_PROP + "): no one can log in to " + server.getName()
					+ " while " + server.propertyName("requireTls") + " is true");
		}
		// the transfer settings (bufferSize, the timeouts, uploadMode, the passive ports, linger ...)
		// the server read from this configuration when it was made (FtpServer.readSettings)
	}

	private static String required(Properties c, String name) throws IOException {
		String ret = c.getProperty(name);
		if (ret == null || ret.trim().isEmpty()) {
			throw new IOException("The setting " + name + " is required");
		}
		return ret.trim();
	}
}
