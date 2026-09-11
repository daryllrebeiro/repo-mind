import * as vscode from 'vscode';
import { spawn } from 'child_process';
import * as path from 'path';

let diagnosticCollection: vscode.DiagnosticCollection;

export function activate(context: vscode.ExtensionContext) {
  diagnosticCollection = vscode.languages.createDiagnosticCollection('repomind');
  context.subscriptions.push(diagnosticCollection);

  const indexCommand = vscode.commands.registerCommand('repomind.indexWorkspace', async () => {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      vscode.window.showWarningMessage('RepoMind: No workspace folder open to index.');
      return;
    }

    const rootPath = workspaceFolders[0].uri.fsPath;
    await vscode.window.withProgress({
      location: vscode.ProgressLocation.Notification,
      title: 'RepoMind: Indexing workspace...',
      cancellable: false,
    }, async () => {
      try {
        await runRepomindCli(['index', rootPath]);
        vscode.window.showInformationMessage('RepoMind: Workspace indexed successfully.');
      } catch (err: any) {
        vscode.window.showErrorMessage(`RepoMind Index Error: ${err.message}`);
      }
    });
  });

  const impactCommand = vscode.commands.registerCommand('repomind.showImpact', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      vscode.window.showWarningMessage('RepoMind: Open a source file to analyze symbol impact.');
      return;
    }

    const document = editor.document;
    const position = editor.selection.active;
    const wordRange = document.getWordRangeAtPosition(position);
    const symbol = wordRange ? document.getText(wordRange) : '';

    if (!symbol) {
      vscode.window.showWarningMessage('RepoMind: Place the cursor over a class, method, or interface symbol.');
      return;
    }

    const workspaceFolders = vscode.workspace.workspaceFolders;
    const rootPath = workspaceFolders ? workspaceFolders[0].uri.fsPath : path.dirname(document.uri.fsPath);

    try {
      const output = await runRepomindCli(['query', 'impact', symbol, '--repo', rootPath]);
      showImpactPanel(symbol, output);
    } catch (err: any) {
      vscode.window.showErrorMessage(`RepoMind Impact Error: ${err.message}`);
    }
  });

  const checkRulesCommand = vscode.commands.registerCommand('repomind.checkArchitectureRules', async () => {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) return;

    const rootPath = workspaceFolders[0].uri.fsPath;
    try {
      const output = await runRepomindCli(['rules', 'check', '--repo', rootPath, '--json']);
      parseAndShowDiagnostics(output, rootPath);
      vscode.window.showInformationMessage('RepoMind: Architecture rules verified.');
    } catch (err: any) {
      vscode.window.showWarningMessage(`RepoMind Architecture Check: ${err.message}`);
    }
  });

  context.subscriptions.push(indexCommand, impactCommand, checkRulesCommand);
}

function runRepomindCli(args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const config = vscode.workspace.getConfiguration('repomind');
    const cliPath = config.get<string>('cliPath') || 'repomind';

    const child = spawn(cliPath, args);
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', (code) => {
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(stderr || stdout || `Exited with code ${code}`));
      }
    });

    child.on('error', (err) => {
      reject(err);
    });
  });
}

function showImpactPanel(symbol: string, details: string) {
  const panel = vscode.window.createWebviewPanel(
    'repomindImpact',
    `RepoMind Impact: ${symbol}`,
    vscode.ViewColumn.Beside,
    { enableScripts: true }
  );

  panel.webview.html = `
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 16px; line-height: 1.5; color: var(--vscode-editor-foreground); background-color: var(--vscode-editor-background); }
        pre { background: var(--vscode-textCodeBlock-background); padding: 12px; border-radius: 6px; overflow-x: auto; }
        h2 { color: var(--vscode-textLink-foreground); }
      </style>
    </head>
    <body>
      <h2>Symbol Impact Analysis: ${symbol}</h2>
      <pre>${escapeHtml(details)}</pre>
    </body>
    </html>
  `;
}

function parseAndShowDiagnostics(jsonOutput: string, rootPath: string) {
  diagnosticCollection.clear();
  try {
    const report = JSON.parse(jsonOutput);
    if (!report.violations || !Array.isArray(report.violations)) return;

    const fileMap = new Map<string, vscode.Diagnostic[]>();

    for (const v of report.violations) {
      const filePath = v.filePath ? path.resolve(rootPath, v.filePath) : '';
      if (!filePath) continue;

      const line = (v.line && v.line > 0) ? v.line - 1 : 0;
      const range = new vscode.Range(line, 0, line, 100);
      const message = `[${v.rule}] ${v.sourceFqn} -> ${v.targetFqn} (${v.edgeKind}): ${v.message || 'Architecture rule violation'}`;
      const diag = new vscode.Diagnostic(range, message, vscode.DiagnosticSeverity.Error);

      const list = fileMap.get(filePath) || [];
      list.push(diag);
      fileMap.set(filePath, list);
    }

    for (const [filePath, diags] of fileMap.entries()) {
      diagnosticCollection.set(vscode.Uri.file(filePath), diags);
    }
  } catch (_e) {
    // Non-fatal if output was plain text
  }
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function deactivate() {
  if (diagnosticCollection) {
    diagnosticCollection.clear();
  }
}
