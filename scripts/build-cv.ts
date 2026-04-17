import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import matter from "gray-matter";
import puppeteer from "puppeteer";

// Node-compatible dirname (replaces Bun-only import.meta.dir)
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ROOT = path.resolve(__dirname, "..");
const DOCS = path.join(ROOT, "docs");
// Output directly into app/public so Astro serves /cv.pdf on the site
const OUT = path.join(ROOT, "app", "public", "cv.pdf");

// ─── Types ────────────────────────────────────────────────────────────────────

interface Profile {
  name: string;
  title: string;
  birth_year: number;
  location: string;
  photo?: string;
  contacts: {
    github?: string;
    linkedin?: string;
    telegram?: string;
    email?: string;
    phone?: string;
  };
}

interface Experience {
  company?: string;
  title?: string;
  period: string;
  cv_description?: string;
  cv_skip?: boolean;
}

interface Skill {
  category: string;
  content: string;
}

interface Education {
  degrees: Array<{ level: string; school: string; years: string }>;
  languages: Array<{ name: string; level: string }>;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function readMd(filePath: string) {
  return matter(fs.readFileSync(filePath, "utf-8"));
}

function calcAge(birthYear: number): number {
  return new Date().getFullYear() - birthYear;
}

/** Extract the content body of a `## Heading` section from markdown. */
function extractSection(markdown: string, heading: string): string {
  const re = new RegExp(`## ${heading}\\n([\\s\\S]*?)(?=\\n## |$)`);
  const m = markdown.match(re);
  return m ? m[1].trim() : "";
}

/**
 * Parse top-level bullet items from markdown, handling multi-line items.
 */
function extractBullets(markdown: string): string[] {
  const items: string[] = [];
  let current = "";
  for (const line of markdown.split("\n")) {
    const trimmed = line.trimStart();
    if (trimmed.startsWith("- ")) {
      if (current) items.push(current);
      current = trimmed.slice(2).trim();
    } else if (current && trimmed) {
      current += " " + trimmed;
    } else if (current && !trimmed) {
      items.push(current);
      current = "";
    }
  }
  if (current) items.push(current);
  return items;
}

/** Extract **bold** terms from markdown */
function extractBoldTerms(markdown: string): string[] {
  const terms: string[] = [];
  const re = /\*\*([^*]+)\*\*/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(markdown)) !== null) {
    terms.push(m[1]);
  }
  return terms;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ─── Data loading ─────────────────────────────────────────────────────────────

function loadProfile(): {
  meta: Profile;
  summary: string;
  strengths: string[];
  lookingFor: string;
} {
  const { data, content } = readMd(path.join(DOCS, "profile/index.md"));
  const summary = extractSection(content, "Кратко");

  const focusSection = extractSection(content, "Профессиональный фокус");
  const strengthsPart = focusSection.split("**Что не моё:**")[0];
  const strengthsRaw = strengthsPart.replace("**Что делаю хорошо:**", "");
  const strengths = extractBullets(strengthsRaw).map((item) => {
    const colonIdx = item.indexOf(":");
    return colonIdx > 0 ? item.substring(0, colonIdx).trim() : item;
  });

  // Load "Что ищу" from the document instead of hardcoding
  const lookingForRaw = extractSection(content, "Что ищу");
  const lookingFor = lookingForRaw
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .join(" ");

  return { meta: data as Profile, summary, strengths, lookingFor };
}

function loadPhoto(photoPath?: string): string | null {
  if (!photoPath) return null;
  const fullPath = path.isAbsolute(photoPath)
    ? photoPath
    : path.resolve(ROOT, photoPath);
  if (!fs.existsSync(fullPath)) {
    console.warn(`[cv] Photo not found: ${fullPath} — skipping`);
    return null;
  }
  const ext = path.extname(fullPath).slice(1).toLowerCase();
  const mime = ext === "jpg" ? "jpeg" : ext;
  const data = fs.readFileSync(fullPath);
  return `data:image/${mime};base64,${data.toString("base64")}`;
}

function loadEducation(): Education {
  const { content } = readMd(path.join(DOCS, "profile/04-education.md"));

  const DEGREE_HEADINGS = ["Бакалавриат", "Магистратура"];
  const degrees = DEGREE_HEADINGS.map((level) => {
    const section = extractSection(content, level);
    const lines = section.split("\n").map((l) => l.trim()).filter(Boolean);
    return {
      level,
      years: lines[0] ?? "",
      school: abbreviateSchool(lines[1] ?? ""),
    };
  }).filter((d) => d.years);

  const langSection = extractSection(content, "Языки");
  const languages: Education["languages"] = [
    { name: "Русский", level: "Родной" },
  ];
  for (const line of langSection.split("\n")) {
    const m = line.match(/^(.+?)\s*—\s*(.+)$/);
    if (m) languages.push({ name: m[1].trim(), level: m[2].trim() });
  }

  return { degrees, languages };
}

function abbreviateSchool(school: string): string {
  const map: Record<string, string> = {
    "Поволжский государственный технологический университет": "ПГТУ",
  };
  return map[school] ?? school;
}

function loadExperience(): Experience[] {
  const dir = path.join(DOCS, "experience");
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .sort()
    .map((f) => readMd(path.join(dir, f)).data as Experience)
    .filter((e) => e.company && !e.cv_skip);
}

function loadSkills(): Skill[] {
  const dir = path.join(DOCS, "skills");
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .sort()
    .map((f) => {
      const { data, content } = readMd(path.join(dir, f));
      return {
        category: (data as any).category ?? f.replace(".md", ""),
        content,
      };
    });
}

// ─── Skill tag definitions ────────────────────────────────────────────────────

const SKILL_NAMES: Record<string, string> = {
  architecture: "Архитектура",
  backend: "Стек",
  leadership: "Лидерство",
  tooling: "Инфраструктура · AI",
};

const SKILL_TAGS: Record<string, string[]> = {
  architecture: ["Low-code платформы", "SaaS", "B2B", "ADR / C4", "Greenfield & Legacy", "Миграции без даунтайма"],
  backend: ["Kotlin / KMP", "Java", "TypeScript / Node.js", "PostgreSQL", "Redis", "Ktor · Exposed", "NestJS"],
  leadership: ["Tech Lead", "Backend Lead", "R&D", "Code Review", "Менторинг", "Преподавание"],
  tooling: ["GitHub Actions", "Jenkins", "Claude Code", "OpenRouter", "Docker / VPS", "Bun · Astro"],
};

// ─── HTML rendering ───────────────────────────────────────────────────────────

function renderContacts(contacts: Profile["contacts"]): string {
  const items: string[] = [];
  if (contacts.phone)
    items.push(esc(contacts.phone));
  if (contacts.email)
    items.push(`<a href="mailto:${contacts.email}">${esc(contacts.email)}</a>`);
  if (contacts.telegram)
    items.push(`<a href="https://t.me/${contacts.telegram.replace("@", "")}">${esc(contacts.telegram)}</a>`);
  if (contacts.linkedin)
    items.push(`<a href="https://linkedin.com/in/${contacts.linkedin}">linkedin.com/in/${esc(contacts.linkedin)}</a>`);
  if (contacts.github)
    items.push(`<a href="https://github.com/${contacts.github}">github.com/${esc(contacts.github)}</a>`);
  return items
    .map((i) => `<span class="ci">${i}</span>`)
    .join('<span class="csep"> · </span>');
}

function renderExperience(experiences: Experience[]): string {
  return experiences
    .map(
      (e) => `
      <div class="exp">
        <div class="exp-row">
          <div class="exp-left">
            <span class="exp-co">${esc(e.company!)}</span>
            ${e.title ? `<span class="exp-role">${esc(e.title)}</span>` : ""}
          </div>
          <span class="exp-period">${esc(e.period)}</span>
        </div>
        ${e.cv_description ? `<p class="exp-desc">${esc(e.cv_description)}</p>` : ""}
      </div>`,
    )
    .join("");
}

function renderSkills(skills: Skill[]): string {
  return skills
    .map((s) => {
      const name = SKILL_NAMES[s.category] ?? s.category;
      const tags = SKILL_TAGS[s.category] ?? extractBoldTerms(s.content).slice(0, 7);
      return `
        <div class="skill-cat">
          <div class="skill-cat-name">${name}</div>
          <div class="skill-tags">
            ${tags.map((t) => `<span class="st">${esc(t)}</span>`).join("")}
          </div>
        </div>`;
    })
    .join("");
}

function renderEducation(edu: Education): string {
  const degrees = edu.degrees
    .map(
      (d) => `
      <div class="edu-entry">
        <span class="edu-deg">${d.level}</span>
        <span class="edu-school">${d.school} · ${d.years}</span>
      </div>`,
    )
    .join("");

  const langs = edu.languages
    .map(
      (l) => `<div class="lang"><span>${l.name}</span><span class="lang-lvl">${l.level}</span></div>`,
    )
    .join("");

  return degrees + `<div class="lang-block">${langs}</div>`;
}

// ─── Full HTML ────────────────────────────────────────────────────────────────

function buildHtml(
  profile: Profile,
  photoUri: string | null,
  summary: string,
  strengths: string[],
  lookingFor: string,
  education: Education,
  experiences: Experience[],
  skills: Skill[],
): string {
  const age = calcAge(profile.birth_year);
  const yearsInIT = new Date().getFullYear() - 2014;

  const photoHtml = photoUri
    ? `<img class="photo" src="${photoUri}" alt="">`
    : `<div class="photo-placeholder"></div>`;

  const summaryParas = summary
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => `<p>${esc(l)}</p>`)
    .join("");

  const strengthsHtml = strengths.length
    ? `<ul class="str-list">${strengths.map((s) => `<li>${esc(s)}</li>`).join("")}</ul>`
    : "";

  const chips = [
    `${yearsInIT}+ лет в IT`,
    "Kotlin · Java · TypeScript",
    "Low-code · SaaS · Fintech",
    "Команды 7–13 чел.",
  ]
    .map((c) => `<span class="chip">${c}</span>`)
    .join("");

  // Render "Что ищу" from data — single sentence per paragraph
  const lookingForHtml = lookingFor
    .split(/\.\s+/)
    .filter(Boolean)
    .map((s) => `<p>${esc(s.endsWith(".") ? s : s + ".")}</p>`)
    .join("");

  return /* html */ `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<style>
  /* ── Reset ── */
  * { box-sizing: border-box; margin: 0; padding: 0; }
  a { color: inherit; text-decoration: none; }

  /* ── Tokens ── */
  :root {
    --ink:     #0f172a;
    --blue:    #1d4ed8;
    --blue-bg: #eff6ff;
    --blue-bd: #bfdbfe;
    --text:    #1e293b;
    --sub:     #475569;
    --muted:   #64748b;
    --border:  #e2e8f0;
    --bg:      #f8fafc;
  }

  /* ── Page ── */
  body {
    font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
    font-size: 10pt;
    line-height: 1.5;
    color: var(--text);
    background: #fff;
  }

  /* ─────────────────────────── HEADER ─── */
  .hd {
    background: var(--ink);
    color: #fff;
    padding: 18px 20px 16px;
    display: flex;
    align-items: center;
    gap: 18px;
  }

  .photo {
    width: 78px; height: 78px;
    border-radius: 50%;
    object-fit: cover;
    border: 2.5px solid rgba(255,255,255,0.22);
    flex-shrink: 0;
  }
  .photo-placeholder {
    width: 78px; height: 78px;
    border-radius: 50%;
    background: rgba(255,255,255,0.07);
    border: 2px dashed rgba(255,255,255,0.18);
    flex-shrink: 0;
  }

  .hd-info { flex: 1; min-width: 0; }
  .hd-info h1 {
    font-size: 21pt; font-weight: 700;
    letter-spacing: -0.4px; line-height: 1.1;
    margin-bottom: 2px;
  }
  .hd-info .job-title {
    font-size: 10.5pt;
    color: #94a3b8;
    margin-bottom: 10px;
  }
  .contacts { display: flex; flex-wrap: wrap; gap: 3px 10px; font-size: 8.5pt; }
  .ci { color: #cbd5e1; }
  .ci a { color: #cbd5e1; }
  .csep { color: rgba(255,255,255,0.18); }

  .hd-meta {
    text-align: right;
    font-size: 9pt; color: #64748b;
    white-space: nowrap; line-height: 1.7;
    align-self: flex-start;
    padding-top: 2px;
  }

  /* ─────────────────────────── BODY LAYOUT ─── */
  .body { display: flex; }

  .main { flex: 1; min-width: 0; padding: 15px 18px 15px 18px; }

  .sidebar {
    width: 188px; flex-shrink: 0;
    padding: 15px 14px;
    background: var(--bg);
    border-left: 1px solid var(--border);
  }

  /* ─────────────────────────── SECTIONS ─── */
  .section { margin-bottom: 13px; }
  .section:last-child { margin-bottom: 0; }

  .sec-title {
    font-size: 7.5pt; font-weight: 700;
    text-transform: uppercase; letter-spacing: 1.5px;
    color: var(--blue);
    border-bottom: 1.5px solid var(--blue);
    padding-bottom: 3px; margin-bottom: 8px;
  }

  .sidebar .sec-title {
    font-size: 7pt; color: var(--ink);
    border-bottom-color: #cbd5e0;
  }

  /* ─── Summary ─── */
  .sum-text { color: var(--sub); font-size: 10pt; line-height: 1.55; }
  .sum-text p + p { margin-top: 3px; }

  .str-list {
    margin: 6px 0 0 14px;
    font-size: 9.5pt; color: var(--sub); line-height: 1.5;
  }
  .str-list li { margin-bottom: 1px; }

  .chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
  .chip {
    background: var(--blue-bg);
    border: 1px solid var(--blue-bd);
    color: var(--blue);
    font-size: 7.5pt; font-weight: 600;
    padding: 2px 8px; border-radius: 999px;
  }

  /* ─── Experience ─── */
  .exp {
    margin-bottom: 8px; padding-bottom: 8px;
    border-bottom: 1px solid var(--border);
    padding-left: 11px; position: relative;
  }
  .exp:last-child { border-bottom: none; margin-bottom: 0; padding-bottom: 0; }
  .exp::before {
    content: '';
    position: absolute; left: 0; top: 6px;
    width: 5px; height: 5px;
    border-radius: 50%; background: var(--blue);
  }

  .exp-row {
    display: flex; justify-content: space-between;
    align-items: baseline; gap: 6px;
  }
  .exp-left { display: flex; align-items: baseline; gap: 5px; flex: 1; min-width: 0; }
  .exp-co { font-weight: 700; font-size: 10.5pt; color: var(--ink); white-space: nowrap; }
  .exp-role { color: var(--muted); font-size: 9.5pt; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .exp-role::before { content: '— '; }
  .exp-period {
    color: var(--muted); font-size: 8.5pt; white-space: nowrap; flex-shrink: 0;
    background: #f1f5f9; padding: 1px 6px; border-radius: 4px; font-weight: 500;
  }
  .exp-desc { color: var(--sub); font-size: 9.5pt; line-height: 1.43; margin-top: 2px; }

  /* ─── Sidebar: Skills ─── */
  .skill-cat { margin-bottom: 7px; }
  .skill-cat-name { font-size: 8.5pt; font-weight: 700; color: var(--ink); margin-bottom: 3px; }
  .skill-tags { display: flex; flex-wrap: wrap; gap: 3px; }
  .st {
    font-size: 7.5pt; color: var(--sub);
    background: #fff; border: 1px solid var(--border);
    padding: 1px 5px; border-radius: 3px; line-height: 1.45;
  }

  /* ─── Sidebar: Education ─── */
  .edu-entry { margin-bottom: 5px; }
  .edu-deg { font-size: 8.5pt; font-weight: 700; display: block; }
  .edu-school { font-size: 8pt; color: var(--muted); display: block; }

  /* ─── Sidebar: Languages ─── */
  .lang-block { margin-top: 7px; border-top: 1px solid var(--border); padding-top: 6px; }
  .lang { display: flex; justify-content: space-between; font-size: 8.5pt; margin-bottom: 2px; }
  .lang-lvl { color: var(--muted); }

  /* ─── Sidebar: Looking for ─── */
  .looking {
    font-size: 8pt; color: var(--sub);
    line-height: 1.5; font-style: italic;
  }
  .looking p + p { margin-top: 3px; }
</style>
</head>
<body>

  <!-- ══════ HEADER ══════ -->
  <div class="hd">
    ${photoHtml}
    <div class="hd-info">
      <h1>${esc(profile.name)}</h1>
      <div class="job-title">${esc(profile.title)}</div>
      <div class="contacts">${renderContacts(profile.contacts)}</div>
    </div>
    <div class="hd-meta">
      ${age} лет<br>${esc(profile.location)}
    </div>
  </div>

  <!-- ══════ BODY ══════ -->
  <div class="body">

    <!-- ── MAIN ── -->
    <div class="main">

      <div class="section">
        <div class="sec-title">О себе</div>
        <div class="sum-text">${summaryParas}</div>
        ${strengthsHtml}
        <div class="chips">${chips}</div>
      </div>

      <div class="section">
        <div class="sec-title">Опыт работы</div>
        ${renderExperience(experiences)}
      </div>

    </div>

    <!-- ── SIDEBAR ── -->
    <div class="sidebar">

      <div class="section">
        <div class="sec-title">Компетенции</div>
        ${renderSkills(skills)}
      </div>

      <div class="section">
        <div class="sec-title">Образование · Языки</div>
        ${renderEducation(education)}
      </div>

      <div class="section">
        <div class="sec-title">Ищу</div>
        <div class="looking">${lookingForHtml}</div>
      </div>

    </div>

  </div>

</body>
</html>`;
}

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  console.log("[cv] Loading data...");

  const { meta: profile, summary, strengths, lookingFor } = loadProfile();
  const photoUri = loadPhoto(profile.photo);
  const education = loadEducation();
  const experiences = loadExperience();
  const skills = loadSkills();

  console.log(`[cv] ${experiences.length} experience entries, ${skills.length} skill categories`);

  const html = buildHtml(profile, photoUri, summary, strengths, lookingFor, education, experiences, skills);

  // Ensure output directory exists
  fs.mkdirSync(path.dirname(OUT), { recursive: true });

  console.log("[cv] Launching Chromium...");
  const browser = await puppeteer.launch({
    args: ["--no-sandbox", "--disable-setuid-sandbox"],
  });

  try {
    const page = await browser.newPage();
    await page.setContent(html, { waitUntil: "networkidle0" });
    await page.pdf({
      path: OUT,
      format: "A4",
      printBackground: true,
      margin: { top: "0", right: "0", bottom: "0", left: "0" },
    });
  } finally {
    await browser.close();
  }

  console.log(`[cv] Generated → ${path.relative(ROOT, OUT)}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
