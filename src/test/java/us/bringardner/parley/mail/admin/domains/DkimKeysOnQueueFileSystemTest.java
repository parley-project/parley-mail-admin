package us.bringardner.parley.mail.admin.domains;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.admin.TestFiles;
import us.bringardner.parley.mail.admin.domains.DomainService.DkimEntry;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.UserService;

/** DKIM keys are made, found and read on the file system the queue is on, as parley-smtp's servers read them */
public class DkimKeysOnQueueFileSystemTest {

	@TempDir
	Path tempDir;

	private SettingsService settings;

	@AfterEach
	public void disconnect() throws Exception {
		if (settings != null) {
			settings.getMailRoot().getFileSourceFactory().disConnect();
		}
	}

	@Test
	public void aKeyIsMadeAndFoundOnTheMailRootsFileSystem() throws Exception {
		FileSource dir = FileSourceFactory.fileProxyFactory.createFileSource(tempDir.toString());
		TestFiles.write(dir.getChild("parley-mail.properties"), "parley.mail.root=/mail\nparley.mail.fileSource=memory\n");
		FileSource users = dir.getChild("users.txt");
		TestFiles.write(users, "");
		FileSource aliases = dir.getChild("aliases.txt");
		TestFiles.write(aliases, "");
		FileSource homeDkim = dir.getChild("home-dkim");
		homeDkim.mkdirs();

		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		settings = s;
		s.load();
		s.set("queue.domains", "a.test");
		DomainService d = new DomainService(s, new UserService(users), new AliasStore(aliases), homeDkim);

		DkimEntry e = d.generateDkim("a.test", "s1", "rsa");
		assertEquals("/mail/.smtp-queue/dkim/a.test-s1.pem", e.keyFile, "in the queue's folder on the store");
		assertEquals("memory", s.onQueueFileSystem(e.keyFile).getFileSourceFactory().getTypeId());
		assertTrue(s.onQueueFileSystem(e.keyFile).isFile(), "on the store's file system");
		assertTrue(homeDkim.listFiles().length == 0, "nothing on the admin home's disk");

		assertNotNull(d.dkimRecord(e), "the TXT record is found next to the key");
		@SuppressWarnings("unchecked")
		Map<String, Object> dk = (Map<String, Object>) d.describe("a.test").get("dkim");
		assertEquals(Boolean.TRUE, dk.get("keyFileExists"), "the page finds the key");

		// a key the store doesn't hold is missing
		assertFalse(Boolean.TRUE.equals(describeWith(s, d, "b.test:s9:/mail/.smtp-queue/dkim/none.pem")));
	}

	private static Object describeWith(SettingsService s, DomainService d, String keys) throws Exception {
		s.set("queue.domains", "a.test,b.test");
		s.set("queue.dkim.keys", keys);
		@SuppressWarnings("unchecked")
		Map<String, Object> dk = (Map<String, Object>) d.describe("b.test").get("dkim");
		return dk.get("keyFileExists");
	}
}
