# Imixs-Data View

An Imixs Data View presents workitems in a list. The data view definition describes the selector as well as the columns and their contents.

<img src="../doc/images/data-view-01.png" width=800 />

The selector can either be a lucene query or a reference to a [Imixs Data Group](../imixs-data-groups/README.md).

<img src="../doc/images/data-view-02.png" width=800 />

You can also define a export into a POI Format:

<img src="../doc/images/data-view-03.png" width=800 />

## Export a Data View

Data Views can also be computed and exported into a file during the processing-cycle. For this the signal adapter
`org.imixs.workflow.dataview.DataViewExportAdapter` can be called.

In contrast to the [DataGroupExportAdapter](../imixs-data-groups/README.md#export-a-data-group), which exports the workitems referenced by a Data Group (`$uniqueIdRef`), the `DataViewExportAdapter` selects its dataset directly from the query defined in the Data View itself. Use this adapter whenever you want to export a Data View that is **not** based on a Data Group.

**Example:**

```xml
<imixs-data-view name="EXPORT">
    <type>CSV|POI</type>
    <dataview>invoices</dataview>
    <targetname>my-export.csv</targetname>
   <debug>true</debug>
</imixs-data-view>
```

The tags `type`, `dataview`, `targetname` and `debug` behave exactly as described for the `DataGroupExportAdapter`. The export file is stored into the current workitem.

If your Data View selects the workitems referenced by a Data Group instead, use the `DataGroupExportAdapter` as described in the [Imixs-Data-Groups Project](https://github.com/imixs/imixs-data/tree/main/imixs-data-groups).
