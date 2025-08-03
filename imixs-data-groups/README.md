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
    <event.maxcount>50</event.maxcount>
    <maxcount>50</maxcount>
</imixs-data-group>
```

This will add the current workitem to a new data group of the workflow model 'sepa-export-manual-1.0' with the initial task 1000 and the initial event '10'. If a corresponding group already exists, the workflow group will be processed by the event 20 which is a optional functionality. The DataGroupService is using the given query to test if a corresponding data group already exists.
