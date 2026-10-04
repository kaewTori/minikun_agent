const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('Pony history pages three rows without changing the selected score', () => {
  const source = fs.readFileSync('src/main/resources/static/cockpit/cockpit.js', 'utf8');
  const start = source.indexOf('  function renderStudioHistory()');
  const end = source.indexOf('\n  async function loadStudioHistory()', start);
  const nodes = new Map();
  const node = (tag = '', className = '', textContent = '') => ({
    tag, className, textContent, children: [], disabled: false,
    classList: { toggle() {} },
    replaceChildren(...children) { this.children = children; },
    append(...children) { this.children.push(...children); },
    setAttribute(name, value) { this[name] = value; },
    addEventListener(name, callback) { this[name] = callback; },
    scrollIntoView() {}
  });
  const $ = selector => {
    if (!nodes.has(selector)) nodes.set(selector, node());
    return nodes.get(selector);
  };
  const history = Array.from({ length: 6 }, (_, index) => ({
    id: String(index), title: `Prompt ${index}`, mode: 'manual',
    image_url: '/image.png', created_at: '2026-09-30T00:00:00Z',
    prompt: `score_9, ${'very long prompt, '.repeat(80)}${index}`,
    review: { score: 90 - index, good: 'Good', tip: 'Tip' }
  }));
  const context = {
    state: { studio: { history, historyPage: 0, selectedHistoryId: '' } },
    $, element: node, matchMedia: () => ({ matches: true })
  };
  vm.createContext(context);
  vm.runInContext(`${source.slice(start, end)}; this.renderStudioHistory = renderStudioHistory;`, context);

  context.renderStudioHistory();
  assert.equal($('#studio-history-list').children.length, 3);
  assert.equal($('#studio-review-score').children[0], '90');
  assert.equal($('#studio-history-page-label').textContent, '1 / 2');

  context.state.studio.historyPage = 1;
  context.renderStudioHistory();
  assert.equal($('#studio-history-list').children.length, 3);
  assert.equal($('#studio-history-page-label').textContent, '2 / 2');
  assert.equal($('#studio-review-score').children[0], '90');
  assert.equal($('#studio-review-prompt').textContent, history[0].prompt);

  $('#studio-history-list').children[0].click();
  assert.equal(context.state.studio.selectedHistoryId, '3');
  assert.equal($('#studio-review-score').children[0], '87');
});
