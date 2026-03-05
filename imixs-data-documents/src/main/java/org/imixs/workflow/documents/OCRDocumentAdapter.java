package org.imixs.workflow.documents;

import java.util.List;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.archive.core.SnapshotService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The OCRDocumentAdapter extracts text form attached file data objects using
 * the Tika OCR Service.
 * <p>
 * The adapter expect the following environment setting
 * 
 * TIKA_SERVICE_MODE: "MODEL"
 * 
 * You can set additional options to be passed to the Tika Service in a
 * corresponding BPMN configuration
 * 
 * <p>
 * 
 * <pre>
 * {@code
        <imixs-ocr name="TIKA">
          <filepattern>(pdf|PDF|eml|msg)$</filepattern>
          <maxpdfpages>20</maxpdfpages>
          <option>X-Tika-PDFocrStrategy=OCR_ONLY</options>
          <option>X-Tika-PDFOcrImageType=RGB</options>
          <option>X-Tika-PDFOcrDPI=400</options>
        </imixs-ocr>

        <imixs-ocr name="RMETA">
          <filepattern>(pdf|PDF|eml|msg)$</filepattern>
          <embeddingsPattern>(pdf|PDF)$</embeddingsPattern>
          <maxpdfpages>20</maxpdfpages>
          <option>X-Tika-PDFocrStrategy=OCR_ONLY</options>
          <option>X-Tika-PDFOcrImageType=RGB</options>
          <option>X-Tika-PDFOcrDPI=400</options>
        </imixs-ocr>         
   }
 * </pre>
 * 
 * @see OCRDocumentService
 * @version 2.0
 * @author rsoika
 */
public class OCRDocumentAdapter implements SignalAdapter {

    public static final String OCR_ERROR = "OCR_ERROR";
    public static final String OCR_TIKA = "TIKA";
    public static final String OCR_RMETA = "RMETA";

    private static Logger logger = Logger.getLogger(OCRDocumentAdapter.class.getName());

    @Inject
    @ConfigProperty(name = TikaService.ENV_OCR_SERVICE_MODE, defaultValue = "auto")
    String serviceMode;

    @Inject
    TikaService ocrService;

    @Inject
    WorkflowService workflowService;

    @Inject
    SnapshotService snapshotService;

    /**
     * This method posts a text from an attachment to the Imixs-ML Analyse service
     * endpoint
     */
    @SuppressWarnings("unchecked")
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event) throws AdapterException {
        boolean ocrDebug = false;
        List<String> tikaOptions = null;
        String filePattern = null;
        String embeddingsPattern = null;
        int maxPdfPages = 0;
        long processingTime = System.currentTimeMillis();

        if (!"model".equalsIgnoreCase(serviceMode)) {
            logger.info("├── ⚠️ Unexpected TIKA_SERVICE_MODE=" + serviceMode
                    + " - running the OCRDocumentAdapter the env TIKA_SERVICE_MODE should be set to 'model'. Adapter will be ignored!");
            logger.info("└── Running in Server Mode!");
            return workitem;
        }
        try {
            List<ItemCollection> ocrTikaDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ocr",
                    OCR_TIKA, workitem, false);
            List<ItemCollection> ocrRMetaDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ocr",
                    OCR_RMETA, workitem, false);

            // Support deprecated configuration
            if (ocrTikaDefinitions.size() == 0 && ocrRMetaDefinitions.size() == 0) {
                return executeOldConfiguration(workitem, event);
            }

            logger.info("├── Running OCR Adapter...");

            // Check TIKA mode
            for (ItemCollection ocrDefinition : ocrTikaDefinitions) {
                tikaOptions = ocrDefinition.getItemValue("option");
                filePattern = ocrDefinition.getItemValueString("filepattern");
                maxPdfPages = ocrDefinition.getItemValueInteger("maxpdfpages"); // only for pdf documents
                if ("true".equalsIgnoreCase(ocrDefinition.getItemValueString("debug"))) {
                    ocrDebug = true;
                }

                if (ocrDebug) {
                    logger.info("│   ├── mode=" + OCR_TIKA);
                    logger.info("│   ├── filePattern=" + filePattern);
                    logger.info("│   ├── maxPdfPages=" + maxPdfPages);
                    for (String option : tikaOptions) {
                        logger.info("│   ├── Tika-Option: " + option);
                    }
                }

                // extract text data....
                ocrService.extractText(workitem, snapshotService.findSnapshot(workitem), null, tikaOptions,
                        filePattern, maxPdfPages);
            }

            // Check RMETA mode

            for (ItemCollection ocrDefinition : ocrRMetaDefinitions) {
                tikaOptions = ocrDefinition.getItemValue("option");
                filePattern = ocrDefinition.getItemValueString("filepattern");
                embeddingsPattern = ocrDefinition.getItemValueString("embeddingsPattern");
                maxPdfPages = ocrDefinition.getItemValueInteger("maxpdfpages"); // only for pdf documents
                if ("true".equalsIgnoreCase(ocrDefinition.getItemValueString("debug"))) {
                    ocrDebug = true;
                }
                if (ocrDebug) {
                    logger.info("│   ├── mode=" + OCR_RMETA);
                    logger.info("│   ├── filePattern=" + filePattern);
                    logger.info("│   ├── embeddingsPattern=" + embeddingsPattern);
                    logger.info("│   ├── maxPdfPages=" + maxPdfPages);
                    for (String option : tikaOptions) {
                        logger.info("│   ├── Tika-Option: " + option);
                    }
                }

                // extract text data....
                ocrService.extractText(workitem, snapshotService.findSnapshot(workitem), null, tikaOptions,
                        filePattern, maxPdfPages, embeddingsPattern);
            }

        } catch (PluginException e) {
            String message = "OCR ERROR: TikaService - unable to extract text: " + e.getMessage();
            throw new AdapterException(e.getErrorContext(), e.getErrorCode(), message, e);
        } catch (RuntimeException e) {
            // we catch a runtimeException to avoid dead locks in the eventLog processing
            // issue #153
            String message = "OCR ERROR: TikaService - unable to extract text: " + e.getMessage();
            throw new AdapterException(OCRDocumentAdapter.class.getSimpleName(), OCR_ERROR, message, e);
        }

        if (ocrDebug) {
            logger.info("└── OCRDocumentAdapter completed in " + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return workitem;
    }

    /**
     * Old execute method reading the old config format
     * 
     * <pre>
     * {@code
            <tika name="options">X-Tika-PDFocrStrategy=OCR_ONLY</tika>
            <tika name="options">X-Tika-PDFOcrImageType=RGB</tika>
            <tika name="options">X-Tika-PDFOcrDPI=400</tika>
       }
     * </pre>
     * 
     * @param document
     * @param event
     * @return
     * @throws AdapterException
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public ItemCollection executeOldConfiguration(ItemCollection document, ItemCollection event)
            throws AdapterException {

        if ("model".equalsIgnoreCase(serviceMode)) {
            try {
                List<String> tikaOptions = null;
                String filePattern = null;
                String embeddingsPattern = null;
                int maxPdfPages = 0;
                // read opitonal tika options
                ItemCollection evalItemCollection = workflowService.evalWorkflowResult(event, "tika", document, false);
                if (evalItemCollection != null) {
                    logger.warning("├── ⚠️ Deprecated BPMN configuration use <imixs-ocr> format!");
                    tikaOptions = evalItemCollection.getItemValue("options");
                    filePattern = evalItemCollection.getItemValueString("filepattern");
                    embeddingsPattern = evalItemCollection.getItemValueString("embeddingsPattern");
                    maxPdfPages = evalItemCollection.getItemValueInteger("maxpdfpages"); // only for pdf documents
                }
                // extract text data....
                ocrService.extractText(document, snapshotService.findSnapshot(document), null, tikaOptions,
                        filePattern, maxPdfPages, embeddingsPattern);
            } catch (PluginException e) {
                String message = "Tika OCRService - unable to extract text: " + e.getMessage();
                throw new AdapterException(e.getErrorContext(), e.getErrorCode(), message, e);
            } catch (RuntimeException e) {
                // we catch a runtimeException to avoid dead locks in the eventLog processing
                // issue #153
                String message = "Tika OCRService - unable to extract text: " + e.getMessage();
                throw new AdapterException(OCRDocumentAdapter.class.getSimpleName(), OCR_ERROR, message, e);
            }
        }

        return document;
    }

}