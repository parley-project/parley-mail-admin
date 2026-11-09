package us.bringardner.parley.mail.admin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import us.bringardner.parley.mail.admin.server.LocalConfDir;

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
 */
public final class AdminHome {

	public static final String HOME_PROPERTY = "parley.mail.admin.home";
	public static final String HOME_ENV = "PARLEY_MAIL_ADMIN_HOME";
	/** Names the settings folder instead of ServerMain.confDir() (system property or context parameter). */
	public static final String SETTINGS_PROPERTY = "parley.mail.admin.settingsDirectory";

	private final File dir;
	private final File settingsDir;

	/** A home whose settings files are in the home too. */
	public AdminHome(File dir) throws IOException {
		this(dir, dir);
	}

	public AdminHome(File dir, File settingsDir) throws IOException {
		this.dir = dir.getCanonicalFile();
		mkdirs(this.dir);
		this.settingsDir = settingsDir.getCanonicalFile();
		mkdirs(this.settingsDir);
	}

	/**
	 * The settings folder: the {@value #SETTINGS_PROPERTY} system property, else the context
	 * parameter, else ServerMain.confDir() if it exists, else the home.
	 *
	 * @throws IOException if CONF isn't on the local file system ({@link LocalConfDir})
	 */
	public static File defaultSettingsDirectory(File home, String contextParameter) throws IOException {
		String tmp = System.getProperty(SETTINGS_PROPERTY);
		if (isBlank(tmp)) {
			tmp = contextParameter;
		}
		if (!isBlank(tmp)) {
			if (tmp.startsWith("~/")) {
				tmp = System.getProperty("user.home") + tmp.substring(1);
			}
			return new File(tmp);
		}
		File conf = LocalConfDir.get();
		return conf != null && conf.isDirectory() ? conf : home;
	}

	/** The home from the system property or environment, else ~/.parley/mail-admin. */
	public static File defaultDirectory(String contextParameter) {
		String tmp = System.getProperty(HOME_PROPERTY);
		if (isBlank(tmp)) {
			tmp = contextParameter;
		}
		if (isBlank(tmp)) {
			tmp = System.getenv(HOME_ENV);
		}
		if (isBlank(tmp)) {
			return new File(new File(System.getProperty("user.home"), ".parley"), "mail-admin");
		}
		if (tmp.startsWith("~/")) {
			tmp = System.getProperty("user.home") + tmp.substring(1);
		}
		return new File(tmp);
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	public File getDirectory() {
		return dir;
	}

	/** The folder of the settings files the servers read. */
	public File getSettingsDirectory() {
		return settingsDir;
	}

	/** The shared settings file, parley-mail.properties. */
	public File settingsFile() {
		return new File(settingsDir, "parley-mail.properties");
	}

	public File defaultUsersFile() {
		return new File(dir, "users.txt");
	}

	public File defaultAliasesFile() {
		return new File(dir, "aliases.txt");
	}

	public File defaultMailRoot() {
		return new File(dir, "mail");
	}

	public File logDirectory() throws IOException {
		return mkdirs(new File(dir, "logs"));
	}

	public File dkimDirectory() throws IOException {
		return mkdirs(new File(dir, "dkim"));
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
			mkdirs(new File(defaultMailRoot(), ".parley-locks"));
		}
		copyTemplate("parley-mail.properties.template", settingsFile());
		copyTemplate("users.txt.template", defaultUsersFile());
		copyTemplate("aliases.txt.template", defaultAliasesFile());
	}

	private void copyTemplate(String resource, File target) throws IOException {
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
		Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private static File mkdirs(File d) throws IOException {
		if (!d.isDirectory() && !d.mkdirs()) {
			throw new IOException("Can't create the directory " + d);
		}
		return d;
	}
}
