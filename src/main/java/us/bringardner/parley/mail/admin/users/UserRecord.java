package us.bringardner.parley.mail.admin.users;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One line of the users file (parley-net's FileBasedAcl format):
 * {@code name , credential , PERM|PERM , key=value|key=value}.
 */
public final class UserRecord {

	public static final String READ = "READ";
	public static final String WRITE = "WRITE";
	public static final String ADMIN = "ADMIN";
	public static final String MAILDROP = "maildrop";
	/** The parameter listing the user's groups, separated by {@code ;} (parley-net's FileBasedAcl) */
	public static final String GROUPS = "groups";

	private final String name;
	private String credential;
	private final Set<String> permissions = new LinkedHashSet<>();
	private final Map<String, String> parameters = new LinkedHashMap<>();

	public UserRecord(String name, String credential) {
		this.name = name;
		this.credential = credential;
	}

	/** Parse a line of the file; null if it isn't a user (FileBasedAcl ignores it too). */
	static UserRecord parse(String line) {
		String[] parts = line.split(",");
		if (parts.length < 3) {
			return null; // FileBasedAcl needs name, credential and permissions
		}
		UserRecord u = new UserRecord(parts[0].trim(), parts[1].trim());
		for (String p : parts[2].split("\\|")) {
			if (!p.trim().isEmpty()) {
				u.permissions.add(p.trim());
			}
		}
		if (parts.length > 3) {
			for (String a : parts[3].split("\\|")) {
				String[] kv = a.split("=");
				if (kv.length == 2) {
					u.parameters.put(kv[0].trim(), kv[1].trim());
				}
			}
		}
		return u;
	}

	String format() {
		StringBuilder b = new StringBuilder(name).append(" , ").append(credential).append(" , ").append(String.join("|", permissions));
		if (!parameters.isEmpty()) {
			b.append(" , ");
			boolean first = true;
			for (Map.Entry<String, String> e : parameters.entrySet()) {
				if (!first) {
					b.append('|');
				}
				b.append(e.getKey()).append('=').append(e.getValue());
				first = false;
			}
		}
		return b.toString();
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

	String getCredential() {
		return credential;
	}

	void setCredential(String credential) {
		this.credential = credential;
	}

	/** True if the password is stored hashed (not in plain text). */
	public boolean isHashed() {
		return credential.startsWith("{");
	}

	/** True if the user can log in with SCRAM-SHA-256. */
	public boolean hasScram() {
		return credential.contains("{SCRAM-SHA-256}") || !isHashed();
	}

	public Set<String> getPermissions() {
		return Collections.unmodifiableSet(permissions);
	}

	void setPermissions(Set<String> perms) {
		permissions.clear();
		permissions.addAll(perms);
	}

	public boolean hasPermission(String p) {
		return permissions.contains(p);
	}

	public Map<String, String> getParameters() {
		return Collections.unmodifiableMap(parameters);
	}

	void setParameter(String key, String value) {
		if (value == null || value.isEmpty()) {
			parameters.remove(key);
		} else {
			parameters.put(key, value);
		}
	}

	/** The user's groups (full names, such as sales@bringardner.us), in the order written. */
	public java.util.List<String> getGroups() {
		java.util.List<String> ret = new java.util.ArrayList<>();
		String v = parameters.get(GROUPS);
		if (v != null) {
			for (String g : v.split(";")) {
				if (!g.trim().isEmpty() && !ret.contains(g.trim())) {
					ret.add(g.trim());
				}
			}
		}
		return ret;
	}

	void setGroups(java.util.Collection<String> groups) {
		setParameter(GROUPS, groups == null || groups.isEmpty() ? null : String.join(";", groups));
	}

	/** The maildrop directory relative to the mail root: the parameter, or the name. */
	public String getMaildrop() {
		String m = parameters.get(MAILDROP);
		return m != null ? m : name;
	}
}
