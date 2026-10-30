package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.history.HistoryStore;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;

/**
 * A mail store that isn't on this machine's disk (a database several servers share, say): the
 * admin opens it as the servers do, with parley.mail.fileSource's connection settings or a
 * filesource: URL, for the store's and queue's settings, mailbox sizes and the SMTP history.
 */
public class RemoteStoreTest {

	@TempDir
	File dir;

	@AfterEach
	public void close() {
		RecordingFileSourceFactory.last = null;
	}

	private void settings(String text) throws IOException {
		Files.writeString(new File(dir, "parley-mail.properties").toPath(), text, StandardCharsets.UTF_8);
	}

	private static void write(FileSource f, String text) throws IOException {
		f.getParentFile().mkdirs();
		try (OutputStream o = f.getOutputStream()) {
			o.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void theFileSystemGetsTheServersConnectionSettings() throws Exception {
		settings("parley.mail.root=/mail\nparley.mail.fileSource=Recording\n"
				+ "parley.mail.fileSource.host=db1.example.test\nparley.mail.fileSource.password=@file:/etc/parley/db.secret\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		assertNull(s.getAssetError());
		assertFalse(s.isLocalMailRoot());
		RecordingFileSourceFactory f = RecordingFileSourceFactory.last;
		assertEquals("db1.example.test", f.settings.getProperty("host"));
		assertEquals("@file:/etc/parley/db.secret", f.settings.getProperty("password"), "the reference: the file system reads it");

		s.set("store.sharing", "cluster");
		s.set("queue.domains", "example.test");
		FileSource root = s.getMailRoot();
		assertSame(f, root.getFileSourceFactory(), "one connection, kept");
		assertTrue(root.getChild(".parley-store.properties").isFile(), "written to the store's file system");
		assertTrue(root.getChild(".smtp-queue").getChild("queue.properties").isFile());
	}

	@Test
	public void aFilesourceUrlRoot() throws Exception {
		settings("parley.mail.root=filesource:/mail?sourcetype=recording&host=db2.example.test\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		assertNull(s.getAssetError());
		assertFalse(s.isLocalMailRoot(), "a URL is never a local path");
		assertTrue(RecordingFileSourceFactory.last.url.getQuery().contains("host=db2.example.test"));
		assertNull(s.getStoreFile().getFile(), "a FileSource, not a local file");
		assertEquals("/mail/.parley-store.properties", s.getStoreFile().getPath());
	}

	@Test
	public void aStoreThatCantBeReachedIsSaidWithoutTheUrlsSettings() throws Exception {
		settings("parley.mail.root=filesource:/mail?sourcetype=nosuch&password=hunter2\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		assertNull(s.getStoreFile());
		assertNotNull(s.getAssetError());
		assertTrue(s.getAssetError().startsWith("Can't reach the mail root filesource:/mail?..."), s.getAssetError());
		assertFalse(s.getAssetError().contains("hunter2"), s.getAssetError());
		IOException e = assertThrows(IOException.class, s::getMailRoot);
		assertFalse(e.getMessage().contains("hunter2"), e.getMessage());

		settings("parley.mail.root=/mail\nparley.mail.fileSource=nosuch\n");
		s.load();
		assertTrue(s.getAssetError().contains("There is no file system nosuch (" + SettingsCatalog.FILE_SOURCE + ")"), s.getAssetError());
	}

	@Test
	public void aNewConnectionOnlyWhenItsSettingsChange() throws Exception {
		settings("parley.mail.root=/mail\nparley.mail.fileSource=recording\nparley.mail.fileSource.host=a\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		FileSource first = s.getMailRoot();
		assertSame(first, s.getMailRoot());
		s.set(SettingsCatalog.FILE_SOURCE + ".host", "b");
		FileSource second = s.getMailRoot();
		assertFalse(first == second);
		assertEquals("b", ((RecordingFileSourceFactory) second.getFileSourceFactory()).settings.getProperty("host"));
	}

	@Test
	public void theHistoryOnTheStoresFileSystem() throws Exception {
		settings("parley.mail.root=/mail\nparley.mail.fileSource=recording\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		FileSource h = s.getMailRoot().getChild(".smtp-history");
		java.time.LocalDate today = java.time.LocalDate.now();
		write(h.getChild("smtp-" + today + ".jsonl"), "{\"time\":1,\"type\":\"QUEUED\",\"id\":\"m1\"}\n");
		List<Map<String, Object>> rows = new HistoryStore(() -> h, new com.fasterxml.jackson.databind.ObjectMapper()).query(today, today, null,
				null, 10);
		assertEquals(1, rows.size());
		assertEquals("m1", rows.get(0).get("id"));
	}

	@Test
	public void theHistoryOnLocalDiskBesideAStoreOnAnotherFileSystem() throws Exception {
		File local = new File(dir, "local-history");
		settings("parley.mail.root=/mail\nparley.mail.fileSource=recording\n");
		SettingsService s = new SettingsService(dir, new SettingsCatalog());
		s.load();
		s.set(SettingsCatalog.QUEUE_HISTORY_FILE_SOURCE, "FileProxy");
		s.set(SettingsCatalog.QUEUE_HISTORY_DIRECTORY, local.getPath());
		assertEquals("FileProxy", s.get(SettingsCatalog.QUEUE_HISTORY_FILE_SOURCE, null));
		FileSource h = us.bringardner.parley.smtp.queue.QueueHistory.folder(s.getMailRoot(),
				s.get(SettingsCatalog.QUEUE_HISTORY_FILE_SOURCE, null), s.get(SettingsCatalog.QUEUE_HISTORY_DIRECTORY, null));
		assertEquals("fileproxy", h.getFileSourceFactory().getTypeId());
		assertEquals(local.getAbsolutePath(), h.getAbsolutePath());
		assertEquals("recording", s.getMailRoot().getFileSourceFactory().getTypeId(), "the mail itself stays on its own file system");
	}
}
