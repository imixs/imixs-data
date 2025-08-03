# Imixs-Data Groups

Imixs Data groups are used to group workitems in a master process. For example you can group invoices to be exported into an external IT system in a new master group or you may want to payment information into a statement of account for a customer.

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
