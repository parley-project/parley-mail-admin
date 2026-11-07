package us.bringardner.parley.mail.admin.users;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.PermissionManager;
import us.bringardner.parley.iam.PrincipalChanges;
import us.bringardner.parley.iam.PrincipalManager;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.net.server.FileUserRegistry;

/**
 * The mail suite's users, kept in the user registry the mail servers read (parley-iam), with
 * parley-mail-admin's own rules for them. The Users page, the admin logins and the other pages
 * read and change users only through this.
 * <p>
 * Which registry: when the servers run in this application and one of them has read the users
 * file, the registry they share ({@code SharedUsers}); otherwise one of this service's own over
 * the same file. A change is written by the registry (parley-iam's {@link PrincipalManager} and
 * {@link PermissionManager}); adding or changing a user is one {@link PrincipalChanges}, applied
 * once, so the file is written once and a user is never half made.
 * <p>
 * The application's rules, which the registry doesn't make:
 * <ol>
 * <li>a password has at least {@value #MIN_PASSWORD_LENGTH} characters and no control characters;</li>
 * <li>a name is letters, digits and {@code . _ % + -}, optionally followed by {@code @domain}, and the
 * domain must be a local domain; names are kept in lower case, and a name someone types is
 * lower-cased before it is looked up;</li>
 * <li>a user of a domain gets the maildrop {@code domain/name} unless another is given;</li>
 * <li>a group is named with its domain ({@code sales@example.com}); putting a user in a group
 * that doesn't exist yet makes the group;</li>
 * <li>the last administrator who is turned on can't be deleted, lose ADMIN, or be turned off.</li>
 * </ol>
 * A turned-off user can't log in and gets no mail; the registry gives none of its groups until it
 * is turned on again, so its groups can't be changed while it is off.
 *
 * @since 2.0.0
 */
public final class UserService {

	public static final int MIN_PASSWORD_LENGTH = 8;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}(@[A-Za-z0-9][A-Za-z0-9.\\-]{0,252})?");
	private static final Pattern MAILDROP = Pattern.compile("[A-Za-z0-9._@%+\\-]+(/[A-Za-z0-9._@%+\\-]+)*");
	private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_\\-]+");

	/** Why the users can't be changed when the registry can't change them */
	public static final String READ_ONLY = "The user registry in use can't add, change or delete users, so the Users page is read-only";

	private volatile File file;
	private final Function<File, UserRegistry> shared;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile Predicate<String> domainCheck = d -> true;
	/** This service's own registry of the users file (guarded by this), made when first needed */
	private FileUserRegistry own;

	/**
	 * @param file the users file
	 * @param shared gives the registry the running servers share for a users file (its canonical
	 *            path), or null when none has read it; null for none ever
	 */
	public UserService(File file, Function<File, UserRegistry> shared) {
		this.file = file;
		this.shared = shared;
	}

	public File getFile() {
		return file;
	}

	/** Use another users file (the setting changed). */
	public void setFile(File file) {
		this.file = file;
	}

	/** Names with a domain must have one of these (the local domains). */
	public void setDomainCheck(Predicate<String> check) {
		this.domainCheck = check;
	}

	/** Called after a change, and after the file was changed outside this application. */
	public void addListener(Runnable r) {
		listeners.add(r);
	}

	// ------------------------------------------------------------------ the registry

	/**
	 * The registry the pages use now: the servers' shared one when there is one, else this
	 * service's own (the users file is made, with only its heading, if it doesn't exist).
	 *
	 * @throws IOException if the users file can't be made or read
	 */
	public UserRegistry registry() throws IOException {
		File f = file.getCanonicalFile();
		UserRegistry s = shared == null ? null : shared.apply(f);
		return s != null ? s : own(f);
	}

	private synchronized FileUserRegistry own(File f) throws IOException {
		if (!f.exists()) {
			File dir = f.getParentFile();
			if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("Can't make the folder of the users file " + f);
			}
			Files.write(f.toPath(), "#name , password , permissions , parameters\n".getBytes(StandardCharsets.UTF_8));
		}
		if (own == null || !f.equals(own.getSourceFile())) {
			FileUserRegistry r = new FileUserRegistry();
			r.initialize(f);
			own = r;
		}
		return own;
	}

	/** This service's own registry, if it made one; for telling it the file changed. */
	public synchronized FileUserRegistry ownRegistry() {
		return own;
	}

	/** True when the registry can add, change and delete users (else the Users page is read-only). */
	public boolean isEditable() {
		try {
			UserRegistry r = registry();
			return r instanceof PrincipalManager && r instanceof PermissionManager;
		} catch (IOException e) {
			return false;
		}
	}

	private PrincipalManager manager() throws IOException {
		UserRegistry r = registry();
		if (!(r instanceof PrincipalManager) || !(r instanceof PermissionManager)) {
			throw new IllegalStateException(READ_ONLY);
		}
		return (PrincipalManager) r;
	}

	/** A users file registry reads the file again now if it changed, so the pages show it as it is. */
	private static UserRegistry current(UserRegistry r) {
		if (r instanceof FileUserRegistry) {
			((FileUserRegistry) r).reloadNow();
		}
		return r;
	}

	/**
	 * Read the users file again if it was changed outside this application; the listeners are told.
	 *
	 * @return true if it was changed
	 */
	public boolean reloadIfChanged() {
		FileUserRegistry r = ownRegistry();
		if (r != null && r.reloadNow()) {
			fire();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Every user, turned on or off, by name.
	 *
	 * @throws UncheckedIOException when the registry can't answer now (its cause is the
	 *             {@link IOException}, a {@code UserRegistryUnavailableException} when the registry
	 *             is unavailable)
	 */
	public synchronized List<UserRecord> list() {
		List<UserRecord> ret = new ArrayList<>();
		try {
			UserRegistry r = current(registry());
			if (!(r instanceof PrincipalManager)) {
				return ret; // a registry that can't list its users
			}
			for (ParleyPrincipal p : ((PrincipalManager) r).findPrincipals(null, PrincipalManager.Kind.USER, 0)) {
				ret.add(record(r, p));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e.getMessage(), e);
		}
		return ret;
	}

	/**
	 * The user with this name, turned on or off. The name is lower-cased first, as the pages save
	 * names; a name kept in another case (written outside these pages) is found as given.
	 *
	 * @return the user, or null if there is none
	 * @throws UncheckedIOException when the registry can't answer now (see {@link #list()})
	 */
	public synchronized UserRecord get(String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		String given = name.trim();
		String lower = given.toLowerCase(Locale.ROOT);
		try {
			UserRegistry r = current(registry());
			if (!(r instanceof PrincipalManager)) {
				return null;
			}
			PrincipalManager m = (PrincipalManager) r;
			ParleyPrincipal p = find(m, lower);
			if (p == null && !lower.equals(given)) {
				p = find(m, given);
			}
			return p == null ? null : record(r, p);
		} catch (IOException e) {
			throw new UncheckedIOException(e.getMessage(), e);
		}
	}

	/** @return the user (turned on or off) with exactly this name, or null */
	private static ParleyPrincipal find(PrincipalManager m, String name) throws IOException {
		for (ParleyPrincipal p : m.findPrincipals(name, PrincipalManager.Kind.USER, 0)) {
			if (p.getName().equals(name)) {
				return p;
			}
		}
		return null;
	}

	/** The user as the pages show it, read from the registry. */
	private static UserRecord record(UserRegistry r, ParleyPrincipal p) throws IOException {
		String name = p.getName();
		List<String> permissions = new ArrayList<>();
		if (r instanceof PermissionManager) {
			for (ParleyPermission grant : ((PermissionManager) r).findGrants(p)) {
				// the pages show grants about the whole server (the only ones a users file keeps)
				if (grant.getResourceId() == null) {
					permissions.add(grant.getName());
				}
			}
		}
		Object maildrop = p.getAttribute(UserRecord.MAILDROP);
		boolean enabled = !(r instanceof PrincipalManager) || ((PrincipalManager) r).isEnabled(p);
		List<String> groups = new ArrayList<>();
		for (ParleyPrincipal g : r.findGroups(p)) {
			groups.add(g.getName());
		}
		boolean plainText = r.findStoredCredential(name, StoredCredentialType.PLAIN_TEXT) != null;
		boolean scram = r.findStoredCredential(name, StoredCredentialType.SCRAM_SHA_256) != null;
		return new UserRecord(name, permissions, maildrop == null ? null : maildrop.toString(), groups, enabled, plainText, scram);
	}

	/**
	 * True if some user holds ADMIN, turned on or off. While none does, the first administrator
	 * can be made without logging in, so a turned-off administrator still counts.
	 */
	public boolean hasAdmin() {
		for (UserRecord u : list()) {
			if (u.hasPermission(UserRecord.ADMIN)) {
				return true;
			}
		}
		return false;
	}

	/** The administrators who are turned on: those who can log in to these pages. */
	private int countActiveAdmins() {
		int n = 0;
		for (UserRecord u : list()) {
			if (u.isActiveAdmin()) {
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ changing

	/**
	 * Add a user, turned on, with no groups.
	 *
	 * @param maildrop the directory under the mail root, or null for the default ({@code domain/name}
	 *                 for a user with a domain, the name for one without)
	 */
	public void add(String name, char[] password, Collection<String> permissions, String maildrop) throws IOException {
		add(name, password, permissions, maildrop, null, true);
	}

	/**
	 * Add a user: one change set (the user, its password, maildrop, permissions, groups and
	 * whether it is on), applied once.
	 *
	 * @param groups the user's groups (full names with their domain, sales@example.com), or null
	 *               for none; a group that doesn't exist is made; mailbox ACLs name a group as
	 *               {@code $sales@example.com}
	 * @param enabled false to add the user turned off
	 */
	public void add(String name, char[] password, Collection<String> permissions, String maildrop,
			Collection<String> groups, boolean enabled) throws IOException {
		List<String> grp = groups == null ? new ArrayList<>() : checkGroups(groups);
		String n = checkName(name);
		checkPassword(password);
		Set<String> perms = checkPermissions(permissions);
		String drop = checkMaildrop(maildrop);
		synchronized (this) {
			PrincipalManager m = manager();
			if (get(n) != null) {
				throw new IllegalArgumentException("The user " + n + " already exists");
			}
			PrincipalChanges changes = new PrincipalChanges();
			changes.createUser(n);
			changes.setPassword(n, password);
			String value = drop == null ? defaultMaildrop(n) : drop;
			if (value != null) {
				changes.setAttribute(n, UserRecord.MAILDROP, value);
			}
			for (String p : perms) {
				changes.grant(n, permission(p));
			}
			for (String g : grp) {
				joinGroup(m, changes, n, g);
			}
			if (!enabled) {
				changes.setEnabled(n, false);
			}
			apply(m, changes);
		}
		fire();
	}

	/**
	 * Change a user's password, permissions or maildrop; null leaves that one as it is, and an
	 * empty maildrop means the default.
	 */
	public void update(String name, char[] password, Collection<String> permissions, String maildrop) throws IOException {
		update(name, password, permissions, maildrop, null, null);
	}

	/**
	 * Change a user: one change set, applied once (nothing is written when nothing changes).
	 * A null argument leaves that part as it is.
	 *
	 * @param permissions the permissions the user is to hold (granted and revoked to match)
	 * @param maildrop the maildrop; empty for the default
	 * @param groups the groups the user is to be in (an empty list takes it out of every group); a
	 *            group that doesn't exist is made. Refused for a turned-off user, whose groups the
	 *            registry doesn't give: turn it on first.
	 * @param enabled true to turn the user on, false to turn it off
	 */
	public void update(String name, char[] password, Collection<String> permissions, String maildrop,
			Collection<String> groups, Boolean enabled) throws IOException {
		List<String> grp = groups == null ? null : checkGroups(groups);
		if (password != null) {
			checkPassword(password);
		}
		Set<String> perms = permissions == null ? null : checkPermissions(permissions);
		String drop = maildrop == null ? null : checkMaildrop(maildrop);
		synchronized (this) {
			PrincipalManager m = manager();
			UserRecord u = get(name);
			if (u == null) {
				throw new IllegalArgumentException("No user " + name);
			}
			String n = u.getName();
			if (u.isActiveAdmin() && countActiveAdmins() == 1) {
				if (perms != null && perms.stream().noneMatch(UserRecord.ADMIN::equalsIgnoreCase)) {
					throw new IllegalArgumentException("The last administrator can't lose ADMIN");
				}
				if (Boolean.FALSE.equals(enabled)) {
					throw new IllegalArgumentException("The last administrator can't be turned off");
				}
			}
			if (grp != null && !u.isEnabled() && !grp.isEmpty()) {
				throw new IllegalArgumentException(n + " is turned off, and a turned-off user's groups can't be changed: turn it on first");
			}
			PrincipalChanges changes = new PrincipalChanges();
			if (password != null) {
				changes.setPassword(n, password);
			}
			if (perms != null) {
				for (String held : u.getPermissions()) {
					if (perms.stream().noneMatch(held::equalsIgnoreCase)) {
						changes.revoke(n, permission(held));
					}
				}
				for (String p : perms) {
					if (!u.hasPermission(p)) {
						changes.grant(n, permission(p));
					}
				}
			}
			if (maildrop != null) {
				String value = drop == null ? defaultMaildrop(n) : drop;
				String now = u.getMaildropAttribute();
				if (value == null ? now != null : !value.equals(now)) {
					changes.setAttribute(n, UserRecord.MAILDROP, value);
				}
			}
			if (grp != null && u.isEnabled()) {
				for (String g : u.getGroups()) {
					if (!grp.contains(g)) {
						changes.removeMember(g, n);
					}
				}
				for (String g : grp) {
					if (!u.getGroups().contains(g)) {
						joinGroup(m, changes, n, g);
					}
				}
			}
			if (enabled != null && enabled != u.isEnabled()) {
				changes.setEnabled(n, enabled);
			}
			if (changes.isEmpty()) {
				return;
			}
			apply(m, changes);
		}
		fire();
	}

	/** Delete a user: its mail is kept. */
	public void delete(String name) throws IOException {
		synchronized (this) {
			PrincipalManager m = manager();
			UserRecord u = get(name);
			if (u == null) {
				throw new IllegalArgumentException("No user " + name);
			}
			if (u.isActiveAdmin() && countActiveAdmins() == 1) {
				throw new IllegalArgumentException("The last administrator can't be deleted");
			}
			apply(m, new PrincipalChanges().delete(u.getName()));
		}
		fire();
	}

	/** Put the user in the group, making the group first when it doesn't exist. */
	private static void joinGroup(PrincipalManager m, PrincipalChanges changes, String user, String group) throws IOException {
		ParleyPrincipal p = m.findPrincipal(group);
		if (p == null) {
			changes.createGroup(group);
		} else if (!p.isGroup()) {
			throw new IllegalArgumentException(group + " is a user, not a group");
		}
		changes.addMember(group, user);
	}

	/** Apply the changes, then wipe the passwords they hold. */
	private static void apply(PrincipalManager m, PrincipalChanges changes) throws IOException {
		try {
			m.apply(changes);
		} finally {
			changes.clear();
		}
	}

	private static ParleyPermission permission(String name) {
		return new BasicParleyPermission(name, null);
	}

	/** domain/name for a user of one domain; null (the name) for a user of every domain. */
	private static String defaultMaildrop(String name) {
		int at = name.lastIndexOf('@');
		return at < 0 ? null : name.substring(at + 1) + "/" + name.substring(0, at);
	}

	private void fire() {
		for (Runnable r : listeners) {
			r.run();
		}
	}

	// ------------------------------------------------------------------ the application's checks

	/**
	 * Check group names: each one with its domain (sales@example.com), as mailbox ACLs name them,
	 * and none of the users file's separators.
	 *
	 * @return the names, trimmed, without repeats
	 */
	public static List<String> checkGroups(Collection<String> groups) {
		List<String> ret = new ArrayList<>();
		for (String g : groups) {
			String n = g == null ? "" : g.trim();
			if (n.isEmpty()) {
				continue;
			}
			if (n.startsWith("$")) {
				n = n.substring(1);
			}
			int at = n.lastIndexOf('@');
			if (at <= 0 || at == n.length() - 1 || n.indexOf('@') != at) {
				throw new IllegalArgumentException("A group is named with its domain, like sales@example.com: " + g);
			}
			if (!n.matches("[^\\s,|=;$]+")) {
				throw new IllegalArgumentException("A group name can't have spaces or , | = ; $: " + g);
			}
			if (!ret.contains(n)) {
				ret.add(n);
			}
		}
		return ret;
	}

	private String checkName(String name) {
		if (name == null || !NAME.matcher(name.trim()).matches()) {
			throw new IllegalArgumentException("A user name is letters, digits and . _ % + -, optionally followed by @domain");
		}
		// lower case: SMTP finds a recipient by its address as written or in lower case
		String n = name.trim().toLowerCase(Locale.ROOT);
		int at = n.lastIndexOf('@');
		if (at > 0 && !domainCheck.test(n.substring(at + 1))) {
			throw new IllegalArgumentException(n.substring(at + 1) + " is not one of the local domains (add it on the Domains page first)");
		}
		return n;
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
