package us.bringardner.parley.mail.admin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The directory that holds the mail suite's own files: the settings, the user and alias files
 * (unless the settings put them elsewhere), the history of the SMTP queue, the log files and the
 * DKIM keys made from the web pages. The mail itself goes to {@code parley.mail.root}, which is
 * {@code <home>/mail} in the settings made on the first start.
 * <p>
 * The home is, in order: the {@value #HOME_PROPERTY} system property, the servlet context
 * parameter of the same name, the {@value #HOME_ENV} environment variable, or
 * {@code ~/.parley/mail-admin}.
 */
public final class AdminHome {

	public static final String HOME_PROPERTY = "parley.mail.admin.home";
	public static final String HOME_ENV = "PARLEY_MAIL_ADMIN_HOME";

	private final File dir;

	public AdminHome(File dir) throws IOException {
		this.dir = dir.getCanonicalFile();
		mkdirs(this.dir);
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

	public File settingsFile() {
		return new File(dir, "parley-mail.properties");
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

	public File historyDirectory() throws IOException {
		return mkdirs(new File(dir, "history"));
	}

	public File logDirectory() throws IOException {
		return mkdirs(new File(dir, "logs"));
	}

	public File dkimDirectory() throws IOException {
		return mkdirs(new File(dir, "dkim"));
	}

	/**
	 * Write the settings, users and aliases files of a new home from the templates in this
	 * package, unless they exist. {@code @HOME@} in a template is replaced by the home directory.
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
