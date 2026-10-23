package us.bringardner.parley.mail.admin.users;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import us.bringardner.parley.net.server.FileBasedAcl;

/**
 * The users file, edited by the Users page. Comments and the order of the lines are kept.
 * Passwords are stored as PBKDF2 hashes with SCRAM-SHA-256 and SCRAM-SHA-1 credentials
 * ({@link FileBasedAcl#hashPasswordWithScram}), so every login mechanism works.
 * <p>
 * Names are kept in lower case. A user named {@code name@domain} gets the mail of that domain only, in the maildrop
 * {@code domain/name} unless another is given; a user without a domain gets mail for every local
 * domain (see parley-smtp's virtual domains).
 */
public final class UserStore {

	public static final int MIN_PASSWORD_LENGTH = 8;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}(@[A-Za-z0-9][A-Za-z0-9.\\-]{0,252})?");
	private static final Pattern MAILDROP = Pattern.compile("[A-Za-z0-9._@%+\\-]+(/[A-Za-z0-9._@%+\\-]+)*");
	private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_\\-]+");

	/** A line of the file: a comment or blank (user null) or a user. */
	private static final class Line {
		final String text;
		UserRecord user;

		Line(String text, UserRecord user) {
			this.text = text;
			this.user = user;
		}
	}

	private volatile File file;
	private final List<Line> lines = new ArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile Predicate<String> domainCheck = d -> true;
	private long loadedModified;
	private long loadedLength;

	public UserStore(File file) {
		this.file = file;
	}

	public File getFile() {
		return file;
	}

	/** Use another file (the setting changed); it is read now. */
	public synchronized void setFile(File file) throws IOException {
		this.file = file;
		load();
	}

	/** Names with a domain must have one of these (the local domains). */
	public void setDomainCheck(Predicate<String> check) {
		this.domainCheck = check;
	}

	/** Called after the file is written or read again. */
	public void addListener(Runnable r) {
		listeners.add(r);
	}

	public synchronized void load() throws IOException {
		lines.clear();
		if (!file.exists()) {
			File dir = file.getAbsoluteFile().getParentFile();
			if (dir != null) {
				dir.mkdirs();
			}
			Files.write(file.toPath(), "#name , password , permissions , parameters\n".getBytes(StandardCharsets.UTF_8));
		}
		loadedModified = file.lastModified();
		loadedLength = file.length();
		String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		for (String l : text.split("\r?\n", -1)) {
			String t = l.trim();
			UserRecord u = t.isEmpty() || t.startsWith("#") ? null : UserRecord.parse(t);
			if (u != null) {
				// a later line wins, as in FileBasedAcl
				for (Line old : lines) {
					if (old.user != null && old.user.getName().equals(u.getName())) {
						old.user = null;
					}
				}
			}
			lines.add(new Line(l, u));
		}
		while (!lines.isEmpty() && lines.get(lines.size() - 1).user == null && lines.get(lines.size() - 1).text.isEmpty()) {
			lines.remove(lines.size() - 1);
		}
	}

	/** Read the file again if something else changed it; true if it did. */
	public synchronized boolean reloadIfChanged() throws IOException {
		if (file.lastModified() == loadedModified && file.length() == loadedLength) {
			return false;
		}
		load();
		fire();
		return true;
	}

	public synchronized List<UserRecord> list() {
		List<UserRecord> ret = new ArrayList<>();
		for (Line l : lines) {
			if (l.user != null) {
				ret.add(l.user);
			}
		}
		return ret;
	}

	public synchronized UserRecord get(String name) {
		for (Line l : lines) {
			if (l.user != null && l.user.getName().equalsIgnoreCase(name)) {
				return l.user;
			}
		}
		return null;
	}

	/** True if some user has the ADMIN permission. */
	public synchronized boolean hasAdmin() {
		for (UserRecord u : list()) {
			if (u.hasPermission(UserRecord.ADMIN)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Add a user.
	 *
	 * @param maildrop the directory under the mail root, or null for the default ({@code domain/name}
	 *                 for a user with a domain, the name for one without)
	 */
	public void add(String name, char[] password, Collection<String> permissions, String maildrop) throws IOException {
		name = checkName(name);
		checkPassword(password);
		Set<String> perms = checkPermissions(permissions);
		String drop = checkMaildrop(maildrop);
		synchronized (this) {
			if (get(name) != null) {
				throw new IllegalArgumentException("The user " + name + " already exists");
			}
			UserRecord u = new UserRecord(name, FileBasedAcl.hashPasswordWithScram(password));
			u.setPermissions(perms);
			u.setParameter(UserRecord.MAILDROP, drop == null ? defaultMaildrop(u) : drop);
			lines.add(new Line(null, u));
			save();
		}
		fire();
	}

	/**
	 * Change a user's password, permissions or maildrop; null leaves that one as it is, and an
	 * empty maildrop means the default.
	 */
	public void update(String name, char[] password, Collection<String> permissions, String maildrop) throws IOException {
		if (password != null) {
			checkPassword(password);
		}
		Set<String> perms = permissions == null ? null : checkPermissions(permissions);
		String drop = maildrop == null ? null : checkMaildrop(maildrop);
		synchronized (this) {
			UserRecord u = get(name);
			if (u == null) {
				throw new IllegalArgumentException("No user " + name);
			}
			if (perms != null && u.hasPermission(UserRecord.ADMIN) && !perms.contains(UserRecord.ADMIN) && countAdmins() == 1) {
				throw new IllegalArgumentException("The last administrator can't lose ADMIN");
			}
			if (password != null) {
				u.setCredential(FileBasedAcl.hashPasswordWithScram(password));
			}
			if (perms != null) {
				u.setPermissions(perms);
			}
			if (maildrop != null) {
				u.setParameter(UserRecord.MAILDROP, drop == null ? defaultMaildrop(u) : drop);
			}
			save();
		}
		fire();
	}

	public void delete(String name) throws IOException {
		synchronized (this) {
			UserRecord u = get(name);
			if (u == null) {
				throw new IllegalArgumentException("No user " + name);
			}
			if (u.hasPermission(UserRecord.ADMIN) && countAdmins() == 1) {
				throw new IllegalArgumentException("The last administrator can't be deleted");
			}
			for (Line l : lines) {
				if (l.user == u) {
					l.user = null;
				}
			}
			lines.removeIf(l -> l.user == null && l.text == null);
			save();
		}
		fire();
	}

	/** domain/name for a user of one domain; null (the name) for a user of every domain. */
	private static String defaultMaildrop(UserRecord u) {
		return u.getDomain() == null ? null : u.getDomain() + "/" + u.getLocalPart();
	}

	private int countAdmins() {
		int n = 0;
		for (UserRecord u : list()) {
			if (u.hasPermission(UserRecord.ADMIN)) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Check a password.
	 *
	 * @return the user, or null if there's no such user or the password is wrong
	 */
	public UserRecord authenticate(String name, char[] password) {
		UserRecord u = name == null ? null : get(name.trim());
		if (u == null || password == null) {
			// spend the same time, so a guesser can't tell which names exist
			FileBasedAcl.verifyPassword("x".toCharArray(), DUMMY_HASH);
			return null;
		}
		String stored = u.getCredential();
		boolean ok;
		if (stored.startsWith(FileBasedAcl.HASH_PREFIX)) {
			String hash = stored.split(";")[0].trim();
			ok = FileBasedAcl.verifyPassword(password, hash);
		} else if (stored.startsWith("{")) {
			ok = false; // a credential this application doesn't know
		} else {
			ok = MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), new String(password).getBytes(StandardCharsets.UTF_8));
		}
		return ok ? u : null;
	}

	private static final String DUMMY_HASH = FileBasedAcl.hashPassword("not a password".toCharArray());

	private synchronized void save() throws IOException {
		StringBuilder out = new StringBuilder();
		for (Line l : lines) {
			if (l.user != null) {
				out.append(l.user.format()).append('\n');
			} else if (l.text != null) {
				String t = l.text.trim();
				if (t.isEmpty() || t.startsWith("#")) {
					out.append(l.text).append('\n');
				}
				// a deleted user's original line is dropped
			}
		}
		File dir = file.getAbsoluteFile().getParentFile();
		File tmp = new File(dir, "." + file.getName() + ".tmp");
		Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
		try {
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		// the edited lines replace what was read: reread to keep line texts and file stamps in step
		load();
	}

	private void fire() {
		for (Runnable r : listeners) {
			r.run();
		}
	}

	private String checkName(String name) {
		if (name == null || !NAME.matcher(name.trim()).matches()) {
			throw new IllegalArgumentException("A user name is letters, digits and . _ % + -, optionally followed by @domain");
		}
		// lower case: SMTP finds a recipient by its address as written or in lower case
		name = name.trim().toLowerCase(Locale.ROOT);
		int at = name.lastIndexOf('@');
		if (at > 0 && !domainCheck.test(name.substring(at + 1))) {
			throw new IllegalArgumentException(name.substring(at + 1) + " is not one of the local domains (add it on the Domains page first)");
		}
		return name;
	}

	private static void checkPassword(char[] password) {
		if (password == null || password.length < MIN_PASSWORD_LENGTH) {
			throw new IllegalArgumentException("A password needs at least " + MIN_PASSWORD_LENGTH + " characters");
		}
		for (char c : password) {
			if (c < ' ') {
				throw new IllegalArgumentException("A password can't contain control characters");
			}
		}
	}

	private static Set<String> checkPermissions(Collection<String> permissions) {
		Set<String> ret = new LinkedHashSet<>();
		if (permissions != null) {
			for (String p : permissions) {
				if (p == null || !PERMISSION.matcher(p.trim()).matches()) {
					throw new IllegalArgumentException("Invalid permission '" + p + "'");
				}
				ret.add(p.trim().toUpperCase(Locale.ROOT));
			}
		}
		return ret;
	}

	private static String checkMaildrop(String maildrop) {
		if (maildrop == null || maildrop.trim().isEmpty()) {
			return null;
		}
		String m = maildrop.trim();
		if (!MAILDROP.matcher(m).matches() || m.contains("..")) {
			throw new IllegalArgumentException("A maildrop is a path under the mail root, like example.com/tony");
		}
		return m;
	}
}
