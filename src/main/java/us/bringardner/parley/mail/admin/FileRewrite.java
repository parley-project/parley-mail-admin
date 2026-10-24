package us.bringardner.parley.mail.admin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;

/**
 * Writes a file through a temporary file in the same folder, then renames it over the original, so
 * a crash never leaves half a file. The new file keeps the original's permissions and group (a
 * users file readable by the mail servers' group stays readable by it); a file that didn't exist
 * gets the permissions every new file of this process gets. Nothing here assumes which user or
 * group the application runs as.
 */
public final class FileRewrite {

	private static final System.Logger LOG = System.getLogger(FileRewrite.class.getName());

	private FileRewrite() {
	}

	/** Replace the file's content with these bytes. */
	public static void write(File file, byte[] content) throws IOException {
		File target = file.getAbsoluteFile();
		File dir = target.getParentFile();
		if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Can't create " + dir);
		}
		Path tmp = new File(dir, "." + target.getName() + ".tmp").toPath();
		Files.write(tmp, content);
		try {
			copyAttributes(target.toPath(), tmp);
			try {
				Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Give the new file the original's permissions and group, where the file system has them. A
	 * group this process may not give (it isn't a member) is left as it is, with a warning.
	 */
	static void copyAttributes(Path original, Path copy) throws IOException {
		if (!Files.exists(original)) {
			return;
		}
		PosixFileAttributeView from = Files.getFileAttributeView(original, PosixFileAttributeView.class);
		PosixFileAttributeView to = Files.getFileAttributeView(copy, PosixFileAttributeView.class);
		if (from == null || to == null) {
			return; // Windows: the folder's ACLs apply to the new file
		}
		PosixFileAttributes a = from.readAttributes();
		GroupPrincipal group = a.group();
		try {
			if (!group.equals(to.readAttributes().group())) {
				to.setGroup(group);
			}
		} catch (IOException | SecurityException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't give " + original + " its group " + group.getName() + " again: " + e.getMessage());
		}
		to.setPermissions(a.permissions());
	}
}
