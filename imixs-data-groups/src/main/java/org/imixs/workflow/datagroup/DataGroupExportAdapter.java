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

package org.imixs.workflow.datagroup;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.dataview.DataViewService;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;

/**
 * The DataGroupExportAdapter is used to export the data of a data group either
 * into a csv file or an excel file based on a dataview definition
 * 
 * Example:
 * 
 * <pre>
 * {@code
<imixs-data-group name="EXPORT">
    <type>CSV|EXCEL</type>
    <dataview>invoices</dataview>
    <targetname>my-export.csv</targetname>
   <debug>true</debug>
</imixs-data-group>
 * }
 * </pre>
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class DataGroupExportAdapter implements SignalAdapter {

    private static Logger logger = Logger.getLogger(DataGroupAdapter.class.getName());

    public static final String MODE_EXPORT = "export";

    @Inject
    DocumentService documentService;

    @Inject
    private WorkflowService workflowService;

    @Inject
    private DataGroupService dataGroupService;

    @Inject
    private DataViewService dataViewService;

    /**
     * Default Constructor
     */
    public DataGroupExportAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     * 
     * @param workflowService
     */
    @Inject
    public DataGroupExportAdapter(WorkflowService workflowService) {
        super();
        this.workflowService = workflowService;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * This method parses the DataGroup Event definitions.
     * 
     * A workitem can be added name=ADD or removed name=REMOVE
     * 
     * @throws PluginException
     */
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        long processingTime = System.currentTimeMillis();

        // read optional configuration form the model or imixs.properties....

        List<ItemCollection> exportDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-group",
                MODE_EXPORT, workitem, true);

        /**
         * Iterate over each PROMPT definition and process the prompt
         */
        if (exportDefinitions != null) {
            for (ItemCollection groupDefinition : exportDefinitions) {
                exportWorkitemToDataGroup(workitem, groupDefinition);
            }

        }

        logger.info("├── ✅ completed (" + (System.currentTimeMillis() - processingTime) + "ms)");

        return workitem;
    }

    /**
     * This helper method adds a single workitem to a dataGroup defined by a
     * dataGroupDefinition
     * 
     * @param workitem
     * @param groupDefinition
     * @throws AccessDeniedException
     * @throws PluginException
     */
    private void exportWorkitemToDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
            throws AccessDeniedException, PluginException {
        boolean debug = groupDefinition.getItemValueBoolean("debug");
        String type = groupDefinition.getItemValueString("type").trim();
        String separator = groupDefinition.getItemValueString("separator");
        if (separator.isBlank()) {
            separator = ";";
        }
        String targetname = groupDefinition.getItemValueString("targetname").trim();
        String dataview = groupDefinition.getItemValueString("dataview").trim();

        logger.info("├── export dataGroup: " + type + " -> " + targetname);
        if (debug) {
            logger.info("│   ├── type=" + type);
            logger.info("│   ├── separator=" + separator);
            logger.info("│   ├── dataview='" + dataview + "'");
        }
        try {
            // find definition
            ItemCollection dataViewDefinition = dataViewService.loadDataViewDefinition(dataview);
            if (dataViewDefinition == null) {
                throw new PluginException(DataGroupAdapter.class.getName(),
                        DataGroupService.API_ERROR, "⚠️ Failed to dataview - not defined!");
            }
            List<ItemCollection> viewItemDefinitions = dataViewService
                    .computeDataViewItemDefinitions(dataViewDefinition);
            // load data
            List<ItemCollection> data = dataGroupService.loadData(workitem.getUniqueID(), 0, 9999, null, false, false);
            byte[] fileRawData = null;
            if (debug) {
                logger.info("│   ├── ☑️ loaded data - " + data.size() + " workitems found");
                logger.info("│   ├── export data....");
                if ("csv".equalsIgnoreCase(type)) {
                    fileRawData = exportCSV(data, viewItemDefinitions, separator);
                    FileData fileData = new FileData(targetname, fileRawData, "application/text", null);
                    workitem.addFileData(fileData);
                    logger.info("│   ├── ✅ export successful");
                } else if ("excel".equalsIgnoreCase(type)) {

                } else {
                    logger.info("│   ├── ⚠️ export type '" + type + "' not supported!");
                }
            }
        } catch (QueryException e) {
            logger.warning("├── ⚠️ Failed to export dataGroup: " + e.getMessage());
            throw new PluginException(DataGroupAdapter.class.getName(),
                    DataGroupService.API_ERROR,
                    "⚠️ Failed to export dataGroup: " + e.getMessage(), e);
        }

    }

    /**
     * Writes a CSV File into a bye array based on the given ViewItems definition
     * and
     * the data colleciton
     * 
     * @param data
     */
    private byte[] exportCSV(List<ItemCollection> data, List<ItemCollection> viewItemDefinitions, String separator) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8));

        // write header
        String header = "";
        for (ItemCollection itemDef : viewItemDefinitions) {
            String label = itemDef.getItemValueString("item.label");
            header = header + escapeCSVField(label) + separator;
        }
        // cut last separator...
        if (header.length() > 0) {
            header = header.substring(0, header.length() - separator.length());
        }
        writer.println(header);

        // iterate over the data
        for (ItemCollection workitem : data) {
            String line = "";
            // build each column
            for (ItemCollection itemDef : viewItemDefinitions) {
                String type = itemDef.getItemValueString("item.type");
                String name = itemDef.getItemValueString("item.name");
                String format = itemDef.getItemValueString("item.format"); // optional

                String fieldValue = "";

                switch (type) {
                    case "xs:double":
                        double _double = workitem.getItemValueDouble(name);
                        if (format != null && !format.isEmpty()) {
                            fieldValue = String.format(format, _double);
                        } else {
                            fieldValue = String.valueOf(_double);
                        }
                        break;

                    case "xs:float":
                        float _float = workitem.getItemValueFloat(name);
                        if (format != null && !format.isEmpty()) {
                            fieldValue = String.format(format, _float);
                        } else {
                            fieldValue = String.valueOf(_float);
                        }
                        break;

                    case "xs:int":
                        int _int = workitem.getItemValueInteger(name);
                        if (format != null && !format.isEmpty()) {
                            fieldValue = String.format(format, _int);
                        } else {
                            fieldValue = String.valueOf(_int);
                        }
                        break;

                    case "xs:date":
                        Date _date = workitem.getItemValueDate(name);
                        if (_date != null) {
                            if (format != null && !format.isEmpty()) {
                                SimpleDateFormat sdf = new SimpleDateFormat(format);
                                fieldValue = sdf.format(_date);
                            } else {
                                fieldValue = _date.toString();
                            }
                        }
                        break;

                    default:
                        // string
                        String value = workitem.getItemValueString(name);
                        if (value != null) {
                            if (format != null && !format.isEmpty()) {
                                fieldValue = String.format(format, value);
                            } else {
                                fieldValue = value;
                            }
                        }
                        break;
                }

                line = line + escapeCSVField(fieldValue) + separator;
            }

            // cut last separator...
            if (line.length() > 0) {
                line = line.substring(0, line.length() - separator.length());
            }

            // add line
            writer.println(line);
        }

        writer.flush();
        writer.close();

        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Escapes CSV fields by wrapping them in quotes if they contain
     * separator, newline, or quote characters
     */
    private String escapeCSVField(String field) {
        if (field == null) {
            return "";
        }

        // If field contains separator, newline, or quotes, wrap in quotes
        if (field.contains(",") || field.contains(";") || field.contains("\n") ||
                field.contains("\r") || field.contains("\"")) {
            // Escape existing quotes by doubling them
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }

        return field;
    }
}
