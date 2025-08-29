package org.imixs.archive.documents;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.documents.OCRDocumentService;
import org.imixs.workflow.exceptions.AdapterException;

/**
 * 
 * 
 * @see OCRDocumentService
 * @version 1.0
 * @author rsoika
 */
@Deprecated
public class OCRDocumentAdapter extends org.imixs.workflow.documents.OCRDocumentAdapter {

    private static Logger logger = Logger.getLogger(OCRDocumentAdapter.class.getName());

    /**
     * This method posts a text from an attachment to the Imixs-ML Analyse service
     * endpoint
     */
    @Deprecated
    @Override
    public ItemCollection execute(ItemCollection document, ItemCollection event) throws AdapterException {

        logger.warning(
                "This OCRDocumentAdapter is deprecated and should be replaced with 'org.imixs.workflow.documents.OCRDocumentAdapter'");
        return super.execute(document, event);
    }

}