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

import java.io.Serializable;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.WorkflowKernel;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;
import org.openbpmn.bpmn.BPMNModel;

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
     * @param key
     * @param workitem
     * @return
     * @throws ModelException
     * @throws PluginException
     */
    public ItemCollection createNewGroup( ItemCollection workitem, ItemCollection event)
            throws ModelException, PluginException {
        String modelVersion = null;
        int taskID = -1;

        // test if the event provides a sepa export configuration
        ItemCollection sepaConfig = workflowService.evalWorkflowResult(event, "sepa-export", workitem, true);
        if (sepaConfig != null && sepaConfig.hasItem("modelversion") && sepaConfig.hasItem("task")) {
            logger.fine("read model information from event");
            modelVersion = sepaConfig.getItemValueString("modelVersion");
            taskID = sepaConfig.getItemValueInteger("task");
        } else {
            logger.fine("read model information from configuration");
            ItemCollection configuration = loadConfiguration();
            if (configuration == null) {
                throw new PluginException(PluginException.class.getName(), ERROR_MISSING_DATA,
                        "Unable to load sepa configuration!");
            }
            modelVersion = configuration.getItemValueString(SepaWorkflowService.ITEM_MODEL_VERSION);
            taskID = configuration.getItemValueInteger(SepaWorkflowService.ITEM_INITIAL_TASK);
        }

        // build the sepa export workitem....
        ItemCollection sepaExport = new ItemCollection().model(modelVersion).task(taskID);
       
        sepaExport.replaceItemValue(WorkflowKernel.CREATED, new Date());
        sepaExport.replaceItemValue(WorkflowKernel.MODIFIED, new Date());
        // set unqiueid, needed for xslt
        sepaExport.setItemValue(WorkflowKernel.UNIQUEID, WorkflowKernel.generateUniqueID());

        String type = "OUT"; // default
        if (sepaConfig != null && !sepaConfig.isItemEmpty("type")) {
            type = sepaConfig.getItemValueString("type");
        }
        // Invoice/Lastschrift?
        if ("OUT".equalsIgnoreCase(type)) {
            // copy dbtr data...
            sepaExport.setItemValue(SepaWorkflowService.ITEM_DBTR_IBAN,
                    workitem.getItemValue(SepaWorkflowService.ITEM_DBTR_IBAN));
            if (workitem.hasItem(SepaWorkflowService.ITEM_DBTR_NAME)) {
                sepaExport.setItemValue(SepaWorkflowService.ITEM_DBTR_NAME,
                        workitem.getItemValue(SepaWorkflowService.ITEM_DBTR_NAME));
            }
            if (workitem.hasItem(SepaWorkflowService.ITEM_DBTR_BIC)) {
                sepaExport.setItemValue(SepaWorkflowService.ITEM_DBTR_BIC,
                        workitem.getItemValue(SepaWorkflowService.ITEM_DBTR_BIC));
            }
        } else {
            // copy cdtr data...
            sepaExport.setItemValue(SepaWorkflowService.ITEM_CDTR_IBAN,
                    workitem.getItemValue(SepaWorkflowService.ITEM_CDTR_IBAN));
            if (workitem.hasItem(SepaWorkflowService.ITEM_CDTR_NAME)) {
                sepaExport.setItemValue(SepaWorkflowService.ITEM_CDTR_NAME,
                        workitem.getItemValue(SepaWorkflowService.ITEM_CDTR_NAME));
            }
            if (workitem.hasItem(SepaWorkflowService.ITEM_CDTR_BIC)) {
                sepaExport.setItemValue(SepaWorkflowService.ITEM_CDTR_BIC,
                        workitem.getItemValue(SepaWorkflowService.ITEM_CDTR_BIC));
            }
        }
        if (workitem.hasItem(SepaWorkflowService.ITEM_PAYMENT_TYPE)) {
            sepaExport.setItemValue(SepaWorkflowService.ITEM_PAYMENT_TYPE,
                    workitem.getItemValue(SepaWorkflowService.ITEM_PAYMENT_TYPE));
        }
        // set sepa.report from first ref if available...
        if (workitem.hasItem(SepaWorkflowService.ITEM_SEPA_REPORT)) {
            sepaExport.setItemValue(SepaWorkflowService.ITEM_SEPA_REPORT,
                    workitem.getItemValue(SepaWorkflowService.ITEM_SEPA_REPORT));
        }

        // set workflow group name from the Task Element to identify document in xslt
        ModelManager modelManager = new ModelManager(workflowService);
        BPMNModel model = modelManager.getModel(modelVersion);
        ItemCollection task = modelManager.findTaskByID(model, taskID);

        // model.openDefaultProces().fin(type);.getTask(taskID);
        String modelTaskGroupName = task.getItemValueString("txtworkflowgroup"); // DO NOT CHANGE!
        sepaExport.setItemValue(WorkflowKernel.WORKFLOWGROUP, modelTaskGroupName);

        logger.info("...created new SEPA export for iban=" + key + "...");

        return sepaExport;
    }

}
