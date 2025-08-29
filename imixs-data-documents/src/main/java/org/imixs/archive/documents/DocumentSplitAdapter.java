package org.imixs.archive.documents;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

/**
 *
 * @see DocumentSplitAdapter
 * @version 1.0
 * @author rsoika
 */
@Deprecated
public class DocumentSplitAdapter extends org.imixs.workflow.documents.DocumentSplitAdapter {

    private static Logger logger = Logger.getLogger(DocumentSplitAdapter.class.getName());

    /**
     * This method posts a text from an attachment to the Imixs-ML Analyse service
     * endpoint
     */
    @Deprecated
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {
        logger.warning(
                "This DocumentSplitAdapter is deprecated and should be replaced with 'org.imixs.workflow.documents.DocumentSplitAdapter'");
        return super.execute(workitem, event);

    }

}