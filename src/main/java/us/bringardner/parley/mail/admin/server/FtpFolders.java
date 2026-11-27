package us.bringardner.parley.mail.admin.server;

import java.util.Locale;

/**
 * Folders of the FTP settings {@code primaryRoot} and {@code domainRoot} (absolute paths in the mail store's file
 * system, for example {@code /ftp} and {@code /ftp/{domain}}), in the server package because the FTP server makes
 * a user's domain folder when the user logs in ({@code users.FtpRoots} gives each user's root from the same settings).
 */
public final class FtpFolders {

	private FtpFolders() {
	}

	/**
	 * The folder of a domain's own below the FTP server's root: {@code domainRoot} with the domain filled in,
	 * below the folder the two settings share. The primary domain's users work in the root itself (or a folder
	 * of their own), but their domain's folder is there too.
	 *
	 * @return the folder ("/b.test"), or "/" and the domain when the settings are not usable
	 */
	public static String domainFolder(String primaryRoot, String domainRoot, String domain) {
		String p = folder(primaryRoot);
		String d = folder(domainRoot);
		String name = domain.toLowerCase(Locale.ROOT);
		if (p == null || d == null || d.contains("{name}")) {
			return "/" + name;
		}
		String path = d.replace("{domain}", name);
		String base = base(p, d);
		String rel = base.equals("/") ? path : path.substring(base.length());
		return rel.isEmpty() ? "/" : rel;
	}

	/** The folder every root is in: the deepest folder both have in common (up to the first {domain} or {name}) */
	public static String base(String primary, String domain) {
		String[] a = primary.split("/");
		String[] b = domain.split("/");
		StringBuilder ret = new StringBuilder();
		for (int i = 1; i < a.length && i < b.length; i++) {
			if (b[i].contains("{") || !a[i].equals(b[i])) {
				break;
			}
			ret.append('/').append(a[i]);
		}
		return ret.length() == 0 ? "/" : ret.toString();
	}

	/** An absolute folder without a trailing separator, or null when it is empty or not usable */
	public static String folder(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String v = value.trim().replace('\\', '/');
		while (v.length() > 1 && v.endsWith("/")) {
			v = v.substring(0, v.length() - 1);
		}
		if (!v.startsWith("/")) {
			return null;
		}
		for (String seg : v.split("/")) {
			if (seg.equals("..") || seg.equals(".")) {
				return null;
			}
		}
		return v;
	}
}
