import json, sys

# Read existing file
with open(r'c:\Users\Administrator\Desktop\pla_118th_brigade.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Keep faction and vehicles, replace classes
data['classes'] = {}

# Import the classes builder
sys.path.insert(0, r'd:\minecraft\modp\Espetro')
from build_classes_part1 import add_classes
add_classes(data['classes'])

# Write back
with open(r'c:\Users\Administrator\Desktop\pla_118th_brigade.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print("Done!")
