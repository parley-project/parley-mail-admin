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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.credential.ProofCredential;
import us.bringardner.parley.mail.admin.settings.PropertiesFile;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.ServiceId;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.mail.admin.users.UserService;
import us.bringardner.parley.net.server.FileUserRegistry;

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

		// the user registry rewrites the users file keeping them too
		UserService u = new UserService(f, null);
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
		// each server reads only its own prefix: the relay's settings aren't the submission server's
		assertTrue(ServiceId.SMTP.isAffectedBy("parley.mail.smtp.requireTls"));
		assertFalse(ServiceId.SUBMISSION.isAffectedBy("parley.mail.smtp.requireTls"), "the relay's own");
		assertTrue(ServiceId.SUBMISSION.isAffectedBy("parley.mail.submission.requireTls"));
		assertFalse(ServiceId.SMTP.isAffectedBy("parley.mail.submission.port"));
		assertFalse(ServiceId.IMAPS.isAffectedBy("parley.mail.imap.requireTls"), "imap's own");
		assertTrue(ServiceId.IMAPS.isAffectedBy("parley.mail.imaps.port"));
		assertFalse(ServiceId.IMAP.isAffectedBy("parley.mail.imaps.port"));
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.imap.port"));
		assertTrue(ServiceId.FTP.isAffectedBy("parley.mail.ftp.root"));
		assertFalse(ServiceId.FTP.isAffectedBy("parley.mail.ftps.port"), "ftps' own");
		assertFalse(ServiceId.FTPS.isAffectedBy("parley.mail.ftp.root"), "ftp's own");
		assertTrue(ServiceId.FTP.isAffectedBy("store.sharing"));
		assertFalse(ServiceId.FTP.isAffectedBy("queue.domains"), "the domains are read again by FTP without a restart");
		// the queue's settings: every SMTP server; the store's: every server
		assertTrue(ServiceId.SUBMISSION.isAffectedBy("queue.domains"));
		assertFalse(ServiceId.POP3.isAffectedBy("queue.domains"));
		assertTrue(ServiceId.POP3.isAffectedBy("store.sharing"));
		assertTrue(ServiceId.POP3.isAffectedBy("parley.mail.root"));
		assertTrue(ServiceId.POP3.isAffectedBy("parley.mail.tls.keyStore"));
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.users"), "users are reloaded without a restart");
		assertFalse(ServiceId.POP3.isAffectedBy("parley.mail.admin.autoStart"));
	}

	@Test
	public void theStoresAndTheQueuesOwnSettings() throws Exception {
		File root = new File(dir, "mail");
		write(new File(dir, "parley-mail.properties"), "parley.mail.root=" + root.getAbsolutePath().replace('\\', '/') + "\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.setNewQueueDefaults(Map.of("aliases", "/etc/parley/aliases.txt"));
		s.load();
		File store = new File(root, ".parley-store.properties");
		File queue = new File(root, ".smtp-queue/queue.properties");
		assertEquals(store.getAbsolutePath().replace('\\', '/'), s.getStoreFile().getPath());
		assertEquals(queue.getAbsolutePath().replace('\\', '/'), s.getQueueFile().getPath());
		assertFalse(s.getStoreFile().exists(), "not made until something is set");
		assertNull(s.get("store.sharing"));

		// setting one makes its file; the host name is the queue's and every SMTP server's
		s.update(Map.of("store.sharing", "host", "queue.hostname", "mx.a.test", "queue.domains", "a.test,b.test"));
		assertEquals("sharing=host\n", read(store));
		java.util.Properties q = new java.util.Properties();
		q.load(new java.io.StringReader(read(queue)));
		assertEquals("mx.a.test", q.getProperty("hostname"));
		assertEquals("a.test,b.test", q.getProperty("domains"));
		assertEquals("/etc/parley/aliases.txt", q.getProperty("aliases"), "a new queue names the aliases file");
		String smtp = read(new File(dir, "parley-smtp.properties"));
		for (String k : SettingsCatalog.SMTP_HOSTNAMES) {
			assertTrue(smtp.contains(k + "=mx.a.test"), smtp);
		}
		assertFalse(read(new File(dir, "parley-mail.properties")).contains("hostname"));
		assertEquals("a.test,b.test", s.get("queue.domains"));
		assertEquals("host", s.getAll().get("store.sharing"));
		assertThrows(IllegalArgumentException.class, () -> s.set("store.sharing", "sometimes"));

		// edited outside: read again
		write(queue, read(queue).replace("domains=a.test,b.test", "domains=c.test"));
		assertTrue(s.reloadIfChanged());
		assertEquals("c.test", s.get("queue.domains"));

		// another queue folder: the files follow
		s.set(SettingsCatalog.SMTP_QUEUE, "queues/main");
		assertEquals(new File(root, "queues/main/queue.properties").getAbsolutePath().replace('\\', '/'), s.getQueueFile().getPath());
		assertNull(s.get("queue.domains"));
		s.set(SettingsCatalog.SMTP_QUEUE, "../outside");
		assertNull(s.getQueueFile(), "under the mail root only");
		assertTrue(s.getAssetError().contains("must be under the mail root"), s.getAssetError());
		assertThrows(IllegalArgumentException.class, () -> s.set("queue.domains", "a.test"));
	}

	@Test
	public void theStoresSettingsOnAnotherFileSystem() throws Exception {
		write(new File(dir, "parley-mail.properties"), "parley.mail.root=/mail\nparley.mail.fileSource=memory\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		assertNull(s.getAssetError());
		assertNull(s.getStoreFile().getFile(), "a FileSource, not a local file");
		s.set("store.sharing", "cluster");
		assertTrue(s.getStoreFile().exists());
		s.getStoreFile().load();
		assertEquals("cluster", s.getStoreFile().get("sharing"));
		assertEquals("/mail/.parley-store.properties", s.getStoreFile().getPath());
	}

	@Test
	public void aFileInAFolderThisProcessCantAddTo() throws Exception {
		File locked = new File(dir, "root");
		locked.mkdirs();
		File f = new File(locked, ".parley-store.properties");
		write(f, "sharing=off\n");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileAttributeView(f.toPath(), java.nio.file.attribute.PosixFileAttributeView.class) != null, "POSIX permissions");
		Files.setPosixFilePermissions(locked.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-x---"));
		try {
			org.junit.jupiter.api.Assumptions.assumeFalse(new File(locked, "probe").createNewFile() && new File(locked, "probe").delete(), "not as root");
			FileRewrite.write(f, "sharing=host\n".getBytes(StandardCharsets.UTF_8));
			assertEquals("sharing=host\n", read(f), "written in place");
		} finally {
			Files.setPosixFilePermissions(locked.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	public void usersFileIsReadByTheServersList() throws Exception {
		File f = new File(dir, "users.txt");
		write(f, "# users\nold , secret , READ\n");
		UserService u = new UserService(f, null);
		u.setDomainCheck(d -> d.equals("a.test"));
		assertEquals(1, u.list().size());
		assertFalse(u.get("old").isHashed(), "a plain text password");
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, u.registry().authenticate(
				new PasswordCredential("old", "secret".toCharArray(), Requester.withoutAddress("test"))).getStatus(), "a plain text password works");

		u.add("Tony@A.test", "password1".toCharArray(), List.of("read", "WRITE", "ADMIN"), null);
		UserRecord tony = u.get("tony@a.test");
		assertEquals("tony@a.test", tony.getName(), "names are kept in lower case");
		assertEquals("a.test/tony", tony.getMaildrop(), "a domain's user lives in domain/user");
		assertEquals(Set.of("READ", "WRITE", "ADMIN"), tony.getPermissions());
		assertTrue(tony.isHashed());
		assertTrue(tony.hasScram());
		assertTrue(tony.isEnabled());
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, u.registry().authenticate(
				new PasswordCredential("tony@a.test", "password1".toCharArray(), Requester.withoutAddress("test"))).getStatus());
		assertEquals(AuthenticationResult.Status.REFUSED, u.registry().authenticate(
				new PasswordCredential("tony@a.test", "password2".toCharArray(), Requester.withoutAddress("test"))).getStatus());

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

		// the servers' registry (parley-net's FileUserRegistry) reads the same file
		FileUserRegistry registry = new FileUserRegistry();
		registry.initialize(FileSource.of(f));
		ParleyPrincipal p = registry.findPrincipal("tony@a.test");
		assertNotNull(p);
		assertEquals("a.test/tony", p.getAttribute("maildrop"));
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, registry.authenticate(
				new PasswordCredential("tony@a.test", "password1".toCharArray(), Requester.withoutAddress("test"))).getStatus());
		assertTrue(registry.supports(ProofCredential.class, StoredCredentialType.SCRAM_SHA_256), "every user can log in with SCRAM");

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
		us.bringardner.parley.files.FileSource hs = us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(h.getPath());
		us.bringardner.parley.mail.admin.history.HistoryStore store = new us.bringardner.parley.mail.admin.history.HistoryStore(() -> hs,
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
