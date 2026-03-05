# Imixs-Data

The _Imixs-Data_ project provides modules to import, export, extract, group and view data related to the [Imixs-Workflow](https://www.imixs.org) engine. All modules are based on Jakarta EE and integrate seamlessly into the Imixs-Workflow processing life cycle via CDI events and Signal Adapters.

## Modules

| Module                                                                                     | Description                                                     |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| [imixs-data-documents](https://github.com/imixs/imixs-data/tree/main/imixs-data-documents) | OCR text extraction from document attachments using Apache Tika |
| [imixs-data-groups](https://github.com/imixs/imixs-data/tree/main/imixs-data-groups)       | Group and organize related workitems under a master process     |
| [imixs-data-views](https://github.com/imixs/imixs-data/tree/main/imixs-data-views)         | Select, view and export data views                              |
| [imixs-data-importer](https://github.com/imixs/imixs-data/tree/main/imixs-data-importer)   | Import documents from external sources (FTP, IMAP, CSV)         |

---

## Imixs-Data-Documents

_Imixs-Data-Documents_ provides Plugins and Adapter classes to extract textual information from attached documents — including Optical character recognition (OCR) — during the processing life cycle of a workitem. The extracted text can be used for further processing, AI prompt enrichment or full-text search.

The text extraction is based on [Apache Tika](https://tika.apache.org/) and is controlled via BPMN model configuration. Two extraction modes are supported:

**TIKA** — standard mode, sends the document to the Tika `/tika` endpoint and returns the full extracted text as a single text block.

**RMETA** — recursive metadata mode, uses the Tika `/rmeta/text` endpoint to extract text from container files (e.g. `.eml`, `.msg`) with fine-grained control over which embedded documents (PDFs, ZIPs, etc.) are included. Technical or binary files like CAD files (`.step`) can be cleanly excluded even when nested inside ZIP archives.

→ [Full Documentation](https://github.com/imixs/imixs-data/tree/main/imixs-data-documents)

---

## Imixs-Data-Groups

_Imixs-Data-Groups_ provides services and adapters to group and organize related workitems under a master process. A data group is a business process that is referenced by other processes within the same process instance via the item `$workitemref`.

Typical use cases are consolidating payment transactions of a customer in a "Statement of Account", or grouping invoices that need to be exported into another IT system under an "Export process".

The `DataGroupAdapter` supports the following operations:

- **ADD** — add the current workitem to a data group (creates one if none exists)
- **REMOVE** — remove the current workitem from a data group
- **EXECUTE** — process all referred workitems with a given event in the same transaction
- **EXPORT** — export the data of a data group into a CSV or Excel file

→ [Full Documentation](https://github.com/imixs/imixs-data/tree/main/imixs-data-groups)

---

## Imixs-Data-Views

_Imixs-Data-Views_ provides methods to define, select and export data views. A data view is a reusable query and column definition that can be used across modules — for example the `DataGroupExportAdapter` uses data view definitions to produce structured exports.

→ [Full Documentation](https://github.com/imixs/imixs-data/tree/main/imixs-data-views)

---

## Imixs-Data-Importer

_Imixs-Data-Importer_ provides a generic scheduler-based import service to import documents from various external sources into the Imixs-Workflow processing life cycle. The service is based on the Imixs-Scheduler API and uses CDI events, making it highly extensible for custom import sources.

The following standard import sources are supported out of the box:

- **IMAP** — import documents from an email mailbox
- **FTP** — import documents from an FTP server
- **CSV** — import datasets from CSV files located on an FTP server

→ [Full Documentation](https://github.com/imixs/imixs-data/tree/main/imixs-data-importer)

---

## Maven Dependencies

Each module can be included independently:

```xml
<!-- OCR / Document Text Extraction -->
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-data-documents</artifactId>
    <scope>compile</scope>
</dependency>

<!-- Data Groups -->
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-data-groups</artifactId>
    <scope>compile</scope>
</dependency>

<!-- Data Views -->
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-data-views</artifactId>
    <scope>compile</scope>
</dependency>

<!-- Data Importer -->
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-data-importer</artifactId>
    <scope>compile</scope>
</dependency>
```
