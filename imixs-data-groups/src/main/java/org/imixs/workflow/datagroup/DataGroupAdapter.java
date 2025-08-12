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

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;

/**
 * The DataGroupAdapter is used add or remove a workitem to a data groups
 * 
 * Example:
 * 
 * <pre>
 * {@code
<imixs-data-group name="ADD">
    <query>(type:workitem) AND ($modelversion:sepa-export-manual*)</endpoint>
    <init.model>sepa-export-manual-1.0</init.model>
    <init.task>1000</init.task>
    <init.event>10</init.event>
    <!-- Optional -->
    <update.event>20</update.event>
</imixs-data-group>
 * }
 * </pre>
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class DataGroupAdapter implements SignalAdapter {

    private static Logger logger = Logger.getLogger(DataGroupAdapter.class.getName());

    @Inject
    DocumentService documentService;

    @Inject
    private WorkflowService workflowService;

    @Inject
    private DataGroupService dataGroupService;

    /**
     * Default Constructor
     */
    public DataGroupAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     * 
     * @param workflowService
     */
    @Inject
    public DataGroupAdapter(WorkflowService workflowService) {
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

        List<ItemCollection> addDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-group",
                DataGroupService.MODE_ADD, workitem, false);
        List<ItemCollection> removeDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-group",
                DataGroupService.MODE_REMOVE, workitem, false);
        /**
         * Iterate over each PROMPT definition and process the prompt
         */
        if (addDefinitions != null) {
            for (ItemCollection groupDefinition : addDefinitions) {
                addWorkitemToDataGroup(workitem, groupDefinition);
            }

        }

        // verify REMOVE mode
        if (removeDefinitions != null) {
            for (ItemCollection groupDefinition : removeDefinitions) {
                removeWorkitemFromDataGroup(workitem, groupDefinition);
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
    @SuppressWarnings("unchecked")
    private void addWorkitemToDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
            throws AccessDeniedException, PluginException {
        boolean debug = groupDefinition.getItemValueBoolean("debug");
        String query = groupDefinition.getItemValueString("query");
        query = workflowService.adaptText(query, workitem);

        String initModel = groupDefinition.getItemValueString("init.model");
        int initTaskId = groupDefinition.getItemValueInteger("init.task");
        int initEventId = groupDefinition.getItemValueInteger("init.event");
        String itemList = groupDefinition.getItemValueString("init.items");
        int updateEventId = groupDefinition.getItemValueInteger("update.event");

        logger.info("├── add workitem to dataGroup: " + initModel);
        ItemCollection dataGroup;
        // find group
        try {
            dataGroup = dataGroupService.findDataGroup(query);
            if (dataGroup == null) {
                if (debug) {
                    logger.info(
                            "│   ├── create new dataGroup " + initModel + " " + initTaskId + "." + initEventId);
                }
                // create a new one
                dataGroup = dataGroupService.createDataGroup(initModel, initTaskId, initEventId, workitem, itemList);
                if (debug) {
                    logger.info("│   ├── dataGroup created");
                }
            }

            // add current workitem to data gruop
            List<String> refList = workitem.getItemValue(DataGroupService.ITEM_WORKITEMREF);
            if (!refList.contains(dataGroup.getUniqueID())) {
                workitem.appendItemValueUnique(DataGroupService.ITEM_WORKITEMREF, dataGroup.getUniqueID());
                if (updateEventId > 0) {
                    dataGroup.event(updateEventId);
                    workflowService.processWorkItem(dataGroup);
                }

            } else {
                logger.info(
                        "│   ├── workitem already assigned to dataGroup '" + dataGroup.getUniqueID() + "'");
            }
        } catch (QueryException | ModelException | ProcessingErrorException e) {
            logger.warning("├── ⚠️ Failed to update dataGroup: " + e.getMessage());
            throw new PluginException(DataGroupAdapter.class.getName(),
                    DataGroupService.API_ERROR,
                    "⚠️ Failed to update dataGroup: " + e.getMessage(), e);
        }
    }

    /**
     * This helper method removes a single workitem from a dataGroup defined by a
     * dataGroupDefinition
     * 
     * @param workitem
     * @param groupDefinition
     * @throws AccessDeniedException
     * @throws PluginException
     * @throws ModelException
     */
    @SuppressWarnings("unchecked")
    private void removeWorkitemFromDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
            throws AccessDeniedException, PluginException {
        boolean debug = groupDefinition.getItemValueBoolean("debug");
        String query = groupDefinition.getItemValueString("query");
        query = workflowService.adaptText(query, workitem);
        int updateEventId = groupDefinition.getItemValueInteger("update.event");

        ItemCollection dataGroup;
        logger.info("├── remove workitem '" + workitem.getUniqueID() + "' from dataGroup...");
        // find group
        try {
            dataGroup = dataGroupService.findDataGroup(query);
            if (dataGroup != null) {
                if (debug) {
                    logger.info(
                            "│   ├── remove workitem '" + workitem.getUniqueID() + "' from dataGroup "
                                    + dataGroup.getUniqueID());
                }
                List<String> refList = workitem.getItemValue(DataGroupService.ITEM_WORKITEMREF);
                while (refList.contains(dataGroup.getUniqueID())) {
                    refList.remove(dataGroup.getUniqueID());
                    workitem.setItemValue(DataGroupService.ITEM_WORKITEMREF, refList);
                }

                if (updateEventId > 0) {
                    dataGroup.event(updateEventId);
                    workflowService.processWorkItem(dataGroup);
                }
            } else {
                logger.info(
                        "│   ├── ⚠️ no matching data group found");
            }

        } catch (QueryException | ProcessingErrorException | ModelException e) {
            logger.warning("├── ⚠️ Failed to update dataGroup: " + e.getMessage());
            throw new PluginException(DataGroupAdapter.class.getName(),
                    DataGroupService.API_ERROR,
                    "⚠️ Failed to update dataGroup: " + e.getMessage(), e);
        }
    }
}
