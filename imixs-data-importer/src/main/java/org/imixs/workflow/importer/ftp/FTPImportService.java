/*  
 *  Imixs-Workflow 
 *  
 *  Copyright (C) 2001-2020 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Project: 
 *      https://www.imixs.org
 *      https://github.com/imixs/imixs-workflow
 *  
 *  Contributors:  
 *      Imixs Software Solutions GmbH - Project Management
 *      Ralph Soika - Software Developer
 */

package org.imixs.workflow.importer.ftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.imixs.workflow.importer.DocumentImportEvent;
import org.imixs.workflow.importer.DocumentImportService;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.MediaType;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * The FTPImportService reacts on DocumentImportEvent and processes an
 * FTP/FTPS/SFTP data source.
 * <p>
 * Supported types:
 * - type=FTP
 * - type=FTPS
 * - type=SFTP
 * <p>
 * FTP/FTPS uses Apache Commons Net.
 * SFTP uses SSHJ.
 *
 * @author
 *         Imixs Software Solutions GmbH
 *         Ralph Soika
 */
@Stateless
public class FTPImportService {

    private static final Logger logger = Logger.getLogger(FTPImportService.class.getName());

    @EJB
    WorkflowService workflowService;

    @EJB
    ModelService modelService;

    @EJB
    DocumentImportService documentImportService;

    /**
     * Reacts on a DocumentImportEvent and imports files from an FTP/FTPS/SFTP
     * server.
     */
    public void onEvent(@Observes DocumentImportEvent event) {

        // check if source is already completed
        if (event.getResult() == DocumentImportEvent.PROCESSING_COMPLETED) {
            logger.finest("...... import source already completed - no processing will be performed.");
            return;
        }

        String type = event.getSource().getItemValueString("type");
        if (!List.of("FTP", "FTPS", "SFTP").contains(type.toUpperCase())) {
            logger.finest("...... type '" + type + "' skipped.");
            return;
        }

        String server = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SERVER);
        String port = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_PORT);
        String user = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_USER);
        String password = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_PASSWORD);
        String path = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SELECTOR);

        // test insecure ssl
        Properties sourceOptions = documentImportService.getOptionsProperties(event.getSource());
        boolean insecureSSH = Boolean.parseBoolean(sourceOptions.getProperty("ftp.insecure", "false"));
        String ftpSubProtocol = sourceOptions.getProperty("ftp.subprotocol", "FTP");
        documentImportService.logMessage("├── 🗄️ FTP Import: server: " + ftpSubProtocol + "://" + server, event);

        if (server == null || server.isEmpty()) {
            documentImportService.logMessage("├── ⚠️ No server specified!", event);
            return;
        }

        if (port == null || port.isEmpty()) {
            port = type.equalsIgnoreCase("SFTP") ? "22" : "21";
        }

        try {
            if ("SFTP".equalsIgnoreCase(ftpSubProtocol)) {
                processSftp(event, server, port, user, password, path, insecureSSH);
            }
            if ("FTPS".equalsIgnoreCase(ftpSubProtocol)) {
                processFtps(event, server, port, user, password, path, true);
            }
            if ("FTP".equalsIgnoreCase(ftpSubProtocol)) {
                processFtps(event, server, port, user, password, path, false);
            }

            event.setResult(DocumentImportEvent.PROCESSING_COMPLETED);
        } catch (Exception e) {
            logger.severe("File transfer error: " + e.getMessage());
            documentImportService.logMessage("├── ⚠️ Transfer failed: " + e.getMessage(), event);
            event.setResult(DocumentImportEvent.PROCESSING_ERROR);
        }
    }

    /*
     * ==========================================================
     * FTP + FTPS Implementation (Apache Commons Net)
     * ==========================================================
     */

    private void processFtps(DocumentImportEvent event, String host, String port, String user, String pass, String path,
            boolean tls)
            throws Exception {

        FTPClient ftpClient = tls ? new FTPSClient("TLS", false) : new FTPClient();
        try {
            documentImportService.logMessage(
                    "│   ├── 🔐 Connecting to " + (tls ? "FTPS" : "FTP") + " server: " + host + "...",
                    event);
            ftpClient.connect(host, Integer.parseInt(port));
            if (!ftpClient.login(user, pass)) {
                throw new IOException("Login failed for user " + user);
            }

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlEncoding("UTF-8");

            boolean changed = ftpClient.changeWorkingDirectory(path);
            if (!changed) {
                throw new IOException("Unable to change working directory to: " + path);
            }

            FTPFile[] files = ftpClient.listFiles();
            if (files.length == 0) {
                documentImportService.logMessage("└── ✅ Directory empty: " + path, event);
                return;
            }
            documentImportService.logMessage("│   ├── connection successful!", event);
            for (FTPFile file : files) {
                if (!file.isFile())
                    continue;

                try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                    ftpClient.retrieveFile(file.getName(), os);
                    byte[] data = os.toByteArray();
                    if (data.length > 0) {
                        createWorkitem(event.getSource(), file.getName(), data);
                        try {
                            ftpClient.deleteFile(file.getName());
                        } catch (IOException ee) {
                            documentImportService.logMessage(
                                    "│   ├── ⚠️ Failed to remove file from source directory: " + ee.getMessage(),
                                    event);
                            throw ee;
                        }
                        documentImportService.logMessage("│   ├── ✅ Imported " + file.getName(), event);
                    } else {
                        documentImportService.logMessage("│   ├── ⚠️ Empty file ignored: " + file.getName(), event);
                    }
                }
            }
            documentImportService.logMessage("└── ✅ Completed.", event);
        } finally {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    /*
     * ==========================================================
     * SFTP Implementation (SSHJ)
     * ==========================================================
     */

    private void processSftp(DocumentImportEvent event, String host, String port, String user, String pass, String path,
            boolean insecureSSH) throws Exception {

        documentImportService.logMessage("│   ├── 🔐 Connecting to SFTP server: " + host + "...", event);
        SSHClient ssh = new SSHClient();
        if (insecureSSH) {
            // insecure mode - allow all hosts
            ssh.addHostKeyVerifier(new HostKeyVerifier() {
                @Override
                public boolean verify(String hostname, int port, java.security.PublicKey key) {
                    // ignore key validation
                    return true;
                }

                @Override
                public List<String> findExistingAlgorithms(String hostname, int port) {
                    return java.util.Collections.emptyList();
                }
            });
        } else {
            // secure mode
            ssh.loadKnownHosts();
        }

        ssh.connect(host, Integer.parseInt(port));
        ssh.authPassword(user, pass);

        try (SFTPClient sftp = ssh.newSFTPClient()) {
            List<RemoteResourceInfo> files = sftp.ls(path);
            documentImportService.logMessage("│   ├── connection successful!", event);

            if (files.isEmpty()) {
                documentImportService.logMessage("└── ✅ Directory empty: " + path, event);
                return;
            }

            for (RemoteResourceInfo file : files) {
                if (file.isDirectory())
                    continue;

                try (RemoteFile remoteFile = sftp.open(file.getPath());
                        InputStream is = remoteFile.new RemoteFileInputStream();
                        ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                    is.transferTo(os);
                    byte[] data = os.toByteArray();
                    if (data.length > 0) {
                        createWorkitem(event.getSource(), file.getName(), data);
                        try {
                            sftp.rm(file.getPath());
                        } catch (IOException ee) {
                            documentImportService.logMessage(
                                    "│   ├── ⚠️ Failed to remove file from source directory: " + ee.getMessage(),
                                    event);
                            throw ee;
                        }
                        documentImportService.logMessage("│   ├── ✅ Imported " + file.getName(), event);
                    } else {
                        documentImportService.logMessage("│   ├── ⚠️ Empty file ignored: " + file.getName(), event);
                    }
                }
            }

            documentImportService.logMessage("└── ✅ Completed.", event);
        } finally {
            ssh.close();
            ssh.disconnect();
        }
    }

    /*
     * ==========================================================
     * Workitem creation (unchanged)
     * ==========================================================
     */

    public ItemCollection createWorkitem(ItemCollection source, String fileName, byte[] rawData)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {

        ItemCollection workitem = new ItemCollection();
        workitem.model(source.getItemValueString(DocumentImportService.SOURCE_ITEM_MODELVERSION));
        workitem.task(source.getItemValueInteger(DocumentImportService.SOURCE_ITEM_TASK));
        workitem.event(source.getItemValueInteger(DocumentImportService.SOURCE_ITEM_EVENT));
        workitem.setWorkflowGroup(source.getItemValueString("workflowgroup"));

        // Add import Information
        workitem.setItemValue("document.import.type", source.getItemValue("type"));
        workitem.setItemValue("document.import.selector", source.getItemValue("selector"));
        workitem.setItemValue("document.import.options", source.getItemValue("options"));

        String contentType = MediaType.WILDCARD;
        if (fileName.toLowerCase().endsWith(".pdf")) {
            contentType = "application/pdf";
        }

        FileData fileData = new FileData(fileName, rawData, contentType, null);
        workitem.addFileData(fileData);

        workitem = workflowService.processWorkItemByNewTransaction(workitem);
        return workitem;
    }
}
