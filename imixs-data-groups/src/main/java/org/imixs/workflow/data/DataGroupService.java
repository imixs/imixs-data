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

import java.io.Serializable;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
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
public class DataGroupService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(DataGroupService.class.getName());

    public static final String API_ERROR = "API_ERROR";
    public static final String ERROR_MISSING_DATA = "MISSING_DATA";

    public static final String MODE_ADD = "add";
    public static final String MODE_REMOVE = "remove";

    @Inject
    protected WorkflowService workflowService;

    /**
     * Helper method verifies all data groups and returns the latest for the
     * given key name. If no data group exists the method returns null.
     * 
     * @param key
     * @param taskID - optional can be used to restrict the lookup for a specific
     *               task
     * @return
     * @throws QueryException
     */
    public ItemCollection findDataGroup(String query) throws QueryException {

        List<ItemCollection> resultList = workflowService.getDocumentService().find(query, 1, 0, "$modified", true);

        if (resultList.size() > 0) {
            return resultList.get(0);
        }
        // no matching data group found
        return null;
    }

    /**
     * Helper method to create a new Data Group
     * 
     * @param modelversion
     * @param taskId
     * @param eventId
     * @return new DataGroup
     * @throws ModelException
     * @throws PluginException
     */
    public ItemCollection createDataGroup(String modelVersion, int taskId, int eventId)
            throws ModelException, PluginException {

        logger.info("Create new DataGroup " + modelVersion + " " + taskId + "." + eventId);
        ItemCollection dataGroup = new ItemCollection().model(modelVersion).task(taskId).event(eventId);
        return workflowService.processWorkItem(dataGroup);

    }

}
