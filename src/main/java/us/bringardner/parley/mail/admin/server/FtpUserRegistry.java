package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.BasicParleyPermission;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.net.server.IDomainList;

/**
 * The mail suite's FTP user registry: who may log in to the suite's FTP servers and where each
 * user's root is, set by the administrator with the server's settings (after its prefix,
 * {@code parley.mail.ftp} or {@code parley.mail.ftps}). The users are the mail users of the suite's
 * local domains ({@link SuiteUserRegistry}).
 * <ul>
 * <li>{@code loginPermission}: only users the shared user registry gives this permission may log in
 * (admin, say; the registry decides, and names ignore case). Not set: every mail user of a local
 * domain. A registry that can't answer the check is a temporary failure (FTP's 421), never a
 * refusal.</li>
 * <li>{@code primaryRoot}: the root of the primary domain's users ({@code parley.mail.admin.primaryDomain}),
 * a folder on the server's own disk (/data/home, say).</li>
 * <li>{@code domainRoot}: the root of the other domains' users, a folder on the server's own disk in
 * which {@code {domain}} is the user's domain and {@code {name}} their name (/data/home/{domain}, say).</li>
 * </ul>
 * {@code primaryRoot} and {@code domainRoot} go together: with neither, each user's root is their
 * folder in the store ({@code <root>/<domain>/<name>}, as before); with both, the roots are on the
 * server's disk, and the FTP server's own root is the deepest folder they have in common (/data/home
 * for the examples), where it keeps its lock folder. A root that doesn't exist is made when its user
 * logs in, so the server's account needs to be able to write there. Users can't leave their root.
 * <p>
 * Before 2.0.0 this was {@code FtpAcl}, a parley-net access control list; the settings are unchanged.
 *
 * @since 2.0.0
 */
final class FtpUserRegistry extends SuiteUserRegistry {

	/** The setting (after the server's prefix): the permission a user needs to log in */
	static final String LOGIN_PERMISSION = "loginPermission";
	/** The setting (after the server's prefix): the primary domain's users' root on the server's disk */
	static final String PRIMARY_ROOT = "primaryRoot";
	/** The setting (after the server's prefix): the other domains' users' root on the server's disk */
	static final String DOMAIN_ROOT = "domainRoot";
	/** The suite's primary domain (parley-mail-admin's setting, in parley-mail.properties) */
	static final String PRIMARY_DOMAIN = "parley.mail.admin.primaryDomain";

	private final ParleyPermission loginPermission;
	private final String primaryDomain;
	private final String primaryRoot;
	private final String domainRoot;
	private final String base;

	/**
	 * @param users the user registry the mail servers share; null: no one can log in
	 * @param domains the suite's local domains
	 * @param c the server's configuration
	 * @param prefix the server's prefix
	 * @throws IOException if only one of the roots is set, or a root isn't an absolute path
	 */
	FtpUserRegistry(UserRegistry users, IDomainList domains, Properties c, String prefix) throws IOException {
		super(users, domains);
		String p = setting(c, prefix + "." + LOGIN_PERMISSION);
		this.loginPermission = p == null ? null : new BasicParleyPermission(p, null);
		String d = setting(c, PRIMARY_DOMAIN);
		this.primaryDomain = d == null ? null : d.toLowerCase(Locale.ROOT);
		this.primaryRoot = folder(setting(c, prefix + "." + PRIMARY_ROOT), prefix + "." + PRIMARY_ROOT);
		this.domainRoot = folder(setting(c, prefix + "." + DOMAIN_ROOT), prefix + "." + DOMAIN_ROOT);
		if ((primaryRoot == null) != (domainRoot == null)) {
			throw new IOException(prefix + "." + PRIMARY_ROOT + " and " + prefix + "." + DOMAIN_ROOT + " go together: set both or neither");
		}
		this.base = primaryRoot == null ? null : base(primaryRoot, domainRoot);
	}

	/**
	 * The folder every root is in: the deepest folder primaryRoot and domainRoot (up to its first
	 * {domain} or {name}) have in common. It is the FTP server's own root, where the server keeps
	 * its lock folder, so the server's account must be able to write there.
	 */
	private static String base(String primary, String domain) {
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

	/** @return the folder on the server's disk every user's root is in (the FTP server's root), or null in the store */
	String serverDiskBase() {
		return base;
	}

	/** @return true if the roots are on the server's disk (primaryRoot and domainRoot are set) */
	boolean usesServerDisk() {
		return primaryRoot != null;
	}

	/** @return the primary domain, or null if none is set */
	String primaryDomain() {
		return primaryDomain;
	}

	/** With {@code loginPermission} set, the shared registry's answer to that permission check. */
	@Override
	AuthorizationResult admits(Requester loggedIn) {
		if (loginPermission == null) {
			return super.admits(loggedIn);
		}
		return checkPermission(loggedIn, loginPermission, null);
	}

	@Override
	String root(String domain, String name) {
		if (!usesServerDisk()) {
			return super.root(domain, name);
		}
		String template = domain.equals(primaryDomain) ? primaryRoot : domainRoot;
		String path = template.replace("{domain}", domain).replace("{name}", name);
		// a path in the server's root (the base)
		String rel = base.equals("/") ? path : path.substring(base.length());
		return rel.isEmpty() ? "/" : rel;
	}

	private static String setting(Properties c, String name) {
		String v = c.getProperty(name);
		return v == null || v.trim().isEmpty() ? null : v.trim();
	}

	/** An absolute folder, without a trailing separator, whose {domain} and {name} are whole folder names */
	private static String folder(String value, String name) throws IOException {
		if (value == null) {
			return null;
		}
		String v = value.replace('\\', '/');
		while (v.length() > 1 && v.endsWith("/")) {
			v = v.substring(0, v.length() - 1);
		}
		if (!v.startsWith("/")) {
			throw new IOException(name + " must be an absolute path: " + value);
		}
		for (String seg : v.split("/")) {
			if (seg.equals("..") || seg.equals(".")
					|| (seg.contains("{") && !seg.equals("{domain}") && !seg.equals("{name}"))) {
				throw new IOException(name + ": a folder can't be . or .., and {domain} or {name} must be a whole folder name: " + value);
			}
		}
		return v;
	}
}
