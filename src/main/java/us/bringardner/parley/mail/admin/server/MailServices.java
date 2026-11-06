package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import javax.management.JMException;
import javax.management.ObjectName;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.store.MailStoreAdmin;
import us.bringardner.parley.mail.store.MailStoreAdminMXBean;
import us.bringardner.parley.net.server.ParleyConnection;
import us.bringardner.parley.net.server.ServerMain;

/**
 * How the launcher makes and runs a mail server: from its own configuration, with the user registry the
 * launcher's servers share ({@link SharedUsers}), and while it runs with its beans registered in the
 * launcher's {@link ServerContext}: its store's administration
 * ({@code us.bringardner.parley:type=MailStore,name=ID}, the launcher's id for it) and any others the
 * caller adds (the SMTP queue).
 *
 * @since 2.0.0
 */
final class MailServices {

	private MailServices() {
	}

	/** Make a mail server from its configuration (parley-core's BaseObject.makeWith) and give it the shared users */
	static <T extends AbstractMailServer> T make(ServerContext context, java.util.Properties configuration, Callable<T> make)
			throws Exception {
		T server = BaseObject.makeWith(configuration, make);
		SharedUsers.share(context, server);
		return server;
	}

	/** Run a mail server, its store bean registered while it runs */
	static ServerMain.Service service(AbstractMailServer server, String id, String name, ServerContext context) {
		return service(server, id, name, context, null);
	}

	/**
	 * Run a mail server, its store bean and the caller's registered while it runs.
	 *
	 * @param more registers the caller's beans once the server listens and returns their names (null: none)
	 */
	static ServerMain.Service service(AbstractMailServer server, String id, String name, ServerContext context,
			Function<ServerContext, List<ObjectName>> more) {
		ServerMain.Service s = ServerMain.service(server, name);
		return new ServerMain.Service() {
			private final java.util.List<ObjectName> beans = new java.util.ArrayList<>();
			private boolean registered;

			@Override
			public String name() {
				return s.name();
			}

			@Override
			public void startAndWait(long timeoutMillis) throws IOException {
				s.startAndWait(timeoutMillis);
				register();
			}

			private synchronized void register() {
				if (registered) {
					return;
				}
				registered = true;
				MailStoreAdmin admin = server.getStoreAdmin();
				if (admin != null) {
					try {
						beans.add(context.register(MailStoreAdminMXBean.TYPE, id, admin));
					} catch (JMException | RuntimeException e) {
						System.err.println("Can't register the mail store's JMX bean for " + id + ": " + e);
					}
				}
				if (more != null) {
					try {
						beans.addAll(more.apply(context));
					} catch (RuntimeException e) {
						System.err.println("Can't register the JMX beans of " + id + ": " + e);
					}
				}
			}

			private synchronized void unregister() {
				if (registered) {
					for (ObjectName n : beans) {
						context.unregister(n);
					}
					beans.clear();
					registered = false;
				}
			}

			@Override
			public int port() {
				return s.port();
			}

			@Override
			public void stop(long timeoutMillis) throws InterruptedException {
				// first, so no change through the store's bean runs once the server gives the store up
				unregister();
				s.stop(timeoutMillis);
			}

			@Override
			public void awaitStop() throws InterruptedException {
				s.awaitStop();
				unregister();
			}

			@Override
			public int connections() {
				return s.connections();
			}

			@Override
			public List<ParleyConnection> connectionList() {
				return s.connectionList();
			}
		};
	}
}
