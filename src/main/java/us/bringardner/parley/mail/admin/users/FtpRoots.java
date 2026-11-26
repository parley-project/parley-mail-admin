package us.bringardner.parley.mail.admin.users;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The application's rule for a user's FTP folder, from the FTP settings {@code primaryRoot} (the primary
 * domain's users) and {@code domainRoot} (the other domains', {@code {domain}} filled in), both absolute
 * paths in the mail store's file system, for example {@code /ftp} and {@code /ftp/{domain}}. The folders
 * share a base (the deepest folder they have in common, /ftp here): that is the FTP server's own root
 * (its {@code root} setting), and a user's folder is the path below it ("/" and "/example.com"). With
 * neither setting the primary domain's users get "/" and the others "/" and the domain. A {@code {name}} in
 * {@code domainRoot} is not supported (the rule is per domain, not per user): the default applies.
 *
 * @since 2.0.0
 */
public class FtpRoots {

	private FtpRoots() {
	}

	/**
	 * @param primaryRoot gives the {@code primaryRoot} setting now, or null
	 * @param domainRoot gives the {@code domainRoot} setting now, or null
	 * @param primaryDomain gives the primary domain now, or null
	 * @return the rule {@link UserService#setUserRoots} takes; each call reads the settings again
	 */
	public static Function<String, String> rule(Supplier<String> primaryRoot, Supplier<String> domainRoot,
			Supplier<String> primaryDomain) {
		return domain -> root(primaryRoot.get(), domainRoot.get(), primaryDomain.get(), domain);
	}

	/** The folder (below the FTP server's root) of a domain's users */
	static String root(String primaryRoot, String domainRoot, String primaryDomain, String domain) {
		boolean primary = domain.equalsIgnoreCase(primaryDomain);
		String p = folder(primaryRoot);
		String d = folder(domainRoot);
		if (p == null || d == null || d.contains("{name}")) {
			return primary ? "/" : "/" + domain;
		}
		String base = base(p, d);
		String path = primary ? p : d.replace("{domain}", domain.toLowerCase(Locale.ROOT));
		String rel = base.equals("/") ? path : path.substring(base.length());
		return rel.isEmpty() ? "/" : rel;
	}

	/** The folder every root is in: the deepest folder both have in common (up to the first {domain} or {name}) */
	static String base(String primary, String domain) {
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
	private static String folder(String value) {
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
