const escapeHtml = (value = '') => String(value)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const inlinePatterns = (value) => {
  let result = value
  result = result.replace(/`([^`]+)`/g, '<code>$1</code>')
  result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  result = result.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  result = result.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  return result
}

const autoLinkUrls = (value) =>
  value.replace(/(?<![">])(https?:\/\/[^\s<>"'&]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>')

const renderInline = (value) => {
  let result = escapeHtml(value)
  result = inlinePatterns(result)
  result = autoLinkUrls(result)
  return result
}

const parseRowCells = (row) =>
  row
    .replace(/^\||\|$/g, '')
    .split('|')
    .map((cell) => cell.trim())

const isTableSeparator = (cells) =>
  cells.length > 0 && cells.every((cell) => /^[-: ]+$/.test(cell))

const flushTable = (rows, html) => {
  if (!rows || !rows.length) return
  const parsed = rows.map(parseRowCells)
  let headerCells = []
  let bodyRows = parsed

  if (parsed.length > 1 && isTableSeparator(parsed[1])) {
    headerCells = parsed[0]
    bodyRows = parsed.slice(2)
  }

  const renderCells = (cells, tag) =>
    cells.map((cell) => `<${tag}>${renderInline(cell)}</${tag}>`).join('')

  html.push('<table>')
  if (headerCells.length) {
    html.push(`<thead><tr>${renderCells(headerCells, 'th')}</tr></thead>`)
  }
  if (bodyRows.length) {
    html.push('<tbody>')
    bodyRows.forEach((cells) => {
      html.push(`<tr>${renderCells(cells, 'td')}</tr>`)
    })
    html.push('</tbody>')
  }
  html.push('</table>')
}

export const renderMarkdown = (content = '') => {
  const lines = String(content || '').replace(/\r\n/g, '\n').split('\n')
  const html = []
  let inCode = false
  let listType = null
  let paragraph = []
  let tableRows = null

  const flushParagraph = () => {
    if (paragraph.length) {
      html.push(`<p>${renderInline(paragraph.join(' '))}</p>`)
      paragraph = []
    }
  }

  const closeList = () => {
    if (listType) {
      html.push(`</${listType}>`)
      listType = null
    }
  }

  lines.forEach((line) => {
    if (line.trim().startsWith('```')) {
      flushParagraph()
      closeList()
      flushTable(tableRows, html)
      tableRows = null
      if (inCode) {
        html.push('</code></pre>')
      } else {
        html.push('<pre><code>')
      }
      inCode = !inCode
      return
    }

    if (inCode) {
      html.push(`${escapeHtml(line)}\n`)
      return
    }

    const trimmed = line.trim()
    if (!trimmed) {
      flushParagraph()
      closeList()
      flushTable(tableRows, html)
      tableRows = null
      return
    }

    if (/^\s*[-*_]{3,}\s*$/.test(trimmed)) {
      flushParagraph()
      closeList()
      flushTable(tableRows, html)
      tableRows = null
      html.push('<hr>')
      return
    }

    if (tableRows !== null) {
      if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
        tableRows.push(trimmed)
        return
      }
      flushTable(tableRows, html)
      tableRows = null
    }

    if (trimmed.startsWith('|') && trimmed.endsWith('|')) {
      flushParagraph()
      closeList()
      tableRows = [trimmed]
      return
    }

    const heading = trimmed.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      flushParagraph()
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      return
    }

    const quote = trimmed.match(/^>\s?(.+)$/)
    if (quote) {
      flushParagraph()
      closeList()
      html.push(`<blockquote>${renderInline(quote[1])}</blockquote>`)
      return
    }

    const ulItem = trimmed.match(/^[-*]\s+(.+)$/)
    if (ulItem) {
      flushParagraph()
      if (listType !== 'ul') {
        closeList()
        html.push('<ul>')
        listType = 'ul'
      }
      html.push(`<li>${renderInline(ulItem[1])}</li>`)
      return
    }

    const olItem = trimmed.match(/^\d+\.\s+(.+)$/)
    if (olItem) {
      flushParagraph()
      if (listType !== 'ol') {
        closeList()
        html.push('<ol>')
        listType = 'ol'
      }
      html.push(`<li>${renderInline(olItem[1])}</li>`)
      return
    }

    closeList()
    paragraph.push(trimmed)
  })

  flushParagraph()
  closeList()
  flushTable(tableRows, html)
  if (inCode) {
    html.push('</code></pre>')
  }

  return html.join('')
}
