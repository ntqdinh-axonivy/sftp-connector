package com.axonivy.connector.sftp.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import ch.ivyteam.log.Logger;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import ch.ivyteam.ivy.environment.Ivy;

import static com.axonivy.connector.sftp.enums.AuthMethod.PASSWORD;
/**
 * Service class for file transfer to/from the SFTP server. The service class is
 * used to decouple the SFTP implementation.
 */
public class SftpClientService implements AutoCloseable {
	private static final Logger LOG = Ivy.log();

	private static final String PATHSEPARATOR = "/";
	private static final int SESSION_TIMEOUT = 10000;
	private static final int CHANNEL_TIMEOUT = 5000;
	private static final String PARENT_DIR_REFERENCE = "..";
	private static final String CURRENT_DIR_WITH_SLASH = "./";
	private static final String CURRENT_DIR_REFERENCE = ".";
	private static final String ABSOLUTE_PATH_PREFIX = "/";
	private static final String WINDOWS_PATH_SEPARATOR = "\\";
	private static final String BASE_LOCAL_DIR_VAR = "baseLocalDir";
	private static final String ENFORCE_PATH_RESTRICTIONS_VAR = "enforcePathRestrictions";
	private static final String SFTP_VAR = "com.axonivy.connector.sftp.server";
	private static final String HOST_VAR = "host";
	private static final String PORT_VAR = "port";
	private static final String SECRET_SSHPASSPHRASE_VAR = "sshPassphraseSecret";
	private static final String SSHKEY_FILEPATH_VAR = "sshkeyFilePath";
	private static final String AUTH_VAR = "auth";
	private static final String PASSWORD_VAR = "password";
	private static final String USERNAME_VAR = "username";

	/**
	 * A Session represents a connection to an SSH server.
	 */
	private Session session;
	/**
	 * A Channel connected to an SFTP server (as a subsystem of the ssh server).
	 */
	private ChannelSftp channel;
	/**
	 * Base directory for local file operations (validation reference point).
	 */
	private Path baseLocalDir;
	
	private boolean enforcePathRestrictions;

	/***
	 * 
	 * @param sftpName
	 * @throws IOException 
	 */
	public SftpClientService(String sftpName) throws IOException {
		String host = getClientHost(sftpName);
		String portRaw = getPort(sftpName);
		String username = getUsername(sftpName);
		String password = getVar(sftpName, PASSWORD_VAR);
		String auth = getVar(sftpName, AUTH_VAR);
		String sshKeyFilePath = getVar(sftpName, SSHKEY_FILEPATH_VAR);
		String secretSSHpassphrase = getVar(sftpName, SECRET_SSHPASSPHRASE_VAR);
		String enforcePathRestrictionsString = getVar(sftpName, ENFORCE_PATH_RESTRICTIONS_VAR);
		// Initialize base directory with default if not configured
		enforcePathRestrictions = Boolean.parseBoolean(enforcePathRestrictionsString);
		if (enforcePathRestrictions) {
			String baseLocalDirStr = getVar(sftpName, BASE_LOCAL_DIR_VAR);
			if (StringUtils.isEmpty(baseLocalDirStr)) {
				throw new IOException("Security validation is enabled (enforcePathRestrictions=true) but baseLocalDir is not configured. ");
			}
			baseLocalDir = Paths.get(baseLocalDirStr);
		}
		int port = 22;
		try {
			port = Integer.parseInt(portRaw);
		} catch (NumberFormatException nfe) {
			LOG.error("The Global Variable: com.axonivy.connector.sftp.server.port = {0} does not contain a number. The default port number: {1} will be used instead.",
					portRaw, port, nfe);
		}
		LOG.debug("The following settings will be used to connect to the SFTP server: hostname: {0}, port: {1}, username: {2}. Connection in progress...",
				host, port, username);
		try {
			JSch jsch = new JSch();

			session = jsch.getSession(username, host, port);
			if (StringUtils.isEmpty(auth) || PASSWORD.name().equalsIgnoreCase(auth)) {
				session.setPassword(password);
			} else {
				byte[] sshKeyBytes = Files.readAllBytes(Paths.get(sshKeyFilePath));
				session.setConfig("PreferredAuthentications", "publickey");
				jsch.addIdentity(null, sshKeyBytes, null, secretSSHpassphrase.getBytes());
			}
			// 10 seconds session timeout
			session.connect(SESSION_TIMEOUT);
			
			channel = (ChannelSftp) session.openChannel("sftp");
			if (channel == null) {
				close();
				throw new IOException("Error while opening the channel to SFTP session '" + host + "' with username '"
						+ username + "'!");
			}
			// 5 seconds timeout
			channel.connect(CHANNEL_TIMEOUT);
		} catch (JSchException ex) {
			throw new IOException(
					"Error while trying to connect to SFTP server '" + host + "' with username '" + username + "': ",
					ex);
		}
		LOG.debug("Connection established.");
	}

	/**
	 * Closes the current channel and the connection to the server.
	 */
	@Override
	public void close() {
		try {
			if (channel != null) {
				channel.disconnect();
				channel = null;
			}
		} finally {
			if (session != null) {
				session.disconnect();
				session = null;
			}
		}
	}

	/**
	 * Returns the current local directory in absolute form.
	 * 
	 * @return the current local directory
	 */
	public String getLocalCurrentDir() {
		return channel.lpwd();
	}

	/**
	 * Creates a new remote directory.
	 * 
	 * @param name
	 * @throws IOException
	 */
	public void makeRemoteDir(String name) throws IOException {
		validateRemotePath(name);
		try {
			channel.mkdir(name);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Returns the current remote directory in absolute form.
	 * 
	 * @return the current remote directory
	 * @throws IOException
	 */
	public String getRemoteCurrentDir() throws IOException {
		try {
			return channel.pwd();
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Returns the File information of a single file.
	 * 
	 * @param remoteFilePath
	 * @return the File information
	 */
	public FileData getFileData(String remoteFilePath) {
		validateRemotePath(remoteFilePath);
		FileData fd = null;
		try {
			List<ChannelSftp.LsEntry> lsEntryList = channel.ls(remoteFilePath);
			if (lsEntryList != null && !lsEntryList.isEmpty()) {
				LsEntry lsEntry = lsEntryList.get(0);

				fd = new FileData();
				int i = remoteFilePath.lastIndexOf('/');
				fd.parentPath = (i < 0) ? "" : remoteFilePath.substring(0, i);
				fd.isDirectory = lsEntry.getAttrs().isDir();
				fd.isFile = !lsEntry.getAttrs().isDir() && !lsEntry.getAttrs().isLink();
				fd.name = lsEntry.getFilename();
				fd.size = lsEntry.getAttrs().getSize();
				fd.modificationDate = new Date(1000L * lsEntry.getAttrs().getMTime());
			}
		} catch (SftpException ex) { // If an error occurs, null will be returned
			LOG.warn("If an error occurs, null will be returned", ex);
		}

		return fd;
	}

	/**
	 * Returns the list of all File information of all the files in a directory.
	 * 
	 * @param remoteDir
	 * @return
	 */
	public List<FileData> getFileDataList(String remoteDir) {
		validateRemotePath(remoteDir);
		List<FileData> fileDataList = new ArrayList<>();
		try {
			List<ChannelSftp.LsEntry> lsEntryLst = channel.ls(remoteDir);
			for (LsEntry lsEntry : lsEntryLst) {
				FileData fd = new FileData();
				fd.parentPath = remoteDir;
				fd.isDirectory = lsEntry.getAttrs().isDir();
				fd.isFile = !lsEntry.getAttrs().isDir() && !lsEntry.getAttrs().isLink();
				fd.name = lsEntry.getFilename();
				fd.size = lsEntry.getAttrs().getSize();
				fd.modificationDate = new Date(1000L * lsEntry.getAttrs().getMTime());
				fileDataList.add(fd);
			}
		} catch (SftpException ex) { // If an error occurs, empty list will be returned
			LOG.warn("If an error occurs, empty list will be returned", ex);
		}
		return fileDataList;
	}

	/**
	 * Uploads a file from an input stream. If the file is already existing in the
	 * remote directory, it will be overwritten.
	 * 
	 * @param is                the source file, in form of an input stream.
	 * @param remoteDstFilePath the remote destination file name, relative to the
	 *                          current remote directory.
	 * @throws IOException
	 */
	public void uploadFile(InputStream is, String remoteDstFilePath) throws IOException {
		validateRemotePath(remoteDstFilePath);
		try {
			channel.put(is, remoteDstFilePath);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Uploads a file. If the file is already existing in the remote directory, it
	 * will be overwritten.
	 * 
	 * @param localSrcFilePath  the local source file name, absolute or relative to
	 *                          the current local directory.
	 * @param remoteDstFilePath the remote destination file name, absolute or
	 *                          relative to the current remote directory.
	 * @throws IOException
	 */
	public void uploadFile(String localSrcFilePath, String remoteDstFilePath) throws IOException {
		validateLocalPath(localSrcFilePath, baseLocalDir);
		validateRemotePath(remoteDstFilePath);
		try {
			channel.put(localSrcFilePath, remoteDstFilePath);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Downloads a file to an OutputStream. This uses OVERWRITE mode and no progress
	 * monitor.
	 * 
	 * @param remoteSrcFilePath the source file name, relative to the current remote
	 *                          directory
	 * @param oStream           the Output Stream
	 * @throws IOException
	 */
	public void downloadFile(String remoteSrcFilePath, OutputStream oStream) throws IOException {
		validateRemotePath(remoteSrcFilePath);
		try {
			channel.get(remoteSrcFilePath, oStream);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Downloads a file. If the file is already existing in the local directory, it
	 * will be overwritten.
	 * 
	 * @param remoteSrcFilePath the source file name, relative to the current remote
	 *                          directory.
	 * @param localDstFilePath  the destination file name, relative to the current
	 *                          local directory.
	 * @throws IOException
	 */
	public void downloadFile(String remoteSrcFilePath, String localDstFilePath) throws IOException {
		validateRemotePath(remoteSrcFilePath);
		validateLocalPath(localDstFilePath, baseLocalDir);
		try {
			channel.get(remoteSrcFilePath, localDstFilePath);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Removes one remote file or one remote directory and its content.
	 * 
	 * @param path
	 * @throws IOException
	 */
	public void deleteRemoteFileOrDir(String path) throws IOException {
		FileData fd = getFileData(path);
		if (fd != null) {
			if (fd.isFile) {
				try {
					channel.rm(path); // Remove file
				} catch (SftpException ex) {
					throw new IOException(ex);
				}
			} else if (fd.isDirectory) {
				List<FileData> fileAndFolderList = getFileDataList(path); // List source directory structure

				for (FileData item : fileAndFolderList) { // Iterate objects in the list to get file/folder names
					if (item.isFile) { // If it is a file (not a directory)
						try {
							channel.rm(path + "/" + item.name); // Remove file
						} catch (SftpException ex) {
							throw new IOException(ex);
						}
					} else if (!(CURRENT_DIR_REFERENCE.equals(item.name) || PARENT_DIR_REFERENCE.equals(item.name))) { // If it is a subdir
						try {
							// removing sub directory.
							channel.rmdir(path + PATHSEPARATOR + item.name);
						} catch (Exception ex) { // If subdir is not empty and error occurs,
							// Do deleteRemoteFileOrDir on this subdir to enter it and clear its contents
							deleteRemoteFileOrDir(path + "/" + item.name);
							LOG.warn(
									"If subdir is not empty and error occurs, Do deleteRemoteFileOrDir on this subdir to enter it and clear its contents",
									ex);
						}
					}
				}
				try {
					channel.rmdir(path); // delete the parent directory after empty
				} catch (SftpException ex) {
					throw new IOException(ex);
				}
			}
		}
	}

	/**
	 * Changes the current remote directory. This checks the existence and
	 * accessibility of the indicated directory, and changes the current remote
	 * directory setting.
	 * 
	 * @param path a directory path, absolute or relative to the current remote
	 *             path.
	 * @throws IOException
	 */
	public void changeDir(String path) throws IOException {
		try {
			channel.cd(path);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * This method is called recursively to Upload the local folder content to the
	 * SFTP server remote directory.
	 * 
	 * @param sourcePath
	 */
	public void uploadAllFiles(String sourcePath) {
		validateLocalPath(sourcePath, baseLocalDir);
		File sourceFile = new File(sourcePath);
		File[] files = sourceFile.listFiles();
		for (File f : files) {
			if (f.isFile() && !f.getName().startsWith(CURRENT_DIR_REFERENCE)) { // Copy if it is a file
				try {
					uploadFile(new FileInputStream(f), f.getName());
				} catch (IOException e) {
					LOG.error("Error occured while uploading", e);
				}
			} else {
				// Check if the directory is already existing
				FileData fileData = getFileData(f.getName());
				if (fileData != null) {
					LOG.debug("Directory exists IsDir=" + fileData.isDirectory);
				} else { // else create a directory
					LOG.debug("Creating dir " + f.getName());
					try {
						makeRemoteDir(f.getName());
					} catch (IOException e) {
						LOG.error("Error occured", e);
					}
				}
				try {
					changeDir(f.getName());
				} catch (IOException e1) {
					LOG.error("Error occured", e1);
				}

				uploadAllFiles(f.getAbsolutePath());

				try {
					changeDir(PARENT_DIR_REFERENCE);
				} catch (IOException e1) {
					LOG.error("Error occured", e1);
				}
			}
		}
	}

	/**
	 * This method is called recursively to download the remote folder content of
	 * the SFTP server.
	 * 
	 * @param sourcePath
	 * @param destinationPath
	 */
	public void downloadAllFiles(String sourcePath, String destinationPath) {
		validateLocalPath(destinationPath, baseLocalDir);
		List<FileData> fileAndFolderList = getFileDataList(sourcePath); // Let list of folder content
		// Iterate through list of folder content
		for (FileData item : fileAndFolderList) {
			if (!item.isDirectory) { // Check if it is a file (not a directory)
				try { // Download file from source (source filename, destination filename)
					downloadFile(sourcePath + PATHSEPARATOR + item.name, destinationPath + PATHSEPARATOR + item.name);
				} catch (IOException e) {
					LOG.error("Error occured while downloading", e);
				}
			} else if (!(CURRENT_DIR_REFERENCE.equals(item.name) || PARENT_DIR_REFERENCE.equals(item.name))) {
				// Empty folder copy
				new File(destinationPath + PATHSEPARATOR + item.name).mkdirs();
				// Enter found folder on server to read its contents and create locally
				downloadAllFiles(sourcePath + PATHSEPARATOR + item.name, destinationPath + PATHSEPARATOR + item.name);
			}
		}
	}

	/**
	 * 
	 * @param oldpath the old name of the file, relative to the current remote
	 *                directory.
	 * @param newpath the new name of the file, relative to the current remote
	 *                directory.
	 * @throws IOException
	 */
	public void rename(String oldpath, String newpath) throws IOException {
		validateRemotePath(oldpath);
		validateRemotePath(newpath);
		try {
			channel.rename(oldpath, newpath);
		} catch (SftpException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * Validates a local file path to prevent directory traversal attacks.
	 * Uses Java NIO Path API to resolve, normalize, and validate the path
	 * stays within the base directory. This prevents attacks like ../../../etc/passwd.
	 * 
	 * @param userPath the user-supplied local file path
	 * @param baseDir the base directory that userPath must not escape from
	 * @return the validated absolute path
	 * @throws SecurityException if path attempts to traverse outside baseDir
	 */
	private void validateLocalPath(String userPath, Path baseDir) throws SecurityException {
		if (!enforcePathRestrictions) {
			return;
		}
		Path resolved = baseDir.resolve(userPath).normalize().toAbsolutePath();
		if (!resolved.startsWith(baseDir.toAbsolutePath())) {
			throw new SecurityException("Path traversal blocked: " + userPath);
		}
	}

	/**
	 * Validates a remote SFTP path to prevent directory traversal attacks.
	 * Uses string-based pattern matching to reject traversal attempts.
	 * Note: Remote path security is primarily enforced at the SFTP server level
	 * (e.g., chroot, SSH key restrictions, SFTP server ACLs).
	 * This method provides client-side defense-in-depth by rejecting obvious traversal patterns.
	 * 
	 * @param remotePath the remote SFTP file path to validate
	 * @throws SecurityException if path contains traversal patterns or invalid sequences
	 */
	private void validateRemotePath(String remotePath) throws SecurityException {
		if (!enforcePathRestrictions) {
			return;
		}
		if (remotePath == null || remotePath.trim().isEmpty()) {
			throw new SecurityException("Security validation failed: remote path cannot be null or empty");
		}
		if (remotePath.startsWith(ABSOLUTE_PATH_PREFIX)) {
			throw new SecurityException("Security validation failed: absolute paths not allowed for remote transfers. Use relative paths instead: " + remotePath);
		}
		if (remotePath.contains(PARENT_DIR_REFERENCE) || remotePath.contains(CURRENT_DIR_WITH_SLASH) || remotePath.startsWith(CURRENT_DIR_REFERENCE)) {
			throw new SecurityException("Security validation failed: path traversal detected in remote path: " + remotePath);
		}
		if (remotePath.contains(WINDOWS_PATH_SEPARATOR)) {
			throw new SecurityException("Security validation failed: invalid path separator in remote path: " + remotePath);
		}
	}

	private static String getVar(String store, String var) {
		return Ivy.var().get(String.format("%s.%s.%s", SFTP_VAR, store, var));
	}
	
	public static String getClientHost(String store) {
		return getVar(store, HOST_VAR);
	}
	
	public static String getPort(String store) {
		return getVar(store, PORT_VAR);
	}
	
	public static String getUsername(String store) {
		return getVar(store, USERNAME_VAR);
	}

	/**
	 * File information class
	 *
	 */
	public static class FileData {
		boolean isFile;
		boolean isDirectory;
		String parentPath;
		String name;
		long size;
		/**
		 * The last modification date.
		 */
		Date modificationDate;

		/**
		 * @return the isFile
		 */
		public boolean isFile() {
			return isFile;
		}

		/**
		 * @param isFile the isFile to set
		 */
		public void setFile(boolean isFile) {
			this.isFile = isFile;
		}

		/**
		 * @return the isDirectory
		 */
		public boolean isDirectory() {
			return isDirectory;
		}

		/**
		 * @param isDirectory the isDirectory to set
		 */
		public void setDirectory(boolean isDirectory) {
			this.isDirectory = isDirectory;
		}

		/**
		 * @return the parentPath
		 */
		public String getParentPath() {
			return parentPath;
		}

		/**
		 * @param parentPath the parentPath to set
		 */
		public void setParentPath(String parentPath) {
			this.parentPath = parentPath;
		}

		/**
		 * @return the name
		 */
		public String getName() {
			return name;
		}

		/**
		 * @param name the name to set
		 */
		public void setName(String name) {
			this.name = name;
		}

		/**
		 * @return the size
		 */
		public long getSize() {
			return size;
		}

		/**
		 * @param size the size to set
		 */
		public void setSize(long size) {
			this.size = size;
		}

		/**
		 * @return the modificationDate
		 */
		public Date getModificationDate() {
			return modificationDate;
		}

		/**
		 * @param modificationDate the modificationDate to set
		 */
		public void setModificationDate(Date modificationDate) {
			this.modificationDate = modificationDate;
		}
	}
}
