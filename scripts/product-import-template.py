"""
Builds the Excel template the back office's import panel offers as a download.

    python scripts/product-import-template.py

writes apps/terminal/public/templates/storex-product-import-template.xlsx. Needs openpyxl.

The workbook is a *convenience in front of* the CSV import (M3-03), not a second format: the panel
still only reads CSV, and the shop saves the Products sheet as "CSV UTF-8". So everything here
exists to make that CSV come out the way `parseProductCsv` in @lumora/domain reads it:

- headers normalise to the parser's names ("VAT %" -> vat, "Tax Mode" -> taxmode);
- SKU and Barcode cells are Text, because a General cell turns a 13-digit EAN into 4.79E+12 and
  drops leading zeros, and the CSV then carries the mangled value;
- Price is 0.00 with no thousands separator, so Excel never writes a quoted "1,250.00";
- the example rows live on the "How to use" sheet, never on Products, because Excel exports only
  the active sheet and an example left on it would be imported as a real product.

Pass a second argument to put the example rows on the Products sheet as well. That is for
testing the CSV round trip, never for the shipped file.
"""
import sys
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.comments import Comment
from openpyxl.worksheet.datavalidation import DataValidation

DEFAULT_OUT = (
    Path(__file__).resolve().parent.parent
    / "apps/terminal/public/templates/storex-product-import-template.xlsx"
)
out = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_OUT)
fill_examples = len(sys.argv) > 2  # test mode: put example rows into the Products sheet

FONT = "Arial"
BLUE = "1DA1F2"
HEADER_FILL = PatternFill("solid", fgColor="0B5C8A")
INPUT_FILL = PatternFill("solid", fgColor="FFFDE7")
thin = Side(style="thin", color="D0D7DE")
BORDER = Border(left=thin, right=thin, top=thin, bottom=thin)
ROWS = 2000

# Header text normalises to the parser's names: "VAT %" -> vat, "Tax Mode" -> taxmode.
COLUMNS = [
    ("SKU", 18, "@", "REQUIRED. Your product code, e.g. RICE-SAMBA-5.\nMust be unique. Re-importing the same SKU updates that product."),
    ("Name", 36, "@", "REQUIRED. Product name as it prints on the receipt."),
    ("Price", 12, "0.00", "REQUIRED. Selling price in rupees, e.g. 2850 or 2850.00.\nNo 'Rs', no thousands commas."),
    ("VAT %", 9, "0.##", "VAT percentage, e.g. 18. Use 0 for zero-rated goods (rice, bread).\nA blank cell = 0%."),
    ("Tax Mode", 13, "@", "INCLUSIVE (price already contains VAT) or EXCLUSIVE.\nBlank = INCLUSIVE."),
    ("Category", 22, "@", "Optional. New category names are created automatically.\nCheck spelling: 'Bevarages' becomes a second category."),
    ("Barcode", 32, "@", "Optional. Scan or type the barcode.\nSeveral barcodes for one product: separate with |  e.g. 4791234567890|4791234567906"),
]

EXAMPLES = [
    ("RICE-SAMBA-5", "Samba Rice 5kg", 2850, 0, "INCLUSIVE", "Rice & Grains", "4791111100015"),
    ("MILK-PWD-400", "Full Cream Milk Powder 400g", 1150, 18, "INCLUSIVE", "Dairy", "4791111200012"),
    ("DRINK-COLA-15", "Cola 1.5L", 450, 18, "INCLUSIVE", "Beverages", "4791111300019|4791111300026"),
    ("BREAD-SAND", "Sandwich Bread, Large", 220, 0, "", "Bakery", ""),
]


def style_header(ws, row):
    for i, (title, width, _, note) in enumerate(COLUMNS, start=1):
        c = ws.cell(row=row, column=i, value=title)
        c.font = Font(name=FONT, bold=True, color="FFFFFF")
        c.fill = HEADER_FILL
        c.alignment = Alignment(horizontal="center", vertical="center")
        c.border = BORDER
        ws.column_dimensions[c.column_letter].width = width


wb = Workbook()

# ---------------------------------------------------------------- Products (the one you save as CSV)
ws = wb.active
ws.title = "Products"
style_header(ws, 1)
for i, (_, _, _, note) in enumerate(COLUMNS, start=1):
    cm = Comment(note, "StoreX")
    cm.width, cm.height = 320, 90
    ws.cell(row=1, column=i).comment = cm
ws.row_dimensions[1].height = 22
ws.freeze_panes = "A2"

for r in range(2, ROWS + 2):
    for i, (_, _, fmt, _) in enumerate(COLUMNS, start=1):
        c = ws.cell(row=r, column=i)
        c.number_format = fmt
        c.font = Font(name=FONT)

if fill_examples:
    for r, row in enumerate(EXAMPLES, start=2):
        for i, v in enumerate(row, start=1):
            ws.cell(row=r, column=i, value=v if v != "" else None)

rng = lambda col: f"{col}2:{col}{ROWS + 1}"
dv_price = DataValidation(type="decimal", operator="greaterThanOrEqual", formula1="0",
                          showErrorMessage=True, errorTitle="Price",
                          error="Price must be a number of 0 or more, e.g. 2850.00")
dv_price.add(rng("C"))
dv_vat = DataValidation(type="decimal", operator="between", formula1="0", formula2="100",
                        showErrorMessage=True, errorTitle="VAT %",
                        error="Write the percentage as a number: 18, not 18% or 1800.")
dv_vat.add(rng("D"))
dv_mode = DataValidation(type="list", formula1='"INCLUSIVE,EXCLUSIVE"', allow_blank=True,
                         showErrorMessage=True, errorTitle="Tax Mode",
                         error="Pick INCLUSIVE or EXCLUSIVE (or leave blank for INCLUSIVE).")
dv_mode.add(rng("E"))
dv_sku = DataValidation(type="custom", formula1="COUNTIF($A$2:$A$2001,A2)=1",
                        showErrorMessage=True, errorTitle="SKU",
                        error="This SKU is already used on another row. Each SKU may appear once.")
dv_sku.add(rng("A"))
for dv in (dv_price, dv_vat, dv_mode, dv_sku):
    ws.add_data_validation(dv)

# ---------------------------------------------------------------- How to use
g = wb.create_sheet("How to use")
g.column_dimensions["A"].width = 4
g.sheet_view.showGridLines = False
lines = [
    ("StoreX — product import template", "title"),
    ("", None),
    ("1.  Fill in the Products sheet, one product per row. Hover a header to see what it wants.", None),
    ("2.  Required: SKU, Name, Price. Everything else is optional.", None),
    ("3.  Go back to the Products sheet, then File > Save As > \"CSV UTF-8 (Comma delimited) (*.csv)\".", "bold"),
    ("     Excel only saves the sheet you are looking at, so Products must be the open sheet.", None),
    ("     When Excel warns that other sheets will be lost, click OK. Keep this .xlsx as your master copy.", None),
    ("4.  StoreX back office > Products > Import > CSV file > choose the .csv you just saved.", None),
    ("5.  Read the preview: it lists every new product, every price change and every new category.", None),
    ("     Press \"Import N products\" to save them.", None),
    ("     Nothing is saved until you confirm. If any row has a problem, the whole file is refused.", None),
    ("", None),
    ("Good to know", "head"),
    ("•  Re-importing an SKU that already exists UPDATES that product. It never creates a copy.", None),
    ("•  Products not in the file are left alone. Importing is not a delete.", None),
    ("•  SKU is required here, even though the Add Product screen can make one up for you.", None),
    ("•  Barcode cells are set to Text so Excel keeps all 13 digits (no 4.79E+12) and leading zeros.", None),
    ("     If you paste barcodes from elsewhere, use Paste Special > Values to keep the Text format.", None),
    ("•  Prices: no 'Rs' and no thousands commas. 2850 or 2850.00.", None),
    ("•  VAT: 18 means 18%. Leave the whole VAT column empty only if every product is zero-rated.", None),
    ("", None),
    ("Example — copy this layout, not these rows", "head"),
]
for r, (text, kind) in enumerate(lines, start=1):
    c = g.cell(row=r, column=2, value=text)
    if kind == "title":
        c.font = Font(name=FONT, bold=True, size=16, color="0B5C8A")
    elif kind == "head":
        c.font = Font(name=FONT, bold=True, size=12, color="0B5C8A")
    elif kind == "bold":
        c.font = Font(name=FONT, bold=True)
    else:
        c.font = Font(name=FONT)

start = len(lines) + 1
for i, (title, width, _, _) in enumerate(COLUMNS, start=2):
    c = g.cell(row=start, column=i, value=title)
    c.font = Font(name=FONT, bold=True, color="FFFFFF")
    c.fill = HEADER_FILL
    c.border = BORDER
    g.column_dimensions[c.column_letter].width = max(width, 12)
g.column_dimensions["B"].width = 18
for r, row in enumerate(EXAMPLES, start=start + 1):
    for i, v in enumerate(row, start=2):
        c = g.cell(row=r, column=i, value=v)
        c.font = Font(name=FONT, color="555555")
        c.fill = INPUT_FILL
        c.border = BORDER
        c.number_format = COLUMNS[i - 2][2]
g.cell(row=start + len(EXAMPLES) + 1, column=2,
       value="Row 3 shows two barcodes on one product. Row 4 shows a blank Tax Mode (= INCLUSIVE) and no barcode.").font = Font(name=FONT, italic=True, color="555555")

wb.active = 0
wb.save(out)
print("saved", out)
