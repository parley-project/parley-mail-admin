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
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.mail.admin.users.UserStore;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IPrincipal;

/** The files the pages edit: settings, users and aliases, and the log and history files they read. */
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
	public void settingsAreCheckedAndKeptInTheServersFiles() throws Exception {
		write(new File(dir, "parley-mail.properties"), "# shared\nparley.mail.root=/mail\nparley.mail.smtp.port=2525\n");
		write(new File(dir, "parley-pop3.properties"), "parley.mail.root=/pop3-only\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		assertNull(System.getProperty("parley.mail.smtp.port"), "the servers read the files: no system properties");
		assertEquals("2525", s.get("parley.mail.smtp.port"));
		assertEquals("parley-mail.properties", s.findFile("parley.mail.smtp.port").getFile().getName(), "kept where it is");
		assertEquals("/pop3-only", s.readBy(ServiceId.Protocol.POP3).get("parley.mail.root"), "a protocol's file wins for its servers");
		assertEquals("/mail", s.readBy(ServiceId.Protocol.IMAP).get("parley.mail.root"));

		assertThrows(IllegalArgumentException.class, () -> s.set("parley.mail.smtp.port", "70000"));
		assertThrows(IllegalArgumentException.class, () -> s.set("parley.mail.smtp.requireTls", "yes"));
		assertThrows(IllegalArgumentException.class, () -> s.set("bad name", "x"));
		assertThrows(IllegalArgumentException.class, () -> s.set("user.home", "/x"), "only Parley settings");

		// a new setting goes to its protocol's file; others to the shared one
		Set<String> changed = s.update(Map.of("parley.mail.submission.maxConnections", "10", "parley.mail.imaps.port", "993", "parley.mail.lockDomain", "m"));
		assertEquals(3, changed.size());
		assertTrue(read(new File(dir, "parley-smtp.properties")).contains("parley.mail.submission.maxConnections=10"));
		assertTrue(read(new File(dir, "parley-imap.properties")).contains("parley.mail.imaps.port=993"));
		assertTrue(read(new File(dir, "parley-mail.properties")).endsWith("parley.mail.lockDomain=m\n"));
		assertEquals(Set.of("parley.mail.smtp.port"), s.set("parley.mail.smtp.port", "2526"));
		assertTrue(read(new File(dir, "parley-mail.properties")).startsWith("# shared\nparley.mail.root=/mail\nparley.mail.smtp.port=2526\n"));
		assertEquals(Set.of(), s.set("parley.mail.smtp.port", "2526"), "no change");

		// removed from every file
		s.set("parley.mail.root", null);
		assertNull(s.get("parley.mail.root"));
		assertFalse(read(new File(dir, "parley-pop3.properties")).contains("parley.mail.root"));

		// changed outside: read again, and the listeners hear of it
		java.util.List<Set<String>> heard = new java.util.ArrayList<>();
		s.addListener(heard::add);
		assertFalse(s.reloadIfChanged());
		write(new File(dir, "parley-pop3.properties"), "parley.mail.pop3.port=1110\n# a longer file\n");
		assertTrue(s.reloadIfChanged());
		assertEquals("1110", s.get("parley.mail.pop3.port"));
		assertEquals(List.of(Set.of("parley.mail.pop3.port")), heard);
	}

	@Test
	public void rewrittenFilesKeepTheirPermissions() throws Exception {
		File f = new File(dir, "users.txt");
		write(f, "a , b , READ\n");
		java.nio.file.attribute.PosixFileAttributeView v = Files.getFileAttributeView(f.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
		org.junit.jupiter.api.Assumptions.assumeTrue(v != null, "POSIX permissions");
		Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
		Files.setPosixFilePermissions(f.toPath(), perms);
		java.nio.file.attribute.GroupPrincipal group = v.readAttributes().group();
		FileRewrite.write(f, "c , d , READ\n".getBytes(StandardCharsets.UTF_8));
		assertEquals("c , d , READ\n", read(f));
		assertEquals(perms, Files.getPosixFilePermissions(f.toPath()));
		assertEquals(group, v.readAttributes().group());
		assertFalse(new File(dir, ".users.txt.tmp").exists());

		// the stores rewrite through it too
		UserStore u = new UserStore(f);
		u.load();
		u.add("someone", "password1".toCharArray(), null, null);
		assertEquals(perms, Files.getPosixFilePermissions(f.toPath()));
		PropertiesFile p = new PropertiesFile(new File(dir, "p.properties"));
		p.set("a", "b");
		p.save();
		Files.setPosixFilePermissions(p.getFile().toPath(), perms);
		p.set("a", "c");
		p.save();
		assertEquals(perms, Files.getPosixFilePermissions(p.getFile().toPath()));
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
		FileBasedAcl acl = new FileBasedAcl();
		acl.initialize(f);
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

	@Test
	public void logFilesAreReadFromTheEnd() throws Exception {
		File log = new File(dir, "servers.log");
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			b.append("line ").append(i).append('\n');
		}
		write(log, b.toString());
		write(new File(dir, "servers.log.1"), "older\n");
		write(new File(dir, "secret.txt"), "not a log\n");
		us.bringardner.parley.mail.admin.log.LogFiles files = new us.bringardner.parley.mail.admin.log.LogFiles(() -> List.of(log, new File(dir, "missing.log")));
		assertEquals(List.of(log.getAbsoluteFile(), new File(dir, "servers.log.1").getAbsoluteFile()), files.list(), "the file and its rotated copy");
		assertNotNull(files.find(log.getPath()));
		assertNull(files.find(new File(dir, "secret.txt").getPath()), "only the log files");
		assertNull(files.find(dir.getPath() + "/../" + dir.getName() + "/secret.txt"));
		String tail = us.bringardner.parley.mail.admin.log.LogFiles.tail(log, 30);
		assertTrue(tail.startsWith("line 99"), "from the start of a line: " + tail);
		assertTrue(tail.endsWith("line 999\n"));
	}

	@Test
	public void historyIsReadFromTheServersFiles() throws Exception {
		File h = new File(dir, ".smtp-history");
		h.mkdirs();
		java.time.LocalDate today = java.time.LocalDate.now();
		long now = System.currentTimeMillis();
		write(new File(h, "smtp-" + today + ".jsonl"), "{\"time\":" + (now - 2) + ",\"type\":\"QUEUED\",\"id\":\"m1\",\"from\":\"a@x\",\"recipients\":[\"b@y\"]}\n"
				+ "{\"time\":" + (now - 1) + ",\"type\":\"DELIVERED\",\"id\":\"m1\",\"from\":\"a@x\",\"recipient\":\"b@y\",\"result\":\"ok\"}\n"
				+ "{\"time\":" + now + ",\"type\"\n");
		write(new File(h, "smtp-" + today.minusDays(1) + ".jsonl"), "{\"time\":1,\"type\":\"QUEUED\",\"id\":\"m0\"}\n");
		us.bringardner.parley.mail.admin.history.HistoryStore store = new us.bringardner.parley.mail.admin.history.HistoryStore(() -> h,
				new com.fasterxml.jackson.databind.ObjectMapper());
		List<Map<String, Object>> rows = store.query(today, today, null, null, 100);
		assertEquals(2, rows.size(), "a line being written is skipped");
		assertEquals("DELIVERED", rows.get(0).get("type"), "newest first");
		assertEquals(3, store.query(today.minusDays(1), today, null, null, 100).size());
		assertEquals(1, store.query(today, today, "B@Y", Set.of("DELIVERED"), 100).size());
		assertEquals(0, new us.bringardner.parley.mail.admin.history.HistoryStore(() -> null, new com.fasterxml.jackson.databind.ObjectMapper())
				.query(today, today, null, null, 10).size(), "no history yet");
	}
}
