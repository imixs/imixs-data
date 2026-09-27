package org.imixs.workflow.dataview;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;

/**
 * The DataViewExportAdapter exports the dataset selected by the query of a
 * DataView definition into a csv file or an excel file.
 * <p>
 * In contrast to DataGroupExportAdapter, this adapter does not depend on any
 * DataGroup reference ($uniqueidref) - the dataset comes directly from the
 * query stored in the DataView definition.
 * <p>
 * Example:
 * 
 * <pre>
 * {@code
<imixs-data-view name="EXPORT">
    <type>CSV|POI</type>
    <dataview>invoices</dataview>
    <targetname>my-export.csv</targetname>
    <debug>true</debug>
</imixs-data-view>
 * }
 * </pre>
 *
 * @author Ralph Soika
 * @version 1.0
 */
public class DataViewExportAdapter implements SignalAdapter {

    private static Logger logger = Logger.getLogger(DataViewExportAdapter.class.getName());

    public static final String MODE_EXPORT = "export";

    @Inject
    private WorkflowService workflowService;

    @Inject
    private DocumentService documentService;

    @Inject
    private DataViewService dataViewService;

    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        long processingTime = System.currentTimeMillis();

        List<ItemCollection> exportDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-data-view",
                MODE_EXPORT, workitem, true);

        if (exportDefinitions != null) {
            for (ItemCollection groupDefinition : exportDefinitions) {
                exportWorkitemToDataView(workitem, groupDefinition);
            }
        }

        logger.info("├── ✅ completed (" + (System.currentTimeMillis() - processingTime) + "ms)");
        return workitem;
    }

    /**
     * Exports the dataset selected by the DataView query into csv or excel.
     */
    private void exportWorkitemToDataView(ItemCollection workitem, ItemCollection groupDefinition)
            throws AccessDeniedException, PluginException {
        boolean debug = groupDefinition.getItemValueBoolean("debug");
        String type = groupDefinition.getItemValueString("type").trim();
        String separator = groupDefinition.getItemValueString("separator");
        if (separator.isBlank()) {
            separator = ";";
        }
        String targetname = groupDefinition.getItemValueString("targetname").trim();
        String dataview = groupDefinition.getItemValueString("dataview").trim();

        logger.info("├── export dataview: " + type + " -> " + targetname);
        if (debug) {
            logger.info("│   ├── type=" + type);
            logger.info("│   ├── separator=" + separator);
            logger.info("│   ├── dataview='" + dataview + "'");
        }
        try {
            ItemCollection dataViewDefinition = dataViewService.loadDataViewDefinition(dataview);
            if (dataViewDefinition == null) {
                throw new PluginException(DataViewExportAdapter.class.getName(), DataViewService.ERROR_CONFIG,
                        "⚠️ Failed to load dataview - not defined!");
            }

            List<ItemCollection> viewItemDefinitions = dataViewService
                    .computeDataViewItemDefinitions(dataViewDefinition);

            // resolve <itemvalue> placeholders in the query against the current workitem
            String query = dataViewService.parseQuery(dataViewDefinition, workitem);
            String sortBy = dataViewDefinition.getItemValueString("sort.by");
            if (sortBy.isEmpty()) {
                sortBy = "$modified"; // default
            }
            boolean sortReverse = dataViewDefinition.getItemValueBoolean("sort.reverse");

            if (debug) {
                logger.info("│   ├── export data....");
            }
            if ("csv".equalsIgnoreCase(type)) {
                List<ItemCollection> data = documentService.find(query, DataViewService.MAX_ROWS, 0, sortBy,
                        sortReverse);
                byte[] fileRawData = dataViewService.exportCSV(data, viewItemDefinitions, separator);
                workitem.addFileData(new FileData(targetname, fileRawData, "application/text", null));
                logger.info("│   ├── ✅ export successful");
            } else if ("poi".equalsIgnoreCase(type)) {
                int totalCount = documentService.count(query);
                if (totalCount > DataViewService.MAX_ROWS) {
                    throw new PluginException(DataViewExportAdapter.class.getName(), DataViewService.ERROR_CONFIG,
                            "Data can not be exported into Excel because dataset exceeds "
                                    + DataViewService.MAX_ROWS + " rows!");
                }
                List<ItemCollection> data = documentService.find(query, DataViewService.MAX_ROWS, 0, sortBy,
                        sortReverse);
                FileData fileData = dataViewService.exportPOI(data, dataViewDefinition, viewItemDefinitions);

                // create a temp event to resolve the optional 'poi.update' workflow result
                ItemCollection tmpEvent = new ItemCollection().setItemValue("txtActivityResult",
                        dataViewDefinition.getItemValue("poi.update"));
                ItemCollection poiConfig = workflowService.evalWorkflowResult(tmpEvent, "poi-update",
                        dataViewDefinition, false);
                DataViewPOIHelper.poiUpdate(workitem, fileData, poiConfig, workflowService);

                workitem.addFileData(fileData);
                logger.info("│   ├── ✅ export successful");
            } else {
                logger.info("│   ├── ⚠️ export type '" + type + "' not supported!");
            }

        } catch (QueryException e) {
            logger.warning("├── ⚠️ Failed to export dataview: " + e.getMessage());
            throw new PluginException(DataViewExportAdapter.class.getName(), DataViewService.ERROR_CONFIG,
                    "⚠️ Failed to export dataview: " + e.getMessage(), e);
        }
    }
}