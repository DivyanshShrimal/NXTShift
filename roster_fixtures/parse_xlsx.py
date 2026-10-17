import zipfile
import xml.etree.ElementTree as ET

path = 'C:/Users/shrim/Downloads/NXTShift_October_2026_Roster_Partially_Filled.xlsx'
with zipfile.ZipFile(path, 'r') as z:
    # Read shared strings
    shared_strings = []
    if 'xl/sharedStrings.xml' in z.namelist():
        tree = ET.fromstring(z.read('xl/sharedStrings.xml'))
        for si in tree.findall('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}si'):
            t = si.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t')
            shared_strings.append(t.text if t is not None else '')

    # Read sheet1.xml
    sheet_tree = ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
    for row in sheet_tree.findall('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}sheetData/{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row'):
        row_num = row.attrib.get('r')
        row_vals = []
        for c in row.findall('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}c'):
            cell_type = c.attrib.get('t')
            v = c.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}v')
            val = v.text if v is not None else ''
            if cell_type == 's' and val.isdigit():
                val = shared_strings[int(val)]
            row_vals.append((c.attrib.get('r'), val))
        if row_vals:
            print(f"Row {row_num}: {row_vals}")
