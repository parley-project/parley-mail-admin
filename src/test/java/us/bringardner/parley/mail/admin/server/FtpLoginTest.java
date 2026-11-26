package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthenticationResult.Status;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.mail.admin.TestFiles;
import us.bringardner.parley.mail.admin.users.UserService;

/** The suite's FTP servers let in only the users with the loginPermission setting's permission. */
public class FtpLoginTest {

	@TempDir
	Path dir;

	private UserRegistry registry() throws Exception {
		FileSource f = TestFiles.write(TestFiles.local(dir).getChild("users.txt"), "");
		UserService users = new UserService(f);
		users.add("boss@a.test", "password-one".toCharArray(), Arrays.asList("admin"), null);
		users.add("clerk@a.test", "password-two".toCharArray(), Arrays.asList("READ"), null);
		return users.registry();
	}

	private static AuthenticationResult login(UserRegistry registry, String permission, String name, String password) {
		PasswordCredential c = new PasswordCredential(name, password.toCharArray(), Requester.withoutAddress("test"));
		try {
			return FtpLogin.admit(registry, permission, c, registry.authenticate(c));
		} finally {
			c.clear();
		}
	}

	@Test
	public void onlyAnAdminLogsInWhenTheSettingSaysAdmin() throws Exception {
		UserRegistry r = registry();
		assertEquals(Status.AUTHENTICATED, login(r, "admin", "boss@a.test", "password-one").getStatus());
		assertEquals(Status.REFUSED, login(r, "admin", "clerk@a.test", "password-two").getStatus());
	}

	@Test
	public void everyUserLogsInWithoutTheSetting() throws Exception {
		UserRegistry r = registry();
		assertEquals(Status.AUTHENTICATED, login(r, null, "clerk@a.test", "password-two").getStatus());
		assertEquals(Status.AUTHENTICATED, login(r, " ", "clerk@a.test", "password-two").getStatus());
	}

	@Test
	public void aWrongPasswordIsStillRefusedAndNotChanged() throws Exception {
		UserRegistry r = registry();
		AuthenticationResult wrong = login(r, "admin", "boss@a.test", "nope");
		assertEquals(Status.REFUSED, wrong.getStatus());
		PasswordCredential c = new PasswordCredential("boss@a.test", "nope".toCharArray(), Requester.withoutAddress("test"));
		AuthenticationResult raw = r.authenticate(c);
		assertSame(raw, FtpLogin.admit(r, "admin", c, raw));
		c.clear();
	}
}
