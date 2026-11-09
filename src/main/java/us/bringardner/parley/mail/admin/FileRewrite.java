package us.bringardner.parley.mail.admin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.GroupPrincipal;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.ILogger;
import us.bringardner.parley.files.FileSource;

/**
 * Writes a file through a uniquely named temporary file in the same folder, then renames it over
 * the original ({@code renameTo(dest, true)}), so a crash never leaves half a file. The rename is
 * one step where the file system's {@code getCapabilities().atomicReplace()} says so (local
 * files); elsewhere the file is missing for a moment. The new file keeps the original's owner,
 * group and other permissions and its group (a users file readable by the mail servers' group
 * stays readable by it), where the file system can give them; a file that didn't exist gets the
 * permissions every new file of this process gets. In a folder this process may not add files
 * to, a file it may write is written in place. Nothing here assumes which user or group the
 * application runs as, or which file system the file is on.
 */
public final class FileRewrite {

	/** The logger, looked up each time: parley-core picks the logger type at the first lookup,
	 *  and clearLoggerCache() would leave a kept one behind */
	private static ILogger log() {
		return BaseObject.findLogger(FileRewrite.class.getName());
	}

	private FileRewrite() {
	}

	/** Replace the file's content with these bytes. */
	public static void write(FileSource file, byte[] content) throws IOException {
		FileSource dir = file.getParentFile();
		if (dir == null) {
			throw new IOException("Can't write " + file.getPath() + ": it has no folder");
		}
		if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
			throw new IOException("Can't create " + dir.getPath());
		}
		FileSource tmp;
		try {
			// a name of its own, so another writer of the file can't collide
			tmp = dir.getFileSourceFactory().createTempFile("." + file.getName() + ".", ".tmp", dir);
		} catch (IOException e) {
			if (file.isFile() && file.canWrite()) {
				// a folder this process may not add files to, but a file it may write (the mail store's
				// settings in a root only its owner adds to): written in place, keeping everything about it
				try (OutputStream out = file.getOutputStream()) {
					out.write(content);
				}
				return;
			}
			throw new IOException("Can't write " + file.getPath() + ": this process may not add files to " + dir.getPath()
					+ " (" + e.getMessage() + ")", e);
		}
		boolean moved = false;
		try {
			try (OutputStream out = tmp.getOutputStream()) {
				out.write(content);
			}
			if (file.exists()) {
				copyAttributes(file, tmp);
			}
			moved = tmp.renameTo(file, true);
			if (!moved) {
				throw new IOException("Can't replace " + file.getPath() + " with " + tmp.getPath());
			}
		} finally {
			if (!moved && tmp.exists()) {
				tmp.delete();
			}
		}
	}

	/**
	 * Give the copy the original's owner, group and other permissions and its group, where the file
	 * system has them; what it can't give is skipped with a log line (a group this process may not
	 * give, because it isn't a member, is left as it is).
	 */
	public static void copyAttributes(FileSource original, FileSource copy) {
		try {
			boolean all = copy.setOwnerReadable(original.canOwnerRead())
					& copy.setOwnerWritable(original.canOwnerWrite())
					& copy.setOwnerExecutable(original.canOwnerExecute())
					& copy.setGroupReadable(original.canGroupRead())
					& copy.setGroupWritable(original.canGroupWrite())
					& copy.setGroupExecutable(original.canGroupExecute())
					& copy.setOtherReadable(original.canOtherRead())
					& copy.setOtherWritable(original.canOtherWrite())
					& copy.setOtherExecutable(original.canOtherExecute());
			if (!all) {
				log().warn("Can't give " + original.getPath() + " all of its permissions again");
			}
		} catch (IOException | RuntimeException e) {
			log().warn("Can't give " + original.getPath() + " its permissions again: " + e.getMessage());
		}
		try {
			GroupPrincipal group = original.getGroup();
			if (group != null && !group.equals(copy.getGroup()) && !copy.setGroup(group)) {
				log().warn("Can't give " + original.getPath() + " its group " + group.getName() + " again");
			}
		} catch (IOException | RuntimeException e) {
			log().warn("Can't give " + original.getPath() + " its group again: " + e.getMessage());
		}
	}
}
