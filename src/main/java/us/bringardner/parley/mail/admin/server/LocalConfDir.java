package us.bringardner.parley.mail.admin.server;

import java.io.File;
import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.ServerMain;

/**
 * parley-net's {@link ServerMain#confDir()} (a FileSource on the default file system) as a local
 * folder: this application keeps its settings as local files, so CONF must be on the local file
 * system. It is, unless parley.files.defaultFactory names another.
 */
public final class LocalConfDir {

	private LocalConfDir() {
	}

	/**
	 * @return CONF as a local folder
	 * @throws IOException if CONF is on another file system, or that file system can't be connected
	 */
	public static File get() throws IOException {
		FileSource conf = ServerMain.confDir();
		try {
			return conf.toFile();
		} catch (UnsupportedOperationException e) {
			throw new IOException("The settings folder " + conf + " is on " + conf.getFileSourceFactory().getTypeId()
					+ "; Parley mail admin keeps its settings on the local file system");
		}
	}
}
