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
package org.imixs.workflow.dataview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;
import org.imixs.workflow.faces.data.ViewController;
import org.imixs.workflow.faces.data.ViewHandler;
import org.imixs.workflow.faces.data.WorkflowController;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Conversation;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The DataViewController is used to display a data view
 * <p>
 * The controller uses the uniqueId from the URL to load the definition. The
 * bean reads optional cached query data form a session scoped cache EJB and
 * reloads the last state. This is useful for situations where the user
 * navigates to a new page (e.g. open a workitem) and late uses browser history
 * back.
 * <p>
 * 
 * 
 * @author rsoika
 * @version 1.0
 */
@Named
@ConversationScoped
public class DataViewController extends ViewController {

    private static final long serialVersionUID = 1L;

    protected List<ItemCollection> viewItemDefinitions = null;
    protected ItemCollection dataViewDefinition = null;
    protected ItemCollection filter;
    protected String query;
    protected String errorMessage;

    @Inject
    protected DataViewCache dataViewCache;

    @Inject
    protected Conversation conversation;

    @Inject
    protected DocumentService documentService;

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected WorkflowController workflowController;

    @Inject
    protected DataViewService dataViewService;

    @Inject
    protected ViewHandler viewHandler;

    @Inject
    protected DataViewDefinitionController dataViewDefinitionController;

    private String lastDataViewName = null;

    private static Logger logger = Logger.getLogger(DataViewController.class.getName());

    @Override
    @PostConstruct
    public void init() {
        super.init();
        // this.setQuery(dataViewController.getQuery());
        this.setSortBy("$modified");
        this.setSortReverse(false);
        this.setPageSize(100);
        this.setLoadStubs(false);
    }

    /**
     * This method loads the form information and prefetches the data
     */
    public void onLoad() {
        String uniqueid = null;

        // Important: start a new conversation because of the usage of the
        // CustomFormController!
        startConversation();

        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!facesContext.isPostback() && !facesContext.isValidationFailed()) {
            // ...
            FacesContext fc = FacesContext.getCurrentInstance();
            Map<String, String> paramMap = fc.getExternalContext().getRequestParameterMap();
            // try to extract the uniqueid form the query string...
            uniqueid = paramMap.get("id");
            if (uniqueid == null || uniqueid.isEmpty()) {
                // alternative 'workitem=...'
                uniqueid = paramMap.get("workitem");
            }
            dataViewDefinition = documentService.load(uniqueid);
        }

        if (uniqueid != null && !uniqueid.isEmpty()) {
            filter = dataViewCache.get(uniqueid);
        } else {
            filter = new ItemCollection();
        }

        try {
            // Init new Filter....
            if (dataViewDefinition != null) {
                filter.setItemValue("txtWorkflowEditorCustomForm",
                        dataViewDefinition.getItemValue("form"));
                filter.setItemValue("name",
                        dataViewDefinition.getItemValueString("name"));
                filter.setItemValue("description",
                        dataViewDefinition.getItemValueString("description"));
                viewItemDefinitions = dataViewService.computeDataViewItemDefinitions(dataViewDefinition);

                // Update View Handler settings
                String sortBy = dataViewDefinition.getItemValueString("sort.by");
                if (sortBy.isEmpty()) {
                    sortBy = "$modified";
                }
                this.setSortBy(sortBy);

                this.setSortReverse(dataViewDefinition.getItemValueBoolean("sort.reverse"));
                this.setPageIndex(filter.getItemValueInteger("pageIndex"));
                // prefetch data
                this.run();
                // if (!filter.getItemValueString("query").isEmpty()) {
                // query = filter.getItemValueString("query");
                // // Prefetch data to update total count and page count
                // viewHandler.getData(this);
                // }
            }
        } catch (QueryException | PluginException e) {
            logger.warning("Failed to load dataview definition: " + e.getMessage());
        }

    }

    public ItemCollection getDataViewDefinition() {
        return dataViewDefinition;
    }

    public List<ItemCollection> getViewItemDefinitions() {
        return viewItemDefinitions;
    }

    /**
     * Loads a dataView Item Definition by name
     * 
     * @param dataView - name of the dataview
     * @return
     */
    public List<ItemCollection> getViewItemDefinitions(String dataView) {

        // Caching
        if (viewItemDefinitions == null || !Objects.equals(dataView, lastDataViewName)) {
            // load data view definition
            lastDataViewName = dataView;
            dataViewDefinition = dataViewService.loadDataViewDefinition(dataView);
            if (dataViewDefinition != null) {
                viewItemDefinitions = dataViewService.computeDataViewItemDefinitions(dataViewDefinition);
            }

        }
        return viewItemDefinitions;
    }

    public ItemCollection getFilter() {
        return filter;
    }

    public void setFilter(ItemCollection filter) {
        this.filter = filter;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * This helper method builds a query from the query definition and the current
     * filter criteria.
     * 
     * The method loads the query form the definition and replaces all {<itemname>}
     * elements with the values from the filter
     * 
     *
     * @throws QueryException
     */
    public void run() throws PluginException, QueryException {
        reset();
        query = dataViewService.parseQuery(dataViewDefinition, filter);
        filter.setItemValue("query", query);
        // Prefetch data to update total count and page count
        viewHandler.getData(this);
        // cache filter
        dataViewCache.put(dataViewDefinition.getUniqueID(), filter);
    }

    /**
     * Returns the current query
     * 
     * @return
     */
    @Override
    public String getQuery() {
        return query;
    }

    /**
     * Overwrites ViewController method to cach Query Exceptions
     * 
     * @return view result
     * @throws QueryException
     */
    public List<ItemCollection> loadData() throws QueryException {
        List<ItemCollection> result = null;
        try {
            result = super.loadData();
        } catch (QueryException e) {
            // just print a warning - result is empty!
            logger.warning("Invalid Query: " + e.getMessage());
            result = new ArrayList<>();
        }
        return result;

    }

    /**
     * This method navigates back in the page index and caches the current page
     * index
     */
    public void back() {
        viewHandler.back(this);
        filter.setItemValue("pageIndex", this.getPageIndex());
    }

    /**
     * This method navigates forward in the page index and caches the current page
     * index
     */
    public void forward() {
        viewHandler.forward(this);
        filter.setItemValue("pageIndex", this.getPageIndex());
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
     * Exports data into a excel template processed by apache-poi. The method sends
     * a DataViewExport event to allow clients to adapt the export process.
     * 
     * @see DataViewExportEvent
     *
     * @throws PluginException
     * @throws QueryException
     */
    public String export() throws PluginException, QueryException {

        // build query and prepare dataset
        run();

        // Build target filename
        boolean debug = dataViewDefinition.getItemValueBoolean("debug");
        FileData fileDataExport = null;
        try {
            // test if query exceeds max count
            int totalCount = documentService.count(query);
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
            List<ItemCollection> workitems = documentService.find(query, DataViewService.MAX_ROWS, 0, sortBy,
                    dataViewDefinition.getItemValueBoolean("sort.reverse"));

            fileDataExport = dataViewService.poiExport(workitems, dataViewDefinition, viewItemDefinitions);

            // create a temp event
            ItemCollection event = new ItemCollection().setItemValue("txtActivityResult",
                    dataViewDefinition.getItemValue("poi.update"));
            ItemCollection poiConfig = workflowService.evalWorkflowResult(event, "poi-update", dataViewDefinition,
                    false);

            // merge workitem fields (Workaround because custom forms did hard coded map to
            // workflowController instead of workitem
            filter.copy(workflowController.getWorkitem());
            DataViewPOIHelper.poiUpdate(filter, fileDataExport, poiConfig, workflowService);

            // Build target Filename

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

}
