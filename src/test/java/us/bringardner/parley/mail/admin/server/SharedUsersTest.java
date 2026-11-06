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

import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.FileUserRegistry;

/**
 * The launcher's mail servers share one user registry per users file (moved here from parley-mail and
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
		UserRegistry registry = a.getUserRegistry();
		assertNotNull(registry);
		assertNotNull(registry.findPrincipal("tony"));
		assertSame(registry, b.getUserRegistry());
		assertSame(registry, SharedUsers.find(one, users));
		assertNotSame(registry, c.getUserRegistry(), "another launcher has its own");
		assertNull(SharedUsers.find(new ServerContext(MBeanServerFactory.newMBeanServer()), users));

		Plain byCode = new Plain("parley.mail.a");
		assertNotNull(byCode.getUserRegistry());
		assertNotSame(registry, byCode.getUserRegistry(), "made by code: a registry of its own");

		// the application changed the file: the shared registry reads it now
		Files.write(users.toPath(), "tony,secret,READ\njose,other,READ\n".getBytes(StandardCharsets.UTF_8));
		users.setLastModified(System.currentTimeMillis() + 5000);
		SharedUsers.find(one, users).reloadNow();
		assertNotNull(b.getUserRegistry().findPrincipal("jose"));
	}

	@Test
	public void serversWithAnotherReloadSettingGetTheirOwn() throws Exception {
		File users = file("users.txt", "tony,secret,READ\n");
		java.util.Properties fast = new java.util.Properties();
		fast.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		fast.setProperty(FileUserRegistry.PROP_RELOAD_INTERVAL, "1s");
		java.util.Properties plain = new java.util.Properties();
		plain.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		Plain a = MailServices.make(one, fast, () -> new Plain("parley.mail.a"));
		Plain b = MailServices.make(one, plain, () -> new Plain("parley.mail.a"));
		assertNotSame(a.getUserRegistry(), b.getUserRegistry());
		assertSame(fast, ((FileUserRegistry) a.getUserRegistry()).getConfiguration(), "made with the servers' settings: their reload interval");
		assertNotNull(SharedUsers.find(one, users), "found under either key");
	}

	static class CountingRegistry extends FileUserRegistry {
		final AtomicInteger closed = new AtomicInteger();

		@Override
		public void close() {
			closed.incrementAndGet();
		}
	}

	@Test
	public void aSharedRegistryIsClosedWithTheContext() throws Exception {
		ServerContext context = new ServerContext(MBeanServerFactory.newMBeanServer());
		CountingRegistry shared = context.shared("users", CountingRegistry.class, CountingRegistry::new);
		assertSame(shared, context.shared("users", CountingRegistry.class, CountingRegistry::new));
		assertEquals(0, shared.closed.get());
		context.close();
		assertEquals(1, shared.closed.get(), "a UserRegistry is Closeable, so the context closes it");
		assertTrue(context.sharedKeys().isEmpty());
	}
}
