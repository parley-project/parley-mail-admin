package us.bringardner.parley.mail.admin.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.registry.ForwardingUserRegistry;
import us.bringardner.parley.net.server.FileUserRegistry;

/** parley-mail-admin's rules for users, kept in the users file through its parley-iam registry. */
public class UserServiceTest {

	@TempDir
	File dir;

	private File file(String text) throws Exception {
		File f = new File(dir, "users.txt");
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	private static AuthenticationResult.Status login(UserService users, String name, String password) throws Exception {
		return users.registry().authenticate(new PasswordCredential(name, password.toCharArray(), Requester.withoutAddress("test"))).getStatus();
	}

	@Test
	public void groupLinesSurviveAnEditFromTheUsersPage() throws Exception {
		File f = file("# users\n"
				+ "tony@a.test , secret , READ\n"
				+ "group: support@a.test\n");
		UserService users = new UserService(f, null);
		users.update("tony@a.test", null, Arrays.asList("READ", "WRITE"), null);

		String text = read(f);
		assertTrue(text.contains("group: support@a.test\n"), text);
		assertTrue(text.startsWith("# users\n"), text);

		FileUserRegistry registry = new FileUserRegistry();
		registry.initialize(f);
		ParleyPrincipal support = registry.findPrincipal("support@a.test");
		assertNotNull(support, "the group is still there");
		assertTrue(support.isGroup());
		assertEquals(2, users.get("tony@a.test").getPermissions().size());
	}

	@Test
	public void addingAUserIsOneChangeWithItsGroups() throws Exception {
		File f = file("# users\nboss@a.test , bosspassword , READ|WRITE|ADMIN\ngroup: sales@a.test\n");
		UserService users = new UserService(f, null);
		int[] heard = { 0 };
		users.addListener(() -> heard[0]++);

		users.add("Ann@A.test", "annpassword".toCharArray(), List.of("READ"), null, List.of("sales@a.test", "$support@a.test"), true);
		assertEquals(1, heard[0], "one change");
		UserRecord ann = users.get("ann@a.test");
		assertEquals("ann@a.test", ann.getName(), "names are kept in lower case");
		assertEquals("a.test/ann", ann.getMaildrop());
		assertEquals(List.of("sales@a.test", "support@a.test"), ann.getGroups(), "a group that didn't exist is made");
		assertEquals(Set.of("READ"), ann.getPermissions());
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, login(users, "ann@a.test", "annpassword"));

		// a step that can't work: nothing is made, not even the user
		String before = read(f);
		assertThrows(IllegalArgumentException.class,
				() -> users.add("carl@a.test", "carlpassword".toCharArray(), List.of("READ"), null, List.of("ann@a.test"), true), "ann is a user");
		assertNull(users.get("carl@a.test"));
		assertEquals(before, read(f), "the file wasn't written");
	}

	@Test
	public void namesTypedInAnyCaseAreFound() throws Exception {
		File f = file("tony@a.test , tonypassword , READ\nOld , oldpassword , READ\n");
		UserService users = new UserService(f, null);
		assertEquals("tony@a.test", users.get("  Tony@A.Test ").getName(), "lower-cased before the lookup");
		assertEquals("Old", users.get("Old").getName(), "a name kept in another case is found as written");
		assertNull(users.get("nobody"));
		users.update("TONY@a.test", null, List.of("READ", "WRITE"), null);
		assertTrue(users.get("tony@a.test").hasPermission("WRITE"));
	}

	@Test
	public void turningAUserOffAndOn() throws Exception {
		File f = file("boss@a.test , bosspassword , READ|WRITE|ADMIN\n");
		UserService users = new UserService(f, null);
		users.add("ann@a.test", "annpassword".toCharArray(), List.of("READ", "WRITE"), null, List.of("sales@a.test"), true);

		users.update("ann@a.test", null, null, null, null, false);
		UserRecord ann = users.get("ann@a.test");
		assertNotNull(ann, "a turned-off user is still listed");
		assertFalse(ann.isEnabled());
		assertEquals(Set.of("READ", "WRITE"), ann.getPermissions(), "its permissions are kept");
		assertEquals(List.of("sales@a.test"), ann.getGroups(), "its groups are kept and shown");
		assertTrue(read(f).contains("enabled=false"));
		assertEquals(AuthenticationResult.Status.REFUSED, login(users, "ann@a.test", "annpassword"), "it can't log in");

		// its groups can be changed without turning it on, and the servers still see it as gone
		users.update("ann@a.test", null, null, null, List.of("other@a.test"), null);
		ann = users.get("ann@a.test");
		assertFalse(ann.isEnabled(), "still off");
		assertEquals(List.of("other@a.test"), ann.getGroups());
		UserRegistry servers = users.registry();
		assertNull(servers.findPrincipal("ann@a.test"), "the servers don't find it");
		ParleyPrincipal other = servers.findPrincipal("other@a.test");
		assertNotNull(other, "the group was made");
		assertEquals(0, servers.findMembers(other).size(), "a turned-off user isn't listed as a member");

		users.update("ann@a.test", null, null, null, null, true);
		ann = users.get("ann@a.test");
		assertTrue(ann.isEnabled());
		assertEquals(List.of("other@a.test"), ann.getGroups(), "the groups changed while it was off count now");
		assertEquals(1, servers.findMembers(servers.findPrincipal("other@a.test")).size());
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, login(users, "ann@a.test", "annpassword"));

		users.add("carl@a.test", "carlpassword".toCharArray(), List.of("READ"), null, null, false);
		assertFalse(users.get("carl@a.test").isEnabled(), "added turned off");
	}

	@Test
	public void theLastActiveAdministrator() throws Exception {
		File f = file("boss@a.test , bosspassword , READ|WRITE|ADMIN\n");
		UserService users = new UserService(f, null);
		assertTrue(users.hasAdmin());
		assertThrows(IllegalArgumentException.class, () -> users.update("boss@a.test", null, null, null, null, false), "can't be turned off");
		assertThrows(IllegalArgumentException.class, () -> users.update("boss@a.test", null, List.of("READ"), null), "can't lose ADMIN");
		assertThrows(IllegalArgumentException.class, () -> users.delete("boss@a.test"), "can't be deleted");

		// a second administrator who is turned off doesn't count
		users.add("deputy@a.test", "deputypassword".toCharArray(), List.of("READ", "ADMIN"), null, null, false);
		assertThrows(IllegalArgumentException.class, () -> users.update("boss@a.test", null, null, null, null, false));
		// turned on, it does
		users.update("deputy@a.test", null, null, null, null, true);
		users.update("boss@a.test", null, null, null, null, false);
		assertFalse(users.get("boss@a.test").isEnabled());
		assertThrows(IllegalArgumentException.class, () -> users.update("deputy@a.test", null, null, null, null, false),
				"now the deputy is the last one turned on");
	}

	@Test
	public void anEditThatChangesNothingWritesNothing() throws Exception {
		File f = file("tony@a.test , tonypassword , READ , maildrop=a.test/tony\n");
		UserService users = new UserService(f, null);
		int[] heard = { 0 };
		users.addListener(() -> heard[0]++);
		String before = read(f);
		users.update("tony@a.test", null, List.of("read"), "a.test/tony", List.of(), true);
		assertEquals(before, read(f));
		assertEquals(0, heard[0]);
	}

	@Test
	public void theServersRegistryIsUsedWhenThereIsOne() throws Exception {
		File f = file("tony@a.test , tonypassword , READ\n");
		FileUserRegistry servers = new FileUserRegistry();
		servers.initialize(f.getCanonicalFile());
		UserService users = new UserService(f, file -> file.equals(servers.getSourceFile()) ? servers : null);
		assertSame(servers, users.registry());
		users.update("tony@a.test", null, List.of("READ", "WRITE"), null);
		assertTrue(servers.findGrants(servers.findPrincipal("tony@a.test")).size() == 2, "the servers see it at once");
		assertNull(users.ownRegistry(), "no registry of its own was made");
	}

	@Test
	public void aRegistryThatCantChangeUsers() throws Exception {
		File f = file("tony@a.test , tonypassword , READ\n");
		FileUserRegistry file = new FileUserRegistry();
		file.initialize(f);
		// only the reading part of a registry
		UserRegistry readOnly = new ForwardingUserRegistry(file);
		UserService users = new UserService(f, any -> readOnly);
		assertFalse(users.isEditable());
		assertEquals(List.of(), users.list(), "it can't list its users");
		assertThrows(IllegalStateException.class, () -> users.add("ann@a.test", "annpassword".toCharArray(), null, null));
		assertThrows(IllegalStateException.class, () -> users.delete("tony@a.test"));
	}

	@Test
	public void theApplicationsChecks() throws Exception {
		File f = file("");
		UserService users = new UserService(f, null);
		users.setDomainCheck(d -> d.equals("a.test"));
		assertThrows(IllegalArgumentException.class, () -> users.add("x@b.test", "password1".toCharArray(), null, null), "not a local domain");
		assertThrows(IllegalArgumentException.class, () -> users.add("short@a.test", "pw".toCharArray(), null, null));
		assertThrows(IllegalArgumentException.class, () -> users.add("x@a.test", "password1".toCharArray(), null, null, List.of("nodomain"), true));
		assertThrows(IllegalArgumentException.class, () -> users.add("x@a.test", "password1".toCharArray(), null, "../etc"));
		assertEquals(List.of(), users.list());
		assertFalse(users.hasAdmin());
	}
}
