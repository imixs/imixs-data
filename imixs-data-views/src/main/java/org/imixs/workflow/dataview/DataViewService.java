/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.workflow.dataview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.imixs.archive.core.SnapshotService;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The OpenAIAPIService provides methods to post prompt templates to the OpenAI
 * API supported by Llama-cpp http server.
 * 
 * The service supports various processing events to update a prompt template
 * and to evaluate a completion result
 * 
 * 
 * @author rsoika
 *
 */
@Stateless
@LocalBean
public class DataViewService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(DataViewService.class.getName());

    public static final String ITEM_WORKITEMREF = "$workitemref";
    public static final int MAX_ROWS = 9999;
    public static final String ERROR_API = "API_ERROR";
    public static final String ERROR_CONFIG = "CONFIG_ERROR";
    public static final String ERROR_MISSING_DATA = "MISSING_DATA";

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected DocumentService documentService;

    @Inject
    protected SnapshotService snapshotService;

    @Inject
    protected Event<DataViewExportEvent> dataViewExportEvents;

    public WorkflowService getWorkflowService() {
        return workflowService;
    }

    /**
     * This method loads a DataView Definition for a given dataview
     * 
     * @param dataView - name of a dataview
     * @return ItemCollection
     */
    public ItemCollection loadDataViewDefinition(String dataView) {
        long l = System.currentTimeMillis();
        ItemCollection dataViewDefinition = null;
        try {
            String query = "(type:dataview) AND (name:\"" + dataView + "\")";

            List<ItemCollection> result = documentService.find(query, 1, 0);
            if (result.size() > 0) {
                dataViewDefinition = result.get(0);

            }
            logger.fine(
                    "getViewItemDefinitions: " + dataView + " took: " + (System.currentTimeMillis() - l) + "ms");
        } catch (QueryException e) {
            logger.warning("DataView '" + dataView + "' is not defined!");
        }
        return dataViewDefinition;
    }

    /**
     * Returns a List of ItemCollection instances representing the view column
     * description.
     * Each column has the items:
     * 
     * name,label,format,convert
     * 
     * @param dataViewDefinition
     * @return
     * 
     */
    @SuppressWarnings("unchecked")
    public List<ItemCollection> computeDataViewItemDefinitions(ItemCollection dataViewDefinition) {

        ArrayList<ItemCollection> result = new ArrayList<ItemCollection>();
        List<Object> mapItems = dataViewDefinition.getItemValue("dataview.items");
        for (Object mapOderItem : mapItems) {
            if (mapOderItem instanceof Map) {
                ItemCollection itemCol = new ItemCollection((Map) mapOderItem);
                // check label
                String itemLabel = itemCol.getItemValueString("item.label");
                if (itemLabel.isEmpty()) {
                    itemCol.setItemValue("item.label", itemLabel);
                }
                // check type
                String itemType = itemCol.getItemValueString("item.type");
                if (itemType.isEmpty()) {
                    itemCol.setItemValue("item.type", "xs:string");
                }
                result.add(itemCol);
            }
        }

        // if no columns are defined we create the default columns
        if (result.size() == 0) {
            ItemCollection itemCol = new ItemCollection();
            itemCol.setItemValue("item.name", "$workflowSummary");
            itemCol.setItemValue("item.label", "Name");
            itemCol.setItemValue("item.type", "xs:anyURI");
            result.add(itemCol);

            itemCol = new ItemCollection();
            itemCol.setItemValue("item.name", "$modified");
            itemCol.setItemValue("item.label", "Modified");
            itemCol.setItemValue("item.type", "xs:date");
            result.add(itemCol);
        }
        return result;
    }

    /**
     * This method returns the first excel poi template from the Data Definition
     *
     * @param dataViewDefinition with the attached fileData
     * @throws PluginException if no template was found
     *
     */
    public FileData loadTemplate(ItemCollection dataViewDefinition) throws PluginException {

        // first filename
        List<FileData> fileDataList = dataViewDefinition.getFileData();
        if (fileDataList != null && fileDataList.size() > 0) {
            String fileName = fileDataList.get(0).getName();
            return snapshotService.getWorkItemFile(dataViewDefinition.getUniqueID(), fileName);
        }

        // we did not found the template!
        throw new PluginException(DataViewController.class.getSimpleName(), ERROR_CONFIG,
                "Missing Excel Export Template - check DataView definition!");

    }

    /**
     * Applies item values to the given query string defined in a dataViewDefinition
     * 
     * @param query
     * @param filter
     * @return
     */
    public String parseQuery(ItemCollection dataViewDefinition, ItemCollection filter) {

        String query = dataViewDefinition.getItemValueString("query");
        boolean debug = dataViewDefinition.getItemValueBoolean("debug");
        if (debug) {
            logger.info("🪲 parse query=" + query);
        }
        try {
            query = workflowService.adaptText(query, filter);
        } catch (PluginException e) {
            logger.warning("Failed to parse Query: " + e.getMessage());
        }

        // support deprecated format
        query = parseQueryDeprecated(query, filter);
        return query;
    }

    /**
     * Deprecated - use <itemvalue> instead!
     * 
     * @param query
     * @param filter
     * @return
     */
    private String parseQueryDeprecated(String query, ItemCollection filter) {
        List<String> filterItems = filter.getItemNames();
        for (String itemName : filterItems) {
            String itemValue = filter.getItemValueString(itemName);

            // is date?
            if (filter.getItemValueDate(itemName) != null) {
                SimpleDateFormat dateformat = new SimpleDateFormat("yyyyMMddHHmm");
                itemValue = dateformat.format(filter.getItemValueDate(itemName));
            }
            // Create regex pattern to match {itemName} (case-sensitive)
            // The Pattern.quote is used to escape any special regex characters in the
            // itemName.
            // Only in case we have a value, we replace all occurrences in the query
            // case-insensitive.
            itemValue = itemValue.trim();
            if (!itemValue.isEmpty()) {
                // query = query.replaceAll("(?i)\\{" + Pattern.quote(itemName) + "\\}",
                // itemValue);
                // Prüfen ob das Pattern im Query-String vorkommt
                String patternToFind = "\\{" + Pattern.quote(itemName) + "\\}";
                java.util.regex.Pattern checkPattern = java.util.regex.Pattern.compile("(?i)" + patternToFind);
                java.util.regex.Matcher matcher = checkPattern.matcher(query);
                if (matcher.find()) {
                    logger.warning("Deprecated query pattern {itemName} - use <itemvalue> tag instead!");
                    query = query.replaceAll("(?i)" + patternToFind, itemValue);
                }
            }
        }

        // if we still have {} replace them with *
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{[^}]*\\}");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        query = matcher.replaceAll("*");
        // remove **
        query = query.replace("**", "*");
        return query;
    }

    /**
     * The method exports a dataset into a a POI XSSFWorkbook and returns a new
     * FileData object with a POI Workfbook. The Workbook is loaded from a template
     * data in a dataViewDefinition.
     * <p>
     * The export method sends a DataViewExportEvent. An observer CID bean can
     * implement alternative exporters.
     * With the event property 'completed' a client can signal that the export
     * process is completed. Otherwise the default behavior will be adapted.
     * 
     * 
     * 
     * @throws PluginException
     */
    public FileData poiExport(List<ItemCollection> dataset, ItemCollection dataViewDefinition,
            List<ItemCollection> viewItemDefinitions) throws PluginException {

        boolean debug = dataViewDefinition.getItemValueBoolean("debug");

        // load XSSFWorkbook
        FileData templateFileData = loadTemplate(dataViewDefinition);
        // build target name
        SimpleDateFormat dateformat = new SimpleDateFormat("yyyyMMddHHmm");
        String targetFileName = dataViewDefinition.getItemValueString("poi.targetFilename");
        if (targetFileName.isEmpty()) {
            throw new PluginException(DataViewController.class.getSimpleName(), DataViewService.ERROR_CONFIG,
                    "Missing Excel Export definition - check configuration!");
        }
        targetFileName = workflowService.adaptText(targetFileName, dataViewDefinition);
        targetFileName = targetFileName + "_" + dateformat.format(new Date()) + ".xlsx";

        // start export
        if (debug) {
            logger.info("├── Start POI Export : " + targetFileName + "...");
            logger.info("│   ├── Target File: " + targetFileName);
        }
        try (InputStream inputStream = new ByteArrayInputStream(templateFileData.getContent())) {
            XSSFWorkbook doc = new XSSFWorkbook(inputStream);
            // send DataViewExportEvent....
            if (dataViewExportEvents != null) {
                DataViewExportEvent event = new DataViewExportEvent(dataset, dataViewDefinition, viewItemDefinitions,
                        doc);
                dataViewExportEvents.fire(event); // found FileData?
                if (!event.isCompleted()) {
                    // Default behavior
                    insertRows(dataset, dataViewDefinition, viewItemDefinitions, doc);
                }
            }

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            // write data
            doc.write(byteArrayOutputStream);
            doc.close();

            // return a new DataFile object
            byte[] newContent = byteArrayOutputStream.toByteArray();
            FileData fileData = new FileData(targetFileName, newContent, templateFileData.getContentType(), null);
            return fileData;

        } catch (IOException e) {
            throw new PluginException(DataViewPOIHelper.class.getSimpleName(), ERROR_CONFIG,
                    "failed to update excel export: " + e.getMessage());
        }
    }

    /**
     * This helper method inserts for each ItemCollection of a DataSet a new row
     * into a POI
     * XSSFSheet based on a DataViewDefintion.
     * 
     * @param dataset
     * @param referenceCell
     * @param viewItemDefinitions
     * @param doc
     */
    private void insertRows(List<ItemCollection> dataset, ItemCollection dataViewDefinition,
            List<ItemCollection> viewItemDefinitions, XSSFWorkbook doc) {
        String referenceCell = dataViewDefinition.getItemValueString("poi.referenceCell");

        // NOTE: we only take the first sheet !
        XSSFSheet sheet = doc.getSheetAt(0);

        CellReference cr = new CellReference(referenceCell);
        XSSFRow referenceRow = sheet.getRow(cr.getRow());
        int referenceRowPos = referenceRow.getRowNum() + 1;
        int rowPos = referenceRowPos;
        // int lastRow = sheet.getLastRowNum();
        int lastRow = 999;
        logger.finest("Last rownum=" + lastRow);
        sheet.shiftRows(rowPos, lastRow, dataset.size(), true, true);

        for (ItemCollection workitem : dataset) {
            logger.finest("......copy row...");

            // now create a new line..
            XSSFRow row = sheet.createRow(rowPos);
            row.copyRowFrom(referenceRow, new CellCopyPolicy());
            // insert values
            int cellNum = 0;
            for (ItemCollection itemDef : viewItemDefinitions) {
                String type = itemDef.getItemValueString("item.type");
                String name = itemDef.getItemValueString("item.name");
                try {
                    switch (type) {
                        case "xs:double":
                            row.getCell(cellNum).setCellValue(workitem.getItemValueDouble(name));
                            break;
                        case "xs:float":
                            row.getCell(cellNum).setCellValue(workitem.getItemValueFloat(name));
                            break;
                        case "xs:int":
                            row.getCell(cellNum).setCellValue(workitem.getItemValueInteger(name));
                            break;
                        case "xs:date":
                            row.getCell(cellNum).setCellValue(workitem.getItemValueDate(name));
                            break;
                        default:
                            row.getCell(cellNum).setCellValue(workitem.getItemValueString(name));
                    }
                } catch (Exception epoi) {
                    logger.warning("POI Error cell " + cellNum + " item: " + name);
                }
                cellNum++;
            }

            rowPos++;
        }
        // delete reference row
        sheet.shiftRows(referenceRowPos, lastRow + dataset.size(), -1, true, true);

    }

}
