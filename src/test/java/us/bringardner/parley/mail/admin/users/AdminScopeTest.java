package us.bringardner.parley.mail.admin.users;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Who manages what: the primary domain's administrators everything, the others their own domain. */
public class AdminScopeTest {

	private static UserRecord user(String name, String... perms) {
		return new UserRecord(name, new HashSet<>(Arrays.asList(perms)), null, List.of(), true, false, true);
	}

	@Test
	public void whoIsAnAdministrator() {
		assertNull(AdminScope.of(null, "a.test"));
		assertNull(AdminScope.of(user("tony@a.test", "READ", "WRITE"), "a.test"), "no ADMIN permission");
		assertNull(AdminScope.of(user("root", "ADMIN"), "a.test"), "no domain: administers nothing");
		assertTrue(AdminScope.of(user("Admin@A.Test", "ADMIN"), "a.test").isPrimary(), "any case");
		assertFalse(AdminScope.of(user("boss@b.test", "ADMIN"), "a.test").isPrimary());
		assertFalse(AdminScope.of(user("admin@a.test", "ADMIN"), null).isPrimary(), "no primary domain known: nobody is primary");
	}

	@Test
	public void primaryManagesEverything() {
		AdminScope p = AdminScope.of(user("admin@a.test", "ADMIN"), "a.test");
		assertTrue(p.allowsDomain("b.test"));
		assertTrue(p.allowsDomain(null));
		assertTrue(p.allowsAddress("root"));
		assertTrue(p.allowsMaildrop("b.test/boss"));
		assertTrue(p.allowsAny(null));
	}

	@Test
	public void domainAdministratorManagesItsDomain() {
		AdminScope b = AdminScope.of(user("boss@b.test", "ADMIN"), "a.test");
		assertTrue(b.allowsDomain("B.TEST"));
		assertFalse(b.allowsDomain("a.test"));
		assertFalse(b.allowsDomain(null), "users of every domain are the primary's");
		assertTrue(b.allowsAddress("clerk@b.test"));
		assertTrue(b.allowsAddress("<clerk@B.test>"));
		assertFalse(b.allowsAddress("clerk@a.test"));
		assertFalse(b.allowsAddress("clerk@sub.b.test"), "a subdomain is another domain");
		assertFalse(b.allowsAddress("clerk"));
		assertFalse(b.allowsAddress(""), "a bounce's empty sender");
		assertTrue(b.allowsAny(Arrays.asList("", "x@a.test", "clerk@b.test")));
		assertFalse(b.allowsAny(Arrays.asList("x@a.test", "y@c.test")));

		assertTrue(b.allowsMaildrop(null), "the default");
		assertTrue(b.allowsMaildrop(" "));
		assertTrue(b.allowsMaildrop("b.test/clerk"));
		assertTrue(b.allowsMaildrop("/b.test/clerk"));
		assertFalse(b.allowsMaildrop("a.test/admin"));
		assertFalse(b.allowsMaildrop("b.test"), "the domain's folder itself holds every mailbox of it");
		assertFalse(b.allowsMaildrop("b.test/../a.test/admin"));
		assertFalse(b.allowsMaildrop("b.testx/clerk"));
	}
}
