# Imixs-Data Groups

An Imixs Data Group is an business process that is referenced by other processes within the same process instance.
Data groups allow you to organize and group related workitems under a master process.
For example, you want to summarize all payment transactions of a customer in a consolidated 'Statement of Account'.
Or you may want to group invoices that need to be exported into another IT system in an 'Export process'.

A business process references a data group via the item `$workitemref`, which makes it easy to access data groups via the core API from Imixs-Workflow.

## Build a Data Group

To build a data group just add the DataGroupAdapter to the corresponding event and setup a configuration. See the following example:

```xml
<imixs-data-group name="ADD">
    <query>(type:workitem) AND ($modelversion:sepa-export-manual*)</query>
    <init.model>sepa-export-manual-1.0</init.model>
    <init.task>1000</init.task>
    <init.event>10</init.event>
    <!-- Optional -->
    <update.event>20</update.event>

</imixs-data-group>
```

This will add the current workitem to a new data group of the workflow model 'sepa-export-manual-1.0' with the initial task 1000 and the initial event '10'. If a corresponding group already exists, the data group will be processed by the event 20 which is an optional functionality. The DataGroupService is using the given query to test if a corresponding data group already exists.

## Remove a Workitem from a Data Group

To remove a workitem from a data group you can use the following definition:

```xml
<imixs-data-group name="REMOVE">
    <query>(type:workitem) AND ($modelversion:sepa-export-manual*)</query>
    <!-- Optional -->
    <update.event>20</update.event>

</imixs-data-group>
```

This definition will remove the current workitem from a data group of the workflow model 'sepa-export-manual-1.0'. If a corresponding group exists, the data group will be processed by the event 20 which is an optional functionality. The DataGroupService is using the given query to test if a corresponding data group already exists.
