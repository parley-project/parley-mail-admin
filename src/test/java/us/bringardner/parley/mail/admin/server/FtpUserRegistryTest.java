package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.iam.AuthenticationResult.Status;
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
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.credential.ProofCredential;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.net.server.DomainListUnavailableException;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.net.server.IDomainList;

/**
 * The suite's FTP user registry (SuiteUserRegistry and FtpUserRegistry, before 2.0.0 SuiteAcl and
 * FtpAcl): who may log in to the suite's FTP servers, and each user's root.
 */
public class FtpUserRegistryTest {

	private static final String PREFIX = "parley.mail.ftp";
	private static final Requester REQUESTER = Requester.withoutAddress("ftp");
	private static final ParleyPermission WRITE = new BasicParleyPermission(StandardPermissions.WRITE, null);

	@TempDir
	File dir;

	/** The suite's local domains, or a list that can't be read */
	private static final class Domains implements IDomainList {
		final Set<String> names;
		volatile boolean down;

		Domains(String... names) {
			this.names = Set.of(names);
		}

		@Override
		public void initialize(Function<String, String> setting) {
		}

		@Override
		public boolean contains(String domain) throws DomainListUnavailableException {
			if (down) {
				throw new DomainListUnavailableException("the queue's folder can't be read");
			}
			return names.contains(domain);
		}

		@Override
		public Set<String> getDomains() {
			return names;
		}

		@Override
		public void reload() {
		}

		@Override
		public boolean reloadIfChanged() {
			return false;
		}
	}

	/** A registry whose passwords are right but whose permission checks can't be answered */
	private static final class PermissionsDown implements UserRegistry {
		private final UserRegistry users;

		PermissionsDown(UserRegistry users) {
			this.users = users;
		}

		@Override
		public boolean supports(Class<? extends Credential> kind, StoredCredentialType stored) {
			return users.supports(kind, stored);
		}

		@Override
		public AuthenticationResult authenticate(Credential credential) {
			return users.authenticate(credential);
		}

		@Override
		public StoredCredential findStoredCredential(String user, StoredCredentialType type) {
			return null;
		}

		@Override
		public ParleyPrincipal findPrincipal(String name) throws us.bringardner.parley.iam.UserRegistryUnavailableException {
			return users.findPrincipal(name);
		}

		@Override
		public ParleyPrincipal findPrincipal(EntityId id) throws us.bringardner.parley.iam.UserRegistryUnavailableException {
			return users.findPrincipal(id);
		}

		@Override
		public AuthorizationResult checkPermission(Requester requester, ParleyPermission needed, ProtectedResource resource) {
			return AuthorizationResult.unavailable("the database is down");
		}
	}

	private FileUserRegistry users() throws IOException {
		File f = new File(dir, "users.txt");
		Files.write(f.toPath(), ("tony@a.test , secret1 , read|write|ADMIN\n"
				+ "jose@a.test , secret2 , READ|WRITE\n"
				+ "boss@b.test , secret3 , admin\n"
				+ "nodomain , secret4 , ADMIN\n"
				+ "eve@c.test , secret5 , ADMIN\n"
				+ "../x@a.test , secret6 , ADMIN\n").getBytes(StandardCharsets.UTF_8));
		FileUserRegistry r = new FileUserRegistry();
		r.initialize(f);
		return r;
	}

	private static AuthenticationResult login(UserRegistry r, String user, String password) {
		return r.authenticate(new PasswordCredential(user, password.toCharArray(), REQUESTER));
	}

	private static Object root(AuthenticationResult result) {
		return result.getPrincipal().getAttribute(FtpRequestProcessor.PARAMETER_ROOT);
	}

	private static Properties settings(String... pairs) {
		Properties p = new Properties();
		for (int i = 0; i < pairs.length; i += 2) {
			p.setProperty(pairs[i], pairs[i + 1]);
		}
		return p;
	}

	@Test
	public void everyUserOfALocalDomainWithAFolderOfTheirOwn() throws Exception {
		FtpUserRegistry r = new FtpUserRegistry(users(), new Domains("a.test", "b.test"), settings(), PREFIX);
		assertTrue(r.supports(PasswordCredential.class, null));
		assertFalse(r.supports(ProofCredential.class, StoredCredentialType.SCRAM_SHA_256), "FTP sends passwords");
		assertFalse(r.usesServerDisk());

		AuthenticationResult tony = login(r, "tony@a.test", "secret1");
		assertEquals(Status.AUTHENTICATED, tony.getStatus());
		assertEquals("tony@a.test", tony.getPrincipal().getName());
		assertEquals("/a.test/tony", root(tony));
		assertEquals("/a.test/jose", root(login(r, "jose@a.test", "secret2")));
		assertEquals("/b.test/boss", root(login(r, "boss@b.test", "secret3")));

		assertEquals(Status.REFUSED, login(r, "tony@a.test", "wrong").getStatus());
		assertEquals(Status.REFUSED, login(r, "nobody@a.test", "secret1").getStatus());
		assertEquals(Status.REFUSED, login(r, "nodomain", "secret4").getStatus(), "the mail users are name@domain");
		assertEquals(Status.REFUSED, login(r, "eve@c.test", "secret5").getStatus(), "not a local domain");
		assertEquals(Status.REFUSED, login(r, "../x@a.test", "secret6").getStatus(), "a name that would be a path");

		ParleyPrincipal found = r.findPrincipal("jose@a.test");
		assertEquals("/a.test/jose", found.getAttribute(FtpRequestProcessor.PARAMETER_ROOT));
		assertEquals(found, r.findPrincipal(found.getId()));
		assertNull(r.findPrincipal("eve@c.test"));
		assertNull(r.findPrincipal("nodomain"));
	}

	@Test
	public void theSharedRegistryAnswersThePermissionChecks() throws Exception {
		FtpUserRegistry r = new FtpUserRegistry(users(), new Domains("a.test", "b.test"), settings(), PREFIX);
		AuthenticationResult jose = login(r, "jose@a.test", "secret2");
		Requester in = REQUESTER.loggedIn(jose);
		assertTrue(r.checkPermission(in, WRITE, null).isAllowed(), "the rooted principal holds what the user holds");
		assertFalse(r.checkPermission(in, new BasicParleyPermission(StandardPermissions.ADMIN, null), null).isAllowed());
		assertTrue(r.checkPermission(REQUESTER.loggedIn(login(r, "boss@b.test", "secret3")), WRITE, null).isAllowed(), "admin covers write");
		assertFalse(r.checkPermission(REQUESTER, WRITE, null).isAllowed(), "nobody logged in");

		FtpUserRegistry none = new FtpUserRegistry(null, new Domains("a.test"), settings(), PREFIX);
		assertFalse(none.supports(PasswordCredential.class, null));
		assertEquals(Status.REFUSED, login(none, "tony@a.test", "secret1").getStatus(), "no users file: no one logs in");
	}

	@Test
	public void onlyAdministratorsWithTheirRootsOnTheServersDisk() throws Exception {
		// mx1: only users with admin log in; the primary domain's get /data/home, the others /data/home/<domain>
		Properties c = settings(PREFIX + "." + FtpUserRegistry.LOGIN_PERMISSION, "admin",
				PREFIX + "." + FtpUserRegistry.PRIMARY_ROOT, "/data/home",
				PREFIX + "." + FtpUserRegistry.DOMAIN_ROOT, "/data/home/{domain}",
				FtpUserRegistry.PRIMARY_DOMAIN, "A.test");
		FtpUserRegistry r = new FtpUserRegistry(users(), new Domains("a.test", "b.test"), c, PREFIX);
		assertTrue(r.usesServerDisk());
		assertEquals("/data/home", r.serverDiskBase());
		assertEquals("a.test", r.primaryDomain());

		AuthenticationResult tony = login(r, "tony@a.test", "secret1");
		assertEquals(Status.AUTHENTICATED, tony.getStatus(), "ADMIN in the file is the admin permission (names ignore case)");
		assertEquals("/", root(tony), "the primary domain's administrators: /data/home");
		AuthenticationResult boss = login(r, "boss@b.test", "secret3");
		assertEquals(Status.AUTHENTICATED, boss.getStatus());
		assertEquals("/b.test", root(boss), "another domain's administrators: /data/home/b.test");
		assertEquals(Status.REFUSED, login(r, "jose@a.test", "secret2").getStatus(), "not an administrator");
	}

	@Test
	public void aRegistryThatCantAnswerIsNeverARefusal() throws Exception {
		Domains domains = new Domains("a.test");
		FtpUserRegistry r = new FtpUserRegistry(users(), domains, settings(), PREFIX);
		domains.down = true;
		assertEquals(Status.UNAVAILABLE, login(r, "tony@a.test", "secret1").getStatus(), "the domains can't be read");
		assertThrows(us.bringardner.parley.iam.UserRegistryUnavailableException.class, () -> r.findPrincipal("tony@a.test"));
		domains.down = false;
		assertEquals(Status.AUTHENTICATED, login(r, "tony@a.test", "secret1").getStatus());

		FtpUserRegistry admins = new FtpUserRegistry(new PermissionsDown(users()), new Domains("a.test"),
				settings(PREFIX + "." + FtpUserRegistry.LOGIN_PERMISSION, "ADMIN"), PREFIX);
		assertEquals(Status.UNAVAILABLE, login(admins, "tony@a.test", "secret1").getStatus(), "the login permission can't be checked");
		assertEquals(Status.REFUSED, login(admins, "tony@a.test", "wrong").getStatus(), "a wrong password is still wrong");
		FtpUserRegistry everyone = new FtpUserRegistry(new PermissionsDown(users()), new Domains("a.test"), settings(), PREFIX);
		assertEquals(Status.AUTHENTICATED, login(everyone, "tony@a.test", "secret1").getStatus(), "no login permission: nothing to check");
		assertEquals(AuthorizationResult.Status.UNAVAILABLE,
				everyone.checkPermission(REQUESTER.loggedIn(login(everyone, "tony@a.test", "secret1")), WRITE, null).getStatus());
	}

	@Test
	public void theRootsGoTogether() throws Exception {
		assertThrows(IOException.class, () -> new FtpUserRegistry(null, new Domains(), settings(PREFIX + "." + FtpUserRegistry.PRIMARY_ROOT, "/data/home"), PREFIX));
		assertThrows(IOException.class, () -> new FtpUserRegistry(null, new Domains(),
				settings(PREFIX + "." + FtpUserRegistry.PRIMARY_ROOT, "data", PREFIX + "." + FtpUserRegistry.DOMAIN_ROOT, "/data/{domain}"), PREFIX));
		assertNotNull(new FtpUserRegistry(null, new Domains(), settings(), PREFIX));
	}
}
