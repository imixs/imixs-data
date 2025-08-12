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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;
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
 * The DataGroupController provides a ViewController to display a list of
 * references to the current workflow group. The controller extends the
 * ViewController and supports pagination.
 * 
 * @author rsoika
 * @version 1.0
 */

@Named
@ConversationScoped
public class DataGroupViewController extends ViewController {
    private static final long serialVersionUID = 1L;

    public static final int MAX_SEARCH_RESULT = 1000;
    public static Logger logger = Logger.getLogger(DataGroupViewController.class.getName());

    @Inject
    protected WorkflowService workflowService;

    @Inject
    WorkflowController workflowController;

    @Inject
    private Conversation conversation;

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
        this.setLoadStubs(false);
        // select all references.....
        String uniqueId = workflowController.getWorkitem().getUniqueID();
        String query = "(";
        query = " (type:\"workitem\" OR type:\"workitemarchive\") AND (" + DataGroupService.ITEM_WORKITEMREF + ":\""
                + uniqueId + "\")";

        logger.fine("Query= " + query);
        return query;

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

}
