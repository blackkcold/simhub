#!/usr/bin/env python3
"""Keep editable SVG artwork and Android VectorDrawable paths equivalent."""
from pathlib import Path
from xml.etree import ElementTree as ET

root=Path(__file__).resolve().parents[1]
names=("home","messages","sim","settings","sync","send","add","qr","shield","signal")
for name in names:
    svg=ET.parse(root/"design/icons"/f"{name}.svg").getroot()
    android=ET.parse(root/"android/app/src/main/res/drawable"/f"ic_hub_{name}.xml").getroot()
    sp=svg.find("{http://www.w3.org/2000/svg}path")
    ap=android.find("path")
    assert svg.attrib["viewBox"]=="0 0 24 24",name
    assert sp is not None and ap is not None,name
    assert sp.attrib["d"]==ap.attrib["{http://schemas.android.com/apk/res/android}pathData"],name
print(f"PASS: {len(names)} editable SVG icons match runtime Android vectors")
