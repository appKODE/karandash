# Templates

The human-facing formats (`markdown`, `html`, `slack`, and the `template` format) render one report view through
logic-less [Mustache](https://mustache.github.io/mustache.5.html) templates. Every one of them ships a bundled template;
a config can replace it. JSON, SARIF, JUnit and rdjson are built in code and take no template.

## Overriding a bundled template

<!-- docs-test: skip -->
```yaml
reporters:
  markdown:
    template: { file: templates/summary.mustache }
    partials:
      row: { file: templates/row.mustache }
```

- `template` replaces the bundled layout of the `standard` and `quiet` styles (`verbose` keeps the full built-in layout).
- `partials.<name>` is included as `{{>name}}`.
- `{ file: ... }` paths are relative to the config file. A plain string works too.

How `{{name}}` is escaped depends on the format:

| Format | `{{x}}` escaping | Result |
|--------|------------------|--------|
| `markdown` | Markdown (one line, `|`, `[`, `#`, `@`, `<` … neutralised) | the MR summary note |
| `html` | HTML | the page and the email body |
| `slack` | Slack mrkdwn (`& < >`) | one mrkdwn section block |
| `template` | `escape:` key: `none` (default), `md` (or `markdown`), `html`, `slack`, `json`, any case; `format render --escape` takes the same names | the body as is |

`{{{x}}}` inserts a value unescaped. Four section lambdas escape their rendered content: `{{#md}}…{{/md}}`,
`{{#html}}…{{/html}}`, `{{#slack}}…{{/slack}}`, `{{#json}}…{{/json}}`.

A template that does not parse or fails to render is logged at warn and the bundled one is used, so a broken template
never loses a report. Output is capped at 1 000 000 characters. Templates see only the report view: no environment
variables, files or helpers.

## A custom format

The `template` format has no bundled layout of its own beyond a plain-text fallback, so it is the way to produce any
other text (a chat message, a CSV, a custom JSON):

<!-- docs-test: skip -->
```yaml
reporters:
  template:
    template: { file: templates/teams.json.mustache }
    escape: json
    maxFindings: 10
```

It also takes `language`, `style` and `mr`, like the other formats.

## The data a template sees

`karandash format schema` prints the template data of a built-in example report. The main keys:

- `passed`, `verdict` (`Failed`/`Passed`, localized), `headline`, `version`, `runNumber` (when known), `footer`
- `counts` (`errors`, `warnings`, `infos`, `total`, `text`)
- `delta` (`new`, `fixed`, `unchanged`, `text`): only when a baseline exists
- `mr` (`ref`, `title`, `url`, `lineLinks`: `path:line` to the URL of that line in the MR): only when an MR was fetched
- `findings[]`: `severity` (`error`/`warning`/`info`), the flags `error`, `warning`, `info`, `icon`, `tag`
  (`error`/`warn`/`info`), `rule`, `message`, `where`, `shortWhere`, `path`, `line`, `link`, `fix`, `why`,
  `fingerprint`
- `more`: the number of findings left out by `maxFindings`
- `files[]`: `name` and `findings[]` (the same rows), the merge request first, then each file
- `details`: `risk`, `skipped[]` (`rule`, `reason`), `passedRules[]`, `aiCoverage`, `fixed`, `lines[]`, `any`
- `labels`: localized fixed labels (`where`, `finding`, `fix`, `why`, `details`, `more`, `rule`)

## Trying a template

```bash
karandash format render --template summary.mustache --partial row=row.mustache --escape md
```

`format render` fills the template with the same example report and prints the result. A template error is printed and
exits with code 2, instead of falling back to the bundled layout.
