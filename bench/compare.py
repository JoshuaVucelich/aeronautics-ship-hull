#!/usr/bin/env python3
"""Print the baseline vs ship-hull comparison from two probe result files."""
import json
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def fmt(v, digits=3):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        if v != v:
            return "n/a"
        return f"{v:.{digits}f}"
    return str(v)


def row(label, base, mod, digits=3):
    return f"| {label} | {fmt(base, digits)} | {fmt(mod, digits)} |"


def main():
    base = load(sys.argv[1])
    mod = load(sys.argv[2])
    lines = []
    lines.append("# Headless pack comparison")
    lines.append("")
    pack = mod.get("pack", {})
    lines.append(
        "Pack: NeoForge {neoforge}, Minecraft {minecraft}, Create {create}, "
        "Sable {sable}, Aeronautics {aeronautics}, Simulated {simulated}. "
        "Sodium loaded: {sodium}.".format(**{k: pack.get(k, "") for k in
            ("neoforge", "minecraft", "create", "sable", "aeronautics", "simulated", "sodium")})
    )
    lines.append("")
    lines.append(f"Ship hull on the mod run: `{pack.get('shiphull') or 'absent'}`.")
    lines.append(f"Baseline ship hull loaded: `{base.get('shiphullLoaded')}`. Mod run loaded: `{mod.get('shiphullLoaded')}`.")
    lines.append("")
    lines.append(base.get("jitter", ""))
    lines.append(base.get("packetDelivery", ""))
    if mod.get("movingLayout"):
        lines.append(f"Moving layout: {mod['movingLayout']}.")
    if mod.get("errorJitter"):
        lines.append(mod["errorJitter"])
    lines.append("")
    for name in ("parked", "moving", "group"):
        b = base["scenarios"][name]
        m = mod["scenarios"][name]
        lines.append(f"## {name}")
        lines.append("")
        lines.append("| metric | baseline | with mod |")
        lines.append("| --- | ---: | ---: |")
        lines.append(row("MSPT mean", b["mspt"]["mean"], m["mspt"]["mean"]))
        lines.append(row("MSPT p99", b["mspt"]["p99"], m["mspt"]["p99"]))
        lines.append(row("MSPT stddev", b["mspt"]["stddev"], m["mspt"]["stddev"]))
        lines.append(row("MSPT max", b["mspt"]["max"], m["mspt"]["max"]))
        lines.append(row("desync mean (blocks)", b["serverToClientDesync"]["meanBlocks"], m["serverToClientDesync"]["meanBlocks"]))
        lines.append(row("desync RMS (blocks)", b["serverToClientDesync"]["rmsBlocks"], m["serverToClientDesync"]["rmsBlocks"]))
        lines.append(row("desync p99 (blocks)", b["serverToClientDesync"]["p99Blocks"], m["serverToClientDesync"]["p99Blocks"]))
        lines.append(row("MSPT samples", b["mspt"]["samples"], m["mspt"]["samples"], 0))
        lines.append(row("client jitter RMS", b["jitter"]["clientRmsBlocksPerTick2"], m["jitter"]["clientRmsBlocksPerTick2"], 6))
        lines.append(row("server jitter RMS", b["jitter"]["serverRmsBlocksPerTick2"], m["jitter"]["serverRmsBlocksPerTick2"], 6))
        lines.append(row("error jitter RMS", b["jitter"].get("errorRmsBlocksPerTick2"), m["jitter"].get("errorRmsBlocksPerTick2"), 6))
        lines.append(row("move packets", b["movePackets"], m["movePackets"], 0))
        lines.append(row("stop packets", b["stopPackets"], m["stopPackets"], 0))
        lines.append(row("ships removed", len(b["removed"]), len(m["removed"]), 0))
        lines.append("")
        if m.get("shipRows"):
            lines.append("Mod-run ship rows:")
            lines.append("")
            for row_s in m["shipRows"]:
                mode = row_s.get("mode", "")
                extra = ""
                if "travelBlocks" in row_s:
                    extra = (
                        f" travel={fmt(row_s.get('travelBlocks'))} path={fmt(row_s.get('pathBlocks'))} "
                        f"throttled={row_s.get('throttledTicks', 0)} pulse={row_s.get('pulseTicks', 0)} "
                        f"gapThrottle={fmt(row_s.get('maxThrottledGap'))} gapPulse={fmt(row_s.get('maxPulseGap'))}"
                    )
                lines.append(
                    f"- `{row_s['name']}` mode={mode or 'n/a'} removed={row_s['removed']} "
                    f"y={fmt(row_s['minY'])}..{fmt(row_s['maxY'])} maxSpeed={fmt(row_s['maxSpeed'])} "
                    f"moves={row_s['movePackets']} stops={row_s['stopPackets']}{extra}"
                )
            lines.append("")
        delta = m.get("profilerDelta") or {}
        if delta:
            interesting = {k: delta[k] for k in sorted(delta) if delta[k]}
            lines.append("Profiler deltas on the mod run (non-zero):")
            lines.append("")
            for k, v in interesting.items():
                lines.append(f"- {k}: {v}")
            lines.append("")
    if base.get("failure"):
        lines.append(f"Baseline failure: {base['failure']}")
    if mod.get("failure"):
        lines.append(f"Mod failure: {mod['failure']}")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
