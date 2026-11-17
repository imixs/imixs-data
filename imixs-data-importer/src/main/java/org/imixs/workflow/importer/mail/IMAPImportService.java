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

package org.imixs.workflow.importer.mail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.angus.mail.imap.IMAPFolder;
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
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

/**
 * The IMAPImportService scans an IMAP account.
 * <p>
 * The service creates for each email in the INBOX (or a configured import
 * folder) a workitem based on the configuration defined by the import source.
 * <p>
 * After the message was imported successfully, the message is moved from the
 * import folder into an archive folder. If the archive folder does not exist,
 * the service creates the folder. The folder name can be configured by the
 * option 'archive.folder'. The default name is 'imixs-archive'.
 * <p>
 * The following options are supported:
 * <ul>
 * <li><b>detach.mode</b> - defines which file attachments are detached from
 * the email and added to the workitem:
 * <ul>
 * <li>PDF (default) - only PDF attachments are detached. The origin message
 * is always added to the workitem.</li>
 * <li>ALL - all attachments are detached. In addition the mail body is added
 * as a PDF or HTML file. The origin message is added only if
 * 'preserve.origin' is true.</li>
 * <li>NONE - no attachments are detached. The origin message is always added
 * to the workitem.</li>
 * </ul>
 * </li>
 * <li><b>detach.remove</b> - if set to 'true', all attachments which were
 * detached and added to the workitem are removed from the origin message
 * file stored in the workitem. This avoids duplicate content in the workitem.
 * The message on the IMAP server is not modified. Default is 'false'.</li>
 * <li><b>preserve.origin</b> - only evaluated in detach.mode=ALL. If set to
 * 'false', the origin message is not added to the workitem. Default is
 * 'true'.</li>
 * <li><b>gotenberg.service</b> - endpoint of a Gotenberg service used to
 * convert the mail body into a PDF file (detach.mode=ALL). If not defined or
 * not reachable, the mail body is added in HTML format.</li>
 * <li><b>subject.regex</b> - optional regular expression. Only messages with
 * a matching subject are imported.</li>
 * <li><b>archive.folder</b> - name of the IMAP archive folder.</li>
 * <li><b>imap.authenticator</b> - class name of the IMAPAuthenticator used to
 * open the mail store. Defaults to the IMAPBasicAuthenticator.</li>
 * <li><b>debug</b> - enables additional debug output.</li>
 * </ul>
 * <p>
 * To access a mail store the IMAPImportService injects IMAPAuthenticators.
 * These authenticator classes are used to authenticate against a mail store.
 *
 * @author rsoika
 * @version 1.1
 */
@Stateless
public class IMAPImportService {

    public static final String OPTION_DEBUG = "debug";
    public static final String OPTION_ARCHIVE_FOLDER = "archive.folder";
    public static final String OPTION_SUBJECT_REGEX = "subject.regex";
    public static final String OPTION_IMAP_AUTHENTICATOR = "imap.authenticator";

    public static final String OPTION_DETACH_MODE = "detach.mode";
    public static final String OPTION_DETACH_REMOVE = "detach.remove";
    public static final String OPTION_PRESERVE_ORIGIN = "preserve.origin";
    public static final String OPTION_GOTENBERG_SERVICE = "gotenberg.service";

    public static final String DETACH_MODE_PDF = "PDF";
    public static final String DETACH_MODE_ALL = "ALL";
    public static final String DETACH_MODE_NONE = "NONE";
    public static final String ARCHIVE_DEFAULT_NAME = "imixs-archive";

    public static final String DEFAULT_NO_SUBJECT = "no subject";

    private static Logger logger = Logger.getLogger(IMAPImportService.class.getName());

    @EJB
    WorkflowService workflowService;

    @EJB
    ModelService modelService;

    @EJB
    DocumentImportService documentImportService;

    @EJB
    MailMessageService mailMessageService;

    @Inject
    @Any
    protected Instance<IMAPAuthenticator> imapAuthenticators;

    /**
     * This method reacts on a CDI ImportEvent and reads documents from an IMAP
     * server.
     * <p>
     * Depending on the option 'detach.mode' the method detaches file attachments
     * from the email. With the option 'detach.remove' the detached files are
     * removed from the origin message stored in the workitem.
     */
    public void onEvent(@Observes DocumentImportEvent event) {
        IMAPFolder inboxFolder = null;
        // check if source is already completed
        if (event.getResult() == DocumentImportEvent.PROCESSING_COMPLETED) {
            logger.finest("...... import source already completed - no processing will be performed.");
            return;
        }

        if (!"IMAP".equalsIgnoreCase(event.getSource().getItemValueString("type"))) {
            // ignore data source
            logger.finest("...... type '" + event.getSource().getItemValueString("type") + "' skipped.");
            return;
        }

        String imapServer = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SERVER);
        String importFolderName = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SELECTOR);

        documentImportService.logMessage("├── 📬 connecting IMAP server: " + imapServer + " /INBOX/" + importFolderName,
                event);

        Properties sourceOptions = documentImportService.getOptionsProperties(event.getSource());
        Pattern subjectPattern = null;
        String subjectRegex = sourceOptions.getProperty(OPTION_SUBJECT_REGEX, "");
        // do we have filter options providing a subject regex?
        if (subjectRegex != null && !subjectRegex.trim().isEmpty()) {
            try {
                subjectPattern = Pattern.compile(subjectRegex);
                documentImportService.logMessage("│   ├── subject.regex = " + subjectRegex, event);
            } catch (PatternSyntaxException e) {
                documentImportService.logMessage("│   ├── ⚠️ Error - invalid subject regex: " + e.getMessage(), event);
                return;
            }
        }

        try {
            Store store = null;
            // depending on the option "imap.authenticator" we use the corresponding
            // IMAPAuthenticator to open the mail store
            IMAPAuthenticator imapAuthenticator = null;
            String authenticatorClass = sourceOptions.getProperty(OPTION_IMAP_AUTHENTICATOR,
                    "org.imixs.workflow.importer.mail.IMAPBasicAuthenticator");
            for (IMAPAuthenticator _imapAuthenticator : this.imapAuthenticators) {
                // find the matching authenticator....
                if (authenticatorClass.equals(_imapAuthenticator.getClass().getName())) {
                    documentImportService.logMessage("│   ├── IMAPAuthenticator = " + authenticatorClass, event);
                    imapAuthenticator = _imapAuthenticator;
                    break;
                }
            }
            store = imapAuthenticator.openMessageStore(event.getSource(), sourceOptions);
            if (store == null) {
                documentImportService.logMessage("│   ├── ⚠️ failed to connect to IMAP server.",
                        event);
            }
            documentImportService.logMessage("│   ├── ☑️ connection to IMAP server successful",
                    event);

            // first we need to open the INBOX...
            inboxFolder = (IMAPFolder) store.getFolder("INBOX");
            // next open the import folder
            IMAPFolder importFolder = null;
            // do we have a custom import folder?
            if (!importFolderName.isEmpty()) {
                // in case a folder is specified this folder needs to be opened from the
                // INBOX default folder!
                importFolder = (IMAPFolder) inboxFolder.getFolder(importFolderName);
            } else {
                // if not specified it is always the INBOX
                importFolder = inboxFolder;
            }

            importFolder.open(Folder.READ_WRITE);

            // Depending on the option 'detach.mode' attachments will be added to the new
            // workitem.
            String detachOption = sourceOptions.getProperty(OPTION_DETACH_MODE, DETACH_MODE_PDF);
            documentImportService.logMessage("│   ├── detach.mode = " + detachOption, event);
            // Depending on the option 'detach.remove' detached attachments will be removed
            // from the origin message stored in the workitem.
            boolean detachRemove = Boolean
                    .parseBoolean(sourceOptions.getProperty(OPTION_DETACH_REMOVE, "false"));
            documentImportService.logMessage("│   ├── detach.remove = " + detachRemove, event);

            // open archive folder...
            IMAPFolder archiveFolder = openImapArchive(store, inboxFolder, sourceOptions, event);

            // fetch all messages from the import folder...
            Message[] messages = importFolder.getMessages();
            documentImportService.logMessage("│   ├── " + messages.length + " messages found", event);
            int successCount = 0;
            int errorCount = 0;
            for (Message message : messages) {
                try {
                    boolean result = processMessageObject(message, subjectPattern, event, sourceOptions, importFolder,
                            archiveFolder);
                    if (result) {
                        successCount++;
                    } else {
                        errorCount++;
                    }
                } catch (MessagingException me) {
                    documentImportService.logMessage("│   ├── ⚠️ failed to read message: " +
                            me.getMessage(), event);
                    continue;
                }
            }

            documentImportService.logMessage(
                    "└── ✅ Completed - " + successCount + " messages imported, " + errorCount + " errors",
                    event);

        } catch (AccessDeniedException | ProcessingErrorException | PluginException | ModelException e) {
            documentImportService.logMessage("IMAP import failed: " + e.getMessage(), event);
            event.setResult(DocumentImportEvent.PROCESSING_ERROR);
            return;
        } catch (MessagingException | IOException e) {
            documentImportService.logMessage("IMAP import failed: " + e.getMessage(), event);
            event.setResult(DocumentImportEvent.PROCESSING_ERROR);
            return;
        }

    }

    /**
     * This method processes a single IMAP message object. The method creates a
     * workitem and moves the message into the archive folder.
     * <p>
     * Depending on the option 'detach.mode' file attachments are detached from
     * the message and added to the workitem. If the option 'detach.remove' is set
     * to 'true', the detached attachments are removed from the origin message
     * file stored in the workitem. Because IMAP messages are read-only, this is
     * done on a local copy of the message. The message on the IMAP server is not
     * modified.
     * <p>
     * The method returns true if the message was successfully imported.
     *
     * @param message        - the IMAP message to be processed
     * @param subjectPattern - optional subject filter (can be null)
     * @param event          - the current import event
     * @param sourceOptions  - the import source options
     * @param importFolder   - the IMAP folder the message is read from
     * @param archiveFolder  - the IMAP folder the message is moved to
     * @return true if the message was imported, false if the message was
     *         skipped
     * @throws MessagingException
     * @throws ModelException
     * @throws PluginException
     * @throws ProcessingErrorException
     * @throws AccessDeniedException
     * @throws IOException
     */
    private boolean processMessageObject(Message message,
            Pattern subjectPattern,
            DocumentImportEvent event,
            Properties sourceOptions,
            IMAPFolder importFolder,
            IMAPFolder archiveFolder) throws MessagingException, AccessDeniedException, ProcessingErrorException,
            PluginException, ModelException, IOException {

        // Note: Boolean.getBoolean() would read a system property - parseBoolean()
        // evaluates the option value
        boolean debug = Boolean.parseBoolean(sourceOptions.getProperty(OPTION_DEBUG, "false"));
        String detachOption = sourceOptions.getProperty(OPTION_DETACH_MODE, DETACH_MODE_PDF);
        boolean detachRemove = Boolean.parseBoolean(sourceOptions.getProperty(OPTION_DETACH_REMOVE, "false"));
        boolean preserveOrigin = "true"
                .equalsIgnoreCase(sourceOptions.getProperty(OPTION_PRESERVE_ORIGIN, "true"));

        Address[] fromAddress = null;
        String subject = null;
        // if we can not open the mail a MessagingException is thrown - we normally skip
        // this mail in such a case - see Issue #175
        fromAddress = message.getFrom();
        subject = message.getSubject();

        if (subject == null || subject.trim().isEmpty()) {
            subject = DEFAULT_NO_SUBJECT;
        }
        Date sent = message.getSentDate();
        logger.fine("......received mail from: " + fromAddress[0].toString());
        logger.fine("......subject = " + subject);
        // do we have a subject regular expression provided by the options?
        if (subjectPattern != null) {
            if (DEFAULT_NO_SUBJECT.equals(subject)) {
                // skip this mail
                return false;
            }
            // test if subject matches?
            Matcher subjectMatcher = subjectPattern.matcher(subject);
            if (!subjectMatcher.find()) {
                // skip this mail
                return false;
            }
        } else {
            logger.finest("...no regex pattern mail will be processed...");
        }

        ItemCollection workitem = createWorkitem(event.getSource());
        // store message attributes
        if (fromAddress[0] instanceof InternetAddress) {
            InternetAddress internetAddr = (InternetAddress) fromAddress[0];
            workitem.setItemValue("mail.from", internetAddr.getAddress());
            workitem.setItemValue("mail.from.personal", internetAddr.getPersonal());

        } else {
            workitem.setItemValue("mail.from", fromAddress[0].toString());
        }
        workitem.setItemValue("mail.subject", subject);
        workitem.setItemValue("mail.sent", sent);

        // Will the origin message be added to the workitem?
        // - DETACH_MODE_PDF / DETACH_MODE_NONE: always
        // - DETACH_MODE_ALL: only if preserve.origin=true
        boolean attachOrigin = !DETACH_MODE_ALL.equalsIgnoreCase(detachOption) || preserveOrigin;

        // In case detached files should be removed from the origin message, we need a
        // modifiable local copy, because IMAP messages are read-only. A copy is only
        // needed if attachments are detached and the origin message is stored.
        MimeMessage messageCopy = null;
        if (detachRemove && attachOrigin && !DETACH_MODE_NONE.equalsIgnoreCase(detachOption)
                && message instanceof MimeMessage) {
            messageCopy = createMessageCopy((MimeMessage) message);
        }
        // the origin message to be stored in the workitem - either the cleaned copy or
        // the unchanged IMAP message
        Message originMessage = (messageCopy != null) ? messageCopy : message;

        // Detached files are collected first and added to the workitem later. This
        // allows us to add the origin message as the first file to the workitem.
        List<FileData> detachedFiles = new ArrayList<>();

        if (!DETACH_MODE_NONE.equalsIgnoreCase(detachOption)) {
            // scan for attachments....
            // we need to test if the content is a multipart or if it is a plain text mail
            Object contentObject = originMessage.getContent();
            if (contentObject instanceof Multipart) {
                // here we are safe to cast the content to Multipart
                Multipart multiPart = (Multipart) contentObject;
                // collect all parts added to the workitem, so they can be removed afterwards
                List<BodyPart> detachedParts = new ArrayList<>();
                for (int i = 0; i < multiPart.getCount(); i++) {

                    BodyPart bodyPart = multiPart.getBodyPart(i);
                    if (bodyPart instanceof MimeBodyPart) {
                        MimeBodyPart mimeBodyPart = (MimeBodyPart) bodyPart;
                        if (Part.ATTACHMENT.equalsIgnoreCase(mimeBodyPart.getDisposition())) {

                            String fileName = mimeBodyPart.getFileName();
                            if (fileName == null) {
                                logger.warning("...skip detaching file, because of missing filename");
                                continue; // skip this attachment
                            }

                            // decode filename (issue #202)
                            fileName = MimeUtility.decodeText(fileName);

                            // detach only PDF files?
                            if (DETACH_MODE_PDF.equalsIgnoreCase(detachOption)) {
                                if (!fileName.toLowerCase().endsWith(".pdf")) {
                                    continue; // skip this attachment
                                }
                            }
                            // add this attachment to the workitem
                            byte[] content;
                            try (InputStream input = mimeBodyPart.getInputStream()) {
                                content = IMAPImportHelper.readAllBytes(input);
                            }
                            String contentType = mimeBodyPart.getContentType();
                            contentType = IMAPImportHelper.fixContentType(contentType, fileName, debug);
                            FileData fileData = new FileData(fileName, content, contentType, null);
                            // collect the file - it is added to the workitem after the origin message
                            detachedFiles.add(fileData);
                            // remember this part - it may be removed from the origin message
                            detachedParts.add(bodyPart);
                        }
                    }
                }

                // remove all detached files from the message copy to avoid duplicate content
                // in the workitem
                if (messageCopy != null && !detachedParts.isEmpty()) {
                    for (BodyPart part : detachedParts) {
                        multiPart.removeBodyPart(part);
                    }
                    messageCopy.setContent(multiPart);
                    messageCopy.saveChanges();
                    logger.fine("......removed " + detachedParts.size()
                            + " detached file(s) from origin message");
                }
            } else {
                // email content is of type text/plain - so we can not extract data...
            }
        }

        // 1. attach the origin message (optionally without the detached files) as the
        // first file of the workitem
        if (attachOrigin) {
            mailMessageService.attachMessage(originMessage, workitem);
        }

        // 2. in DETACH_MODE_ALL we attach the mail body as a PDF or HTML file
        // Note: the conversion is based on the unchanged IMAP message
        if (DETACH_MODE_ALL.equalsIgnoreCase(detachOption)) {
            // do we have a gotenberg service?
            String gotenbergService = sourceOptions.getProperty(OPTION_GOTENBERG_SERVICE);
            if (gotenbergService != null && !gotenbergService.isEmpty()) {
                try {
                    logger.info("using gotenbergservice: " + gotenbergService);
                    mailMessageService.attachPDFMessage(message, workitem, gotenbergService);
                } catch (IOException eio) {
                    // there was a problem to call the gotenberg service - fallback to HTML
                    documentImportService
                            .logMessage(
                                    "│   ├── connecting to gotenberg service '" + gotenbergService + "' failed: "
                                            + eio.getMessage() + " message will be added in HTML format!",
                                    event);
                    // attach email as HTML....
                    mailMessageService.attachHTMLMessage(message, workitem);
                }
            } else {
                // attach the email as HTML....
                mailMessageService.attachHTMLMessage(message, workitem);
            }
        }

        // 3. add all detached files to the workitem
        for (FileData fileData : detachedFiles) {
            workitem.addFileData(fileData);
        }

        // finally process the workitem
        workitem = workflowService.processWorkItemByNewTransaction(workitem);

        // move the unchanged IMAP message into the archive folder
        Message[] messageList = { message };
        importFolder.moveMessages(messageList, archiveFolder);
        return true;
    }

    /**
     * Creates a modifiable local copy of a (read-only) IMAP message.
     * <p>
     * The copy constructor of MimeMessage reads the complete message content
     * from the source. Changes on the copy do not affect the message on the IMAP
     * server.
     * <p>
     * The original Message-ID is preserved, because saveChanges() would
     * otherwise generate a new one.
     *
     * @param source - the IMAP message
     * @return a modifiable copy of the message
     * @throws MessagingException
     */
    private MimeMessage createMessageCopy(MimeMessage source) throws MessagingException {
        return new MimeMessage(source) {
            @Override
            protected void updateMessageID() throws MessagingException {
                // keep the Message-ID copied from the source message
            }
        };
    }

    /**
     * This method opens the IMAP archive folder. If the folder does not exist, the
     * method creates the folder. The folder name can be configured by the property
     * ARCHIVE_FOLDER. The default name is 'imixs-archive'
     *
     * @param store         - the mail store
     * @param inbox         - the INBOX folder
     * @param sourceOptions - the import source options
     * @param event         - the current import event
     * @return the opened archive folder
     * @throws MessagingException
     */
    private IMAPFolder openImapArchive(Store store, IMAPFolder inbox, Properties sourceOptions,
            DocumentImportEvent event) throws MessagingException {
        // open archive folder
        String imapArchiveFolder = sourceOptions.getProperty(OPTION_ARCHIVE_FOLDER, ARCHIVE_DEFAULT_NAME);
        documentImportService.logMessage("│   ├── archive.folder = " + imapArchiveFolder, event);
        IMAPFolder archive = (IMAPFolder) inbox.getFolder(imapArchiveFolder);
        // if archive folder did not exist create it...
        if (archive.exists() == false) {
            logger.info("...creating folder '" + imapArchiveFolder + "'");
            boolean isCreated = archive.create(Folder.HOLDS_MESSAGES);
            if (isCreated) {
                logger.info("...folder successful created");
            } else {
                documentImportService.logMessage("│   ├── ⚠️ Error - failed to create new archive folder!", event);
            }
        }
        archive.open(Folder.READ_WRITE);
        return archive;
    }

    /**
     * Creates a new workitem based on the given import source. The workitem is
     * not yet processed.
     *
     * @param source - the import source
     * @return a new workitem
     * @throws ModelException
     * @throws PluginException
     * @throws ProcessingErrorException
     * @throws AccessDeniedException
     */
    public ItemCollection createWorkitem(ItemCollection source)
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

        return workitem;
    }

}