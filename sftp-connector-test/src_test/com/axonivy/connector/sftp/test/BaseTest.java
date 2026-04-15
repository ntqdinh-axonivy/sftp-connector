package com.axonivy.connector.sftp.test;

import ch.ivyteam.ivy.bpm.engine.client.element.BpmProcess;
import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.environment.IvyTest;

@IvyProcessTest(enableWebServer = true)
public class BaseTest {
	protected static final String TEST_SFTP_SERVER_NAME = "dummy";
	protected static final String TEST_SFTP_SSH_SERVER_NAME = "dummy_ssh";
	
	protected static final BpmProcess TEST_HELPER_PROCESS = BpmProcess.path("Sftp/SftpHelper");
	protected static final BpmProcess TEST_UPLOAD_FILE_PROCESS = BpmProcess.path("Sftp/SftpUploadFile");
	protected static final BpmProcess TEST_DOWNLOAD_FILE_PROCESS = BpmProcess.path("Sftp/SftpDownloadFile");

	protected static final String PREFIX = "com.axonivy.connector.sftp.server";
	protected static final String TEST_FILE_NAME = "market_market_connector_sftp.pdf";
	protected static final long TEST_FILE_SIZE = 207569L;
	
	protected static void setVarForSFTPName(String sftpServerName, String username, String auth, String password,
			String sshKeyFilePath, String sshpassphrase, AppFixture fixture) {
		setVarForSFTPName(sftpServerName, username, auth, password, sshKeyFilePath, sshpassphrase, "false", "", fixture);
	}
	
	protected static void setVarForSFTPName(String sftpServerName, String username, String auth, String password,
			String sshKeyFilePath, String sshpassphrase, String enforcePathRestrictions, String baseLocalDir, AppFixture fixture) {
		setVar(sftpServerName, "host", "localhost", fixture);
		setVar(sftpServerName, "username", username, fixture);
		setVar(sftpServerName, "port", "22", fixture);
		setVar(sftpServerName, "auth", auth, fixture);
		setVar(sftpServerName, "password", password, fixture);
		setVar(sftpServerName, "sshkeyFilePath", sshKeyFilePath, fixture);
		setVar(sftpServerName, "sshPassphraseSecret", sshpassphrase, fixture);
		setVar(sftpServerName, "enforcePathRestrictions", enforcePathRestrictions, fixture);
		setVar(sftpServerName, "baseLocalDir", baseLocalDir, fixture);
	}

	protected static void setVar(String sftpServerName, String var, String value, AppFixture fixture) {
		fixture.var(String.format("%s.%s.%s", PREFIX, sftpServerName, var), value);
	}
	
}
