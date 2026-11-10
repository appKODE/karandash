import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { execFileSync } from 'child_process';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    Executable,
    RevealOutputChannelOn
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;

export function activate(context: vscode.ExtensionContext): void {
    console.log('Karandash extension is activating...');

    try {
        // A configured or locally built jar wins; otherwise `karandash lsp` from the CLI distribution
        const lspJarPath = findLspJar();
        if (lspJarPath) {
            if (!isJavaAvailable()) {
                handleMissingJava();
                return;
            }
            startLanguageServer(context, getJavaCommand(), serverArgs(lspJarPath));
        } else if (isCliAvailable()) {
            const lspError = probeFailure(getCliCommand(), ['lsp', '--help']);
            if (lspError) {
                vscode.window.showErrorMessage(
                    `'${getCliCommand()}' has no working 'lsp' command (a native binary does not serve the language server; use the JVM distribution): ${lspError}`
                );
                return;
            }
            startLanguageServer(context, getCliCommand(), ['lsp']);
        } else {
            handleMissingLspJar();
            return;
        }
        console.log('Karandash extension activated successfully');
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        vscode.window.showErrorMessage(`Failed to activate Karandash extension: ${message}`);
        console.error('Activation error:', error);
    }
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

/**
 * Find the karandash-lsp.jar file in standard locations
 */
function findLspJar(): string | null {
    const config = vscode.workspace.getConfiguration('karandash');
    const customPath = config.get<string | null>('lspServerPath');
    if (customPath) {
        if (fs.existsSync(customPath)) {
            console.log(`Using configured LSP path: ${customPath}`);
            return customPath;
        }
        vscode.window.showWarningMessage(
            `Configured karandash.lspServerPath not found: ${customPath}. Using auto-detection.`
        );
    }

    const searchPaths = [
        // Workspace location
        path.join(vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '', '.karandash', 'lsp', 'karandash-lsp.jar'),
        // User home location
        path.join(os.homedir(), '.karandash', 'lsp', 'karandash-lsp.jar'),
        // Built from source in a Karandash checkout (`./gradlew :dsl:lsp-server:shadowJar`)
        path.join(vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '', 'dsl', 'lsp-server', 'build', 'libs', 'karandash-lsp.jar'),
    ];

    for (const jarPath of searchPaths) {
        if (fs.existsSync(jarPath)) {
            console.log(`Found LSP JAR at: ${jarPath}`);
            return jarPath;
        }
    }

    console.error('LSP JAR not found in any standard location');
    return null;
}

/**
 * The karandash command from settings or default
 */
function getCliCommand(): string {
    return vscode.workspace.getConfiguration('karandash').get<string>('cliPath') || 'karandash';
}

/**
 * Run `command args` without a shell (a setting value is never interpolated into a command line); null when it
 * exits 0, otherwise what it printed on stderr (or the spawn error).
 */
function probeFailure(command: string, args: string[]): string | null {
    try {
        execFileSync(command, args, { stdio: 'pipe' });
        return null;
    } catch (error) {
        const e = error as { stderr?: Buffer | string; message?: string };
        const stderr = e.stderr ? e.stderr.toString().trim() : '';
        return stderr || e.message || String(error);
    }
}

/**
 * Check that `karandash` runs (it must be the JVM distribution to serve `lsp`)
 */
function isCliAvailable(): boolean {
    return probeFailure(getCliCommand(), ['--version']) === null;
}

/**
 * karandash.jar takes the lsp subcommand; karandash-lsp.jar is the server itself
 */
function serverArgs(jarPath: string): string[] {
    return path.basename(jarPath) === 'karandash-lsp.jar' ? ['-jar', jarPath] : ['-jar', jarPath, 'lsp'];
}

/**
 * Get the Java command from settings or default
 */
function getJavaCommand(): string {
    const config = vscode.workspace.getConfiguration('karandash');
    return config.get<string>('javaPath') || 'java';
}

/**
 * Check if Java is available on the system
 */
function isJavaAvailable(): boolean {
    return probeFailure(getJavaCommand(), ['-version']) === null;
}

/**
 * Handle missing LSP JAR scenario
 */
function handleMissingLspJar(): void {
    const message = 'Karandash language server not found: install the karandash command (JVM distribution), set karandash.cliPath, or build the server jar.';
    const buildAction = 'Build LSP Server';
    const installAction = 'Show Instructions';

    vscode.window.showErrorMessage(message, buildAction, installAction).then(action => {
        if (action === buildAction) {
            buildLspJar();
        } else if (action === installAction) {
            showInstallInstructions();
        }
    });
}

/**
 * Handle missing Java scenario
 */
function handleMissingJava(): void {
    const message = 'Java is required to run the Karandash LSP server but was not found.';
    const downloadAction = 'Download Java';

    vscode.window.showErrorMessage(message, downloadAction).then(action => {
        if (action === downloadAction) {
            vscode.env.openExternal(vscode.Uri.parse('https://adoptium.net/'));
        }
    });
}

/**
 * Attempt to build the LSP JAR using Gradle
 */
function buildLspJar(): void {
    const terminal = vscode.window.createTerminal('Karandash LSP Build');
    terminal.show();
    terminal.sendText('./gradlew :dsl:lsp-server:shadowJar');

    vscode.window.showInformationMessage(
        'Building LSP server... Check the terminal for progress. Reload the window when complete.',
        'Reload Window'
    ).then(action => {
        if (action === 'Reload Window') {
            vscode.commands.executeCommand('workbench.action.reloadWindow');
        }
    });
}

/**
 * Show installation instructions
 */
function showInstallInstructions(): void {
    const instructions = `# Karandash LSP Server Setup

## Building from Source

1. Navigate to the Karandash project root
2. Run: \`./gradlew :dsl:lsp-server:shadowJar\`
3. The JAR will be built at: \`dsl/lsp-server/build/libs/karandash-lsp.jar\` (found there when the checkout is the open workspace)

## Installation Locations

Copy the JAR to one of these locations:

- **Workspace**: \`.karandash/lsp/karandash-lsp.jar\`
- **User Home**: \`~/.karandash/lsp/karandash-lsp.jar\`

## Requirements

- Java 17 or higher
- Gradle (included via gradlew)

Reload the VS Code window after installation.
`;

    const panel = vscode.window.createWebviewPanel(
        'karandashSetup',
        'Karandash Setup Instructions',
        vscode.ViewColumn.One,
        {}
    );

    panel.webview.html = `
        <!DOCTYPE html>
        <html>
        <head>
            <style>
                body { font-family: var(--vscode-font-family); padding: 20px; }
                pre { background: var(--vscode-textCodeBlock-background); padding: 10px; border-radius: 4px; }
                code { font-family: var(--vscode-editor-font-family); }
                h1, h2 { color: var(--vscode-foreground); }
            </style>
        </head>
        <body>
            <pre>${instructions}</pre>
        </body>
        </html>
    `;
}

/**
 * Start the Language Server
 */
function startLanguageServer(context: vscode.ExtensionContext, command: string, args: string[]): void {
    // Define the server launch command
    const executable: Executable = {
        command,
        args,
        options: {
            env: {
                PATH: process.env.PATH,
                JAVA_HOME: process.env.JAVA_HOME,
                HOME: process.env.HOME,
                USERPROFILE: process.env.USERPROFILE,
                TMPDIR: process.env.TMPDIR,
                TEMP: process.env.TEMP,
                TMP: process.env.TMP,
                LANG: process.env.LANG,
                HTTP_PROXY: process.env.HTTP_PROXY,
                HTTPS_PROXY: process.env.HTTPS_PROXY,
                NO_PROXY: process.env.NO_PROXY,
            }
        }
    };

    const serverOptions: ServerOptions = executable;

    // Get trace configuration
    const config = vscode.workspace.getConfiguration('karandash');
    const traceServer = config.get<string>('trace.server') || 'off';

    // Define client options
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'karandash' }
        ],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('{**/karandash.config.kts,**/.karandash/config.kts,**/.karandash/*.kts}')
        },
        outputChannelName: 'Karandash Language Server',
        revealOutputChannelOn: RevealOutputChannelOn.Never,
        initializationOptions: {
            trace: traceServer
        }
    };

    // Create and start the client
    client = new LanguageClient(
        'karandashLsp',
        'Karandash Language Server',
        serverOptions,
        clientOptions
    );

    // Start the client (this will also launch the server)
    client.start().then(
        () => {
            console.log('Karandash LSP client started successfully');
            vscode.window.setStatusBarMessage('$(check) Karandash LSP ready', 3000);
        },
        (error) => {
            console.error('Failed to start LSP client:', error);
            vscode.window.showErrorMessage(`Failed to start Karandash LSP: ${error.message}`);
        }
    );

    context.subscriptions.push(client);
}
