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
 * The DataGroupAdapter is used add or remove a workitem to a data groups. In
 * addition it is also possible to execute all relationships ob a datagroup.
 * 
 * Example to add a workitem to a data group:
 * 
 * <pre>
 * {@code
<imixs-data-group name="ADD">
    <query>(type:workitem) AND ($modelversion:sepa-export-manual*)</query>
    <init.model>sepa-export-manual-1.0</init.model>
    <init.task>1000</init.task>
    <init.event>10</init.event>
    <!-- Optional -->
    <init.items>datev.booking_period</init.items>
    <update.event>20</update.event>
</imixs-data-group>
 * }
 * </pre>
 * 
 * Example to execute all referred workitem in a data group:
 * 
 * <pre>
 * {@code
<imixs-data-group name="EXECUTE">
    <query>(type:workitem) AND ($modelversion:invoice*) AND ($taskid:1000)</query>
    <event>20</event>
</imixs-data-group>
 * }
 * </pre>
 * 
 * Note: The execution query will be extended by ' AND
 * ($workitemref:<UNIQUEID>)'
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class DataGroupAdapter implements SignalAdapter {

    public static final String MODE_ADD = "add";
    public static final String MODE_REMOVE = "remove";
    public static final String MODE_EXECUTE = "execute";

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
                MODE_ADD, workitem, true);
        List<ItemCollection> removeDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-group",
                MODE_REMOVE, workitem, true);
        List<ItemCollection> executeDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-group",
                MODE_EXECUTE, workitem, true);

        /**
         * Iterate over each definition and process the data group
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

        // verify EXECUTE mode
        if (executeDefinitions != null) {
            for (ItemCollection groupDefinition : executeDefinitions) {
                executeWorkitemFromDataGroup(workitem, groupDefinition);
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
    protected void addWorkitemToDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
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

            // add current workitem to data group
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
    protected void removeWorkitemFromDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
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

    /**
     * This helper method executes all workitems referring to this data group
     * 
     * @param workitem
     * @param groupDefinition
     * @throws AccessDeniedException
     * @throws PluginException
     * @throws ModelException
     */
    protected void executeWorkitemFromDataGroup(ItemCollection workitem, ItemCollection groupDefinition)
            throws AccessDeniedException, PluginException {
        boolean debug = groupDefinition.getItemValueBoolean("debug");
        String query = groupDefinition.getItemValueString("query");
        query = workflowService.adaptText(query, workitem);
        int eventId = groupDefinition.getItemValueInteger("event");

        ItemCollection dataGroup;
        logger.info("├── execute data group '" + workitem.getUniqueID() + "' ...");
        // find group
        try {

            List<ItemCollection> refList = dataGroupService.findDataGroupReferences(query, workitem);
            if (refList.size() > 0) {
                if (debug) {
                    logger.info("│   ├── executing " + refList.size() + " ...");
                }
                for (ItemCollection refWorkitem : refList) {
                    workflowService.processWorkItem(refWorkitem.event(eventId));
                }

            } else {
                logger.info(
                        "│   ├── ⚠️ no matching references found for data group '" + workitem.getUniqueID() + "'");
            }

        } catch (QueryException | ProcessingErrorException | ModelException e) {
            logger.warning("├── ⚠️ Failed to execute dataGroup references: " + e.getMessage());
            throw new PluginException(DataGroupAdapter.class.getName(),
                    DataGroupService.API_ERROR,
                    "⚠️ Failed to execute dataGroup references: " + e.getMessage(), e);
        }
    }

}
