package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

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
}
