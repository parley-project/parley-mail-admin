package us.bringardner.parley.mail.admin.users;

import java.util.Collection;
import java.util.Locale;

/**
 * What a logged-in administrator may manage. An administrator of the primary domain (the first
 * administrator's) manages everything; an administrator of another domain manages only that
 * domain: its users, aliases and DKIM keys, and the queue and history of mail to or from it. A
 * user with the ADMIN permission but no domain in the name manages nothing.
 */
public final class AdminScope {

	private final String user;
	private final String domain;
	private final boolean primary;

	private AdminScope(String user, String domain, boolean primary) {
		this.user = user;
		this.domain = domain;
		this.primary = primary;
	}

	/**
	 * The scope of a user, or null if the user isn't an administrator of any domain (no such user,
	 * no ADMIN permission, or no domain in the name).
	 *
	 * @param primaryDomain the primary domain, or null if none is known yet (then nobody is primary)
	 */
	public static AdminScope of(UserRecord u, String primaryDomain) {
		if (u == null || !u.hasPermission(UserRecord.ADMIN) || u.getDomain() == null || u.getDomain().isEmpty()) {
			return null;
		}
		String d = u.getDomain();
		return new AdminScope(u.getName(), d, primaryDomain != null && d.equalsIgnoreCase(primaryDomain.trim()));
	}

	/** The administrator's name. */
	public String getUser() {
		return user;
	}

	/** The administrator's domain (lower case). */
	public String getDomain() {
		return domain;
	}

	/** True for an administrator of the primary domain, who manages every domain. */
	public boolean isPrimary() {
		return primary;
	}

	/** True if this administrator manages the domain (null: something of no domain). */
	public boolean allowsDomain(String d) {
		if (primary) {
			return true;
		}
		return d != null && d.trim().equalsIgnoreCase(domain);
	}

	/** True if this administrator manages the address (user@domain); one with no domain only for the primary. */
	public boolean allowsAddress(String address) {
		return allowsDomain(domainOf(address));
	}

	/** True if any of the addresses is in this administrator's domains. */
	public boolean allowsAny(Collection<String> addresses) {
		if (primary) {
			return true;
		}
		if (addresses != null) {
			for (String a : addresses) {
				if (allowsAddress(a)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * True if this administrator may give a user this maildrop: the primary any, another
	 * administrator only the default (null or empty) or one under its own domain's folder.
	 */
	public boolean allowsMaildrop(String maildrop) {
		if (primary || maildrop == null || maildrop.trim().isEmpty()) {
			return true;
		}
		String m = maildrop.trim().replace('\\', '/');
		while (m.startsWith("/")) {
			m = m.substring(1);
		}
		return m.toLowerCase(Locale.ROOT).startsWith(domain + "/") && !m.contains("..");
	}

	/** The domain of an address (lower case, without a trailing &gt;), or null if it has none. */
	public static String domainOf(String address) {
		if (address == null) {
			return null;
		}
		String a = address.trim();
		if (a.startsWith("<")) {
			a = a.substring(1);
		}
		if (a.endsWith(">")) {
			a = a.substring(0, a.length() - 1);
		}
		int at = a.lastIndexOf('@');
		if (at < 0 || at == a.length() - 1) {
			return null;
		}
		return a.substring(at + 1).toLowerCase(Locale.ROOT);
	}
}
