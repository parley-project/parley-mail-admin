package us.bringardner.parley.mail.admin.users;

import java.io.File;
import java.io.IOException;

import us.bringardner.parley.net.server.AccessControlUnavailableException;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.server.IServer;

/**
 * The one user list of every server in the suite: a {@link FileBasedAcl} of the users file, made
 * again whenever the file changes (from the Users page or otherwise), so users can be added and
 * passwords changed while the servers run. If the file can't be read, the previous list stays in
 * use.
 */
public final class SuiteAcl implements IAccessControlList {

	/** The property prefix FileBasedAcl reads its {@code userFile} with. */
	static final String ACL_NAME = "parley.mail.admin.acl";

	private volatile FileBasedAcl current;
	private volatile File file;

	public SuiteAcl(File file) throws IOException {
		reload(file);
	}

	public File getFile() {
		return file;
	}

	/** Read the users file again (another file if it moved). */
	public synchronized void reload(File newFile) throws IOException {
		System.setProperty(ACL_NAME + "." + FileBasedAcl.PROP_FILE_NAME, newFile.getAbsolutePath());
		FileBasedAcl acl = new FileBasedAcl();
		acl.initialize(ACL_NAME);
		current = acl;
		file = newFile;
	}

	public void reload() throws IOException {
		reload(file);
	}

	@Override
	public void initialize(IServer server) {
		// already loaded: one list for every server
	}

	@Override
	public void initialize(String serverName) {
	}

	@Override
	public boolean checkPermission(IPrincipal user, IPermission action) {
		return current.checkPermission(user, action);
	}

	@Override
	public IPrincipal getPrincipal(String user) {
		return current.getPrincipal(user);
	}

	@Override
	public IPrincipal findPrincipal(String user) throws AccessControlUnavailableException {
		return current.findPrincipal(user);
	}

	@Override
	public void authenticateUnknownUser(byte[] credentials) {
		current.authenticateUnknownUser(credentials);
	}

	@Override
	public boolean supportsScram(String hash) {
		return current.supportsScram(hash);
	}

	@Override
	public ScramCredentials getScramCredentials(String user, String hash) throws AccessControlUnavailableException {
		return current.getScramCredentials(user, hash);
	}
}
