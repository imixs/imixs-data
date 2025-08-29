# Imixs-Data

The Imixs-Data project provides modules to import, export extract, group and view data related to the Imixs-Workflow engine.

**Note:** The Imixs-Archive modules [Imixs-Archive-Importer](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-importer), [Imixs-Archive-Exporter](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-exporter) and [Imixs-Archive-Documents](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-documetns) will be migrated in the near future into this new master project.

- **Imixs-Data-Groups** - a library providing methods to group workitems in a master workflow
- **Imixs-Data-Views** - a library providing methods to select view and export data views
- **Imixs-Data-Documents** - a library providing various OCR methods
- **Imixs-Data-Importer** - a library import data

### Imixs-Archive-Documents

_Imixs-Archive-Documents_ provides Plugins and Adapter classes to extract textual information from attached documents - including Optical character recognition - during the processing life cycle of a workitem. This information can be used for further processing or to search for documents.

[Imixs-Archive-Documents](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-documents)

The `OCRDocumentService` provides a service component to extract textual information from documents attached to a Workitem. The text extraction is based on [Apache Tika](https://tika.apache.org/).

[Imixs-Archive-OCR](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-ocr)

### Imixs-Archive-Importer

_Imixs-Archive-Importer_ provides a generic import service to be used to import documents form various external sources like a FTP server or a IMAP account.

[Imixs-Archive-Importer](https://github.com/imixs/imixs-archive/tree/master/imixs-archive-importer)

The `CSVImportService` also provides a generic function to import datasets from CSV files located on a FTP server.
