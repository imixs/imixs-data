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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.index.UpdateService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.imixs.workflow.exceptions.QueryException;
import org.imixs.workflow.importer.DocumentImportEvent;
import org.imixs.workflow.importer.DocumentImportService;

import jakarta.ejb.EJBException;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The CSVImportService reacts on DocumentImportEvent and imports a CSV file
 * form a FTP data source or the local file system.
 * <p>
 * The FTP implementation is based on org.apache.commons.net.ftp
 * <p>
 * The CSV import can be customized by various options
 * 
 * @author rsoika
 *
 */
@Named
public class CSVImportService {

    public static final String DATA_ERROR = "DATA_ERROR";
    public static final String CONFIG_ERROR = "CONFIG_ERROR";
    public static final String IMPORT_ERROR = "IMPORT_ERROR";

    private static Logger logger = Logger.getLogger(CSVImportService.class.getName());

    @Inject
    UpdateService indexUpdateService;

    @Inject
    DocumentService documentService;

    @Inject
    WorkflowService workflowService;

    @Inject
    DocumentImportService documentImportService;

    /**
     * This method reacts on a CDI ImportEvent and imports the data of a CSV file
     * form a ftp server.
     * 
     * 
     */
    public void onEvent(@Observes DocumentImportEvent event) {
        String encoding;
        String type;
        String keyField;

        // check if source is already completed
        if (event.getResult() == DocumentImportEvent.PROCESSING_COMPLETED) {
            logger.finest("...... import source already completed - no processing will be performed.");
            return;
        }

        if (!"CSV".equalsIgnoreCase(event.getSource().getItemValueString("type"))) {
            // ignore data source
            logger.finest("...... type '" + event.getSource().getItemValueString("type") + "' skipped.");
            return;
        }
        try {
            String ftpServer = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SERVER);

            String csvSelector = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_SELECTOR);
            if (!csvSelector.startsWith("/") && !csvSelector.startsWith("./")) {
                csvSelector = "/" + csvSelector;
            }
            if (csvSelector == null || csvSelector.isEmpty() || !csvSelector.toLowerCase().endsWith(".csv")) {
                documentImportService.logMessage("...invalid selector - .csv file path missing - " + csvSelector,
                        event);
            }
            documentImportService.logMessage("├── csv import: " + csvSelector, event);

            Properties sourceOptions = documentImportService.getOptionsProperties(event.getSource());

            // resolve type...
            type = sourceOptions.getProperty("type");
            if (type == null || type.isEmpty()) {
                // if no type is set we default to 'workitem'
                documentImportService.logMessage("│   ├── Missing property 'type' - set default 'workitem'", event);
                type = "workitem";
            }
            // if type is 'workitem' workflow configuration is mandatory
            if ("workitem".equals(type)) {
                String modelVersion = event.getSource()
                        .getItemValueString(DocumentImportService.SOURCE_ITEM_MODELVERSION);
                String workflowGroup = event.getSource()
                        .getItemValueString(DocumentImportService.SOURCE_ITEM_WORKFLOWGROUP);
                int taskID = event.getSource().getItemValueInteger(DocumentImportService.SOURCE_ITEM_TASK);
                int eventID = event.getSource().getItemValueInteger(DocumentImportService.SOURCE_ITEM_EVENT);
                if (modelVersion.isBlank() && workflowGroup.isBlank()) {
                    String error = "either $workflowgroup or $modelversion must be set! ";
                    documentImportService.logMessage("│   ├── ⚠️ Missing configuration: " + error, event);
                    throw new PluginException(this.getClass().getName(), CONFIG_ERROR,
                            error);
                }
                if (taskID == 0 || eventID == 0) {
                    String error = "$taskID and $eventID must be set! ";
                    documentImportService.logMessage("│   ├── ⚠️ Missing configuration: " + error, event);
                    throw new PluginException(this.getClass().getName(), CONFIG_ERROR,
                            error);
                }
            }

            // get key ..
            keyField = sourceOptions.getProperty("key");
            if (keyField == null || keyField.isEmpty()) {
                throw new PluginException(this.getClass().getName(), CONFIG_ERROR,
                        "Missing property 'key' to import entities");
            } else {
                // _ prafix
                if (!keyField.startsWith("_")) {
                    keyField = "_" + keyField;
                }
            }

            // get encoding..
            encoding = sourceOptions.getProperty("encoding");
            if (encoding == null || encoding.isEmpty()) {
                encoding = "UTF-8";
            }

            documentImportService.logMessage("│   ├── encoding=" + encoding, event);
            FileData fileData = null;
            // if ftp server is defined, load from server...
            if (!ftpServer.isEmpty()) {
                fileData = readFileDataFromFTP(ftpServer, csvSelector, encoding, event);

            } else {
                // default try import from local path
                Path path = Paths.get(csvSelector);
                String fileName = path.getFileName().toString();
                byte[] fileContent = Files.readAllBytes(Paths.get(csvSelector));
                fileData = new FileData(fileName, fileContent, null, null);
            }

            if (fileData != null) {
                documentImportService
                        .logMessage("│   ├── ⚙️ file '" + fileData.getName() + "' processing ▷ "
                                + fileData.getContent().length + " bytes", event);

                String lastChecksum = event.getSource().getItemValueString("csv.checksum");
                // create checksum....

                String newChecksum = fileData.generateMD5();
                documentImportService.logMessage("│   ├── checksum=" + newChecksum, event);
                if (lastChecksum.isEmpty() || !lastChecksum.equals(newChecksum)) {
                    // read data....
                    InputStream inputStream = new ByteArrayInputStream(fileData.getContent());
                    String log = importData(inputStream, encoding, type, keyField, event);
                    // update checksum
                    event.getSource().setItemValue("csv.checksum", newChecksum);
                    documentImportService.logMessage(log, event);
                    documentImportService.logMessage("├── ✅ file import completed successful.", event);
                } else {
                    documentImportService.logMessage("├── ✅ no data changes since last import.", event);
                }
            } else {
                documentImportService.logMessage(
                        "...Warning - invalid file content '" + fileData.getName() + "' - file will be deleted!",
                        event);
            }
        } catch (PluginException | NoSuchAlgorithmException | IOException e) {
            logger.severe("Data Error: " + e.getMessage());
            e.printStackTrace();
            documentImportService.logMessage("├── ⚠️ file import failed: " + e.getMessage(), event);
            event.setResult(DocumentImportEvent.PROCESSING_ERROR);
            return;
        }
        // flush index...
        indexUpdateService.updateIndex();
        // completed
        event.setResult(DocumentImportEvent.PROCESSING_COMPLETED);
    }

    /**
     * Helper method to import the data source from a FTP server
     * 
     * The method returns a byte array with the file raw data.
     */
    protected FileData readFileDataFromFTP(String ftpServer, String csvSelector, String encoding,
            DocumentImportEvent event) {
        FTPClient ftpClient = null;
        FileData fileData = null;

        try {
            String ftpPort = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_PORT);
            String ftpUser = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_USER);
            String ftpPassword = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_PASSWORD);
            // if no server is given we exit
            if (ftpServer.isEmpty()) {
                logger.warning("...... no server specified!");
                return null;
            }

            if (ftpPort.isEmpty()) {
                // set default port
                ftpPort = "21";
            }

            logger.finest("......read directories ...");

            documentImportService.logMessage("│   ├── connecting to FTP server: " + ftpServer, event);

            // TLS
            ftpClient = new FTPSClient("TLS", false);
            ftpClient.setControlEncoding(encoding);
            ftpClient.connect(ftpServer, Integer.parseInt(ftpPort));
            if (ftpClient.login(ftpUser, ftpPassword) == false) {
                documentImportService.logMessage("FTP file transfer failed: login failed!", event);
                event.setResult(DocumentImportEvent.PROCESSING_ERROR);
                return null;
            }

            ftpClient.enterLocalPassiveMode();
            logger.finest("...... FileType=" + FTP.BINARY_FILE_TYPE);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlEncoding(encoding);

            // try to enter the working directory....
            File file = new File(csvSelector);
            String csvFTPPath = file.getParent();
            String csvFilename = file.getName();

            boolean bWorkingDir = ftpClient.changeWorkingDirectory(csvFTPPath);
            if (bWorkingDir == true) {

                documentImportService.logMessage("│   ├── working directory: " + ftpClient.printWorkingDirectory(),
                        event);

                logger.info("import file " + csvFilename + "...");
                // String fullFileName = ftpPath + "/" + file.getName();
                try (ByteArrayOutputStream is = new ByteArrayOutputStream();) {

                    // because time stamps are not provided by all ftp servers and always in same
                    // format we store the checksum of the file to test if the file has changed
                    // since the last import
                    ftpClient.retrieveFile(csvFilename, is);
                    byte[] rawData = is.toByteArray();

                    // Close Connection now
                    try {
                        logger.info("...document content read, closing FTP client.");
                        ftpClient.logout();
                        ftpClient.disconnect();
                    } catch (IOException e) {
                        documentImportService.logMessage(
                                "│   ├── FTP error - failed to close connection after reading CSV File: "
                                        + e.getMessage(),
                                event);
                        // we still can continue as we should already have read the file content...
                    }

                    fileData = new FileData(file.getName(), rawData, null, null);

                } catch (AccessDeniedException | ProcessingErrorException e) {

                    documentImportService.logMessage("...FTP import failed: " + e.getMessage(), event);
                    event.setResult(DocumentImportEvent.PROCESSING_ERROR);
                    return null;
                }

            } else {
                documentImportService.logMessage("│   ├── failed to change into working directory: " + csvFTPPath,
                        event);
            }

        } catch (IOException e) {
            logger.severe("FTP I/O Error: " + e.getMessage());
            if (ftpClient.isConnected()) {
                int r = ftpClient.getReplyCode();
                logger.severe("FTP ReplyCode=" + r);
                documentImportService.logMessage(
                        "│   ├── FTP file transfer failed (replyCode=" + r + ") : " + e.getMessage(),
                        event);
            }
            event.setResult(DocumentImportEvent.PROCESSING_ERROR);
            return null;

        } finally {
            // do logout if still connected....
            try {
                if (ftpClient.isConnected()) {
                    logger.warning("FTP Client is till connected, closing.....");
                    ftpClient.logout();
                    ftpClient.disconnect();
                }
            } catch (IOException e) {
                documentImportService.logMessage("│   ├── FTP file transfer failed: " + e.getMessage(), event);
                event.setResult(DocumentImportEvent.PROCESSING_ERROR);
                return null;
            }
        }

        return fileData;

    }

    /**
     * This method imports all entities from a csv file. The file must have one
     * header line.
     * <p>
     * All existing entries not listed in the current file will be removed.
     * <p>
     * Each imported document will have a unique key in the item 'name' to be used
     * to verify if the entry already exists.
     * <p>
     * Optional a workflow task/event can be defined in the source configuration. In
     * this case the entity will be processed. Otherwise it will be saved only.
     * <p>
     * The method returns a log . If an error occurs a plugin exception is thrown
     * <p>
     * Method: the implementation first reads all existing ItemCollection objects
     * from the database and stores the hash into a local array. Next the method
     * reads all entries from the CSV file and stores also the hash objects in a
     * array. Next both arrays will be compared. If the compare results in not
     * equal, the entry will be updated/imported. If the entry does no longer exist,
     * the entry will be removed from the database. This operation runs in-memory
     * and reduces database access.
     * 
     * 
     * 
     * @return ErrorMessage or empty String
     * @throws PluginException
     */
    public String importData(InputStream inputStream, String encoding, String type, String keyField,
            DocumentImportEvent event) throws PluginException {

        logger.fine("...starting csv data import...");
        String log = "";
        int line = 0;
        String dataLine = null;
        List<String> csvIndexCache = new ArrayList();
        Map<String, RecordComparator> databaseCache = null;
        int workitemsTotal = 0;
        int workitemsImported = 0;
        int workitemsUpdated = 0;
        int workitemsDeleted = 0;
        int workitemsFailed = 0;
        int blockSize = 0;

        // read Workflow options (optional)
        String modelVersion = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_MODELVERSION);
        String workflowGroup = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_WORKFLOWGROUP);
        int taskID = event.getSource().getItemValueInteger(DocumentImportService.SOURCE_ITEM_TASK);
        int eventID = event.getSource().getItemValueInteger(DocumentImportService.SOURCE_ITEM_EVENT);
        String csvFileName = event.getSource().getItemValueString("selector");
        if (encoding == null) {
            encoding = "UTF-8";
        }

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, encoding));

            // read first line containing the object type
            String header = in.readLine();
            if (!header.contains(";")) {
                throw new PluginException(this.getClass().getName(), IMPORT_ERROR,
                        "File Format not supported, fields must be separated by ';' ");
            }
            // String[] header1List = header1.split(";(?=([^\"]*\"[^\"]*\")*[^\"]*$)", 99);
            // header1List = normalizeValueList(header1List);
            List<String> fields = parseFieldList(header);

            if (fields == null || fields.size() == 0) {
                throw new PluginException(this.getClass().getName(), IMPORT_ERROR,
                        "File Format not supported, 1st line must contain the item names.");
            }
            if (type == null || type.isEmpty()) {
                throw new PluginException(this.getClass().getName(), IMPORT_ERROR, "Missing type to import entities");
            }
            databaseCache = readDocumentsFromDatabase(fields, type, event);

            logger.info("...object type=" + type);
            logger.info("...key field=" + keyField);
            line++;

            // read content....
            while ((dataLine = in.readLine()) != null) {
                blockSize++;
                line++;
                workitemsTotal++;
                ItemCollection entity = readEntity(dataLine, fields, type, keyField);
                if (entity == null) {
                    logger.warning("...Incorrect data line: " + dataLine);
                    continue;
                }

                // store id into cache
                String keyItemValue = entity.getItemValueString("name");
                if (keyItemValue.isBlank()) {
                    logger.warning("KeyField '" + keyField + "' is empty - line:" + line);
                    continue;
                }
                if (csvIndexCache.contains(keyItemValue)) {
                    logger.warning("...WARNING duplicate entry found: " + keyField + "=" + keyItemValue);
                    documentImportService
                            .logMessage("│   ├── ⚠️ duplicate entry found: " + keyField + "=" + keyItemValue, event);
                    continue;
                }
                csvIndexCache.add(keyItemValue);
                // Add import Information
                entity.setItemValue("document.import.type", event.getSource().getItemValue("type"));
                entity.setItemValue("document.import.selector", event.getSource().getItemValue("selector"));
                entity.setItemValue("document.import.options", event.getSource().getItemValue("options"));

                RecordComparator record = new RecordComparator(entity, fields);

                // test if entity already exists in database....
                RecordComparator existingIndex = databaseCache.get(record.id);
                if (existingIndex != null && existingIndex.hash == record.hash) {
                    // we have an existing record
                    // now let's see if the data has changed....
                    // no changes!
                    continue;
                }
                if (existingIndex == null) {
                    // create a new entry...
                    entity.task(taskID);
                    processEntity(entity, modelVersion, workflowGroup, eventID);
                    workitemsImported++;
                } else {
                    // update existing entity...
                    logger.fine("update existing entity");
                    // copy all entries from the import into the existing entity
                    ItemCollection existingEntity = documentService.load(existingIndex.uniqueId);
                    existingEntity.replaceAllItems(entity.getAllItems());
                    processEntity(existingEntity, modelVersion, workflowGroup, eventID);
                    workitemsUpdated++;
                }

                if (blockSize >= 100) {
                    blockSize = 0;
                    logger.info("│   ├── " + csvFileName + ": " + workitemsTotal + " entries read (" + workitemsImported
                            + " imports , " + workitemsUpdated
                            + " updates)");
                    // flush lucene index!
                    indexUpdateService.updateIndex();
                }
            }

            logger.info("completed: " + workitemsTotal + " entries successful read");

        } catch (Exception e) {
            // Catch Workflow Exceptions
            workitemsFailed++;
            String sError = "import error at line " + line + ": " + e + " data=" + dataLine;
            logger.severe(sError);
            throw new PluginException(CSVImportService.class.getName(), DATA_ERROR, sError, e);
        }

        finally {
            // Close the input stream
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // now we remove all existing entries no longer listed in the file
        Set<String> databaseKeys = databaseCache.keySet();
        for (String key : databaseKeys) {
            if (!csvIndexCache.contains(key)) {
                // remove old record!
                ItemCollection deprecatedEntity = documentService.load(databaseCache.get(key).uniqueId);
                documentService.remove(deprecatedEntity);
                workitemsDeleted++;
            }

        }

        log += "..." + workitemsTotal + " entries read -> " + workitemsImported + " new entries - " + workitemsUpdated
                + " updates - " + workitemsDeleted + " deletions - " + workitemsFailed + " errors";

        logger.info(log);
        return log;
    }

    /**
     * This method processes an entity with the given workflow metadata. If no
     * workflow metadata is provided or the processing failed the method performs a
     * simple save.
     * 
     */
    private void processEntity(ItemCollection entity, String modelVersion, String workflowGroup,
            int eventID) {
        if (eventID > 0) {
            // process
            entity.model(modelVersion).workflowGroup(workflowGroup).event(eventID);
            try {
                workflowService.processWorkItemByNewTransaction(entity);
            } catch (EJBException | AccessDeniedException | ProcessingErrorException | PluginException
                    | ModelException e) {
                // processing failed so we perform a simple save!
                logger.warning("Processing failed: " + e.getMessage());
                documentService.saveByNewTransaction(entity);
            }
        } else {
            // update
            documentService.saveByNewTransaction(entity);
        }
    }

    /**
     * This helper method reads all existing documents and stores the hash index in
     * a local map index.
     * 
     * @return count of deletions
     * @throws QueryException
     * @throws PluginException
     */
    private Map<String, RecordComparator> readDocumentsFromDatabase(List<String> fields, String type,
            DocumentImportEvent event) throws QueryException, PluginException {
        int pageIndex = 0;
        int pageSize = 100;
        int totalCount = 0;
        Map<String, RecordComparator> result = new HashMap<>();

        String modelVersion = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_MODELVERSION);
        String workflowGroup = event.getSource().getItemValueString(DocumentImportService.SOURCE_ITEM_WORKFLOWGROUP);
        logger.info("│   ├── read entries from database...");
        // now we store the hash from each document in a hash index table.
        String query = "(type:" + type + ") ";

        // read Workflow options (optional)
        if ("workitem".equalsIgnoreCase(type)
                && (modelVersion.isEmpty() && workflowGroup.isEmpty())) {
            throw new PluginException(this.getClass().getName(), DATA_ERROR,
                    "Missing definition of $workflowgroup/$modelversion - type=='workitem' ! ");
        }
        if (!workflowGroup.isEmpty()) {
            query = query + " AND ($workflowgroup:\"" + workflowGroup + "\") ";
        } else {
            // optional query modelversion...
            if (!modelVersion.isEmpty()) {
                query = query + " AND ($modelversion:\"" + modelVersion + "\") ";
            }
        }
        logger.info("│   ├── query=" + query);
        while (true) {
            List<ItemCollection> entries = documentService.find(query, pageSize, pageIndex, "$created", false);
            for (ItemCollection entity : entries) {
                totalCount++;

                String id = entity.getItemValueString("name");
                result.put(id, new RecordComparator(entity, fields));
            }

            if (entries.size() == pageSize) {
                pageIndex++;
                logger.info("│   ├── " + totalCount + " entries read");
            } else {
                // end
                break;
            }
        }
        return result;
    }

    /**
     * This method creates a ItemCollection from a csv file data line
     * 
     * The method also creates a txtworkflowabstract to support fulltext search
     * 
     * @param data
     * @param fieldnames
     * @return
     */
    private ItemCollection readEntity(String data, List<String> fieldnames, String type, String keyField) {
        ItemCollection result = new ItemCollection();
        // add type...
        result.replaceItemValue("type", type);

        int iCol = 0;
        // @see
        // http://stackoverflow.com/questions/2241758/regarding-java-split-command-parsing-csv-file
        String[] valuList = data.split(";(?=([^\"]*\"[^\"]*\")*[^\"]*$)", 99);
        valuList = normalizeValueList(valuList);
        for (String itemValue : valuList) {
            // test if the token has content
            itemValue = itemValue.trim();
            if (itemValue != null && !itemValue.isEmpty()) {
                // create a itemValue with the corresponding fieldName
                result.replaceItemValue(fieldnames.get(iCol), itemValue);
            } else {
                // empty value
                result.replaceItemValue(fieldnames.get(iCol), "");
            }
            iCol++;
            if (iCol >= fieldnames.size()) {
                break;
            }
        }
        // replace 'name' by the key field
        String keyItemValue = result.getItemValueString(keyField);
        result.replaceItemValue("name", keyItemValue);

        return result;
    }

    /**
     * This method removes the " from a value list
     * 
     * 
     * @param data
     * @return
     */
    private String[] normalizeValueList(String[] data) {

        for (int i = 0; i < data.length; i++) {
            String value = data[i];
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
                data[i] = value;
            }

        }
        return data;
    }

    /**
     * This method parses the field descriptions (first line of the csv file)
     * 
     * @return list of fieldnames
     */
    private List<String> parseFieldList(String data) {
        List<String> result = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(data, ";");
        while (st.hasMoreTokens()) {
            String field = st.nextToken().trim();
            if (!field.isEmpty()) {
                field = field.replace("\"", "");
                field = field.replace("'", "");
                field = field.replace(".", "");
                field = field.replace(' ', '_');
                field = field.replace('/', '_');
                field = field.replace('\\', '_');
                field = field.replace('.', '_');
                field = field.replace('>', '_');
                field = field.replace('<', '_');
                field = field.replace('&', '_');
                result.add("_" + field.trim());
            } else {
                // add dummy entry
                result.add(null);
            }

        }
        return result;
    }

    /**
     * Local entity record to compare entities by a hash code
     */
    class RecordComparator {
        String id;
        int hash;
        String uniqueId;

        public RecordComparator(ItemCollection entity, List<String> fields) {
            this.id = entity.getItemValueString("name");
            this.hash = generateHash(entity, fields);
            this.uniqueId = entity.getUniqueID();
        }

        /**
         * Builds a hashcode from a
         * 
         * @param wokritem
         * @param fields
         * @return
         */
        private int generateHash(ItemCollection wokritem, List<String> fields) {
            String result = "";
            for (String item : fields) {
                result = result + wokritem.getItemValueString(item);
            }
            return result.hashCode();
        }
    }
}
