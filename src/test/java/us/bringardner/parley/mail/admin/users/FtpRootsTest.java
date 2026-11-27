package us.bringardner.parley.mail.admin.users;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The FTP folder of a user, from the FTP settings primaryRoot and domainRoot. */
public class FtpRootsTest {

	@Test
	public void theRootsAreBelowTheirCommonBase() {
		// the FTP server's root is /ftp: the primary domain's users have all of it, the others their own folder
		assertEquals("/", FtpRoots.root("/ftp", "/ftp/{domain}", "a.test", "a.test"));
		assertEquals("/b.test", FtpRoots.root("/ftp", "/ftp/{domain}", "a.test", "B.test"));
		assertEquals("/ftp", FtpRoots.base("/ftp", "/ftp/{domain}"));
	}

	@Test
	public void aSeparateFolderForThePrimaryDomain() {
		assertEquals("/main", FtpRoots.root("/ftp/main", "/ftp/{domain}", "a.test", "a.test"));
		assertEquals("/b.test", FtpRoots.root("/ftp/main", "/ftp/{domain}", "a.test", "b.test"));
	}

	@Test
	public void withoutTheSettingsTheDefaultApplies() {
		assertEquals("/", FtpRoots.root(null, null, "a.test", "a.test"));
		assertEquals("/b.test", FtpRoots.root(null, null, "a.test", "b.test"));
		// one alone, a relative path or a {name} is not usable: the default
		assertEquals("/b.test", FtpRoots.root("/ftp", null, "a.test", "b.test"));
		assertEquals("/b.test", FtpRoots.root("ftp", "ftp/{domain}", "a.test", "b.test"));
		assertEquals("/b.test", FtpRoots.root("/ftp", "/ftp/{domain}/{name}", "a.test", "b.test"));
	}

	@Test
	public void noCommonFolderMeansTheRootOfTheStore() {
		assertEquals("/ftp", FtpRoots.root("/ftp", "/other/{domain}", "a.test", "a.test"));
		assertEquals("/other/b.test", FtpRoots.root("/ftp", "/other/{domain}", "a.test", "b.test"));
	}

	@Test
	public void aDomainHasItsOwnFolderEvenWhenItsUsersWorkInTheRoot() {
		assertEquals("/a.test", us.bringardner.parley.mail.admin.server.FtpFolders.domainFolder("/ftp", "/ftp/{domain}", "A.test"));
		assertEquals("/b.test", us.bringardner.parley.mail.admin.server.FtpFolders.domainFolder("/ftp/main", "/ftp/{domain}", "b.test"));
		assertEquals("/a.test", us.bringardner.parley.mail.admin.server.FtpFolders.domainFolder(null, null, "a.test"));
		assertEquals("/a.test", us.bringardner.parley.mail.admin.server.FtpFolders.domainFolder("/ftp", "/ftp/{domain}/{name}", "a.test"));
	}
}
