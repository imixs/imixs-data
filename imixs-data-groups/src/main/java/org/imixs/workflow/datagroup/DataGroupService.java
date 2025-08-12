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

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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

    public static final String ITEM_WORKITEMREF = "$workitemref";

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
     * @param source       - source ItemCollection to copy items
     * @param items        - list of items to be copied.
     * @return new DataGroup
     * @throws ModelException
     * @throws PluginException
     */
    public ItemCollection createDataGroup(String modelVersion, int taskId, int eventId, ItemCollection source,
            String items)
            throws ModelException, PluginException {

        logger.info("Create new DataGroup " + modelVersion + " " + taskId + "." + eventId);
        ItemCollection dataGroup = new ItemCollection().model(modelVersion).task(taskId).event(eventId);

        // now clone the field list...
        copyItemList(items, source, dataGroup);
        return workflowService.processWorkItem(dataGroup);

    }

    /**
     * This Method copies the fields defined in 'items' into the targetWorkitem.
     * Multiple values are separated with comma ','.
     * <p>
     * In case a item name contains '|' the target field name will become the right
     * part of the item name.
     * <p>
     * Example: {@code
     *   txttitle,txtfirstname
     *   
     *   txttitle|newitem1,txtfirstname|newitem2
     *   
     * }
     * 
     * <p>
     * Optional also reg expressions are supported. In this case mapping of the item
     * name is not supported.
     * <p>
     * Example: {@code
     *   (^artikel$|^invoice$),txtTitel|txtNewTitel
     *   
     *   
     * } A reg expression must be includes in brackets.
     * 
     */
    protected void copyItemList(String items, ItemCollection source, ItemCollection target) {
        // clone the field list...
        logger.info("copy itemlist: " + items);
        StringTokenizer st = new StringTokenizer(items, ",");
        while (st.hasMoreTokens()) {
            String field = st.nextToken().trim();

            // test if field is a reg ex
            if (field.startsWith("(") && field.endsWith(")")) {
                Pattern itemPattern = Pattern.compile(field);
                Map<String, List<Object>> map = source.getAllItems();
                for (String itemName : map.keySet()) {
                    if (itemPattern.matcher(itemName).find()) {
                        target.replaceItemValue(itemName, source.getItemValue(itemName));
                    }
                }

            } else {
                // default behavior without reg ex
                int pos = field.indexOf('|');
                if (pos > -1) {
                    target.replaceItemValue(field.substring(pos + 1).trim(),
                            source.getItemValue(field.substring(0, pos).trim()));
                } else {
                    target.replaceItemValue(field, source.getItemValue(field));
                }
            }
        }
    }

}
