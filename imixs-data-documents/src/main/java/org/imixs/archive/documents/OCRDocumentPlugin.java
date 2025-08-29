package org.imixs.archive.documents;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.documents.OCRDocumentService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

/**
 * The TikaPlugin extracts the textual information from document attachments.
 * The plug-in sends each new attached document to an instance of an Apache Tika
 * Server to get the file content.
 * <p>
 * The TikaPlugin can be used instead of the TIKA_SERVICE_MODE = 'auto' which
 * will react on the ProcessingEvent BEFORE_PROCESS. The plugin runs only in
 * case the TIKA_SERVICE_MODE is NOT set to 'auto'!
 * 
 * @see OCRDocumentService
 * @version 1.0
 * @author rsoika
 */
@Deprecated
public class OCRDocumentPlugin extends org.imixs.workflow.documents.OCRDocumentPlugin {

    private static Logger logger = Logger.getLogger(OCRDocumentPlugin.class.getName());

    /**
     * This method sends the document content to the tika server and updates the DMS
     * information.
     * 
     * 
     * @throws PluginException
     * @throws AdapterException
     */
    @Deprecated
    @Override
    public ItemCollection run(ItemCollection document, ItemCollection event) throws PluginException {
        logger.warning(
                "This OCRDocumentPlugin is deprecated and should be replaced with 'org.imixs.workflow.documents.OCRDocumentPlugin'");
        return super.run(document, event);
    }
}
