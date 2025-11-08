/*******************************************************************************
 *  Imixs Workflow Technology
 *  Copyright (C) 2003, 2008 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Contributors:  
 *  	Imixs Software Solutions GmbH - initial API and implementation
 *  	Ralph Soika
 *  
 *******************************************************************************/
package org.imixs.workflow.datagroup;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.dataview.DataViewController;
import org.imixs.workflow.dataview.DataViewExportEvent;
import org.imixs.workflow.dataview.DataViewPOIHelper;
import org.imixs.workflow.dataview.DataViewService;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;
import org.imixs.workflow.faces.data.ViewController;
import org.imixs.workflow.faces.data.WorkflowController;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Conversation;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The DataGroupController provides methods to display a data group. The
 * controller extends the ViewController class to display a list of
 * references to the current workflow group. The controller supports pagination.
 * <p>
 * The controller can optional load a DataView definition to compute the query
 * and the column sets. If no DataView definition is set, the controller
 * provides a default query.
 * 
 * @author rsoika
 * @version 1.0
 */

@Named
@ConversationScoped
public class DataGroupController extends ViewController {
    private static final long serialVersionUID = 1L;

    public static final int MAX_SEARCH_RESULT = 1000;
    public static Logger logger = Logger.getLogger(DataGroupController.class.getName());

    @Inject
    protected WorkflowService workflowService;

    @Inject
    WorkflowController workflowController;

    @Inject
    DocumentService documentService;

    @Inject
    private Conversation conversation;

    @Inject
    DataViewService dataViewService;

    private String dataGroupQuery;

    // optional dataView settings
    private ItemCollection dataViewDefinition;
    private String options;
    private String dataViewName;
    private List<ItemCollection> viewItemDefinitions = null;

    @Override
    @PostConstruct
    public void init() {
        super.init();
        // this.setQuery(dataViewController.getQuery());
        this.setSortBy("$modified");
        this.setSortReverse(false);
        this.setPageSize(100);
        this.setLoadStubs(false);
        startConversation();
    }

    /**
     * Starts a new conversation
     */
    protected void startConversation() {
        if (conversation.isTransient()) {
            conversation.setTimeout(
                    ((HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest())
                            .getSession().getMaxInactiveInterval() * 1000);
            conversation.begin();
            logger.log(Level.FINEST, "......start new conversation, id={0}",
                    conversation.getId());
        }
    }

    /**
     * Loads a single Workitem
     * 
     * @param id
     * @return
     */
    public ItemCollection loadWorkitem(String id) {
        return workflowService.getWorkItem(id);
    }

    /**
     * Returns the current query
     * 
     * @return
     */
    @Override
    public String getQuery() {
        return dataGroupQuery;
    }

    /**
     * Helper method to load a full workitem from the frontend
     * 
     * @param id
     * @return
     */
    public ItemCollection getWorkitem(String id) {
        return workflowService.getWorkItem(id);
    }

    /**
     * Returns a single value from a Option key/value list
     * 
     * @param options - options
     * @param key     - option key
     * @return option value
     */
    public String getOptionValue(String options, String key) {
        // Null checks
        if (options == null || key == null || options.trim().isEmpty() || key.trim().isEmpty()) {
            return null;
        }

        // Split options into key/value pairs (separated by semicolon)
        String[] pairs = options.split(";");

        for (String pair : pairs) {
            // Split each pair into key and value (separated by equals sign)
            String[] keyValue = pair.split("=", 2); // Limit to 2 in case value contains "="

            if (keyValue.length == 2) {
                String currentKey = keyValue[0].trim();
                String currentValue = keyValue[1].trim();

                // Check if the searched key was found
                if (key.equals(currentKey)) {
                    return currentValue;
                }
            }
        }

        // Key not found
        return null;
    }

    public String getDataViewName() {
        return dataViewName;
    }

    /**
     * Computes the query. The Quer is either defined by a DataView definition or
     * computed dynamically.
     */
    private void computeQuery() {

        dataGroupQuery = "";
        boolean debug = false;

        // Lazy load dataView if dataViewName is set but dataView not loaded yet
        if (dataViewName != null && dataViewDefinition == null) {
            loadDataView(dataViewName);
        }

        // do we have a dataView defined?
        if (dataViewDefinition != null) {
            debug = dataViewDefinition.getItemValueBoolean("debug");
            if (debug) {
                logger.info("resolve query by dataView '" + dataViewName + "'");
            }
            // resove query by dataView
            dataGroupQuery = dataViewService.parseQuery(dataViewDefinition, workflowController.getWorkitem());

        } else {
            // select all references by ref.....
            String uniqueId = workflowController.getWorkitem().getUniqueID();
            dataGroupQuery = "(";
            dataGroupQuery = " (type:\"workitem\" OR type:\"workitemarchive\") AND ("
                    + DataGroupService.ITEM_WORKITEMREF
                    + ":\""
                    + uniqueId + "\")";
        }

    }

    /**
     * Set options and parse dataViewName from options.
     * The expected format of the options string is:
     * <p>
     * "dataview=DATAVIEWNAME"
     * 
     * <p>
     * The method loads the corresponding dataView definition immediately.
     * 
     * @param options
     */
    public void setOptions(String options) {
        this.options = options;

        // Extract and set dataViewName from options
        if (options != null) {
            String dataViewName = getOptionValue(options, "dataview");
            if (dataViewName != null) {
                this.dataViewName = dataViewName;
                // Note: We don't load the dataView here - lazy loading in getQuery()

                computeQuery();

            }
        }
    }

    public String getOptions() {
        return options;
    }

    /**
     * Loads a DataView by name and preloads the viewItem definitions of the
     * DataView
     * 
     * @param dataViewName
     * @return
     */
    public ItemCollection loadDataView(String dataViewName) {
        dataViewDefinition = dataViewService.loadDataViewDefinition(dataViewName);
        // preload the viewItem definitions
        viewItemDefinitions = dataViewService.computeDataViewItemDefinitions(dataViewDefinition);
        return dataViewDefinition;
    }

    public List<ItemCollection> getViewItemDefinitions() {
        return viewItemDefinitions;
    }

    /**
     * Exports data into a excel template processed by apache-poi. The method sends
     * a DataViewExport event to allow clients to adapt the export process.
     * 
     * @see DataViewExportEvent
     *
     * @throws PluginException
     * @throws QueryException
     */
    public String export() throws PluginException, QueryException {

        // Build target filename
        boolean debug = dataViewDefinition.getItemValueBoolean("debug");

        // start export
        if (debug) {
            logger.info("│   ├── Query: " + dataGroupQuery);
        }

        try {

            // test if query exceeds max count
            int totalCount = documentService.count(dataGroupQuery);
            // start export
            if (debug) {
                logger.info("│   ├── Count: " + totalCount);
            }
            if (totalCount > DataViewService.MAX_ROWS) {
                throw new PluginException(DataViewController.class.getSimpleName(), DataViewService.ERROR_CONFIG,
                        "Data can not be exported into Excel because dataset exceeds " + DataViewService.MAX_ROWS
                                + " rows!");
            }
            String sortBy = dataViewDefinition.getItemValueString("sort.by");
            if (sortBy.isEmpty()) {
                sortBy = "$modified"; // default
            }
            List<ItemCollection> workitems = documentService.find(dataGroupQuery, DataViewService.MAX_ROWS, 0, sortBy,
                    dataViewDefinition.getItemValueBoolean("sort.reverse"));

            FileData fileDataExport = dataViewService.poiExport(workitems, dataViewDefinition, viewItemDefinitions);

            // create a temp event
            ItemCollection event = new ItemCollection().setItemValue("txtActivityResult",
                    dataViewDefinition.getItemValue("poi.update"));
            ItemCollection poiConfig = workflowService.evalWorkflowResult(event, "poi-update", dataViewDefinition,
                    false);

            // merge workitem fields (Workaround because custom forms did hard coded map to
            // workflowController instead of workitem

            DataViewPOIHelper.poiUpdate(workflowController.getWorkitem(), fileDataExport, poiConfig, workflowService);

            if (debug) {
                logger.info("├── POI Export completed!");
            }
            // See:
            // https://stackoverflow.com/questions/9391838/how-to-provide-a-file-download-from-a-jsf-backing-bean
            DataViewPOIHelper.downloadExcelFile(fileDataExport);
        } catch (IOException | QueryException e) {
            throw new PluginException(DataViewController.class.getSimpleName(), DataViewService.ERROR_CONFIG,
                    "Failed to generate Excel Export: " + e.getMessage());
        }

        // return "/pages/admin/excel_export_rechnungsausgang.jsf?faces-redirect=true";
        return "";
    }

    /**
     * Returns true if a poi export is defined
     * 
     * @return
     */
    public boolean hasPoiExport() {
        if (dataViewDefinition != null && !dataViewDefinition.getItemValueString("poi.targetfilename").isBlank()) {
            return true;
        }
        return false;
    }
}
