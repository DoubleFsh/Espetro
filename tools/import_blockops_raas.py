#!/usr/bin/env python3
"""Convert a BlockOps RAAS profile into Espetro CapturePoints.json."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ID_PATTERN = re.compile(r"[a-z0-9_.-]{1,64}")
MIN_STAGES = 3
MAX_STAGES = 26


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{number}: expected key=value")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def split_ids(value: str, separator: str = ",") -> list[str]:
    return [part.strip() for part in value.split(separator) if part.strip()]


def point_area(properties: dict[str, str], point_id: str, radius: int,
               min_y: int, max_y: int) -> dict[str, object]:
    prefix = f"objective.{point_id}."
    try:
        x = round(float(properties[prefix + "x"]))
        z = round(float(properties[prefix + "z"]))
    except KeyError as error:
        raise ValueError(f"missing coordinate for {point_id}: {error.args[0]}") from error
    except ValueError as error:
        raise ValueError(f"invalid coordinate for {point_id}") from error

    point: dict[str, object] = {
        "id": point_id,
        "pos1": {"x": x - radius, "y": min_y, "z": z - radius},
        "pos2": {"x": x + radius, "y": max_y, "z": z + radius},
    }
    display_name = properties.get(prefix + "name", "").strip()
    if display_name:
        point["displayName"] = display_name
    return point


def parse_lanes(value: str, known_points: set[str]) -> list[dict[str, object]]:
    lanes: list[dict[str, object]] = []
    for route_index, raw_route in enumerate(value.split(";"), 1):
        stages: list[list[str]] = []
        used: set[str] = set()
        for raw_stage in raw_route.split(","):
            choices = split_ids(raw_stage, "|")
            if not choices:
                continue
            for point_id in choices:
                if point_id not in known_points:
                    raise ValueError(
                        f"route {route_index} references unknown point {point_id}")
                if point_id in used:
                    raise ValueError(
                        f"route {route_index} repeats point {point_id}")
                used.add(point_id)
            stages.append(choices)
        if stages:
            lanes.append({"id": f"route_{route_index:03d}", "stages": stages})
    if not lanes:
        raise ValueError("raas.templates contains no routes")
    for lane in lanes:
        stage_count = len(lane["stages"])
        if not MIN_STAGES <= stage_count <= MAX_STAGES:
            raise ValueError(
                f"{lane['id']} must contain {MIN_STAGES} to {MAX_STAGES} stages")
    return lanes


def aas_points(point_by_id: dict[str, dict[str, object]], route: list[str]) -> list[dict[str, object]]:
    planned: list[dict[str, object]] = []
    for index, point_id in enumerate(route, 1):
        if point_id not in point_by_id:
            raise ValueError(f"AAS route references unknown point {point_id}")
        point = dict(point_by_id[point_id])
        point.pop("id", None)
        point.pop("displayName", None)
        point["name"] = chr(ord("A") + index - 1)
        point["batch"] = index
        planned.append(point)
    return planned


def convert(args: argparse.Namespace) -> dict[str, object]:
    properties = read_properties(args.input)
    point_ids = split_ids(properties.get("raas.objectives", ""))
    if not point_ids:
        raise ValueError("missing raas.objectives")
    if len(point_ids) != len(set(point_ids)):
        raise ValueError("raas.objectives contains duplicate ids")
    invalid_ids = [point_id for point_id in point_ids if not ID_PATTERN.fullmatch(point_id)]
    if invalid_ids:
        raise ValueError(f"invalid objective id: {invalid_ids[0]}")

    points = [
        point_area(properties, point_id, args.radius, args.min_y, args.max_y)
        for point_id in point_ids
    ]
    point_by_id = {str(point["id"]): point for point in points}
    lanes = parse_lanes(properties.get("raas.templates", ""), set(point_ids))
    aas_route = split_ids(args.aas)

    mode = args.mode.upper()
    if mode in {"AAS", "RANDOM"} and not MIN_STAGES <= len(aas_route) <= MAX_STAGES:
        raise ValueError(
            f"{mode} requires --aas with {MIN_STAGES} to {MAX_STAGES} point ids")

    result: dict[str, object] = {
        "objectiveMode": mode,
        "endBehavior": "terminate",
        "teamReinforcements": {
            "ATTACK": args.attack_tickets,
            "DEFEND": args.defend_tickets,
        },
    }
    if aas_route:
        planned = aas_points(point_by_id, aas_route)
        result["totalBatches"] = len(planned)
        result["plannedPoints"] = planned
    result["raas"] = {"points": points, "lanes": lanes}
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="BlockOps raas/*.properties")
    parser.add_argument("output", type=Path, help="Espetro CapturePoints.json")
    parser.add_argument("--mode", choices=("AAS", "RAAS", "RANDOM"), default="RAAS")
    parser.add_argument("--aas", default="", help="comma-separated fixed AAS point ids")
    parser.add_argument("--radius", type=int, default=16, help="capture half-width in blocks")
    parser.add_argument("--min-y", type=int, default=-64)
    parser.add_argument("--max-y", type=int, default=320)
    parser.add_argument("--attack-tickets", type=int, default=280)
    parser.add_argument("--defend-tickets", type=int, default=1200)
    args = parser.parse_args()

    if args.radius < 1 or args.min_y >= args.max_y:
        parser.error("radius must be positive and min-y must be below max-y")
    try:
        result = convert(args)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
