#!/usr/bin/env python3
"""Bounded summary of JFR JSON from `jfr print --json`."""

from __future__ import annotations

import json
import math
import re
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone

DURATION_RE = re.compile(
    r"^P(?:(?P<days>\d+(?:[.,]\d+)?)D)?"
    r"(?:T(?:(?P<hours>\d+(?:[.,]\d+)?)H)?"
    r"(?:(?P<minutes>\d+(?:[.,]\d+)?)M)?"
    r"(?:(?P<seconds>\d+(?:[.,]\d+)?)S)?)?$"
)
MAX_TYPES = 100
MAX_OVERLAPS = 10
MAX_STACKS_PER_WINDOW = 5
MAX_FRAMES = 12


def duration_ms(value):
    if not isinstance(value, str):
        return None
    match = DURATION_RE.fullmatch(value)
    if not match:
        return None
    parts = match.groupdict()
    if not any(parts.values()):
        return None
    number = lambda key: float((parts[key] or "0").replace(",", "."))
    total = (
        number("days") * 86400
        + number("hours") * 3600
        + number("minutes") * 60
        + number("seconds")
    )
    return total * 1000


def instant(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(
            value[:-1] + "+00:00" if value.endswith("Z") else value
        )
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def timestamp(value):
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def numeric(value):
    if isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def bounded(value, depth=0):
    if depth > 2:
        return str(value)[:200]
    if isinstance(value, str):
        return value[:200]
    if isinstance(value, list):
        return [bounded(item, depth + 1) for item in value[:10]]
    if isinstance(value, dict):
        return {
            str(key)[:80]: bounded(item, depth + 1)
            for key, item in list(value.items())[:10]
        }
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return str(value)[:200]


def values_of(event):
    values = event.get("values")
    return values if isinstance(values, dict) else {}


def type_of(event):
    return str(event.get("type") or "<unknown>")[:200]


def thread_name(value):
    if isinstance(value, dict):
        value = value.get("javaName") or value.get("name")
    return str(value) if value else ""


def server_thread(values):
    for key in ("sampledThread", "eventThread"):
        name = thread_name(values.get(key))
        if name.casefold() == "server thread":
            return name[:200]
    return None


def stack_methods(values):
    stack = values.get("stackTrace")
    frames = stack.get("frames") if isinstance(stack, dict) else stack
    if not isinstance(frames, list):
        return []
    methods = []
    for frame in frames[:MAX_FRAMES]:
        if not isinstance(frame, dict):
            continue
        method = frame.get("method")
        if not isinstance(method, dict):
            continue
        owner = method.get("type")
        owner = owner.get("name") if isinstance(owner, dict) else owner
        name = method.get("name")
        if owner and name:
            methods.append((str(owner) + "." + str(name))[:300])
        elif name:
            methods.append(str(name)[:300])
    return methods


def is_native_slice(event_type):
    return event_type == "NativeSlice" or event_type.endswith(".NativeSlice")


def is_server_tick(phase):
    return phase.rsplit(".", 1)[-1].casefold() == "server_tick"


def record(event_index, event):
    values = values_of(event)
    start = instant(values.get("startTime"))
    if start is None:
        return None
    phase = str(values.get("phase") or "")[:200]
    reported_ms = numeric(values.get("serverTickMs"))
    if reported_ms is None:
        reported_ms = numeric(values.get("tickms"))
    parsed_ms = duration_ms(values.get("duration"))

    if is_server_tick(phase):
        span_ms = reported_ms if reported_ms is not None else (parsed_ms or 0.0)
        end = start
        begin = end - timedelta(milliseconds=span_ms)
    else:
        span_ms = parsed_ms if parsed_ms is not None else (reported_ms or 0.0)
        begin = start
        end = begin + timedelta(milliseconds=span_ms)

    row = {
        "event_index": event_index,
        "phase": phase,
        "start": timestamp(begin),
        "end": timestamp(end),
        "duration_ms": round(span_ms, 3),
    }
    if "operations" in values:
        row["operations"] = bounded(values["operations"])
    if reported_ms is not None:
        row["serverTickMs"] = round(reported_ms, 3)
    return {"row": row, "begin": begin, "end": end, "duration_ms": span_ms}


def ranked(items, limit=10):
    return sorted(items, key=lambda item: item["duration_ms"], reverse=True)[:limit]


def pause_record(event_index, event):
    values = values_of(event)
    start = instant(values.get("startTime"))
    if start is None:
        return None
    span_ms = duration_ms(values.get("duration")) or 0.0
    end = start + timedelta(milliseconds=span_ms)
    return {
        "row": {
            "event_index": event_index,
            "type": type_of(event),
            "start": timestamp(start),
            "duration_ms": round(span_ms, 3),
        },
        "begin": start,
        "end": end,
        "duration_ms": span_ms,
    }


def is_pause(event_type):
    return event_type.endswith(("GCPhasePause", "SafepointBegin", "ExecuteVMOperation"))


def is_execution_sample(event_type):
    return event_type.endswith("ExecutionSample")


def top_stacks(events, limit, frame_limit):
    groups = Counter()
    for event in events:
        methods = stack_methods(values_of(event))
        signature = tuple(methods[:MAX_FRAMES]) or ("<no stack frames>",)
        groups[signature] += 1
    return [
        {"count": count, "methods": list(signature[:frame_limit])}
        for signature, count in sorted(
            groups.items(), key=lambda item: (-item[1], item[0])
        )[:limit]
    ]


def overlaps(first_start, first_end, second_start, second_end):
    return second_start <= first_end and second_end >= first_start


def window_summary(kind, native, pauses, samples):
    matching_pauses = [
        item for item in pauses
        if overlaps(native["begin"], native["end"], item["begin"], item["end"])
    ]
    matching_pauses.sort(key=lambda item: item["duration_ms"], reverse=True)

    matching_samples = []
    for event in samples:
        when = instant(values_of(event).get("startTime"))
        if when is not None and native["begin"] <= when <= native["end"]:
            matching_samples.append(event)

    return {
        "kind": kind,
        **native["row"],
        "overlaps": {
            "count": len(matching_pauses),
            "truncated": len(matching_pauses) > MAX_OVERLAPS,
            "events": [
                item["row"] for item in matching_pauses[:MAX_OVERLAPS]
            ],
        },
        "server_thread_samples": {
            "count": len(matching_samples),
            "top_stacks": top_stacks(
                matching_samples, MAX_STACKS_PER_WINDOW, 5
            ),
        },
    }


def analyze(document):
    recording = document.get("recording", document)
    events = recording.get("events") if isinstance(recording, dict) else None
    if not isinstance(events, list):
        raise SystemExit("Expected recording.events to be a JSON array")

    counts = Counter()
    native_ticks = []
    native_spans = []
    pauses = []
    gc_pauses = []
    server_samples = []

    for index, event in enumerate(events):
        if not isinstance(event, dict):
            continue
        event_type = type_of(event)
        counts[event_type] += 1
        values = values_of(event)

        if is_native_slice(event_type):
            item = record(index, event)
            if item is not None:
                (native_ticks if is_server_tick(item["row"]["phase"]) else native_spans).append(item)
        if is_pause(event_type):
            item = pause_record(index, event)
            if item is not None:
                pauses.append(item)
                if event_type.endswith("GCPhasePause"):
                    gc_pauses.append(item)
        if is_execution_sample(event_type) and server_thread(values):
            server_samples.append(event)

    top_ticks = ranked(native_ticks)
    top_spans = ranked(native_spans)
    top_pauses = ranked(gc_pauses)
    type_items = sorted(counts.items(), key=lambda item: (-item[1], item[0]))

    output = {
        "event_count": len(events),
        "counts_by_type": dict(type_items[:MAX_TYPES]),
        "counts_by_type_truncated": len(type_items) > MAX_TYPES,
        "server_ticks": [item["row"] for item in top_ticks],
        "native_spans": [item["row"] for item in top_spans],
        "gc_pauses": [item["row"] for item in top_pauses],
        "windows": [
            window_summary("server_tick", item, pauses, server_samples)
            for item in top_ticks
        ] + [
            window_summary("native_span", item, pauses, server_samples)
            for item in top_spans
        ],
        "server_thread_execution_samples": {
            "count": len(server_samples),
            "top_stacks": top_stacks(server_samples, 10, 10),
        },
    }
    return output


def main():
    if len(sys.argv) > 2:
        raise SystemExit("Usage: analyze_jfr.py [JFR_JSON_FILE]")

    stream = open(sys.argv[1], encoding="utf-8") if len(sys.argv) == 2 else sys.stdin
    try:
        document = json.load(stream)
    finally:
        if stream is not sys.stdin:
            stream.close()

    json.dump(analyze(document), sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()

