package us.bringardner.parley.mail.admin.server;

import java.io.File;
import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.FileUserRegistry;

/**
 * The launcher's mail servers share one user registry (parley-net's {@link FileUserRegistry}) per
 * users file ({@code parley.mail.users}): read once, and read again when it changes, for every server
 * that uses it, instead of a registry of each server's own. Servers whose reload setting
 * ({@link FileUserRegistry#PROP_RELOAD_INTERVAL}) differs get registries of their own, so none gets
 * another server's.
 *
 * @since 2.0.0
 */
public final class SharedUsers {

	/** The {@link ServerContext#shared} key of a users file's registry (followed by its canonical path) */
	public static final String KEY = "parley.mail.users:";

	private SharedUsers() {
	}

	/**
	 * Give the server the registry the context's servers share for its users file, if it reads one (it
	 * made a registry of its own from it when it was made); before it starts.
	 */
	public static void share(ServerContext context, AbstractMailServer server) throws IOException {
		FileSource file = server.getUsersFile();
		if (file == null) {
			return;
		}
		String reload = server.getProperty(FileUserRegistry.PROP_RELOAD_INTERVAL);
		server.setUserRegistry(list(context, file, reload, server.getConfiguration()));
	}

	/**
	 * The registry of this (canonical) file the context's servers share, made the first time it's asked for
	 * @param reload the servers' {@link FileUserRegistry#PROP_RELOAD_INTERVAL} (part of the key)
	 * @param configuration the settings the registry is made with (the first server's, which have that
	 *  reload setting), or null for the system properties
	 */
	public static FileUserRegistry list(ServerContext context, FileSource file, String reload, java.util.Properties configuration) throws IOException {
		String key = KEY + file.getPath() + (reload == null ? "" : "|" + reload.trim());
		try {
			return context.shared(key, FileUserRegistry.class, () -> {
				// made with the servers' settings (its reload interval), as a server makes its own
				FileUserRegistry registry = us.bringardner.parley.core.BaseObject.makeWith(configuration, FileUserRegistry::new);
				registry.initialize(file);
				return registry;
			});
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * The registry of this file the context's servers share, or null if none of them uses it (yet): for
	 * an application that changed the file and wants them to read it now ({@code reloadNow()}).
	 */
	public static FileUserRegistry find(ServerContext context, File file) throws IOException {
		String path = file.getCanonicalFile().getPath();
		Object o = context.get(KEY + path);
		if (o == null) {
			// one with a reload setting of its own
			String prefix = KEY + path + "|";
			o = context.sharedKeys().stream().filter(k -> k.startsWith(prefix)).findFirst().map(context::get).orElse(null);
		}
		return o instanceof FileUserRegistry ? (FileUserRegistry) o : null;
	}
}
