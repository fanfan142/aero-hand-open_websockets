#!/usr/bin/env python3
"""Fast static checks for Aero Hand firmware sources and artifacts."""

from __future__ import annotations

import argparse
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_TARGET = REPO_ROOT / "esp32_wifi" / "firmware" / "aero_hand_wifi"
SOURCE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".ino"}
REQUIRED_PROTOCOL_MARKERS = (
    "hand_info",
    "firmware_type",
    "firmware_version",
    "protocol_version",
    "command_type",
    "request_id",
    "joint_control",
    "multi_joint_control",
    "actuator_control",
    "get_states",
    "homing",
    "wifi_status",
    "wifi_config_set",
    "wifi_connect_sta",
    "wifi_start_ap",
    "wifi_clear_sta",
)
WIFI_TRANSITION_ACTIONS = {
    "wifi_connect_sta": "WIFI_ACTION_CONNECT_STA",
    "wifi_start_ap": "WIFI_ACTION_START_AP",
    "wifi_clear_sta": "WIFI_ACTION_CLEAR_STA",
}


def find_unterminated_string(source: str) -> tuple[int, int] | None:
    """Return the first normal C/C++ string that crosses a physical line."""
    state = "code"
    string_start = (0, 0)
    line = 1
    column = 1
    index = 0

    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""

        if state == "code":
            if char == "/" and following == "/":
                state = "line_comment"
                index += 2
                column += 2
                continue
            if char == "/" and following == "*":
                state = "block_comment"
                index += 2
                column += 2
                continue
            if char == '"':
                state = "string"
                string_start = (line, column)
            elif char == "'":
                state = "character"

        elif state == "line_comment":
            if char in "\r\n":
                state = "code"

        elif state == "block_comment":
            if char == "*" and following == "/":
                state = "code"
                index += 2
                column += 2
                continue

        elif state in {"string", "character"}:
            quote = '"' if state == "string" else "'"
            if char == "\\":
                if following == "\r" and index + 2 < len(source) and source[index + 2] == "\n":
                    index += 3
                    line += 1
                    column = 1
                    continue
                if following == "\n":
                    index += 2
                    line += 1
                    column = 1
                    continue
                index += min(2, len(source) - index)
                column += min(2, len(source) - index)
                continue
            if char == quote:
                state = "code"
            elif char in "\r\n":
                if state == "string":
                    return string_start
                state = "code"

        if char == "\r":
            if following == "\n":
                index += 1
            line += 1
            column = 1
        elif char == "\n":
            line += 1
            column = 1
        else:
            column += 1
        index += 1

    return string_start if state == "string" else None


def find_command_handler_body(source: str, command: str) -> str | None:
    marker = f'strcmp(type, "{command}") == 0'
    marker_start = source.find(marker)
    if marker_start < 0:
        return None

    body_start = source.find("{", marker_start + len(marker))
    if body_start < 0:
        return None

    depth = 1
    for index in range(body_start + 1, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[body_start + 1 : index]
    return None


def check_wifi_command_handlers(path: Path, source: str) -> list[str]:
    errors: list[str] = []
    config_body = find_command_handler_body(source, "wifi_config_set")
    if config_body is not None:
        compact_config_body = "".join(config_body.split())
        if "sendWifiStatus(clientNum,requestId)" not in compact_config_body:
            errors.append(f"{path}: wifi_config_set does not emit correlated wifi_status")

    for command, action in WIFI_TRANSITION_ACTIONS.items():
        body = find_command_handler_body(source, command)
        if body is None:
            continue
        if f"scheduleWifiAction({action})" not in body:
            errors.append(f"{path}: {command} does not schedule {action}")
        if "sendWifiStatus(" in body:
            errors.append(f"{path}: {command} emits wifi_status before the transition")
    return errors


def check_source(path: Path) -> list[str]:
    try:
        source = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        return [f"{path}: cannot read UTF-8 source: {error}"]

    errors = check_wifi_command_handlers(path, source)
    location = find_unterminated_string(source)
    if location is not None:
        line, column = location
        errors.append(f"{path}:{line}:{column}: unterminated string literal before newline")
    return errors


def check_required_markers(target: Path, content: bytes) -> list[str]:
    missing_markers = [
        marker for marker in REQUIRED_PROTOCOL_MARKERS if marker.encode("ascii") not in content
    ]
    if not missing_markers:
        return []
    return [f"{target}: missing required protocol markers: {', '.join(missing_markers)}"]


def check_source_target(target: Path) -> list[str]:
    if target.is_file():
        source_files = [target]
    elif target.is_dir():
        source_files = sorted(
            path
            for path in target.rglob("*")
            if path.is_file() and path.suffix.lower() in SOURCE_SUFFIXES
        )
    else:
        return [f"{target}: source target does not exist"]

    if not source_files:
        return [f"{target}: source directory contains no C/C++ firmware files"]

    errors: list[str] = []
    contract_bytes = bytearray()
    for source_file in source_files:
        errors.extend(check_source(source_file))
        try:
            contract_bytes.extend(source_file.read_bytes())
        except OSError as error:
            errors.append(f"{source_file}: cannot read source bytes: {error}")

    errors.extend(check_required_markers(target, contract_bytes))
    return errors


def check_binary(target: Path) -> list[str]:
    try:
        content = target.read_bytes()
    except OSError as error:
        return [f"{target}: cannot read firmware binary: {error}"]
    return check_required_markers(target, content)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "targets",
        nargs="*",
        type=Path,
        default=[DEFAULT_SOURCE_TARGET],
        help="firmware source file, source directory, or compiled .bin to check",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    errors: list[str] = []
    for target in args.targets:
        if target.is_file() and target.suffix.lower() == ".bin":
            errors.extend(check_binary(target))
        else:
            errors.extend(check_source_target(target))

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1

    for target in args.targets:
        print(f"OK: {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
