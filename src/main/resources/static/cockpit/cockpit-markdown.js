((root, factory) => {
  const renderer = factory();
  if (typeof module === "object" && module.exports) module.exports = renderer;
  else root.MinikunMarkdown = renderer;
})(typeof globalThis !== "undefined" ? globalThis : window, () => {
  "use strict";

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#039;"
    })[character]);
  }

  function normalizeLine(line) {
    let normalized = line.replace(/\\$/, "");
    normalized = normalized.replace(/^(\s*)\\(?=#{1,6}\s|>{1}\s|[-*_]{3,}\s*$)/, "$1");
    if (/^\s*\\?\|.*\|\s*$/.test(normalized)) normalized = normalized.replace(/\\\|/g, "|");
    return normalized;
  }

  function normalize(value) {
    let fenced = false;
    return String(value ?? "")
      .replace(/\r\n?/g, "\n")
      .split("\n")
      .map((line) => {
        if (/^\s*```/.test(line)) {
          fenced = !fenced;
          return line;
        }
        return fenced ? line : normalizeLine(line);
      })
      .join("\n")
      .trim();
  }

  function renderInline(value) {
    const codeSpans = [];
    const links = [];
    let html = escapeHtml(value).replace(/`([^`\n]+)`/g, (_, code) => {
      const token = `\u0000CODE${codeSpans.length}\u0000`;
      codeSpans.push(`<code>${code}</code>`);
      return token;
    });
    html = html
      .replace(/\[([^\]]+)]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => {
        const token = `\u0000LINK${links.length}\u0000`;
        links.push(`<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`);
        return token;
      })
      .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
      .replace(/__([^_\n]+)__/g, "<strong>$1</strong>")
      .replace(/~~([^~\n]+)~~/g, "<del>$1</del>")
      .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>")
      .replace(/(^|[^_])_([^_\n]+)_(?!_)/g, "$1<em>$2</em>");
    html = links.reduce((result, link, index) => result.replace(`\u0000LINK${index}\u0000`, link), html);
    return codeSpans.reduce((result, block, index) => result.replace(`\u0000CODE${index}\u0000`, block), html);
  }

  function splitTableRow(line) {
    let value = line.trim().replace(/^\|/, "").replace(/\|$/, "");
    const cells = [];
    let cell = "";
    let escaped = false;
    for (const character of value) {
      if (escaped) {
        cell += character;
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === "|") {
        cells.push(cell.trim());
        cell = "";
      } else {
        cell += character;
      }
    }
    cells.push(cell.trim());
    return cells;
  }

  function tableAlignment(cell) {
    const value = cell.trim();
    if (/^:-{3,}:$/.test(value)) return "center";
    if (/^-{3,}:$/.test(value)) return "right";
    if (/^:-{3,}$/.test(value)) return "left";
    return "";
  }

  function isTableDivider(line) {
    const cells = splitTableRow(line);
    return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.trim()));
  }

  function isHorizontalRule(line) {
    return /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line);
  }

  function isBlockStart(lines, index) {
    const line = lines[index] || "";
    return /^\s*```/.test(line)
      || /^\s{0,3}#{1,6}\s+/.test(line)
      || isHorizontalRule(line)
      || /^\s*>\s?/.test(line)
      || /^\s*[-*+]\s+/.test(line)
      || /^\s*\d+[.)]\s+/.test(line)
      || (line.includes("|") && isTableDivider(lines[index + 1] || ""));
  }

  function renderTable(lines, start) {
    const headers = splitTableRow(lines[start]);
    const alignments = splitTableRow(lines[start + 1]).map(tableAlignment);
    const mobileLayout = headers.length <= 3 ? "cards" : "scroll";
    const rows = [];
    let index = start + 2;
    while (index < lines.length && lines[index].trim() && lines[index].includes("|")) {
      rows.push(splitTableRow(lines[index]));
      index += 1;
    }
    const cellStyle = (column) => alignments[column] ? ` style="text-align:${alignments[column]}"` : "";
    const head = headers.map((cell, column) => `<th${cellStyle(column)}>${renderInline(cell)}</th>`).join("");
    const body = rows.map((row) => `<tr>${headers.map((header, column) => `<td${cellStyle(column)} data-label="${escapeHtml(header)}">${renderInline(row[column] || "")}</td>`).join("")}</tr>`).join("");
    return {
      html: `<div class="markdown-table-wrap" data-mobile-layout="${mobileLayout}"><table data-mobile-layout="${mobileLayout}"><thead><tr>${head}</tr></thead>${body ? `<tbody>${body}</tbody>` : ""}</table></div>`,
      next: index
    };
  }

  function render(value) {
    const lines = normalize(value).split("\n");
    const output = [];
    let index = 0;
    while (index < lines.length) {
      const line = lines[index];
      if (!line.trim()) {
        index += 1;
        continue;
      }

      const fence = line.match(/^\s*```([\w.+-]*)\s*$/);
      if (fence) {
        const code = [];
        index += 1;
        while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) code.push(lines[index++]);
        if (index < lines.length) index += 1;
        output.push(`<div class="code-block"><header><span>${escapeHtml(fence[1] || "code")}</span><button type="button" data-copy-code>คัดลอก</button></header><pre><code>${escapeHtml(code.join("\n"))}</code></pre></div>`);
        continue;
      }

      const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/);
      if (heading) {
        const level = heading[1].length;
        output.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
        index += 1;
        continue;
      }

      if (isHorizontalRule(line)) {
        output.push("<hr>");
        index += 1;
        continue;
      }

      if (line.includes("|") && isTableDivider(lines[index + 1] || "")) {
        const table = renderTable(lines, index);
        output.push(table.html);
        index = table.next;
        continue;
      }

      if (/^\s*>\s?/.test(line)) {
        const quote = [];
        while (index < lines.length && /^\s*>\s?/.test(lines[index])) quote.push(lines[index++].replace(/^\s*>\s?/, ""));
        output.push(`<blockquote>${quote.map(renderInline).join("<br>")}</blockquote>`);
        continue;
      }

      const unordered = line.match(/^\s*[-*+]\s+(.+)/);
      if (unordered) {
        const items = [];
        while (index < lines.length) {
          const item = lines[index].match(/^\s*[-*+]\s+(.+)/);
          if (!item) break;
          items.push(`<li>${renderInline(item[1])}</li>`);
          index += 1;
        }
        output.push(`<ul>${items.join("")}</ul>`);
        continue;
      }

      const ordered = line.match(/^\s*\d+[.)]\s+(.+)/);
      if (ordered) {
        const items = [];
        while (index < lines.length) {
          const item = lines[index].match(/^\s*\d+[.)]\s+(.+)/);
          if (!item) break;
          items.push(`<li>${renderInline(item[1])}</li>`);
          index += 1;
        }
        output.push(`<ol>${items.join("")}</ol>`);
        continue;
      }

      const paragraph = [];
      while (index < lines.length && lines[index].trim() && (paragraph.length === 0 || !isBlockStart(lines, index))) {
        paragraph.push(lines[index++]);
      }
      output.push(`<p>${paragraph.map(renderInline).join("<br>")}</p>`);
    }
    return output.join("");
  }

  return { normalize, render };
});
