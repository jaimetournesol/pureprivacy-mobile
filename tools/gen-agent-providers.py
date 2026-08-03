#!/usr/bin/env python3
"""Generate the Agents app's provider list from Hermes's own catalog.

The wizard used to carry six hand-written providers while Hermes ships ~40. Rather than
maintain a second list by hand and let it drift, generate it from the source of truth:

    docker exec pureprivacy-agent /opt/hermes/venv/bin/python \\
      /dev/stdin < tools/gen-agent-providers.py > /tmp/providers.kt

Then paste the block between the AGENT_PROVIDERS markers in MainActivity.kt. Re-run it
whenever HERMES_AGENT_REF is bumped.

Why generated at dev time rather than fetched at runtime: the catalog lives inside the agent
CONTAINER, and there is no channel from there to the phone. The box could relay it, but that
would mean letting the agent container write into /handoff — the volume that holds every
agent's Matrix credentials — which is not a trade worth making for a picker list.

What is filtered out, and why: auth types the phone cannot complete with a name and a text
field. `aws_sdk` (Bedrock) needs an IAM chain, `vertex` needs a GCP service account,
`external_process` needs a local binary, and `virtual` (Mixture of Agents) is a routing
preset over other providers rather than something you hold a credential for. All of them
remain configurable from Agent settings over Tor.
"""

import sys

sys.path.insert(0, "/opt/hermes/agent")

from hermes_cli.provider_catalog import provider_catalog  # noqa: E402

PHONE_CAPABLE = {"api_key", "oauth_device_code", "oauth_external", "oauth_minimax"}

# The catalog's own "custom" entry is unlabelled ("custom" / "direct API"); we append a
# better-worded one at the end, so drop it here rather than shipping both.
SKIP_SLUGS = {"custom"}

# Providers where the owner MUST supply a base URL. Deliberately not derived from
# `base_url_env_var`: nearly every provider defines one, but it is an *override* for people
# behind a proxy — not a required field. Deriving from it made the wizard demand a base URL
# for Anthropic, which has a perfectly good default.
REQUIRES_BASE_URL = {
    "azure-foundry",  # its own description says "user-supplied base URL"
    "lmstudio",       # points at a machine on your network
}


def kotlin_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def hint_for(p) -> str:
    """Prefer the catalog description, trimmed to something a phone dialog can show."""
    text = (p.description or p.label or "").strip()
    # Descriptions are written as "Label — detail"; the label is already on screen. Only the
    # em-dash form is safe to split on: " (" would decapitate "(slash-form model IDs)".
    if text.startswith(p.label) and " — " in text:
        text = text.split(" — ", 1)[1]
    elif text.startswith(p.label + " (") and text.endswith(")"):
        # "LM Studio (Local desktop app...)" — the label is already the row title.
        text = text[len(p.label) + 2:-1]
    text = text.strip().rstrip(".")
    if len(text) > 96:
        text = text[:93].rstrip() + "…"
    return text or p.label


def main() -> int:
    rows = []
    for p in sorted(provider_catalog(), key=lambda x: (x.order, x.slug)):
        if p.auth_type not in PHONE_CAPABLE or p.slug in SKIP_SLUGS:
            continue
        rows.append(
            '    AgentProvider("{slug}", "{label}", "{hint}"{oauth}{base}),'.format(
                slug=kotlin_escape(p.slug),
                label=kotlin_escape(p.label),
                hint=kotlin_escape(hint_for(p)),
                oauth=", oauth = true" if p.auth_type.startswith("oauth") else "",
                base=", needsBaseUrl = true" if p.slug in REQUIRES_BASE_URL else "",
            )
        )

    print("private val AGENT_PROVIDERS = listOf(")
    print('    AgentProvider("", "Same as my other agents", "Copies the setup you already have."),')
    for row in rows:
        print(row)
    print(
        '    AgentProvider("custom", "Custom endpoint", '
        '"Any OpenAI-compatible URL — Mistral, a self-hosted model, anything.", '
        "needsBaseUrl = true),"
    )
    print(")")
    print(f"// {len(rows)} providers generated from Hermes by tools/gen-agent-providers.py",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
