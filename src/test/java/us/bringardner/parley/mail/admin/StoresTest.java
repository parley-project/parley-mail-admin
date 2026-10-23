package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.mail.admin.settings.PropertiesFile;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.SuiteAcl;
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.mail.admin.users.UserStore;
import us.bringardner.parley.net.server.IPrincipal;

/** The files the pages edit: settings, users and aliases. */
public class StoresTest {

	@TempDir
	File dir;

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	private static void write(File f, String text) throws Exception {
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void propertiesFileKeepsCommentsAndOrder() throws Exception {
		File f = new File(dir, "a.properties");
		write(f, "# top\n\na=1\n# about b\nb = two \\\n  lines\nc:3\n");
		PropertiesFile p = new PropertiesFile(f);
		p.load();
		assertEquals("1", p.get("a"));
		assertEquals("two lines", p.get("b"));
		assertEquals("3", p.get("c"));
		p.set("b", "changed");
		p.set("a", null);
		p.set("d", "x=y #not a comment");
		p.set("e", " leading space");
		p.save();
		assertEquals("# top\n\n# about b\nb=changed\nc:3\nd=x=y #not a comment\ne=\\ leading space\n", read(f));
		PropertiesFile again = new PropertiesFile(f);
		again.load();
		assertEquals("x=y #not a comment", again.get("d"));
		assertEquals(" leading space", again.get("e"));
		assertEquals(Arrays.asList("b", "c", "d", "e"), List.copyOf(again.getValues().keySet()));
	}

	@Test
	public void settingsAreCheckedAndApplied() throws Exception {
		File f = new File(dir, "s.properties");
		write(f, "parley.mail.smtp.port=2525\n");
		SettingsService s = new SettingsService(new PropertiesFile(f), new SettingsCatalog());
		try {
			s.load();
			assertEquals("2525", System.getProperty("parley.mail.smtp.port"));
			assertThrows(IllegalArgumentException.class, () -> s.set("parley.mail.smtp.port", "70000"));
			assertThrows(IllegalArgumentException.class, () -> s.set("parley.mail.smtp.requireTls", "yes"));
			assertThrows(IllegalArgumentException.class, () -> s.set("bad name", "x"));
			assertThrows(IllegalArgumentException.class, () -> s.set("user.home", "/x"), "only Parley settings");
			System.setProperty("parley.test.fromCommandLine", "dash-D");
			s.set("parley.test.fromCommandLine", "file");
			assertEquals("file", System.getProperty("parley.test.fromCommandLine"));
			s.set("parley.test.fromCommandLine", null);
			assertEquals("dash-D", System.getProperty("parley.test.fromCommandLine"), "the -D value comes back");
			System.clearProperty("parley.test.fromCommandLine");
			Set<String> changed = s.set("parley.mail.smtp.port", "2526");
			assertEquals(Set.of("parley.mail.smtp.port"), changed);
			assertEquals("2526", System.getProperty("parley.mail.smtp.port"));
			s.set("parley.mail.smtp.port", "");
			assertNull(System.getProperty("parley.mail.smtp.port"), "removed from the file and the system properties");
			assertFalse(read(f).contains("parley.mail.smtp.port"));
		} finally {
			s.clear();
		}
	}

	@Test
	public void whichServicesASettingAffects() {
		assertTrue(ServiceId.SMTP.isAffectedBy("parley.mail.smtp.domains"));
		assertTrue(ServiceId.SUBMISSION.isAffectedBy("parley.mail.smtp.domains"), "the submission servers share the SMTP queue");
		assertFalse(ServiceId.SMTP.isAffectedBy("parley.mail.submission.port"));
		assertTrue(ServiceId.IMAPS.isAffectedBy("parley.mail.imap.requireTls"));
		assertTrue(ServiceId.IMAPS.isAffectedBy("parley.mail.imaps.port"));
		assertFalse(ServiceId.IMAP.isAffectedBy("parley.mail.imaps.port"));
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.imap.port"));
		assertTrue(ServiceId.POP3.isAffectedBy("parley.mail.root"));
		assertTrue(ServiceId.POP3.isAffectedBy("parley.mail.tls.keyStore"));
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.users"), "users are reloaded without a restart");
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.admin.autoStart"));
	}

	@Test
	public void usersFileIsReadByTheServersList() throws Exception {
		File f = new File(dir, "users.txt");
		write(f, "# users\nold , secret , READ\n");
		UserStore u = new UserStore(f);
		u.setDomainCheck(d -> d.equals("a.test"));
		u.load();
		assertEquals(1, u.list().size());
		assertNotNull(u.authenticate("old", "secret".toCharArray()), "a plain text password works");

		u.add("Tony@A.test", "password1".toCharArray(), List.of("read", "WRITE", "ADMIN"), null);
		UserRecord tony = u.get("tony@a.test");
		assertEquals("tony@a.test", tony.getName(), "names are kept in lower case");
		assertEquals("a.test/tony", tony.getMaildrop(), "a domain's user lives in domain/user");
		assertEquals(Set.of("READ", "WRITE", "ADMIN"), tony.getPermissions());
		assertTrue(tony.isHashed());
		assertTrue(tony.hasScram());
		assertNotNull(u.authenticate("tony@a.test", "password1".toCharArray()));
		assertNull(u.authenticate("tony@a.test", "password2".toCharArray()));
		assertNull(u.authenticate("nobody", "password1".toCharArray()));

		assertThrows(IllegalArgumentException.class, () -> u.add("x@b.test", "password1".toCharArray(), null, null), "not a local domain");
		assertThrows(IllegalArgumentException.class, () -> u.add("bad,name", "password1".toCharArray(), null, null));
		assertThrows(IllegalArgumentException.class, () -> u.add("short", "pw".toCharArray(), null, null));
		assertThrows(IllegalArgumentException.class, () -> u.add("x", "password1".toCharArray(), null, "../etc"));
		assertThrows(IllegalArgumentException.class, () -> u.add("old", "password1".toCharArray(), null, null), "exists");
		assertThrows(IllegalArgumentException.class, () -> u.delete("tony@a.test"), "the last administrator");
		assertThrows(IllegalArgumentException.class, () -> u.update("tony@a.test", null, List.of("READ"), null), "the last administrator");

		String text = read(f);
		assertTrue(text.startsWith("# users\nold , secret , READ\ntony@a.test , {PBKDF2}"), text);
		assertTrue(text.contains(" , READ|WRITE|ADMIN , maildrop=a.test/tony\n"), text);

		// the servers' list (parley-net's FileBasedAcl) reads the same file
		SuiteAcl acl = new SuiteAcl(f);
		IPrincipal p = acl.getPrincipal("tony@a.test");
		assertNotNull(p);
		assertEquals("a.test/tony", p.getParameter("maildrop"));
		assertTrue(p.authenticate("password1".getBytes(StandardCharsets.UTF_8)));
		assertTrue(acl.supportsScram("SHA-256"), "every user can log in with SCRAM");

		u.update("tony@a.test", null, null, "somewhere/else");
		assertEquals("somewhere/else", u.get("tony@a.test").getMaildrop());
		u.update("tony@a.test", null, null, "");
		assertEquals("a.test/tony", u.get("tony@a.test").getMaildrop(), "empty: the default again");
		u.update("old", "newpassword".toCharArray(), List.of("READ", "WRITE"), "elsewhere/old");
		assertEquals("elsewhere/old", u.get("old").getMaildrop());
		assertTrue(u.get("old").isHashed());
		u.delete("old");
		assertNull(u.get("old"));
		assertFalse(read(f).contains("old ,"));
		assertTrue(read(f).startsWith("# users\n"), "comments are kept");
	}

	@Test
	public void aliases() throws Exception {
		File f = new File(dir, "aliases.txt");
		write(f, "# aliases\npostmaster: tony\nsales: a\nsales: b # two lines\n");
		AliasStore a = new AliasStore(f);
		a.load();
		Map<String, List<String>> list = a.list();
		assertEquals(List.of("a", "b"), list.get("sales"));
		a.put("Sales@A.test", List.of("tony@a.test", " jose@example.org "));
		a.put("sales", List.of("c"));
		assertEquals("# aliases\npostmaster: tony\nsales: c\nsales@a.test: tony@a.test, jose@example.org\n", read(f));
		assertTrue(a.delete("postmaster"));
		assertFalse(a.delete("postmaster"));
		assertThrows(IllegalArgumentException.class, () -> a.put("x", List.of()));
		assertThrows(IllegalArgumentException.class, () -> a.put("x y", List.of("a")));
	}
}
