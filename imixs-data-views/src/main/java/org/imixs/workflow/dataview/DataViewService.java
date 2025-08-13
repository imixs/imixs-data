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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
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
public class DataViewService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(DataViewService.class.getName());

    public static final String ITEM_WORKITEMREF = "$workitemref";

    public static final String API_ERROR = "API_ERROR";
    public static final String ERROR_MISSING_DATA = "MISSING_DATA";

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected DocumentService documentService;

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
}
