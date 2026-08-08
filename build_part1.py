import json

data = {}

# =========== FACTION (unchanged) ===========
data["faction"] = {
    "name": "第118混合合成旅",
    "description": "合成化部队",
    "icon": "🔴",
    "selection_image": "pla_118.png",
    "show_name": "中国人民解放军",
    "team": "ATTACK",
    "color": "AA5555",
    "faction_id": "PLA"
}

data["VehTypes"] = ["mbt", "ifv", "acv", "car", "transport_helicopter"]

data["vehicles"] = {
    "mbt": {"display_name": "ZTZ-99A 主战坦克", "entity": ["dragonrise_reforge:ztz99a"], "per_max_count": 1, "respawn_minutes": 10, "troop_value": 5, "max": 100},
    "ifv": {"display_name": "ZBD-04A 步兵战车", "entity": ["dragonrise_reforge:zbd04a"], "per_max_count": 1, "respawn_minutes": 10, "troop_value": 5, "max": 100},
    "acv": {"display_name": "ZBL-08 轮式步兵战车", "entity": ["dragonrise_reforge:zbl08", "dragonrise_reforge:zbl08"], "per_max_count": 1, "respawn_minutes": 10, "troop_value": 5, "max": 100},
    "transport_helicopter": {"display_name": "Z20 通用直升机", "entity": ["dragonrise_reforge:z20"], "per_max_count": 1, "respawn_minutes": 10, "troop_value": 5, "max": 100},
    "car": {"display_name": "CSK-181 东风猛士 高机动载具", "entity": ["dragonrise_reforge:csk181"], "per_max_count": 1, "respawn_minutes": 3, "troop_value": 5, "max": 1}
}

# =========== NBT HELPERS ===========
def qbz191(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{{e},GunCurrentAmmoCount:30,GunFireMode:"AUTO",GunId:"cib:qbz191",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:30,AmmoId:"tacz:58x42",MagazineFamily:"58x42_30",MaxCapacity:30}}}}}}'

def qbz192(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{{e},GunCurrentAmmoCount:30,GunFireMode:"AUTO",GunId:"cib:qbz192",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:30,AmmoId:"tacz:58x42",MagazineFamily:"58x42_30",MaxCapacity:30}}}}}}'

def qsz92():
    return 'tacz:modern_kinetic_gun{AttachmentEXTENDED_MAG:{Count:1b,id:"minecraft:air"},GunCurrentAmmoCount:20,GunFireMode:"SEMI",GunId:"cib:qsz92",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{Count:1b,id:"taczmagazines:magazine",tag:{AmmoCount:19,AmmoId:"cib:58x21",MagazineFamily:"58x21_20",MaxCapacity:20}}}'

def qjb201(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{AttachmentEXTENDED_MAG:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"tacz:extended_mag_3"}}}}{e},GunCurrentAmmoCount:150,GunFireMode:"AUTO",GunId:"cib:qjb201",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:150,AmmoId:"tacz:58x42",MagazineFamily:"58x42_150_ext3",MaxCapacity:150}}}}}}'

def qjy201(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{AttachmentEXTENDED_MAG:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"tacz:extended_mag_3"}}}}{e},GunCurrentAmmoCount:150,GunFireMode:"AUTO",GunId:"cib:qjy201",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:150,AmmoId:"tacz:58x42",MagazineFamily:"58x42_150_ext3",MaxCapacity:150}}}}}}'

def qbu191(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{AttachmentEXTENDED_MAG:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"tacz:extended_mag_3"}}}}{e},GunCurrentAmmoCount:30,GunFireMode:"SEMI",GunId:"cib:qbu191",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:30,AmmoId:"tacz:58x42",MagazineFamily:"58x42_30_ext3",MaxCapacity:30}}}}}}'

def qcw05(att=None):
    e = ""
    if att:
        for k,v in att:
            e += f',{k}:{{Count:1b,id:"tacz:attachment",tag:{{AttachmentId:"{v}"}}}}'
    return f'tacz:modern_kinetic_gun{{{e},GunCurrentAmmoCount:20,GunFireMode:"AUTO",GunId:"cib:qcw05",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{{Count:1b,id:"taczmagazines:magazine",tag:{{AmmoCount:20,AmmoId:"cib:58x21",MagazineFamily:"58x21_20",MaxCapacity:20}}}}}}'

def dzj08():
    return 'tacz:modern_kinetic_gun{GunCurrentAmmoCount:1,GunFireMode:"SEMI",GunId:"cib:dzj08",HasBulletInBarrel:1b}'

def pf98():
    return 'tacz:modern_kinetic_gun{GunCurrentAmmoCount:1,GunFireMode:"SEMI",GunId:"suffuse:pf98a",HasBulletInBarrel:1b}'

def qlz87():
    return 'tacz:modern_kinetic_gun{GunCurrentAmmoCount:6,GunFireMode:"AUTO",GunId:"suffuse:qlz87",HasBulletInBarrel:1b,TaCZMag_StoredMagazine:{Count:1b,id:"taczmagazines:magazine",tag:{AmmoCount:6,AmmoId:"suffuse:35x32mm",MagazineFamily:"35x32mm_6",MaxCapacity:6}}}'

def m42(n):
    return f'taczmagazines:magazine{{AmmoCount:30,AmmoId:"tacz:58x42",MagazineFamily:"58x42_30",MaxCapacity:30}} {n}'

def m21(n):
    return f'taczmagazines:magazine{{AmmoCount:20,AmmoId:"cib:58x21",MagazineFamily:"58x21_20",MaxCapacity:20}} {n}'

def drum(n):
    return f'taczmagazines:magazine{{AmmoCount:150,AmmoId:"tacz:58x42",MagazineFamily:"58x42_150_ext3",MaxCapacity:150}} {n}'

def m35(n):
    return f'taczmagazines:magazine{{AmmoCount:6,AmmoId:"suffuse:35x32mm",MagazineFamily:"35x32mm_6",MaxCapacity:6}} {n}'

def armor():
    return ["dragonrise_reforge:med21_chest", "dragonrise_reforge:pants21", "dragonrise_reforge:t21_helmet"]

def frag(n):
    return f"superbwarfare:hand_grenade {n}"

def smoke(n=2):
    return f"superbwarfare:m18_smoke_grenade {n}"

def med(n):
    return f"superbwarfare:medical_kit {n}"

def bread(n=20):
    return f"minecraft:bread {n}"

def ri(i,c,mx):
    return {"id":i,"count":c,"max":mx}

def ra(a,c,mx):
    return {"id":f'tacz:ammo{{AmmoId:"{a}"}}',"count":c,"max":mx}

ICON = "/home/shu/图片/Icon"

# =========== CLASSES ===========
C = data["classes"] = {}

# --- COMMANDER (3 variants) ---
C["PLA_118_COMMANDER"] = {
    "strict_count": False, "name": "队长", "icon": "leader", "description": "指挥全队作战", "role": "指挥",
    "maxPlayers": 1, "team_count": True, "max_per_squad": 1, "troopValue": 3, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 0, "leader_only": True,
    "variants": {
        "红点": {"name": "队长(红点握把)", "description": "QBZ-191 + 握把 + 红点 (7 mags)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:csol2")]), qsz92(), frag(2), smoke(2), *armor(), med(3), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,3),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "倍镜": {"name": "队长(倍镜握把)", "description": "QBZ-191 + 握把 + QMK-171A (6 mags, 少1雷)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:qmk171")]), qsz92(), frag(1), smoke(2), *armor(), med(3), bread(), m42(5), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,3),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "消音": {"name": "队长(QBZ-192消音)", "description": "QBZ-192 + 握把 + QMK-171A + 消音器 (5 mags)", "maxPlayers": 1,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:qmk171"),("AttachmentMUZZLE","cib:muzzle_191")]), qsz92(), smoke(2), *armor(), med(3), bread(), m42(4), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,3),ra("tacz:58x42",120,120),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/leader.png"
}

# --- LEAD CREWMAN (1 variant) ---
C["PLA_118_LEAD_CREWMAN"] = {
    "strict_count": True, "name": "载具组长", "icon": "crewman", "description": "指挥载具作战", "role": "载具指挥",
    "maxPlayers": 1, "team_count": True, "max_per_squad": 1, "troopValue": 3, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 2, "leader_only": True,
    "variants": {
        "default": {"name": "载具组长", "description": "QBZ-192 + 握把 (7 mags)", "maxPlayers": 1,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191")]), qsz92(), smoke(2), *armor(), med(2), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/crewman.png"
}

# --- LEAD PILOT (1 variant) ---
C["PLA_118_LEAD_PILOT"] = {
    "strict_count": True, "name": "飞行员组长", "icon": "pilot", "description": "指挥空中作战", "role": "飞行指挥",
    "maxPlayers": 1, "team_count": True, "max_per_squad": 1, "troopValue": 3, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 2, "leader_only": True,
    "variants": {
        "default": {"name": "飞行员组长", "description": "QCW-05 + 红点 (6 mags)", "maxPlayers": 1,
            "commands": [qcw05([("AttachmentSCOPE","cib:csol2")]), qsz92(), smoke(2), *armor(), med(2), bread(), m21(8)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,2),ra("cib:58x21",160,160),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/pilot.png"
}

# --- MEDIC (3 variants) ---
C["PLA_118_MEDIC"] = {
    "strict_count": False, "name": "医护兵", "icon": "medic", "description": "战场医疗，拥有更多医疗用品", "role": "医疗支援",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 2, "troopValue": 2, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 0, "leader_only": False,
    "variants": {
        "机瞄": {"name": "医护兵(机瞄)", "description": "QBZ-191 机瞄 (7 mags, 9医疗)", "maxPlayers": 1,
            "commands": [qbz191(), qsz92(), frag(2), smoke(2), *armor(), med(9), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,9),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20)]}},
        "红点": {"name": "医护兵(红点)", "description": "QBZ-191 + 红点 (7 mags, 少1雷, 9医疗)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentSCOPE","cib:csol2")]), qsz92(), frag(1), smoke(2), *armor(), med(9), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,9),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20)]}},
        "倍镜": {"name": "医护兵(倍镜)", "description": "QBZ-191 + QMK-171A (6 mags, 9医疗)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentSCOPE","cib:qmk171")]), qsz92(), smoke(2), *armor(), med(9), bread(), m42(5), m21(3)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,9),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20)]}}
    },
    "IconImage": f"{ICON}/medic.png"
}

# --- CREWMAN (1 variant) ---
C["PLA_118_CREWMAN"] = {
    "strict_count": True, "name": "载具组员", "icon": "crewman", "description": "装甲载具驾驶员与炮手", "role": "载具操作",
    "maxPlayers": 8, "team_count": False, "max_per_squad": 2, "troopValue": 2, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 2, "leader_only": False,
    "variants": {
        "default": {"name": "载具组员", "description": "QBZ-192 机瞄 (7 mags)", "maxPlayers": 8,
            "commands": [qbz192(), qsz92(), smoke(2), *armor(), med(2), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,3),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/crewman.png"
}

# --- PILOT (1 variant) ---
C["PLA_118_PILOT"] = {
    "strict_count": True, "name": "飞行员", "icon": "pilot", "description": "直升机驾驶员", "role": "飞行操作",
    "maxPlayers": 8, "team_count": False, "max_per_squad": 2, "troopValue": 2, "row": 1,
    "unlock_per_n": 0, "unlock_min_squad": 2, "leader_only": False,
    "variants": {
        "default": {"name": "飞行员", "description": "QCW-05 机瞄 (6 mags)", "maxPlayers": 8,
            "commands": [qcw05(), qsz92(), smoke(2), *armor(), med(2), bread(), m21(8)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,3),ra("cib:58x21",160,160),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/pilot.png"
}

# --- RIFLEMAN (6 variants) ---
C["PLA_118_RIFLEMAN"] = {
    "strict_count": False, "name": "步枪兵", "icon": "rifleman", "description": "基础步兵，携带弹药包补给队友", "role": "基础步兵",
    "maxPlayers": 100, "team_count": False, "max_per_squad": -1, "troopValue": 1, "row": 2,
    "unlock_per_n": 0, "unlock_min_squad": 0, "leader_only": False,
    "variants": {
        "机瞄": {"name": "步枪兵(机瞄)", "description": "QBZ-191 机瞄 (7 mags)", "maxPlayers": 100,
            "commands": [qbz191(), frag(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "红点": {"name": "步枪兵(红点)", "description": "QBZ-191 + 红点 (7 mags)", "maxPlayers": 100,
            "commands": [qbz191([("AttachmentSCOPE","cib:csol2")]), frag(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "倍镜": {"name": "步枪兵(倍镜)", "description": "QBZ-191 + QMK-171A (6 mags, 少1雷)", "maxPlayers": 100,
            "commands": [qbz191([("AttachmentSCOPE","cib:qmk171")]), frag(1), smoke(2), *armor(), med(2), bread(), m42(5)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "短管": {"name": "步枪兵(QBZ-192)", "description": "QBZ-192 + 握把 (7 mags)", "maxPlayers": 100,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191")]), frag(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "短管红点": {"name": "步枪兵(QBZ-192红点)", "description": "QBZ-192 + 握把 + 红点 (7 mags)", "maxPlayers": 100,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:csol2")]), frag(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "短管倍镜": {"name": "步枪兵(QBZ-192倍镜)", "description": "QBZ-192 + 握把 + QMK-171A (6 mags, 少1雷)", "maxPlayers": 100,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:qmk171")]), frag(1), smoke(2), *armor(), med(2), bread(), m42(5)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/rifleman.png"
}

print("Part 1 done, writing...")
with open(r'd:\minecraft\modp\Espetro\_classes_part1.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("Part 1 written.")
