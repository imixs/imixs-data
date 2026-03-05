# Imixs-Data-Documents

_Imixs-Data-Document_ is a sub-project of Imixs-Data. The project provides Services, Plugins and Adapter classes
to extract textual information from attached documents during the processing life cycle of a workitem.
This includes also Optical character recognition (OCR).
The extracted textual information can be used for further processing or to search for documents.

## Text Extraction

The text extraction is mainly based on the [Apache Tika Project](https://tika.apache.org/). The text extraction can be controlled based on a BPMN model
through the corresponding adapter or plug-in class. For a more general and model independent text extraction the OCRDocumentService can be used.

The textual information for each attachment is stored as a custom attribute named 'text' into the FileData object of a workitem. This information can be used by applications to analyse, verify or process textual information of any document type.

The following environment variable is mandatory:

- `OCR_SERVICE_ENDPOINT` - defines the Rest API end-point of an Apache Tika instance.

---

## The OCRDocumentAdapter

The Adapter class `org.imixs.workflow.documents.OCRDocumentAdapter` is a signal adapter which can be bound on a specific BPMN event element.
The environment variable `TIKA_SERVICE_MODE` must be set to `model` to activate the adapter.

### Configuration

The adapter class is configured in the BPMN event workflow result using the `<imixs-ocr>` tag. Two modes are supported: **TIKA** (standard) and **RMETA** (recursive extraction).

---

### Mode: TIKA (Standard)

The standard mode sends the document to the Tika `/tika` endpoint and adds the extracted text into the `$file` item of the workitem.

```xml
<imixs-ocr name="TIKA">
  <filepattern>(pdf|PDF|eml|msg)$</filepattern>
  <maxpdfpages>20</maxpdfpages>
  <option>X-Tika-PDFocrStrategy=OCR_ONLY</option>
  <option>X-Tika-PDFOcrImageType=RGB</option>
  <option>X-Tika-PDFOcrDPI=400</option>
</imixs-ocr>
```

**Parameters:**

| Parameter     | Description                                                |
| ------------- | ---------------------------------------------------------- |
| `filepattern` | Optional regex to filter attachments by filename           |
| `maxpdfpages` | Optional maximum number of pages to scan for PDF documents |
| `option`      | Optional Tika header options (must start with `X-Tika-`)   |

---

### Mode: RMETA (Recursive Metadata Extraction)

The `RMETA` mode uses the Tika `/rmeta/text` endpoint which returns a structured JSON response containing the text content of the container document and all embedded documents separately. This is particularly useful for e-mail files (`.eml`, `.msg`) that contain attachments such as PDFs, ZIP archives or technical files.

In contrast to the standard `TIKA` mode, the `RMETA` mode allows fine-grained control over which embedded documents are included in the extracted text via the `embeddingsPattern` parameter. Only embedded documents whose resource path matches the pattern are included. The mail body itself (container document, index 0) is always included.

```xml
<imixs-ocr name="RMETA">
  <filepattern>(pdf|PDF|eml|msg)$</filepattern>
  <embeddingsPattern>(pdf|PDF)$</embeddingsPattern>
  <maxpdfpages>20</maxpdfpages>
  <option>X-Tika-PDFocrStrategy=OCR_ONLY</option>
  <option>X-Tika-PDFOcrImageType=RGB</option>
  <option>X-Tika-PDFOcrDPI=400</option>
</imixs-ocr>
```

**Parameters:**

| Parameter           | Description                                                                             |
| ------------------- | --------------------------------------------------------------------------------------- |
| `filepattern`       | Optional regex to filter attachments by filename                                        |
| `embeddingsPattern` | Optional regex to filter embedded documents by their resource path (e.g. `(pdf\|PDF)$`) |
| `maxpdfpages`       | Optional maximum number of pages to scan for PDF documents                              |
| `option`            | Optional Tika header options (must start with `X-Tika-`)                                |

#### How RMETA Works

When Tika processes a container file (e.g. an `.eml` with attachments - including ZIP attachments), the `/rmeta/text` endpoint returns a JSON array where each element represents one document in the hierarchy:

- **Index 0** – the container document itself (e.g. the mail body) → always included
- **Index 1..n** – embedded documents (e.g. images, PDFs, files inside a ZIP) → included only if their resource path matches `embeddingsPattern`

Example: an `.eml` file containing a PDF and a ZIP archive with a PDF and a `.step` CAD file:

```
[0] /                               → mail body             ✅ always included
[1] /image001.jpg                   → inline image          ⛔ skipped
[2] /order.pdf                      → PDF attachment        ✅ matches (pdf|PDF)$
[3] /drawings.zip/part.pdf          → PDF inside ZIP        ✅ matches (pdf|PDF)$
[4] /drawings.zip/part.step         → CAD file inside ZIP   ⛔ skipped
[5] /drawings.zip                   → ZIP container itself  ⛔ skipped
```

This ensures that only relevant content reaches downstream processing (e.g. an AI prompt), while large binary or technical files are cleanly excluded — even when nested inside ZIP archives.

---

## The OCRDocumentPlugin

The Plugin class `org.imixs.workflow.documents.OCRDocumentPlugin` can be used as an alternative for the tika service mode 'auto'. The plugin extracts textual information from document attachments based on the model configuration. You need to add the plugin to your model to activate it.

    `org.imixs.workflow.documents.OCRDocumentPlugin`

The environment variable `TIKA_SERVICE_MODE` must be set to `model`.

---

## The OCRDocumentService

The `OCRDocumentService` is a general service to extract the textual information from file attachments during the processing life cycle independent from a BPMN model. The service reacts on the CDI event `BEFORE_PROCESS` and extracts the data automatically. The environment variable `OCR_SERVICE_MODE` must be set to `auto` to activate this service.
If set to `model` the `OCRDocumentPlugin` or the `OCRDocumentAdapter` must be used in a BPMN model to activate the text extraction.

The following optional environment settings are supported:

| Environment Setting    | Type    | Description                                               | Example               |
| ---------------------- | ------- | --------------------------------------------------------- | --------------------- |
| `OCR_SERVICE_ENDPOINT` | URL     | The Tika Endpoint URI                                     | http://tika:9998/tika |
| `OCR_STRATEGY`         | String  | NO_OCR, OCR_ONLY, OCR_AND_TEXT_EXTRACTION, AUTO (default) | NO_OCR                |
| `OCR_SERVICE_MODE`     | String  | Must be set to AUTO                                       | AUTO                  |
| `OCR_FILEPATTERN`      | String  | Optional Regex for a file pattern                         | `(pdf)$`              |
| `OCR_MAXPDFPAGES`      | Integer | Max PDF Pages to be scanned                               | 10                    |

---

## OCR

The _Optical character recognition (OCR)_ is based on the [Apache Project 'Tika'](https://tika.apache.org/).
Tika extracts text from over a thousand different file types including PDF and office documents and supports _Optical character recognition (OCR)_ based on the [Tesseract project](https://github.com/tesseract-ocr/tesseract).

To run a Tika Server with Docker, the [official Docker image](https://hub.docker.com/r/apache/tika) can be used:

    $ docker run -d -p 9998:9998 apache/tika:3.2.0.0-full

### The TikaService

The _TikaService_ EJB provides methods to extract textual information from documents attached to a workitem. A valid Tika Server endpoint must exist.

The following environment variables are supported:

- `OCR_SERVICE_ENDPOINT` - defines the Rest API end-point of the Tika server (mandatory).
- `OCR_SERVICE_MAXFILESIZE` - defines the maximum allowed file size in bytes (default is 5242880 = 5MB)
- `OCR_STRATEGY` - which strategy to use for OCR (AUTO|NO_OCR|OCR_AND_TEXT_EXTRACTION|OCR_ONLY)

#### OCR_SERVICE_ENDPOINT

The environment variable `OCR_SERVICE_ENDPOINT` must point to a valid tika service. If the endpoint does not include the `/tika` path segment it will be added automatically. The `/rmeta/text` endpoint is derived from this value automatically when using RMETA mode.

    OCR_SERVICE_ENDPOINT=http://tika:9998/tika

#### OCR Strategies

With the optional environment variable `OCR_STRATEGY` the behavior of how text is extracted from a PDF file can be controlled:

**AUTO** – The best OCR strategy is chosen by the Tika Server itself. This is the default setting.

**NO_OCR** – OCR processing is disabled and text is extracted only from PDF files including raw text. If a PDF file does not contain raw text data no text will be extracted.

**OCR_ONLY** – PDF files will always be OCR scanned even if the PDF file contains text data.

**OCR_AND_TEXT_EXTRACTION** – OCR processing and raw text extraction is performed. Note: This may result in a duplication of text and the mode is not recommended.

### Tika Options

By providing additional `X-Tika-*` options you can customize the Tika server behavior per BPMN event:

```xml
<option>X-Tika-PDFocrStrategy=OCR_AND_TEXT_EXTRACTION</option>
<option>X-Tika-PDFOcrImageType=RGB</option>
<option>X-Tika-PDFOcrDPI=72</option>
<option>X-Tika-OCRLanguage=eng+deu</option>
```

For more details about the Tika configuration see:

- https://cwiki.apache.org/confluence/display/TIKA/TikaServer
- https://cwiki.apache.org/confluence/display/TIKA/TikaOCR
- https://cwiki.apache.org/confluence/display/tika/PDFParser%20(Apache%20PDFBox)

---

## Searching Documents

All extracted textual information from attached documents is also searchable by the Imixs search index. The class `org.imixs.workflow.documents.DocumentIndexer` adds the OCR content for each file attachment into the search index.

---

## The e-Invoice Adapter

The Adapter class `org.imixs.workflow.documents.EInvoiceAutoAdapter` can be used to extract data from an e-invoice document. The `EInvoiceAutoAdapter` class automatically resolves all relevant e-invoice fields. The following fields are supported:

| Item              | Type   | Description                |
| ----------------- | ------ | -------------------------- |
| invoice.number    | text   | Invoice number             |
| invoice.date      | date   | Invoice date               |
| invoice.duedate   | date   | Invoice due date           |
| invoice.total     | double | Invoice total grant amount |
| invoice.total.net | double | Invoice total net amount   |
| invoice.total.tx  | double | Invoice total tax amount   |
| cdtr.name         | text   | Creditor name              |

The implementation of the EInvoice Adapter classes is based on the [Imixs e-invoice project](https://github.com/imixs/e-invoice).

### The e-Invoice MetaAdapter

The Adapter class `org.imixs.workflow.documents.EInvoiceMetaAdapter` can detect and extract content from e-invoice documents in different formats. This implementation is based on XPath expressions and can resolve custom fields not supported by the `EInvoiceAutoAdapter` class.

The detection outcome of the adapter is a new item named `einvoice.type` with the detected type of the e-invoice format. E.g:

- Factur-X/ZUGFeRD 2.0

The adapter can be configured in a BPMN event to extract e-invoice data fields:

```xml
<e-invoice name="ENTITY">
  <name>invoice.number</name>
  <xpath>//rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:ID</xpath>
</e-invoice>
<e-invoice name="ENTITY">
  <name>invoice.date</name>
  <type>date</type>
  <xpath>//rsm:ExchangedDocument/ram:IssueDateTime/udt:DateTimeString/text()</xpath>
</e-invoice>
<e-invoice name="ENTITY">
  <name>invoice.total</name>
  <type>double</type>
  <xpath>//ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:GrandTotalAmount</xpath>
</e-invoice>
<e-invoice name="ENTITY">
  <name>cdtr.name</name>
  <xpath>//ram:ApplicableHeaderTradeAgreement/ram:SellerTradeParty/ram:Name/text()</xpath>
</e-invoice>
```

If the type is not set the item value will be treated as a String. Possible types are `double` and `date`.
If the document is not an e-invoice no items and also the `einvoice.type` field will be set.

---

## How to Install

To include the imixs-data-documents plugins the following maven dependency can be added:

```xml
<!-- Imixs-Documents / Tika Service -->
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-data-documents</artifactId>
    <scope>compile</scope>
</dependency>
```
