export const GITHUB = {
  owner: "knyazevs",
  repo: "knyazevs",
  branch: "main",
};

// Файлы, которые исключаются из навигации
export const EXCLUDE_FILES = ["how-to.md"];

// Человекочитаемые названия для директорий
export const DIR_LABELS: Record<string, string> = {
  adr: "Архитектурные решения",
  diagrams: "Диаграммы",
  profile: "Профиль",
  experience: "Опыт",
  projects: "Проекты",
  skills: "Компетенции",
};

// Человекочитаемые названия для конкретных файлов (без расширения)
export const FILE_LABELS: Record<string, string> = {
  "index": "Обо мне",
  "01-principles": "Принципы",
  "02-lessons": "Уроки и провалы",
  "03-industry-views": "Взгляды на индустрию",
  "04-education": "Образование",
};
