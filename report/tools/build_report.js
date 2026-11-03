// Builds the revised sections 4.5-4.9 and 10.0 of Senior Progress Report #2 as a .docx.
const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, Table, TableRow, TableCell,
  WidthType, ShadingType, BorderStyle, ImageRun, Header, Footer, PageNumber, LevelFormat, PageBreak,
  VerticalAlign,
} = require("docx");

const FIG = "/Users/rawanyasir/senior_webapp/report/figures";
const OUT = "/Users/rawanyasir/senior_webapp/report/Progress_Report_2_sections_4.5-4.9_and_10.docx";

const NAVY = "1F3A5F";
const BODY_FONT = "Times New Roman";
const CODE_FONT = "Courier New";
const TEXT_W = 9026; // A4 width minus 2 x 1440 margins, in DXA
const PX_W = 600;    // full text width in pixels (96 dpi)

// ---------------------------------------------------------------- image sizes
function imageSize(file) {
  const b = fs.readFileSync(file);
  if (b[0] === 0x89) return { w: b.readUInt32BE(16), h: b.readUInt32BE(20), type: "png", data: b };
  let i = 2;
  while (i < b.length) {
    const marker = b[i + 1];
    const len = b.readUInt16BE(i + 2);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7), type: "jpg", data: b };
    }
    i += 2 + len;
  }
  throw new Error("no size for " + file);
}

// ---------------------------------------------------------------- inline markup: `code` and **bold**
function runs(text, opts = {}) {
  const out = [];
  for (const part of text.split(/(`[^`]+`|\*\*[^*]+\*\*)/)) {
    if (!part) continue;
    if (part.startsWith("`")) out.push(new TextRun({ text: part.slice(1, -1), font: CODE_FONT, size: (opts.size || 24) - 2 }));
    else if (part.startsWith("**")) out.push(new TextRun({ text: part.slice(2, -2), bold: true, ...opts }));
    else out.push(new TextRun({ text: part, ...opts }));
  }
  return out;
}

const P = (text, opts = {}) => new Paragraph({
  alignment: opts.align || AlignmentType.JUSTIFIED,
  spacing: { after: opts.after ?? 160, line: 276 },
  keepNext: opts.keepNext,
  children: runs(text, opts.run || {}),
});
const H1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)] });
const H2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(t)] });
const H3 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(t)] });
const bullet = (text) => new Paragraph({
  numbering: { reference: "bullets", level: 0 }, alignment: AlignmentType.JUSTIFIED,
  spacing: { after: 80, line: 276 }, children: runs(text),
});

function caption(label, text) {
  return new Paragraph({
    alignment: AlignmentType.CENTER, spacing: { before: 60, after: 240 },
    children: [new TextRun({ text: label + ": ", bold: true, size: 22 }), new TextRun({ text, size: 22 })],
  });
}
function tableCaption(label, text) {
  return new Paragraph({
    alignment: AlignmentType.CENTER, spacing: { before: 120, after: 80 }, keepNext: true,
    children: [new TextRun({ text: label + ": ", bold: true, size: 22 }), new TextRun({ text, size: 22 })],
  });
}

function figure(file, label, text, widthPx = PX_W) {
  const img = imageSize(path.join(FIG, file));
  const h = Math.round(widthPx * img.h / img.w);
  return [
    new Paragraph({
      alignment: AlignmentType.CENTER, spacing: { before: 120, after: 0 }, keepNext: true,
      children: [new ImageRun({ type: img.type, data: img.data, transformation: { width: widthPx, height: h },
        altText: { title: label, description: text, name: label } })],
    }),
    caption(label, text),
  ];
}

const noBorder = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const noBorders = { top: noBorder, bottom: noBorder, left: noBorder, right: noBorder };

// Two phone screenshots side by side, each with its own caption (as in section 4.8 of the report).
function figurePair(a, b) {
  const colW = TEXT_W / 2;
  const cell = ([file, label, text]) => {
    const img = imageSize(path.join(FIG, file));
    const w = 235;
    const h = Math.round(w * img.h / img.w);
    return new TableCell({
      width: { size: colW, type: WidthType.DXA }, borders: noBorders,
      children: [
        new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 0 },
          children: [new ImageRun({ type: img.type, data: img.data, transformation: { width: w, height: h },
            altText: { title: label, description: text, name: label } })] }),
        caption(label, text),
      ],
    });
  };
  return new Table({
    width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: [colW, colW],
    borders: { ...noBorders, insideHorizontal: noBorder, insideVertical: noBorder },
    rows: [new TableRow({ cantSplit: true, children: [cell(a), cell(b)] })],
  });
}

// ---------------------------------------------------------------- code listings
function codeLineRuns(line, lang) {
  // Grey italic comments; everything else plain. Quote-aware so "#" inside strings isn't a comment.
  const marker = lang === "kotlin" ? "//" : "#";
  let inS = null;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inS) { if (ch === inS && line[i - 1] !== "\\") inS = null; continue; }
    if (ch === '"' || ch === "'") { inS = ch; continue; }
    if (line.startsWith(marker, i)) {
      return [
        new TextRun({ text: line.slice(0, i), font: CODE_FONT, size: 16 }),
        new TextRun({ text: line.slice(i), font: CODE_FONT, size: 16, color: "5B6B7F", italics: true }),
      ];
    }
  }
  return [new TextRun({ text: line.length ? line : " ", font: CODE_FONT, size: 16 })];
}

function code(src, lang = "python") {
  const lines = src.replace(/^\n/, "").replace(/\s+$/, "").split("\n");
  for (const l of lines) if (l.length > 92) console.warn(`long code line (${l.length}): ${l}`);
  const border = { style: BorderStyle.SINGLE, size: 4, color: "C9D1DC" };
  return new Table({
    width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: [TEXT_W],
    rows: [new TableRow({ cantSplit: lines.length < 30, children: [new TableCell({
      width: { size: TEXT_W, type: WidthType.DXA },
      borders: { top: border, bottom: border, left: border, right: border },
      shading: { fill: "F4F6F9", type: ShadingType.CLEAR, color: "auto" },
      margins: { top: 100, bottom: 100, left: 140, right: 100 },
      children: lines.map((l) => new Paragraph({ spacing: { after: 0, line: 240 }, keepNext: true,
        children: codeLineRuns(l, lang) })),
    })] })],
  });
}
const listing = (label, text, src, lang) => [code(src, lang), caption(label, text)];

// ---------------------------------------------------------------- data tables
function dataTable(widths, header, rows, opts = {}) {
  const total = widths.reduce((a, b) => a + b, 0);
  if (total !== TEXT_W) throw new Error(`table widths ${total} != ${TEXT_W}`);
  const border = { style: BorderStyle.SINGLE, size: 4, color: "B4BCC8" };
  const borders = { top: border, bottom: border, left: border, right: border };
  const size = opts.size || 21;
  const mk = (text, i, isHead, rowOpts = {}) => new TableCell({
    width: { size: widths[i], type: WidthType.DXA }, borders, verticalAlign: VerticalAlign.TOP,
    shading: isHead ? { fill: "E7ECF4", type: ShadingType.CLEAR, color: "auto" }
      : rowOpts.fill ? { fill: rowOpts.fill, type: ShadingType.CLEAR, color: "auto" } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: String(text).split("\n").map((t) => new Paragraph({ spacing: { after: 0, line: 252 },
      children: isHead ? [new TextRun({ text: t, bold: true, color: NAVY, size })]
        : runs(t, { size, bold: rowOpts.bold }) })),
  });
  return new Table({
    width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: widths,
    rows: [
      new TableRow({ tableHeader: true, cantSplit: true, children: header.map((h, i) => mk(h, i, true)) }),
      ...rows.map((r) => {
        const rowOpts = Array.isArray(r) ? {} : r.opts;
        const cells = Array.isArray(r) ? r : r.cells;
        return new TableRow({ cantSplit: true, children: cells.map((c, i) => mk(c, i, false, rowOpts)) });
      }),
    ],
  });
}
const spacer = () => new Paragraph({ spacing: { after: 120 }, children: [] });

// =================================================================== content
const notes = [
  new Paragraph({ spacing: { after: 200 }, children: [new TextRun({ text: "How to merge these sections (delete this page before submitting)", bold: true, size: 30, color: NAVY })] }),
  P("This file contains the revised **Sections 4.5 to 4.9** and the revised **Section 10.0** of Senior Progress Report #2, written from the code of the application as it stands on 31 October 2026. Your original paragraphs are kept word for word at the start of each subsection; everything after them is new. The formatting follows the report (Times New Roman, navy headings, the same caption style), so the sections can be copied into the Google Doc as they are."),
  bullet("Replace the current Sections 4.5 to 4.8 with Sections 4.5 to 4.8 of this file, and add the new Section 4.9 (Software Testing) after 4.8."),
  bullet("Replace the current Section 10.0 with Section 10.0 of this file. It is now titled **Work in Progress: Application Implementation** and separates what is finished from what is still being done."),
  bullet("The figures of the GUI/UX prototype are renumbered because five new figures come before them: old Figures 4-2 to 4-9 become **Figures 4-7 to 4-14**. New figures are 4-2 to 4-6, 4-15 to 4-17 and 10-1. Section 11 figures are unchanged."),
  bullet("New tables are 4-2 to 4-7 and 10-2; Table 10-1 is updated. Code is shown in numbered **Listings 4-1 to 4-12**."),
  bullet("Elsewhere in the report, please update: Section 5.4 (\"66 automated tests\" is now 77, or 111 including the PostgreSQL runs, and the app has been tested end to end on the emulator but not yet on real phones); Section 9.1 (the ER model is now provided as Figure 4-6); Section 9.2 and the introduction of Section 3.0 (the application is largely built but still in progress, see Section 10.0); the Table of Contents, List of Figures and List of Tables."),
  bullet("Suggested additions to the Abbreviations: InChI (International Chemical Identifier), JWT (JSON Web Token), SMTP (Simple Mail Transfer Protocol), SQL (Structured Query Language)."),
  P("All figures are also saved as separate image files in the `report/figures` folder next to this document, in case you prefer to insert them yourself.", { after: 0 }),
  new Paragraph({ children: [new PageBreak()] }),
];

// ---------------------------------------------------------------- 4.5
const s45 = [
  H2("4.5 Application Architecture Decisions"),
  P("The previously proposed architecture did not center on the importance of distinguishing between different services and encompassing the database part as its own modularised layer. The newly improved architecture was decided to be separated into the layers described below."),
  P("During this phase each layer was implemented as its own part of the code base, so that every box in the design (Figure 4-1) now corresponds to concrete code (Figure 4-2). The Android application forms the presentation layer, and the other four layers run inside one back-end server written in Python with FastAPI. Keeping these four layers in a single process keeps hosting simple for a small team, while the boundaries between them are strict: each layer only calls the layer directly below it, so the Inference Service could later be moved to a server of its own without changing the other layers."),
  ...figure("existing/fig4-1_architecture_summary.jpg", "Figure 4-1", "Summary of the application architecture."),
  ...figure("fig_arch_modules.png", "Figure 4-2", "The implemented architecture, showing which part of the code base realises each layer.", 560),

  H3("4.5.1 Presentation layer"),
  P("This is done to enforce the ease of scalability; if this application were to be expanded, this entire module could be its own individual server. This disallows any potential disruptions that could come with grouping it with another module, service or interface. Not only does this aid in scalability, it also means that no additional load or time wastage would occur, since simply visualising a compound as a 2D structure or canonicalising a SMILES string would otherwise force the application to also load the hybrid model into memory if those two services were contained in one bigger cluster. In essence, displaying a 2D compound could be as costly as running an entire model if not for this distinct split. This layer contains the UI aspects of the application, through which the user primarily interacts with the application’s features. This is where the user inputs their SMILES, views the 2D structural image representation of the compound, and views its activity prediction. It is exclusively used as a layer for rendering purposes; no additional requirements are placed upon it aside from the communication to and from the actual application layer. The obvious reasoning here lies with the ability to remove, replace or update this service without affecting any of the underlying infrastructure of the app."),
  P("**Implementation.** The presentation layer is the native Android application, written in Kotlin with Jetpack Compose (minimum Android 8.0, API level 26). It uses a single-activity design: one `MainActivity` hosts a Compose navigation graph, and every screen is a composable function that only draws the state it is given and reports the user’s actions upwards. Figure 4-3 shows how the screens are connected. A signed-out user moves between onboarding, log in, sign up and password reset. Once signed in, the five main areas (Home, History, Predict, Insights and Profile) are reached from a bottom navigation bar, and any new or stored prediction opens the Result screen."),
  ...figure("fig_navigation.png", "Figure 4-3", "Navigation between the screens of the Android application."),
  P("Behind the screens sit two classes. `InhibitViewModel` holds the state of the whole app (the signed-in user, the prediction history, the state of the SMILES check and any error message) and survives screen rotation. `InhibitRepository` is the only class that talks to the server. It wraps the Retrofit API client shown in Listing 4-1, attaches the user’s token to every request, turns server errors into sentences that can be shown to the user, and keeps the session in Android DataStore so that the user stays signed in between launches. The screens therefore never handle HTTP or JSON, which keeps this layer limited to rendering, as intended in the design."),
  ...listing("Listing 4-1", "Part of the Retrofit interface through which the app calls the back-end (data/remote/InhibitApi.kt).", `
/** The backend's REST API (all endpoints except auth require a bearer token). */
interface InhibitApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("api/v1/chem/validate")
    suspend fun validate(@Body body: SmilesRequest): ValidateResponse

    @POST("api/v1/chem/depict")
    suspend fun depict(@Body body: DepictRequest): ResponseBody

    @POST("api/v1/predictions")
    suspend fun predict(@Body body: SmilesRequest): SubmissionDto

    @GET("api/v1/predictions")
    suspend fun history(@Query("limit") limit: Int = 1000): SubmissionPage

    // ... register, Google sign-in, password reset, rename, save, delete, similar compounds
}`, "kotlin"),
  P("Two design choices on the prediction screens are worth noting. First, the SMILES is checked while the user types. The ViewModel waits until typing pauses for 0.4 seconds and then asks the server to validate the string (Listing 4-2), so the user sees a readable error, or the compound’s formula, molecular weight and, for a known compound, its name, before pressing Predict. The 2D drawing is produced by the server’s Chemistry Service rather than on the phone, so the app does not need its own copy of RDKit, and drawings are kept in a memory cache on the phone so lists do not download them twice. Second, the Result screen never shows a verdict without its context. A reliability card explains in plain language when the model answered Uncertain, or when the compound is far from the training chemistry (Low reliability), which is the front-end expression of the model behaviour described in Sections 4.3.3 to 4.3.5."),
  ...listing("Listing 4-2", "Live SMILES check, sent 0.4 seconds after the user stops typing (ui/InhibitViewModel.kt).", `
fun checkSmiles(smiles: String) {
    checkJob?.cancel()                     // a new keystroke cancels the previous check
    val input = smiles.trim()
    if (input.isEmpty()) { smilesCheck = null; return }
    smilesCheck = SmilesCheck.Checking
    checkJob = viewModelScope.launch {
        delay(400)                         // wait until the user stops typing
        guarded { repo.validate(input) }
            .onSuccess { r ->
                smilesCheck = if (r.valid) {
                    SmilesCheck.Valid(input, r.canonicalSmiles.orEmpty(), r.formula,
                                      r.molecularWeight, r.commonName)
                } else {
                    SmilesCheck.Invalid(r.error ?: "Invalid SMILES.")
                }
            }
            .onFailure {
                smilesCheck =
                    SmilesCheck.Unavailable(it.message ?: "Couldn't check this SMILES.")
            }
    }
}`, "kotlin"),
  P("The remaining front-end features follow the same pattern: History offers search, filters (Active, Inactive, Uncertain, Saved), sorting, bookmarking and deletion, and any entry can be renamed from its Result screen; Insights draws its totals, distribution and 7-day chart from the user’s real history; any result can be exported as a PDF report generated on the phone and shared through Android’s share sheet; and the Account screen lets the user change their name and change or set a password. Google sign-in uses Android’s Credential Manager, which hands the app a Google ID token that the back-end verifies."),

  H3("4.5.2 Application layer"),
  P("This layer handles most of the coordination that could be involved in this application; it requests and handles authentication/authorisation of the users that are strictly interacting with the presentation layer. In a hypothetical use case, the user is authenticated through this layer, which will then call upon a service that is specifically made to validate the SMILES compound. This layer can also perform checks against the data access layer to see if any cached predictions were made regarding this specific compound. This is done to ensure speed and efficiency along with a preservation of the additional resources that would be wasted if the model were to be run again. This layer also communicates with the service that is responsible for saving this result into the database."),
  P("**Implementation.** The application layer is a REST API built with FastAPI. All endpoints sit under the prefix `/api/v1` and exchange JSON; Table 4-2 lists them. FastAPI checks every request body against a schema before any of our code runs, and every error is returned as one readable sentence in a `detail` field that the app shows to the user as it is. The interactive documentation that FastAPI generates from the same schemas (served at `/docs` during development) was used by the front-end as the reference for the API."),
  tableCaption("Table 4-2", "REST API endpoints provided by the back-end (all under /api/v1)."),
  dataTable([900, 2850, 4076, 1200], ["Method", "Path", "Purpose", "Sign-in"], [
    ["POST", "/auth/register", "Create an account (name, email, password)", "No"],
    ["POST", "/auth/login", "Sign in and receive a token", "No"],
    ["POST", "/auth/google", "Sign in with a Google ID token", "No"],
    ["POST", "/auth/password-reset/request", "Email a 6-digit reset code", "No"],
    ["POST", "/auth/password-reset/confirm", "Set a new password using the code", "No"],
    ["GET, PATCH", "/auth/me", "Read or edit the user’s profile", "Yes"],
    ["POST", "/auth/change-password", "Change or set a password", "Yes"],
    ["POST", "/chem/validate", "Check a SMILES; return formula, weight and name", "Yes"],
    ["POST", "/chem/depict", "2D drawing of a structure (PNG or SVG)", "Yes"],
    ["POST", "/predictions", "Predict a compound and add it to the history", "Yes"],
    ["GET", "/predictions", "The user’s prediction history", "Yes"],
    ["GET, PATCH, DELETE", "/predictions/{id}", "Open, rename, bookmark or delete one entry", "Yes"],
    ["POST", "/predictions/similar", "Earlier entries similar to a structure", "Yes"],
    ["GET", "/model", "Model version, threshold and settings", "Yes"],
    ["GET", "/health", "Server and model status", "No"],
  ]),
  spacer(),
  P("**Authentication and authorisation.** Passwords are stored only as bcrypt hashes. A successful sign-in returns a signed token (a JSON Web Token) that the app sends in the `Authorization` header of every later request. A FastAPI dependency, `get_current_user`, checks the token and loads the user, so each endpoint only has to declare that it needs a signed-in user. Every history query is filtered by that user, so one user can never read another user’s predictions. A failed log-in takes the same time whether or not the email exists, so the API does not reveal which emails have accounts, and repeated failed attempts are rate limited. On a hosted server, sign-up can be limited to the team’s email addresses. A forgotten password is reset with a 6-digit code sent by email; the code expires after 15 minutes and stops working after five wrong guesses, and only a keyed hash of it is stored. Google sign-in is accepted only after the Google ID token has been verified against Google’s published keys."),
  P("**Prediction workflow.** The coordination described above is implemented in `PredictionService` (Listing 4-3). For each request it (1) asks the Chemistry Service to validate and standardise the SMILES, (2) looks up the compound’s name on PubChem the first time this structure is seen, (3) finds or creates the molecule record, (4) reuses the stored prediction when the same structure has already been predicted by the same model version, and otherwise runs the Inference Service and stores the result, and (5) adds a new entry to the user’s history. Because the cache key is the standardised structure rather than the text that was typed, a salt form or another tautomer of a compound that was already predicted is also answered from the cache. The cache is shared between users, while history and the XYZ numbering are kept per user. Figure 4-4 shows the complete exchange, from typing a SMILES to seeing the result, as a sequence diagram."),
  ...listing("Listing 4-3", "The prediction workflow in the application layer (services/predictions.py, shortened).", `
def predict(self, user: User, smiles: str) -> tuple[Submission, bool]:
    prepared = self.chemistry.prepare(smiles)      # validate + standardise
    model = self.inference.load()                  # loaded once, then reused
    names = self._names_to_record(prepared)        # PubChem, first time only

    molecule = self.molecules.get_or_create(
        canonical_smiles=prepared.canonical_smiles, inchikey=prepared.inchikey,
        fingerprint=self.chemistry.similarity_fingerprint(prepared.mol), ...)
    prediction = self.predictions.get_cached(molecule.id, model.model_version)
    cached = prediction is not None
    if not cached:
        result = model.predict(prepared.mol)
        prediction = self.predictions.get_or_create(
            molecule_id=molecule.id, model_version=model.model_version,
            values=asdict(result))

    # Known compounds keep their name (e.g. "Olaparib"); others get XYZ-001, XYZ-002, ...
    number = self.users.take_compound_number(user.id)
    name = molecule.common_name or f"{self.compound_prefix}-{number:03d}"
    submission = self.submissions.create(user_id=user.id, molecule=molecule,
                                         prediction=prediction, name=name,
                                         input_smiles=prepared.input_smiles)
    self.session.commit()
    return submission, cached`),
  P("The endpoint that exposes this workflow (Listing 4-4) is deliberately thin. Its only other job is to turn the two expected failures into the right HTTP responses: an invalid SMILES becomes a 422 response carrying the Chemistry Service’s explanation, and a missing model becomes a 503 response. The server never returns a made-up answer when no model is loaded, which an automated test checks (Section 4.9.2)."),
  ...listing("Listing 4-4", "The prediction endpoint (api/routes/predictions.py).", `
@router.post("", response_model=PredictResponse, status_code=status.HTTP_201_CREATED)
def create_prediction(body: SmilesRequest, user: User = Depends(get_current_user),
                      service: PredictionService = Depends(get_prediction_service),
                      target: str = Depends(_target)):
    try:
        submission, cached = service.predict(user, body.smiles)
    except InvalidSmilesError as exc:
        raise HTTPException(422, str(exc))           # readable reason for the app
    except ModelUnavailableError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc))
    return PredictResponse.of(submission, target, cached=cached)`),
  ...figure("fig_sequence_predict.png", "Figure 4-4", "Sequence of messages when a user checks and then predicts a compound.", 560),

  H3("4.5.3 Domain layer, Chemistry Service and Inference Service"),
  P("This is the primary changed module in our architecture; this layer is effectively split into two separate services. The Chemistry Service is responsible for parsing, validating and canonicalising the SMILES compound and the aforementioned 2D representation of it. As for the Inference Service, it is the “core” of this application; it contains the blocks of code required to featurise the compound via Morgan fingerprint featurisation, and it also houses the models developed."),
  P("**Chemistry Service.** The Chemistry Service (`domain/chemistry.py`) has no knowledge of the model, so validating or drawing a compound never loads the classifier into memory, which is the reason for the split described above. Parsing is done in two passes so that the user is given a specific reason when a SMILES is wrong, for example an unclosed ring or an atom with too many bonds, instead of a generic failure. The standardisation step must be identical to the cleaning that the training data went through; otherwise the model would receive fingerprints that differ from the ones it learned from, and its answers would be silently wrong. Listing 4-5 shows that step. It matches the `prepare()` function of our training notebook line for line, and an automated test compares the two on difficult inputs such as salts, charged species and tautomers (Section 4.9.2). The service also computes the InChIKey used for the name lookup, the formula and molecular weight, and draws the 2D structure in light or dark colours for the app."),
  ...listing("Listing 4-5", "Standardisation in the Chemistry Service, identical to the training notebook (domain/chemistry.py).", `
def standardise(self, mol: Chem.Mol) -> Chem.Mol:
    """Keep the largest fragment (strips salts and solvents), normalise functional
    groups, neutralise charges and canonicalise the tautomer (at most 200 enumerated).
    If any step fails, the molecule is used as parsed, exactly as in the notebook."""
    try:
        m = rdMolStandardize.LargestFragmentChooser().choose(mol)
        m = rdMolStandardize.Normalizer().normalize(m)
        m = rdMolStandardize.Uncharger().uncharge(m)
        if self.tautomer_canonicalization:
            enumerator = rdMolStandardize.TautomerEnumerator()
            enumerator.SetMaxTautomers(self.max_tautomers)
            m = enumerator.Canonicalize(m)
        return m
    except Exception:
        return mol`),
  P("**Inference Service.** The Inference Service (`domain/inference/`) loads the trained model once, in the background when the server starts, and keeps it in memory. The model is exported from our Colab notebook as a single bundle file containing the 500-tree Extra Trees classifier, the fingerprint settings (Morgan, radius 3, 2,048 bits), the decision threshold (0.795), the calibration scores used by conformal prediction, the 4,942 training compounds used for the applicability domain, and 307 reference molecules stored together with the exact feature rows the notebook computed for them. On start-up the server recomputes those rows with its own code and refuses to serve the model if a single row differs, so a mismatch between training and serving cannot go unnoticed (Section 4.9.3). Figure 4-5 summarises the path from a SMILES to a result, and Listing 4-6 shows the core of a prediction."),
  ...figure("fig_pipeline.png", "Figure 4-5", "From SMILES to result inside the domain layer."),
  ...listing("Listing 4-6", "The core of a prediction in the Inference Service (domain/inference/service.py, shortened).", `
def predict(self, mol: Chem.Mol) -> InferenceResult:
    b = self.bundle
    x = self.featurizer.transform([mol])            # Morgan radius 3, 2048 bits
    p = float(np.mean([est.predict_proba(x)[0, idx]
                       for est, idx in zip(b.classifiers, b.positive_index)]))
    decision = b.conformal.decide(p)                # active | inactive | uncertain

    # Applicability domain: similarity to the closest training compound
    fp = self._ad_generator.GetFingerprint(mol)     # Morgan radius 2, 2048 bits
    nearest = float(max(DataStructs.BulkTanimotoSimilarity(fp, self._ad_fps)))
    ad = b.applicability_domain
    reliability = ("high" if nearest >= ad.high_similarity           # 0.70
                   else "medium" if nearest >= ad.min_similarity     # 0.50
                   else "low")
    return InferenceResult(label=decision.label, score=p,
                           confidence=decision.confidence, nearest_similarity=nearest,
                           reliability=reliability, ...)`),
  P("**How a verdict is decided.** The score is the fraction of the 500 trees that vote active. It ranks compounds well but is not a calibrated probability, so the confidence shown in the app comes from the conformal step instead. That step compares the score with the scores that held-out actives and held-out inactives received during training (Listing 4-7). Each label receives a p-value, and a label is rejected when its p-value is at or below the significance level of 0.20. The model commits to Active or Inactive only when exactly one label survives; when both survive, or both are rejected, it answers Uncertain. Table 4-3 summarises the outputs that the user sees."),
  ...listing("Listing 4-7", "The Mondrian conformal decision (domain/inference/conformal.py).", `
def decide(self, score: float) -> ConformalDecision:
    pa = self._p_value(self._active_scores, 1.0 - score)   # how unusual for an active
    pi = self._p_value(self._inactive_scores, score)       # how unusual for an inactive
    active_ok, inactive_ok = pa > self.significance, pi > self.significance
    if active_ok and not inactive_ok:
        return ConformalDecision("active", pa, pi, 1.0 - pi)
    if inactive_ok and not active_ok:
        return ConformalDecision("inactive", pa, pi, 1.0 - pa)
    if active_ok and inactive_ok:          # both labels are plausible
        return ConformalDecision("uncertain", pa, pi, 1.0 - min(pa, pi))
    return ConformalDecision("uncertain", pa, pi, max(pa, pi))   # looks like neither`),
  tableCaption("Table 4-3", "Outputs shown to the user and how each one is decided."),
  dataTable([1500, 1900, 5626], ["Output", "Values", "How it is decided"], [
    ["Verdict", "Active, Inactive or Uncertain", "Conformal prediction on the Extra Trees score at significance 0.20. Uncertain when the score is compatible with both classes, or with neither."],
    ["Confidence", "0 to 100%", "From the conformal p-values: one minus the p-value of the rejected label."],
    ["Reliability", "High, Medium or Low", "Tanimoto similarity (Morgan radius 2) to the closest of the 4,942 training compounds: High at 0.70 or more, Medium at 0.50 or more, Low below 0.50. Low reliability adds a warning card."],
    ["Name", "e.g. Olaparib, or XYZ-001", "The PubChem name found by InChIKey; otherwise the user’s next XYZ number. Any entry can be renamed."],
  ]),
  spacer(),
  P("**Compound naming.** A small third service (`domain/naming.py`) looks a structure up on PubChem by its InChIKey rather than by its SMILES, so an unpublished structure that a researcher is testing is never sent to an outside service. Names are stored with the molecule, so each structure is looked up only once, and if PubChem is slow or unavailable the prediction still goes ahead and the lookup is simply retried the next time."),

  H3("4.5.4 Data access layer"),
  P("This layer is simply used to transform all of the database calls from the application layer into actual queries. It also contains objects or classes for each category of data that could be either written to or read from the database. This thin layer is what would allow us to test the functionality of our queries, code logic, and business rules without having to directly invoke the actual database that is set up for this application. The data access layer could simply connect to a dummy database purely for testing reasons. The database itself can also be swapped, updated, or replaced and this layer would ensure that no substantial change occurs throughout the rest of the layers."),
  P("**Implementation.** The data access layer (`data/repositories.py`) is a set of small repository classes built on SQLAlchemy 2.0, one for each kind of record: users, molecules, predictions, history entries (submissions) and password reset codes. The services never write SQL themselves; they call methods such as `get_or_create`, `get_cached` or `list_for_user`. This is what allowed the same automated tests to run against SQLite during development and against PostgreSQL before hosting, without any change to the service code. The repositories also hide which database features are available. The similar-compounds search on the Result screen is an example (Listing 4-8): when PostgreSQL has the RDKit cartridge, the repository sends a single indexed similarity query to the database; otherwise it computes the same Tanimoto similarities in Python. The answer is identical either way."),
  ...listing("Listing 4-8", "Similarity search through the RDKit cartridge, with a Python fallback (data/repositories.py, shortened).", `
def find_similar(self, user_id, fingerprint, *, min_similarity, limit, python_similarity):
    if self.rdkit_cartridge:                 # indexed search inside PostgreSQL
        scored = self._similar_ids_cartridge(user_id, fingerprint, min_similarity, limit)
    else:                                    # same Tanimoto similarity, computed in Python
        scored = self._similar_ids_python(user_id, fingerprint, min_similarity, limit,
                                          python_similarity)
    ...

def _similar_ids_cartridge(self, user_id, fingerprint, min_similarity, limit):
    self.session.execute(text("SELECT set_config('rdkit.tanimoto_threshold', :t, true)"),
                         {"t": str(min_similarity)})
    rows = self.session.execute(text(
        "SELECT s.id, tanimoto_sml(m.mfp2, bfp_from_binary_text(:fp)) AS similarity "
        "FROM submissions s JOIN molecules m ON m.id = s.molecule_id "
        "WHERE s.user_id = :user_id AND m.mfp2 % bfp_from_binary_text(:fp) "
        "ORDER BY similarity DESC, s.created_at DESC LIMIT :limit"),
        {"fp": fingerprint, "user_id": user_id, "limit": limit}).all()
    return [(row.id, float(row.similarity)) for row in rows]`),

  H3("4.5.5 Database layer"),
  P("All potential caching and core storage would reside in this layer."),
  P("**Implementation.** The database layer is PostgreSQL 17 in the hosted setup; SQLite is used only on developers’ laptops so that the server can be started without installing a database. The schema is created and upgraded by Alembic migrations that run automatically when the server starts, so a database created by an earlier version of the back-end is brought up to date without losing data; this upgrade path is tested (Section 4.9.4). When the RDKit cartridge is installed, the server adds two generated columns to the molecules table and indexes them (Listing 4-9). Because both columns are generated from data the application already writes, the application code is the same with or without the cartridge."),
  ...listing("Listing 4-9", "The statements that switch on the RDKit cartridge (data/db.py).", `
_CARTRIDGE_DDL = [
    "CREATE EXTENSION IF NOT EXISTS rdkit",
    "ALTER TABLE molecules ADD COLUMN IF NOT EXISTS mol mol "
    "GENERATED ALWAYS AS (mol_from_smiles(canonical_smiles)) STORED",
    "ALTER TABLE molecules ADD COLUMN IF NOT EXISTS mfp2 bfp "
    "GENERATED ALWAYS AS (bfp_from_binary_text(fingerprint)) STORED",
    "CREATE INDEX IF NOT EXISTS ix_molecules_mol ON molecules USING gist (mol)",
    "CREATE INDEX IF NOT EXISTS ix_molecules_mfp2 ON molecules USING gist (mfp2)",
]`),
];

// ---------------------------------------------------------------- 4.6 - 4.8
const s46 = [
  H2("4.6 Database decisions"),
  P("Given the fact that the nature of data stored here is clearly relational (user accounts, molecule and prediction data), this rules out any NoSQL databases that do not provide constraints and schemas."),
  P("Within relational databases, the best possible option to use would be PostgreSQL. This is primarily because it supports an extension (the RDKit cartridge) that allows the addition of chemical molecular columns with a specified “mol” data type directly in SQL. This would allow us the flexibility for querying molecules and saving them onto our databases with no additional transformation to said compounds."),
  P("The RDKit cartridge extension also simplifies the process of checking whether or not a similar or identical compound has been tested by the user before, as PostgreSQL has built in native code that uses an index specifically for chemical fingerprints, rather than simply matching string similarities between compounds, which would prove ineffective."),
  P("**Schema.** Figure 4-6 shows the entity-relationship model that was implemented, and Table 4-4 describes each table. The central decision was to separate what a compound is from who asked about it. A molecule row exists once for each distinct standardised structure and is shared by all users; a prediction row stores the model’s output for one molecule and one model version; and a submission row is one entry in one user’s history, pointing to both. This brings three benefits. First, the unique constraint on (molecule_id, model_version) makes the predictions table the cache described in Section 4.5.2, so the same structure is never run through the model twice. Second, when a new model version is installed, older results are kept for the record but are not reused, because the version is part of the key. Third, a user can rename, bookmark or delete entries in their history without affecting anyone else."),
  P("Every prediction stores its full output and not only the label: the score, the threshold, both conformal p-values, the similarity to the nearest training compound and the reliability band. Every result in the app can therefore be traced back to the model version and the evidence that produced it, which matters for a tool whose output may guide which compounds are synthesised. Columns for pIC50 and ADME predictions already exist, so those models can be added later without changing the schema."),
  ...figure("fig_er.png", "Figure 4-6", "Entity-relationship model of the implemented database.", 600),
  tableCaption("Table 4-4", "Tables in the database."),
  dataTable([2000, 2000, 5026], ["Table", "One row per", "Main contents"], [
    ["users", "registered user", "Unique email; bcrypt password hash (or a marker for Google-only accounts); Google account id; counter behind the XYZ-### names."],
    ["password_reset_codes", "reset code sent", "Keyed hash of the 6-digit code, expiry time and failed attempts; deleted together with the user."],
    ["molecules", "distinct standardised structure", "Canonical SMILES (unique), InChIKey, fingerprint, formula, weight and PubChem names; mol and mfp2 when the cartridge is present."],
    ["predictions", "molecule and model version", "Verdict, score, confidence, p-values, nearest similarity and reliability; unique per (molecule, model version)."],
    ["submissions", "entry in a user’s history", "Links the user, molecule and prediction; display name, SMILES as typed, saved flag and time."],
  ]),
  spacer(),

  H2("4.7 Software used"),
  P("The most suitable contender for the software used is Android Studio, alongside Kotlin and Jetpack Compose. Flutter could be used further down the line if an explicit implementation for an iOS version arises. This front end phone application communicates via HTTPS/JSON calls with the back‑end server, which contains all of the needed APIs, the domain layer, and the database layer as well."),
  P("Table 4-5 lists the software that the implemented application is built on. Versions are fixed in the back-end’s requirements file and the app’s Gradle build, so every team member, and the hosting server, runs exactly the same code. In particular, the RDKit, scikit-learn and NumPy versions on the server are pinned to the ones used in the training notebook, because a different version could featurise molecules slightly differently or fail to load the saved model."),
  tableCaption("Table 4-5", "Software and libraries used in the application."),
  dataTable([1700, 3000, 4326], ["Area", "Software", "Role"], [
    ["Android app", "Kotlin 2.0, Jetpack Compose (Material 3), Navigation Compose, Android Studio", "Screens, navigation and app state (Android 8.0 and newer)"],
    ["", "Retrofit 2.11, OkHttp 4.12, kotlinx.serialization", "HTTPS/JSON calls to the back-end"],
    ["", "DataStore, Credential Manager, PdfDocument", "Saved session, Google sign-in, PDF reports"],
    ["Back-end", "Python 3.12, FastAPI, Uvicorn, Pydantic 2", "REST API, request validation, API documentation"],
    ["", "bcrypt, PyJWT, httpx", "Password hashing, tokens and Google token checks, PubChem calls"],
    ["Chemistry and model", "RDKit 2026.9.1, scikit-learn 1.9.1, NumPy 2.5.4, joblib", "Parsing, standardisation, fingerprints, 2D drawings; Extra Trees model"],
    ["Data", "SQLAlchemy 2.0, Alembic, psycopg 3", "Repositories, schema migrations, PostgreSQL driver"],
    ["", "PostgreSQL 17 with the RDKit cartridge; SQLite", "Hosted database; development database"],
    ["Testing", "pytest, FastAPI TestClient, Android emulator", "Automated and end-to-end testing"],
    ["Hosting", "Docker, Docker Compose, Caddy 2", "Containers, one-command start-up, automatic HTTPS"],
  ]),
  spacer(),

  H2("4.8 Updated GUI/UX prototype"),
  P("The screens below formed the prototype that we then implemented in full. Each of these screens has now been built in Kotlin with Jetpack Compose, as described in Section 10.1."),
  figurePair(["existing/fig_signup.jpg", "Figure 4-7", "Sign up Page."], ["existing/fig_login.jpg", "Figure 4-8", "Log in Page."]),
  figurePair(["existing/fig_home.jpg", "Figure 4-9", "Homepage."], ["existing/fig_predict.jpg", "Figure 4-10", "Predict Page."]),
  figurePair(["existing/fig_active_result.jpg", "Figure 4-11", "Active Result Screen."], ["existing/fig_inactive_result.jpg", "Figure 4-12", "Inactive Result Screen."]),
  figurePair(["existing/fig_history.jpg", "Figure 4-13", "Prediction History Page."], ["existing/fig_insights.jpg", "Figure 4-14", "Insights Page."]),
];

// ---------------------------------------------------------------- 4.9 testing
const s49 = [
  H2("4.9 Software Testing"),
  H3("4.9.1 Testing approach"),
  P("Because the application gives scientific advice, we treated two kinds of error as equally important. The first is the ordinary software bug: a crash, a wrong screen, or one user seeing another user’s data. The second is the silent scientific error, where the app works normally but the answer is wrong because the server prepared a molecule differently from the training notebook. The second kind cannot be noticed by using the app, so it is caught by dedicated consistency checks. Figure 4-15 shows the levels of testing, from many fast unit tests at the bottom to a few slower end-to-end runs at the top."),
  ...figure("fig_testing_levels.png", "Figure 4-15", "Levels of testing used for the application."),
  H3("4.9.2 Automated tests"),
  P("The back-end has an automated test suite written with pytest. It contains 77 tests that run in about ten seconds on a laptop using SQLite. When a PostgreSQL test database is provided, every test that touches the database is repeated on PostgreSQL, giving 111 tests in total. The full set was run against PostgreSQL 17 both with and without the RDKit cartridge, and all 111 tests passed in both cases. The suite is run after every change; Table 4-6 summarises what each part covers."),
  tableCaption("Table 4-6", "Automated back-end test suites."),
  dataTable([2200, 900, 5926], ["Suite (file)", "Tests", "What is checked"], [
    ["Chemistry\n(test_chemistry.py)", "22", "Salts are stripped and charges neutralised; tautomers of one compound give one structure; invalid SMILES get readable errors; formula and weight; 2D drawings; fingerprints survive storage; server cleaning matches the notebook on ten difficult inputs."],
    ["Accounts\n(test_accounts.py)", "15", "Password reset with emailed codes, which expire, stop after five wrong guesses and do not reveal which emails have accounts; changing name and password; Google sign-in creates, reuses and links accounts and rejects forged tokens; sign-up allowlist."],
    ["API\n(test_api.py)", "12", "Register, log in and profile; caching by standardised structure; history save and delete; users cannot see each other’s history; invalid SMILES rejected with a reason; no model gives an error, never a made-up answer; similar compounds."],
    ["Naming and migrations\n(test_naming.py)", "10", "PubChem names parsed, stored and looked up once; a PubChem outage does not block predictions; renaming; a database from the first release is upgraded; database models match the migrations."],
    ["Inference\n(test_inference.py)", "9", "Conformal p-values checked against hand calculations; Uncertain in the overlap; bad calibration data rejected; confidence maths; predictions with the applicability domain; the start-up check catches featurisation drift; the placeholder model is clearly labelled."],
    ["Hosting\n(test_hosting.py)", "9", "Database addresses from hosting providers; production mode refuses unsafe settings; sign-up limited to the team; repeated log-ins rate limited."],
    { cells: ["Total", "77", "111 when the database tests are repeated on PostgreSQL 17"], opts: { bold: true, fill: "F3F5F8" } },
  ]),
  spacer(),
  P("Two examples show the style of the tests. Listing 4-10 is the consistency check between the server and the training notebook: the notebook’s cleaning function is copied into the test exactly as it is written there, and for each difficult input the test requires the same canonical SMILES and the same 2,048 fingerprint bits from both. Listing 4-11 is an API test of the cache. It predicts olaparib, then submits olaparib again as a hydrochloride salt drawn as a different tautomer, and expects the second request to be recognised as the same compound and answered from the cache, while a second user still receives their own history numbering."),
  ...listing("Listing 4-10", "The server’s cleaning must match the training notebook (tests/test_chemistry.py, shortened).", `
def _notebook_prepare(smiles):            # copied from the training notebook
    chooser = rdMolStandardize.LargestFragmentChooser()
    uncharger = rdMolStandardize.Uncharger()
    normalizer = rdMolStandardize.Normalizer()
    taut = rdMolStandardize.TautomerEnumerator()
    taut.SetMaxTautomers(200)
    m = Chem.MolFromSmiles(smiles)
    if m is None:
        return None
    try:
        m = taut.Canonicalize(uncharger.uncharge(normalizer.normalize(chooser.choose(m))))
    except Exception:
        pass
    return m

@pytest.mark.parametrize("smiles", [
    "OC1=NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12.Cl",  # olaparib HCl
    "CC(=O)Oc1ccccc1C(=O)[O-].[Na+]",                                 # carboxylate salt
    "O=C(N1CCCCC1)C(C2=CC=CC=C2N3CCCC)=CC3=O",                        # F-series 16F
    # ... seven more: stereocentre, permanent cation, solvate, nitro group, tautomers
])
def test_server_cleaning_matches_the_notebook(smiles):
    ours = chem.standardise(chem.parse(smiles))
    theirs = _notebook_prepare(smiles)
    assert Chem.MolToSmiles(ours) == Chem.MolToSmiles(theirs)
    gen = rdFingerprintGenerator.GetMorganGenerator(radius=3, fpSize=2048)
    assert (gen.GetFingerprintAsNumPy(ours) == gen.GetFingerprintAsNumPy(theirs)).all()`),
  ...listing("Listing 4-11", "The prediction cache is keyed on the standardised structure (tests/test_api.py, shortened).", `
def test_predict_caches_by_standardised_structure(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)

    first = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth)
    assert first.status_code == 201
    assert first.json()["name"] == "XYZ-001" and first.json()["cached"] is False

    # Same compound as a salt and in the other tautomeric form: served from the cache.
    second = client.post("/api/v1/predictions",
                         json={"smiles": OLAPARIB_HCL_OTHER_TAUTOMER}, headers=auth).json()
    assert second["name"] == "XYZ-002" and second["cached"] is True
    assert second["canonical_smiles"] == first.json()["canonical_smiles"]

    # The cache is shared between users, but numbering and history are per user.
    other = register(client, email="iman@example.com")
    third = client.post("/api/v1/predictions", json={"smiles": OLAPARIB},
                        headers=other).json()
    assert third["cached"] is True and third["name"] == "XYZ-001"`),
  P("The tests use a small toy model trained on sixteen simple compounds, so they run in seconds and do not depend on the real model file; the real model is checked separately, as described next."),

  H3("4.9.3 Consistency between training and serving"),
  P("The start-up reference check described in Section 4.5.3 works as a test that runs every time the server starts, on the real model (Listing 4-12). The 307 reference molecules stored in the model bundle must featurise exactly as they did in the notebook; otherwise the server reports the model as unavailable instead of serving predictions from it. A unit test deliberately changes the featurisation settings and confirms that the check catches the change. In addition, a test compares the database models with the migration scripts, so a fresh server always ends up with the same schema as an older database that has been upgraded."),
  ...listing("Listing 4-12", "The start-up check that the server prepares molecules exactly as the notebook did (domain/inference/service.py, shortened).", `
mols = [chemistry.standardise(chemistry.parse(s)) for s in b.reference_smiles]
ours = self.featurizer.transform(mols).astype(np.float64)       # the server's own pipeline
theirs = np.asarray(b.reference_features, dtype=np.float64)     # saved by the notebook
mismatched = int((ours != theirs).any(axis=1).sum())
if mismatched:
    raise BundleError(f"{mismatched}/{len(mols)} reference molecules featurise "
                      "differently on the server than in training.")`),

  H3("4.9.4 System and end-to-end testing"),
  P("Beyond the automated suite, the complete system was tested in the configurations it is meant to run in:"),
  bullet("**Database:** the full suite on PostgreSQL 17 with and without the RDKit cartridge, and an upgrade of a real database created by the first release of the back-end to the current schema, with its data kept."),
  bullet("**Hosting:** the Docker image was built and run on both ARM (Apple silicon) and x86-64 processors, and the Docker Compose setup was started with PostgreSQL and Caddy to confirm that the three containers work together over HTTPS."),
  bullet("**End to end:** the Android app was run on the Android emulator against the running back-end, going through sign-up, log in, live SMILES checking, prediction, history, saving, renaming, PDF export and password reset (with the reset code read from the development server’s log, because email delivery is not configured yet)."),
  P("**Known-compound checks.** As a final check, known compounds were run through the real model, both through the API and by entering them in the app (Table 4-7, Figures 4-16 and 4-17). Five well-known PARP-1 inhibitors (four approved drugs and veliparib) are predicted Active with High reliability, and five measured inactives are predicted Inactive with High reliability. Everyday drugs that are not PARP inhibitors are predicted Inactive but flagged Low reliability, because nothing like them is in the training data, and an F-series compound, whose chemistry is unlike the training set, is answered Uncertain. Across all 572 measured inactive compounds in the training file, the deployed model returned Inactive for every one. It is important to read this correctly: these inhibitors and measured inactives are part of the training data, so this check confirms that the deployed pipeline reproduces the notebook, not how accurate the model is on new chemistry. Accuracy on unseen chemistry is measured by the scaffold-split evaluation in Section 6."),
  tableCaption("Table 4-7", "Known compounds run through the deployed model."),
  dataTable([2900, 2526, 1200, 1300, 1100], ["Compound", "Type", "Verdict", "Reliability", "Nearest similarity"], [
    ["Olaparib", "Approved PARP-1 inhibitor", "Active", "High", "1.00"],
    ["Rucaparib", "Approved PARP-1 inhibitor", "Active", "High", "1.00"],
    ["Niraparib", "Approved PARP-1 inhibitor", "Active", "High", "1.00"],
    ["Talazoparib", "Approved PARP-1 inhibitor", "Active", "High", "1.00"],
    ["Veliparib", "PARP-1 inhibitor (clinical)", "Active", "High", "1.00"],
    ["Benzamide", "Measured inactive", "Inactive", "High", "1.00"],
    ["Nicotinamide", "Measured inactive", "Inactive", "High", "1.00"],
    ["3-Aminobenzamide", "Measured inactive", "Inactive", "High", "1.00"],
    ["3-Methylbenzamide", "Measured inactive", "Inactive", "High", "1.00"],
    ["3,4-Dihydroisoquinolin-1(2H)-one", "Measured inactive", "Inactive", "High", "1.00"],
    ["Aspirin", "Drug, not a PARP inhibitor", "Inactive", "Low", "0.32"],
    ["Caffeine", "Drug, not a PARP inhibitor", "Inactive", "Low", "0.18"],
    ["Ibuprofen", "Drug, not a PARP inhibitor", "Inactive", "Low", "0.25"],
    ["Paracetamol", "Drug, not a PARP inhibitor", "Inactive", "Low", "0.37"],
    ["16F", "F-series compound", "Uncertain", "Low", "0.42"],
  ], { size: 20 }),
  spacer(),
  figurePair(["app_result_rucaparib.jpg", "Figure 4-16", "Rucaparib entered in the app: Active, named from PubChem."],
             ["app_result_talazoparib.jpg", "Figure 4-17", "Talazoparib entered in the app: Active."]),
  P("**Limits of the testing so far.** The Android code has no automated tests yet; it was tested by hand on the emulator. The app has not been tried on physical phones, and the hosted setup has only been run on our own machines. These are listed as work in progress in Section 10.4."),
];

// ---------------------------------------------------------------- 10.0 work in progress
const s10 = [
  new Paragraph({ children: [new PageBreak()] }),
  H1("10.0 Work in Progress: Application Implementation"),
  P("This phase moved the application from a prototype to a working build that runs end to end on the Android emulator against the back-end. The application is not finished yet: it has not been hosted for the team or used on real phones, a few features are complete in code but wait for accounts that still have to be set up, and some features shown in the prototype depend on models that are still being developed. This section therefore reports the application as work in progress: what has been built and tested (Sections 10.1 to 10.3) and what remains (Section 10.4)."),
  H2("10.1 Front-end implementation"),
  P("The front-end of “Inhibit AI” was built using Kotlin with Jetpack Compose in Android Studio. Every screen that appears in the prototype (Section 4.8, Figures 4-7 to 4-14) has been implemented, so the sign up, log in, home, prediction, result, history, insights and profile screens are all functional. Building the UI declaratively with Jetpack Compose kept the screens consistent with the prototype and made it straightforward to wire each screen to the back-end over HTTPS/JSON. The structure of the app is described in Section 4.5.1. The key features of the front-end, grouped by the area of the app they belong to, are as follows."),
  bullet("**Accounts:** sign up, log in, stay signed in and log out; password reset with a 6-digit code; an Account screen to change the name and change or set a password; a “Continue with Google” button. On a hosted server, sign up can be limited to a team’s email addresses, and repeated failed log-in attempts are blocked."),
  bullet("**Prediction:** live SMILES checking with readable errors; a 2D drawing of the compound; the compound’s formula, weight and, for known compounds, its PubChem name; the same cleaning as in the notebook. The model returns an Active / Inactive / Uncertain verdict with a confidence value and a High / Medium / Low reliability rating, with a warning note when reliability is low, and earlier results for the same compound are reused."),
  bullet("**Result:** compound details (name, IUPAC name, formula, PubChem link, SMILES), a list of similar compounds the user has already tested, renaming, saving, and a PDF report that can be shared."),
  bullet("**History:** every prediction is saved per user, under its PubChem name when the compound is known, otherwise as XYZ-001, XYZ-002 and so on, with search, filters (Active / Inactive / Uncertain / Saved), sorting, bookmarking and delete."),
  bullet("**Insights:** totals, a distribution donut, a 7-day chart, average confidence and a saved count, all drawn from the user’s real data."),
  bullet("**Profile:** the user’s name, email, prediction counts, the model version and settings, help and about pages, and log out."),
  P("The Active / Inactive / Uncertain verdict with a reliability warning is the direct front-end expression of the model behaviour described in Sections 4.3.3 to 4.3.5: the app surfaces the model’s willingness to answer “uncertain” and flags predictions that fall outside the applicability domain rather than presenting every output with equal confidence."),
  H2("10.2 Back-end implementation"),
  P("The back-end follows the five-layer design set out in Section 4.5 and serves the trained model to the app over a REST API. It was built and tested during this phase, and its current state is summarised below."),
  bullet("**Architecture and API:** the report’s five-layer design is implemented (Figure 4-2), with the endpoints listed in Table 4-2 and interactive API documentation served at http://localhost:8000/docs during development."),
  bullet("**Database:** PostgreSQL, with the optional RDKit cartridge enabled so that molecules can be stored and queried natively and previously tested compounds can be matched by chemical fingerprint rather than by string (Figure 4-6). Schema changes are applied by versioned migrations that keep existing data."),
  bullet("**Testing:** 77 automated tests, or 111 including the PostgreSQL runs, cover the back-end services and currently pass (Section 4.9)."),
  bullet("**Model pipeline:** the notebook’s export step (notebook Section 12) produces the model file, which is installed in the back-end; a start-up consistency check verifies that the served model prepares molecules exactly as the notebook did."),
  bullet("**Compound names:** names are looked up on PubChem by InChIKey, so the structure itself never leaves the server."),
  H2("10.3 Hosting and deployment"),
  P("So that the team and our supervisor can use the app from their own phones, the back-end has been packaged for hosting (Figure 10-1). A Docker image contains the API, the model file and all chemistry libraries, and runs as a non-root user. A Docker Compose file starts three containers on one Linux server: Caddy, which obtains and renews HTTPS certificates automatically; the API; and PostgreSQL, whose data is kept in a volume that survives restarts. In production mode the server refuses to start with unsafe settings, such as a weak token secret, SQLite, the placeholder model or a missing model file. The whole setup is documented step by step in a DEPLOY.md file and has been tested on our own machines; it has not yet been deployed on a public server."),
  ...figure("fig_deployment.png", "Figure 10-1", "Planned hosting setup for the application."),
  P("Table 10-1 summarises what is working across the application at the end of this phase, and how complete each area is."),
  tableCaption("Table 10-1", "Summary of the application features and their status at the end of this phase."),
  dataTable([1500, 5726, 1800], ["Area", "What works", "Status"], [
    ["Accounts", "Sign up, log in, stay signed in, log out; password reset with emailed codes; change name and password; Google sign-in; sign-up allowlist and log-in rate limits.", "Done; email and Google need accounts set up"],
    ["Prediction", "Live SMILES checking with readable errors; 2D drawings; PubChem names; the same cleaning as the notebook; Active / Inactive / Uncertain verdicts with confidence; High / Medium / Low reliability with a warning note; reuse of earlier results.", "Done"],
    ["Result", "Compound details, similar compounds already tested, rename, save, PDF export.", "Done"],
    ["History", "Every prediction saved per user, with search, filters (Active / Inactive / Uncertain / Saved), sorting, bookmarking and delete.", "Done"],
    ["Insights", "Totals, a distribution donut, a 7-day chart, average confidence and saved count, all from real data.", "Done"],
    ["Profile", "Name, email, counts, model version and settings, help and about, log out.", "Done"],
    ["Back-end", "The five-layer design; PostgreSQL with optional RDKit cartridge; migrations; 77 automated tests (111 with PostgreSQL); API documentation.", "Done"],
    ["Model pipeline", "The notebook’s export step; the model file installed in the back-end; start-up consistency check.", "Done"],
    ["Hosting", "Docker image, Docker Compose with automatic HTTPS, production safety checks, DEPLOY.md.", "Tested locally; not yet deployed"],
    ["ADME and pIC50", "Fields exist in the database and API; the prototype’s ADME overview is not shown yet.", "Waiting for the models"],
  ]),
  spacer(),
  H2("10.4 Remaining work"),
  P("Table 10-2 lists the work that is still in progress or not yet started, together with what is needed to finish each item. Most of the remaining application work is configuration and testing on real devices rather than new code, which fits the plan in Section 9.1 of moving from construction to verification in the next phase."),
  tableCaption("Table 10-2", "Application work still in progress."),
  dataTable([2600, 1400, 5026], ["Item", "Status", "What is left"], [
    ["Hosting for the team and supervisor", "In progress", "The setup is written and tested locally; it needs a server and a domain name."],
    ["Password-reset emails", "In progress", "The code is complete and tested; it needs the team’s email (SMTP) account. Until then, codes appear in the server log."],
    ["Google sign-in", "In progress", "The code is complete in the app and the server; it needs an OAuth client ID registered for the app in Google Cloud."],
    ["SMILES keyboard on real phones", "In progress", "On the emulator, the keyboard’s auto-correction changed a lowercase “c(” into “C(” while typing, which silently changes the molecule. A fix that turns off suggestions for the SMILES field is written but not yet built and tested; pasting is not affected."],
    ["Testing on physical phones", "Not started", "So far only the emulator has been used; the app should be tried on several phones and Android versions."],
    ["Automated front-end tests", "Not started", "The Android code is tested by hand; Compose UI tests for the main flows are planned."],
    ["pIC50 and ADME predictions", "Not started", "Depends on the regression and ADME models (Section 9.1). The database and API already have fields for them."],
    ["Phone-number sign-up and SMILES from a picture", "Not started", "Shown in the use-case and sequence diagrams (Figures 11-3, 11-5 and 11-7) but not implemented; the Apple sign-in button currently explains that it is unavailable."],
    ["Final GUI/UX polish", "In progress", "Small layout and wording improvements across the screens."],
  ]),
];

// =================================================================== document
const doc = new Document({
  creator: "InhibitAI team",
  title: "Senior Progress Report #2: sections 4.5 to 4.9 and 10.0",
  styles: {
    default: { document: { run: { font: BODY_FONT, size: 24 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, color: NAVY, font: BODY_FONT },
        paragraph: { spacing: { before: 360, after: 160 }, outlineLevel: 0, keepNext: true } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 27, bold: true, color: NAVY, font: BODY_FONT },
        paragraph: { spacing: { before: 280, after: 120 }, outlineLevel: 1, keepNext: true } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 24, bold: true, color: "000000", font: BODY_FONT },
        paragraph: { spacing: { before: 220, after: 100 }, outlineLevel: 2, keepNext: true } },
    ],
  },
  numbering: { config: [{ reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "●",
    alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } }, run: { size: 18 } } }] }] },
  sections: [{
    properties: { page: { margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    headers: { default: new Header({ children: [new Paragraph({
      alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: "8EA2BF", space: 4 } },
      children: [new TextRun({ text: "Senior Progress Report #2", size: 20, color: "555555" })] })] }) },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
      children: [new TextRun({ children: [PageNumber.CURRENT], size: 20, color: "555555" })] })] }) },
    children: [...notes, ...s45, ...s46, ...s49, ...s10],
  }],
});

Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(OUT, buf);
  console.log("wrote", OUT, (buf.length / 1e6).toFixed(1), "MB");
});
