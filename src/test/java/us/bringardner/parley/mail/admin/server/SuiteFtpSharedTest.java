package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The FTPS server falls back to the FTP server's shared settings; the FTP server reads only its own. */
public class SuiteFtpSharedTest {

	@Test
	public void ftpsFallsBackToFtp() {
		assertArrayEquals(new String[] { "parley.mail.ftp" }, SuiteFtp.sharedWith("parley.mail.ftps"));
	}

	@Test
	public void ftpHasNoFallback() {
		assertArrayEquals(new String[0], SuiteFtp.sharedWith("parley.mail.ftp"));
	}

	@Test
	public void makeFolderMakesTheMissingFoldersOnce() throws Exception {
		FileSource root = new MemoryFileSourceFactory().createFileSource("/ftp");
		root.mkdirs();
		SuiteFtp.makeFolder(root, "/a.test");
		SuiteFtp.makeFolder(root, "/a.test");
		SuiteFtp.makeFolder(root, "/x/y");
		assertTrue(root.getChild("a.test").isDirectory());
		assertTrue(root.getChild("x").getChild("y").isDirectory());
	}
}
