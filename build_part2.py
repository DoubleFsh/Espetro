import json

with open(r'd:\minecraft\modp\Espetro\_classes_part1.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

C = data["classes"]
ICON = "/home/shu/图片/Icon"

# =========== NBT HELPERS (copied from part1) ===========
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

# --- RAIDER (2 variants) ---
C["PLA_118_RAIDER"] = {
    "strict_count": True, "name": "奇袭兵", "icon": "raider", "description": "渗透奇袭作战", "role": "奇袭",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 3,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "消音": {"name": "奇袭兵(消音)", "description": "QCW-05 + 消音器 (6 mags, 3雷)", "maxPlayers": 1,
            "commands": [qcw05([("AttachmentMUZZLE","cib:muzzle_191")]), qsz92(), frag(3), smoke(2), *armor(), med(2), bread(), m21(8)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("cib:58x21",160,160),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,3),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "光瞄": {"name": "奇袭兵(光瞄消音)", "description": "QCW-05 + 红点 + 消音器 (6 mags, 3雷)", "maxPlayers": 1,
            "commands": [qcw05([("AttachmentSCOPE","cib:csol2"),("AttachmentMUZZLE","cib:muzzle_191")]), qsz92(), frag(3), smoke(2), *armor(), med(2), bread(), m21(8)],
            "resupply": {"ammo_cost":30,"items":[ri("superbwarfare:medical_kit",2,2),ra("cib:58x21",160,160),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,3),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/raider.png"
}

# --- AUTOMATIC RIFLEMAN (2 variants) ---
C["PLA_118_AUTOMATIC_RIFLEMAN"] = {
    "strict_count": True, "name": "班用机枪", "icon": "automatic_rifleman", "description": "班组压制火力", "role": "火力压制",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 2,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "机瞄": {"name": "班用机枪(机瞄)", "description": "QJB-201 机瞄 (6 drums)", "maxPlayers": 1,
            "commands": [qjb201(), qsz92(), frag(2), smoke(2), *armor(), med(2), bread(), drum(5), m21(3)],
            "resupply": {"ammo_cost":60,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",750,750),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "光瞄": {"name": "班用机枪(光瞄)", "description": "QJB-201 + QMK-204 (6 drums, 少1雷)", "maxPlayers": 1,
            "commands": [qjb201([("AttachmentSCOPE","cib:qmk204")]), qsz92(), frag(1), smoke(2), *armor(), med(2), bread(), drum(5), m21(3)],
            "resupply": {"ammo_cost":60,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",750,750),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/automatic_rifleman.png"
}

# --- RECRUIT (1 variant) ---
C["PLA_118_RECRUIT"] = {
    "strict_count": False, "name": "新兵", "icon": "recruit", "description": "基础训练兵员", "role": "新兵",
    "maxPlayers": 100, "team_count": False, "max_per_squad": -1, "troopValue": 1, "row": 2,
    "unlock_per_n": 0, "unlock_min_squad": 0, "leader_only": False,
    "variants": {
        "default": {"name": "新兵", "description": "QBZ-191 机瞄 (7 mags, 基础装备)", "maxPlayers": 100,
            "commands": [qbz191(), *armor(), med(1), bread(), m42(6)],
            "resupply": {"ammo_cost":20,"items":[ri("superbwarfare:medical_kit",1,1),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20)]}}
    },
    "IconImage": f"{ICON}/recruit.png"
}

# --- GRENADIER (2 variants) ---
C["PLA_118_GRENADIER"] = {
    "strict_count": True, "name": "榴弹兵", "icon": "grenadier", "description": "榴弹远程压制", "role": "榴弹支援",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 3,
    "unlock_per_n": 0, "unlock_min_squad": 3, "leader_only": False,
    "variants": {
        "default": {"name": "榴弹兵(红点)", "description": "QBZ-191 + 握把 + 红点 + QLZ-87 (18发35mm)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:csol2")]), qlz87(), m35(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":80,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2),ra("suffuse:35x32mm",6,18)]}},
        "倍镜": {"name": "榴弹兵(倍镜)", "description": "QBZ-191 + QMK-171A + QLZ-87 (18发35mm, 6 mags)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentSCOPE","cib:qmk171")]), qlz87(), m35(2), smoke(2), *armor(), med(2), bread(), m42(5)],
            "resupply": {"ammo_cost":80,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2),ra("suffuse:35x32mm",6,18)]}}
    },
    "IconImage": f"{ICON}/grenadier.png"
}

# --- LAT (3 variants) ---
C["PLA_118_LAT"] = {
    "strict_count": False, "name": "轻型反坦克兵", "icon": "light_anti_tank", "description": "反装甲作战", "role": "反装甲",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 3,
    "unlock_per_n": 0, "unlock_min_squad": 3, "leader_only": False,
    "variants": {
        "机瞄": {"name": "轻筒(机瞄)", "description": "QBZ-191 机瞄 + DZJ-08 (7 mags, 2雷)", "maxPlayers": 2,
            "commands": [qbz191(), dzj08(), frag(2), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":100,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2),ri(dzj08(),1,1)]}},
        "红点": {"name": "轻筒(红点)", "description": "QBZ-191 + 红点 + DZJ-08 (7 mags, 1雷)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentSCOPE","cib:csol2")]), dzj08(), frag(1), smoke(2), *armor(), med(2), bread(), m42(6)],
            "resupply": {"ammo_cost":100,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2),ri(dzj08(),1,1)]}},
        "倍镜": {"name": "轻筒(倍镜)", "description": "QBZ-191 + QMK-171A + DZJ-08 (6 mags)", "maxPlayers": 1,
            "commands": [qbz191([("AttachmentSCOPE","cib:qmk171")]), dzj08(), smoke(2), *armor(), med(2), bread(), m42(5)],
            "resupply": {"ammo_cost":100,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",150,150),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2),ri(dzj08(),1,1)]}}
    },
    "IconImage": f"{ICON}/lightAT.png"
}

# --- MARKSMAN (2 variants) ---
C["PLA_118_MARKSMAN"] = {
    "strict_count": True, "name": "精确射手", "icon": "marksman", "description": "远程精确打击", "role": "精确射击",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 3,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "光瞄": {"name": "精确射手(光瞄)", "description": "QBU-191 + QMK-191 (7 mags)", "maxPlayers": 1,
            "commands": [qbu191([("AttachmentSCOPE","cib:qmk191")]), qsz92(), frag(1), smoke(2), *armor(), med(2), bread(), m42(6), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "消音": {"name": "精确射手(消音)", "description": "QBU-191 + QMK-191 + 消音器 (5 mags)", "maxPlayers": 1,
            "commands": [qbu191([("AttachmentSCOPE","cib:qmk191"),("AttachmentMUZZLE","cib:muzzle_191")]), smoke(2), *armor(), med(2), bread(), m42(4)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",120,120),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/marksman.png"
}

# --- SCOUT (1 variant) ---
C["PLA_118_SCOUT"] = {
    "strict_count": False, "name": "侦察兵", "icon": "scout", "description": "前线侦察与目标标记", "role": "侦察",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 2, "row": 4,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "消音": {"name": "侦察兵(消音倍镜)", "description": "QBZ-192 + 握把 + QMK-171A + 消音器 (5 mags)", "maxPlayers": 2,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:qmk171"),("AttachmentMUZZLE","cib:muzzle_191")]), qsz92(), frag(1), smoke(2), *armor(), med(2), bread(), m42(4), m21(3)],
            "resupply": {"ammo_cost":40,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",120,120),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/scout.png"
}

# --- MMG (2 variants) ---
C["PLA_118_MMG"] = {
    "strict_count": True, "name": "通用机枪手", "icon": "machine_gunner", "description": "中口径通用机枪火力支援", "role": "机枪手",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 4, "row": 4,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "机瞄": {"name": "机枪手(机瞄)", "description": "QJY-201 机瞄 (6 drums)", "maxPlayers": 1,
            "commands": [qjy201(), qsz92(), frag(2), smoke(2), *armor(), med(2), bread(), drum(5), m21(3)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",750,750),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}},
        "光瞄": {"name": "机枪手(光瞄)", "description": "QJY-201 + QMK-203 (6 drums, 少1雷)", "maxPlayers": 1,
            "commands": [qjy201([("AttachmentSCOPE","cib:qmk203")]), qsz92(), frag(1), smoke(2), *armor(), med(2), bread(), drum(5), m21(3)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",750,750),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",1,1),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/machine_gunner.png"
}

# --- HAT (2 variants) ---
C["PLA_118_HAT"] = {
    "strict_count": True, "name": "重型反坦克兵", "icon": "heavy_anti_tank", "description": "重型火力支援", "role": "重型火力",
    "maxPlayers": 2, "team_count": False, "max_per_squad": 1, "troopValue": 3, "row": 4,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "机瞄": {"name": "重筒(机瞄)", "description": "QBZ-192 机瞄 + PF98 (4发火箭弹)", "maxPlayers": 1,
            "commands": [qbz192(), pf98(), smoke(2), *armor(), med(2), bread(), m42(6), 'tacz:ammo{AmmoId:"suffuse:120mm"} 4'],
            "resupply": {"ammo_cost":80,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2),ra("suffuse:120mm",2,4)]}},
        "光瞄": {"name": "重筒(光瞄)", "description": "QBZ-192 + 红点 + PF98 (2发火箭弹)", "maxPlayers": 1,
            "commands": [qbz192([("AttachmentSCOPE","cib:csol2")]), pf98(), smoke(2), *armor(), med(2), bread(), m42(6), 'tacz:ammo{AmmoId:"suffuse:120mm"} 2'],
            "resupply": {"ammo_cost":80,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",180,180),ri("minecraft:bread",20,20),ri("superbwarfare:m18_smoke_grenade",2,2),ra("suffuse:120mm",1,2)]}}
    },
    "IconImage": f"{ICON}/HeavyAT.png"
}

# --- ENGINEER (1 variant) ---
C["PLA_118_ENGINEER"] = {
    "strict_count": False, "name": "战斗工兵", "icon": "combat_engineer", "description": "爆破与修筑，携带C4和反坦克地雷", "role": "工程爆破",
    "maxPlayers": 2, "team_count": True, "max_per_squad": 1, "troopValue": 2, "row": 4,
    "unlock_per_n": 0, "unlock_min_squad": 6, "leader_only": False,
    "variants": {
        "default": {"name": "战斗工兵", "description": "QBZ-192 + 握把 + 红点 + C4 + 反坦克地雷 (7 mags)", "maxPlayers": 2,
            "commands": [qbz192([("AttachmentGRIP","cib:grip_191"),("AttachmentSCOPE","cib:csol2")]), frag(2), smoke(2), *armor(), med(2), bread(), "superbwarfare:m15_anti_tank_mine 3", "superbwarfare:m112_c4 1", m42(6)],
            "resupply": {"ammo_cost":50,"items":[ri("superbwarfare:medical_kit",2,2),ra("tacz:58x42",210,210),ri("minecraft:bread",20,20),ri("superbwarfare:hand_grenade",2,2),ri("superbwarfare:m18_smoke_grenade",2,2)]}}
    },
    "IconImage": f"{ICON}/engineer.png"
}

print("Part 2 done, writing final...")
with open(r'c:\Users\Administrator\Desktop\pla_118th_brigade.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("Final JSON written successfully!")
