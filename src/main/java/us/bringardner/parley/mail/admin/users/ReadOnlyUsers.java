package us.bringardner.parley.mail.admin.users;

import java.io.IOException;
import java.util.Set;

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

/**
 * What the application gives each of its servers: a read-only view of the application's own user
 * registry. A server can authenticate and authorize users, and read their attributes, but it is not
 * given a way to change a user (the view is a {@link UserRegistry}, not a PrincipalManager), and
 * closing it leaves the application's registry alone. The application changes its registry at any time and
 * every call asks it again, so a server sees the change at once, and a change of registry (another users
 * file) too.
 *
 * @since 2.0.0
 */
public final class ReadOnlyUsers implements UserRegistry {

	/** Gives the application's registry as it is now */
	@FunctionalInterface
	public interface Source {
		UserRegistry get() throws IOException;
	}

	private final Source source;

	public ReadOnlyUsers(Source source) {
		this.source = source;
	}

	private UserRegistry registry() throws UserRegistryUnavailableException {
		try {
			return source.get();
		} catch (IOException | RuntimeException e) {
			throw new UserRegistryUnavailableException("The users can't be read: " + e.getMessage(), e);
		}
	}

	@Override
	public boolean supports(Class<? extends Credential> kind, StoredCredentialType stored) {
		try {
			return registry().supports(kind, stored);
		} catch (UserRegistryUnavailableException e) {
			return false;
		}
	}

	@Override
	public AuthenticationResult authenticate(Credential credential) {
		try {
			return registry().authenticate(credential);
		} catch (UserRegistryUnavailableException e) {
			return AuthenticationResult.unavailable(e.getMessage());
		}
	}

	@Override
	public StoredCredential findStoredCredential(String user, StoredCredentialType type) throws UserRegistryUnavailableException {
		return registry().findStoredCredential(user, type);
	}

	@Override
	public void recordOutcome(String user, AuthenticationResult result) {
		try {
			registry().recordOutcome(user, result);
		} catch (UserRegistryUnavailableException e) {
			// nothing to record it in
		}
	}

	@Override
	public ParleyPrincipal findPrincipal(String name) throws UserRegistryUnavailableException {
		return registry().findPrincipal(name);
	}

	@Override
	public ParleyPrincipal findUser(String name) throws UserRegistryUnavailableException {
		return registry().findUser(name);
	}

	@Override
	public ParleyPrincipal findPrincipal(EntityId id) throws UserRegistryUnavailableException {
		return registry().findPrincipal(id);
	}

	@Override
	public Set<ParleyPrincipal> findByAttribute(String name, Object value) throws UserRegistryUnavailableException {
		return registry().findByAttribute(name, value);
	}

	@Override
	public Set<ParleyPrincipal> findGroups(ParleyPrincipal principal) throws UserRegistryUnavailableException {
		return registry().findGroups(principal);
	}

	@Override
	public Set<ParleyPrincipal> findMembers(ParleyPrincipal group) throws UserRegistryUnavailableException {
		return registry().findMembers(group);
	}

	@Override
	public AuthorizationResult checkPermission(Requester requester, ParleyPermission needed, ProtectedResource resource) {
		try {
			return registry().checkPermission(requester, needed, resource);
		} catch (UserRegistryUnavailableException e) {
			return AuthorizationResult.unavailable(e.getMessage());
		}
	}

	/** The application's registry is the application's: a server closing its view doesn't close it */
	@Override
	public void close() {
		// nothing of its own
	}
}
