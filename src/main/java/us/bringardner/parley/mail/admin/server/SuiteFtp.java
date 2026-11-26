package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.net.server.ServerMain;

/**
 * Makes one FTP server of the suite ({@link FtpProviders}). The server finds its own settings when it
 * starts, as the mail servers do: {@code parley-mail.properties}, then {@code parley-mail-ftp.properties}
 * (or the file {@code parley.mail.ftp.configFile} names). Its users are the application's registry
 * ({@link ServerContext#users()}); each user's root is the registry's {@code userRoot} attribute, which the
 * application keeps, so the server knows nothing of domains.
 *
 * @since 2.0.0
 */
final class SuiteFtp {

	private SuiteFtp() {
	}

	/**
	 * The prefixes a server also reads the settings the FTP servers share from (the external address and the
	 * passive ports): the FTPS server takes the FTP server's when it has none of its own.
	 */
	static String[] sharedWith(String prefix) {
		return FtpProviders.FTPS_PREFIX.equals(prefix) ? new String[] { FtpProviders.FTP_PREFIX } : new String[0];
	}

	/**
	 * An FTP server of the suite: it opens the mail store when it starts (the store is named in the
	 * suite's settings, which it reads then), puts its own folder in it and claims the store's sharing.
	 */
	/** The setting (after the prefix) naming the permission a user needs to log in */
	static final String LOGIN_PERMISSION = "loginPermission";

	private static final class SuiteFtpServer extends FtpServer {

		/** Every setting the files read so far (the connection settings of the mail store's file system are among them) */
		private final Properties read = new Properties();
		private FileSource mailRoot;
		private String rootProblem;
		/** True when the application gave the users: the users file never replaces them */
		private volatile boolean usersGiven;
		/** The registry made from the users file ({@code parley.mail.users}) when the application gave none */
		private us.bringardner.parley.net.server.FileUserRegistry ownUsers;

		void useUsers(us.bringardner.parley.iam.UserRegistry users) {
			usersGiven = true;
			setUserRegistry(users);
		}

		/**
		 * Without users from the application (a server run on its own) the users are the suite's users file,
		 * as the mail servers have them.
		 */
		private void readUsers() throws IOException {
			if (usersGiven || getProperty(propertyName(AbstractMailServer.AUTHENTICATION_PROVIDER_PROPERTY)) != null) {
				return;
			}
			String file = trimmed(getProperty(AbstractMailServer.MAIL_USERS_PROP));
			if (file == null) {
				return;
			}
			closeOwnUsers();
			us.bringardner.parley.net.server.FileUserRegistry registry = new us.bringardner.parley.net.server.FileUserRegistry();
			registry.initialize(FileSource.of(file).getCanonicalFile());
			ownUsers = registry;
			setUserRegistry(registry);
		}

		private void closeOwnUsers() {
			if (ownUsers != null) {
				try {
					ownUsers.close();
				} catch (IOException | RuntimeException e) {
					logError("Can't close the user registry of " + getName(), e);
				}
				ownUsers = null;
			}
		}

		@Override
		public synchronized void stop() {
			super.stop();
			closeOwnUsers();
		}

		SuiteFtpServer(int port, boolean secure, String prefix) {
			// the folder it serves is the store's, known when its settings are read: until then a folder that is always there
			super(port, FTP_NAME, secure, prefix, placeholderRoot(), sharedWith(prefix));
		}

		private static FileSource placeholderRoot() {
			try {
				return FileSource.of(System.getProperty("java.io.tmpdir"));
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}

		@Override
		protected String getSettingsFileName() {
			return FtpProviders.SETTINGS_FILE;
		}

		@Override
		protected String getSettingsFileProperty() {
			return FtpProviders.CONFIG_PROP;
		}

		/** The suite's settings (the mail root, the TLS key store ...), read before the server's own file */
		@Override
		protected String getSharedSettingsFileName() {
			return "parley-mail.properties";
		}

		@Override
		protected void settingsFileRead(us.bringardner.parley.core.SettingsFile found) {
			super.settingsFileRead(found);
			read.putAll(found.getProperties());
		}

		@Override
		protected void settingsRead(boolean fromFile) throws IOException {
			super.settingsRead(fromFile);
			readUsers();
			rootProblem = null;
			String root = trimmed(getProperty(AbstractMailServer.MAIL_ROOT_PROP));
			if (root == null) {
				rootProblem = "the setting " + AbstractMailServer.MAIL_ROOT_PROP + " is required";
				return;
			}
			try {
				String source = getProperty(AbstractMailServer.MAIL_FILE_SOURCE_PROP);
				Properties all = new Properties();
				all.putAll(read);
				all.putAll(System.getProperties());
				Properties connection = MaildropRoot.isUrl(root) ? null
						: MaildropRoot.connectionSettings(all, AbstractMailServer.MAIL_FILE_SOURCE_PROP + ".");
				mailRoot = MaildropRoot.open(root, source, connection, AbstractMailServer.MAIL_FILE_SOURCE_PROP);
				String folderName = getProperty(propertyName(FtpProviders.ROOT), FtpProviders.DEFAULT_ROOT).trim();
				FileSource folder = MaildropRoot.child(mailRoot, folderName.isEmpty() ? FtpProviders.DEFAULT_ROOT : folderName);
				if (folder.getAbsolutePath().equals(mailRoot.getAbsolutePath())) {
					rootProblem = propertyName(FtpProviders.ROOT) + " must be a folder under the mail root, not the root itself";
					return;
				}
				setFtpRoot(folder);
			} catch (IOException | RuntimeException e) {
				rootProblem = "can't open the mail store " + MaildropRoot.describe(root, null) + ": " + e.getMessage();
			}
			useSuiteKeyStore();
			setRequireTls(getBooleanProperty(propertyName("requireTls"), true));
			setServerId(getProperty(propertyName("serverId")));
		}

		/** The suite's TLS key store, unless this server names one of its own */
		private void useSuiteKeyStore() {
			String keyStore = trimmed(getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PROP));
			if (keyStore != null && getProperty(propertyName(Server.PROPERTY_KEY_STORE_NAME)) == null) {
				setKeyStoreFileName(keyStore);
				String password = getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PASSWORD_PROP);
				if (password != null) {
					setKeyStorePassword(password);
				}
				setKeyStoreType(getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_TYPE_PROP, "PKCS12"));
			}
		}

		/** Only users with the {@code loginPermission} setting's permission log in, when it is set (the application's rule) */
		@Override
		public us.bringardner.parley.iam.AuthenticationResult authenticate(us.bringardner.parley.iam.Credential credential) {
			us.bringardner.parley.iam.AuthenticationResult result = super.authenticate(credential);
			return FtpLogin.admit(requireUserRegistry(), getProperty(propertyName(LOGIN_PERMISSION)), credential, result);
		}

		@Override
		protected void checkSettings(java.util.List<String> problems) {
			super.checkSettings(problems);
			if (rootProblem != null) {
				problems.add(rootProblem);
			}
			if (isSecure()) {
				String own = getKeyStoreFileName();
				if (own == null || own.trim().isEmpty()) {
					problems.add("implicit TLS needs a key store: set " + AbstractMailServer.MAIL_TLS_KEY_STORE_PROP
							+ " (for every mail server) or " + propertyName(Server.PROPERTY_KEY_STORE_NAME) + " (for this one)");
				}
			}
		}

		@Override
		public synchronized void start() {
			if (mailRoot != null) {
				try {
					setStoreSharing(MailLocks.sharing(mailRoot));
				} catch (IOException e) {
					logError("Can't start " + getName() + ": " + e.getMessage(), e);
					throw new java.io.UncheckedIOException(e);
				}
			}
			super.start();
		}

		private static String trimmed(String s) {
			return s == null || s.trim().isEmpty() ? null : s.trim();
		}
	}

	static ServerMain.Service create(ServerContext context, String prefix, boolean secure, int port) {
		SuiteFtpServer server = new SuiteFtpServer(port, secure, prefix);
		if (context.users() != null) {
			server.useUsers(context.users());
		}
		return ServerMain.service(server, secure ? "FTP server (implicit TLS)" : "FTP server");
	}
}
