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
package org.imixs.workflow.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import javax.naming.NamingException;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ItemCollectionComparator;
import org.imixs.workflow.WorkflowKernel;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.index.SchemaService;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;

/**
 * The WorkitemLinkController provides suggest-box behavior based on the JSF 2.0
 * ajax capability to add WorkItem references to the current WorkItem.
 * 
 * All WorkItem references will be stored in the property '$workitemref'
 * Note: @RequestScoped did not work because the ajax request will reset the
 * result during submit
 * 
 * 
 * 
 * @author rsoika
 * @version 1.0
 */

@Named
@RequestScoped
public class DataGroupController implements Serializable {

    public static final int MAX_SEARCH_RESULT = 1000;
    public static Logger logger = Logger.getLogger(DataGroupController.class.getName());

    // search and lookups
    private Map<Integer, List<ItemCollection>> searchCache = null;

    private Map<Integer, List<ItemCollection>> referencesCache = null;
    private List<ItemCollection> searchResult = null;

    @EJB
    protected WorkflowService workflowService;

    @EJB
    protected SchemaService schemaService;

    private static final long serialVersionUID = 1L;

    public DataGroupController() {
        super();
        searchResult = new ArrayList<ItemCollection>();
        searchCache = new HashMap<Integer, List<ItemCollection>>();
        referencesCache = new HashMap<Integer, List<ItemCollection>>();
    }

    /**
     * This method searches a text phrase within the user profile objects
     * (type=profile).
     * <p>
     * JSF Integration:
     * 
     * {@code 
     * 
     * <h:commandScript name="imixsOfficeWorkflow.mlSearch" action=
     * "#{cargosoftController.search()}" rendered="#{cargosoftController!=null}"
     * render= "cargosoft-results" /> }
     * 
     * <p>
     * JavaScript Example:
     * 
     * <pre>
     * {@code
     *  imixsOfficeWorkflow.cargosoftSearch({ item: '_invoicenumber' })
     *  }
     * </pre>
     * 
     */
    public void searchWorkitems() {
        // get the param from faces context....
        FacesContext fc = FacesContext.getCurrentInstance();
        String _phrase = fc.getExternalContext().getRequestParameterMap().get("phrase");
        String options = fc.getExternalContext().getRequestParameterMap().get("options");
        if (_phrase == null) {
            return;
        }

        logger.finest("......workitemLink search prase '" + _phrase + "'  options=" + options);
        searchWorkitems(_phrase, options);

    }

    /**
     * This method returns a list of profile ItemCollections matching the search
     * phrase. The search statement includes the items 'txtName', 'txtEmail' and
     * 'txtUserName'. The result list is sorted by txtUserName
     * 
     * @param phrase - search phrase
     * @return - list of matching profiles
     */
    public List<ItemCollection> searchWorkitems(String phrase, String filter) {
        int searchHash = computeSearchHash(phrase, filter);
        searchResult = searchCache.get(searchHash);
        if (searchResult != null) {
            return searchResult;
        }

        logger.finest(".......search workitem links : " + phrase);
        if (phrase == null || phrase.isEmpty()) {
            searchResult = new ArrayList<ItemCollection>();
            searchCache.put(searchHash, searchResult);
            return searchResult;
        }

        // start lucene search
        searchResult = new ArrayList<ItemCollection>();
        Collection<ItemCollection> col = null;
        String sQuery = "";
        try {
            phrase = phrase.trim().toLowerCase();
            phrase = schemaService.escapeSearchTerm(phrase);
            // issue #170
            phrase = schemaService.normalizeSearchTerm(phrase);

            // search only type workitem and workitemsarchive
            sQuery += "((type:workitem) OR (type:workitemarchive)) AND  (*" + phrase + "*)";

            if (filter != null && !"".equals(filter.trim())) {
                String sNewFilter = filter.trim().replace(".", "?");
                sQuery += " AND (" + sNewFilter + ") ";
            }
            logger.finest("......query=" + sQuery);

            col = workflowService.getDocumentService().find(sQuery, MAX_SEARCH_RESULT, 0);

            if (col != null) {
                for (ItemCollection aWorkitem : col) {
                    searchResult.add(cloneWorkitem(aWorkitem));
                }
                // sort by $workflowabstract..
                Collections.sort(searchResult, new ItemCollectionComparator("$workflowabstract", true));
            }

        } catch (Exception e) {
            logger.warning("Search error query = '" + sQuery + "'  - " + e.getMessage());
        }

        searchCache.put(searchHash, searchResult);
        return searchResult;

    }

    public List<ItemCollection> getSearchResult() {
        return searchResult;
    }

    /**
     * Helper method compute a hash from a phrase and a filter rule
     */
    public static int computeSearchHash(String _phrase, String _filter) {
        int hash = 0;

        if (_phrase != null && !_phrase.isEmpty()) {
            hash = Objects.hash(hash, _phrase);
        }
        if (_filter != null && !_filter.isEmpty()) {
            hash = Objects.hash(hash, _filter);
        }

        return hash;
    }

    /**
     * Returns a list of all workItems holding a reference to the current workItem.
     * 
     * @return
     * @throws NamingException
     */
    public List<ItemCollection> getReferences(String uniqueId) {
        return getReferences(uniqueId, "");
    }

    /**
     * returns a list of all workItems holding a reference to the current workItem.
     * If the filter is set the processID will be tested for the filter regex
     * 
     * @return
     * @param filter
     * @throws NamingException
     */
    public List<ItemCollection> getReferences(String uniqueId, String filter) {
        List<ItemCollection> result = new ArrayList<ItemCollection>();

        // return an empty list if still no $uniqueid is defined for the
        // current workitem
        if ("".equals(uniqueId)) {
            return result;
        }

        int searchHash = computeSearchHash(uniqueId, filter);
        result = referencesCache.get(searchHash);
        if (result != null) {
            return result;
        }

        // select all references.....
        String sQuery = "(";
        sQuery = " (type:\"workitem\" OR type:\"workitemarchive\") AND (" + DataGroupService.ITEM_WORKITEMREF + ":\""
                + uniqueId + "\")";

        List<ItemCollection> workitems = null;

        try {
            workitems = workflowService.getDocumentService().findStubs(sQuery, MAX_SEARCH_RESULT, 0,
                    WorkflowKernel.LASTEVENTDATE, true);
        } catch (QueryException e) {

            e.printStackTrace();
        }
        // sort by modified
        Collections.sort(workitems, new ItemCollectionComparator("$created", true));
        result = new ArrayList<ItemCollection>();
        // now test if filter matches, and clone the workItem
        if (filter != null && !filter.isEmpty()) {
            for (ItemCollection itemcol : workitems) {
                // test
                if (matchesWorkitem(itemcol, filter)) {
                    result.add(itemcol);
                }
            }
        } else {
            result.addAll(workitems);
        }

        referencesCache.put(searchHash, result);
        return result;

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
     * This method clones the given workItem with a minimum of attributes.
     * 
     * @param aWorkitem
     * @return
     */
    public static ItemCollection cloneWorkitem(ItemCollection aWorkitem) {
        ItemCollection clone = new ItemCollection();

        // clone the standard WorkItem properties
        clone.replaceItemValue("Type", aWorkitem.getItemValue("Type"));
        clone.replaceItemValue("$UniqueID", aWorkitem.getItemValue("$UniqueID"));
        clone.replaceItemValue("$UniqueIDRef", aWorkitem.getItemValue("$UniqueIDRef"));
        clone.replaceItemValue("$ModelVersion", aWorkitem.getItemValue("$ModelVersion"));
        clone.replaceItemValue("$ProcessID", aWorkitem.getItemValue("$ProcessID"));
        clone.replaceItemValue("$Created", aWorkitem.getItemValue("$Created"));
        clone.replaceItemValue("$Modified", aWorkitem.getItemValue("$Modified"));
        clone.replaceItemValue("$isAuthor", aWorkitem.getItemValue("$isAuthor"));
        clone.replaceItemValue("$creator", aWorkitem.getItemValue("$creator"));
        clone.replaceItemValue("$editor", aWorkitem.getItemValue("$editor"));

        clone.replaceItemValue("$TaskID", aWorkitem.getItemValue("$TaskID"));
        clone.replaceItemValue("$EventID", aWorkitem.getItemValue("$EventID"));
        clone.replaceItemValue("$workflowGroup", aWorkitem.getItemValue("$workflowGroup"));
        clone.replaceItemValue("$workflowStatus", aWorkitem.getItemValue("$workflowStatus"));
        clone.replaceItemValue("$lastTask", aWorkitem.getItemValue("$lastTask"));
        clone.replaceItemValue("$lastEvent", aWorkitem.getItemValue("$lastEvent"));
        clone.replaceItemValue("$lastEventDate", aWorkitem.getItemValue("$lastEventDate"));
        clone.replaceItemValue("$eventLog", aWorkitem.getItemValue("$eventLog"));
        clone.replaceItemValue("$lasteditor", aWorkitem.getItemValue("$lasteditor"));

        clone.replaceItemValue("txtName", aWorkitem.getItemValue("txtName"));

        clone.replaceItemValue("$WorkflowStatus", aWorkitem.getItemValue("$WorkflowStatus"));
        clone.replaceItemValue("$WorkflowGroup", aWorkitem.getItemValue("$WorkflowGroup"));
        clone.replaceItemValue("namCreator", aWorkitem.getItemValue("namCreator"));
        clone.replaceItemValue("namCurrentEditor", aWorkitem.getItemValue("namCurrentEditor"));
        clone.replaceItemValue("$Owner", aWorkitem.getItemValue("$Owner"));
        clone.replaceItemValue("namOwner", aWorkitem.getItemValue("namOwner"));
        clone.replaceItemValue("namTeam", aWorkitem.getItemValue("namTeam"));
        clone.replaceItemValue("namManager", aWorkitem.getItemValue("namManager"));
        clone.replaceItemValue("namassist", aWorkitem.getItemValue("namassist"));

        if (aWorkitem.getType().startsWith("space")) {
            cloneWorkitemByPraefix("space", aWorkitem, clone);
        }
        if (aWorkitem.getType().startsWith("process")) {
            cloneWorkitemByPraefix("process", aWorkitem, clone);
        }

        clone.replaceItemValue("$workflowsummary", aWorkitem.getItemValue("$workflowsummary"));
        clone.replaceItemValue("$WorkflowAbstract", aWorkitem.getItemValue("$WorkflowAbstract"));

        if (aWorkitem.hasItem("Name"))
            clone.replaceItemValue("Name", aWorkitem.getItemValue("Name"));

        if (aWorkitem.hasItem("sequencenumber"))
            clone.replaceItemValue("sequencenumber", aWorkitem.getItemValue("sequencenumber"));

        return clone;

    }

    /**
     * Clones all items by a given praefix
     * 
     * @param string
     * @param aWorkitem
     * @param clone
     */
    private static void cloneWorkitemByPraefix(String praefix, ItemCollection aWorkitem, ItemCollection clone) {
        List<String> itemNames = aWorkitem.getItemNames();
        String itempraefix = praefix + ".";
        for (String itemName : itemNames) {
            if (itemName.startsWith(itempraefix)) {
                clone.replaceItemValue(itemName, aWorkitem.getItemValue(itemName));
            }
        }

    }

    /**
     * This method tests if a given WorkItem matches a filter expression. The
     * expression is expected in a column separated list of reg expressions for
     * Multiple properties. - e.g.:
     * 
     * <code>(txtWorkflowGroup:Invoice)($ProcessID:1...)</code>
     * 
     * @param workitem - workItem to be tested
     * @param filter   - combined regex to test different fields
     * @return - true if filter matches filter expression.
     */
    public static boolean matchesWorkitem(ItemCollection workitem, String filter) {

        if (filter == null || "".equals(filter.trim()))
            return true;

        // split columns
        StringTokenizer regexTokens = new StringTokenizer(filter, ")");
        while (regexTokens.hasMoreElements()) {
            String regEx = regexTokens.nextToken();
            // remove columns
            regEx = regEx.replace("(", "");
            regEx = regEx.replace(")", "");
            regEx = regEx.replace(",", "");
            // test if ':' found
            if (regEx.indexOf(':') > -1) {
                regEx = regEx.trim();
                // test if regEx contains "
                regEx = regEx.replace("\"", "");
                String itemName = regEx.substring(0, regEx.indexOf(':'));
                regEx = regEx.substring(regEx.indexOf(':') + 1);
                @SuppressWarnings("unchecked")
                List<Object> itemValues = workitem.getItemValue(itemName);
                for (Object aValue : itemValues) {
                    if (!aValue.toString().matches(regEx)) {
                        logger.fine("Value '" + aValue + "' did not match : " + regEx);
                        return false;
                    }
                }

            }
        }
        // workitem matches criteria
        return true;
    }
}
