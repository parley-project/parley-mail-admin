package us.bringardner.parley.mail.admin.server;

import java.io.File;
import java.io.IOException;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.FileBasedAcl;

/**
 * The launcher's mail servers share one user list per users file ({@code parley.mail.users}): read
 * once, and read again when it changes, for every server that uses it, instead of a list of each
 * server's own. Servers whose reload setting ({@link FileBasedAcl#PROP_RELOAD_INTERVAL}) differs get
 * lists of their own, so none gets another server's.
 *
 * @since 2.0.0
 */
public final class SharedUsers {

	/** The {@link ServerContext#shared} key of a users file's list (followed by its canonical path) */
	public static final String KEY = "parley.mail.users:";

	private SharedUsers() {
	}

	/**
	 * Give the server the list the context's servers share for its users file, if it reads one (it
	 * made a list of its own from it when it was made); before it starts.
	 */
	public static void share(ServerContext context, AbstractMailServer server) throws IOException {
		File file = server.getUsersFile();
		if (file == null) {
			return;
		}
		String reload = server.getProperty(FileBasedAcl.PROP_RELOAD_INTERVAL);
		server.setAccessControl(list(context, file, reload));
	}

	/** The list of this (canonical) file the context's servers share, made the first time it's asked for */
	public static FileBasedAcl list(ServerContext context, File file, String reload) throws IOException {
		String key = KEY + file.getPath() + (reload == null ? "" : "|" + reload.trim());
		try {
			return context.shared(key, FileBasedAcl.class, () -> {
				FileBasedAcl acl = new FileBasedAcl();
				acl.initialize(file);
				return acl;
			});
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * The list of this file the context's servers share, or null if none of them uses it (yet): for
	 * an application that changed the file and wants them to read it now ({@code reloadNow()}).
	 */
	public static FileBasedAcl find(ServerContext context, File file) throws IOException {
		String path = file.getCanonicalFile().getPath();
		Object o = context.get(KEY + path);
		if (o == null) {
			// one with a reload setting of its own
			String prefix = KEY + path + "|";
			o = context.sharedKeys().stream().filter(k -> k.startsWith(prefix)).findFirst().map(context::get).orElse(null);
		}
		return o instanceof FileBasedAcl ? (FileBasedAcl) o : null;
	}
}
