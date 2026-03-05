import re

file_path = r"d:\BBA_4\BBA_New-main\java\logs\report_103_group_QHPLIA2023ABBA301.html"

try:
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 提取 2021 年的数据块
    # 假设 id="y2021" 开始，到 id="y2022" 结束
    match_2021 = re.search(r'id="y2021"(.*?)id="y2022"', content, re.DOTALL)
    if not match_2021:
        # 可能是最后一年
        match_2021 = re.search(r'id="y2021"(.*)', content, re.DOTALL)

    if match_2021:
        data_2021 = match_2021.group(1)
        print("Found 2021 data block.")
        
        # 查找保险合同金融变动额
        # 模式可能是：<td>保险合同金融变动额(12)</td>...<td>194.50</td>...
        # 因为 HTML 被压缩，可能有换行也可能没有
        
        # 尝试查找 IFIE 相关的行
        ifie_pattern = re.compile(r'保险合同金融变动额.*?<td.*?>(.*?)</td>.*?<td.*?>(.*?)</td>', re.DOTALL)
        ifie_match = ifie_pattern.search(data_2021)
        
        if ifie_match:
            print(f"IFIE Row found: Non-LC={ifie_match.group(1)}, LC={ifie_match.group(2)}")
        else:
            print("IFIE Row NOT found in 2021 block.")
            
        # 查找 OCI
        oci_pattern = re.compile(r'其他综合收益其他变动.*?<td.*?>(.*?)</td>.*?<td.*?>(.*?)</td>', re.DOTALL)
        oci_match = oci_pattern.search(data_2021)
        if oci_match:
            print(f"OCI Row found: Non-LC={oci_match.group(1)}, LC={oci_match.group(2)}")
        else:
            print("OCI Row NOT found in 2021 block.")

    else:
        print("2021 data block NOT found.")

except Exception as e:
    print(f"Error: {e}")
