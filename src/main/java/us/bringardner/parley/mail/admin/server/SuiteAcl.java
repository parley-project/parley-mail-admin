package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.AbstractPrincipal;
import us.bringardner.parley.net.server.AccessControlUnavailableException;
import us.bringardner.parley.net.server.DomainListUnavailableException;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IDomainList;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.IServer;

/**
 * The mail suite's users, for FTP: a login is {@code name@domain} as in the user list the mail
 * servers share, and the domain must be one of the suite's local domains. Each user gets a root of
 * their own, {@code /<domain>/<name>} in the server's folder of the mail store, which FTP makes when
 * they first log in; the user list's permissions (READ, WRITE, ADMIN) apply as they are.
 *
 * @since 2.0.0
 */
class SuiteAcl implements IAccessControlList {

	private final IAccessControlList users;
	private final IDomainList domains;

	/**
	 * @param users the user list the mail servers share; null: no one can log in
	 * @param domains the suite's local domains
	 */
	SuiteAcl(IAccessControlList users, IDomainList domains) {
		this.users = users;
		this.domains = domains;
	}

	@Override
	public void initialize(IServer server) throws IOException {
		// both are made ready by the provider
	}

	@Override
	public boolean checkPermission(IPrincipal user, IPermission action) {
		return users != null && users.checkPermission(user, action);
	}

	@Override
	public IPrincipal getPrincipal(String user) {
		try {
			return findPrincipal(user);
		} catch (AccessControlUnavailableException e) {
			return null;
		}
	}

	@Override
	public IPrincipal findPrincipal(String user) throws AccessControlUnavailableException {
		if (user == null || users == null) {
			return null;
		}
		int at = user.lastIndexOf('@');
		if (at <= 0 || at == user.length() - 1) {
			return null; // the mail users are name@domain
		}
		String domain = user.substring(at + 1).toLowerCase(Locale.ROOT);
		try {
			if (!domains.contains(domain)) {
				return null;
			}
		} catch (DomainListUnavailableException e) {
			throw new AccessControlUnavailableException(e.getMessage(), e);
		}
		IPrincipal p = users.findPrincipal(user);
		if (p == null) {
			return null;
		}
		String name = p.getName().substring(0, p.getName().lastIndexOf('@'));
		if (!safe(name) || !admits(p)) {
			return null; // a name that would be a path, or a user who may not log in
		}
		return new Rooted(p, root(domain, name));
	}

	/**
	 * @param user the user list's principal of a user of a local domain
	 * @return true if they may log in to FTP; here, every such user
	 */
	boolean admits(IPrincipal user) {
		return true;
	}

	/**
	 * The user's root, a path in the server's root that the user can't leave; here their folder in
	 * the FTP folder of the store, {@code /<domain>/<name>}.
	 *
	 * @param domain the user's domain (a local domain, in lower case)
	 * @param name the user's name without the domain (one folder name)
	 */
	String root(String domain, String name) {
		return "/" + domain + "/" + name;
	}

	/** A name that is one folder name: no separators, no dot names, no control characters */
	static boolean safe(String name) {
		if (name.isEmpty() || name.equals(".") || name.equals("..")) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '/' || c == '\\' || c == ':' || c < ' ' || c == 127) {
				return false;
			}
		}
		return true;
	}

	/** The user list checks the password (a Rooted view is looked up by its name there). */
	@Override
	public boolean verifyPassword(IPrincipal user, byte[] credentials) throws AccessControlUnavailableException {
		if (users == null || user == null) {
			return false;
		}
		IPrincipal target = user instanceof Rooted ? ((Rooted) user).target : user;
		return users.verifyPassword(target, credentials);
	}

	@Override
	public void authenticateUnknownUser(byte[] credentials) {
		if (users != null) {
			users.authenticateUnknownUser(credentials);
		}
	}

	/** The list's user with the root FTP gives them; the list's own principal stays as it is. */
	private static final class Rooted extends AbstractPrincipal {

		private final IPrincipal target;

		Rooted(IPrincipal target, String root) {
			super(target.getName());
			this.target = target;
			setPermissions(target.getPermissions());
			setGroups(target.getGroups());
			Map<Object, Object> parameters = new HashMap<>(target.getParameters());
			parameters.put(FtpRequestProcessor.PARAMETER_ROOT, root);
			setParameters(parameters);
		}
	}
}
