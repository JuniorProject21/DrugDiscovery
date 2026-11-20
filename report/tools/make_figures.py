"""Figures for Progress Report #2, sections 4.5-4.9 and 10 (drawn from the actual code)."""
import subprocess
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Polygon, Rectangle

OUT = Path("/Users/rawanyasir/senior_webapp/report/figures")
OUT.mkdir(parents=True, exist_ok=True)
plt.rcParams["font.family"] = "Arial"
DPI = 220

INK = "#1F2937"
MUTED = "#4B5563"
LINE = "#6B7280"
NAVY = "#1B2A4A"
# Same colours per layer as Figure 4-1 in the report.
LAYER = {
    "presentation": ("#EAF4FD", "#7FB3E3", NAVY),
    "application": ("#F0EFFC", "#A9A5E6", NAVY),
    "domain": ("#EAF7F2", "#7CC9B0", NAVY),
    "data": ("#FDF1E7", "#EFA56C", "#A24F12"),
    "database": ("#EAF3FC", "#8DB9E4", NAVY),
}


def canvas(w, h):
    fig = plt.figure(figsize=(w, h))
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, w)
    ax.set_ylim(0, h)
    ax.axis("off")
    return fig, ax


def box(ax, x, y, w, h, fill="white", edge="#9CA3AF", lw=0.9, r=0.06, z=1, ls="-"):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0,rounding_size={r}",
                                fc=fill, ec=edge, lw=lw, zorder=z, ls=ls))


def label(ax, x, y, s, size=7.5, weight="normal", color=INK, ha="center", va="center", z=5, **kw):
    ax.text(x, y, s, fontsize=size, fontweight=weight, color=color, ha=ha, va=va, zorder=z,
            linespacing=1.25, **kw)


def arrow(ax, x1, y1, x2, y2, color=LINE, lw=0.9, style="-|>", ls="-", z=3, rad=0.0, mut=7):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style, mutation_scale=mut, color=color,
                                 lw=lw, ls=ls, zorder=z, shrinkA=0, shrinkB=0,
                                 connectionstyle=f"arc3,rad={rad}"))


def titled_box(ax, x, y, w, h, title, desc, fill="white", edge="#C7CED9", tsize=7.8, dsize=6.6):
    box(ax, x, y, w, h, fill=fill, edge=edge, z=2)
    if desc:
        label(ax, x + w / 2, y + h * 0.70, title, size=tsize, weight="bold")
        label(ax, x + w / 2, y + h * 0.33, desc, size=dsize, color=MUTED)
    else:
        label(ax, x + w / 2, y + h / 2, title, size=tsize, weight="bold")


def save(fig, name):
    fig.savefig(OUT / name, dpi=DPI, facecolor="white")
    plt.close(fig)
    print("wrote", name)


# ---------------------------------------------------------------- 4-2 layers mapped to code
def fig_architecture():
    W, H = 6.6, 6.3
    fig, ax = canvas(W, H)
    bands = [
        ("presentation", "Presentation layer", "Android app\nKotlin + Jetpack Compose", [
            ("Compose screens", "Sign up, Log in, Home, Predict,\nResult, History, Insights, Profile"),
            ("InhibitViewModel", "screen state, live SMILES\ncheck, PDF export"),
            ("InhibitRepository", "Retrofit API client,\nsaved session (DataStore)"),
        ]),
        ("application", "Application layer", "FastAPI server\napi/ and services/", [
            ("REST routes  /api/v1", "auth, chem, predictions,\nmodel, health"),
            ("AuthService", "bcrypt passwords, signed tokens,\nreset codes, Google sign-in"),
            ("PredictionService", "validate, name, check cache,\npredict, save"),
        ]),
        ("domain", "Domain layer", "domain/", [
            ("Chemistry Service", "parse, standardise (RDKit),\nInChIKey, 2D drawing"),
            ("Inference Service", "Morgan r3/2048, Extra Trees,\nconformal, reliability"),
            ("Compound naming", "PubChem lookup by InChIKey\n(SMILES never sent)"),
        ]),
        ("data", "Data access layer", "SQLAlchemy 2.0\ndata/repositories.py", [
            ("User\nRepository", None), ("Molecule\nRepository", None), ("Prediction\nRepository", None),
            ("Submission\nRepository", None), ("PasswordReset\nRepository", None),
        ]),
        ("database", "Database layer", "PostgreSQL 17\n(SQLite for development)", [
            ("RDKit cartridge", "mol and mfp2 columns\nwith GiST indexes"),
            ("Five tables", "users, molecules, predictions,\nsubmissions, reset codes"),
            ("Alembic migrations", "versioned schema, applied\nautomatically at start-up"),
        ]),
    ]
    band_h, gap, top = 1.02, 0.25, H - 0.08
    mx0, mx1 = 1.78, W - 0.14
    centers = []
    for i, (key, name, sub, modules) in enumerate(bands):
        fill, edge, tcol = LAYER[key]
        y = top - band_h - i * (band_h + gap)
        box(ax, 0.06, y, W - 0.12, band_h, fill=fill, edge=edge, lw=1.1, r=0.08)
        label(ax, 0.2, y + band_h - 0.2, name, size=9.2, weight="bold", color=tcol, ha="left")
        label(ax, 0.2, y + band_h - 0.36, sub, size=6.9, color=MUTED, ha="left", va="top")
        n = len(modules)
        g = 0.1 if n > 3 else 0.12
        bw = (mx1 - mx0 - g * (n - 1)) / n
        bh = 0.74
        by = y + (band_h - bh) / 2
        for j, (t, d) in enumerate(modules):
            bx = mx0 + j * (bw + g)
            titled_box(ax, bx, by, bw, bh, t, d, tsize=7.6 if n == 3 else 7.0)
            if key == "presentation" and j < n - 1:
                arrow(ax, bx + bw + 0.01, by + bh / 2, bx + bw + g - 0.01, by + bh / 2, mut=6)
        centers.append(y)
    xa = (mx0 + mx1) / 2
    for i in range(len(bands) - 1):
        y_top = centers[i]
        y_bot = centers[i + 1] + band_h
        arrow(ax, xa, y_top - 0.02, xa, y_bot + 0.02, style="<|-|>", lw=1.0, color="#374151", mut=6)
    label(ax, xa + 0.1, (centers[0] + centers[1] + band_h) / 2, "HTTPS + JSON, Bearer token",
          size=6.8, color=MUTED, ha="left")
    label(ax, xa + 0.1, (centers[1] + centers[2] + band_h) / 2, "Python calls (one process)",
          size=6.8, color=MUTED, ha="left")
    label(ax, xa + 0.1, (centers[3] + centers[4] + band_h) / 2, "SQL", size=6.8, color=MUTED, ha="left")
    save(fig, "fig_arch_modules.png")


# ---------------------------------------------------------------- 4-3 navigation map
def fig_navigation():
    W, H = 6.6, 3.05
    fig, ax = canvas(W, H)
    # signed out
    box(ax, 0.06, 0.12, 2.3, 2.8, fill="#F7F8FA", edge="#C7CED9", r=0.08)
    label(ax, 0.2, 2.75, "Signed out", size=8.5, weight="bold", color=NAVY, ha="left")
    bw, bh = 0.9, 0.42
    nodes = {
        "Onboarding": (0.76, 2.1), "Log in": (0.22, 1.25), "Sign up": (1.32, 1.25),
        "Forgot password\n(6-digit code)": (0.22, 0.32),
    }
    for t, (x, y) in nodes.items():
        box(ax, x, y, bw, bh, fill="white", edge="#7FB3E3", z=2)
        label(ax, x + bw / 2, y + bh / 2, t, size=7.2, weight="bold" if "\n" not in t else "normal")
    arrow(ax, 1.05, 2.1, 0.75, 1.68)
    arrow(ax, 1.35, 2.1, 1.65, 1.68)
    arrow(ax, 1.12, 1.52, 1.32, 1.52, style="<|-|>")
    arrow(ax, 0.67, 1.25, 0.67, 0.75)
    label(ax, 1.75, 0.6, "Google sign-in\navailable on both", size=6.5, color=MUTED)

    # signed in
    box(ax, 2.72, 0.12, W - 2.78, 2.8, fill="#F7F8FA", edge="#C7CED9", r=0.08)
    label(ax, 2.86, 2.75, "Signed in: bottom navigation bar", size=8.5, weight="bold", color=NAVY, ha="left")
    tabs = ["Home", "History", "Predict", "Insights", "Profile"]
    tw, tg, tx0, ty = 0.62, 0.1, 2.92, 1.98
    pos = {}
    for i, t in enumerate(tabs):
        x = tx0 + i * (tw + tg)
        box(ax, x, ty, tw, 0.42, fill="#EDE9FE" if t == "Predict" else "white", edge="#A9A5E6", z=2)
        label(ax, x + tw / 2, ty + 0.21, t, size=7.4, weight="bold")
        pos[t] = x + tw / 2
    box(ax, tx0 + 0.55, 0.42, 1.55, 0.6, fill="white", edge="#7CC9B0", z=2)
    label(ax, tx0 + 0.55 + 0.775, 0.81, "Result", size=7.6, weight="bold")
    label(ax, tx0 + 0.55 + 0.775, 0.58, "verdict, reliability, compound,\nsimilar compounds, PDF export",
          size=6.2, color=MUTED)
    rx_mid = tx0 + 0.55 + 0.775
    for t in ("Home", "History", "Predict"):
        arrow(ax, pos[t], ty, rx_mid + (pos[t] - rx_mid) * 0.45, 1.02)
    box(ax, pos["Profile"] - 0.62, 0.42, 0.97, 0.6, fill="white", edge="#7CC9B0", z=2)
    label(ax, pos["Profile"] - 0.135, 0.81, "Account details", size=7.2, weight="bold")
    label(ax, pos["Profile"] - 0.135, 0.6, "name, change or\nset password", size=6.2, color=MUTED)
    arrow(ax, pos["Profile"], ty, pos["Profile"], 1.02)
    label(ax, pos["Insights"] + 0.05, 1.62, "Home and Insights cards\nopen History with a filter", size=6.2, color=MUTED)

    # sign-in transition
    arrow(ax, 2.38, 1.46, 2.9, 2.12, lw=1.1, color="#374151")
    label(ax, 2.55, 1.32, "signed in", size=6.6, color=MUTED, rotation=0)
    save(fig, "fig_navigation.png")


# ---------------------------------------------------------------- 4-4 sequence of a prediction
def fig_sequence():
    W, H = 6.6, 6.55
    fig, ax = canvas(W, H)
    parts = [("User", 0.42), ("Android app", 1.3), ("FastAPI\n(routes + services)", 2.72), ("Chemistry\nService", 3.72),
             ("Inference\nService", 4.6), ("Database", 5.45), ("PubChem", 6.18)]
    x = {name.split("\n")[0]: px for name, px in parts}
    top = H - 0.1
    hh = 0.42
    for name, px in parts:
        w = 1.08 if "routes" in name else 0.72 if px < 6 else 0.66
        box(ax, px - w / 2, top - hh, w, hh, fill="#EEF2F8", edge="#8DB9E4", z=4)
        label(ax, px, top - hh / 2, name, size=7.0, weight="bold", z=6)
        ax.plot([px, px], [0.12, top - hh], color="#9CA3AF", lw=0.8, ls=(0, (3, 3)), zorder=1)

    y = top - hh - 0.3
    step = 0.272

    def msg(a, b, text, ret=False, lane_note=None):
        nonlocal y
        xa, xb = x[a], x[b]
        arrow(ax, xa, y, xb, y, ls="--" if ret else "-", color="#374151" if not ret else "#6B7280", lw=0.85)
        mid = (xa + xb) / 2
        ax.text(mid, y + 0.035, text, fontsize=6.5, ha="center", va="bottom", color=INK, zorder=6,
                bbox=dict(boxstyle="square,pad=0.08", fc="white", ec="none"))
        y -= step

    def self_msg(a, text):
        nonlocal y
        xa = x[a]
        ax.add_patch(FancyArrowPatch((xa, y + 0.05), (xa, y - 0.08), arrowstyle="-|>", mutation_scale=6,
                                     color="#374151", lw=0.85, connectionstyle="bar,fraction=-0.9",
                                     shrinkA=0, shrinkB=0, zorder=3))
        ax.text(xa + 0.2, y - 0.015, text, fontsize=6.5, ha="left", va="center", color=INK, zorder=6,
                bbox=dict(boxstyle="square,pad=0.08", fc="white", ec="none"))
        y -= step

    def section(text):
        nonlocal y
        ax.text(0.08, y + 0.02, text, fontsize=7, fontweight="bold", color=NAVY, ha="left", va="center")
        y -= 0.2

    section("1. Live check while typing")
    msg("User", "Android app", "types a SMILES")
    msg("Android app", "FastAPI", "POST /chem/validate")
    msg("FastAPI", "Chemistry", "parse, standardise")
    msg("Chemistry", "FastAPI", "canonical SMILES, InChIKey", ret=True)
    msg("FastAPI", "PubChem", "look up name by InChIKey (first time only)")
    msg("PubChem", "FastAPI", "common and IUPAC name", ret=True)
    msg("FastAPI", "Android app", "valid, formula, weight, name", ret=True)
    msg("Android app", "FastAPI", "POST /chem/depict (2D drawing)")
    y -= 0.05
    section("2. Prediction")
    msg("User", "Android app", "taps Predict")
    msg("Android app", "FastAPI", "POST /predictions + token")
    self_msg("FastAPI", "check token, load user")
    msg("FastAPI", "Chemistry", "prepare(smiles)")
    msg("FastAPI", "Database", "find molecule and cached prediction")
    # alt frame
    y_alt_top = y + 0.12
    y -= 0.16
    msg("FastAPI", "Inference", "predict(mol)")
    msg("Inference", "FastAPI", "label, score, confidence, reliability", ret=True)
    msg("FastAPI", "Database", "save prediction")
    y_alt_bot = y + 0.12
    ax.add_patch(Rectangle((1.9, y_alt_bot), 3.9, y_alt_top - y_alt_bot, fill=False, ec="#7C3AED", lw=0.8, zorder=2))
    ax.add_patch(Rectangle((1.9, y_alt_top - 0.17), 1.42, 0.17, fc="#EDE9FE", ec="#7C3AED", lw=0.8, zorder=2))
    ax.text(1.95, y_alt_top - 0.085, "alt  [no cached prediction]", fontsize=6.3, color="#5B21B6", va="center",
            zorder=6)
    y -= 0.05
    msg("FastAPI", "Database", "save history entry (name or XYZ-###)")
    msg("FastAPI", "Android app", "201 Created: result JSON", ret=True)
    msg("Android app", "User", "Result screen", ret=True)
    save(fig, "fig_sequence_predict.png")


# ---------------------------------------------------------------- 4-5 prediction pipeline
def fig_pipeline():
    W, H = 6.6, 3.0
    fig, ax = canvas(W, H)
    bw, bh, g = 0.8, 0.62, 0.14
    y1 = 1.9
    steps = [
        ("SMILES", "as typed or\npasted", "#FFFFFF", "#9CA3AF"),
        ("Parse and\nvalidate", "readable error\nif invalid", LAYER["domain"][0], LAYER["domain"][1]),
        ("Standardise", "same as training\nnotebook", LAYER["domain"][0], LAYER["domain"][1]),
        ("Morgan FP", "radius 3,\n2048 bits", LAYER["domain"][0], LAYER["domain"][1]),
        ("Extra Trees", "500 trees", LAYER["domain"][0], LAYER["domain"][1]),
        ("Conformal\ndecision", "significance\n0.20", LAYER["domain"][0], LAYER["domain"][1]),
    ]
    xs = []
    for i, (t, d, f, e) in enumerate(steps):
        bx = 0.08 + i * (bw + g)
        xs.append(bx)
        box(ax, bx, y1, bw, bh, fill=f, edge=e, z=2)
        label(ax, bx + bw / 2, y1 + bh * 0.68, t, size=7.2, weight="bold")
        label(ax, bx + bw / 2, y1 + bh * 0.24, d, size=6.0, color=MUTED)
        if i:
            arrow(ax, bx - g + 0.01, y1 + bh / 2, bx - 0.01, y1 + bh / 2, mut=6)
    # output box spanning both rows
    ox = 0.08 + 6 * (bw + g)
    ow = W - ox - 0.08
    box(ax, ox, 0.5, ow, 2.02, fill="#F5F3FF", edge="#A78BFA", lw=1.1, z=2)
    label(ax, ox + ow / 2, 2.3, "Result", size=8, weight="bold")
    label(ax, ox + ow / 2, 1.95, "Active\nInactive\nUncertain", size=7, color=INK)
    label(ax, ox + ow / 2, 1.45, "+ confidence", size=6.5, color=MUTED)
    label(ax, ox + ow / 2, 0.95, "Reliability\nHigh / Medium /\nLow", size=6.6, color=INK)
    arrow(ax, xs[5] + bw + 0.01, y1 + bh / 2, ox - 0.01, y1 + bh / 2, mut=6)
    label(ax, xs[4] + bw / 2 + 0.47, y1 + bh + 0.12, "score = share of trees voting active", size=6.2, color=MUTED)
    label(ax, xs[2] + bw / 2, y1 + bh + 0.2,
          "largest fragment, normalise,\nneutralise, canonical tautomer", size=6.0, color=MUTED)

    # reliability branch
    y2 = 0.62
    branch = [
        ("Morgan FP", "radius 2,\n2048 bits", 3),
        ("Nearest training\ncompound", "Tanimoto\nsimilarity", 4),
        ("Reliability band", "High \u2265 0.70\nMedium \u2265 0.50", 5),
    ]
    for t, d, col in branch:
        bx = xs[col]
        box(ax, bx, y2, bw, bh, fill="#FFF7ED", edge="#EFA56C", z=2)
        label(ax, bx + bw / 2, y2 + bh * 0.70, t, size=6.8, weight="bold")
        label(ax, bx + bw / 2, y2 + bh * 0.25, d, size=6.0, color=MUTED)
    for col in (4, 5):
        arrow(ax, xs[col] - g + 0.01, y2 + bh / 2, xs[col] - 0.01, y2 + bh / 2, mut=6)
    # from standardise down to branch
    sx = xs[2] + bw / 2
    ax.plot([sx, sx], [y1, y2 + bh / 2], color=LINE, lw=0.9, zorder=1)
    arrow(ax, sx, y2 + bh / 2, xs[3] - 0.01, y2 + bh / 2, mut=6)
    arrow(ax, xs[5] + bw + 0.01, y2 + bh / 2, ox - 0.01, y2 + bh / 2, mut=6)
    label(ax, 0.1, 0.25, "Top row: the activity verdict.  Bottom row: how far the compound is from the "
          "training chemistry (applicability domain).", size=6.4, color=MUTED, ha="left")
    save(fig, "fig_pipeline.png")


# ---------------------------------------------------------------- 4-6 ER diagram (Graphviz)
def fig_er():
    def table(name, rows, note=None):
        cells = "".join(
            f'<TR><TD ALIGN="LEFT" PORT="{c}_w">{"<B>" if k == "PK" else ""}{c}{"</B>" if k == "PK" else ""}</TD>'
            f'<TD ALIGN="LEFT"><FONT COLOR="#4B5563">{t}</FONT></TD>'
            f'<TD ALIGN="LEFT" PORT="{c}_e"><FONT COLOR="#A24F12">{k or "&#160;"}</FONT></TD></TR>'
            for c, t, k in rows)
        extra = (f'<TR><TD COLSPAN="3" ALIGN="LEFT" BGCOLOR="#F3F4F6"><FONT POINT-SIZE="8" COLOR="#4B5563">'
                 f'{note}</FONT></TD></TR>') if note else ""
        return (f'{name} [label=<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="3" COLOR="#9CA3AF">'
                f'<TR><TD COLSPAN="3" BGCOLOR="#DCE6F4"><B><FONT COLOR="#1B2A4A">{name}</FONT></B></TD></TR>'
                f'{cells}{extra}</TABLE>>];')

    users = table("users", [
        ("id", "integer", "PK"), ("email", "varchar(320)", "UQ"), ("password_hash", "varchar(100)", ""),
        ("full_name", "varchar(200)", ""), ("google_sub", "varchar(255)", "UQ"),
        ("next_compound_number", "integer", ""), ("created_at", "timestamptz", "")])
    resets = table("password_reset_codes", [
        ("id", "integer", "PK"), ("user_id", "integer", "FK"), ("code_hash", "varchar(64)", ""),
        ("attempts", "integer", ""), ("expires_at", "timestamptz", ""), ("used_at", "timestamptz", ""),
        ("created_at", "timestamptz", "")])
    molecules = table("molecules", [
        ("id", "integer", "PK"), ("canonical_smiles", "text", "UQ"), ("inchikey", "varchar(27)", "IX"),
        ("fingerprint", "bytea", ""), ("formula", "varchar(100)", ""), ("molecular_weight", "float", ""),
        ("common_name", "varchar(200)", ""), ("iupac_name", "text", ""), ("pubchem_cid", "integer", ""),
        ("names_checked_at", "timestamptz", ""), ("created_at", "timestamptz", ""),
        ("mol", "mol (RDKit)", "GiST"), ("mfp2", "bfp (RDKit)", "GiST")],
        note="mol and mfp2 are generated columns added<BR ALIGN=\"LEFT\"/>when the RDKit cartridge is installed")
    predictions = table("predictions", [
        ("id", "integer", "PK"), ("molecule_id", "integer", "FK"), ("model_version", "varchar(100)", ""),
        ("label", "varchar(16)", ""), ("score", "float", ""), ("score_std", "float", ""),
        ("confidence", "float", ""), ("threshold", "float", ""), ("p_value_active", "float", ""),
        ("p_value_inactive", "float", ""), ("nearest_similarity", "float", ""), ("in_domain", "boolean", ""),
        ("reliability", "varchar(8)", ""), ("pic50", "float", ""), ("adme", "json", ""),
        ("created_at", "timestamptz", "")],
        note="UNIQUE (molecule_id, model_version):<BR ALIGN=\"LEFT\"/>one cached result per structure and model")
    submissions = table("submissions", [
        ("id", "integer", "PK"), ("user_id", "integer", "FK"), ("molecule_id", "integer", "FK"),
        ("prediction_id", "integer", "FK"), ("name", "varchar(50)", ""), ("input_smiles", "text", ""),
        ("is_saved", "boolean", ""), ("created_at", "timestamptz", "IX")])
    dot = f"""
digraph ER {{
  graph [rankdir=LR, nodesep=0.4, ranksep=0.7, newrank=true, fontname="Arial", bgcolor="white", pad=0.15];
  node [shape=plaintext, fontname="Arial", fontsize=9];
  edge [color="#4B5563", fontname="Arial", fontsize=8, arrowsize=0.8, penwidth=1.0];
  {users}
  {resets}
  {molecules}
  {predictions}
  {submissions}
  users:id_e:e -> submissions:user_id_w:w [dir=both, arrowtail=teetee, arrowhead=crowodot, xlabel="1 : N"];
  molecules:id_e:e -> submissions:molecule_id_w:w [dir=both, arrowtail=teetee, arrowhead=crowodot, xlabel="1 : N"];
  users:id_e:e -> password_reset_codes:user_id_w:w [dir=both, arrowtail=teetee, arrowhead=crowodot, xlabel="1 : N"];
  molecules:id_e:e -> predictions:molecule_id_w:w [dir=both, arrowtail=teetee, arrowhead=crowodot, xlabel="1 : N"];
  predictions:id_w:w -> submissions:prediction_id_e:e [dir=both, arrowtail=teetee, arrowhead=crowodot, xlabel="1 : N", constraint=false];
  submissions -> predictions [style=invis];
  submissions -> password_reset_codes [style=invis];
  {{rank=same; users; molecules;}}
  {{rank=same; password_reset_codes; predictions;}}
}}"""
    src = OUT.parent / "er_diagram.dot"
    src.write_text(dot)
    subprocess.run(["dot", "-Tpng", "-Gdpi=220", str(src), "-o", str(OUT / "fig_er.png")], check=True)
    src.unlink()
    print("wrote fig_er.png")


# ---------------------------------------------------------------- 4-x testing levels
def fig_testing():
    W, H = 6.6, 3.3
    fig, ax = canvas(W, H)
    levels = [  # bottom to top
        ("Unit tests", "#DBEAFE", "Chemistry rules (salts, charges, tautomers, readable errors), conformal p-values,\n"
         "confidence maths, rate limiter. Each function checked on its own with pytest."),
        ("Integration tests", "#E0E7FF", "Whole API through FastAPI's TestClient with a real database: accounts, password\n"
         "reset, Google sign-in, predictions, caching, history, privacy between users."),
        ("Consistency checks", "#D1FAE5", "Server cleaning = notebook prepare(); start-up check that 307 reference molecules\n"
         "featurise exactly as in training; database models = migrations."),
        ("System tests", "#FEF3C7", "Same suite on PostgreSQL 17 with and without the RDKit cartridge; upgrading a\n"
         "first-release database; Docker image on ARM and x86-64; Compose with HTTPS."),
        ("End-to-end tests", "#FCE7F3", "Android emulator against the running back-end: sign up, live check, prediction,\n"
         "history, PDF export; approved PARP-1 inhibitors and measured inactives."),
    ]
    n = len(levels)
    base_w, top_w, x_c = 2.05, 0.7, 1.42
    y0, lh, gap = 0.12, 0.6, 0.04
    for i, (name, color, desc) in enumerate(levels):
        yb = y0 + i * (lh + gap)
        yt = yb + lh
        wb = base_w - (base_w - top_w) * i / n
        wt = base_w - (base_w - top_w) * (i + 1) / n
        ax.add_patch(Polygon([(x_c - wb / 2, yb), (x_c + wb / 2, yb), (x_c + wt / 2, yt), (x_c - wt / 2, yt)],
                             closed=True, fc=color, ec="#6B7280", lw=0.8))
        label(ax, x_c, yb + lh / 2, name, size=7.3 if i < 4 else 6.6, weight="bold")
        label(ax, 2.62, yb + lh / 2, desc, size=6.6, color=INK, ha="left")
        ax.plot([x_c + (wb + wt) / 4 + 0.05, 2.55], [yb + lh / 2, yb + lh / 2], color="#D1D5DB", lw=0.7, zorder=0)
    arrow(ax, 0.2, 0.2, 0.2, H - 0.15, lw=0.8, color="#9CA3AF")
    label(ax, 0.1, H / 2, "fewer tests, slower, closer to real use", size=6.2, color=MUTED, rotation=90)
    save(fig, "fig_testing_levels.png")


# ---------------------------------------------------------------- 10-1 deployment
def fig_deployment():
    W, H = 6.6, 2.95
    fig, ax = canvas(W, H)
    # phones
    box(ax, 0.08, 1.45, 1.22, 0.95, fill=LAYER["presentation"][0], edge=LAYER["presentation"][1], z=2)
    label(ax, 0.69, 2.12, "Phones", size=8, weight="bold")
    label(ax, 0.69, 1.8, "InhibitAI Android app\n(team members and\nsupervisor)", size=6.4, color=MUTED)
    # server
    box(ax, 1.72, 0.1, 2.98, 2.75, fill="#F7F8FA", edge="#9CA3AF", r=0.08, ls="--")
    label(ax, 1.86, 2.66, "Linux server: Docker Compose", size=7.4, weight="bold", color=NAVY, ha="left")
    box(ax, 1.9, 1.45, 1.0, 0.95, fill="#FFFFFF", edge="#9CA3AF", z=2)
    label(ax, 2.4, 2.15, "Caddy 2", size=7.8, weight="bold")
    label(ax, 2.4, 1.76, "automatic HTTPS\ncertificates,\nports 80 and 443", size=6.1, color=MUTED)
    box(ax, 3.45, 1.45, 1.05, 0.95, fill=LAYER["application"][0], edge=LAYER["application"][1], z=2)
    label(ax, 3.975, 2.15, "api", size=7.8, weight="bold")
    label(ax, 3.975, 1.76, "FastAPI + Uvicorn,\nmodel bundle,\nnon-root user", size=6.1, color=MUTED)
    box(ax, 3.45, 0.25, 1.05, 0.85, fill=LAYER["database"][0], edge=LAYER["database"][1], z=2)
    label(ax, 3.975, 0.88, "db", size=7.8, weight="bold")
    label(ax, 3.975, 0.52, "PostgreSQL 17,\nvolume db-data\n(kept on restart)", size=6.1, color=MUTED)
    arrow(ax, 1.3, 1.92, 1.9, 1.92, lw=1.1, color="#374151")
    label(ax, 1.6, 2.04, "HTTPS", size=6.4, color=MUTED)
    arrow(ax, 2.9, 1.92, 3.45, 1.92)
    arrow(ax, 3.975, 1.45, 3.975, 1.1, style="<|-|>")
    label(ax, 2.42, 0.72, "Production mode refuses\nto start with a weak token\nsecret, SQLite, the\n"
          "placeholder model, or\nno model file.", size=6.0, color=MUTED)
    # external services, reached from the api container only
    ext = [("PubChem", "compound names\n(InChIKey only)", 2.05), ("SMTP server", "password-reset\nemails", 1.17),
           ("Google", "sign-in token\nkeys", 0.29)]
    for t, d, y in ext:
        box(ax, 5.25, y, 1.25, 0.62, fill="white", edge="#C7CED9", z=2)
        label(ax, 5.875, y + 0.44, t, size=7.2, weight="bold")
        label(ax, 5.875, y + 0.2, d, size=6.0, color=MUTED)
        arrow(ax, 4.5, 1.92 if y > 2 else 1.7 if y > 1 else 1.52, 5.25, y + 0.31, ls="--", lw=0.7)
    label(ax, 5.875, 2.8, "outbound HTTPS only", size=6.2, color=MUTED)
    save(fig, "fig_deployment.png")


if __name__ == "__main__":
    fig_architecture()
    fig_navigation()
    fig_sequence()
    fig_pipeline()
    fig_er()
    fig_testing()
    fig_deployment()
