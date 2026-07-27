import assert from 'node:assert/strict'
import { renderMarkdown } from './markdown.js'

// HTML in user content must always be escaped: the result is injected with v-html
const escaped = renderMarkdown('<script>alert(1)</script>')
assert.ok(!escaped.includes('<script>'), 'raw script tag must be escaped')
assert.ok(escaped.includes('&lt;script&gt;'))

const imgEscaped = renderMarkdown('<img src=x onerror=alert(1)>')
assert.ok(!imgEscaped.includes('<img'), 'raw img tag must be escaped')

// Plain URLs are linked exactly once, with safe rel attributes
const autolinked = renderMarkdown('see https://example.com/a?b=1 end')
assert.equal((autolinked.match(/<a /g) || []).length, 1)
assert.ok(autolinked.includes('rel="noopener noreferrer"'))

// A markdown link must not be linked a second time by the autolinker.
// This is the case the removed lookbehind used to handle - the replacement must
// behave identically while still parsing on Safari below 16.4.
const markdownLink = renderMarkdown('[label](https://example.com/x)')
assert.equal((markdownLink.match(/<a /g) || []).length, 1)
assert.ok(markdownLink.includes('>label</a>'))

// Mixed: one markdown link plus one bare URL means exactly two anchors
const mixed = renderMarkdown('[label](https://example.com/x) and https://plain.dev')
assert.equal((mixed.match(/<a /g) || []).length, 2)

// URLs inside code spans stay literal
const inCode = renderMarkdown('`curl https://in.code`')
assert.ok(inCode.includes('<code>curl https://in.code</code>'))
assert.equal((inCode.match(/<a /g) || []).length, 0)

// javascript: URLs are never turned into links
const jsUrl = renderMarkdown('javascript:alert(1)')
assert.equal((jsUrl.match(/<a /g) || []).length, 0)

// Structure still works: headings, lists, quotes, tables, fenced code
assert.ok(renderMarkdown('# Title').includes('<h1>Title</h1>'))
assert.ok(renderMarkdown('- one\n- two').includes('<li>one</li>'))
assert.ok(renderMarkdown('> quoted').includes('<blockquote>quoted</blockquote>'))
assert.ok(renderMarkdown('| a | b |\n| - | - |\n| 1 | 2 |').includes('<table>'))
assert.ok(renderMarkdown('```\ncode\n```').includes('<pre><code>'))

// Empty and nullish input must not throw
assert.equal(renderMarkdown(''), '')
assert.equal(renderMarkdown(undefined), '')
assert.equal(renderMarkdown(null), '')

console.log('markdown.spec.mjs: all assertions passed')
