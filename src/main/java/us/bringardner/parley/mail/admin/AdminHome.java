package us.bringardner.parley.mail.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The directory that holds the admin application's own files: the user and alias files (unless
 * the settings put them elsewhere), its log files and the DKIM keys made from the web pages. The
 * mail itself goes to {@code parley.mail.root}, which is {@code <home>/mail} in the settings made
 * on the first start.
 * <p>
 * The home is, in order: the {@value #HOME_PROPERTY} system property, the servlet context
 * parameter of the same name, the {@value #HOME_ENV} environment variable, or
 * {@code ~/.parley/mail-admin}.
 * <p>
 * The settings files (parley-mail.properties, parley-smtp.properties ...) are in the settings
 * folder, the one the servers read: the {@value #SETTINGS_PROPERTY} system property or context
 * parameter, else parley-net's {@code ServerMain.confDir()} (/etc/parley, or the folder of the file
 * PARLEY_CONF names) when it exists, else the home.
 * <p>
 * Every folder here is on the default file system ({@link FileSource#of(String)}: the local disk
 * unless {@code parley.files.defaultFactory} names another), as the servers' settings are; a value
 * may also be a {@code filesource:} URL. Only the DKIM keys' folder is always on the local disk
 * ({@link #dkimDirectory()}).
 */
public final class AdminHome {

	public static final String HOME_PROPERTY = "parley.mail.admin.home";
	public static final String HOME_ENV = "PARLEY_MAIL_ADMIN_HOME";
	/** Names the settings folder instead of ServerMain.confDir() (system property or context parameter). */
	public static final String SETTINGS_PROPERTY = "parley.mail.admin.settingsDirectory";

	private final FileSource dir;
	private final FileSource settingsDir;

	/** A home whose settings files are in the home too. */
	public AdminHome(FileSource dir) throws IOException {
		this(dir, dir);
	}

	public AdminHome(FileSource dir, FileSource settingsDir) throws IOException {
		this.dir = mkdirs(dir).getCanonicalFile();
		this.settingsDir = mkdirs(settingsDir).getCanonicalFile();
	}

	/**
	 * The settings folder: the {@value #SETTINGS_PROPERTY} system property, else the context
	 * parameter, else ServerMain.confDir() if it exists, else the home.
	 *
	 * @throws IOException if the file system the folder is on can't be reached
	 */
	public static FileSource defaultSettingsDirectory(FileSource home, String contextParameter) throws IOException {
		String tmp = System.getProperty(SETTINGS_PROPERTY);
		if (isBlank(tmp)) {
			tmp = contextParameter;
		}
		if (!isBlank(tmp)) {
			return FileSource.of(expandHome(tmp));
		}
		FileSource conf = ServerMain.confDir();
		return conf != null && conf.isDirectory() ? conf : home;
	}

	/**
	 * The home from the system property or environment, else ~/.parley/mail-admin.
	 *
	 * @throws IOException if the file system the folder is on can't be reached
	 */
	public static FileSource defaultDirectory(String contextParameter) throws IOException {
		String tmp = System.getProperty(HOME_PROPERTY);
		if (isBlank(tmp)) {
			tmp = contextParameter;
		}
		if (isBlank(tmp)) {
			tmp = System.getenv(HOME_ENV);
		}
		if (isBlank(tmp)) {
			return FileSource.of(System.getProperty("user.home")).getChild(".parley").getChild("mail-admin");
		}
		return FileSource.of(expandHome(tmp));
	}

	private static String expandHome(String path) {
		String p = path.trim();
		return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	public FileSource getDirectory() {
		return dir;
	}

	/** The folder of the settings files the servers read. */
	public FileSource getSettingsDirectory() {
		return settingsDir;
	}

	/** The shared settings file, parley-mail.properties. */
	public FileSource settingsFile() throws IOException {
		return settingsDir.getChild("parley-mail.properties");
	}

	public FileSource defaultUsersFile() throws IOException {
		return dir.getChild("users.txt");
	}

	public FileSource defaultAliasesFile() throws IOException {
		return dir.getChild("aliases.txt");
	}

	public FileSource defaultMailRoot() throws IOException {
		return dir.getChild("mail");
	}

	public FileSource logDirectory() throws IOException {
		return mkdirs(dir.getChild("logs"));
	}

	/**
	 * The folder of the DKIM keys made from the web pages: the home's {@code dkim} folder, always on
	 * the local disk (a FileProxy), since parley-smtp's DKIM signer reads its key files there
	 * (java.io.File); it is the home's path on the local disk when the home is on another file system.
	 */
	public FileSource dkimDirectory() throws IOException {
		return mkdirs(FileSourceFactory.fileProxyFactory.createFileSource(dir.getChild("dkim").getAbsolutePath()));
	}

	/**
	 * Write the shared settings file (in the settings folder), and the users and aliases files of a
	 * new home, from the templates in this package, unless they exist. {@code @HOME@} in a template
	 * is replaced by the home directory.
	 */
	public void createMissingFiles() throws IOException {
		if (!settingsFile().exists()) {
			// a new home: its mail root keeps its file locks with it (parley-files looks for a
			// .parley-locks directory in or above a file; without one it warns and uses a private one)
			mkdirs(defaultMailRoot().getChild(".parley-locks"));
		}
		copyTemplate("parley-mail.properties.template", settingsFile());
		copyTemplate("users.txt.template", defaultUsersFile());
		copyTemplate("aliases.txt.template", defaultAliasesFile());
	}

	private void copyTemplate(String resource, FileSource target) throws IOException {
		if (target.exists()) {
			return;
		}
		String text;
		try (InputStream in = AdminHome.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("Missing template " + resource);
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		// forward slashes work everywhere, and need no escaping in a properties file
		text = text.replace("@HOME@", dir.getPath().replace('\\', '/'));
		try (OutputStream out = target.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static FileSource mkdirs(FileSource d) throws IOException {
		if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
			throw new IOException("Can't create the directory " + d.getPath());
		}
		return d;
	}
}
