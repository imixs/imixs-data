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

package org.imixs.data;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
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
            <event.add>10</event.add>
            <event.maxcount>50</event.maxcount>
            <maxcount>50</maxcount>
        </imixs-data-group>
 * }
 * }
 * </pre>
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class DataGroupAdapter implements SignalAdapter {

    public static final int API_EVENT_SUCCESS = 110;
    public static final int API_EVENT_FAILURE = 90;

    private static Logger logger = Logger.getLogger(DataGroupAdapter.class.getName());

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
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        boolean debug = false;
        long processingTime = System.currentTimeMillis();

        logger.finest("...running data-group adapter...");

        // read optional configuration form the model or imixs.properties....
        try {

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
                    int eventIdAdd = groupDefinition.getItemValueInteger("event.add");
                    int maxCount = groupDefinition.getItemValueInteger("maxCount");
                    int eventIdMaxCount = groupDefinition.getItemValueInteger("event.maxCount");

                    ItemCollection dataGroup;
                    // find group
                    try {
                        dataGroup = dataGroupService.findDataGroup(query);

                        if (dataGroup == null) {
                            // create a new one
                            dataGroup = dataGroupService.createNewGroup(workitem, event);
                        }

                        // add current workitem to data gruop
                        List<String> refList = dataGroup.getItemValue("");
                        if (!refList.contains(workitem.getUniqueID())) {
                            dataGroup.appendItemValueUnique("", workitem.getUniqueID());
                            // set event 100
                            dataGroup.event(eventIdAdd);
                            workflowService.processWorkItem(dataGroup);

                            // test if the max count was reached.
                            if (maxCount > 0 && dataGroup.getItemValue("").size() >= maxCount) {
                                logger.info("......Max Count of " + maxCount
                                        + " workitems reached, executing maxcount-event=" + eventIdMaxCount);
                                // process the maxcoutn event on the sepa export
                                dataGroup.event(eventIdMaxCount);
                                workflowService.processWorkItem(dataGroup);
                            }
                        }
                    } catch (QueryException | ModelException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }

                    return workitem;
                }

            }

            // verify if we also have an optional SUGGEST configuration (only one definition
            // is supported!)
            if (removeDefinitions != null && removeDefinitions.size() > 0) {

            }

        } catch (PluginException e) {
            logger.severe("Unable to parse item definitions for 'imixs-ai', verify model - " + e.getMessage());
            throw new PluginException(
                    DataGroupAdapter.class.getSimpleName(), e.getErrorCode(),
                    "Unable to parse item definitions for 'imixs-ai', verify model - " + e.getMessage(), e);
        }

        if (debug) {
            logger.info("===> Total Processing Time=" + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return workitem;
    }

}
