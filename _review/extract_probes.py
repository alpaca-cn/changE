# -*- coding: utf-8 -*-
"""Extract the acceptance probe lines from the surefire XML of the new test class."""
import xml.etree.ElementTree as ET

P = (r"E:\idea\program\change\backend\target\surefire-reports"
     r"\TEST-com.change.service.ChangeLogServiceUndoTransactionTest.xml")
r = ET.parse(P).getroot()
print(f"tests={r.get('tests')} failures={r.get('failures')} errors={r.get('errors')}")
o = r.find("system-out")
if o is not None and o.text:
    for line in o.text.splitlines():
        if "PROBE" in line:
            print(line)
