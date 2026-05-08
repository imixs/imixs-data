package org.imixs.workflow.documents;

import java.util.List;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.archive.core.SnapshotService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.WorkflowContext;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The OCRDocumentPlugin extracts textual information from document attachments
 * using the Tika OCR Service. The plugin can be added to a BPMN model to
 * activate text extraction model-wide, without requiring any additional
 * configuration on individual BPMN events.
 * <p>
 * The environment variable OCR_SERVICE_MODE must be set to 'model' to activate
 * this plugin.
 * <p>
 * <b>Default behavior (no event configuration):</b><br>
 * If no {@code <imixs-ocr>} tag is present in the BPMN event, the plugin
 * extracts text from all attachments using default settings. This is the
 * recommended usage — simply add the plugin to your model and it works out of
 * the box.
 * <p>
 * <b>Optional per-event configuration:</b><br>
 * For fine-grained control, the plugin can optionally be configured per BPMN
 * event using the {@code <imixs-ocr>} tag. Two modes are supported:
 * <ul>
 * <li><b>TIKA</b> – standard text extraction via the Tika {@code /tika}
 * endpoint</li>
 * <li><b>RMETA</b> – recursive extraction via the Tika {@code /rmeta/text}
 * endpoint, useful for e-mail files (.eml, .msg) containing embedded
 * documents</li>
 * </ul>
 * <p>
 * Example optional BPMN event configuration:
 *
 * <pre>
 * {@code
 * <imixs-ocr name="TIKA">
 *   <filepattern>(pdf|PDF|eml|msg)$</filepattern>
 *   <maxpdfpages>20</maxpdfpages>
 *   <option>X-Tika-PDFocrStrategy=OCR_ONLY</option>
 *   <option>X-Tika-PDFOcrImageType=RGB</option>
 *   <option>X-Tika-PDFOcrDPI=400</option>
 * </imixs-ocr>
 *
 * <imixs-ocr name="RMETA">
 *   <filepattern>(pdf|PDF|eml|msg)$</filepattern>
 *   <embeddingsPattern>(pdf|PDF)$</embeddingsPattern>
 *   <maxpdfpages>20</maxpdfpages>
 *   <option>X-Tika-PDFocrStrategy=OCR_ONLY</option>
 *   <option>X-Tika-PDFOcrImageType=RGB</option>
 *   <option>X-Tika-PDFOcrDPI=400</option>
 * </imixs-ocr>
 * }
 * </pre>
 *
 * @see OCRDocumentAdapter
 * @see OCRDocumentService
 * @version 2.0
 * @author rsoika
 */
public class OCRDocumentPlugin extends AbstractPlugin {

    public static final String OCR_ERROR = "OCR_ERROR";
    public static final String OCR_TIKA = "TIKA";
    public static final String OCR_RMETA = "RMETA";

    private static Logger logger = Logger.getLogger(OCRDocumentPlugin.class.getName());

    @Inject
    TikaService ocrService;

    @Inject
    @ConfigProperty(name = TikaService.ENV_OCR_SERVICE_MODE, defaultValue = "auto")
    String serviceMode;

    @Inject
    SnapshotService snapshotService;

    @Override
    public void init(WorkflowContext actx) throws PluginException {
        super.init(actx);
        logger.finest("...... service mode = " + serviceMode);
    }

    /**
     * Extracts text from document attachments.
     * <p>
     * If no {@code <imixs-ocr>} configuration is found in the BPMN event, the
     * plugin runs with default settings and processes all attachments. If an
     * {@code <imixs-ocr>} configuration is present, it is used for fine-grained
     * control (TIKA or RMETA mode).
     *
     * @throws PluginException
     */
    @SuppressWarnings("unchecked")
    @Override
    public ItemCollection run(ItemCollection document, ItemCollection event) throws PluginException {

        if (!"model".equalsIgnoreCase(serviceMode)) {
            logger.warning("├── ⚠️ Unexpected OCR_SERVICE_MODE=" + serviceMode
                    + " - running the OCRDocumentPlugin the env OCR_SERVICE_MODE should be set to 'model'. Plugin will be ignored!");
            return document;
        }

        try {
            List<ItemCollection> ocrTikaDefinitions = this.getWorkflowService().evalWorkflowResultXML(event,
                    "imixs-ocr", OCR_TIKA, document, false);
            List<ItemCollection> ocrRMetaDefinitions = this.getWorkflowService().evalWorkflowResultXML(event,
                    "imixs-ocr", OCR_RMETA, document, false);

            ItemCollection deprecatedTIkeDefinitions = this.getWorkflowService().evalWorkflowResult(event, "tika",
                    document,
                    false);

            if (ocrTikaDefinitions.isEmpty() && ocrRMetaDefinitions.isEmpty()) {
                // No event configuration present — run with defaults.
                // This is the standard use case: plugin is added to the model
                // without any per-event configuration.
                ocrService.extractText(document, snapshotService.findSnapshot(document),
                        null, null, null, 0);
                return document;
            }

            // Process TIKA mode
            for (ItemCollection ocrDefinition : ocrTikaDefinitions) {
                List<String> tikaOptions = ocrDefinition.getItemValue("option");
                String filePattern = ocrDefinition.getItemValueString("filepattern");
                int maxPdfPages = ocrDefinition.getItemValueInteger("maxpdfpages");

                ocrService.extractText(document, snapshotService.findSnapshot(document),
                        null, tikaOptions, filePattern, maxPdfPages);
            }

            // Process RMETA mode
            for (ItemCollection ocrDefinition : ocrRMetaDefinitions) {
                List<String> tikaOptions = ocrDefinition.getItemValue("option");
                String filePattern = ocrDefinition.getItemValueString("filepattern");
                String embeddingsPattern = ocrDefinition.getItemValueString("embeddingsPattern");
                int maxPdfPages = ocrDefinition.getItemValueInteger("maxpdfpages");

                ocrService.extractText(document, snapshotService.findSnapshot(document),
                        null, tikaOptions, filePattern, maxPdfPages, embeddingsPattern);
            }

            if (deprecatedTIkeDefinitions != null) {
                executeOldConfiguration(document, deprecatedTIkeDefinitions);
            }

        } catch (AdapterException e) {
            throw new PluginException(e);
        }

        return document;
    }

    /**
     * Fallback for deprecated BPMN configuration using the old {@code <tika>} tag
     * format:
     *
     * <pre>
     * {@code
     * <tika name="options">X-Tika-PDFocrStrategy=OCR_ONLY</tika>
     * <tika name="options">X-Tika-PDFOcrImageType=RGB</tika>
     * <tika name="options">X-Tika-PDFOcrDPI=400</tika>
     * }
     * </pre>
     *
     * @deprecated Use {@code <imixs-ocr>} tag format instead.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    private ItemCollection executeOldConfiguration(ItemCollection document, ItemCollection evalItemCollection)
            throws PluginException {

        logger.warning("├── ⚠️ Deprecated BPMN configuration - use <imixs-ocr> format!");

        try {

            if (evalItemCollection != null) {
                List<String> tikaOptions = evalItemCollection.getItemValue("options");
                String filePattern = evalItemCollection.getItemValueString("filepattern");
                int maxPdfPages = evalItemCollection.getItemValueInteger("maxpdfpages");

                ocrService.extractText(document, snapshotService.findSnapshot(document),
                        null, tikaOptions, filePattern, maxPdfPages);
            } else {
                // No configuration at all — run with defaults
                ocrService.extractText(document, snapshotService.findSnapshot(document),
                        null, null, null, 0);
            }
        } catch (AdapterException e) {
            throw new PluginException(e);
        }

        return document;
    }
}