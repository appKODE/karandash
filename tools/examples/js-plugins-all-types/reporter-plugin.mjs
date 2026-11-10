export default {
  id: "js-reporter-plugin",
  name: "JS HTML Reporter Plugin",
  version: "1.0.0",
  type: "REPORTER",
  formatId: "html",
  isDefaultEnabled: false,

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    return true;
  },

  shutdown() {
    // no resources to release
  },

  defaultConfig() {
    return {
      title: "Karandash Validation Report",
      showPassed: true
    };
  },

  report(findings, passed, config) {
    const title = (config && config.title) || "Karandash Validation Report";
    const showPassed = config ? config.showPassed !== false : true;
    const status = passed ? "PASSED" : "FAILED";
    const statusColor = passed ? "#2e7d32" : "#c62828";

    const severityColor = {
      ERROR: "#c62828",
      WARNING: "#e65100",
      INFO: "#1565c0"
    };

    const rows = findings.map(f => {
      const color = severityColor[f.severity] || "#333";
      const location = f.location ? `${f.location.file}:${f.location.line}` : "-";
      return `
      <tr>
        <td style="color:${color};font-weight:bold;padding:8px 12px;border-bottom:1px solid #e0e0e0">${escapeHtml(f.severity)}</td>
        <td style="padding:8px 12px;border-bottom:1px solid #e0e0e0">${escapeHtml(f.rule)}</td>
        <td style="padding:8px 12px;border-bottom:1px solid #e0e0e0">${escapeHtml(f.message)}</td>
        <td style="padding:8px 12px;border-bottom:1px solid #e0e0e0;font-family:monospace;font-size:0.85em">${escapeHtml(location)}</td>
      </tr>`;
    }).join("");

    const noFindingsRow = findings.length === 0
      ? `<tr><td colspan="4" style="padding:16px 12px;text-align:center;color:#666">No findings</td></tr>`
      : "";

    const passedSection = showPassed
      ? `<p style="margin:0;font-size:0.9em;color:#666">${findings.length} finding(s) total</p>`
      : "";

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 0; padding: 24px; background: #f5f5f5; color: #212121; }
    .card { background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,.12); padding: 24px; max-width: 960px; margin: 0 auto; }
    h1 { margin: 0 0 8px; font-size: 1.5rem; }
    .badge { display: inline-block; padding: 4px 12px; border-radius: 4px; font-weight: bold; font-size: 0.9rem; color: #fff; background: ${statusColor}; margin-bottom: 16px; }
    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
    thead th { text-align: left; padding: 8px 12px; background: #f5f5f5; font-size: 0.85rem; text-transform: uppercase; letter-spacing: .05em; border-bottom: 2px solid #e0e0e0; }
  </style>
</head>
<body>
  <div class="card">
    <h1>${escapeHtml(title)}</h1>
    <span class="badge">${status}</span>
    ${passedSection}
    <table>
      <thead>
        <tr>
          <th>Severity</th>
          <th>Rule</th>
          <th>Message</th>
          <th>Location</th>
        </tr>
      </thead>
      <tbody>
        ${rows}
        ${noFindingsRow}
      </tbody>
    </table>
  </div>
</body>
</html>`;
  }
};

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
