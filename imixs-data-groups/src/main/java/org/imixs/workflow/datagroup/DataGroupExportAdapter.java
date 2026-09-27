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

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.dataview.DataViewPOIHelper;
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
    <type>CSV|POI</type>
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

            // load data
            byte[] fileRawData = null;
            if (debug) {
                logger.info("│   ├── export data....");
            }
            if ("csv".equalsIgnoreCase(type)) {
                List<ItemCollection> data = dataGroupService.loadData(workitem.getUniqueID(), DataViewService.MAX_ROWS,
                        0,
                        null, false, false);
                List<ItemCollection> viewItemDefinitions = dataViewService
                        .computeDataViewItemDefinitions(dataViewDefinition);
                fileRawData = dataViewService.exportCSV(data, viewItemDefinitions, separator);
                FileData fileData = new FileData(targetname, fileRawData, "application/text", null);
                workitem.addFileData(fileData);
                logger.info("│   ├── ✅ export successful");
            } else if ("poi".equalsIgnoreCase(type)) {
                FileData fileData = exportPoi(workitem, dataViewDefinition);
                workitem.addFileData(fileData);
                logger.info("│   ├── ✅ export successful");
            } else {
                logger.info("│   ├── ⚠️ export type '" + type + "' not supported!");
            }

        } catch (

        QueryException e) {
            logger.warning("├── ⚠️ Failed to export dataGroup: " + e.getMessage());
            throw new PluginException(DataGroupAdapter.class.getName(),
                    DataGroupService.API_ERROR,
                    "⚠️ Failed to export dataGroup: " + e.getMessage(), e);
        }

    }

    /**
     * Writes a Excel File into a bye array based on the given ViewItems definition
     * and the data collection
     * 
     * @param data
     * @throws QueryException
     * @throws PluginException
     */
    private FileData exportPoi(ItemCollection workitem, ItemCollection dataViewDefinition)
            throws QueryException, PluginException {
        String uniqueid = workitem.getUniqueID();

        List<ItemCollection> viewItemDefinitions = dataViewService
                .computeDataViewItemDefinitions(dataViewDefinition);

        String sortBy = dataViewDefinition.getItemValueString("sort.by");
        if (sortBy.isEmpty()) {
            sortBy = "$modified"; // default
        }
        List<ItemCollection> workitems = dataGroupService.loadData(uniqueid, DataViewService.MAX_ROWS, 0, sortBy, false,
                false);

        FileData fileDataExport = dataViewService.exportPOI(workitems, dataViewDefinition, viewItemDefinitions);

        // create a temp event
        ItemCollection event = new ItemCollection().setItemValue("txtActivityResult",
                dataViewDefinition.getItemValue("poi.update"));
        ItemCollection poiConfig = workflowService.evalWorkflowResult(event, "poi-update", dataViewDefinition,
                false);

        DataViewPOIHelper.poiUpdate(workitem, fileDataExport, poiConfig, workflowService);

        return fileDataExport;

    }
}
