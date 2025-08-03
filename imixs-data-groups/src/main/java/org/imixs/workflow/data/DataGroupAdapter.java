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

package org.imixs.workflow.data;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
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
    <query>(type:workitem) AND (:sepa-export-manual*)</endpoint>
    <init.model>sepa-export-manual-1.0</init.model>
    <init.task>1000</init.task>
    <init.event>10</init.event>
    <!-- Optional -->
    <update.event>20</update.event>
    <event.maxcount>50</event.maxcount>
    <maxcount>50</maxcount>
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
     * This method parses the LLM Event definitions.
     * 
     * For each PROMPT the method posts a context data (e.g text from an attachment)
     * to the Imixs-AI Analyse service endpoint
     * 
     * @throws PluginException
     */
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        boolean debug = false;
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

                String query = groupDefinition.getItemValueString("query");

                String initModel = groupDefinition.getItemValueString("init.model");
                int initTaskId = groupDefinition.getItemValueInteger("init.task");
                int initEventId = groupDefinition.getItemValueInteger("init.event");
                int updateEventId = groupDefinition.getItemValueInteger("update.event");

                int maxCount = groupDefinition.getItemValueInteger("maxCount");
                int eventIdMaxCount = groupDefinition.getItemValueInteger("event.maxCount");

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
                        dataGroup = dataGroupService.createDataGroup(initModel, initTaskId, initEventId);
                        if (debug) {
                            logger.info("│   ├── dataGroup created");
                        }
                    }

                    // add current workitem to data gruop
                    List<String> refList = dataGroup.getItemValue("$workitemref");
                    if (!refList.contains(workitem.getUniqueID())) {
                        dataGroup.appendItemValueUnique("$workitemref", workitem.getUniqueID());
                        if (updateEventId > 0) {
                            dataGroup.event(updateEventId);
                            workflowService.processWorkItem(dataGroup);
                        } else {
                            // simple update
                            documentService.save(dataGroup);
                        }

                        // test if the max count was reached.
                        if (maxCount > 0 && dataGroup.getItemValue("").size() >= maxCount) {
                            logger.info("│   ├── Max Count of " + maxCount
                                    + " workitems reached, executing maxcount-event=" + eventIdMaxCount);
                            // process the maxcoutn event on the sepa export
                            dataGroup.event(eventIdMaxCount);
                            workflowService.processWorkItem(dataGroup);
                        }
                    }
                } catch (QueryException | ModelException | ProcessingErrorException e) {
                    logger.warning("├── ⚠️ Failed to update dataGroup: " + e.getMessage());
                    throw new PluginException(DataGroupAdapter.class.getName(),
                            DataGroupService.API_ERROR,
                            "⚠️ Failed to update dataGroup: " + e.getMessage(), e);
                }

                return workitem;
            }

        }

        // verify REMOVE mode
        if (removeDefinitions != null && removeDefinitions.size() > 0) {
            logger.warning("├── ⚠️ Remove not yet implemented");
        }

        logger.info("├── ✅ completed (" + (System.currentTimeMillis() - processingTime) + "ms)");

        return workitem;
    }

}
