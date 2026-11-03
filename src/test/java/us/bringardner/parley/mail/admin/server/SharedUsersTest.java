package us.bringardner.parley.mail.admin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IAccessControlList;

/**
 * The launcher's mail servers share one user list per users file (moved here from parley-mail and
 * parley-net with ServerContext): per launcher, not per JVM; closed with the context.
 */
public class SharedUsersTest {

	/** A mail server that is only made, never started */
	static class Plain extends AbstractMailServer {
		Plain(String prefix) {
			super(0, "Plain", prefix, false);
			finishInit();
		}

		@Override
		protected String getRootProperty() {
			return MAIL_ROOT_PROP;
		}
	}

	@TempDir
	File dir;

	private final ServerContext one = new ServerContext(MBeanServerFactory.newMBeanServer());
	private final ServerContext two = new ServerContext(MBeanServerFactory.newMBeanServer());

	@AfterEach
	public void clear() {
		one.close();
		two.close();
		System.clearProperty(AbstractMailServer.MAIL_USERS_PROP);
	}

	private File file(String name, String text) throws IOException {
		File f = new File(dir, name);
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	private Plain made(ServerContext context, String prefix) throws Exception {
		return MailServices.make(context, null, () -> new Plain(prefix));
	}

	@Test
	public void theServersOfOneLauncherShareTheUsers() throws Exception {
		File users = file("users.txt", "tony,secret,READ|WRITE\n");
		System.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		Plain a = made(one, "parley.mail.a");
		Plain b = made(one, "parley.mail.b");
		Plain c = made(two, "parley.mail.a");
		IAccessControlList acl = a.getAccessControl();
		assertNotNull(acl);
		assertNotNull(acl.getPrincipal("tony"));
		assertSame(acl, b.getAccessControl());
		assertSame(acl, SharedUsers.find(one, users));
		assertNotSame(acl, c.getAccessControl(), "another launcher has its own");
		assertNull(SharedUsers.find(new ServerContext(MBeanServerFactory.newMBeanServer()), users));

		Plain byCode = new Plain("parley.mail.a");
		assertNotNull(byCode.getAccessControl());
		assertNotSame(acl, byCode.getAccessControl(), "made by code: a list of its own");

		// the application changed the file: the shared list reads it now
		Files.write(users.toPath(), "tony,secret,READ\njose,other,READ\n".getBytes(StandardCharsets.UTF_8));
		users.setLastModified(System.currentTimeMillis() + 5000);
		SharedUsers.find(one, users).reloadNow();
		assertNotNull(b.getAccessControl().getPrincipal("jose"));
	}

	@Test
	public void serversWithAnotherReloadSettingGetTheirOwn() throws Exception {
		File users = file("users.txt", "tony,secret,READ\n");
		java.util.Properties fast = new java.util.Properties();
		fast.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		fast.setProperty(FileBasedAcl.PROP_RELOAD_INTERVAL, "1s");
		java.util.Properties plain = new java.util.Properties();
		plain.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		Plain a = MailServices.make(one, fast, () -> new Plain("parley.mail.a"));
		Plain b = MailServices.make(one, plain, () -> new Plain("parley.mail.a"));
		assertNotSame(a.getAccessControl(), b.getAccessControl());
		assertNotNull(SharedUsers.find(one, users), "found under either key");
	}

	static class CountingAcl extends FileBasedAcl {
		final AtomicInteger closed = new AtomicInteger();

		@Override
		public void close() {
			closed.incrementAndGet();
		}
	}

	@Test
	public void aSharedListIsClosedWithTheContext() throws Exception {
		ServerContext context = new ServerContext(MBeanServerFactory.newMBeanServer());
		CountingAcl shared = context.shared("users", CountingAcl.class, CountingAcl::new);
		assertSame(shared, context.shared("users", CountingAcl.class, CountingAcl::new));
		assertEquals(0, shared.closed.get());
		context.close();
		assertEquals(1, shared.closed.get(), "an IAccessControlList is AutoCloseable, so the context closes it");
		assertTrue(context.sharedKeys().isEmpty());
	}
}
