package us.bringardner.parley.mail.admin.users;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A user as the Users page shows it, read from the user registry by {@link UserService}: its name,
 * the permissions granted to it, its maildrop, its groups, and whether it is turned on. A value: a
 * change goes through {@link UserService}, which then reads the user again.
 */
public final class UserRecord {

	public static final String READ = "READ";
	public static final String WRITE = "WRITE";
	public static final String ADMIN = "ADMIN";
	/** The attribute naming the user's maildrop (the servers read it too) */
	public static final String MAILDROP = "maildrop";

	private final String name;
	private final Set<String> permissions;
	private final String maildrop;
	private final List<String> groups;
	private final boolean enabled;
	private final boolean plainText;
	private final boolean scram;

	/**
	 * @param maildrop the maildrop attribute, or null when the user has none
	 * @param groups the user's groups; empty for a turned-off user, whose groups the registry doesn't give
	 * @param plainText true when the password is kept in plain text
	 * @param scram true when the user can log in with SCRAM-SHA-256
	 */
	UserRecord(String name, Collection<String> permissions, String maildrop, Collection<String> groups,
			boolean enabled, boolean plainText, boolean scram) {
		this.name = name;
		this.permissions = Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
		this.maildrop = maildrop;
		this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
		this.enabled = enabled;
		this.plainText = plainText;
		this.scram = scram;
	}

	public String getName() {
		return name;
	}

	/** The domain part of the name (lower case), or null for a user of every domain. */
	public String getDomain() {
		int at = name.lastIndexOf('@');
		return at < 0 ? null : name.substring(at + 1).toLowerCase(Locale.ROOT);
	}

	/** The name without the domain. */
	public String getLocalPart() {
		int at = name.lastIndexOf('@');
		return at < 0 ? name : name.substring(0, at);
	}

	/** False when the password is kept in plain text. */
	public boolean isHashed() {
		return !plainText;
	}

	/** True if the user can log in with SCRAM-SHA-256. */
	public boolean hasScram() {
		return scram;
	}

	/** True when the user is turned on: it can log in and get mail. */
	public boolean isEnabled() {
		return enabled;
	}

	public Set<String> getPermissions() {
		return permissions;
	}

	/**
	 * True if the user holds this permission, in any case: parley-iam's permission names ignore
	 * case, so {@code admin} and {@code ADMIN} are one permission to the servers too.
	 */
	public boolean hasPermission(String p) {
		for (String held : permissions) {
			if (held.equalsIgnoreCase(p)) {
				return true;
			}
		}
		return false;
	}

	/** True for a user that is turned on and holds ADMIN: one who can use the admin pages. */
	public boolean isActiveAdmin() {
		return enabled && hasPermission(ADMIN);
	}

	/**
	 * The user's groups (full names, such as sales@bringardner.us). Empty for a turned-off user:
	 * the registry doesn't say which groups it is in until it is turned on again.
	 */
	public List<String> getGroups() {
		return groups;
	}

	/** The maildrop attribute, or null when the user has none (its maildrop is then its name). */
	String getMaildropAttribute() {
		return maildrop;
	}

	/** The maildrop directory relative to the mail root: the attribute, or the name. */
	public String getMaildrop() {
		return maildrop != null ? maildrop : name;
	}
}
