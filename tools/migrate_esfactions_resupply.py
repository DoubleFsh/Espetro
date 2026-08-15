#!/usr/bin/env python3
"""Deterministically migrate Espetro faction resupply data to per-entry costs.

The command list is the source of truth for spare TaCZ Magazines.  Only top-level
``taczmagazines:magazine`` commands are parsed; magazines embedded in gun NBT are
intentionally ignored.  The tool is idempotent and validates its transformed
result before it writes anything.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import sys
import tempfile
from collections import OrderedDict
from pathlib import Path
from typing import Any


MAGAZINE_COMMAND = re.compile(
    r"^taczmagazines:magazine\{(?P<nbt>.*)\}(?:\s+(?P<count>[0-9]+))?$"
)
RESOURCE_LOCATION = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
NBT_INTEGER = r"(?P<value>[0-9]+)(?:[bBsSlL])?"


def nbt_string(nbt: str, key: str) -> str | None:
    match = re.search(rf"(?:^|[,{{])\s*{re.escape(key)}\s*:\s*\"([^\"]+)\"", nbt)
    return match.group(1) if match else None


def nbt_integer(nbt: str, key: str) -> int | None:
    match = re.search(rf"(?:^|[,{{])\s*{re.escape(key)}\s*:\s*{NBT_INTEGER}", nbt)
    return int(match.group("value")) if match else None


def parse_magazine(command: str) -> dict[str, Any] | None:
    match = MAGAZINE_COMMAND.fullmatch(command.strip())
    if not match:
        return None
    nbt = match.group("nbt")
    family = nbt_string(nbt, "MagazineFamily")
    ammo_id = nbt_string(nbt, "AmmoId")
    capacity = nbt_integer(nbt, "MaxCapacity")
    ammo_count = nbt_integer(nbt, "AmmoCount")
    count = int(match.group("count") or "1")
    if not family or not ammo_id or capacity is None or ammo_count is None or count < 1:
        raise ValueError(f"cannot parse top-level magazine command: {command}")
    return {
        "family": family,
        "ammo_id": ammo_id,
        "capacity": capacity,
        "ammo_count": ammo_count,
        "count": count,
    }


def canonical_magazine_command(magazine: dict[str, Any]) -> str:
    return (
        "taczmagazines:magazine"
        f'{{AmmoCount:{magazine["capacity"]},AmmoId:"{magazine["ammo_id"]}",'
        f'MagazineFamily:"{magazine["family"]}",MaxCapacity:{magazine["capacity"]}}}'
        f' {magazine["count"]}'
    )


def canonical_magazine_nbt(identity: tuple[str, str, int]) -> str:
    family, ammo_id, capacity = identity
    return (
        f'{{AmmoCount:{capacity},AmmoId:"{ammo_id}",'
        f'MagazineFamily:"{family}",MaxCapacity:{capacity}}}'
    )


def split_inline_nbt(raw_id: str) -> tuple[str, str | None]:
    brace = raw_id.find("{")
    if brace < 0:
        return raw_id.strip(), None
    item_id = raw_id[:brace].strip()
    nbt = raw_id[brace:].strip()
    if not nbt.startswith("{") or not nbt.endswith("}"):
        raise ValueError(f"invalid inline item SNBT: {raw_id}")
    return item_id, nbt


def loose_ammo_id(item: dict[str, Any]) -> str | None:
    if item.get("id") != "tacz:ammo":
        return None
    nbt = item.get("nbt")
    return nbt_string(nbt, "AmmoId") if isinstance(nbt, str) else None


def variants(document: dict[str, Any]):
    classes = document.get("classes")
    if not isinstance(classes, dict):
        raise ValueError("root.classes must be an object")
    for class_id, class_data in classes.items():
        class_variants = class_data.get("variants")
        if not isinstance(class_variants, dict):
            continue
        for variant_id, variant in class_variants.items():
            yield class_id, variant_id, variant


def correct_known_source_errors(class_id: str, variant_id: str,
                                commands: list[str], report: dict[str, Any]) -> list[str]:
    corrected = list(commands)
    if class_id in {"PLA_112_RAIDER", "PLA_118_RAIDER"} and variant_id == "红点":
        for index, command in enumerate(corrected):
            magazine = parse_magazine(command)
            if magazine and magazine["family"] == "58x42_30":
                magazine.update(
                    family="58x21_50", ammo_id="cib:58x21", capacity=50, ammo_count=50
                )
                corrected[index] = canonical_magazine_command(magazine)
                report["fixes"].append("corrected Raider red-dot spare magazine to 58x21_50")
    return corrected


def migrate_variant(class_id: str, variant_id: str, variant: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "class": class_id,
        "variant": variant_id,
        "added_magazines": [],
        "removed_loose_ammo": [],
        "retained_loose_ammo": [],
        "fixes": [],
    }
    commands = variant.get("commands", [])
    if not isinstance(commands, list) or not all(isinstance(command, str) for command in commands):
        raise ValueError(f"{class_id}/{variant_id}: commands must be a string list")
    commands = correct_known_source_errors(class_id, variant_id, commands, result)
    variant["commands"] = commands

    grouped: OrderedDict[tuple[str, str, int], int] = OrderedDict()
    for command in commands:
        magazine = parse_magazine(command)
        if magazine is None:
            continue
        identity = (magazine["family"], magazine["ammo_id"], magazine["capacity"])
        grouped[identity] = grouped.get(identity, 0) + magazine["count"]

    resupply = variant.get("resupply")
    if not isinstance(resupply, dict):
        return result
    resupply.pop("ammo_cost", None)
    resupply.pop("ammoCost", None)
    raw_items = resupply.get("items", [])
    if not isinstance(raw_items, list):
        raise ValueError(f"{class_id}/{variant_id}: resupply.items must be a list")

    normalized: list[dict[str, Any]] = []
    magazine_ammo = {identity[1] for identity in grouped}
    for raw_item in raw_items:
        if not isinstance(raw_item, dict) or not isinstance(raw_item.get("id"), str):
            raise ValueError(f"{class_id}/{variant_id}: malformed resupply item")
        item = copy.deepcopy(raw_item)
        item_id, inline_nbt = split_inline_nbt(item["id"])
        item["id"] = item_id
        if inline_nbt is not None and not item.get("nbt"):
            item["nbt"] = inline_nbt
        if class_id in {"PLA_112_SCOUT", "PLA_118_SCOUT"} and variant_id == "default" \
                and item["id"] == "superbwarfare:drone 1":
            item["id"] = "superbwarfare:drone"
            result["fixes"].append("removed count suffix from Scout drone resupply id")
        if item["id"] == "taczmagazines:magazine":
            # Always regenerate magazine entries from initial top-level command stacks.
            continue
        ammo_id = loose_ammo_id(item)
        if ammo_id and ammo_id in magazine_ammo:
            result["removed_loose_ammo"].append(ammo_id)
            continue
        if ammo_id:
            result["retained_loose_ammo"].append(ammo_id)
        item["ammo_cost"] = 1
        normalized.append(item)

    for identity, initial_count in grouped.items():
        magazine_item = {
            "id": "taczmagazines:magazine",
            "nbt": canonical_magazine_nbt(identity),
            "count": 1,
            "max": initial_count,
            "ammo_cost": 1,
        }
        normalized.append(magazine_item)
        result["added_magazines"].append(
            {
                "family": identity[0],
                "ammo_id": identity[1],
                "capacity": identity[2],
                "max": initial_count,
            }
        )
    resupply["items"] = normalized
    return result


def validate_document(path: Path, document: dict[str, Any]) -> dict[str, int]:
    counts = {"variants": 0, "resupply_items": 0, "magazine_items": 0}
    for class_id, variant_id, variant in variants(document):
        counts["variants"] += 1
        expected: OrderedDict[tuple[str, str, int], int] = OrderedDict()
        for command in variant.get("commands", []):
            magazine = parse_magazine(command)
            if magazine:
                identity = (magazine["family"], magazine["ammo_id"], magazine["capacity"])
                expected[identity] = expected.get(identity, 0) + magazine["count"]
        resupply = variant.get("resupply")
        if not isinstance(resupply, dict):
            continue
        if "ammo_cost" in resupply or "ammoCost" in resupply:
            raise ValueError(f"{path.name}:{class_id}/{variant_id}: legacy top-level ammo_cost")
        actual: dict[tuple[str, str, int], int] = {}
        for item in resupply.get("items", []):
            counts["resupply_items"] += 1
            if item.get("ammo_cost") != 1:
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: item ammo_cost is not 1")
            item_id = item.get("id")
            if not isinstance(item_id, str) or not RESOURCE_LOCATION.fullmatch(item_id):
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: invalid item id {item_id!r}")
            if "{" in item_id or "}" in item_id:
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: inline SNBT remains")
            if item_id != "taczmagazines:magazine":
                continue
            counts["magazine_items"] += 1
            nbt = item.get("nbt", "")
            identity = (
                nbt_string(nbt, "MagazineFamily"),
                nbt_string(nbt, "AmmoId"),
                nbt_integer(nbt, "MaxCapacity"),
            )
            if None in identity or nbt_integer(nbt, "AmmoCount") != identity[2]:
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: invalid full magazine NBT")
            if item.get("count") != 1 or not isinstance(item.get("max"), int):
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: invalid magazine count/max")
            if identity in actual:
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: duplicate magazine identity")
            actual[identity] = item["max"]
        if dict(expected) != actual:
            raise ValueError(
                f"{path.name}:{class_id}/{variant_id}: magazine catalog mismatch; "
                f"expected={dict(expected)!r}, actual={actual!r}"
            )
        matched_ammo = {identity[1] for identity in expected}
        for item in resupply.get("items", []):
            if loose_ammo_id(item) in matched_ammo:
                raise ValueError(f"{path.name}:{class_id}/{variant_id}: matched loose ammo remains")
    return counts


def atomic_json_write(path: Path, document: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(document, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="Faction JSON file or directory")
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument("--write", action="store_true", help="atomically rewrite inputs")
    destination.add_argument("--output-dir", type=Path, help="write migrated copies here")
    parser.add_argument("--report", type=Path, help="write a machine-readable migration report")
    arguments = parser.parse_args()

    paths = [arguments.input] if arguments.input.is_file() else sorted(arguments.input.glob("*.json"))
    if not paths:
        parser.error("no JSON files found")
    report: dict[str, Any] = {"schema": 1, "files": [], "totals": {}}
    total_variants = total_items = total_magazines = 0
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            document = json.load(handle)
        variant_report = [
            migrate_variant(class_id, variant_id, variant)
            for class_id, variant_id, variant in variants(document)
        ]
        counts = validate_document(path, document)
        total_variants += counts["variants"]
        total_items += counts["resupply_items"]
        total_magazines += counts["magazine_items"]
        report["files"].append({"name": path.name, **counts, "details": variant_report})
        target = path if arguments.write else (
            arguments.output_dir / path.name if arguments.output_dir else None
        )
        if target is not None:
            atomic_json_write(target, document)
    report["totals"] = {
        "files": len(paths),
        "variants": total_variants,
        "resupply_items": total_items,
        "magazine_items": total_magazines,
    }
    if arguments.report:
        atomic_json_write(arguments.report, report)
    print(json.dumps(report["totals"], ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"migration failed: {error}", file=sys.stderr)
        raise SystemExit(1)
