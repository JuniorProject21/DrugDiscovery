"""The "Learn more" assistant: grounded in this server's numbers, and honest when switched off."""

import pytest

from app.services.llm import ChatClient, LlmUnavailable, facts_prompt
from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"


class FakeModel:
    """Stands in for the language model: records what it was told, answers with a fixed line."""

    def __init__(self, answer="The lactam group is what pushed this towards active."):
        self.enabled = True
        self.model = "fake-open-model"
        self.answer = answer
        self.calls = []

    def reply(self, *, system, turns):
        self.calls.append((system, turns))
        return self.answer

    @property
    def last_system(self):
        return self.calls[-1][0]

    @property
    def last_turns(self):
        return self.calls[-1][1]


@pytest.fixture
def client(make_client, toy_bundle):
    return make_client(toy_bundle)


def ask(client, auth, **body):
    return client.post("/api/v1/chat", json={"smiles": OLAPARIB, **body}, headers=auth)


def test_the_opening_answer_explains_the_result(client):
    auth = register(client)
    fake = FakeModel()
    client.app.state.container.chat = fake

    response = ask(client, auth)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["reply"] == fake.answer
    assert body["model"] == "fake-open-model"
    # With no question of their own, the user is asking for the explanation.
    assert fake.last_turns[-1].role == "user"
    assert "explain" in fake.last_turns[-1].content.lower()


def test_the_model_is_given_this_server_s_own_numbers(client):
    """The app sends only a SMILES, so the assistant cannot be fed a made-up score."""
    auth = register(client)
    fake = FakeModel()
    client.app.state.container.chat = fake
    predicted = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()

    ask(client, auth, name="16F")
    system = fake.last_system
    assert "16F" in system
    assert predicted["canonical_smiles"] in system
    assert f"{predicted['prediction']['score']:.3f}" in system
    assert f"{predicted['prediction']['threshold']:.3f}" in system
    assert predicted["prediction"]["label"] in system
    # The fragments from the explanation, and the rule-based ADME, are in there too.
    assert "Fragments that moved the score" in system
    assert "Rule-based ADME" in system


def test_the_model_is_told_not_to_invent_numbers_or_dress_adme_up(client):
    auth = register(client)
    fake = FakeModel()
    client.app.state.container.chat = fake
    ask(client, auth)
    system = fake.last_system.lower()
    assert "never invent" in system
    assert "not predicted by a trained model" in system
    assert "confirming in the lab" in system


def test_a_follow_up_question_keeps_the_conversation(client):
    auth = register(client)
    fake = FakeModel()
    client.app.state.container.chat = fake

    ask(client, auth, question="Why is the reliability high?", history=[
        {"role": "user", "content": "Explain this result to me in plain English."},
        {"role": "assistant", "content": "It looks like a PARP inhibitor."},
    ])
    roles = [t.role for t in fake.last_turns]
    assert roles == ["user", "assistant", "user"]
    assert fake.last_turns[-1].content == "Why is the reliability high?"


def test_without_a_model_configured_it_says_so_instead_of_answering(client):
    """A made-up answer would be worse than none (report section 4.3.5)."""
    auth = register(client)
    response = ask(client, auth)  # the default container has no LLM configured
    assert response.status_code == 501
    assert "isn't set up" in response.json()["detail"]


def test_a_provider_that_fails_is_reported_as_unavailable(client):
    auth = register(client)

    class Broken(FakeModel):
        def reply(self, *, system, turns):
            raise LlmUnavailable("Couldn't reach the assistant. Check your connection and try again.")

    client.app.state.container.chat = Broken()
    response = ask(client, auth)
    assert response.status_code == 503
    assert "Couldn't reach" in response.json()["detail"]


def test_the_chat_needs_a_signed_in_user_and_a_real_structure(client):
    auth = register(client)
    client.app.state.container.chat = FakeModel()
    assert client.post("/api/v1/chat", json={"smiles": OLAPARIB}).status_code == 401
    assert ask(client, auth).status_code == 200
    bad = client.post("/api/v1/chat", json={"smiles": "not a molecule"}, headers=auth)
    assert bad.status_code == 422


def test_a_question_that_is_too_long_is_refused(client):
    auth = register(client)
    client.app.state.container.chat = FakeModel()
    assert ask(client, auth, question="x" * 501).status_code == 422


def test_an_unconfigured_client_refuses_rather_than_calling_anything():
    off = ChatClient(base_url="", api_key="", model="")
    assert off.enabled is False
    with pytest.raises(LlmUnavailable, match="isn't set up"):
        off.reply(system="s", turns=[])


def test_the_facts_block_never_claims_adme_was_predicted():
    block = facts_prompt({
        "canonical_smiles": "NC(=O)c1ccccc1", "name": "Benzamide", "formula": "C7H7NO",
        "label": "inactive", "score": 0.1, "threshold": 0.795, "confidence": 1.0,
        "reliability": "high", "nearest_similarity": 0.9, "model_version": "ET v1.0",
        "fragments": [{"fragment": "C(N)(=O)c", "weight": 0.11}],
        "adme": {"absorption": "high", "distribution": "high", "metabolism": "good",
                 "toxicity": "low", "properties": {"molecular_weight": 121.14, "logp": 0.6,
                                                   "tpsa": 43.1, "qed": 0.5}},
    })
    assert "Rule-based ADME" in block
    assert "0.100" in block and "0.795" in block
    assert "C(N)(=O)c  +0.110" in block
