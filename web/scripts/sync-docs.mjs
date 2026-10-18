// Copia a documentação do repositório (docs/*.md) para dentro do site, com o
// frontmatter que o Astro precisa. A fonte é uma só: os MDs versionados.
//
// A ordem define a sequência na página de documentação.
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const source = resolve(root, 'docs');
const target = resolve(here, '../src/content/docs');
const REPO = 'https://github.com/ferforastieri/valky/blob/main';

const ORDER = [
  'instalacao.md',
  'arquitetura.md',
  'api.md',
  'recursos.md',
  'seguranca.md',
  'operacao.md',
  'desenvolvimento.md',
];

// Links relativos dos MDs apontam para arquivos do repositório; no site eles
// precisam ir para o GitHub.
function toAbsoluteLinks(markdown) {
  return markdown.replace(/\]\((?!https?:|#|mailto:)([^)]+)\)/g, (_match, path) => `](${REPO}/${path.replace(/^\.\//, '')})`);
}

function frontmatter(title, order) {
  return `---\ntitle: ${JSON.stringify(title)}\norder: ${order}\n---\n`;
}

function titleOf(markdown, fallback) {
  const heading = markdown.match(/^#\s+(.+)$/m);
  return heading ? heading[1].trim() : fallback;
}

// O título do arquivo já aparece no cabeçalho da seção: tira o H1 do corpo.
function withoutTitle(markdown) {
  return markdown.replace(/^#\s+.+\n/, '');
}

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });

const files = await readdir(source);
const chosen = ORDER.filter((file) => files.includes(file));
const extra = files.filter((file) => file.endsWith('.md') && !chosen.includes(file)).sort();

for (const [index, file] of [...chosen, ...extra].entries()) {
  const markdown = await readFile(resolve(source, file), 'utf8');
  const body = toAbsoluteLinks(withoutTitle(markdown));
  const title = titleOf(markdown, file.replace(/\.md$/, ''));
  await writeFile(resolve(target, file), frontmatter(title, index + 1) + body);
}

console.log(`docs: ${chosen.length + extra.length} arquivo(s) sincronizado(s) de docs/`);
