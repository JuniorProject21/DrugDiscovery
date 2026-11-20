"""The chat behind "Learn more" on a result.

An open-weights model (Llama, Qwen, Mistral...) served over the OpenAI-compatible chat API, which
is what Groq, Together, OpenRouter, a Hugging Face router endpoint and a local Ollama all speak,
so the provider is a setting rather than code. Without one configured the endpoint says so plainly
and the app falls back to its own written explanation: it never makes an answer up.

What goes out is the compound and the numbers this server computed for it - never anything about
the user. The model is given those numbers and told to explain them; it is not asked to predict
anything, because the prediction is the trained model's job, not the language model's.
"""

import logging
from dataclasses import dataclass

import httpx

log = logging.getLogger(__name__)

MAX_HISTORY = 8
MAX_QUESTION = 500


class LlmUnavailable(RuntimeError):
    """No language model is configured, or the provider could not be reached."""


@dataclass(frozen=True)
class ChatTurn:
    role: str  # "user" or "assistant"
    content: str


class ChatClient:
    """A chat model reachable over the OpenAI-compatible /chat/completions API."""

    def __init__(self, *, base_url: str, api_key: str, model: str, max_tokens: int = 500,
                 timeout: float = 45.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.max_tokens = max_tokens
        self.timeout = timeout

    @property
    def enabled(self) -> bool:
        return bool(self.base_url and self.model)

    def reply(self, *, system: str, turns: list[ChatTurn]) -> str:
        if not self.enabled:
            raise LlmUnavailable(
                "The assistant isn't set up on this server yet, so there's nothing to ask. "
                "The panel above still explains the result."
            )
        messages = [{"role": "system", "content": system}]
        messages += [{"role": t.role, "content": t.content} for t in turns[-MAX_HISTORY:]]
        headers = {"Content-Type": "application/json"}
        if self.api_key:  # a local server (Ollama, llama.cpp) needs no key
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            response = httpx.post(
                f"{self.base_url}/chat/completions",
                headers=headers,
                json={
                    "model": self.model,
                    "messages": messages,
                    "max_tokens": self.max_tokens,
                    # Low, because the job is to explain given numbers, not to be imaginative.
                    "temperature": 0.2,
                },
                timeout=self.timeout,
            )
        except httpx.HTTPError as exc:
            log.warning("Chat provider unreachable: %s", exc)
            raise LlmUnavailable("Couldn't reach the assistant. Check your connection and try again.") from exc
        if response.status_code == 401:
            raise LlmUnavailable("The assistant's API key was refused. Check INHIBITAI_LLM_API_KEY.")
        if response.status_code == 429:
            raise LlmUnavailable("The assistant is rate-limited right now. Please try again in a minute.")
        if response.status_code >= 400:
            log.warning("Chat provider returned %s: %s", response.status_code, response.text[:400])
            raise LlmUnavailable("The assistant couldn't answer that. Please try again.")
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, ValueError) as exc:
            raise LlmUnavailable("The assistant sent an answer this app couldn't read.") from exc
        text = (content or "").strip()
        if not text:
            raise LlmUnavailable("The assistant sent an empty answer. Please try again.")
        return text


SYSTEM_PROMPT = """You are the assistant inside InhibitAI, an app that predicts whether a compound \
inhibits human PARP-1. A student or researcher is looking at one result and wants to understand it.

You will be given the facts InhibitAI computed for that compound. Those numbers are the truth:
- Never invent, change or round away a number, and never give a score or verdict that isn't listed.
- If you are asked something the facts don't cover, say what InhibitAI does and doesn't tell us.
- The fragment weights come from switching each fingerprint bit off and measuring how far the score \
moves, so they say what this model relied on, not what a chemist would call the binding group. You \
may add known medicinal chemistry context, but say clearly when you are doing that.
- The ADME values are computed from the structure with published rules (Lipinski, Veber, structural \
alerts), not predicted by a trained model. Never present them as measured or predicted ADME.

Answer in plain English, 120 words or fewer unless asked for more, no markdown headings. Explain \
reasoning rather than listing numbers the user can already see. Remember that this is a \
computational prediction that needs confirming in the lab, and never give medical advice."""


def facts_prompt(facts: dict) -> str:
    """The grounding block appended to the system prompt, built from this server's own numbers."""
    lines = [f"Compound: {facts.get('name') or 'unnamed'}", f"SMILES: {facts['canonical_smiles']}"]
    if facts.get("formula"):
        lines.append(f"Formula: {facts['formula']}")
    lines.append(
        f"Verdict: {facts['label']} (model score {facts['score']:.3f}, decision threshold "
        f"{facts['threshold']:.3f}, confidence {facts.get('confidence', 0) * 100:.0f}%)"
    )
    if facts.get("reliability"):
        similarity = facts.get("nearest_similarity")
        nearest = f", nearest training compound {similarity:.2f} similar" if similarity is not None else ""
        lines.append(f"Reliability: {facts['reliability']}{nearest}")
    lines.append(f"Model: {facts.get('model_version', 'unknown')}, an Extra Trees ensemble on Morgan "
                 "fingerprints with conformal prediction for the uncertain cases.")
    fragments = facts.get("fragments") or []
    if fragments:
        lines.append("Fragments that moved the score (positive = pushed towards active):")
        for f in fragments[:6]:
            lines.append(f"  {f['fragment']}  {f['weight']:+.3f}")
    adme = facts.get("adme") or {}
    if adme:
        p = adme.get("properties", {})
        lines.append(
            f"Rule-based ADME: absorption {adme.get('absorption')}, distribution "
            f"{adme.get('distribution')}, metabolism {adme.get('metabolism')}, toxicity alerts "
            f"{adme.get('toxicity')}. MW {p.get('molecular_weight')}, logP {p.get('logp')}, "
            f"TPSA {p.get('tpsa')}, QED {p.get('qed')}."
        )
    return "Facts for this compound:\n" + "\n".join(lines)
