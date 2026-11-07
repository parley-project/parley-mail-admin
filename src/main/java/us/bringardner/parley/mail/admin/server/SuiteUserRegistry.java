package us.bringardner.parley.mail.admin.server;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.Credential;
import us.bringardner.parley.iam.EntityId;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.ProtectedResource;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StoredCredential;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.principal.BasicParleyPrincipal;
import us.bringardner.parley.net.server.DomainListUnavailableException;
import us.bringardner.parley.net.server.IDomainList;

/**
 * The mail suite's users, for FTP: a parley-iam {@link UserRegistry} over the user registry the
 * mail servers share. A login is {@code name@domain} as in the users file, and the domain must be
 * one of the suite's local domains. Each user gets a root of their own, {@code /<domain>/<name>} in
 * the server's folder of the mail store, which FTP makes when they first log in (the principal's
 * {@value FtpRequestProcessor#PARAMETER_ROOT} attribute, read by parley-ftp). The shared registry
 * checks the passwords and answers the permission checks (read, write, admin) as it does for the
 * mail servers.
 * <p>
 * Before 2.0.0 this was {@code SuiteAcl}, a parley-net access control list.
 *
 * @since 2.0.0
 */
class SuiteUserRegistry implements UserRegistry {

	private final UserRegistry users;
	private final IDomainList domains;

	/**
	 * @param users the user registry the mail servers share; null: no one can log in
	 * @param domains the suite's local domains
	 */
	SuiteUserRegistry(UserRegistry users, IDomainList domains) {
		this.users = users;
		this.domains = domains;
	}

	/** Passwords, when the shared registry checks them. */
	@Override
	public boolean supports(Class<? extends Credential> kind, StoredCredentialType stored) {
		return users != null && kind == PasswordCredential.class && stored == null && users.supports(kind, stored);
	}

	/**
	 * The shared registry checks the password (for every name, so a refusal takes as long whatever
	 * the reason); then the user must be of a local domain, have a name that is one folder name, and
	 * be admitted ({@link #admits}). The principal is the shared registry's, with the user's root.
	 */
	@Override
	public AuthenticationResult authenticate(Credential credential) {
		if (!(credential instanceof PasswordCredential)) {
			return AuthenticationResult.unsupported("The suite's FTP servers check passwords only");
		}
		if (users == null) {
			return AuthenticationResult.refused("no users file");
		}
		AuthenticationResult result = users.authenticate(credential);
		if (result.getStatus() != AuthenticationResult.Status.AUTHENTICATED) {
			return result;
		}
		ParleyPrincipal p = result.getPrincipal();
		ParleyPrincipal rooted;
		try {
			rooted = rooted(p);
		} catch (UserRegistryUnavailableException e) {
			return AuthenticationResult.unavailable(e.getMessage());
		}
		if (rooted == null) {
			return AuthenticationResult.refused(p.getName() + " isn't a user of a local domain who can have a folder");
		}
		Requester requester = credential.getRequester();
		if (requester == null) {
			requester = Requester.withoutAddress("ftp");
		}
		AuthorizationResult admitted = admits(requester.loggedIn(result));
		switch (admitted.getStatus()) {
			case ALLOWED:
				return AuthenticationResult.authenticated(rooted, result.getMethod());
			case UNAVAILABLE:
				// never "denied": the registry couldn't answer
				return AuthenticationResult.unavailable(admitted.getReason());
			default:
				return AuthenticationResult.refused(p.getName() + " may not log in to FTP: " + admitted.getReason());
		}
	}

	/**
	 * May this user log in to FTP? Asked after the password was checked.
	 *
	 * @param loggedIn the requester with the user logged in (the shared registry's principal)
	 * @return ALLOWED to let them in, DENIED to refuse, UNAVAILABLE when the registry can't answer
	 *         now; here, every user of a local domain is ALLOWED
	 */
	AuthorizationResult admits(Requester loggedIn) {
		return AuthorizationResult.allowed("every user of a local domain");
	}

	/** No proofs: FTP sends passwords. */
	@Override
	public StoredCredential findStoredCredential(String user, StoredCredentialType type) {
		return null;
	}

	/**
	 * A user of a local domain whose name is one folder name, with the root FTP gives them. Whether
	 * they may log in ({@link #admits}) is asked when they log in.
	 */
	@Override
	public ParleyPrincipal findPrincipal(String name) throws UserRegistryUnavailableException {
		if (name == null || users == null) {
			return null;
		}
		ParleyPrincipal p = users.findPrincipal(name);
		return p == null ? null : rooted(p);
	}

	@Override
	public ParleyPrincipal findPrincipal(EntityId id) throws UserRegistryUnavailableException {
		if (id == null || users == null) {
			return null;
		}
		ParleyPrincipal p = users.findPrincipal(id);
		return p == null ? null : rooted(p);
	}

	/**
	 * The shared registry answers: the rooted principal has the shared principal's id, so it holds
	 * what the user holds there.
	 */
	@Override
	public AuthorizationResult checkPermission(Requester requester, ParleyPermission needed, ProtectedResource resource) {
		if (users == null) {
			return AuthorizationResult.denied("no users file");
		}
		return users.checkPermission(requester, needed, resource);
	}

	/** The shared registry belongs to the servers' context, which closes it. */
	@Override
	public void close() {
		// nothing of its own
	}

	/**
	 * @param p a principal of the shared registry
	 * @return p with its root attribute, or null if it isn't a user of a local domain whose name is
	 *         one folder name
	 * @throws UserRegistryUnavailableException if the domain list can't be read now
	 */
	private ParleyPrincipal rooted(ParleyPrincipal p) throws UserRegistryUnavailableException {
		if (p.isGroup()) {
			return null; // a group doesn't log in
		}
		String full = p.getName();
		int at = full.lastIndexOf('@');
		if (at <= 0 || at == full.length() - 1) {
			return null; // the mail users are name@domain
		}
		String domain = full.substring(at + 1).toLowerCase(Locale.ROOT);
		try {
			if (!domains.contains(domain)) {
				return null;
			}
		} catch (DomainListUnavailableException e) {
			throw new UserRegistryUnavailableException("The suite's domains can't be read: " + e.getMessage(), e);
		}
		String name = full.substring(0, at);
		if (!safe(name)) {
			return null; // a name that would be a path
		}
		Map<String, Object> attributes = new LinkedHashMap<>();
		for (String a : p.getAttributeNames()) {
			attributes.put(a, p.getAttribute(a));
		}
		attributes.put(FtpRequestProcessor.PARAMETER_ROOT, root(domain, name));
		return new BasicParleyPrincipal(p.getId(), full, false, attributes);
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
}
