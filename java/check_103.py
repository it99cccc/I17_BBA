
import re
import sys

def check_report(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"File not found: {file_path}")
        return

    # Find 2021 section
    # The tabs switch display, but the content is all there in divs.
    # Look for <div id="y2021" ...> ... </div>
    # Note: ReportGenerator might put everything on one line or few lines.
    
    # Extract content for y2021
    match = re.search(r'<div id="y2021".*?>(.*?)</div>', content, re.DOTALL)
    if not match:
        # Try finding where it starts and ends roughly if regex fails on large content
        start = content.find('id="y2021"')
        if start == -1:
            print("No 2021 section found")
            return
        # It ends at the next <div id="y2022"> or end of file
        end = content.find('id="y2022"')
        if end == -1:
            end = len(content)
        section = content[start:end]
    else:
        section = match.group(1)

    print("Found 2021 section.")
    
    # Find "保险合同金融变动额(12)" row
    # The structure is likely: <tr><td>保险合同金融变动额(12)</td><td>val1</td><td>val2</td><td>val3</td><td>val4</td></tr>
    # Allow for attributes in td/tr
    
    row_pattern = re.compile(r'保险合同金融变动额\(12\).*?</tr>', re.DOTALL)
    row_match = row_pattern.search(section)
    if row_match:
        row_html = row_match.group(0)
        print(f"Row HTML: {row_html}")
        
        # Extract values
        # <td ...>value</td>
        tds = re.findall(r'<td.*?>(.*?)</td>', row_html)
        if len(tds) >= 4: # 0 is label, 1,2,3,4 are values
            print(f"Values: {tds[1:]}")
            # Remove html tags from values
            clean_values = [re.sub(r'<[^>]+>', '', v).strip() for v in tds[1:]]
            print(f"Clean Values: {clean_values}")
    else:
        print("Row '保险合同金融变动额(12)' not found in 2021 section.")

    # Also check IFIE_OCI (14)
    row_pattern_oci = re.compile(r'其他综合收益其他变动\(14\).*?</tr>', re.DOTALL)
    row_match_oci = row_pattern_oci.search(section)
    if row_match_oci:
        row_html = row_match_oci.group(0)
        # Extract values
        tds = re.findall(r'<td.*?>(.*?)</td>', row_html)
        if len(tds) >= 4:
            clean_values = [re.sub(r'<[^>]+>', '', v).strip() for v in tds[1:]]
            print(f"IFIE_OCI (14) Values: {clean_values}")

if __name__ == "__main__":
    check_report(r"D:\BBA_4\BBA_New-main\java\logs\report_103_group_QHPLIA2023ABBA301.html")
